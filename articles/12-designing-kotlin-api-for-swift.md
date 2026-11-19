# 为 Swift 设计 Kotlin API：能导出，不等于好用

> 面向读者：已经能把 Kotlin framework 接入 iOS，开始关心 API 质量、Swift 6 并发与长期演进的 KMP 开发者
>
> 技术核对与实验验证日期：2026-11-20
>
> 实验版本：Kotlin 2.4.21、Swift 6.3.2、Xcode 26.5、Objective-C framework export；Swift Export 仍为 Alpha

上一篇我们拆开了 Kotlin/Native：KLIB、LLVM、runtime、GC 和 framework 各自负责什么。

这一篇换个座位。

不再坐在 Kotlin 作者面前，而是坐到一位 Swift 开发者旁边。她打开 Xcode，准备刷新实验列表，却看到这样的代码：

```swift
let result: any RefreshExperimentsResult = try await bridge.refreshOnce()

if let applied = result as? RefreshExperimentsResultApplied {
    // success
} else if let failed = result as? RefreshExperimentsResultFailed {
    if failed.reason == RefreshFailureReason.server {
        // failure
    }
}
```

它能编译，也能运行。

但它暴露了四个问题：

1. Swift 必须知道 Kotlin sealed hierarchy 的生成规则；
2. `switch` 无法穷尽，新增 subtype 时编译器不会保护调用者；
3. Kotlin 的 `Ios*` 实现命名进入了产品代码；
4. 业务结果、互操作错误和平台文案混在了一起。

如果我们只用“framework 构建成功”评价 API，这些问题全部会被漏掉。

## 先看改造后的 Swift

PocketLab 现在把 Swift 使用面收敛成：

```swift
let outcome = try await feature.experimentList.refreshOnce()

switch outcome {
case .updated(let changedCount):
    notice = "同步完成：更新了 \(changedCount) 条记录"
case .unchanged:
    notice = "已是最新状态"
case .rejected(let reason):
    notice = failureMessage(for: reason)
}
```

列表观察是：

```swift
for try await experiments in feature.experimentList.experiments() {
    self.experiments = experiments
}
```

SwiftUI 只接触：

```swift
struct Experiment: Identifiable, Sendable
enum RefreshOutcome: Sendable, Equatable
enum RefreshRejection: Sendable, Equatable
```

它不再知道：

- SQLDelight record；
- Kotlin `Flow`；
- `RefreshExperimentsResultApplied` 这类生成类型；
- Kotlin enum 的非穷尽导入规则；
- callback token 如何处理先取消、后安装的竞态。

这才是本篇真正要研究的问题：

> 跨平台 API 的质量，不由 Kotlin 端看起来多优雅决定，而由每个平台实际得到的调用体验决定。

## 一条 Kotlin 声明，要经过五道门

PocketLab 采用的设计顺序是：

```text
共同业务模型
  ↓ 1. 是否应该导出？
Apple 专用 facade
  ↓ 2. Swift 看见什么名字？
Objective-C header
  ↓ 3. 类型信息损失了什么？
Swift importer
  ↓ 4. callback / reference 如何精炼？
Swift value wrapper
  ↓ 5. SwiftUI 真正消费什么？
平台产品 API
```

每一道门都可能改变 API。

因此不能只审查 Kotlin 源码，也不能只审查生成 header。最终 Swift 调用代码、严格并发编译和运行测试都必须进入证据链。

## 第一份证据：2,834 行 header 里藏着什么

里程碑 11 的 Release framework header 有 2,834 行。

搜索 PocketLab 列表 API，可以看到：

```objc
__attribute__((swift_name("IosExperimentListBridge")))
@interface PLSIosExperimentListBridge : PLSBase

- (PLSNativeCancellable *)refreshOnResult:
    (void (^)(id<PLSRefreshExperimentsResult>))onResult
    onFailure:(void (^)(NSString *))onFailure;

- (PLSNativeCancellable *)subscribeOnChange:
    (void (^)(NSArray<PLSExperimentRecord *> *))onChange
    onFailure:(void (^)(NSString *))onFailure;
```

这段 header 已经把问题说得很清楚：

- `IosExperimentListBridge` 是实现视角的名字，不是产品能力的名字；
- refresh 返回 protocol，Swift 只能动态判断具体 subtype；
- observation 直接暴露数据库附近的 `ExperimentRecord`；
- failure 只有字符串，没有稳定机器码；
- callback 方法和 Swift `AsyncSequence` wrapper 同时出现在补全中；
- bridge 构造器还暴露 `ExperimentLocalStore` 与 `RefreshExperimentsUseCase`。

最后一条尤其危险。

Swift 调用者本来只需要一个“实验列表客户端”，却看见了 repository 和 use case 的组装细节。它不是多给了一些自由，而是把内部模块边界变成了外部契约。

## 原则一：先决定“谁不该被看见”

API 设计经常从命名开始，但更早的问题应该是：

> 这个 declaration 为什么需要出现在 Swift 中？

Kotlin/Native 的 Objective-C export 会导出模块中的 public declarations。`public` 对 Kotlin 调用者合理，不代表它就适合成为 Apple framework API。

PocketLab 用三种方式收口。

### 1. Kotlin 模块内实现改成 `internal`

列表 bridge 原来有一个 public 构造器：

```kotlin
constructor(
    localStore: ExperimentLocalStore,
    refreshExperiments: RefreshExperimentsUseCase,
)
```

它只用于同一个 Kotlin 模块的组装和测试，因此改为：

```kotlin
internal constructor(
    localStore: ExperimentLocalStore,
    refreshExperiments: RefreshExperimentsUseCase,
)
```

Swift 不再能绕过 `PocketLabFeatures` 自行拼装内部依赖。

### 2. 仍需对 Kotlin public、但不该给 Swift 的类型使用 `@HiddenFromObjC`

共同层继续公开 `ExperimentRecord`、`ExperimentLocalStore` 和 `RefreshExperimentsResult`，因为 Android 或其他 Kotlin 模块可能需要它们。

Apple framework 不需要。

```kotlin
@HiddenFromObjC
data class ExperimentRecord(...)

@HiddenFromObjC
interface ExperimentLocalStore : ExperimentRepository

@HiddenFromObjC
sealed interface RefreshExperimentsResult
```

这里出现了一个很好的真实失败。

第一次只隐藏 `ExperimentLocalStore` 后，Native 编译器拒绝了：

```text
Only '@HiddenFromObjC' declaration can be a subtype
of '@HiddenFromObjC' declaration.
```

因为公开的 `SqlDelightExperimentRepository` 仍然实现这个隐藏接口。

这不是注解“难用”，而是在提醒我们：可见性是类型图上的约束。最终实现类也必须隐藏，不能让一个公开类型偷偷继承不可见协议。

### 3. 大型工程最终应拆出 export facade 模块

`@HiddenFromObjC` 适合渐进收口，但如果一个大 shared module 有几百个 public types，逐个隐藏会变成反向 allowlist。

更稳健的长期结构是：

```text
:domain        Kotlin-only public API
:data          Kotlin-only implementation
:apple-api     很小的 Apple facade + DTO
               ↓
          exported framework
```

PocketLab 目前仍是教学用单模块，所以先用注解建立边界；模块化里程碑会再把这条物理边界拆出来。

## 原则二：名称属于调用者，不属于实现历史

`IosExperimentListBridge` 描述了“这是 iOS bridge”。

Swift 调用者更关心“它能做什么”。

PocketLab 使用 `@ObjCName` 指定 Swift 名称：

```kotlin
@ObjCName(swiftName = "ExperimentListClient")
class IosExperimentListBridge

@ObjCName(swiftName = "PocketLabFeature")
class IosPocketLabFeature

@ObjCName(swiftName = "CancellationToken")
class NativeCancellable
```

生成 header 仍保留唯一的 Objective-C symbol：

```objc
@interface PLSIosExperimentListBridge : PLSBase
```

但 Swift importer 得到：

```swift
ExperimentListClient
PocketLabFeature
CancellationToken
```

这揭示了一个关键区别：

```text
Objective-C runtime name ≠ Swift source name
```

不要为了让 Swift 名字好看，就把共同 Kotlin 类全部改成 Apple 风格；也不要把 `Ios` 前缀当作永远无法消除的编译器宿命。

不过要记住：`@ObjCName` 在当前 Kotlin 标准库中仍带 experimental opt-in。使用它意味着需要版本锁定、生成物检查和升级验证，而不是“加完注解就永远稳定”。

## 原则三：不要把共同模型原样倒进 Swift

直接复用 `ExperimentRecord` 看似减少 DTO，实际会把三件事绑死：

1. SQLDelight 附近的本地数据形状；
2. Kotlin enum 的导出方式；
3. SwiftUI 的展示需求。

PocketLab 新增 Apple 专用 immutable snapshot：

```kotlin
@ObjCName(swiftName = "ExperimentSnapshot")
class IosExperimentSnapshot internal constructor(
    val id: String,
    val title: String,
    val hypothesis: String,
    val notes: String,
    val updatedAtEpochMilliseconds: Long,
    val isPendingUpload: Boolean,
)
```

为什么不是继续导出 `ExperimentSyncState`？

因为当前 UI 只需要回答“是否待上传”。让 Swift 认识完整 Kotlin enum，会扩大契约，却没有增加产品能力。

这不是鼓励把所有 enum 都变成 Boolean，而是要求 facade 根据消费者需要选择最小、稳定的语义。

Swift 收到 snapshot 后立即复制为本地 value type：

```swift
struct Experiment: Identifiable, Sendable {
    let id: String
    let title: String
    let hypothesis: String
    let notes: String
    let updatedAtEpochMilliseconds: Int64
    let isPendingUpload: Bool
}
```

从此 SwiftUI 不再依赖 Kotlin reference type。

## 为什么这次“复制”是正确成本

官方互操作文档提醒：Kotlin collection 经过 Objective-C 再进入 Swift 时可能发生 collection 与 element 的桥接、转换和复制。

所以不能假装这条路径零成本。

但 PocketLab 的列表快照有三个特点：

- 列表规模小；
- 每次数据库 Flow 本来就发布完整 immutable snapshot；
- SwiftUI 需要 `Identifiable & Sendable` 的平台值模型。

这里主动复制换来了：

- Swift 6 的静态并发检查；
- UI 与 Kotlin object lifetime 解耦；
- 平台格式化和 diff 语义更清楚；
- shared model 未来可演进。

如果未来列表增长到几万条，正确动作不是凭感觉取消 facade，而是用 Instruments 测量桥接成本，再考虑分页、增量 diff 或 Objective-C collection 直读。

## 原则四：sealed result 不要强迫 Swift 猜 subtype

Objective-C framework 路径下，Kotlin sealed interface 会变成 protocol 加多个 classes。

Swift importer 不会把它恢复成可穷尽 Swift enum。

因此 PocketLab 不再直接导出：

```kotlin
sealed interface RefreshExperimentsResult
```

而是导出一个固定 report：

```kotlin
@ObjCName(swiftName = "RefreshReport")
class IosRefreshReport internal constructor(
    val isSuccessful: Boolean,
    val receivedCount: Int,
    val changedCount: Int,
    val failureCode: String?,
    val hasFailureStatusCode: Boolean,
    val failureStatusCode: Int,
)
```

这里看起来比 sealed class “笨”，但它有一个重要目的：成为稳定 wire shape。

Swift wrapper 再把它翻译成原生 enum：

```swift
enum RefreshOutcome: Sendable, Equatable {
    case updated(changedCount: Int32)
    case unchanged
    case rejected(RefreshRejection)
}
```

最终 ViewModel 得到穷尽 `switch`。

### 为什么不用 `Int? statusCode`

Objective-C 不能像 Kotlin/Swift 一样直接表达 nullable primitive。`Int?` 通常会变成 `KotlinInt?` / `NSNumber` 风格的 box。

PocketLab 使用：

```text
hasFailureStatusCode: Bool
failureStatusCode: Int32
```

这不是所有 API 的通用答案，却能让当前 ABI 保持 primitive，并把 optional 恢复留在 Swift wrapper：

```swift
let statusCode = report.hasFailureStatusCode
    ? report.failureStatusCode
    : nil
```

跨语言 API 设计经常需要这种“边界上稍显朴素，平台端重新变优雅”的两段式结构。

## 稳定 code 比共享 enum 更耐版本差异

失败原因通过稳定字符串传递：

```text
unauthorized
server
unexpected_status
malformed_response
connection
```

Swift 映射为：

```swift
enum RefreshRejection: Sendable, Equatable {
    case unauthorized
    case server(statusCode: Int32?)
    case unexpectedStatus(statusCode: Int32?)
    case malformedResponse
    case connection
    case unknown(code: String, statusCode: Int32?)
}
```

`unknown` 不是懒惰的 default。

它是版本兼容策略：如果未来 Kotlin framework 新增 failure code，而旧 Swift 壳还没升级，App 仍能保留原始 code 并给出安全退化，而不是因为强制转换失败崩溃。

## 原则五：底层 callback 是实现原语，不是产品 API

Objective-C framework 最稳定的异步原语仍然是 callback。PocketLab 需要它承载：

- Flow 的多次值；
- refresh 的一次结果；
- 显式 cancellation token。

但 Swift 调用者应该使用 `AsyncSequence` / `async`。

因此 Kotlin 底层方法加上：

```kotlin
@ShouldRefineInSwift
fun subscribe(...): NativeCancellable

@ShouldRefineInSwift
fun refresh(...): NativeCancellable
```

生成 header 出现：

```objc
- (...)subscribeOnChange:... __attribute__((swift_private));
- (...)refreshOnResult:... __attribute__((swift_private));
```

Swift 仍可以在 wrapper 内调用：

```swift
client.__subscribe(...)
client.__refresh(...)
```

但 Xcode 普通补全不会把它们当作推荐 API。

公开体验只剩：

```swift
func experiments() -> AsyncThrowingStream<[Experiment], any Error>
func refreshOnce() async throws -> RefreshOutcome
```

`@ShouldRefineInSwift` 不是把 callback 变成 async。它只是向 importer 声明：“这是给 Swift wrapper 精炼的底层入口。”真正的取消、buffering、错误与 Sendable 语义仍由 wrapper 负责。

## Swift 6：把 `@unchecked Sendable` 缩到不能再小

改造前，Swift stream element 是：

```swift
UncheckedSendable<[ExperimentRecord]>
```

这等于告诉 Swift 编译器：“请相信我，这组 Kotlin objects 可以安全跨并发边界。”

改造后，callback 内先复制：

```swift
continuation.yield(
    snapshots.map(Experiment.init(snapshot:))
)
```

stream element 变成真正满足 `Sendable` 的 Swift struct array。

剩下唯一的 `@unchecked Sendable` 是 `NativeCancellationBox`：

```swift
private final class NativeCancellationBox: @unchecked Sendable {
    private let lock = NSLock()
    private var token: CancellationToken?
}
```

它的责任非常窄：处理 `Task` 可能先结束、Kotlin token 后返回的竞态，并用锁保护 token 安装与取消。

这就是合理的 FFI 安全盒：

- 范围小；
- 不承载业务数据；
- 同步规则写在类内部；
- Swift 6 complete strict concurrency 会持续检查盒子外的代码。

## 错误到底应该用 result、callback 还是 `@Throws`？

不能按个人口味统一成一种。

PocketLab 区分三类失败。

### 1. 预期业务失败：返回 value

401、503、离线和坏响应是 refresh 协议的一部分：

```swift
RefreshOutcome.rejected(...)
```

它们不是异常控制流。

### 2. 意外 bridge 失败：结束 stream 并抛 Swift Error

Kotlin callback 返回：

```kotlin
class IosInteropFailure(
    val code: String,
    val message: String,
)
```

Swift 转为：

```swift
struct PocketLabClientError: LocalizedError, Sendable
```

相比只有 message，稳定 code 可以用于日志、指标和平台文案映射。PocketLab 的 `errorDescription` 根据 code 生成中文用户文案；Kotlin message 只保留为诊断信息，不直接承诺给用户。

### 3. 程序错误：不要包装成“网络失败”

违反不变量、错误强转和内部 bug 不应该被统一吞成友好 toast。它们需要失败、记录并修复。

### `@Throws` 的位置

对 Objective-C framework，带 `@Throws` 的非 suspend Kotlin 函数会被 Swift 导入为 `throws`；未声明的 Kotlin exception 穿过边界可能直接终止程序。

所以 `@Throws` 应用于边界上明确列出的异常契约，不能当作“让所有 Kotlin exception 自动变 Swift Error”的开关。

PocketLab 当前 callback bridge 已在 Kotlin scope 内捕获意外 `Exception` 并翻译为 `InteropFailure`，因此没有为了形式统一再叠一层 `@Throws`。

## 名字、形状和行为必须一起审计

只加 `@ObjCName`，会得到名字漂亮、类型仍难用的 API。

只写 Swift extension，会让 raw callback 与内部类型继续污染补全。

只加 `@HiddenFromObjC`，又可能把真正需要的能力一起藏掉。

PocketLab 的组合是：

| 问题 | 使用的边界工具 |
|---|---|
| 不应导出的共同实现 | `internal` / `@HiddenFromObjC` |
| Swift 名称包含 `Ios` 等实现痕迹 | `@ObjCName(swiftName = ...)` |
| callback 只供 wrapper 使用 | `@ShouldRefineInSwift` |
| sealed result 在 Swift 不可穷尽 | 固定 Kotlin report + Swift native enum |
| Kotlin reference 不满足 Sendable | 立即复制为 Swift value type |
| nullable primitive 会 boxing | value + presence flag |
| 新 failure code 遇到旧 App | stable code + `.unknown` |
| Flow / Job 取消 | `AsyncStream.onTermination` + token |

## header 行数不是 KPI，但 diff 是证据

这次实验记录了三个阶段：

| 阶段 | header 行数 |
|---|---:|
| 里程碑 11 原始 framework | 2,834 |
| 加入 Apple DTO 和 KDoc，尚未隐藏共同类型 | 2,888 |
| 隐藏共同 record / result / repository 图后 | 2,267 |

不能据此得出“越短越好”。一个有文档的必要 API 当然会增加行数。

真正有价值的是 diff 能回答：

- 是否意外出现新 public class？
- Swift name 是否改变？
- callback 是否仍为 `swift_private`？
- internal constructor 是否重新泄漏？
- `Int` 是否意外变成 boxed nullable？
- dependency types 是否进入 framework surface？

所以 PocketLab 新增了可执行门禁：

```shell
scripts/verify-apple-api.sh
```

它会：

1. 构建 Debug framework；
2. 检查预期 Swift names；
3. 检查 raw callbacks 带 `swift_private`；
4. 拒绝 `ExperimentRecord`、`RefreshExperimentsResult` 和内部构造器泄漏；
5. 用 Swift 6 `-strict-concurrency=complete` type-check 完整 SwiftUI source。

这还不是完整的 binary compatibility checker，但已经把最容易回归的设计意图变成了机器可执行事实。

## KDoc 也是 API 的一部分

Kotlin KDoc 会进入 KLIB，并可继续出现在生成的 Objective-C header 和 Xcode Quick Help 中。

这次生成 header 能直接看到：

```objc
/**
 * Immutable Apple-facing experiment snapshot.
 *
 * The shared domain model stays platform-neutral...
 */
```

对跨平台库而言，文档至少应该说明：

- callback 在哪个 dispatcher 到达；
- token 是否幂等取消；
- `close()` 后对象能否复用；
- failure code 的稳定性；
- list 是快照还是 live view；
- 时间单位与 epoch；
- 谁拥有资源生命周期。

“Kotlin 代码自解释”在跨语言 importer 面前通常不成立。

## 2026 年的 Swift Export：能力已经很强，为什么还没切？

Kotlin 2.4 的 Swift Export 已经比早期印象强很多。官方当前文档列出了：

- Kotlin package 保留或 flatten；
- Kotlin enum → Swift enum；
- sealed hierarchy → 可穷尽 Swift enum；
- suspend → Swift async；
- `Flow` → `AsyncSequence`；
- 多模块导出；
- cross-language inheritance / reverse import。

这恰好能减少本篇很多手写 wrapper。

但官方仍明确标为 Alpha，并提醒 breaking changes；当前只支持 direct integration，generic type parameters 仍可能被擦除，也没有自动迁移工具。

PocketLab 因此做出阶段性选择：

```text
现在：稳定 Objective-C framework + 明确 Apple facade + Swift wrapper
并行实验：Swift Export 单独验证，不替换发布路径
切换条件：能力覆盖 + 工具链稳定 + API diff + 双端迁移成本可接受
```

“Alpha 所以不能碰”不是专家判断；“官方新功能所以立刻全量切换”也不是。

正确做法是用同一组 API quality gates 比较两条出口。

## 不要让生成工具替你决定产品 API

无论使用手写 bridge、Swift Export，还是第三方 coroutine wrapper，都只能自动化一部分机械翻译：

- callback ↔ async；
- Flow ↔ AsyncSequence；
- annotation ↔ generated declarations。

它们不会自动回答：

- 数据库 record 是否应该成为 iOS 产品模型；
- 失败是 value 还是 error；
- 哪些名字属于用户语言；
- 新 code 遇到旧 App 如何退化；
- 哪个对象拥有 close；
- 是否值得为零复制牺牲 Sendable 与隔离。

生成工具可以减少胶水，不能替代边界设计。

## 本次验证矩阵

新增两个 iOS 专用场景：

1. 共同 `ExperimentRecord` 被映射为 Apple snapshot，pending 状态不再泄漏 Kotlin enum；
2. refresh failure 被映射为稳定 code、primitive status 与 presence flag。

当前结果：

| 验证 | 结果 |
|---|---:|
| JVM shared | 39/39 |
| Android Host shared | 39/39 |
| iOS x64 shared / interop | 47/47 |
| Android ViewModel | 2/2 |
| Android Compose UI | 2/2 |
| iOS XCTest UI | 1/1 |
| 不同测试场景 | 54 |
| 自动化执行总数 | 130/130 |
| Apple API header gate | 通过 |
| Swift 6 complete strict-concurrency | 通过 |

共同模块共有 125 次目标执行：JVM 39、Android Host 39、iOS 47。加上 Android 壳 4 次与 iOS UI 1 次，得到 130。

## 七个常见误解

### 1. “Kotlin public 就应该给 Swift public”

两个调用群体不同。用 `internal`、`@HiddenFromObjC` 或 export facade 重新定义 Apple surface。

### 2. “加个好听的名字就 Swift-friendly 了”

名字、类型、并发、错误和生命周期必须一起审计。

### 3. “sealed class 到 Swift 还是 sealed class”

Objective-C framework 路径不会自动恢复穷尽 enum；Swift Export 的映射更强，但当前仍为 Alpha。

### 4. “直接共享 data class 最省代码”

可能同时泄漏存储模型、枚举、copy 方法和未来演进约束。边界 DTO 的重复有时是在购买解耦。

### 5. “所有 failure 都应该 throws”

预期业务失败适合 value，意外桥接失败适合 Error，程序错误不应伪装成普通网络失败。

### 6. “用 `@preconcurrency import`，再拿 `@unchecked Sendable` 包住 payload 就结束了”

它只是关闭编译器检查。应尽量复制为 Swift value，把豁免压缩到受锁保护的 FFI 小盒子。

### 7. “header 能生成，API 就兼容”

还需要 Swift source type-check、header contract、运行测试与升级 diff。

## PocketLab 的 API 评审清单

每个准备给 Swift 使用的 Kotlin declaration 都要回答：

1. Swift 真的需要它吗？
2. 它的 Swift 名称描述能力还是实现？
3. 参数标签读起来像 Swift 调用吗？
4. 是否泄漏 repository、DTO、Flow、coroutine 或 SQLDelight 类型？
5. nullable primitive 会不会 boxing？
6. enum / sealed hierarchy 能否安全处理未知值？
7. collection 转换和复制是否可接受？
8. callback 在哪个 dispatcher 到达？
9. cancellation 是否双向传播且幂等？
10. expected failure 与 unexpected error 是否分开？
11. Swift 6 是否只在最窄位置需要 `@unchecked Sendable`？
12. KDoc、header、Swift wrapper 和运行测试是否共同证明契约？

## 读者挑战

1. 临时去掉 `@HiddenFromObjC`，比较 header 中重新出现的类型图，并解释每个类型为什么不属于 Swift 产品 API；
2. 把 `failureStatusCode` 改回 `Int?`，观察 header 与 Swift 类型如何发生 boxing；
3. 去掉 `@ShouldRefineInSwift`，在 Xcode 补全中比较 raw callback 与 refined API；
4. 给 stable code 新增一个 Kotlin 值，但不修改 Swift wrapper，验证 `.unknown` 路径；
5. 把 snapshot 改为直接传 `ExperimentRecord`，记录 Swift 6 Sendable 错误与所需豁免；
6. 建立一个独立 `apple-api` KMP module，只 export facade，比较 header surface；
7. 用 Swift Export 并行生成同一 API，比较 enum、sealed、Flow、包名与 generic 的实际结果。

## 写在最后

KMP 最容易制造一种错觉：共享代码写完了，平台 API 自然就存在了。

实际上，平台 API 不是编译器副产品，而是一份需要设计、测试和演进的产品。

这次 PocketLab 没有改变共同业务规则，却完成了五个重要收口：

- 共同实现不再默认进入 Apple surface；
- Swift 名称从实现术语变成能力术语；
- database record 与 sealed result 被 Apple snapshot/report 隔离；
- callback 被降为隐藏的实现原语；
- SwiftUI 最终只消费原生、可穷尽、可发送的 Swift types。

真正好的跨平台边界，不是让 Swift 代码看起来像“能调用 Kotlin”。

而是让 Swift 开发者在大多数时候不必思考 Kotlin 是怎么实现的。

下一篇，我们会继续沿着产物边界前进：同一份共享模块，什么时候交付 framework，什么时候组 XCFramework，又什么时候应该选择 Swift Package Manager 或 CocoaPods？

## 官方资料

- [Kotlin/Native 与 Swift/Objective-C 互操作](https://kotlinlang.org/docs/native-objc-interop.html)
- [Swift Export](https://kotlinlang.org/docs/native-swift-export.html)
- [`@ObjCName` API](https://kotlinlang.org/api/core/kotlin-stdlib/kotlin.native/-obj-c-name/)
- [`@ShouldRefineInSwift` API](https://kotlinlang.org/api/core/kotlin-stdlib/kotlin.native/-should-refine-in-swift/)
- [`@HiddenFromObjC` 所在的 Native API](https://kotlinlang.org/api/core/kotlin-stdlib/kotlin.native/)
- [Kotlin/Native as an Apple framework](https://kotlinlang.org/docs/apple-framework.html)
- [Apple: Improving Objective-C API declarations for Swift](https://developer.apple.com/documentation/swift/improving-objective-c-api-declarations-for-swift)
- [Swift API Design Guidelines](https://www.swift.org/documentation/api-design-guidelines/)
