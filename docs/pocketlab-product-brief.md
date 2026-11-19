# PocketLab 产品简报

## 一句话说明

PocketLab 是一个离线优先的个人实验记录器：把“我猜……”“我准备怎么验证……”“结果怎样”变成可追踪的小实验。

它不是为了展示 KMP API 而虚构的 Todo App。实验记录天然包含结构化规则、本地存储、搜索、同步状态、时间与冲突处理，足以让我们逐步遇到真实跨平台问题。

## 第一条用户旅程

```text
创建草稿 → 填写标题与假设 → 校验 → 离线保存 → 出现在实验列表
```

项目从“创建并保存草稿”启动；当前已经加入远端读取、本地数据库、刷新合并、Apple 并发桥，以及 Android Compose / iOS SwiftUI 原生实验列表。上传、附件、详情与搜索继续增量加入。

## 共享业务约束

- 标题必填，去除首尾空白后最长 80 个字符。
- 假设必填，去除首尾空白后最长 240 个字符。
- 笔记可为空；保存时去除首尾空白。
- 校验失败时不得生成 ID、读取时钟或写入仓库。
- 成功保存必须包含稳定 ID 与保存时间。
- 校验结果使用稳定 reason code，而不是共享中文/英文提示文案。

这些规则在 Android 与 iOS 上含义相同，因此进入 `commonMain`。

## 暂不共享的职责

- 表单布局、键盘行为、返回手势与导航。
- 错误提示的本地化文案与视觉呈现。
- Android/iOS 应用生命周期。
- 数据库驱动和平台文件位置——先用仓库接口隔离，选型实验后再实现。
- 系统权限与通知。

## MVP 后续增量

1. 本地实验列表与详情（SQLDelight 列表数据源与 Flow 已完成，详情查询待实现）。
2. 按状态和关键字搜索。
3. Ktor 远端读取、同步与离线队列（远端读取与数据库刷新合并已完成，上传待实现）。
4. 冲突检测和可解释的同步状态机。
5. Android Compose 与 iOS SwiftUI 原生实验列表（已完成；详情、编辑与搜索待实现）。

## 当前验收标准

- 保存用例不引用 Android、UIKit、数据库或网络类型。
- 业务规则、错误码、用例编排和外部能力接口位于 `commonMain`。
- fake 位于 `commonTest`，不进入生产代码。
- 同一组测试在 JVM、Android Host 与 iOS Simulator 通过。
- Apple 专用互操作行为在 iOS Native 测试中验证，Swift 包装通过 Swift 6 严格并发 type-check。
- Android 与 iOS 宿主工程都能编译、最终链接并在模拟器启动；平台 ViewModel 不复制刷新与合并规则。
- 共同测试在 JVM、Android Host 与 iOS Native 分别执行；平台 UI 由 Compose semantics 与 XCTest UI smoke 补齐。

## 当前共享状态流

保存草稿已从同步调用演进为：

```text
平台 UI 输入
→ DraftEditorStore
→ StateFlow<DraftEditorState>
→ suspend SaveDraftUseCase
→ suspend ExperimentRepository
```

平台 UI 负责生命周期与渲染，共享 Store 负责保存状态机、校验和重复提交约束。Apple Flow 取消桥已经接入 SwiftUI `.task`，Android 列表通过 `collectAsStateWithLifecycle` 观察平台 ViewModel。创建/编辑页面仍待接入。

## 当前远端读取边界

PocketLab 已加入第一条共同网络管线：

```text
ExperimentRemoteDataSource
→ GET /v1/experiments
→ ContentNegotiation + kotlinx.serialization
→ DTO 映射与稳定失败分类
→ CIO / Android / Darwin 平台引擎
```

共同代码负责请求与协议语义，平台 source set 只选择 Ktor engine。公共接口不暴露 Ktor 类型、传输异常或 JSON DTO。远端列表读取已经通过 `RefreshExperimentsUseCase` 接入本地数据库；平台 UI 将在原生界面阶段只观察数据库 `Flow`。上传与离线队列尚未实现。

## 当前离线优先边界

远端读取已接入本地事实来源：

```text
本地保存 → SQLDelight → PENDING_UPLOAD ─┐
                                        ├→ 数据库 Flow → 平台 UI
Ktor 刷新 → 事务合并 → SYNCED ──────────┘
```

- UI 只观察数据库，不直接消费网络列表。
- pending 本地记录不会被远端刷新覆盖。
- 已同步记录只接受更新的服务器版本。
- 整批刷新在一个事务内合并。
- 当前只有远端读取与本地 pending 标记，尚未实现上传确认、重试、删除 tombstone 或完整 outbox。

## 当前 Swift Concurrency 边界

共同层继续使用原生异步抽象，Apple 边界只负责翻译：

```text
Flow / suspend
→ IosExperimentListBridge
→ callback + NativeCancellable
→ Swift AsyncStream / async
```

- 标准 Objective-C framework 是当前稳定产物。
- Flow collection 与 refresh 各自返回可取消的 Kotlin Job 句柄。
- Swift `AsyncStream.onTermination` 把 Task 取消传播回 Kotlin Job。
- 生产 callback 由 `Dispatchers.Main` 送达，SwiftUI 状态仍应由 `@MainActor` 隔离。
- Swift Export 已支持 suspend 与 Flow 的原生映射，但当前仍为 Alpha；等真实 iOS direct-integration 壳工程建立后再单独评估。

## 当前原生 UI 边界

实验列表采用“共享数据与领域层、平台 ViewModel、原生 UI”方案：

```text
Android Compose → AndroidX ViewModel ─┐
                                     ├→ Refresh UseCase → SQLDelight → database Flow
SwiftUI → @MainActor ObservableObject ┘
```

- Android 使用 `viewModelScope` 与 `collectAsStateWithLifecycle`。
- iOS 使用 SwiftUI `.task`、`.refreshable` 与 AsyncSequence cancellation bridge。
- 两端都只从 SQLDelight 读取列表，remote response 不直接进入 UI。
- 稳定失败 reason 共享；文案、日期格式、空状态和交互属于平台。
- iOS `IosPocketLabFeature` 显式拥有并关闭 bridge、network 与 database。
- 当前 Swift 6 FFI `Sendable` 豁免被限制在 Main dispatcher / MainActor 审计过的窄盒子。
- Compose Multiplatform UI 与共享 ViewModel 都保留为未来可评估方案，而非默认禁用。

## 当前测试边界

PocketLab 按责任和风险组织测试，而不追求固定的单元/集成/UI 百分比：

```text
共同规则与状态机 → 共同层真实数据库集成 → 平台 adapter / interop
→ Android ViewModel / Compose → SwiftUI XCTest smoke
```

- 38 个 `commonTest` 分别在 JVM、Android Host 与 iOS x64 执行；
- 共同集成测试使用真实 Refresh UseCase、SQLDelight Repository、transaction 与 Flow，外部 remote 保持确定性；
- iOS 专用测试覆盖 callback、refresh、取消、异常与资源关闭；
- Android Compose 测试只验证 state 渲染和 intent 转发，不复制共同业务分支；
- iOS UI 测试使用唯一数据库冷启动真实 App，并验证首次刷新与幂等刷新；
- Native 专用测试用弱引用证明纯 Kotlin 强引用环可被 tracing GC 回收；共同并发测试让两个 dispatcher 通过 `Mutex` 安全修改同一对象；
- 当前共有 52 个不同测试场景、128/128 次目标执行，两种计数不得混用；
- 异步测试使用虚拟时间、Compose 同步或条件等待，不使用固定 sleep。

## 当前 Native runtime 边界

PocketLab 把 Kotlin/Native 当作带 runtime 的原生编译目标，而不是“把 Kotlin 文本翻译成 Swift”：

```text
Kotlin source → KLIB（序列化 IR + metadata）→ LLVM + Kotlin runtime
              → static framework → Xcode 最终链接 → iOS App
```

- 现代 Native 对象位于共享 heap，不再使用旧 memory manager 的 freeze 模型；
- “对象可跨线程访问”不等于“修改天然安全”，共享可变状态必须由 `Mutex`、atomic 或单 owner 串行化；
- Kotlin tracing GC 能回收不可达的纯 Kotlin 引用环；Swift/Objective-C ARC 与 GC 组成的混合强引用环不能指望任一方自动打破；
- callback、delegate 与订阅默认审计强弱引用方向，长任务和数据库/network owner 仍显式 `cancel` / `close`；
- framework 只是链接输入，二进制大小与性能结论以 Release 最终 App、GC 日志和 Instruments 证据为准。

