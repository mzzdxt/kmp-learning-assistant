# 实验 17：给现有 App 留一条可回滚的 KMP 迁移缝

## 实验目标

完成本实验后，你应该能回答：

1. 为什么“创建 shared module”不是存量 App 迁移计划？
2. 第一条 KMP 切片应该按文件层、module 还是用户旅程选择？
3. 为什么宿主契约应由 Android/iOS App 拥有，而不是直接暴露整张 KMP 类型图？
4. composition root 怎样成为唯一切换点？
5. legacy 与 KMP 实现如何在不双写的前提下验证等价性？
6. feature flag、kill switch 与代码回滚有什么区别？
7. 数据 schema 如何决定回滚是否真的可行？
8. 什么时候可以删除 legacy path，而不只是把 flag 切到 100%？
9. Android 与 iOS 为什么可以使用不同形状的 host contract？
10. 怎样用自动门禁防止 KMP 类型再次扩散到宿主 UI？

主工程位于：

```text
../02-first-kmp-module
```

本轮结果记录在：

```text
results/2026-11-20-host-seam.md
```

## 前置条件

- 已完成[实验 16](../16-observability/README.md)，能用 build identity 和结构化事件观察迁移结果；
- 理解 Android composition root、SwiftUI App entry point、Kotlin framework 与 Swift wrapper；
- 本机有 JDK 17、Android SDK、Xcode command line tools 与 `rg`；
- iOS runtime smoke 需要与 Xcode 匹配的 Simulator runtime；
- 当前实验不需要远端 feature flag 服务。

## 第一步：不要先问能共享多少

面对一个已有 App，常见的第一张表是：

| 目录 | Kotlin 行数 | 能否移动到 commonMain |
|---|---:|---|
| model | 5,000 | 大部分可以 |
| network | 8,000 | 需要换库 |
| database | 12,000 | 需要迁移 |
| UI | 30,000 | 暂不共享 |

它能估算工作量，却不能告诉你迁移风险。

先画用户旅程：

```text
打开实验列表
→ 读取本地数据
→ 下拉刷新
→ 合并远端结果
→ 展示成功或失败
```

再画它的依赖锥：

```text
screen
  ↓
ViewModel
  ↓
list/refresh contract
  ↓
network + database + domain policy
```

第一条切片要同时满足：

- 用户价值能单独观察；
- 上下游边界可定义；
- 副作用数量有限；
- 有测试与监控；
- 能在一个 composition root 切换；
- 回滚不需要恢复整张数据库。

PocketLab 选择 experiment list，而不是一次移动所有 model。它包含真实网络、数据库和异步风险，却不要求同时迁移编辑页、导航和上传。

## 第二步：做一张迁移清单

在改代码前，为候选切片填写：

| 问题 | Experiment list 的答案 |
|---|---|
| 用户入口 | Android `MainActivity` / SwiftUI `PocketLabIosApp` |
| 现有 owner | 平台 UI 与 ViewModel |
| 新 owner | KMP refresh + SQLDelight，平台仍拥有 UI |
| 数据权威 | 一套 SQLDelight repository |
| 外部副作用 | GET + 本地 transaction |
| 可观察结果 | rows、stable failure、duration |
| 切换点 | 双端 composition root |
| 回滚面 | gateway implementation |
| 不能 shadow 的操作 | refresh/write transaction |
| 删除条件 | cohort 稳定、回滚演练、schema 可降级 |

如果其中任何一项写不出来，迁移计划还只是“想用 KMP”。

## 第三步：建立 Android 宿主契约

打开：

```text
androidApp/src/main/kotlin/dev/kmpjourney/pocketlab/android/
ExperimentListGateway.kt
```

宿主协议只表达 Android 列表当前需要什么：

```kotlin
internal interface ExperimentListGateway {
    fun observeExperiments(): Flow<List<ExperimentListItem>>
    suspend fun refresh(): ExperimentRefreshResult
}
```

host values 是：

```text
ExperimentListItem
ExperimentSyncStatus
ExperimentRefreshResult
ExperimentRefreshFailure
```

注意它没有返回：

```text
ExperimentRecord
ExperimentLocalStore
RefreshExperimentsUseCase
RefreshExperimentsResult
RefreshFailureReason
```

这些类型属于 KMP implementation graph，不是 Android UI 契约。

### 为什么允许“重复”数据类

`ExperimentRecord` 与 `ExperimentListItem` 看起来字段很像。直接复用能少写十几行 mapping，但会产生更大的耦合：

- database 字段改变会传播到 Compose；
- KMP sealed hierarchy 变化会迫使平台 exhaustive when 修改；
- legacy implementation 必须伪造 KMP 类型；
- rollback 不再只是替换 adapter。

边界复制不是无意义重复，它在交换变化隔离。需要测量 mapping 成本，但不要为了消除小对象复制，把整个实现模型变成 UI API。

## 第四步：让 Android ViewModel 忘记 KMP

`ExperimentListViewModel` 现在只接收：

```kotlin
class ExperimentListViewModel(
    private val gateway: ExperimentListGateway,
)
```

它仍负责 Android lifecycle、`viewModelScope`、刷新互斥和 UI phase；业务刷新与数据权威在 gateway 后面。

运行静态检查：

```shell
rg 'ExperimentLocalStore|ExperimentRecord|RefreshExperimentsUseCase' \
  androidApp/src/main/kotlin/dev/kmpjourney/pocketlab/android/ExperimentListViewModel.kt \
  androidApp/src/main/kotlin/dev/kmpjourney/pocketlab/android/ui/ExperimentListScreen.kt
```

预期没有输出。

这不是要求 Android module 完全不知道 KMP。adapter 当然需要 import KMP；关键是让依赖集中在可审计边界，而不是散到 UI tree。

## 第五步：实现 Android KMP adapter

独立文件 `KmpExperimentListGateway.kt` 中的 adapter 做三件事：

```text
KMP database Flow → Android list items
KMP refresh result → Android refresh result
Kotlin cancellation → 原样传播
```

它不做：

```text
第二次 merge
第二次 save
自己的 retry
自己的 database
自己的用户文案
```

这条限制很重要。adapter 是协议翻译，不是第二个业务层。

运行 adapter tests：

```shell
cd ../02-first-kmp-module
./gradlew :androidApp:testDebugUnitTest \
  --tests '*KmpExperimentListGatewayTest'
```

四个场景覆盖：

1. database record 与 sync status mapping；
2. applied result；
3. stable failure + status；
4. cancellation 原样穿过 adapter。

## 第六步：证明旧实现仍能驱动宿主

`ExperimentListViewModelTest` 不再创建 KMP remote、local store 或 use case，而是使用：

```kotlin
private class LegacyExperimentListGateway : ExperimentListGateway
```

这个 fixture 只使用 Android-owned values。它证明同一个 ViewModel 能在切换前由旧实现驱动。

为什么 fixture 不进入 `src/main`？

PocketLab 没有一套真实 legacy production database。为了演示而维护第二套假实现，会增加代码量，却不能提供真实回滚证据。生产项目应保留自己的真实旧实现；学习仓库只保留最小契约证据。

门禁会拒绝 `LegacyExperimentListGateway` 进入生产 source。

## 第七步：在 Android composition root 切换

`AndroidPocketLabGraph` 是唯一选择点：

```kotlin
internal val experimentListGateway: ExperimentListGateway =
    KmpExperimentListGateway(
        localStore = repository,
        refreshExperiments = refreshExperiments,
    )
```

如果这是生产 rollout，可按启动时读取的稳定 flag 选择：

```kotlin
val gateway = if (rollout.useKmpExperimentList) {
    KmpExperimentListGateway(...)
} else {
    LegacyExperimentListGateway(...)
}
```

不要在每次 `refresh()` 时重新读 flag。否则同一个页面可能先观察旧数据库，再把刷新写进新数据库，切换本身就制造故障。

建议在以下作用域固定选择：

- App process；
- 登录 session；
- feature owner 生命周期。

具体选择取决于数据与生命周期，但必须比单次操作更稳定。

## 第八步：建立 Swift 宿主协议

打开：

```text
iosApp/PocketLabIos/ExperimentListGateway.swift
```

Swift 契约是：

```swift
@MainActor
protocol ExperimentListGateway: AnyObject {
    func experiments() -> AsyncThrowingStream<[Experiment], any Error>
    func refresh() async throws -> RefreshOutcome
    func close()
}
```

它没有复制 Android 的 `Flow` 或 sealed interface，而是使用 Swift 的 AsyncSequence、async/throws 和 value types。

共享的是业务含义：

```text
观察列表
执行一次刷新
关闭 feature owner
```

不共享的是语言形状。

### Swift ViewModel 不再 import framework

运行：

```shell
rg 'PocketLabShared|PocketLabFeature|ExperimentListClient' \
  iosApp/PocketLabIos/ExperimentListViewModel.swift \
  iosApp/PocketLabIos/ExperimentListScreen.swift
```

预期没有输出。

只有 `KmpExperimentListGateway` 和 `PocketLabIosApp` composition root import `PocketLabShared`。

这让已有 SwiftUI/Combine 层保持平台所有权，也让 framework API 变化只影响 adapter。

## 第九步：处理 Swift 6 deinit 陷阱

第一次严格编译失败：

```text
call to main actor-isolated instance method 'close()'
in a synchronous nonisolated context
```

即使类标记为 `@MainActor`，Swift 6 的同步 `deinit` 也不能直接调用 main-actor-isolated protocol method。

最终实现显式调度：

```swift
deinit {
    manualRefreshTask?.cancel()
    Task { @MainActor [gateway] in
        gateway.close()
    }
}
```

这要求 `close()` 幂等，因为资源关闭可能与其他生命周期路径竞争。PocketLab 的 KMP feature 已经保证重复 close 安全。

不要通过删除 `close()` 让编译通过。那会把 network、database 和 Kotlin Jobs 的生命周期问题隐藏起来。

## 第十步：用 Swift legacy fixture 证明回滚结构

`scripts/incremental-adoption-smoke.swift` 定义一个完全不 import KMP 的：

```swift
LegacyExperimentListGatewayFixture
```

它可以构造同一个：

```swift
ExperimentListViewModel(gateway: legacyGateway)
```

门禁把它与真正的 KMP adapter、SwiftUI App 一起用：

```text
-swift-version 6
-strict-concurrency=complete
-warnings-as-errors
```

进行 type-check。

这证明的是 compile-time replaceability，不是线上 flag 已经演练。真实生产回滚还需要构建、签名、配置下发、数据兼容和监控。

## 第十一步：禁止 runtime 双写

最诱人的 shadow migration 是：

```text
旧实现 refresh 一次
KMP 实现 refresh 一次
比较结果
```

但 PocketLab refresh 会发网络请求并写数据库。双跑可能造成：

- 请求翻倍；
- 后端 rate limit；
- 两个 transaction 顺序不确定；
- 本地 pending record 被不同规则处理；
- retry 与 cancellation 不一致；
- 用户看到重复副作用。

因此本实验只允许：

```text
pure mapping         离线 parity test
read-only query      受控 shadow compare
write/refresh        单一 active implementation
```

如果业务必须迁移数据，使用版本化 schema migration、一次性 backfill 或明确 outbox protocol，而不是长期应用层双写。

## 第十二步：设计真正的 rollout

生产 rollout 不应只有 0% 和 100%。建议状态机：

```text
disabled
→ internal
→ 1% cohort
→ 10%
→ 50%
→ 100%
→ observation window
→ remove legacy + flag
```

每一步预先定义：

- owner；
- 放量条件；
- 停止条件；
- rollback command；
- 数据降级兼容；
- 观察窗口；
- 下一次决策时间。

指标至少按 platform、version/build、cohort 分层：

```text
crash-free sessions
ANR / hang
refresh applied/failed/cancelled/crashed
latency p50/p95/p99
database migration errors
support tickets
```

不要只看平均值，也不要把 KMP cohort 与不同 App version 混在一起。

## 第十三步：区分三种回滚

### 配置回滚

远端 flag 切回 legacy，不发新版本。最快，但前提是旧实现和旧数据路径仍在 binary 中。

### 代码回滚

revert commit、重新构建并发布。慢一些，但能移除故障代码。移动端还受商店审核与用户升级速度影响。

### 数据回滚

恢复旧 schema 或让旧 binary 继续读取新 schema。通常最难，也最容易被“有 feature flag”掩盖。

一个 flag 只能切换代码路径，不能自动撤销已经写入的数据。发布前必须回答：

```text
旧版本能否读取新数据？
新字段是否可忽略？
migration 是否 destructive？
server 是否仍接受旧协议？
```

如果答案是否定的，就不是“一键回滚”。

## 第十四步：运行渐进迁移门禁

```shell
cd ../02-first-kmp-module
./scripts/verify-incremental-adoption.sh
```

它验证：

```text
Android unit tests
Apple Debug framework
Android host contract + KMP adapter
Android ViewModel/UI KMP type denylist
Swift host contract + KMP adapter
Swift ViewModel/UI framework type denylist
双端 composition roots
legacy fixture 只存在于测试
adapter 没有第二次数据写入
Swift 6 strict concurrency + warnings-as-errors
```

成功输出：

```text
Incremental adoption contract passed.
Android host ViewModel/UI -> Android-owned contract -> KMP adapter
Swift host ViewModel/UI -> Swift-owned contract -> KMP adapter
Legacy rollback fixtures: test/type-check only
Production dual writes: 0
```

## 第十五步：运行 0.17.0 Release smoke

门禁先生成 minified Release：

```shell
./scripts/verify-observability.sh
```

本地 smoke 对 unsigned APK 的 ignored 副本使用 debug keystore 签名，然后：

```shell
adb install -r build/observability/android/pocketlab-release-smoke.apk
adb logcat -c
adb shell am force-stop dev.kmpjourney.pocketlab
adb shell am start -W \
  -n dev.kmpjourney.pocketlab/dev.kmpjourney.pocketlab.android.MainActivity
adb logcat -d -s PocketLab:I AndroidRuntime:E '*:S'
```

本轮结果：

```text
LaunchState: COLD
TotalTime: 1130 ms
refresh duration_ms=408
outcome=applied received_count=3 changed_count=0
```

这些单次数字证明 minified Release 的 KMP adapter 路径可达，不是性能预算。

## 迁移完成的定义

第一条切片完成不等于全 App 完成。对 experiment list，退出条件可以是：

- [ ] 两端 host contract 稳定；
- [ ] adapter/parity tests 通过；
- [ ] Release consumer 和 runtime smoke 通过；
- [ ] internal + cohort rollout 达标；
- [ ] rollback 演练成功；
- [ ] 数据 schema 对降级版本兼容；
- [ ] 观察窗口无显著回归；
- [ ] legacy implementation、flag、metrics dimension 和兼容代码已删除；
- [ ] owner/runbook/文档已更新。

最后一项代码删除完成后，迁移才真正减少系统复杂度。

## 本实验没有做什么

- 没有接入远端 feature flag 服务；
- 没有虚构第二套 production database；
- 没有并行执行两次 refresh；
- 没有迁移 Compose/SwiftUI、导航或编辑页；
- 没有证明真实用户 cohort 指标；
- 没有在缺失 runtime 的本机声称 iOS App 启动成功；
- 没有因为 host DTO 与 KMP DTO 相似就删除 adapter。

这些限制让当前实验只证明它实际覆盖的“可替换宿主边界”。

## 进一步挑战

### 挑战一：为真实 legacy repository 写 adapter

在 test fixture 中模拟一个 Room/URLSession 旧实现，只返回 host values。确认 ViewModel 和 UI 不需要任何修改。

### 挑战二：做纯函数 parity corpus

为 failure/status mapping 准备固定输入 corpus，同时跑 legacy mapper 和 KMP mapper，记录结构化 diff。不要连接数据库或网络。

### 挑战三：设计 cohort assignment

写出稳定分桶算法，明确账户切换、匿名用户、设备迁移和重新安装时的行为。assignment 不得使用敏感标识进入日志。

### 挑战四：演练数据降级

让 0.17.0 写入一份数据库，再用上一版 reader 打开。区分“代码路径可切回”和“旧 binary 可读取新数据”。

### 挑战五：删除迁移脚手架

假设观察窗口结束，写一个清理 PR 计划：删除 legacy、flag、cohort metric、过渡 adapter 和兼容 schema。估算不删除的长期成本。

## 权威资料

- [Kotlin 官方：把现有 Android App 迁移到 KMP](https://kotlinlang.org/docs/multiplatform/multiplatform-integrate-in-existing-app.html)
- [Kotlin 官方：渐进迁移 Jetpack Compose App](https://kotlinlang.org/docs/multiplatform/migrate-from-android.html)
- [Android Developers：向现有工程添加 KMP](https://developer.android.com/kotlin/multiplatform/migrate)
- [KMP 工程配置选择](https://kotlinlang.org/docs/multiplatform/multiplatform-project-configuration.html)
- [KMP 推荐工程结构](https://kotlinlang.org/docs/multiplatform/multiplatform-project-recommended-structure.html)
- [iOS integration methods](https://kotlinlang.org/docs/multiplatform/multiplatform-ios-integration-overview.html)
- [KMP direct integration](https://kotlinlang.org/docs/multiplatform/multiplatform-direct-integration.html)
- [SwiftPM export setup](https://kotlinlang.org/docs/multiplatform/multiplatform-spm-export.html)
