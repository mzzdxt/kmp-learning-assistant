# 实验 08：让 Kotlin Job 和 Swift Task 真正握手

## 实验目标

完成本实验后，你应该能用运行证据回答：

1. Kotlin `suspend` 经过标准 Kotlin/Native framework 后，Swift 实际看见什么？
2. 为什么 `Flow<List<ExperimentRecord>>` 不能在标准 Objective-C 导出路径中直接当成 Swift `AsyncSequence`？
3. `Task.cancel()` 为什么不等于 Kotlin `Job.cancel()`？
4. 如何用 `AsyncStream.onTermination` 建立取消传播？
5. CoroutineScope、Swift Task 和 SwiftUI 生命周期分别由谁拥有？
6. 2026 年的 Swift Export 已经解决了什么，为什么本实验仍保留手写桥？

## 前置条件

- 已完成[实验 07](../07-offline-first/README.md)。
- 当前工程使用 Kotlin 2.4.21、kotlinx.coroutines 1.11.0。
- Xcode 26.5 / Swift 6.3.2 可用。
- 当前 Intel host 使用 `iosX64`；Apple Silicon 应把命令和 target 换成 `iosSimulatorArm64`。

## 先画出三棵并发树

```text
Kotlin side                         Swift side

SupervisorJob                      SwiftUI .task / Task
├── Flow collection Job            ├── for await iteration
└── refresh Job                    └── refresh await

              NativeCancellable
Swift Task cancel ────────────────> Kotlin Job cancel
```

“Swift 语法能 `await` Kotlin”只证明调用形状，不证明两棵取消树已经连接。本实验的重点就是中间那条箭头。

## 第一步：生成真正的 Apple framework

项目的 `iosX64` target 增加了：

```kotlin
iosX64 {
    binaries.framework {
        baseName = "PocketLabShared"
        isStatic = true
    }
}
```

执行：

```shell
cd ../02-first-kmp-module
./gradlew :shared:linkDebugFrameworkIosX64
```

产物位于：

```text
shared/build/bin/iosX64/debugFramework/PocketLabShared.framework
├── Headers/PocketLabShared.h
├── Modules/module.modulemap
├── PocketLabShared
└── Info.plist
```

不要凭印象讨论 Swift API。直接查看编译器生成的 header：

```shell
rg -n \
  'IosExperimentListBridge|NativeCancellable|executeWithCompletionHandler' \
  shared/build/bin/iosX64/debugFramework/PocketLabShared.framework/Headers/PocketLabShared.h
```

你会看到三类证据。

### suspend 仍以 completion handler 进入 Objective-C

```objc
- (void)executeWithCompletionHandler:
    (void (^)(id<PLSRefreshExperimentsResult> _Nullable,
              NSError * _Nullable))completionHandler;
```

Swift 5.5+ importer 允许写：

```swift
let result = try await useCase.execute()
```

但底层 ABI 仍是 completion handler；语法变漂亮不代表 Task cancellation 自动成为 Kotlin Job cancellation。

### 原始 Flow 丢失 Swift 需要的元素形状

```objc
- (id<PLSKotlinx_coroutines_coreFlow>)observeAll;
```

Objective-C protocol 泛型无法保留 `Flow<List<ExperimentRecord>>` 的完整元素关系。Swift 能“看见一个 Flow 对象”，但不能自然地 `for await`。

### 领域专用桥具有清楚的 Swift 形状

```objc
- (PLSNativeCancellable *)subscribeOnChange:
    (void (^)(NSArray<PLSExperimentRecord *> *))onChange
    onFailure:(void (^)(NSString *))onFailure;
```

元素类型、失败通道和取消句柄都已显式出现。

## 第二步：保持共同 API 原生

共同数据层没有改成 callback：

```kotlin
interface ExperimentLocalStore {
    fun observeAll(): Flow<List<ExperimentRecord>>
}

class RefreshExperimentsUseCase {
    suspend fun execute(): RefreshExperimentsResult
}
```

原因很简单：

- Flow 是 Kotlin 数据流的正确抽象；
- suspend 是一次性异步工作的正确抽象；
- Swift 的 FFI 形状属于平台适配问题；
- 不应为了一个消费者降低所有消费者的 API。

## 第三步：只在 Apple source set 翻译

核心实现位于：

```text
shared/src/iosX64Main/kotlin/
  dev/kmpjourney/pocketlab/interop/IosExperimentListBridge.kt
```

订阅逻辑的关键部分：

```kotlin
private val scope = CoroutineScope(SupervisorJob() + dispatcher)

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

这里有五个刻意选择：

1. 生产构造函数使用 `Dispatchers.Main`，让 SwiftUI 状态更新从主执行上下文到达。
2. 数据库查询仍由 Repository 自己的 query dispatcher 执行；Main 只负责收集和回调。
3. `SupervisorJob` 隔离兄弟任务，一个订阅失败不会取消另一项刷新。
4. `CancellationException` 必须重抛，不能伪装成业务失败。
5. 每次操作返回独立 token，桥本身还提供 `close()` 做整体生命周期结束。

token 很小：

```kotlin
class NativeCancellable internal constructor(private val job: Job) {
    val isCancelled: Boolean get() = job.isCancelled
    fun cancel() = job.cancel()
}
```

边界类越小，未来被 Swift Export 或生成插件替换时越容易删除。

## 第四步：在 Swift 恢复 AsyncSequence

可编译示例位于：

```text
swift/ExperimentListAsyncSequence.swift
```

核心包装：

```swift
extension IosExperimentListBridge {
    func experiments()
        -> AsyncThrowingStream<[ExperimentRecord], any Error> {
        AsyncThrowingStream { continuation in
            let cancellation = NativeCancellationBox()
            let token = subscribe(
                onChange: { continuation.yield($0) },
                onFailure: {
                    continuation.finish(
                        throwing: ExperimentObservationError(message: $0)
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

平台 UI 最终可以使用 Swift 自己的语法：

```swift
for try await records in bridge.experiments() {
    // 更新 @MainActor 状态
}
```

`AsyncStream` 不是装饰器。它定义了流元素、结束、失败、缓冲与取消如何进入 Swift Concurrency。

示例使用 `@preconcurrency import PocketLabShared`，因为 Objective-C framework 尚未携带 Swift 6 `Sendable` 审计信息。这只是显式声明“按旧模块导入”，不是线程安全证明；Main dispatcher、`@MainActor` 和下面的锁仍然不可省略。

## 第五步：别漏掉 cancel-before-install 竞态

这段代码看似足够：

```swift
let token = subscribe(...)
continuation.onTermination = { _ in token.cancel() }
```

但异步适配器要考虑一种通用竞态：Swift Task 可能先被取消，Kotlin token 稍后才安装。示例使用带锁的 `NativeCancellationBox`：

```text
install 先发生：保存 token，未来 cancel 时调用
cancel 先发生：记住 cancelled，install 到达后立即取消 token
```

它被标记为 `@unchecked Sendable`，不是为了关闭检查，而是因为线程安全由内部 `NSLock` 明确保证。Swift 6 完整严格并发检查会继续检查其余捕获关系。

## 第六步：一次性 refresh 也要传播取消

标准导出允许直接写：

```swift
let result = try await useCase.execute()
```

示例保留这条函数，证明 Swift importer 的确形成了 async API。同时，可靠取消路径把 refresh callback 包成只发一个元素的 `AsyncThrowingStream`：

```swift
func refreshOnce() async throws -> any RefreshExperimentsResult {
    for try await result in refreshResults() {
        return result
    }
    throw CancellationError()
}
```

当外层 Task 取消时，iteration 结束，`onTermination` 取消 Kotlin token，最终抛出 Swift `CancellationError`。这样两棵并发树才真正握手。

## 第七步：运行四类验证

### 1. iOS bridge 行为测试

```shell
cd ../02-first-kmp-module
./gradlew :shared:iosX64Test
```

新增测试验证：

- 订阅立即收到 StateFlow 当前值；
- 新值继续到达 Swift callback；
- token 取消后不再收到值；
- refresh 结果通过共同 UseCase 写入本地 store；
- 取消挂起 refresh 不调用完成 callback；
- 意外 refresh 异常进入 failure callback；
- `close()` 取消桥拥有的所有 Job。

### 2. framework 链接

```shell
./gradlew :shared:linkDebugFrameworkIosX64
```

这证明声明不只是 Native test 可见，而是真能进入 Apple framework。

### 3. 生成 header 审计

```shell
rg -n 'IosExperimentListBridge|NativeCancellable' \
  shared/build/bin/iosX64/debugFramework/PocketLabShared.framework/Headers/PocketLabShared.h
```

这证明 Swift 面对的 ABI 形状，而不是 Kotlin 源码看起来的形状。

### 4. Swift 6 严格并发 type-check

从本实验目录执行：

```shell
mkdir -p /private/tmp/pocketlab-swift-module-cache

xcrun --sdk iphonesimulator swiftc \
  -typecheck \
  -swift-version 6 \
  -strict-concurrency=complete \
  -module-cache-path /private/tmp/pocketlab-swift-module-cache \
  -target x86_64-apple-ios13.0-simulator \
  -F ../02-first-kmp-module/shared/build/bin/iosX64/debugFramework \
  swift/ExperimentListAsyncSequence.swift
```

命令无输出且 exit code 为 0 表示通过。Apple Silicon 把 target 改为 `arm64-apple-ios13.0-simulator`，并使用对应 framework 目录。

## Swift Export 现在到了哪里？

截至 2026-11-19，Kotlin 官方 Swift Export 文档已经展示：

```kotlin
fun flowOfStrings(): Flow<String>
```

可在 Swift 中写成：

```swift
for try await element in flowOfStrings().asAsyncSequence() { ... }
```

它还能把 suspend 直接导出为 async，把 Kotlin enum 和 sealed hierarchy 映射得更自然。这正是未来方向。

本实验没有直接启用，原因不是它“不支持 Flow”，而是：

- 官方状态仍是 Alpha；
- 只支持 Xcode direct integration；
- 当前 PocketLab 尚未建立 iOS 壳工程；
- 泛型和集合继承仍有明确限制；
- 本阶段要先理解取消和生命周期，而不是隐藏它们。

第 9 个实验建立 SwiftUI 壳后，可以把 Swift Export 作为并行路径验证。

## 工具化选项怎么判断

| 方案 | Flow → AsyncSequence | Task → Job 取消 | 当前成熟度 | 适合场景 |
|---|---:|---:|---|---|
| 手写窄桥 | 是 | 显式实现 | 可控 | 学习原理、少量 API |
| KMP-NativeCoroutines | 是 | 支持 | 第三方成熟方案 | 多个 Flow/suspend API |
| SKIE | 是 | 由工具增强 | 第三方方案 | 希望改善整体 Swift API |
| Swift Export | 原生支持 | 新并发桥 | 官方 Alpha | direct integration 前瞻实验 |

不要按“代码最少”选。至少比较 Kotlin/Swift 版本兼容、生成代码可见性、取消、错误映射、SPM/CocoaPods 集成、二进制体积与团队调试能力。

## 本实验没有证明什么

- 没有运行真实 SwiftUI View 生命周期；第 9 个实验补上。
- 没有证明 App 进入后台后的系统调度行为。
- 没有把所有 PocketLab Flow 都桥接；当前只做垂直切片。
- 没有解决 Objective-C 导出下 sealed interface 的 Swift 人机工程。
- 没有比较 SKIE、KMP-NativeCoroutines、Swift Export 的构建时间和体积。
- 没有证明 macOS x64 host 的长期可用性；编译器已明确发出弃用警告。

## 观察点

1. 删除 `onTermination`，取消 Swift Task 后观察 Kotlin Job 是否继续存活。
2. 把 `Dispatchers.Main` 改成 `Dispatchers.Default`，在真实 SwiftUI 状态更新处观察并发诊断。
3. 在 Kotlin `catch` 中吞掉 `CancellationException`，观察它如何被错误地报告为业务失败。
4. 去掉 `NativeCancellationBox` 的锁，用 Thread Sanitizer 思考 install/cancel 竞态。
5. 直接从 Swift 使用 `observeAll()` 返回的 raw Flow，记录类型信息和调用体验。

## 挑战：证明取消是双向的

扩展实验，使 Kotlin 端也能主动结束 Flow，并满足：

1. 正常完成时 Swift `for await` 退出，不产生错误。
2. Kotlin 失败时 Swift 收到领域明确的 Error。
3. Swift Task 取消时 Kotlin `finally` 被执行。
4. 任意完成路径只结束 continuation 一次。
5. cancel-before-install 与 finish-before-cancel 都有测试。
6. 不用真实 `sleep`，测试保持确定性。

## 参考资料

- [Kotlin/Native 与 Swift/Objective-C 互操作](https://kotlinlang.org/docs/native-objc-interop.html)
- [Kotlin Swift Export](https://kotlinlang.org/docs/native-swift-export.html)
- [Kotlin/Native 与 ARC](https://kotlinlang.org/docs/native-arc-integration.html)
- [Apple AsyncStream](https://developer.apple.com/documentation/swift/asyncstream)
- [Apple AsyncStream onTermination](https://developer.apple.com/documentation/swift/asyncstream/continuation/ontermination)
- [KMP-NativeCoroutines](https://github.com/rickclephas/KMP-NativeCoroutines)
- [KMP Native UI 更新与 Flow 桥接选项](https://kotlinlang.org/docs/multiplatform/multiplatform-update-ui.html)
