# ADR-0015：用宿主契约和纵向切片渐进接入 KMP

- 状态：Accepted
- 日期：2026-11-20
- 决策范围：PocketLab 在已有 Android 与 iOS 宿主中的 KMP 接入、切换、回滚和数据所有权

## 背景

真实存量 App 已经有导航、UI、生命周期、数据库、网络、发布流程、监控和团队边界。把现有代码一次性移动到 `commonMain` 会同时改变模块结构、运行时、数据所有权和双端交付，故障发生时很难判断来自迁移还是业务变化。

PocketLab 已经有可运行的 Android Compose 与 SwiftUI App，但两端宿主此前仍直接认识 KMP 类型：

- Android ViewModel 依赖 `ExperimentLocalStore`、`RefreshExperimentsUseCase` 和 domain record；
- Compose UI 直接渲染 `ExperimentRecord`；
- Swift ViewModel 保存 `PocketLabFeature` 并直接调用 `ExperimentListClient`。

这种接法能运行，却让 KMP 类型沿宿主层扩散。若需要暂时切回旧实现，UI、ViewModel 与测试都要跟着改；如果新旧实现同时写同一业务数据，还会引入冲突、重复副作用和不可逆回滚。

因此，渐进接入的核心不是“把多少文件移动到 shared”，而是：在最小业务切片上建立可替换的宿主契约，只让 composition root 决定当前实现，并保证任一时刻只有一个数据写入权威。

## 决策

### 以实验列表作为第一条纵向切片

迁移单位为完整的 experiment-list 能力：

```text
observe local list + refresh + stable outcome
```

它穿过宿主 UI、异步边界、共享用例、网络和数据库，因此能暴露真实集成风险；同时不包含导航重写、编辑页、上传队列或共享 UI，故障域仍可控制。

不按“先移动所有 model，再移动所有 repository”进行横向大搬家。横向迁移会让多个用户旅程同时处于半迁移状态，难以定义完成和回滚。

### 宿主拥有迁移契约

Android 在 `androidApp` 定义：

```text
ExperimentListGateway
ExperimentListItem
ExperimentRefreshResult
ExperimentRefreshFailure
ExperimentSyncStatus
```

Swift 宿主定义：

```text
ExperimentListGateway
Experiment
RefreshOutcome
RefreshRejection
```

ViewModel 与 UI 只依赖这些宿主类型。旧实现和 KMP adapter 都可实现同一契约。契约按宿主当前需求设计，不照抄 KMP domain graph，也不要求 Android 与 Swift 使用完全相同的语言形状。

### KMP 类型只进入 adapter 和 composition root

Android 的 `KmpExperimentListGateway` 负责：

- 把 KMP database records 复制成 Android host values；
- 把 KMP sealed refresh result 映射为宿主 result；
- 保持 cancellation 原样传播。

Swift 的 `KmpExperimentListGateway` 负责：

- 持有 `PocketLabFeature`；
- 把 `ExperimentListClient` 的 AsyncSequence/async wrapper 暴露为宿主 protocol；
- 在关闭时释放 Kotlin Job、network 和 database owner。

KMP import 不进入双端 ViewModel/UI。平台文案、日期格式、导航和生命周期仍由宿主拥有。

### composition root 是唯一切换点

当前 0.17.0 在两个 composition roots 选择 KMP adapter：

```text
AndroidPocketLabGraph → KmpExperimentListGateway
PocketLabIosApp       → KmpExperimentListGateway
```

回滚时只替换这里提供的实现，不修改 screen 和 ViewModel。当前学习工程使用编译时选择，不接入远端 feature flag；生产 rollout 若加入 flag，必须在进程/会话开始时固定选择，避免同一用户旅程中途切换数据权威。

### 任一时刻只有一个写入权威

迁移 adapter 只做读取映射和一次 refresh 委托，不直接调用 `save`、`mergeServerSnapshot` 或第二套 repository。当前数据写入链仍为：

```text
KMP refresh use case → one SQLDelight repository → one transaction
```

不做“旧数据库写一次、KMP 数据库再写一次”的 runtime dual write。双写会产生顺序、重试、幂等、失败补偿和回滚数据兼容问题。

若需要比较新旧实现：

- 对纯函数使用离线 corpus parity test；
- 对只读查询可在受控环境 shadow compare；
- 对网络刷新、写入、通知、支付等副作用路径不并行执行；
- 差异记录使用稳定、无敏感数据的结果摘要。

### 旧实现只作为迁移证据存在

当前仓库没有需要继续发布的真实 legacy data stack，因此不虚构第二套生产数据库。Android unit test 与 Swift compile-only smoke 提供 legacy fixture，证明宿主 ViewModel 能在完全不引用 KMP 类型的实现上编译和运行。

生产源只保留 KMP adapter。门禁拒绝名为 `LegacyExperimentListGateway` 的 fixture 进入生产目录。这既保留回滚结构的证据，也避免为了演示而制造一套会腐烂的假生产系统。

### rollout 与删除有独立退出条件

“KMP 可以工作”不等于“可以删除旧实现”。生产迁移至少经过：

1. adapter contract tests；
2. 双端 consumer compile 与 UI tests；
3. internal/dogfood cohort；
4. 版本化 crash、ANR/hang、失败率与性能观察；
5. 可执行回滚演练；
6. 数据 schema 与降级兼容确认；
7. 观察窗口结束后删除 legacy implementation 和 flag。

flag 必须有 owner、默认值、过期日期和删除条件。永久双轨会让每次功能修改都支付两套实现成本。

### 本地开发与远程交付分开

同仓迁移继续使用 iOS direct integration，让 Kotlin 修改立即进入 Xcode build。跨仓或独立发布团队可使用已验证的 Release XCFramework + SwiftPM。选择集成方式由仓库边界、团队所有权和发布节奏决定，不由“新旧”标签决定。

Android 继续把 KMP module 当普通 Gradle dependency；宿主 adapter 位于 application module，不把 Android lifecycle 反向搬入 shared。

## 机器可验证契约

`scripts/verify-incremental-adoption.sh` 必须证明：

- Android unit tests 和 Apple Debug framework 可构建；
- Android host contract 与 KMP adapter 存在；
- Android ViewModel/UI 不引用 KMP record、port、use case 或 failure type；
- Swift host protocol 与 KMP adapter 存在；
- Swift ViewModel/UI 不 import `PocketLabShared`，也不引用 framework facade 类型；
- 两端 composition root 都明确选择 KMP adapter；
- legacy fixture 只存在于 test/type-check source；
- adapter 不调用第二次 data write；
- Swift 6 complete strict concurrency 与 warnings-as-errors 能同时编译 KMP adapter、宿主 ViewModel、App 和 legacy fixture。

它不声称验证远端 cohort rollout，因为当前工程没有接入 flag 服务或真实用户分群。

## 当前证据

- Android ViewModel 的两条原有行为测试改由 legacy fixture 驱动，证明宿主层可以在不依赖 KMP 类型时运行；
- Android 新增 4 条 KMP adapter tests，覆盖 record/status 映射、成功、稳定失败和 cancellation；
- Swift compile-only legacy fixture 能构造同一个 `ExperimentListViewModel`；
- Swift 6 首次 type-check 捕获 `@MainActor close()` 不能从同步 deinit 直接调用，修复为显式 MainActor task 后通过；
- 0.17.0 minified Release 在 Pixel 2 API 34 模拟器冷启动成功，KMP adapter 路径完成一次 408 ms 刷新，接收 3 条、变更 0 条（本地快照已收敛）；
- R8 9.1.29 mapping、APK/mapping identity 和 Native dSYM 门禁继续通过；
- 最终 iOS App runtime 仍受本机缺少匹配 iOS 26.5 Simulator runtime 阻断，Swift consumer compile 不能替代 runtime rollout。

完整命令与环境见 `labs/17-incremental-adoption/results/2026-11-20-host-seam.md`。

## 后果

### 正面

- KMP 的故障域收口在 adapter 和 composition root；
- 宿主 UI、导航和 ViewModel 可在实现切换时保持不变；
- Android 与 Swift 可以按各自语言习惯设计契约；
- cancellation、failure 和资源关闭在边界处被显式测试；
- 不需要第二套生产数据库来证明回滚结构；
- KMP framework/API 演进不会直接传播到所有平台 UI 文件。

### 成本

- host value 与 KMP value 之间有显式复制和映射代码；
- 两个平台分别拥有契约，字段变化需要各自审查；
- adapter tests 与静态门禁增加维护成本；
- compile-time seam 只证明可替换结构，不证明真实远端 flag、cohort 和回滚运维；
- 当前 Swift deinit 的关闭动作异步回到 MainActor，资源 owner 必须保持 close 幂等。

## 被拒绝的方案

### 一次性把所有业务和 UI 移入 KMP

同时改变过多边界，无法小流量验证或快速回滚，因此拒绝。

### 让 ViewModel 和 UI 直接使用 KMP 类型

短期映射更少，但 shared API 会扩散到宿主所有层，切换实现需要大面积修改，因此拒绝。

### 为 Android 和 iOS 强制一模一样的宿主协议

两端生命周期、异步和 UI 模型不同。共享业务语义不要求共享平台接口形状，因此拒绝。

### 新旧实现对同一操作同时写入

会引入双写一致性、重复副作用和不可逆数据迁移风险，因此拒绝。

### 为演示保留一套假 legacy 生产栈

它没有真实 owner、用户或发布责任，只会增加腐烂代码；测试/编译 fixture 足以证明 seam，因此拒绝。

### 永久保留 feature flag

永久双轨扩大测试矩阵和维护成本。flag 是迁移工具，不是架构边界，因此拒绝。

### 用代码复用率决定第一条迁移切片

复用率不表达依赖锥、数据风险、回滚成本或团队所有权，因此拒绝。

## 重新评估条件

- 引入真实 legacy database、账号、上传队列或 schema migration；
- 需要远端 cohort rollout、kill switch 或按平台独立放量；
- KMP adapter mapping 成为可测量性能瓶颈；
- 多个 feature 需要复用相同 host contract；
- Swift Export 稳定并改变 Apple facade/adapter 设计；
- Android/iOS 团队改为跨仓独立发布 shared binary；
- 迁移共享 UI 或导航，而不再只共享业务逻辑；
- 旧实现满足删除条件，进入 flag 与兼容代码清理阶段。

## 验证

- `scripts/verify-incremental-adoption.sh` 通过；
- Android unit tests 为 6/6，其中 KMP adapter 4/4；
- Swift 6 strict concurrency + warnings-as-errors type-check 通过；
- Android/Swift host ViewModel 与 UI 的 KMP 类型扫描为 0；
- production legacy fixture 为 0，migration adapter dual write 为 0；
- 0.17.0 minified Android Release 冷启动与刷新 smoke 通过；
- module boundary、Apple API、observability、build performance、SQLDelight migration 与 Apple distribution gates 全部通过；
- iOS UI runtime 仍等待匹配 Simulator runtime，不计为通过。
