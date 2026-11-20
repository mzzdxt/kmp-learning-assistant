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
- 领域规则、模型、端口、用例与状态机由独立 `:domain` module 拥有，禁止依赖 Ktor、SQLDelight、平台 API 或 `:shared`。
- Android/Kotlin consumer 可通过 shared 的 `api` dependency 使用 domain；Apple framework 只链接、不导出 domain API。
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

- 49 个共同场景按所有权分布在 `domain/commonTest` 与 `shared/commonTest`，分别在 JVM、Android Host 与 iOS x64 执行；
- domain 每个目标 23 条；shared 在 JVM/Android Host/iOS x64 分别为 27/27/35，聚合为 50/50/58；
- 共同集成测试使用真实 Refresh UseCase、SQLDelight Repository、transaction 与 Flow，外部 remote 保持确定性；
- iOS 专用测试覆盖 callback、refresh、取消、异常与资源关闭；
- Android Compose 测试只验证 state 渲染和 intent 转发，不复制共同业务分支；
- iOS UI 测试使用唯一数据库冷启动真实 App，并验证首次刷新与幂等刷新；上一完整基线为 1/1，本轮因 Xcode 26.5 缺少匹配 runtime 未重新执行；
- Native 专用测试用弱引用证明纯 Kotlin 强引用环可被 tracing GC 回收；共同并发测试让两个 dispatcher 通过 `Mutex` 安全修改同一对象；
- Android unit 已从 2 条增加到 6 条，其中 4 条专门验证 KMP migration adapter 的映射与取消；
- 仓库共有 69 个不同测试场景、167 次目标执行；本轮可运行矩阵为 166/166，剩余 1 条 iOS UI 等待匹配 runtime，两种计数与验证时间不得混用；
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

## 当前 Swift API 边界

共同业务 API、Apple framework API 与 Swift 产品 API 被视为三层独立契约：

```text
common model / use case
→ Apple facade + snapshot / report
→ hidden raw callbacks
→ AsyncSequence + Swift value / enum
→ SwiftUI
```

- `@HiddenFromObjC` 隐藏列表边界的共同 record、local-store 实现图与 sealed result，内部构造器不进入 framework；
- `@ObjCName` 把 `Ios*` 实现名翻译为 `ExperimentListClient`、`PocketLabFeature` 等能力名；
- callback 使用 `@ShouldRefineInSwift`，只作为 `__subscribe` / `__refresh` wrapper primitive；
- SwiftUI 消费 `Experiment: Identifiable & Sendable` 和可穷尽 `RefreshOutcome`，不再动态 cast Kotlin subtype；
- failure 通过稳定 code 传递，未知 code 安全退化；nullable status 使用 presence flag + primitive value；
- Apple API gate 检查 header allow/deny symbols，并用 Swift 6 complete strict concurrency type-check App source；
- Swift Export 已具备 enum、sealed、suspend 与 Flow 映射，但当前仍为 Alpha，先平行验证而不替换稳定发布路径。

## 当前 Apple 分发边界

PocketLab 把本地联合开发与远程二进制交付分成两条路径：

```text
local:  Kotlin source → embedAndSignAppleFrameworkForXcode → SwiftUI App
remote: Kotlin source → Release XCFramework → ZIP/checksum → SwiftPM consumer
```

- Apple 源码位于共同 `iosMain` / `iosTest`，同时编译 `iosX64`、`iosSimulatorArm64` 与 `iosArm64`；
- framework 使用 static linkage，module name 为 `PocketLabShared`，bundle id 为 `dev.kmpjourney.pocketlab.shared`；
- XCFramework 包含 device `arm64` 与 Simulator `arm64 + x86_64`，不使用 `lipo` 混合 device/Simulator platform variants；
- 同仓 Xcode App 继续 direct integration，避免每次 Kotlin 修改都经过发布循环；
- 远程默认使用不可变 XCFramework ZIP + SwiftPM binary target，checksum 从最终 archive 计算；
- CocoaPods 保留给 Kotlin 必须消费 Pod 或团队已有强 Pod 基础设施的场景，不与 direct integration 同时接管当前 framework；
- 生成 XCFramework、ZIP、checksum 与临时 Swift package 留在 ignored `build/`，源码 Git 只保存配置、模板与门禁；
- Intel host 只运行 `iosX64Test`；Apple Silicon CI 必须补 `iosSimulatorArm64Test`，不把交叉编译冒充运行时覆盖。

## 当前模块边界

PocketLab 使用一个内部领域模块和一个 Apple umbrella/composition 模块：

```text
domain: rules + models + ports + use cases + state machines
  ↓
shared: Ktor + SQLDelight + platform adapters + Apple facade
  ├→ Android App
  └→ one PocketLabShared framework → SwiftUI App
```

- `domain` 声明 JVM、Android 与三种 iOS targets，但不生成 framework；
- `shared` 使用 `api(project(":domain"))` 保持 Kotlin consumer 的传递可见性；
- Apple framework 不 `export` domain，也不启用 `transitiveExport`；
- module gate 禁止 domain 导入平台、Ktor、SQLDelight 或反向依赖 shared；
- module 拆分后 Apple header 从 2,267 行降到 888 行，领域规则、端口与用例不再导出；
- SQLDelight 生成 API 仍与 facade 同处 shared，是未来 infrastructure/apple-api 再拆分的候选，而非已完成项。

## 当前构建性能边界

PocketLab 不再用单一“总构建时间”描述开发体验，而是按反馈目标拆分：

```text
JVM 日常反馈 → Android 风险 → Apple 单架构 Debug
→ 全目标合并验证 → 多架构 Release distribution
```

- Gradle build cache 与 configuration cache 已启用，并由 `verify-build-performance.sh` 验证 reuse；
- Kotlin compiler 同时生成 text/JSON reports，用于定位 incremental reason 与 Native compiler phases；
- benchmark 分离 cache disabled、store、reuse、clean restore 和 up-to-date，不用 wall time 单独证明原因；
- 当前 warm JVM developer lane 平均约 1.024 s；单一 `iosX64` Debug forced rerun 为 19.80 s；上一轮三架构 Release XCFramework gate 约 4 min 37 s，三者不是同条件 benchmark；
- 私有 domain 实现改动没有触发 shared/App 重新编译，公共 ABI 改动会正确沿依赖图传播；
- 不默认执行 `clean`，不在缺少证据时增加 heap、开启 Beta Native incremental 或 Release binary cache；
- 原始编译报告和日志可能含本机路径，只提交去标识化实验记录。

## 当前可观测性边界

PocketLab 用共同事件语义和平台诊断工具组成一条可验证证据链：

```text
typed refresh lifecycle
→ fixed privacy allowlist schema + correlation ID + monotonic duration
→ Android Log / JVM stdout / iOS NSLog adapter
→ R8 mapping / Native dSYM / release artifact identity
```

- 刷新明确区分 `applied`、可预期 `failed`、`cancelled` 与未预期 `crashed`；取消和异常被观察后原样重抛；
- `:domain` 只拥有生命周期端口，`:shared` 定义 schema 与平台 writer，日志后端失败不能改变产品行为；
- schema 不提供任意 message/attributes map，不允许标题、笔记、URL、数据库路径、token、异常 message 或原始响应；
- duration 使用 `TimeSource.Monotonic`，单次 628 ms 样本只作为现场证据，不作为性能预算；
- Android `0.17.0 (17)` Release 开启 R8 minify/resource shrink，并固定 Kotlin 2.4 兼容的 R8 9.1.29；每个发布物需保存匹配 APK/AAB、mapping、SHA-256 与 `pg_map_id`；
- Apple Release 配置为 `DWARF with dSYM`；binary 与 dSYM 必须 UUID 匹配，最终 App archive 由 Xcode 发布流水线保存；
- 当前门禁已用 `iosX64` test executable/dSYM 和 `atos` 恢复 Kotlin 函数名，但最终 SwiftUI App archive 仍受 Xcode 26.5 runtime 缺失阻断；
- 日志不替代 Perfetto/Instruments、Android ANR/vitals、Apple MetricKit 或版本化性能指标。

## 当前渐进迁移边界

PocketLab 的实验列表以宿主拥有的契约接入 KMP：

```text
Android Compose / ViewModel → Android ExperimentListGateway ─┐
                                                             ├→ KMP adapters → one shared data path
SwiftUI / ViewModel         → Swift ExperimentListGateway ───┘
```

- Android/Swift ViewModel 与 UI 不再引用 KMP record、port、use case、framework facade 或 exported report；
- 两端各自使用符合本地语言的 host values/protocol，不强迫 Flow 与 AsyncSequence 使用相同接口形状；
- `KmpExperimentListGateway` 是唯一生产翻译层，composition root 是唯一 implementation selection point；
- legacy implementation 只作为 Android test 与 Swift compile-only fixture，证明宿主可替换；当前仓库不虚构第二套生产数据库；
- adapter 只映射值并委托一次 refresh，不调用第二次 `save`/`merge`，任一用户操作只有一个 data writer；
- Swift 6 strict concurrency 捕获并修复了 `@MainActor close()` 与同步 deinit 的隔离冲突；
- 0.17.0 minified Android Release 已通过真实启动和刷新 smoke；远端 cohort/flag、schema 降级和 iOS runtime rollout 尚未实现；
- migration flag 若引入，必须有 owner、稳定选择作用域、过期日期和 legacy 删除条件，不能永久双轨。

