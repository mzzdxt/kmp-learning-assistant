# PocketLab production release contract 结果

- 验证日期：2026-11-20
- Release identity：0.17.0/build 17
- Database schema：1
- Diagnostic schema：1
- 结论：合同有效，release 未就绪
- App runtime 修改：无

## 单一发布身份

新增：

```text
labs/02-first-kmp-module/release.properties
```

内容：

```properties
versionName=0.17.0
buildNumber=17
databaseSchema=1
diagnosticSchema=1
```

消费者：

| Consumer | 读取方式 | 最终值 |
|---|---|---|
| Android App | Gradle `Properties` | 0.17.0 / 17 |
| KMP framework | Gradle `Properties` | 0.17.0 / 17 |
| Xcode generator | Ruby properties parser | 0.17.0 / 17 |
| generated pbxproj | drift check | 0.17.0 / 17 |

门禁同时检查 SQLDelight `1.db` baseline 与 diagnostic source 中 `schema=1`。

## Ownership

6 个 area 都有不同的 primary 与 backup：

```text
release_command
shared_runtime
android_app
ios_app
data_migration
observability
```

角色 handle 是学习仓库占位，尚未替换为真实组织的人名、值班表和 escalation policy。

## Compatibility contract

```text
mixed version max gap: 2 builds
server changes: additive
unknown fields: ignored
field removal: after minimum supported version advances

database: expand → migrate → contract
rollback: forward fix only
destructive down migration: false
active data writers: one
```

`max gap=2` 是 PocketLab 示例策略，不是生产统计结论。

## Promotion metrics

4 个 promotion metrics：

- crash-free sessions；
- refresh failure rate；
- p95 refresh duration；
- database migration errors。

每个都必须按：

```text
platform + version + build + cohort
```

切片。阈值引用 approved SLO/budget 或上一健康版本，仓库没有虚构 production SLO。

## Rollout plan

Android：

```text
internal → 1%/24h → 10%/24h → 50%/48h → 100%/168h cleanup window
```

iOS automatic updates：

```text
internal → 1 → 2 → 5 → 10 → 20 → 50 → 100%
```

iOS 每一级至少 24h，100% 后保留 168h cleanup window。

合同显式承认：

- Android halt 后已更新用户仍在新 build；
- iOS manual download 可以绕过 phased automatic update 百分比；
- pause/halt 不等于强制降级。

## Rollback contract

| 类型 | 动作 | 明确不能做什么 |
|---|---|---|
| Configuration | disable feature, next session selects old path | 不能逆转已持久化数据 |
| Binary | halt/pause, ship higher forward-fix build | 不能强制商店用户降级 |
| Data | forward fix 或经 incident approval 的 restore | 禁止常规 destructive down migration |

配置 rollback 还要求 toggle 真实实现。JSON 里的 planned flag 不构成远端 kill switch。

## Artifact manifest

Android 要求：

```text
AAB/APK + mapping.txt
artifact/mapping SHA-256
pg_map_id
versionName/versionCode
toolchain versions
```

Apple 要求：

```text
xcarchive + dSYM
binary/dSYM UUID
artifact SHA-256
version/build
toolchain versions
```

Shared binary 要求：

```text
XCFramework ZIP
SwiftPM checksum
header SHA-256
version/build
```

保留策略：至少 400 天。

## Gate 结果

必需 gate 共 8 个：

| Gate | State | 说明 |
|---|---|---|
| toolchain_compatibility | passed | 兼容表 + 真实构建 |
| shared_test_matrix | passed | JVM/Android/iOS x64 |
| android_release | passed | minified Release、mapping、runtime smoke |
| apple_distribution | passed | XCFramework、SwiftPM、consumer compile |
| ios_app_runtime | blocked | 缺匹配 runtime 与最终 App archive |
| artifact_manifest | blocked | 缺最终 iOS xcarchive/dSYM identity |
| production_rollout_backend | not_implemented | 无真实 remote cohort/store rollout |
| rollback_contract_drill | passed | config/binary/data contract machine-checked |

结果：

```text
required passed = 5/8
release_ready = false
```

非必需 `kotlin_library_api_validation` 当前为 `not_implemented`。PocketLab 的 shared Kotlin API 暂未作为跨仓 Kotlin library 发布；若改变交付拓扑，应升级为 blocking gate。

## 单一版本来源的真实构建复验

修改 Gradle/Xcode generator 后运行：

```text
:domain:allTests
:shared:allTests
:androidApp:testDebugUnitTest
:androidApp:assembleRelease
:shared:linkReleaseFrameworkIosX64
```

结果：160 tasks，BUILD SUCCESSFUL，2m20s。

产物 metadata：

```text
Android output-metadata.json: 0.17.0 / 17
PocketLabShared.framework Info.plist: 0.17.0 / 17
```

Observability gate 复验：

```text
Android APK SHA-256:
b166aeb277001101e93fd5b0de183067c4bbf7217812f4fc02498823c2c1644d

Android mapping SHA-256:
505da23d130ded5b69902a1b9216dd22dfef0c3572dfa2e7c0336583de58c240

pg_map_id:
f3b738b6286d0955804af004e955eea2304869d9d00b057b1d20bc44197a0b60

Kotlin/Native test binary + dSYM UUID:
F16EA677-4400-348D-8CD2-0FA512A4A33C
```

Apple distribution gate 复验：

```text
device slice: arm64
Simulator slice: x86_64 arm64
Swift 6 consumer: passed
XCFramework ZIP SwiftPM checksum:
7aeea75f3b444079319d8799a5733dd3eb19cf6c126a3353a32d1436230b98f7
```

旧 observability/distribution scripts 也改为读取 `release.properties`，避免门禁本身保留另一份硬编码版本。

## 运行输出

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

## Negative mutation checks

内存中构造并拒绝：

1. primary 与 backup 相同；
2. Android rollout 百分比不再严格递增；
3. 打开 destructive down migration；
4. required gates blocked 却声明 ready；
5. shared binary 版本漂移；
6. gate 指向未知 owner。

结果：6/6 rejected。

## 仍然缺失

- 匹配 Xcode 的 iOS runtime；
- 最终 SwiftUI App runtime smoke；
- final `.xcarchive + dSYM` UUID manifest；
- Android production AAB manifest；
- 真实 Play staged rollout；
- 真实 App Store phased release；
- 远端 feature flag/cohort service；
- production SLO 与最小样本量；
- 真实 primary/backup/on-call；
- database backup/restore drill 与 RPO/RTO；
- 线上 100% 后的 flag/legacy cleanup。

因此本结果不能被引用为“PocketLab production ready”。它证明的是：readiness 缺口已经被机器准确表达。
