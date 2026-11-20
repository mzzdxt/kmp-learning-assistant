# 别把现有 App 一夜搬进 commonMain：一条可回滚的 KMP 迁移路线

> 系列第 17 篇：在现有 Android 与 iOS App 中渐进引入 KMP

周一，技术负责人宣布：

> 我们下个季度开始上 KMP，先定一个目标——共享率达到 60%。

周二，团队创建了 `shared` module。

周三，三百个 model 被移动到 `commonMain`。

周四，Android 因为 Room、Parcelable 和资源引用无法编译；iOS 看见了一整张陌生的 Kotlin 类型图。

周五，大家决定“先把所有 repository 也迁过去”，因为 model 已经移了一半。

这类迁移最危险的地方，不是 KMP 编译器报错，而是系统进入一种尴尬状态：旧实现不能删，新实现不能独立上线，每次改动都要同时理解两套架构，却没有任何一个用户旅程真正完成迁移。

问题从第一句话就开始了。

“共享率 60%”不是用户价值，也不是风险边界。它不能回答：

- 第一批用户会使用哪个 KMP 能力？
- 出现 crash 时切回哪里？
- 新旧实现是否会同时写数据？
- 旧版本还能否读取新版本写下的数据库？
- Android 和 iOS 是否必须同一天切换？
- 什么证据出现后才能删除旧代码？

这篇文章不讨论怎样用向导创建一个 shared module。官方教程已经能很好地完成这件事。

我们要解决更难的问题：

> 一个已经上线多年的 App，怎样切出第一条可独立验证、可小流量发布、可回滚、最终还能删除旧实现的 KMP 路径？

PocketLab 会把实验列表改造成这条路径。Android 和 SwiftUI 仍保留原生 UI、ViewModel、导航与生命周期；KMP 负责列表事实来源和刷新。两端中间各放一个“宿主拥有的契约”，KMP 类型只进入 adapter 和 composition root。

最终结构是：

```text
Android UI / ViewModel
→ Android-owned contract
→ KMP adapter
→ shared business/data

SwiftUI / ViewModel
→ Swift-owned protocol
→ KMP adapter
→ PocketLabShared framework
```

听起来多了一层。

是的。那一层就是回滚的空间。

## 创建 shared module，只解决了“代码放哪里”

Kotlin 官方的[现有 Android App 迁移教程](https://kotlinlang.org/docs/multiplatform/multiplatform-integrate-in-existing-app.html)给出了一条清晰入门路径：决定共享什么、创建 shared module、添加依赖、移动业务逻辑，再让 Android 和 iOS 消费它。

Android Developers 也提供了[向现有工程添加 KMP module](https://developer.android.com/kotlin/multiplatform/migrate)的当前配置方法。

这些步骤能建立编译关系：

```text
androidApp → shared KMP module
iosApp     → generated framework
```

但生产迁移还多出五个问题：

```text
谁拥有接口？
在哪切换实现？
数据由谁写？
怎样观察差异？
何时删除旧路径？
```

Gradle dependency 能让 Android 找到 shared class，framework search path 能让 Swift import module。它们不会自动给你 rollout、kill switch、schema compatibility 或 incident runbook。

所以第一步不是移动文件，而是画出迁移边界。

## 先画三张图，再写第一行 KMP 代码

### 第一张图：用户旅程

不要从 package tree 开始，从用户动作开始：

```text
进入实验列表
→ 立即看到本地数据
→ 下拉刷新
→ 远端数据合并进数据库
→ 列表自动更新
→ 失败时保留缓存并提示原因
```

这条旅程有明确开始、结束和用户可见结果。

相比之下，“迁移 model 层”没有用户完成态。五十个 model 移完以后，没有一个 screen 因此能独立发布。

### 第二张图：依赖锥

```text
ExperimentListScreen
        ↓
platform ViewModel
        ↓
observe + refresh contract
       ↙ ↘
database  network
        ↓
domain merge rules
```

一个候选切片越靠近依赖图叶子、被依赖越少，迁移风险通常越容易控制。Kotlin 官方最新的复杂 Compose App 渐进迁移指南也建议，从依赖最少的 module 开始，逐个迁移，并保持每个 commit 处于可工作的状态。参见[迁移 Jetpack Compose App 到 KMP](https://kotlinlang.org/docs/multiplatform/migrate-from-android.html)。

这不是绝对规律。一个“叶子 module”如果掌握支付、认证或不可逆 schema，风险仍然很高。因此还要画第三张图。

### 第三张图：副作用和数据权威

```text
GET /experiments           外部请求
SQLDelight transaction     本地写入
database Flow              本地读取
diagnostic event           旁路观察
```

逐项问：

- 能否重复执行？
- 失败后怎样补偿？
- 新旧实现是否使用同一个数据库？
- 旧 binary 能否读取新数据？
- 取消发生在写入前还是写入后？
- 线上比较是否会产生两次副作用？

如果你只画代码依赖，不画数据和副作用，所谓回滚很可能只是“把 flag 切回去”，而数据库已经回不去了。

## 第一条切片应该纵向完成，而不是横向铺开

两种迁移看起来都很有条理。

横向迁移：

```text
所有 models
→ 所有 network DTOs
→ 所有 repositories
→ 所有 use cases
→ 最后接 UI
```

纵向迁移：

```text
一个列表旅程
→ contract
→ one use case
→ one data path
→ Android + iOS consumers
→ release + observe + rollback
```

横向迁移的优点是同类代码集中处理，缺点是价值很晚才出现。系统会长期处于“每层都迁了一部分，没有功能真正结束”的状态。

纵向切片会较早碰到所有真实边界：

- Android lifecycle；
- Swift interop；
- framework 集成；
- cancellation；
- database ownership；
- Release shrink；
- crash symbols；
- CI 与发布。

这听起来更难，却能更早发现 KMP 是否适合当前团队，而不是在迁移半年后才第一次构建 iOS App。

PocketLab 选择 experiment list：业务足够真实，故障域又没有扩大到编辑、上传和导航。

## 迁移前：KMP 类型已经爬进了宿主 UI

在改造前，Android ViewModel 的构造函数是：

```kotlin
class ExperimentListViewModel(
    private val localStore: ExperimentLocalStore,
    private val refreshExperiments: RefreshExperimentsUseCase,
)
```

Compose screen 直接渲染：

```kotlin
List<ExperimentRecord>
```

失败文案直接匹配：

```kotlin
RefreshFailureReason
```

iOS 侧，Swift ViewModel 直接保存：

```swift
private let feature: PocketLabFeature
```

并调用：

```swift
feature.experimentList.experiments()
feature.experimentList.refreshOnce()
```

这些代码没有错。对于从零开始、宿主与 shared 同步演进的小项目，它们甚至可能足够简单。

但我们的目标是模拟存量 App 渐进接入。此时有一个重要问题：

> 如果明天需要切回 legacy implementation，哪些文件必须改？

答案是 ViewModel、UI state、screen test、factory，Swift 还包括 framework-facing lifecycle。

这不是一个切换点，而是一片切换面。

## 宿主契约：让旧 App 决定自己需要什么

Android App 现在定义自己的协议：

```kotlin
internal interface ExperimentListGateway {
    fun observeExperiments(): Flow<List<ExperimentListItem>>
    suspend fun refresh(): ExperimentRefreshResult
}
```

以及自己的 values：

```kotlin
data class ExperimentListItem(...)

enum class ExperimentSyncStatus {
    SYNCED,
    PENDING_UPLOAD,
}

sealed interface ExperimentRefreshResult {
    data class Applied(val changedCount: Int)
    data class Failed(
        val reason: ExperimentRefreshFailure,
        val statusCode: Int?,
    )
}
```

Swift App 定义：

```swift
@MainActor
protocol ExperimentListGateway: AnyObject {
    func experiments() -> AsyncThrowingStream<[Experiment], any Error>
    func refresh() async throws -> RefreshOutcome
    func close()
}
```

为什么叫“宿主拥有”？

因为它表达的是现有 App 的消费需求，不是 shared module 想导出什么。

Android 需要 Flow，因为 ViewModel 用 coroutine/lifecycle；Swift 需要 AsyncSequence 和 async/throws，因为这才是 Swift 并发世界的自然形状。

两端共享同一种业务语义：

```text
观察列表
刷新一次
获得稳定结果
管理 feature 生命周期
```

但不必共享同一种接口语法。

## 为什么不直接复用 KMP data class

迁移 adapter 中最“碍眼”的代码是字段复制：

```kotlin
ExperimentListItem(
    id = record.id,
    title = record.title,
    hypothesis = record.hypothesis,
    notes = record.notes,
    updatedAtEpochMilliseconds = record.updatedAtEpochMilliseconds,
    syncStatus = when (record.syncState) { ... },
)
```

工程师对重复有本能警惕，于是很容易提出：

> `ExperimentRecord` 字段一模一样，直接给 UI 用不就行了？

如果它只是稳定、明确的 public contract，可以考虑。但 PocketLab 的 `ExperimentRecord` 同时是 local-store/domain 语义的一部分。

直接复用会把这些变化传播到 UI：

- 数据库增加内部字段；
- sync state 增加中间状态；
- domain 类型移动 module；
- Apple export 策略调整；
- legacy implementation 需要适配 KMP class。

显式 mapping 做了两件事：

1. 复制当前宿主真正需要的值；
2. 阻断 implementation graph 的变化传播。

当然，边界复制也有成本：对象分配、字段维护、mapping test。是否值得由变更隔离和实测性能决定，不是由“重复代码永远不好”决定。

在列表每秒更新数千次的场景，应该 profile；在用户偶尔刷新的几十条记录中，十几行 mapping 往往比全层耦合便宜。

## Android KMP adapter：翻译，不重做业务

`KmpExperimentListGateway` 位于 Android application module：

```kotlin
internal class KmpExperimentListGateway(
    private val localStore: ExperimentLocalStore,
    private val refreshExperiments: RefreshExperimentsUseCase,
) : ExperimentListGateway
```

它做三件事：

- 把 database Flow 复制成 Android host values；
- 把 shared refresh result 映射为 Android result；
- 让 cancellation 原样穿过。

它没有自己的 retry、cache、transaction 或文案。

adapter 最容易腐化成“临时业务层”：

```text
KMP 返回失败
→ adapter 再重试一次
→ adapter 自己写旧数据库
→ ViewModel 仍不知道发生过两套行为
```

一旦这样做，它就不再是协议翻译，而是第二个真相来源。

PocketLab 的门禁直接扫描 adapter，禁止它调用：

```text
save
mergeServerSnapshot
pendingUploads
```

真正的数据链只有一次：

```text
gateway.refresh
→ RefreshExperimentsUseCase
→ one SQLDelight repository
→ one transaction
```

## 旧实现不必知道 KMP 存在

同一个 Android ViewModel test 使用：

```kotlin
private class LegacyExperimentListGateway : ExperimentListGateway
```

它只返回 `ExperimentListItem` 和 `ExperimentRefreshResult`，没有一个 KMP import。

测试证明：

```text
legacy gateway
→ same ViewModel
→ same UI state
→ same cached-row behavior
```

这才是有价值的替换性。

需要强调的是，PocketLab 没有虚构一套“旧 Room + Retrofit”生产系统。legacy 只是一份 test fixture。

为什么不把它放进 production，演示 feature flag？

因为假 legacy 没有真实用户、owner、schema 和发布责任。它会增加一条看似可回滚、实际从未在生产存在的路径。

真实存量项目应该保留自己的旧实现；学习项目只证明 seam 能容纳它。

## Swift KMP adapter：把 framework 留在边界

Swift 的 adapter 保存 KMP feature：

```swift
@MainActor
final class KmpExperimentListGateway: ExperimentListGateway {
    private let feature: PocketLabFeature

    func experiments()
        -> AsyncThrowingStream<[Experiment], any Error> {
        feature.experimentList.experiments()
    }

    func refresh() async throws -> RefreshOutcome {
        try await feature.experimentList.refreshOnce()
    }

    func close() {
        feature.close()
    }
}
```

现在 Swift ViewModel 不再 import `PocketLabShared`，也不知道 `PocketLabFeature`、`ExperimentListClient` 或 `RefreshReport`。

framework API 如果变化，编译错误集中在：

```text
ExperimentListAsyncSequence.swift
ExperimentListGateway.swift
PocketLabIosApp.swift
```

不会蔓延到 screen 和状态逻辑。

这也解释了为什么 KMP 的 Apple API 设计如此重要：framework 能导出，不代表应该让每个 Swift 文件直接使用它。Apple facade 解决跨语言 surface，宿主 adapter 再解决现有 App 的局部契约。

## 一条 Swift 6 编译错误，揭示了真实生命周期

第一次运行严格编译，失败在 ViewModel 的 `deinit`：

```text
call to main actor-isolated instance method 'close()'
in a synchronous nonisolated context
```

协议被标记为 `@MainActor`，但 Swift 6 不允许同步、非隔离的 deinit 直接调用 actor-isolated method。

如果只想让编译绿色，最简单的办法是删掉 close。

那会留下什么？

- Kotlin Flow subscription；
- refresh Job；
- network client；
- database handle。

真正的修复是把释放动作显式送回 MainActor：

```swift
deinit {
    manualRefreshTask?.cancel()
    Task { @MainActor [gateway] in
        gateway.close()
    }
}
```

同时要求 KMP feature 的 `close()` 幂等。

这就是渐进迁移的价值：不是减少所有代码，而是让跨运行时生命周期在一个窄边界被编译器逼着说清楚。

## composition root：把切换面缩成一个点

Android 当前选择：

```kotlin
internal val experimentListGateway: ExperimentListGateway =
    KmpExperimentListGateway(
        localStore = repository,
        refreshExperiments = refreshExperiments,
    )
```

iOS 当前选择：

```swift
ExperimentListViewModel(
    gateway: KmpExperimentListGateway(
        feature: PocketLabFeatures.shared.demo(...)
    )
)
```

UI 和 ViewModel 不参与选择。

生产 App 加入 rollout flag 后，判断也应停在 composition root：

```text
if KMP cohort
    provide KMP gateway
else
    provide legacy gateway
```

这就是所谓 dependency inversion 在迁移中的实际价值：不是为了画漂亮的六边形，而是让回滚修改一个 provider，不修改整条用户旅程。

## Feature flag 不是随时可拨的电灯开关

假设每次调用 `refresh()` 都读取远端 flag：

```text
09:00  observe legacy database
09:01  flag 刷新为 KMP
09:02  refresh 写入 SQLDelight
09:03  UI 仍观察 legacy database
```

用户会看到“刷新成功，但列表没变”。

所以有状态能力的 implementation selection 必须在稳定作用域固定：

- process；
- 登录 session；
- feature owner；
- 至少完整用户旅程。

flag 变化可以在下次安全重建 owner 时生效，而不是在进行中的 transaction 中途生效。

此外，flag 需要元数据：

```text
owner
default
kill-switch behavior
cohort rule
created date
expiry date
removal issue
```

没有删除日期的迁移 flag，通常会成为永久架构。

## 不要用双写证明“新旧一致”

“两个实现都跑，然后比较结果”听起来是最保险的迁移方式。

对纯函数确实很好：

```text
input corpus
→ legacy mapper
→ KMP mapper
→ structural diff
```

对 read-only query，有时也可以 shadow：

```text
same immutable snapshot
→ two readers
→ compare summaries
```

但刷新不是纯函数。它会：

- 发请求；
- 消耗服务端 quota；
- 写数据库；
- 触发 Flow；
- 影响 retry 和 cancellation；
- 可能产生 analytics 或通知。

把它跑两次，不是观测，是改变系统。

更危险的方案是长期双写两个数据库：

```text
legacy write succeeds
KMP write fails
```

接下来怎么办？

- 回滚第一份？
- 重试第二份？
- 用户重启后补偿？
- 两个版本并发写时谁赢？
- 事件重复发送怎么办？

如果团队没有设计 outbox、幂等 key、顺序、补偿和 reconciliation，双写不是渐进迁移，而是新的一致性系统。

PocketLab 选择更简单的规则：

> 一次用户操作只有一个 active implementation 和一个 data writer。

## 代码回滚容易，数据回滚最难

很多 rollout 文档写着：

```text
若指标异常，将 flag 切回 legacy。
```

这句话隐含一个巨大前提：legacy path 还能理解 KMP path 已经写下的数据。

需要逐项检查：

```text
旧 App 能否打开新 schema？
新增列是否有默认值？
enum 新值旧版本如何处理？
migration 是否删除或重写旧字段？
server 是否仍接受旧请求格式？
新版本是否写入旧版本无法解析的状态？
```

因此回滚至少分三种。

### 配置回滚

远端关闭 KMP cohort，不发新 binary。速度最快，但只能切换仍在 binary 中的代码。

### 代码回滚

revert commit 并发布新版本。移动端受商店审核、分批发布和用户升级速度影响，不是即时操作。

### 数据回滚或降级兼容

让旧实现读取新数据，或把数据迁回旧 schema。通常成本最高，也可能不可逆。

真正的“可回滚”必须同时回答这三层，而不是只展示一个 Boolean flag。

## 小流量 rollout 需要比较同条件 cohort

一个合理的放量阶梯可能是：

```text
disabled
→ developers
→ internal/dogfood
→ 1%
→ 10%
→ 50%
→ 100%
→ observation window
→ delete legacy and flag
```

每一步都需要预先定义，而不是指标红了再开会：

- 进入条件；
- 停止条件；
- rollback owner；
- 决策时间；
- 需要观察多久；
- 哪些数据变化会阻断回滚。

比较指标时至少分层：

```text
platform
app version/build
KMP/legacy cohort
device class
network condition
cold/warm state
```

否则很容易拿 Android 新版本的 KMP 用户，与 iOS 旧版本的 legacy 用户比较，然后把设备、系统和版本差异归因给 KMP。

第 16 篇建立的结构化 refresh outcome 正好成为 rollout signal：

```text
applied / failed / cancelled / crashed
duration p50 / p95 / p99
```

再结合 crash、ANR/hang、support tickets 和数据库 migration error，才能形成决策证据。

单次 408 ms 日志仍然只是现场，不是 cohort 结论。

## Android 与 iOS 不必同一天切换

KMP 共享逻辑，容易让人误以为发布也必须同步。

现实中两端可能有不同条件：

- Android 测试矩阵更完整；
- iOS framework consumer 还在升级 Xcode；
- 某端旧数据库更复杂；
- 商店审核节奏不同；
- 团队 on-call 能力不同。

只要 shared API 和 server protocol 支持两个宿主的版本窗口，Android 可以先 internal rollout，iOS 继续 legacy。

不要把“共同代码”变成“共同爆炸半径”。

当然，独立 rollout 会扩大兼容矩阵：

```text
Android legacy / iOS legacy
Android KMP / iOS legacy
Android KMP / iOS KMP
```

这也是为什么迁移窗口必须有限，不能永久双轨。

## iOS 接入方式由仓库和团队边界决定

Kotlin 官方的 [iOS integration methods](https://kotlinlang.org/docs/multiplatform/multiplatform-ios-integration-overview.html)把接入分为本地与远程：

- 本地 direct integration：Kotlin build 进入 Xcode build，适合同仓联合开发；
- CocoaPods：适合已有 Pod 基础设施或 Kotlin 需要消费 Pod；
- 远程 XCFramework + SwiftPM/CocoaPods：适合独立仓库和发布节奏。

PocketLab 同仓开发继续使用 [direct integration](https://kotlinlang.org/docs/multiplatform/multiplatform-direct-integration.html)，发布验证使用 XCFramework + SwiftPM。

这两者不是迁移阶段的先后顺序：

```text
direct = 开发工作流选择
remote binary = 团队/仓库/发布边界选择
```

有些团队从第一天就需要 remote binary，因为 iOS 团队不安装 Gradle；另一些团队即使上线多年仍适合 monorepo direct integration。

不要因为“生产”两个字就自动选择二进制远程分发，也不要因为“渐进”就把 Xcode build 绑上全部 KMP targets。

## 机器门禁如何防止迁移边界回退

PocketLab 新增：

```shell
./scripts/verify-incremental-adoption.sh
```

它不检查一份 PPT 是否写了“可回滚”，而是检查代码事实。

### Android 检查

```text
host contract exists
KMP adapter exists
composition root selects adapter
ViewModel/UI contains no KMP implementation types
legacy fixture does not enter src/main
adapter contains no second write API
6 unit tests pass
```

### Swift 检查

```text
Swift-owned protocol exists
KMP adapter exists
App entry selects adapter
ViewModel/UI contains no framework types
legacy fixture compiles without KMP import
Swift 6 complete strict concurrency
warnings as errors
```

成功输出：

```text
Android host ViewModel/UI -> Android-owned contract -> KMP adapter
Swift host ViewModel/UI -> Swift-owned contract -> KMP adapter
Legacy rollback fixtures: test/type-check only
Production dual writes: 0
```

静态扫描不是形式化证明。开发者仍可能用另一个函数名偷偷双写，或者把业务逻辑塞进 adapter。

但门禁把团队已经明确禁止的退化变成快速失败，再由 code review 和运行测试覆盖语义。

## 0.17.0 的真实 Release 证据

宿主 adapter 改完后，我们没有只跑 Debug unit test。

Android 0.17.0 开启 R8 minify/resource shrink，门禁保存：

```text
version/build: 0.17.0 (17)
R8: 9.1.29
pg_map_id: f3b738b6286d0955...
APK SHA-256: b166aeb277001101...
mapping SHA-256: 505da23d130ded5b...
```

unsigned APK 的 ignored 副本用本机 debug keystore 签名，v2/v3 验证通过，安装到 Pixel 2 API 34 模拟器。

冷启动：

```text
Status: ok
LaunchState: COLD
TotalTime: 1130 ms
```

刷新：

```text
duration_ms=408
outcome=applied
received_count=3
changed_count=0
```

这证明：

- composition root 实际选择 KMP gateway；
- minified adapter 可运行；
- 数据仍穿过单一 KMP transaction；
- structured diagnostics 没被迁移边界破坏。

它不证明线上 rollout、真实用户性能或 iOS runtime。当前 Xcode 26.5 缺少匹配的 iOS 26.5 Simulator runtime，所以 Swift 侧证据仍是 framework + strict consumer compile。

证据范围必须与结论一样大。

## 四条 adapter test 为什么比“能编译”更重要

Android adapter tests 覆盖：

### Record mapping

```text
KMP SYNCED         → Android SYNCED
KMP PENDING_UPLOAD → Android PENDING_UPLOAD
```

字段和 enum 新增时，这条 test 会迫使边界显式选择。

### Success mapping

```text
KMP Applied(changed=2)
→ Host Applied(changed=2)
```

### Failure mapping

```text
KMP SERVER + 503
→ Host SERVER + 503
```

UI 不依赖 shared sealed hierarchy，但稳定语义没有丢失。

### Cancellation identity

同一个 `CancellationException` 原样穿过 adapter。迁移层不能把生命周期控制流变成失败提示。

这些测试共同回答：

> adapter 是透明翻译，还是偷偷创造了第二套行为？

## 什么时候才能删除旧实现

flag 到 100% 不是删除条件，只是没有主动分配新 legacy cohort。

删除前至少确认：

1. KMP cohort 覆盖目标平台和主要设备；
2. crash、ANR/hang、失败率和性能分布满足预算；
3. rollback 演练真的执行过；
4. schema 对仍在市场上的旧 App 兼容；
5. support/on-call runbook 已更新；
6. 观察窗口覆盖足够用户行为周期；
7. 没有仍依赖 legacy 特殊行为的下游；
8. 清理 PR 同时删除 flag、旧 metrics dimension 和兼容 adapter。

为什么 flag 和 metrics dimension 也要删？

因为它们代表系统仍假设两条路径存在。只删实现、不删控制面，会留下永远无法触发但仍影响理解和测试的幽灵分支。

迁移真正完成的标志不是“shared 行数变多”，而是系统重新回到一条主路径。

## 一份 90 天迁移草案

时间不能照搬，但顺序值得参考。

### 第 1 至 2 周：盘点与选择

- 画用户旅程、依赖锥和副作用；
- 记录 baseline build、crash、latency 和失败率；
- 选择一个可独立发布的纵向切片；
- 明确 platform/shared/team owners；
- 写回滚和数据兼容假设。

交付物不是 KMP 代码，而是一页可被反驳的 migration brief。

### 第 3 至 4 周：建立 seam

- 定义 Android/Swift host contracts；
- 让 legacy implementation 先通过；
- 把 ViewModel/UI 从具体实现解耦；
- 建立 contract tests 和静态门禁。

此时用户行为不应改变。

### 第 5 至 6 周：接入 KMP adapter

- 实现 shared use case/data path；
- 编写 adapter mapping/cancellation tests；
- 验证 Android Release 和 Apple consumer；
- 建立 observability schema 和 build identities；
- 演练本地切换。

### 第 7 至 10 周：小流量

- developers/internal；
- 1%/10% cohort；
- 每一阶按预设指标决定；
- 执行一次真实配置回滚；
- 记录差异和 support feedback。

### 第 11 至 12 周：完成或停止

如果证据支持：

- 50%→100%；
- 保持观察窗口；
- 删除 legacy/flag/兼容代码。

如果证据不支持：

- 切回 legacy；
- 保留可复现测试；
- 写清 KMP、库、团队或数据边界不适配的原因；
- 不用“已经投入很多”继续扩大迁移。

停止也是有效技术决策。

## 七种常见失败方式

### 用共享率做唯一目标

共享率无法表达用户价值、可靠性和团队成本。它可以是描述性指标，不能单独做成功标准。

### 先迁移所有 models

产生大面积依赖变化，却没有可独立发布的用户旅程。

### 让 shared 类型贯穿 UI

边界代码少了，回滚影响面扩大了。

### 双写两个数据库

把代码迁移变成分布式一致性问题。

### 每次调用读取 flag

同一旅程中切换 data authority，产生混合状态。

### 100% 后永久保留 legacy

每个需求继续支付两套实现和测试成本。

### 只验证 Debug

R8、framework export、Swift 6、签名和 Release runtime 风险全部被跳过。

## 回到周一的目标

“共享率达到 60%”现在可以换成更可执行的目标：

> 在不修改 Android/SwiftUI 交互的前提下，让实验列表通过宿主契约切换到 KMP 数据与刷新；一个用户旅程只有一个 data writer；用 Release 产物和 cohort 指标验证；异常时只替换 composition-root provider；稳定观察后删除旧实现和 flag。

它更长，却能被测试、发布、观察和回滚。

KMP 渐进迁移不是把旧房子的砖一块块搬到 `commonMain`，然后祈祷搬完以前不要下雨。

更像在仍然营业的车站换轨：

- 先选一条线路；
- 建立可切换道岔；
- 同一列车只走一条轨；
- 小流量验证；
- 保留明确退路；
- 新轨稳定后拆掉旧轨和临时信号。

宿主契约就是那组道岔。

它不会让迁移没有成本，但能让每一阶段都有清楚的系统状态：现在谁在服务、谁能写数据、出现什么证据时继续、出现什么证据时退回。

这比共享率更接近技术负责人的工作。

## 动手挑战

在你的存量 App 中选一个用户旅程，完成下面这张卡：

```text
Feature:
Current owner:
New KMP owner:
Host contract:
Data authority:
Side effects:
Composition-root switch:
Read-only parity strategy:
Forbidden dual writes:
Rollback command:
Schema downgrade rule:
Rollout metrics:
Legacy deletion date:
```

然后做三个实验：

1. 用 legacy fixture 驱动同一个 ViewModel；
2. 用 KMP adapter 驱动同一个 ViewModel；
3. 删除 ViewModel/UI 中所有 KMP implementation imports。

如果第三步做不到，先别接远端 flag。你的切换面还没有缩成一个点。

完整可运行步骤见[实验 17：给现有 App 留一条可回滚的 KMP 迁移缝](../labs/17-incremental-adoption/README.md)，架构决策见 [ADR-0015](../docs/adr/0015-host-owned-seams-for-incremental-adoption.md)。

## 参考资料

- [Kotlin 官方：把现有 Android App 迁移到 KMP](https://kotlinlang.org/docs/multiplatform/multiplatform-integrate-in-existing-app.html)
- [Kotlin 官方：渐进迁移 Jetpack Compose App](https://kotlinlang.org/docs/multiplatform/migrate-from-android.html)
- [Android Developers：向现有工程添加 KMP](https://developer.android.com/kotlin/multiplatform/migrate)
- [KMP 工程配置选择](https://kotlinlang.org/docs/multiplatform/multiplatform-project-configuration.html)
- [KMP 推荐工程结构](https://kotlinlang.org/docs/multiplatform/multiplatform-project-recommended-structure.html)
- [KMP iOS integration methods](https://kotlinlang.org/docs/multiplatform/multiplatform-ios-integration-overview.html)
- [KMP direct integration](https://kotlinlang.org/docs/multiplatform/multiplatform-direct-integration.html)
- [KMP SwiftPM export setup](https://kotlinlang.org/docs/multiplatform/multiplatform-spm-export.html)

> 本文的代码、版本与实验结果验证于 2026-11-20：Kotlin 2.4.21、Gradle 8.14.4、AGP 8.13.2、R8 9.1.29、Xcode 26.5。远端 flag、cohort 和数据降级需要结合你的发布系统重新设计，不能从示例代码直接推断为已验证能力。
