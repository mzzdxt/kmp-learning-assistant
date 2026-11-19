# ADR-0009：采用现代 Native 共享堆，并显式设计并发与跨 ARC 所有权

- 状态：Accepted
- 日期：2026-11-19
- 决策范围：PocketLab Kotlin/Native 内存、并发与 Swift/Objective-C 生命周期边界

## 背景

PocketLab 的共同层通过 Kotlin/Native static framework 进入 SwiftUI App。共同代码包含 SQLDelight、Ktor、Flow、suspend API 与 Apple callback bridge；Swift 侧包含 `Task`、`AsyncStream`、`ObservableObject` 与 SwiftUI 生命周期。

旧 Kotlin/Native memory manager 曾要求跨线程对象冻结，并产生 `InvalidMutabilityException` 等限制。当前项目使用 Kotlin 2.4.21；legacy memory manager 已从 Kotlin 1.9.20 移除，继续套用 freezing 方案会引入过时 API 和错误心智模型。

现代共享 heap 允许对象被任意线程访问，但不会自动同步复合读写。与此同时，Kotlin tracing GC 与 Swift/Objective-C ARC 使用不同的可达性/所有权模型；纯 Kotlin 引用环与包含 Objective-C 对象的混合环有不同回收结果。

## 决策

### 现代 memory manager

- 不使用 `freeze()`、`isFrozen`、`@SharedImmutable`、`FreezingException`、`InvalidMutabilityException` 或 `native-mt` 依赖；
- 允许 common object 跨线程/dispatcher 传递，但不把“可访问”解释为“线程安全”；
- 共享数据默认使用 immutable snapshot 与只读 Flow/StateFlow；
- 必须共享可变状态时，显式选择 `Mutex`、atomic primitive 或单一 coroutine owner；
- 不为业务对象自行创建不可控全局线程或 Worker。

### 平台与 UI 并发

- Android ViewModel 继续由 `viewModelScope` 拥有工作；
- Swift observable state 继续由 `@MainActor` 隔离；
- Kotlin callback delivery dispatcher 必须由 bridge 明确声明，不能假设 suspend completion 自动回主线程；
- cancellation 在 Kotlin Job 与 Swift Task 间通过显式 token / termination protocol 传播。

### GC 与资源生命周期

- Kotlin 对象由 Native tracing GC 管理，Swift/Objective-C 对象由 ARC 管理；
- `GC.collect()`、`GC.lastGCInfo()`、GC logs 与 safepoint signposts 只用于测试和诊断；
- 不在日常业务代码中手动 collect，不以 `gc=noop` 规避 pause；
- network、database、Flow collection 与 callback 必须显式 close/cancel，不等待 GC、ARC 或 `deinit`；
- 对跨边界 callback 默认执行强引用环审计，Swift owner 使用弱捕获或由取消协议切断；
- interop hot loop 只有在指标证明临时 Objective-C 对象堆积时才增加 `autoreleasepool`。

### 产物与性能结论

- KLIB、framework、Xcode final link 与 App archive 视为独立阶段；
- static framework 的未定义系统符号由最终 App linker 满足；
- framework 文件大小不作为 App 增量体积结论；
- Debug 用于日常反馈，Release/LTO 与最终 App archive 用于发布性能和体积验证；
- GC 或内存问题使用 Instruments、runtime logs 与可复现 workload 取证后再调参。

## 当前证据

### 编译产物

- iosX64 KLIB 约 332 KB，manifest 标明 Kotlin 2.4.21、NATIVE、ios_x64 与完整依赖；
- Debug static framework 是 x86_64 `ar` archive，约 67 MB；
- Release static framework 是 x86_64 `ar` archive，约 12 MB；
- generated Objective-C header 有 2,834 行；
- archive 保留 `_sqlite3_open_v2` 未定义引用，最终 App 使用 `-lsqlite3` 完成链接。
- Kotlin 2.4.21 已警告 `macos_x64` Native host 弃用；现有 iosX64 证据用于 Intel Mac 复现，后续迁往 Apple Silicon host 与 arm64 targets。

这些数字是当前本机构建快照，不作为跨机器性能承诺。

### 并发

`SharedHeapConcurrencyTest` 将同一个可变 ledger 交给两个 single-thread dispatcher，200 次修改全部由 `Mutex` 序列化。该 commonTest 在 JVM、Android Host 和 iOS x64 通过。

### GC

`NativeGarbageCollectorTest` 创建与 roots 断开的纯 Kotlin 强引用环，只保留弱引用；完成一次 Native GC 后弱引用被清空，证明纯 Kotlin cycle 可由 tracing GC 回收。

该测试不证明混合 Swift/Kotlin cycle 可回收。官方 ARC integration 文档明确指出：包含 Objective-C 对象的混合强引用环不能由 Kotlin GC 单独打破。

## 后果

### 正面

- common concurrency primitives 不再被 legacy freezing 限制污染；
- 共享 heap、并发同步和生命周期被拆成三个可分别审计的责任；
- Android/iOS 状态更新继续遵守平台线程/actor 模型；
- callback cycle、取消和资源关闭具有明确断环点；
- 性能与体积决策基于真实 Release/App/Instruments 证据；
- 测试直接验证现代 Native 行为，而不是引用版本无关口号。

### 成本

- 团队仍需主动设计数据竞态保护；现代 memory manager 不提供自动线程安全；
- Swift/Kotlin 混合对象图需要人工所有权审计；
- Native runtime API 与 collector 算法仍会演进，诊断代码需要随版本校准；
- Release Native link、Simulator 和 Instruments 增加本地与 CI 成本；
- static framework 的系统依赖和最终链接仍由 iOS 宿主维护。

## 被拒绝的方案

### 延续 legacy freezing 模式

相关 memory manager 已移除，freezing 始终关闭。继续封装 `freeze()` 或引入 `native-mt` 只会保留过时限制，因此拒绝。

### 因共享 heap 可访问而直接共享普通 MutableMap

可访问性不建立互斥或 happens-before。复合读改写仍有数据竞态，因此拒绝。

### 所有状态统一强制 Main dispatcher

可以避免部分竞态，却把数据库、网络和 CPU 工作错误地绑定 UI 线程，也掩盖真实所有权。只在 UI/callback contract 需要时使用 Main，其他状态按责任选择同步方式。

### 依赖 GC/ARC 自动停止任务和关闭资源

回收时机非确定，且 async work 可能自身维持 root/reference chain。生命周期资源必须显式 cancel/close，因此拒绝。

### 用 Debug framework 大小估算产品包体

Debug archive 含调试与未优化内容，static linker 还会选择和剥离对象。只有最终 Release App 的同条件对照有效，因此拒绝。

### 预先修改 GC 算法与参数

collector 持续演进，未经 profiling 的参数改变可能增加 pause 或内存。先测量，再在可回滚实验中修改，因此拒绝。

## 重新评估条件

- Kotlin/Native memory manager、collector 或 Swift Export 所有权模型发生重大变化；
- 引入共享 ViewModel 或 Compose Multiplatform UI，扩大跨线程状态 surface；
- Instruments 显示 GC pause 或 stable refs 成为实际性能瓶颈；
- 引入大量 Swift callbacks、delegates 或跨边界对象缓存；
- 从 iosX64 迁移到 Apple Silicon targets 并建立 XCFramework 分发；
- 使用新的 Native atomics、lock-free structure 或 actor abstraction。

## 验证

- JVM 39/39、Android Host 39/39、iOS x64 45/45；
- Android ViewModel 2/2、Android Compose UI 2/2、iOS UI 1/1；
- 52 个不同场景，128/128 次自动化执行；
- Debug 与 Release iosX64 static framework 成功构建；
- KLIB manifest、framework type/architecture、generated header 和 sqlite3 symbol 均由本地工具检查。
