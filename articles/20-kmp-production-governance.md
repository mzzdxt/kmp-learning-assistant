# KMP 上生产：团队协作、版本治理与回滚策略

> 适合读者：已经能构建 Android/iOS KMP App，正在准备交付真实用户、建立团队流程或承担技术负责人职责的开发者。
>
> 本文版本敏感信息与实验结果验证于 2026-11-20。

## 开场：群里只说了两个字——回滚

假设 PocketLab 0.18.0 正在放量。

凌晨两点，Android crash rate 上升，iOS 刷新失败也开始增加。群里很快出现一句熟悉的话：

> 先回滚。

接下来通常会有六个人问六个不同的问题：

- 回滚远端 feature flag，还是停止商店放量？
- 已经更新的用户怎么办？
- Android 旧版还能读取新数据库吗？
- iOS 的 dSYM 对应哪个 archive？
- shared framework 到底是哪个 commit 构建的？
- 谁有权限做决定，primary 不在线时找谁？

如果这些问题要在事故中第一次讨论，团队拥有的不是回滚策略，只是“希望旧版本还好用”。

过去 19 个实验，我们让 PocketLab 拥有了 KMP domain/data、Compose 与 SwiftUI、测试矩阵、Swift API、XCFramework、构建缓存、诊断、渐进迁移和技术路线决策。

最后一篇不再新增 API，而是处理更难的一层：

> 一份代码被两个平台共同使用后，谁来拥有它，如何升级它，怎样分批发布，以及出事时到底能撤回什么？

先给出实验结论：

```text
Production release contract: passed
Required gates: 5/8 passed
Release ready: false
```

这三个句子并不矛盾。

合同通过，表示规则完整且没有自相矛盾；not ready，表示最终 iOS runtime/archive 和真实 production rollout 仍缺证据。

成熟的生产治理，不是把所有格子涂绿，而是让缺失证据无法被写成绿色。

## 一、KMP 上生产后，多出来的不是一个 module，而是一组共同责任

纯原生团队容易形成这样的边界：

```text
Android owner → Android code/build/release
iOS owner     → iOS code/build/release
```

加入 KMP 后，依赖图变成：

```text
                 ┌→ Android App → Play
shared Kotlin ───┤
                 └→ Apple framework → iOS App → App Store
```

共同代码的一次变化可能同时影响：

- JVM/Android/Native 编译；
- Kotlin/Swift API；
- Android App；
- iOS framework 与 Xcode link；
- database schema；
- network contract；
- 两端发布节奏；
- crash symbol 与诊断字段。

这就是 KMP 的组织含义：

> 共享代码减少重复实现，却增加了跨平台协调面。

如果团队只讨论代码复用，不讨论共同 owner，shared module 很容易变成一个“大家都能改、事故时没人全权负责”的公共区域。

## 二、生产 readiness 不是一个 Boolean，而是一条证据链

“能上生产吗？”经常被压缩成一个 yes/no。

更准确的模型是：

```text
source identity
→ toolchain compatibility
→ tests and API gates
→ Release artifacts
→ consumer/runtime evidence
→ artifact identity
→ staged rollout capability
→ rollback drill
→ cleanup ownership
```

任何一环缺失，都要说明影响，而不是用另一环的成功补偿。

例如：

- shared tests 全通过，不能替代最终 iOS App runtime；
- XCFramework 能构建，不能替代 App Store archive/dSYM；
- 有 feature flag 文档，不能替代真实 remote configuration；
- 能 pause rollout，不能逆转已写数据库；
- 有 dSYM 文件，不能证明它匹配出事的 binary。

这与前面技术路线决策里的 hard gate 是同一种思想：不可接受的缺口不能被平均分掩盖。

## 三、第一个坑：四处相同的版本，不是一个版本来源

实验开始前，PocketLab 的 `0.17.0/build 17` 写在：

```text
androidApp/build.gradle.kts
shared/build.gradle.kts
iosApp/generate_xcode_project.rb
iosApp/PocketLabIos.xcodeproj/project.pbxproj
```

它们现在一致，只能证明前一次修改很仔细。

下次发布可能发生：

```text
Android: 0.18.0 (18)
KMP framework metadata: 0.17.0 (17)
Xcode generator: 0.18.0 (18)
generated pbxproj: 0.17.0 (17)
```

构建甚至可能成功，但 artifact、crash、mapping 与 dSYM 已经失去共同身份。

### 建立一个 source of truth

PocketLab 新增：

```properties
versionName=0.17.0
buildNumber=17
databaseSchema=1
diagnosticSchema=1
```

Android、KMP framework 和 Xcode generator 都读取它，门禁再检查已生成 `.pbxproj` 没有 drift。

### 为什么仍然需要四个字段？

单一来源不等于单一数字。

| 字段 | 表达什么 | 何时改变 |
|---|---|---|
| versionName | 用户/团队识别的发布版本 | 产品 release |
| buildNumber | 商店与 binary 的单调身份 | 每次可发布构建 |
| databaseSchema | 持久化数据格式 | schema migration |
| diagnosticSchema | 日志/事件消费者契约 | 字段或语义变化 |

一次纯文案修复需要新 build，不需要假装数据库 schema 也升级。

一次日志字段语义变化可能需要 diagnostic schema +1，即使数据库不变。

版本治理不是把所有东西绑成同一个数字，而是让一次发布明确引用所有相关协议版本。

## 四、第二个坑：version catalog 不是 resolved graph

团队常说：“依赖版本都放进 `libs.versions.toml` 了，所以版本受控。”

version catalog 解决的是声明集中化：

```text
Compose = x
Ktor = y
SQLDelight = z
```

它并不强制最终 dependency resolution 一定选择这些值。Gradle 官方说明，version catalog 提供 requested versions，但 conflict resolution 仍可能选择其他版本。[Gradle version catalogs](https://docs.gradle.org/current/userguide/version_catalogs.html)

如果目标是可复现 resolved graph，需要考虑：

- dependency locking；
- platform/BOM constraints；
- dependency insight report；
- 禁止 dynamic version 和 changing coordinates；
- 发布物中记录真实工具链与 resolved versions。

Gradle dependency locking 会把解析结果写入 lock state，并在后续构建验证是否漂移。[Gradle dependency locking](https://docs.gradle.org/current/userguide/dependency_locking.html)

这不意味着每个学习项目必须立刻锁定所有 configuration。它意味着要分清：

```text
catalog = 我请求什么
lock/constraints = 最终允许解析成什么
artifact manifest = 这次实际发布了什么
```

## 五、工具链版本要一起审查，但不要一起盲升

KMP 工程的版本面至少包括：

```text
Kotlin / KMP plugin
Gradle
Android Gradle Plugin
Android SDK / R8
Xcode / Swift
Compose Multiplatform / Jetpack Compose
Ktor / coroutines / serialization / SQLDelight
```

它们不是一条“全部 latest”的直线。

第 19 个实验已经给了一个现实例子：Compose Multiplatform 1.12.2 映射到新的 Jetpack Compose，并要求 PocketLab 同时升级 compileSdk/AGP；Apple x86_64 artifacts 也已移除。

Kotlin 官方提供 KMP plugin 与 Gradle、AGP、Xcode 的兼容范围。[KMP compatibility guide](https://kotlinlang.org/docs/multiplatform/multiplatform-compatibility-guide.html)

兼容表回答的是“官方支持的候选区间”，不能替代：

- 你家的插件组合；
- dependency variants；
- 生成代码；
- Swift API；
- Release link；
- 实际设备 runtime。

### 一个更安全的升级 lane

```text
1. 只改版本，不混业务功能
2. 阅读从旧到新的 incompatible changes
3. 输出 dependency/toolchain diff
4. 运行 fast JVM lane
5. 运行 Android + Apple target matrix
6. 生成 Release AAR/framework/XCFramework/App
7. consumer compile + runtime smoke
8. 对比 API、包体、构建和性能
9. internal/canary
10. 合并后删除临时兼容开关
```

升级失败时，团队应该能判断失败属于哪个层，而不是反复修改业务代码。

## 六、API 版本：能源码编译，不等于已有消费者不会坏

如果 shared code 只在同一仓库、同一 commit 与 App 一起构建，source compatibility 和全仓 CI 能覆盖很多风险。

当 shared binary 跨仓发布后，问题改变了：

- 旧消费者是否还能链接新 binary？
- 新消费者能否暂时使用旧 binary？
- Swift header 是否改变名称、nullability 或 callback 形状？
- Kotlin public API 是否破坏 binary compatibility？
- framework coordinates 是否被覆盖？

### Kotlin consumer

Kotlin Gradle plugin 2.2.0 起提供 binary compatibility validation，`apiDump` 保存 API snapshot，`apiCheck` 在 build 中比较。KLib validation 需要结合官方支持状态审查。[Kotlin backward compatibility guidelines](https://kotlinlang.org/docs/api-guidelines-backward-compatibility.html)

它适合在这些条件下升级为 blocking gate：

- shared Kotlin library 独立发版；
- 有跨仓 Kotlin consumer；
- library 与 App 不总是同 commit 构建。

PocketLab 当前 shared Kotlin API 没有跨仓发布，因此合同把它记为非必需 `not_implemented`，而不是装作已经治理。

### Apple consumer

Apple 侧不能只依赖 Kotlin API dump。还要检查：

- framework header allowlist/denylist；
- Swift names；
- hidden implementation types；
- Swift 6 strict concurrency consumer；
- binary/framework version；
- XCFramework slices；
- SwiftPM checksum。

这也是为什么 PocketLab 的 Apple API gate 与 distribution gate 是两条证据。

## 七、团队 ownership：不要让 `shared` 的 owner 叫“大家”

生产合同声明六个 area：

```text
release_command
shared_runtime
android_app
ios_app
data_migration
observability
```

每个都有 primary 和 backup。

### primary/backup 到底负责什么？

不是“所有代码都由他写”，而是：

- 该 area 的 gate 由谁解释；
- 哪些风险必须阻止 promotion；
- 事故时谁有停止权限；
- 谁维护 runbook；
- primary 不在线时谁能接手；
- 何时删除临时兼容代码。

### 为什么 Android owner 不能自动等于 KMP owner？

Kotlin 技能有重叠，但 production ownership 不同。

shared runtime owner 需要理解：

- source set/targets；
- Native memory/concurrency；
- framework/API；
- database/network；
- 双平台消费者。

Android owner 还要负责：

- lifecycle/ANR；
- R8/mapping；
- Play release；
- Android performance/device matrix。

iOS owner 仍负责：

- Swift/SwiftUI/UIKit；
- Xcode/signing/archive；
- dSYM/symbolication；
- App Store/runtime；
- Apple platform behavior。

使用 Kotlin 共享业务，不会让平台责任消失。

## 八、CI 不该是一条 40 分钟的“万能命令”

KMP 的 target matrix 很容易让每个 PR 都变成昂贵全量构建。

更合理的是按反馈目标分 lane：

### PR fast lane

```text
format/static gates
domain/shared JVM tests
contract/API snapshots
affected Android host tests
```

目标：尽快告诉作者逻辑和边界是否错误。

### Platform lane

```text
Android host/unit/UI
iOS x64 or arm64 Simulator tests on supported host
Swift consumer compile
```

目标：验证 platform variants 和 interop。

### Merge/nightly lane

```text
full target matrix
SQLDelight migration
module boundaries
observability symbols
dependency/security scan
```

目标：捕获跨模块、工具链和低频目标风险。

### Release candidate lane

```text
minified Android Release
mapping + artifact manifest
Apple Release frameworks/XCFramework
final Xcode archive + dSYM
runtime smoke
size/performance diff
release contract
```

目标：产出可以被追责、符号化和分批发布的 binary。

“所有测试都跑了”不够。每条 lane 都要写清它在保护什么，以及它没有保护什么。

## 九、Artifact identity：没有匹配符号文件，就没有可靠堆栈

当线上 crash 到来，最危险的错误不是“没有 mapping”，而是“拿错 mapping 还能输出一个看似合理的堆栈”。

### Android 发布清单

```text
versionName / versionCode
commit
AAB/APK SHA-256
mapping.txt SHA-256
pg_map_id
Kotlin / AGP / R8 / JDK
release decision + rollout history
```

### Apple 发布清单

```text
version / build
commit
xcarchive SHA-256
binary UUID
dSYM UUID
Kotlin / Xcode / Swift
release decision + phased history
```

### KMP binary package

```text
semantic version
XCFramework ZIP SHA-256
SwiftPM checksum
header hash
slices/platforms
build metadata
```

同一版本坐标不能覆盖不同 bytes。

这不仅是“版本规范”，也是缓存和事故取证的基础。Gradle 官方同样警告不要用同一个 non-changing version 重新发布不同 binary。[Gradle caching guidance](https://docs.gradle.org/current/userguide/common_caching_problems.html)

## 十、Rollout 不是 0% 和 100% 之间的一根滑杆

一次生产 rollout 是状态机：

```text
candidate
→ internal
→ canary
→ expand
→ broad
→ 100%
→ observation
→ cleanup
```

每个状态需要：

- owner；
- 进入条件；
- 最小观察窗口；
- 样本量；
- promotion metrics；
- stop conditions；
- 下一步 action；
- rollback capability。

### Android staged rollout

Google Play 支持按百分比分阶段发布、提高比例、halt 和 resume。[Play Console staged rollout](https://support.google.com/googleplay/android-developer/answer/6346149)

关键事实：halt 不会把已经更新的人降级。

因此 Android 示例：

```text
internal
→ 1% / 24h
→ 10% / 24h
→ 50% / 48h
→ 100% / 168h observation
→ cleanup
```

具体百分比和时间不是通用答案。高风险 migration 可以更慢，紧急安全修复可能更快，但每次偏离都要有 owner 与理由。

### iOS phased release

Apple 对自动更新使用 7 天节奏：

```text
1% → 2% → 5% → 10% → 20% → 50% → 100%
```

可以 pause，累计 pause 最长 30 天；用户仍可手动下载更新。[App Store phased release](https://developer.apple.com/help/app-store-connect/update-your-app/release-a-version-update-in-phases)

所以 iOS 也不能把当前百分比当作严格 cohort firewall。

## 十一、Promotion 看什么？先按版本切片，再谈阈值

PocketLab 合同选择四类指标：

```text
crash-free sessions
refresh failure rate
p95 refresh duration
database migration errors
```

每个至少按：

```text
platform + version + build + cohort
```

切片。

### 为什么不用一个跨平台平均值？

假设 Android 新版 crash 飙升，iOS 样本更大且稳定，合并平均可能掩盖事故。

假设 0.18.0 很慢，但 0.17.0 占多数，按平台不按 build 仍会稀释回归。

因此 promotion 问的是：

> 这个平台、这个 build、这个 cohort，相对已批准 SLO 和上一健康版本是否可接受？

### 为什么文章不直接给 99.9%？

因为阈值必须结合：

- 真实 baseline；
- 样本量；
- 用户旅程；
- 风险等级；
- 告警延迟；
- 业务损失。

学习仓库没有生产用户，虚构一个漂亮 SLO 反而会教坏读者。

## 十二、“回滚”至少有三种，能力边界完全不同

### 1. 配置回滚

```text
remote flag off
→ next stable session/process selects old path
```

适合：

- 可替换 UI；
- 入口开关；
- adapter selection；
- 不改变 schema 的行为。

不适合：

- flag service 尚未实现；
- crash 发生在读取配置前；
- 已写坏数据；
- 新 binary 已删除旧 path；
- 同一次用户旅程中切换 data authority。

### 2. Binary forward fix

移动商店通常不能把所有已更新用户强制降级。因此实际动作是：

```text
halt/pause
→ 修复
→ build number +1
→ 重跑 gates
→ 提交/审核
→ staged rollout
```

它有时间成本，所以高风险功能仍需要配置隔离和安全 schema。

### 3. 数据恢复/前向修复

如果新版本已经迁移本地数据库或写入服务端：

- 关 flag 不会恢复旧值；
- 旧 binary 可能读不懂；
- down migration 可能丢失新数据；
- backup restore 可能覆盖事故后的正常写入。

默认策略应该是：

```text
expand schema
→ migrate/backfill
→ observe mixed versions
→ contract after support window
```

事故时优先 forward fix。restore 需要独立 incident decision、RPO/RTO、写入冻结和重放计划。

## 十三、Feature flag 是有利息的债务，不是永久架构

一个合格 flag 至少要有：

```text
id
owner
default
stable selection scope
expiry
removal condition
metrics
data authority
```

PocketLab 的 shared UI 候选 flag 被写成：

```text
status = planned
default = false
scope = app_session
required_for_release = false
```

这几个字段很诚实：它还没有实现，所以不能作为 0.17.0 的回滚证据。

### 为什么 selection scope 必须稳定？

如果每次 refresh 都重新读 flag：

```text
第一次 observe → old database
第二次 refresh → new database
第三次 render → old adapter
```

配置系统本身制造了双写和事实来源漂移。

更合理的是在 App process、登录 session 或 feature owner 生命周期固定选择。

### 什么时候删？

```text
100% rollout
→ healthy observation window
→ no rollback decision
→ remove legacy path
→ remove flag
→ remove transition metrics
→ remove compatibility adapter
```

永久 flag 会让每次需求都支付两套代码、测试、监控和理解成本。

## 十四、数据库回滚：最危险的是一句“我们有 migration”

SQLDelight `verifyMigrations` 可以检查 schema migration 与 query compatibility，这是必要证据。

但 production data safety 还需要：

- 旧 App 与新 schema 的兼容窗口；
- backfill 是否幂等；
- migration 中断如何恢复；
- 大数据量时间与锁；
- backup/restore 真实演练；
- server/local schema 协同；
- 删除字段的延迟；
- telemetry 不记录用户内容。

一个常见的安全节奏：

### Expand

新增 nullable/default 字段、并行读写能力，不立即删除旧字段。

### Migrate

后台或分批 backfill，观察错误、耗时和数据一致性。

### Contract

当最低支持版本已经跨过旧协议、观察窗口结束，再删除旧字段/路径。

这套流程慢一点，却让“暂停 rollout”仍有意义。

## 十五、一次诚实的 readiness 计算

PocketLab 的 required gates：

| Gate | State |
|---|---|
| toolchain compatibility | passed |
| shared test matrix | passed |
| Android Release | passed |
| Apple distribution | passed |
| rollback contract drill | passed |
| iOS App runtime | blocked |
| final artifact manifest | blocked |
| production rollout backend | not implemented |

所以：

```text
5/8 passed
release_ready = false
```

### 为什么 Apple distribution passed，artifact manifest 仍 blocked？

因为我们已经有：

- Release frameworks；
- XCFramework；
- SwiftPM checksum；
- consumer compile。

但还缺：

- 最终 SwiftUI App `.xcarchive`；
- 最终 App dSYM；
- binary/dSYM UUID identity；
- 匹配 runtime 的 App smoke。

Kotlin/Native test executable 的 dSYM 不能冒充最终 App archive。

### 为什么合同本身能通过？

因为 verifier 的职责是检查：

- blockers 是否真实存在；
- required states 是否推导出同一个 readiness；
- 没有人把 `false` 手写成 `true`。

它不是把缺失外部系统变出来。

## 十六、不要只测好合同，要证明坏合同会失败

release verifier 在内存中制造六个错误：

1. primary 与 backup 相同；
2. rollout 百分比倒退；
3. 允许 destructive down migration；
4. blocked gates 却声明 ready；
5. shared binary version 漂移；
6. gate 指向不存在的 owner。

结果：

```text
Negative mutation checks: 6/6 rejected
```

这是一条很实用的测试原则：

> 门禁的价值不在于正确输入能通过，而在于错误输入真的过不去。

同样适用于 API allowlist、隐私 schema、artifact identity 和模块边界。

## 十七、一次生产事故的 15 分钟 runbook

下面不是万能流程，但能让事故开头不再从争论名词开始。

### 0–5 分钟：识别和限制扩散

1. 记录 platform/version/build/cohort；
2. 指定 incident commander；
3. halt/pause rollout；
4. 若真实 flag 存在且安全，固定 scope 关闭；
5. 保存当前 dashboard、release history 和 artifact IDs。

### 5–10 分钟：分类故障域

```text
presentation only?
binary/runtime?
API/interop?
database migration?
server contract?
symbol/observability gap?
```

确认：

- 已更新用户是否仍受影响；
- 旧版本是否安全；
- 数据是否已改变；
- mapping/dSYM 是否匹配；
- 是否需要冻结写入。

### 10–15 分钟：选择恢复路径

```text
config rollback
or binary forward fix
or data forward fix / approved restore
```

公布：

- owner；
- 下一次更新时间；
- 受影响范围；
- 当前止损动作；
- 不会做的危险动作。

事故后再补 postmortem、cleanup 和 gate。不要让临时 flag 或兼容分支永远留下。

## 十八、一份可执行的发布清单

### Change review

- [ ] shared scope 与 owner 清楚；
- [ ] public Kotlin/Swift API diff 已审查；
- [ ] database/network/diagnostic schema diff 已审查；
- [ ] dependency/toolchain diff 独立；
- [ ] feature flag 有 owner/expiry/removal；
- [ ] rollback 能力没有夸大。

### CI

- [ ] fast lane；
- [ ] Android platform lane；
- [ ] Apple platform lane；
- [ ] full target matrix；
- [ ] migration/API/module/observability gates；
- [ ] Release artifacts；
- [ ] consumer compile；
- [ ] runtime smoke。

### Release candidate

- [ ] single release identity 无 drift；
- [ ] AAB/APK + mapping identity；
- [ ] xcarchive + dSYM UUID；
- [ ] XCFramework/checksum/header hash；
- [ ] changelog 与 compatibility window；
- [ ] owner/backup/on-call；
- [ ] dashboard 和 stop conditions；
- [ ] data backup/restore readiness。

### Rollout

- [ ] internal 完成；
- [ ] cohort/version/build 可切片；
- [ ] 每级满足最小观察窗口；
- [ ] promotion 有明确批准人；
- [ ] halt/pause 权限已验证；
- [ ] 已更新用户的处理已说明。

### 100% 之后

- [ ] 继续观察，不立即宣布结束；
- [ ] 删除 legacy/flag/compat adapter；
- [ ] 前移 minimum supported version；
- [ ] 执行 schema contract phase；
- [ ] 归档 artifact + symbols + decision；
- [ ] 更新 runbook 和 ADR。

## 十九、从一个人到多团队，交付拓扑也会改变

### 同仓、同 release train

适合：

- 小团队；
- Android/iOS/shared 一起改；
- source integration 反馈快；
- App 与 framework 同 commit 发布。

重点是全仓 CI 和单一 release identity。

### 跨仓、binary distribution

适合：

- shared team 独立发布；
- consumer 有不同节奏；
- 需要明确 semantic version 与兼容窗口。

同时必须增加：

- API/binary compatibility；
- immutable repository；
- consumer matrix；
- deprecation policy；
- 支持版本数量；
- 安全修复传播 SLA。

### 多产品消费同一 shared library

此时“升级 KMP”不再是一个 App PR，而是 platform product release。需要维护：

- consumer inventory；
- minimum/maximum supported version；
- migration guide；
- sample/contract tests；
- release notes；
- EOL policy。

不要把单仓经验直接外推到内部 SDK 平台。

## 二十、PocketLab 当前的生产结论

PocketLab 已经证明：

- shared logic 能跨 JVM/Android/iOS 测试；
- Android minified Release 可运行并符号化；
- Apple framework/XCFramework/SwiftPM 可构建和消费；
- Kotlin/Swift host contracts 可隔离迁移；
- release identity 已由三端共同 source 驱动；
- owners、rollout、rollback 和 artifact contract 可机器检查。

PocketLab 还没有证明：

- 最终 iOS App runtime；
- final xcarchive/dSYM identity；
- 真实远端 feature flag；
- Play/App Store production rollout；
- production SLO 与真实样本量；
- database backup/restore drill；
- 真实团队 primary/backup/on-call。

所以正确结论不是“教程完成，已经生产级”，而是：

> 我们已经具备解释生产差距、阻止虚假 readiness，并继续补证据的工程框架。

这比一张全绿但含义模糊的 checklist 更接近专家能力。

## 结语：上生产不是发布一个包，而是承诺能长期负责

KMP 的技术魅力很容易展示：同一份 Kotlin 运行在 Android 和 iOS。

生产真正困难的部分则不太适合截图：

- 版本要可追溯；
- 依赖要可复现；
- API 要可演进；
- 团队要有 owner；
- rollout 要能停止；
- 已更新用户要有处理；
- 数据要能前向修复；
- symbols 要匹配 binary；
- 临时 flag 要会删除；
- 缺证据时要敢于说 not ready。

当这些问题都有答案，KMP 才不只是“跨平台代码”，而是一项可以被团队长期运营的生产能力。

## 动手挑战

为你自己的项目写一份最小 release contract：

1. 找出版本号被写了几次；
2. 列出 shared/Android/iOS/data/observability owners；
3. 写出线上允许同时存在的版本窗口；
4. 分开 config、binary 和 data rollback；
5. 保存 Android mapping 与 Apple dSYM 的 binary identity；
6. 写一个 blocked gate，并确保 readiness 自动变 false；
7. 故意破坏 rollout 顺序，证明 CI 会失败；
8. 100% 后删除一个真正的 flag。

最难的通常不是第 7 步，而是第 2 步。

## 参考资料

- [KMP compatibility guide](https://kotlinlang.org/docs/multiplatform/multiplatform-compatibility-guide.html)
- [Kotlin backward compatibility guidelines](https://kotlinlang.org/docs/api-guidelines-backward-compatibility.html)
- [Kotlin binary compatibility validation](https://kotlinlang.org/docs/gradle-binary-compatibility-validation.html)
- [Gradle version catalogs](https://docs.gradle.org/current/userguide/version_catalogs.html)
- [Gradle dependency locking](https://docs.gradle.org/current/userguide/dependency_locking.html)
- [Gradle dependency best practices](https://docs.gradle.org/current/userguide/best_practices_dependencies.html)
- [Google Play staged rollouts](https://support.google.com/googleplay/android-developer/answer/6346149)
- [Google Play release analysis](https://support.google.com/googleplay/android-developer/answer/7383463)
- [App Store phased release](https://developer.apple.com/help/app-store-connect/update-your-app/release-a-version-update-in-phases)
