# Coroutines 遇见 Swift Concurrency：一条 Flow 过桥后，为什么忘了怎么取消？

> 面向读者：熟悉 Kotlin Coroutines、第一次把共享异步 API 交给 Swift 使用的 Android 开发者  
> 技术核对与实验验证日期：2026-11-19  
> 实验版本：Kotlin 2.4.21、kotlinx.coroutines 1.11.0、Xcode 26.5、Swift 6.3.2

PocketLab 的离线数据层终于像一个真正的移动 App 了：

```text
Ktor refresh → SQLDelight → Flow<List<ExperimentRecord>> → UI
```

Android 同事看了一眼，顺手写下：

```kotlin
viewModelScope.launch {
    repository.observeAll().collect { experiments ->
        // render
    }
}
```

iOS 同事拿到 framework，在 Xcode 里输入 `observeAll()`，自动补全告诉他返回值是：

```swift
any Kotlinx_coroutines_coreFlow
```

他沉默了一会儿，问了一个非常合理的问题：

> “所以，我应该怎么 `for await`？”

更麻烦的是，Kotlin 的 `suspend fun refresh()` 在 Swift 里居然真的可以 `try await`。大家刚准备庆祝，第二个问题来了：

> “用户离开页面、Swift Task 被取消时，Kotlin 的网络请求也停了吗？”

答案不是一个简单的“会”或“不会”。

Kotlin Coroutines 和 Swift Concurrency 都有结构化并发、取消、异步序列和错误传播，但相似的词不代表共享同一棵任务树。跨过 Kotlin/Native 边界之后，我们必须重新建立这些关系。

这一篇不满足于让代码“能从 Swift 调到”。我们会让 Kotlin `Job` 和 Swift `Task` 真正握手：

- 用真实 framework header 看清 `suspend` 与 `Flow` 的导出形状；
- 保留共同层的 Flow / suspend，不让 FFI 反向污染架构；
- 在 Apple 边界提供显式 cancellation token；
- 用 Swift `AsyncStream` 恢复 `AsyncSequence`；
- 用 `onTermination` 把 Task 取消传播给 Job；
- 在 Swift 6 完整严格并发检查下编译验证；
- 最后看看 2026 年的 Swift Export 已经把未来推进到哪里。

## 两边都有“结构化并发”，为什么还要桥？

先把两边熟悉的词摆在一起：

| Kotlin | Swift | 看起来像同一件事吗 |
|---|---|---:|
| `suspend fun` | `async func` | 很像 |
| `Job` | `Task` | 很像 |
| `Flow<T>` | `AsyncSequence` | 很像 |
| `CancellationException` | `CancellationError` | 很像 |
| `CoroutineScope` | Task hierarchy | 很像 |
| `Dispatchers.Main` | `@MainActor` | 很像 |

危险恰恰来自“很像”。

Kotlin `Job` 的父子关系由 CoroutineContext 建立；Swift `Task` 的父子关系由 Swift runtime 建立。它们各自知道怎么取消自己的孩子，却不会因为 API 名字相似就自动认识对方。

真实结构更像：

```text
Kotlin runtime                         Swift runtime

SupervisorJob                         SwiftUI .task
├── collect Flow Job                  ├── for await
└── refresh Job                       └── await refresh

             互操作边界
      这里必须显式传递取消信号
```

如果边界只传递了“开始”和“结果”，却没有传递“取消”，那就会出现经典幽灵任务：

1. 用户打开列表页，开始收集 Flow。
2. 用户返回上一页，Swift Task 已结束。
3. Kotlin collection 仍然存活。
4. 数据库继续通知一个已经不存在的页面。
5. 再进入页面，又创建第二个 collection。

这不是语法问题，而是资源所有权问题。

## 不猜 API，先生成 framework

PocketLab 为 `iosX64` target 增加一个静态 framework：

```kotlin
iosX64 {
    binaries.framework {
        baseName = "PocketLabShared"
        isStatic = true
    }
}
```

然后执行：

```shell
./gradlew :shared:linkDebugFrameworkIosX64
```

Kotlin/Native 生成：

```text
PocketLabShared.framework
├── Headers/PocketLabShared.h
├── Modules/module.modulemap
├── PocketLabShared
└── Info.plist
```

对 Swift 互操作而言，Kotlin 源码只是输入，header 才是标准 Objective-C 导出路径下的边界事实。

### suspend 到底变成了什么？

共同层有：

```kotlin
class RefreshExperimentsUseCase {
    suspend fun execute(): RefreshExperimentsResult
}
```

生成 header 中是：

```objc
- (void)executeWithCompletionHandler:
    (void (^)(id<PLSRefreshExperimentsResult> _Nullable,
              NSError * _Nullable))completionHandler;
```

ABI 仍然是 completion handler。

从 Swift 5.5 开始，Clang/Swift importer 可以把符合规则的 completion-handler API 额外呈现为 async，所以这段 Swift 6 代码确实通过了编译：

```swift
func refreshDirectly(
    with useCase: RefreshExperimentsUseCase
) async throws -> any RefreshExperimentsResult {
    try await useCase.execute()
}
```

注意我们证明的是：

> Swift 可以用 async 语法调用它。

我们还没有证明：

> Swift Task 取消会可靠取消底层 Kotlin coroutine。

“能够 await”与“共享取消树”是两条不同的验收标准。

### Flow 到底变成了什么？

共同层有：

```kotlin
fun observeAll(): Flow<List<ExperimentRecord>>
```

header 中是：

```objc
- (id<PLSKotlinx_coroutines_coreFlow>)observeAll;
```

`List<ExperimentRecord>` 的元素关系没有成为 Swift 可自然迭代的 `AsyncSequence<[ExperimentRecord]>`。

原因不神秘：标准 framework 通过 Objective-C 进入 Swift，而 Objective-C 的 protocol 泛型表达能力无法完整承载 Kotlin `Flow<T>`。Swift 知道它拿到了一个 Flow protocol，却不知道怎样以 Swift Concurrency 的方式订阅、取消和处理元素。

所以这段代码不存在：

```swift
// 标准 Objective-C export 下不能这样直接写
for try await records in repository.observeAll() { ... }
```

## 第一个架构原则：别为了 Swift 把共同层改成回调

看到 Flow 导出不友好，最直接的做法是把 Repository 改成：

```kotlin
interface ExperimentLocalStore {
    fun observeAll(callback: (List<ExperimentRecord>) -> Unit)
}
```

这样 Swift 的确舒服一点，但 Kotlin 端开始付账：

- Android 失去 Flow 操作符；
- collection 生命周期变成自定义约定；
- 错误与完成通道需要重新发明；
- 测试不能自然使用 `first()`、`take()`、Turbine 等工具；
- callback 限制从 Apple 边界蔓延进领域接口。

正确方向不是把所有平台降到最小公分母，而是保留各层最自然的抽象：

```text
commonMain
  Flow / suspend
        │
        ▼
ios source set
  callback + cancellation token
        │
        ▼
Swift
  AsyncSequence / async
```

中间层看似绕了一步，实际上把兼容成本关在了最应该出现的地方。

## Kotlin 侧：一座窄桥，不是一套新框架

PocketLab 新增 `IosExperimentListBridge`，只存在于 Apple source set。

### Scope 是桥的资源边界

```kotlin
private val scope = CoroutineScope(SupervisorJob() + dispatcher)
```

生产构造函数传入 `Dispatchers.Main`：

```kotlin
constructor(
    localStore: ExperimentLocalStore,
    refreshExperiments: RefreshExperimentsUseCase,
) : this(localStore, refreshExperiments, Dispatchers.Main)
```

这里不是说数据库查询必须在主线程执行。SQLDelight Repository 已经使用自己的 query dispatcher。这个 Main scope 负责的是：

- collection 生命周期；
- 把最终值送到 Swift callback 的执行上下文；
- 让下一阶段 SwiftUI 更新不会从任意后台线程闯入。

为什么是 `SupervisorJob`？

一个列表订阅失败，不应该自动取消同一桥上的刷新或其他订阅。兄弟任务隔离，但 `close()` 仍能一次取消全部：

```kotlin
fun close() {
    scope.cancel()
}
```

### Flow 被翻译为有类型的 callback

```kotlin
fun subscribe(
    onChange: (List<ExperimentRecord>) -> Unit,
    onFailure: (String) -> Unit,
): NativeCancellable {
    val job = scope.launch {
        try {
            localStore.observeAll().collect(onChange)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            onFailure(failure.message ?: "Experiment observation failed")
        }
    }
    return NativeCancellable(job)
}
```

这段代码最值得注意的不是 callback，而是取消分支：

```kotlin
catch (cancellation: CancellationException) {
    throw cancellation
}
```

如果写成：

```kotlin
catch (failure: Throwable) {
    onFailure(failure.message ?: "failed")
}
```

用户正常离开页面就会被上报为“加载失败”。更糟时，取消语义会被吞掉，协程继续执行清理之后的错误路径。

取消不是业务失败，它是控制流。

### 每次操作返回自己的 Job 句柄

```kotlin
class NativeCancellable internal constructor(
    private val job: Job,
) {
    val isCancelled: Boolean
        get() = job.isCancelled

    fun cancel() {
        job.cancel()
    }
}
```

它在生成的 header 中非常干净：

```objc
@interface PLSNativeCancellable : PLSBase
- (void)cancel;
@property (readonly) BOOL isCancelled;
@end
```

我们没有把整个 `Job` API 导给 Swift，而只暴露跨边界真正需要的能力。

这是 API 设计中的一个通用技巧：

> 不导出实现对象，导出最小控制能力。

## Swift 侧：用 AsyncStream 把回调变回语言原生体验

Apple 把 `AsyncStream` 定义为将 callback 或 delegate API 适配进 async/await 的标准工具。它正适合这座桥。

```swift
extension IosExperimentListBridge {
    func experiments()
        -> AsyncThrowingStream<[ExperimentRecord], any Error> {
        AsyncThrowingStream { continuation in
            let cancellation = NativeCancellationBox()

            let token = subscribe(
                onChange: { records in
                    continuation.yield(records)
                },
                onFailure: { message in
                    continuation.finish(
                        throwing: ExperimentObservationError(message: message)
                    )
                }
            )

            cancellation.install(token)
            continuation.onTermination = { @Sendable _ in
                cancellation.cancel()
            }
        }
    }
}
```

调用者终于得到 Swift 自己的抽象：

```swift
for try await records in bridge.experiments() {
    // render records
}
```

这不仅是语法糖。`AsyncThrowingStream` 明确了四件事：

1. 元素类型是 `[ExperimentRecord]`；
2. 每次 `yield` 是一次列表快照；
3. Kotlin observation 失败时 stream 以 Error 结束；
4. Swift 停止迭代时必须取消 Kotlin collection。

第四点由 `onTermination` 完成。

示例使用了：

```swift
@preconcurrency import PocketLabShared
```

这是因为标准 Objective-C framework 没有经过 Swift 6 的 `Sendable` 审计。`@preconcurrency` 只把“旧模块尚未声明并发隔离”变成显式兼容边界，不会自动让 Kotlin 对象线程安全。回调固定在 Kotlin Main dispatcher、token box 使用锁，以及 Swift UI 使用 `@MainActor`，仍然是我们必须自己证明的契约。

## 真正的握手发生在 onTermination

Swift Task 取消时，`AsyncStream` 会终止迭代并调用 continuation 的 `onTermination`。我们在这里调用 Kotlin token：

```swift
continuation.onTermination = { @Sendable _ in
    cancellation.cancel()
}
```

取消路径终于闭合：

```text
SwiftUI View 消失
→ Swift .task 被取消
→ AsyncStream iteration 终止
→ onTermination
→ NativeCancellable.cancel()
→ Kotlin Job.cancel()
→ Flow collection 执行 finally / 结束
```

如果漏掉这一行，Swift 页面看起来已经释放，Kotlin collection 却可能继续活着。

## 一个容易被忽略的竞态：取消比 token 更早

最短实现可以写成：

```swift
let token = subscribe(...)
continuation.onTermination = { _ in token.cancel() }
```

但健壮的异步适配器应该考虑 cancel-before-install：

```text
线程 A：Swift Task 被取消
线程 B：Kotlin subscribe 返回 token
```

如果取消发生时 token 还没装好，取消信号可能永久丢失。

实验使用一个很小的同步盒：

```swift
private final class NativeCancellationBox: @unchecked Sendable {
    private let lock = NSLock()
    private var token: NativeCancellable?
    private var cancelled = false

    func install(_ token: NativeCancellable) {
        lock.lock()
        if cancelled {
            lock.unlock()
            token.cancel()
        } else {
            self.token = token
            lock.unlock()
        }
    }

    func cancel() {
        lock.lock()
        cancelled = true
        let current = token
        token = nil
        lock.unlock()
        current?.cancel()
    }
}
```

状态只有两条规则：

- install 先发生：保存 token，等待未来取消；
- cancel 先发生：记住取消，token 到达后立即取消。

`@unchecked Sendable` 不是“请编译器闭嘴”。它是开发者对线程安全承担责任的声明，所以内部必须有真实同步机制。这里用 `NSLock` 保护所有可变状态，并且在锁外调用跨语言 `token.cancel()`，避免把不可控工作放在临界区。

## refresh：一个元素的流也有价值

refresh 是一次性操作，不是持续 Flow。为什么示例仍把 callback 包成一个只产生一次结果的 `AsyncThrowingStream`？

因为它免费获得了同一套取消连接：

```swift
private func refreshResults()
    -> AsyncThrowingStream<any RefreshExperimentsResult, any Error> {
    AsyncThrowingStream(bufferingPolicy: .bufferingNewest(1)) { continuation in
        let cancellation = NativeCancellationBox()
        let token = refresh(
            onResult: { result in
                continuation.yield(result)
                continuation.finish()
            },
            onFailure: { message in
                continuation.finish(
                    throwing: ExperimentObservationError(message: message)
                )
            }
        )
        cancellation.install(token)
        continuation.onTermination = { @Sendable _ in
            cancellation.cancel()
        }
    }
}
```

再提供符合调用者预期的 async 函数：

```swift
func refreshOnce() async throws -> any RefreshExperimentsResult {
    for try await result in refreshResults() {
        return result
    }
    throw CancellationError()
}
```

正常时返回唯一结果；Task 取消时 iteration 结束，Kotlin Job 被取消，然后 Swift 抛出 `CancellationError`。

这不是唯一正确实现。成熟桥接库会生成更通用的 continuation/cancellation 适配代码。本实验故意手写一次，是为了让每条边都看得见。

## 线程、Dispatcher 与 MainActor：别把它们混成一句“主线程”

Kotlin 官方 ARC 互操作文档明确提醒：Kotlin suspend 的 completion handler 可能在非主线程调用。

因此这段 Swift 并不天然安全：

```swift
let result = try await useCase.execute()
self.items = result.items // self 也许是 @MainActor
```

Swift 自身的 actor isolation 会帮助诊断，但跨语言 API 需要明确上下文。

PocketLab 采用：

```text
SQL 查询                    Kotlin query dispatcher
Flow collection / callback Kotlin Dispatchers.Main
Swift UI model              Swift @MainActor
```

这三层不是重复：

- query dispatcher 防止数据库工作阻塞 UI；
- Main dispatcher 保证跨边界 callback 的到达线程；
- `@MainActor` 保证 Swift 状态对象的隔离规则。

专家级代码不会说“反正都在主线程”，而会指出每一次切换由谁保证。

## Error 也需要跨语言协议

Kotlin/Native 的标准 Objective-C 导出有一条重要规则：

- 标有 `@Throws` 的异常可以映射为 `NSError` / Swift `throws`；
- suspend 函数没有 `@Throws` 时，只会把 `CancellationException` 作为错误传播；
- 其他未处理 Kotlin 异常跨过边界可能导致程序终止。

PocketLab 的 refresh 没有用异常表达预期业务失败，而是返回稳定结果：

```kotlin
sealed interface RefreshExperimentsResult {
    data class Applied(...) : RefreshExperimentsResult
    data class Failed(...) : RefreshExperimentsResult
}
```

这样 401、503、坏 JSON 等预期结果不会依赖异常桥。但标准 Objective-C export 又带来新的体验问题：sealed interface 在 Swift 中呈现为 protocol + 子类，而不是漂亮的 native enum。

这一轮接受这个限制，因为重点是并发桥。第 12 篇“为 Swift 设计 Kotlin API”会专门处理：

- sealed hierarchy 怎样导出；
- enum 是否需要默认分支；
- NSError、结果对象还是 Swift wrapper；
- 哪些声明应 `@HiddenFromObjC`；
- 哪些 API 应用 `@ShouldRefineInSwift`。

边界问题要分层解决，不要在一篇实验里假装全部完成。

## 生命周期到底属于谁？

桥同时提供两种取消粒度：

### 单操作取消

```swift
let token = bridge.subscribe(...)
token.cancel()
```

适合一个 observation 或一次 refresh 结束。

### 整体关闭

```swift
bridge.close()
```

取消桥拥有的全部 Job，且 bridge 之后不再复用。

到了 SwiftUI，理想所有权会是：

```text
App / feature container
  owns database + network + bridge

SwiftUI screen task
  owns one AsyncSequence iteration

Task cancellation
  cancels one token

feature destruction
  calls bridge.close()
```

不要依赖 Kotlin/Native GC 或 Swift ARC “最终总会回收”。内存回收与异步工作停止不是一回事。

## 我们怎样验证这不是一段漂亮伪代码？

本轮建立了四层证据。

### 证据一：真实 iOS Native 行为测试

新增五个 `iosX64Test`：

1. subscription 收到当前值和后续值，token 取消后不再收到更新；
2. refresh 经过真实共享 UseCase 返回并修改本地 store；
3. 挂起 refresh 被取消后不调用完成 callback；
4. 意外 refresh 异常进入 failure callback，而不是成为无人处理异常；
5. bridge.close 取消全部已有 Job。

测试使用虚拟调度器，没有真实 sleep。

### 证据二：framework 成功链接

```shell
./gradlew :shared:linkDebugFrameworkIosX64
```

它证明桥可以进入真实 Apple binary，而不只是 Kotlin test compilation。

### 证据三：header 审计

生成 header 明确包含：

```objc
@interface PLSIosExperimentListBridge
- (PLSNativeCancellable *)subscribeOnChange:...;
- (PLSNativeCancellable *)refreshOnResult:... onFailure:...;
- (void)close;
@end
```

这是 Swift 可见 API 的直接证据。

### 证据四：Swift 6 完整严格并发检查

```shell
xcrun --sdk iphonesimulator swiftc \
  -typecheck \
  -swift-version 6 \
  -strict-concurrency=complete \
  -target x86_64-apple-ios13.0-simulator \
  -F path/to/debugFramework \
  ExperimentListAsyncSequence.swift
```

示例通过，说明：

- framework 的 Swift importer API 与示例一致；
- `try await useCase.execute()` 确实可编译；
- `AsyncThrowingStream` 的元素和错误类型成立；
- 在显式 `@preconcurrency` 旧模块边界下，wrapper 自己的 `@Sendable` closure 满足 Swift 6 检查；
- token box 的并发声明在完整检查下成立。

type-check 不等于真实 UI 运行，也不证明导入的 Kotlin 类型天然 `Sendable`，但远强于在 Markdown 里写一段未编译 Swift。

## 2026 年的新变量：Swift Export 已经会导出 Flow

如果你几年前研究过 KMP，可能记得一个结论：“Flow 不能直接给 Swift，只能用插件或手写桥。”

这个结论现在必须加上时间和路径限定。

截至 2026-11-19，Kotlin 官方 Swift Export 文档已经展示：

- `suspend fun` 映射为 Swift `async`；
- `Flow<T>` 可通过 `asAsyncSequence()` 成为保留元素类型的 Swift AsyncSequence；
- Kotlin enum 映射为 Swift enum；
- sealed hierarchy 可以生成供穷尽 switch 使用的 Swift enum 视图；
- 默认从 Swift 进入的协程使用 `Dispatchers.Default`，需要时由 Kotlin `withContext` 切换。

这是一大步，而且非常接近我们真正想要的终局。

但同一份官方文档也写得很清楚：

- Swift Export 当前仍是 Alpha；
- 功能仍不完整，破坏性变化可预期；
- 只支持 Xcode direct integration；
- 泛型类型参数通常不受支持或被擦除到上界；
- 继承 `List`、`Set`、`Map` 的类型仍有已知问题。

PocketLab 此时还没有 iOS 壳工程，也就没有 direct-integration Xcode 项目。因此本轮的决策是：

> 用标准 framework + 显式取消桥完成可运行基线；下一阶段建立 SwiftUI 壳后，再把 Swift Export 作为并行实验，而不是假装 Alpha 功能不存在，也不把它提前当生产默认。

专家不是永远坚持旧方案，而是知道什么时候该迁移、迁移前需要什么证据。

## 手写桥、KMP-NativeCoroutines、SKIE、Swift Export 怎么选？

| 方案 | 优点 | 代价 | 当前适用判断 |
|---|---|---|---|
| 手写领域窄桥 | 原理透明、依赖少、行为完全可测 | API 多后重复代码增加 | 少量 API、教学、定制边界 |
| KMP-NativeCoroutines | 生成 suspend/Flow 桥，支持 AsyncSequence、Combine、RxSwift 和取消 | Kotlin/Swift 两侧依赖与版本矩阵 | 需要稳定桥接大量 coroutine API |
| SKIE | 改善 Objective-C 导出后的 Swift API，Flow 可转 AsyncSequence | 构建插件复杂度与版本跟随 | 强调 Swift 调用体验的团队 |
| Swift Export | 官方方向、直接 Swift 模块、原生映射更丰富 | Alpha、direct integration 限制 | 前瞻实验，等待稳定性评估 |

选择时至少问：

1. Swift Task 取消是否传播到 Kotlin Job？
2. Flow 元素泛型是否保留？
3. 冷 Flow 每个订阅是否独立 collection？
4. StateFlow 初始值怎样暴露？
5. Kotlin exception 怎样变成 Swift Error？
6. Swift 6 Sendable / actor isolation 是否通过？
7. Kotlin、Coroutines、Xcode 升级由谁跟进？
8. 生成代码能否查看和调试？
9. framework / XCFramework / SPM 路径是否兼容？
10. 二进制体积和构建时间增加多少？

“少写几行 glue code”只是一个维度。

## 八个常见反模式

### 1. 把 suspend 自动导入 async 当成完整互操作

调用语法成立，不代表取消、错误、线程和生命周期都成立。分别验证。

### 2. 从 Swift 直接操作 raw Flow protocol

这把 Kotlin runtime 细节扩散到每个 iOS 页面。集中在 adapter。

### 3. 为 Swift 把 commonMain 全改成 callback

边界限制不应成为整个共享层的抽象。

### 4. 忘记 onTermination

Swift iteration 结束，Kotlin collection 仍可能继续。取消必须跨桥。

### 5. catch Throwable 后报告“加载失败”

会把正常取消变成错误。先单独重抛 `CancellationException`。

### 6. 桥内部使用不可关闭的全局 Scope

页面生命周期无法停止工作，测试也无法确定性清理。

### 7. 默认 completion 一定回到主线程

Kotlin 官方文档明确说明 completion 可能从其他线程调用。明确 dispatcher 与 actor。

### 8. 只在文章里写 Swift，不交给 swiftc

Swift importer 名称、existential 语法、Sendable 和泛型细节都可能与想象不同。把 type-check 加进实验门禁。

## 一张表收住责任

| 责任 | Kotlin 共同层 | Apple Kotlin adapter | Swift wrapper / UI |
|---|---:|---:|---:|
| 数据库事实来源 | 是 | 否 | 否 |
| Flow / suspend 业务 API | 是 | 消费 | 否 |
| Kotlin CoroutineScope | 否 | 拥有 | 否 |
| Job cancellation token | 否 | 暴露 | 调用 |
| callback → AsyncSequence | 否 | 否 | 是 |
| Swift Task 生命周期 | 否 | 被通知 | 拥有 |
| UI actor isolation | 否 | Main callback | `@MainActor` |
| 关闭数据库/网络 | 组合根 | 接受依赖 | feature owner 触发 |

## 最后的心智模型

- `suspend` 导出成 Swift async，不代表 Task 与 Job 自动共享取消树。
- 标准 Objective-C export 下，Flow protocol 不是 Swift AsyncSequence。
- 共同层继续使用最自然的 Flow / suspend；callback 只存在于 Apple 边界。
- 每个持续任务必须有显式 owner、显式 cancel 和最终 close。
- `AsyncStream.onTermination` 是 Swift Task → Kotlin Job 的关键连接点。
- 取消是控制流，不是业务错误；`CancellationException` 必须继续传播。
- dispatcher 决定 Kotlin 执行上下文，actor 决定 Swift 隔离；两者都要设计。
- generated header 是互操作事实，Swift type-check 是调用事实。
- Swift Export 已经原生支持 Flow → AsyncSequence，但 2026-11 仍是 Alpha。
- 工具选择应比较取消、错误、泛型、版本与调试，而不只比较胶水代码行数。

现在 PocketLab 的异步路径终于完整了一点：

```text
SQLDelight Flow
→ Kotlin collection Job
→ typed callback
→ Swift AsyncThrowingStream
→ SwiftUI Task

SwiftUI Task cancel
→ AsyncStream.onTermination
→ NativeCancellable
→ Kotlin Job.cancel
```

下一篇，我们会把这条路径真正放进两个原生 UI：Android Compose 和 iOS SwiftUI。届时不再只 type-check Swift，而会面对页面生命周期、列表状态、刷新手势、错误文案与依赖装配。

## 动手挑战：让结束也成为协议

当前数据库 Flow 通常不会自然完成。请新增一个可控测试 Flow，并证明三种结束路径：

1. Kotlin 正常完成：Swift `for await` 正常退出。
2. Kotlin 失败：Swift stream 抛出明确 Error。
3. Swift Task 取消：Kotlin collection 的 `finally` 被执行。

额外要求：

- finish/cancel 任意竞态都只能结束 continuation 一次；
- cancel-before-token-install 不丢信号；
- 不使用真实 sleep；
- Swift 6 `-strict-concurrency=complete` 继续通过；
- generated header 变化必须进入审查。

完成之后，你对“跨平台协程”的理解就不再是“Swift 能 await Kotlin”，而是能设计两套运行时之间完整的生命周期协议。

## 参考资料

- [Kotlin/Native 与 Swift/Objective-C 互操作](https://kotlinlang.org/docs/native-objc-interop.html)
- [Kotlin Swift Export](https://kotlinlang.org/docs/native-swift-export.html)
- [Kotlin/Native 与 ARC](https://kotlinlang.org/docs/native-arc-integration.html)
- [Apple AsyncStream](https://developer.apple.com/documentation/swift/asyncstream)
- [Apple AsyncStream onTermination](https://developer.apple.com/documentation/swift/asyncstream/continuation/ontermination)
- [KMP-NativeCoroutines](https://github.com/rickclephas/KMP-NativeCoroutines)
- [KMP Native UI 与 Flow 桥接选项](https://kotlinlang.org/docs/multiplatform/multiplatform-update-ui.html)
