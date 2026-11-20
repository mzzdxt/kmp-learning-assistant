# 实验 20：把“准备上生产”改写成机器可检查的发布合同

## 实验目标

这一轮不再新增业务功能，也不把“所有测试通过”直接翻译成“可以全量发布”。

我们要把 KMP 上生产前最容易散落在口头、群聊和个人经验里的规则，变成一份可审查、可失败的发布合同：

```text
one release identity
→ explicit owners
→ compatibility window
→ blocking gates
→ staged rollout
→ config / binary / data rollback
→ cleanup
```

完成后，你应该能解释：

- 为什么同一版本号需要一个来源，但 App、数据库和诊断 schema 不能共用一个数字；
- Android、iOS、KMP framework 和生成项目如何避免版本漂移；
- 为什么每个关键 area 都需要 primary + backup；
- 为什么“halt rollout”不会让已更新用户自动降级；
- feature flag、重新发 binary 与数据恢复分别能解决什么；
- 为什么 destructive down migration 不是常规回滚方案；
- 为什么发布合同通过，却仍可以得出 `release_ready=false`；
- 如何让错误的 readiness 声明被机器拒绝。

## 前置证据

本实验复用前 19 个里程碑已经建立的能力：

- shared test matrix；
- Android minified Release 与 R8 mapping；
- Apple framework/XCFramework/SwiftPM distribution；
- Swift 6 API consumer gate；
- module boundary；
- build/configuration cache；
- observability schema 与 artifact identity；
- host-owned migration seam；
- mobile strategy 和 shared UI scope ADR。

它们是发布合同的输入，不等于生产 readiness 的全部。

## 文件结构

```text
labs/20-production-governance/
├── README.md
├── release-contract.json
├── scripts/
│   └── verify_release_contract.rb
└── results/
    └── 2026-11-20-release-contract.md

labs/02-first-kmp-module/
└── release.properties
```

## 第一步：建立一个 release identity 来源

此前，`0.17.0 / 17` 分别硬编码在：

- Android `build.gradle.kts`；
- KMP framework `build.gradle.kts`；
- Xcode project generator；
- 已生成的 `.pbxproj`。

四处写着相同值不叫“统一版本”，只叫“暂时碰巧一致”。

现在使用：

```properties
versionName=0.17.0
buildNumber=17
databaseSchema=1
diagnosticSchema=1
```

注意这里仍有四个字段。

### 为什么不只留一个数字

`versionName/buildNumber` 表达发布身份；`databaseSchema` 表达持久化格式；`diagnosticSchema` 表达日志消费者契约。

它们变化的原因不同：

```text
UI 文案修复         → build +1，database schema 不变
新增数据库字段       → build +1，database schema +1
日志字段语义改变     → build +1，diagnostic schema +1
仅重跑相同源码构建   → 不得覆盖同一 immutable artifact
```

一个来源不等于一个数字；它表示一次发布的所有身份在一个可审查位置声明。

### 三端如何消费

Android：

```kotlin
versionCode = pocketLabBuild
versionName = pocketLabVersion
```

KMP framework：

```kotlin
binaryOption("bundleShortVersionString", pocketLabVersion)
binaryOption("bundleVersion", pocketLabBuild)
```

Xcode generator：

```ruby
settings["CURRENT_PROJECT_VERSION"] = release_build_number
settings["MARKETING_VERSION"] = release_version_name
```

已生成的 `.pbxproj` 仍会被门禁读取。源配置正确，但生成物没有重新生成，也属于 drift。

## 第二步：所有权不能写成“移动端团队”

合同声明 6 个 area：

```text
release_command
shared_runtime
android_app
ios_app
data_migration
observability
```

每个 area 必须有：

- primary；
- 不同的 backup；
- 对应 gate；
- 可检查 evidence。

门禁拒绝：

- owner 为空；
- primary 与 backup 是同一角色；
- owner 写成 `everyone`；
- gate 指向不存在的 area。

这不是为了制造组织图，而是回答故障发生后的第一个问题：

> 谁能决定停止放量，谁能在 primary 不在线时执行？

## 第三步：为混合版本世界写兼容窗口

移动发布永远存在混合版本：

- 商店审核和分发有延迟；
- 用户可以关闭自动更新；
- Android staged rollout 不会让所有人同时升级；
- Apple phased release 也只影响自动更新，用户仍可能手动下载；
- 线上请求会同时来自多个 build。

因此合同不写“客户端与服务端同时上线”，而写：

```text
max_build_gap = 2
server_contract = additive
unknown_fields = ignore
field_removal = after_minimum_supported_version_advances
```

对数据库则写：

```text
expand → migrate/backfill → observe → contract
rollback = forward_fix_only
destructive_down_migration = false
single_writer = true
```

这里的 `max_build_gap=2` 是 PocketLab 示例策略，不是行业标准。真实产品要根据强制升级能力、活跃版本分布和服务端兼容成本设定。

## 第四步：把 promotion 指标按版本切开

合同要求每个指标至少按以下维度切片：

```text
platform + version + build + cohort
```

当前四个 promotion metrics：

- crash-free sessions；
- refresh failure rate；
- p95 refresh duration；
- database migration errors。

合同没有伪造一个“通用 crash-free 99.9%”阈值，而是要求引用已批准 SLO、性能预算或上一健康版本。

原因是：

- 样本太小时，固定百分比可能没有统计意义；
- Android/iOS baseline 不同；
- 新版本绝对值与相对退化要同时看；
- migration error 可以零容忍，时延通常需要分位数预算；
- 平均值会隐藏 cohort 回归。

## 第五步：Android 与 iOS 不使用同一条 rollout 时间线

### Android 示例

```text
internal → 1% → 10% → 50% → 100% → 7-day cleanup window
```

Google Play staged rollout 可以选择并逐步增加百分比，也可以 halt。但 halt 只阻止更多用户拿到该版本；已经更新的用户仍留在新版本。[Google Play staged rollout](https://support.google.com/googleplay/android-developer/answer/6346149)

合同因此显式保存：

```json
"already_updated_users_remain_on_new_build": true
```

### iOS 示例

Apple phased release 的自动更新节奏为：

```text
Day 1..7 = 1% → 2% → 5% → 10% → 20% → 50% → 100%
```

它可以 pause，但手动下载仍可绕过当前 phase。[App Store phased release](https://developer.apple.com/help/app-store-connect/update-your-app/release-a-version-update-in-phases)

合同因此显式保存：

```json
"manual_downloads_can_bypass_phase": true
```

两端都不能把“暂停”描述成“撤回已经安装的 binary”。

## 第六步：把三种回滚分开

### 配置回滚

```text
disable feature → next stable selection scope uses old path
```

能处理：

- 被 flag 包住的 presentation/adapter；
- 新用户旅程入口；
- 不改变数据格式的行为切换。

不能处理：

- 已写入的错误数据；
- crash 发生在读取 flag 之前；
- 没有真正实现的 flag；
- 旧 binary 不认识的新 schema。

### Binary 回滚

商店环境下，更准确的动作通常是：

```text
halt/pause current rollout
→ build a forward fix with a higher build number
→ run gates again
→ staged rollout
```

不能假设平台会把已经更新的用户强制降到旧 build。

### 数据回滚

数据库与服务端数据可能已经被新版本修改。合同只允许：

```text
forward fix
or approved restore with incident ownership
```

并明确禁止常规 destructive down migration。

“恢复备份”也不是一个按钮：它涉及 RPO/RTO、写入冻结、重放、隐私、审计和受影响用户范围。没有演练的备份，只是一种愿望。

## 第七步：feature flag 必须会死亡

示例 flag：

```text
shared_ui_summary_card
status = planned
default = false
scope = app_session
owner = shared_runtime
expires = 2027-02-18
```

它当前没有实现，也不是 0.17.0 的发布依赖。合同不会因为 JSON 里写了 flag 就假装拥有远程回滚能力。

每个 flag 必须有：

- owner；
- 默认值；
- 稳定 selection scope；
- 过期日期；
- 删除条件；
- 单一 data writer 约束。

flag 到 100% 后仍要经过观察窗口，再删除旧实现、flag、cohort metric 和兼容代码。

## 第八步：保存能定位到 binary 的产物

### Android

```text
AAB/APK
mapping.txt
artifact + mapping SHA-256
pg_map_id
versionName/versionCode
Kotlin/AGP/R8/JDK versions
```

### Apple

```text
xcarchive
dSYM
binary + dSYM UUID
artifact SHA-256
version/build
Kotlin/Xcode versions
```

### Shared binary

```text
XCFramework ZIP
SwiftPM checksum
Apple header SHA-256
version/build
```

合同要求至少保留 400 天。真实期限应由最长支持版本、事故发现窗口、法规和存储预算决定。

同一个版本坐标不得覆盖不同 bytes。Gradle 官方也明确提醒，不应以同一 non-changing version 重发不同 binary，否则缓存与可复现性都会失去可信度。[Gradle cache guidance](https://docs.gradle.org/current/userguide/common_caching_problems.html)

## 第九步：运行合同门禁

```shell
cd labs/20-production-governance
./scripts/verify_release_contract.rb
```

门禁验证：

1. release identity schema；
2. Android/KMP/Xcode generator 都消费 `release.properties`；
3. 已生成 `.pbxproj` 与 source of truth 一致；
4. SQLDelight schema baseline 和 diagnostic schema 存在；
5. 6 个 owner areas 都有 primary + backup；
6. compatibility、single writer 和 forward-fix policy；
7. Android/iOS rollout 单调递增并到达 100%；
8. Apple phase 与官方 7 天百分比一致；
9. promotion metrics 的 platform/version/build/cohort 切片；
10. feature flag 的 owner/expiry/removal；
11. Android/Apple/shared artifact manifest 字段；
12. required gates 与 derived readiness 一致。

它还在内存中制造 6 个坏合同：

- primary 与 backup 相同；
- rollout 百分比倒退；
- destructive down migration；
- 把 blocked release 写成 ready；
- shared binary 版本漂移；
- gate owner 不存在。

6 个都必须被拒绝。只验证“好输入能通过”还不足以证明门禁真的会挡错。

## 最终输出

```text
Production release contract passed.
Release identity: 0.17.0 (17) from one properties file
Owners: 6 areas, each with primary and backup
Required gates: 5/8 passed
Release ready: false
Explicit blockers: artifact_manifest, ios_app_runtime, production_rollout_backend
Android rollout: 1 -> 10 -> 50 -> 100%
iOS phased release: 1 -> 2 -> 5 -> 10 -> 20 -> 50 -> 100%
Rollback modes: configuration != binary != data
Negative mutation checks: 6/6 rejected
```

完整记录见[实验结果](results/2026-11-20-release-contract.md)。

## 为什么合同通过，发布仍未就绪？

合同通过只证明：

- 输入完整；
- 规则互相一致；
- readiness 没有撒谎；
- 已知缺口被显式阻塞。

PocketLab 当前 3 个 blocker：

### `ios_app_runtime`

缺少匹配 Xcode 的 iOS runtime，最终 SwiftUI App 没有重新运行。

### `artifact_manifest`

已有 KMP framework/XCFramework evidence，但最终 iOS `.xcarchive + dSYM UUID` 尚未生成。Native test dSYM 不能替代最终 App archive。

### `production_rollout_backend`

没有真实远端 cohort、Play staged rollout 或 App Store phased release 执行证据。文档中的百分比不是线上放量。

成熟门禁的价值，不是让 dashboard 全绿，而是阻止团队把缺失证据写成绿色。

## 版本治理不是“永远不升级”

当前 Kotlin、Gradle、AGP、Xcode、Compose 与 libraries 有一组已验证组合。升级时应开独立 change set：

```text
compatibility table
→ dependency diff
→ source/API diff
→ full target matrix
→ Release artifacts
→ consumer compile
→ runtime canary
```

Kotlin 官方维护 KMP plugin 与 Gradle、AGP、Xcode 的兼容表；它是候选范围，不替代真实工程构建。[KMP compatibility guide](https://kotlinlang.org/docs/multiplatform/multiplatform-compatibility-guide.html)

若 shared Kotlin API 跨仓发布，应启用 `apiDump/apiCheck`。Kotlin Gradle plugin 2.2.0 起提供 binary compatibility validation，KLib validation 仍需注意其支持状态。[Kotlin binary compatibility validation](https://kotlinlang.org/docs/api-guidelines-backward-compatibility.html#use-the-binary-compatibility-validator)

版本目录只集中声明 requested versions，不保证最终 resolved graph；Gradle dependency locking 才会保存和验证实际解析版本。[Gradle dependency locking](https://docs.gradle.org/current/userguide/dependency_locking.html)

## 本实验没有证明什么

- 没有证明 PocketLab 已经可以发布；
- 没有真的创建 Play/App Store rollout；
- 没有接入远端 feature flag；
- 没有生成最终 iOS xcarchive/dSYM；
- 没有制定真实组织的人名与值班表；
- 没有为生产指标虚构 SLO；
- 没有执行真实数据库 restore；
- 没有修改 App version，仍为 0.17.0/build 17。

## 延伸挑战

1. 在 Apple Silicon + 匹配 runtime 上生成最终 iOS archive，并把 binary/dSYM UUID 写入 manifest；
2. 为 Android AAB 生成正式 artifact manifest；
3. 接入真实内部 track，只做 internal，不直接 1%；
4. 让一次故意失败的 canary 触发 halt runbook；
5. 在临时数据库执行 backup/restore drill，记录 RPO/RTO；
6. 为跨仓 Kotlin consumer 启用 `apiDump/apiCheck`；
7. 将角色 handle 替换为真实 primary/backup 和 on-call escalation；
8. rollout 结束后执行一次 flag/legacy cleanup PR。

## 权威资料

- [KMP compatibility guide](https://kotlinlang.org/docs/multiplatform/multiplatform-compatibility-guide.html)
- [Kotlin backward compatibility guidelines](https://kotlinlang.org/docs/api-guidelines-backward-compatibility.html)
- [Kotlin binary compatibility validation](https://kotlinlang.org/docs/gradle-binary-compatibility-validation.html)
- [Gradle dependency locking](https://docs.gradle.org/current/userguide/dependency_locking.html)
- [Gradle dependency best practices](https://docs.gradle.org/current/userguide/best_practices_dependencies.html)
- [Google Play staged rollouts](https://support.google.com/googleplay/android-developer/answer/6346149)
- [Google Play release analysis](https://support.google.com/googleplay/android-developer/answer/7383463)
- [App Store phased release](https://developer.apple.com/help/app-store-connect/update-your-app/release-a-version-update-in-phases)
