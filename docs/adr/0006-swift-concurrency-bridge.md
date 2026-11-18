# ADR-0006：保留 Kotlin 原生异步模型，在 Apple 边界显式桥接取消

- 状态：已接受
- 日期：2026-11-19
- 决策范围：PocketLab 的 `Flow` / `suspend` API、Objective-C framework 导出、Swift Concurrency 消费与生命周期

## 背景

PocketLab 的共同数据层已经自然使用 Kotlin 协程：数据库暴露 `Flow<List<ExperimentRecord>>`，刷新用例是 `suspend fun execute()`。Android 可以直接收集 Flow，但当前标准 Kotlin/Native framework 仍通过 Objective-C header 进入 Swift。

实际生成的 header 表明：

- `suspend` 函数导出为 completion-handler 方法，Swift 5.5+ 可以把它导入为 `async`；
- `Flow<T>` 只导出为 Objective-C protocol，元素泛型在这个边界上不再适合 Swift 直接消费；
- Swift `Task` 与 Kotlin `Job` 是两个取消树，语法上的 `await` 不足以证明取消会跨边界传播；
- 回调可能从非主线程到达，SwiftUI 状态更新需要明确执行上下文。

Kotlin 2.4.21 已提供 Alpha 状态的 Swift Export，可把 suspend 映射为 Swift `async`、把 Flow 映射为 `AsyncSequence`。但官方仍声明它不完整、可能发生破坏性变化，且当前只支持 Xcode direct integration。PocketLab 尚未建立 iOS 壳工程，因此不能把 Alpha 能力当成当前稳定集成的前提。

## 决策

- 共同业务 API 保持 Kotlin 原生形态：数据流继续使用 `Flow`，一次性异步工作继续使用 `suspend`。
- 不为了 Swift 改写 Repository 或 UseCase，也不把回调接口放进 `commonMain`。
- 标准 iOS 产物生成静态 `PocketLabShared.framework`，以真实 header 和 Swift 编译器作为互操作证据。
- 在 `iosX64Main` 提供薄 `IosExperimentListBridge`：
  - 在 `Dispatchers.Main` 上收集数据库 Flow；
  - 把列表变化翻译为 `onChange` 回调；
  - 把刷新翻译为独立的结果与意外异常回调；
  - 每次操作返回 `NativeCancellable`，内部持有相应 Kotlin `Job`；
  - `close()` 取消桥拥有的 `SupervisorJob` 及全部子任务。
- Swift 侧使用 `AsyncThrowingStream` / `AsyncStream` 把回调恢复为 `AsyncSequence`。
- Swift `AsyncStream.Continuation.onTermination` 必须调用 `NativeCancellable.cancel()`，把 Swift Task 取消传播给 Kotlin Job。
- Swift 适配器使用带锁的 token box，处理 Task 先取消、Kotlin token 后返回的竞态。
- 直接导入的 suspend → async 只用于展示编译器映射；需要可靠双向取消的生产路径使用显式 token 桥或成熟桥接工具。
- 当前不启用 Swift Export Alpha；等 iOS direct-integration 壳工程建立后，以独立实验评估再迁移。

## 理由

- Flow 和 suspend 是共同业务层最自然、最容易测试的抽象，不应被某个平台的 FFI 形状反向污染。
- 适配层放在 Apple source set，使 Objective-C/Swift 的限制保持局部，也允许未来删除它而不改共同数据层。
- 显式 token 让取消成为可观察、可测试的契约，而不是依赖编译器导入启发式。
- `Dispatchers.Main` 保证回调进入 SwiftUI 时位于主执行上下文；数据库查询仍可在 Repository 的查询 dispatcher 上完成。
- `SupervisorJob` 让一个订阅失败不会自动取消同桥的其他任务，而 `close()` 仍能统一结束全部工作。
- `AsyncStream` 是 Swift 标准库用来把 callback/delegate API 适配为 `AsyncSequence` 的原生机制；`onTermination` 能覆盖 Task 取消和消费者停止迭代。
- 保留标准 framework 路径，可以在没有完整 iOS App 的阶段通过 header、Native 测试和 Swift type-check 得到真实证据。

## 后果

正面：

- Android/Kotlin 调用者继续使用原生 Flow 与 suspend，没有跨平台最小公分母 API。
- Swift 调用者可以写 `for try await`，并让 Task 取消真正取消 Kotlin collection。
- 列表订阅、刷新、单项取消与整体关闭都有明确所有者。
- Swift 6 严格并发检查可以直接验证包装代码。
- 将来迁移 Swift Export、SKIE 或 KMP-NativeCoroutines 时，只替换 Apple 边界。

代价与限制：

- 当前需要维护一小段 Kotlin callback bridge 和 Swift `AsyncStream` 包装。
- `RefreshExperimentsResult` 经标准 Objective-C 导出后仍是 protocol + 子类，Swift `switch` 体验不如原生 enum；这会在“为 Swift 设计 API”阶段继续处理。
- 当前桥只针对实验列表，尚未抽象为通用泛型桥；刻意避免过早创造跨领域框架。
- iOS bridge 测试能证明 Kotlin Job 取消与回调行为，Swift type-check 能证明 API 形状，但尚未在真实 SwiftUI 生命周期中运行。
- Kotlin/Native 的 macOS x64 host 已被弃用，当前 Intel 构建只是过渡环境。

## 拒绝的方案

### 让 Swift 直接使用原始 `Flow`

标准 Objective-C 导出中的 Flow 是协程 protocol，泛型和 collection 生命周期对 Swift 都不自然。让每个页面理解 Kotlin Flow internals 会把互操作成本扩散到 UI。

### 把所有共同 API 改成 callback

这会让 Android/Kotlin 端失去结构化并发与 Flow 操作符，同时让平台限制污染共同业务接口。callback 只应存在于 FFI 适配边界。

### 只依赖 suspend 自动导入为 Swift async

它能简化语法，但不能单独证明 Swift Task 取消会传播到 Kotlin Job。PocketLab 需要可测试的取消语义，因此不能把“看起来像 async”当成完整并发契约。

### 立即切换 Swift Export Alpha

Swift Export 的方向与 PocketLab 目标一致，而且已经支持 suspend 与 Flow。但当前官方状态仍是 Alpha，只支持 direct integration，并存在泛型等限制。应在拥有真实 iOS 壳工程后做独立对照实验，而不是把第一个稳定桥建立在未满足的集成前提上。

### 立即引入 KMP-NativeCoroutines 或 SKIE

二者都能提供更完整的自动桥接，在较大工程中值得采用。本轮先手写一条很窄的桥，以看清 Job、Task、回调、线程和取消之间的真实关系；等 API 数量增长后，再以生成代码、版本兼容、二进制影响和团队体验评估工具化方案。
