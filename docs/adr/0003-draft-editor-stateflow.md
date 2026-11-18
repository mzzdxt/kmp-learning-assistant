# ADR-0003：用 StateFlow 表达草稿编辑状态

- 状态：已接受
- 日期：2026-11-18
- 决策范围：PocketLab 创建草稿的数据流与协程生命周期

## 背景

同步 `SaveDraftUseCase` 无法表达数据库等异步实现；Android Compose 与 iOS SwiftUI 都需要观察编辑、保存中、成功、校验失败和基础设施失败状态。共享层不能依赖 Android `ViewModel` 或 SwiftUI `ObservableObject`。

## 决策

- `ExperimentRepository.save` 与 `SaveDraftUseCase.execute` 改为 `suspend`。
- 创建平台无关的 `DraftEditorStore`。
- Store 内部持有 `MutableStateFlow<DraftEditorState>`，外部只暴露 `StateFlow`。
- 状态机使用 `IDLE → SAVING → SAVED / REJECTED / FAILED`。
- CoroutineScope 由平台宿主注入并拥有；Store 不创建全局 Scope，也不硬编码 Main/IO dispatcher。
- 保存开始用 `MutableStateFlow.compareAndSet` 原子转换；保存期间拒绝第二次提交和字段编辑。
- `CancellationException` 恢复到 IDLE 后继续抛出，其他 `Exception` 映射为稳定 FAILED 状态。
- commonTest 使用 `kotlinx-coroutines-test` 的虚拟时间验证状态序列。

## 理由

- StateFlow 始终有当前值，适合声明式 UI 渲染屏幕状态。
- 私有 MutableStateFlow 防止平台 UI 绕过 Store 修改共享状态。
- 注入 Scope 明确生命周期所有权，避免共享对象永久存活。
- 不在 UseCase 内硬编码 dispatcher，使测试可控，也允许数据库实现决定真正的执行上下文。
- 状态与校验问题都是可恢复的持久 UI 信息，不需要一次性事件通道。

## 后果

正面：

- 两端观察相同状态机，不复制保存竞态处理。
- 重复提交、失败和取消路径可在 commonTest 验证。
- `runTest` 用虚拟时间跳过仓库延迟，测试保持快速确定。

代价：

- Android 与 iOS 仍需各自建立生命周期安全的收集桥接。
- StateFlow 导出到 Swift 后不会自动成为最自然的 Swift API；后续需评估原生包装或桥接库。
- 当前 FAILED 不携带错误分类，接入数据库/网络后需建立稳定错误模型。
- 保存期间字段编辑被忽略，这是明确但较保守的 UX 策略，后续可用 operation token 支持并发编辑。

## 拒绝的方案

### Store 内创建 `CoroutineScope(SupervisorJob() + Dispatchers.Main)`

会隐藏生命周期所有权，并在 commonTest 与 Native 环境中增加 Main dispatcher 配置负担。

### 直接暴露 `MutableStateFlow`

平台 UI 可以任意写入 `SAVED` 或修改校验问题，破坏状态机不变量。

### 使用 Channel 表达屏幕当前状态

Channel 面向逐条消费；新订阅者不能天然获得完整当前状态，不适合页面重建后的渲染。

