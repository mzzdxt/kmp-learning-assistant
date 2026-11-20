# 实验 16：让跨平台故障留下同一条证据链

## 实验目标

完成本实验后，你应该能回答：

1. 业务失败、协程取消、未预期异常和进程崩溃有什么区别？
2. 为什么日志不能改变被观察代码的返回值和异常？
3. Android 与 iOS 怎样共享事件语义，而不共享平台日志 API？
4. 为什么 duration 应该使用 monotonic clock？
5. 怎样设计一个默认不容纳隐私数据的固定日志 schema？
6. R8 后的 Android Release 堆栈为什么必须使用同一次构建的 mapping？
7. Apple crash report、binary 和 dSYM 的 UUID 怎样组成匹配关系？
8. 日志、crash、trace、ANR/hang 和 metrics 分别回答什么？
9. 怎样证明“可定位”，而不把 Debug 运行或单次耗时冒充生产结论？

主工程仍位于：

```text
../02-first-kmp-module
```

本轮机器、产物身份与限制记录在：

```text
results/2026-11-20-diagnostic-chain.md
```

## 前置条件

- 已完成[实验 15](../15-build-performance/README.md)，理解 task lane、Release 产物和证据边界；
- 本机有 JDK 17、Android SDK、Xcode command line tools 与 `rg`；
- Android runtime smoke 需要已启动的模拟器或设备；
- Apple App runtime 验证需要与 Xcode 匹配的 Simulator runtime；
- 不需要任何商业崩溃或日志平台账户。

## 先画出一次刷新的故障面

PocketLab 的刷新路径为：

```text
Compose / SwiftUI
       ↓
platform ViewModel
       ↓
RefreshExperimentsUseCase
       ↓
Ktor remote → protocol mapping → SQLDelight transaction
```

“刷新失败”至少可能表示：

```text
HTTP 503                    可预期业务失败
用户离开页面               协程取消
JSON 契约外异常             未预期异常
主线程长时间阻塞           ANR / hang
进程因未处理异常终止       crash
请求完成但变慢             performance regression
```

如果只记录：

```text
refresh failed
```

你无法判断是哪一次操作、哪种终态、持续多久，也不知道两端是否使用同一含义。

## 第一步：理解领域诊断端口

打开：

```text
../02-first-kmp-module/domain/src/commonMain/kotlin/
dev/kmpjourney/pocketlab/sync/RefreshDiagnostics.kt
```

它表达一个很小的生命周期：

```kotlin
interface RefreshDiagnostics {
    fun begin(): RefreshDiagnosticOperation
}

interface RefreshDiagnosticOperation {
    fun applied(receivedCount: Int, changedCount: Int)
    fun failed(reason: RefreshFailureReason, statusCode: Int?)
    fun cancelled()
    fun crashed(failure: Throwable)
}
```

这里没有：

- Android `Log`；
- Apple `Logger`/OSLog；
- JSON encoder；
- 崩溃供应商 SDK；
- 任意 message map。

领域层只定义“发生了什么”，平台层决定“写到哪里”。这和 repository port 的思想相同，但诊断端口还有一条额外约束：失败的日志系统不能让产品失败。

### 观察不能改变行为

打开 `RefreshExperimentsUseCase.kt`，关注三条路径：

```kotlin
catch (cancellation: CancellationException) {
    operation.observeSafely { cancelled() }
    throw cancellation
} catch (failure: Exception) {
    operation.observeSafely { crashed(failure) }
    throw failure
}
```

顺序不能交换。`CancellationException` 也是异常；如果普通 catch 先接住它，取消会被误记为 crash。

还要注意：

- 可预期 remote failure 仍返回 `RefreshExperimentsResult.Failed`；
- 取消重新抛出同一个实例；
- 未预期异常重新抛出同一个实例；
- 诊断后端抛错时，成功仍成功、原始失败仍是原始失败。

运行领域测试：

```shell
cd ../02-first-kmp-module
./gradlew :domain:jvmTest \
  --tests '*RefreshExperimentsDiagnosticsTest'
```

五个测试分别证明 success、expected failure、cancellation、unexpected exception 和 broken backend。

## 第二步：读懂固定事件 schema

打开：

```text
shared/src/commonMain/kotlin/
dev/kmpjourney/pocketlab/diagnostics/RefreshObservability.kt
```

成功刷新会形成两条记录：

```text
schema=1 event=pocketlab.refresh phase=started level=info
operation_id=<uuid>

schema=1 event=pocketlab.refresh phase=finished level=info
operation_id=<same-uuid> duration_ms=628 outcome=applied
received_count=3 changed_count=3
```

换行只是便于阅读，真实记录为单行。

### 为什么需要 correlation ID

两个用户操作可能并发：

```text
A started
B started
B finished
A finished
```

如果没有 `operation_id`，started 与 finished 无法可靠配对。线程名也不能替代它：协程可能挂起后换线程继续。

### 为什么不用任意 Map

下面的 API 看似灵活：

```kotlin
log("refresh", mapOf("anything" to value))
```

但它让字段、命名、类型和隐私都变成调用点的临时决定。当前 record 只允许：

```text
schema/event/phase/level
operation_id/duration_ms/outcome
received_count/changed_count
failure_reason/status_code/error_type
```

标题、假设、笔记、base URL、database path、Authorization、异常 message 不在类型中，因此不能“顺手”加入。`error_type` 只是受限现场线索：Android 应用类名可能被 R8 改名，不应把它当跨版本稳定 metric；需要聚合时应增加人工维护的稳定 category。

### 做一次隐私红队演练

在新增字段前逐一问：

1. 它是否直接识别用户？
2. 它是否来自用户输入或服务端正文？
3. 它是否可能含 token、URL query、路径或数据库内容？
4. 是否能改用稳定枚举、数量或类型名？
5. 保留多久、谁能访问、用户是否知道？

本实验把 operation ID 当作随机关联值，但它仍应按诊断数据治理；不要把账户 ID、邮箱或设备广告标识塞进去。

### 防止日志注入

token 最长 80 字符，只允许字母、数字、`-`、`_`、`.`；其他字符替换为 `_`。

例如：

```text
abc\nlevel=error token=secret
```

会变成单一安全 token，而不是伪造第二条日志。运行 schema 测试：

```shell
./gradlew :shared:jvmTest \
  --tests '*StructuredRefreshDiagnosticsTest'
```

## 第三步：用 monotonic clock 计算 duration

不要这样计算耗时：

```kotlin
val start = Clock.System.now()
val duration = Clock.System.now() - start
```

wall clock 用来回答“现在几点”，可能受系统校时、时区和用户设置影响。duration 回答“过去多久”，应该使用只前进的 monotonic source：

```kotlin
val mark = TimeSource.Monotonic.markNow()
val elapsed = mark.elapsedNow().inWholeMilliseconds
```

Kotlin 在 JVM 与 Native 上选择相应的单调时钟。测试注入 fake timer，精确证明输出值，不靠真的等待 628 ms。

注意：monotonic 让测量方法正确，不会自动让一个样本成为性能结论。要判断退化，需要相同场景、样本分布、设备分层和版本对照。

## 第四步：比较三个平台 writer

共同 schema 最终进入一个很薄的 `expect/actual`：

```text
jvmMain      stdout / stderr
androidMain  android.util.Log, tag=PocketLab
iosMain      NSLog（当前最小实验适配器）
```

Android 与 iOS 不需要共享日志 SDK；它们共享的是字段和终态含义。

当前 iOS 适配器故意保持最小。生产 App 应替换为 Apple unified logging 的 `Logger`/OSLog，利用 category、level、持久化与 privacy，而不是把 schema 逻辑复制到 Swift。

运行平台后端契约：

```shell
./gradlew \
  :shared:jvmTest \
  :shared:testAndroidHostTest \
  :shared:iosX64Test
```

`PlatformDiagnosticsContractTest` 会调用生产 factory，确保 writer 真能执行且不改变结果。

## 第五步：观察 Android Debug 日志

正常启动 App 后：

```shell
adb logcat -c
adb logcat -s PocketLab:I AndroidRuntime:E '*:S'
```

预期看到：

```text
I PocketLab: schema=1 event=pocketlab.refresh phase=started ...
I PocketLab: schema=1 event=pocketlab.refresh phase=finished ... outcome=applied ...
```

检查：

- started 与 finished 的 ID 相同；
- terminal event 只有一条；
- duration 非负；
- 没有实验标题、笔记、URL 或异常 message；
- 再次刷新时获得新的 operation ID。

Logcat 是开发者诊断通道，不应成为存放用户内容的理由。Release 是否保留、采样或上传日志需要单独的产品、隐私和运维决策。

## 第六步：构建真实 minified Android Release

运行：

```shell
./gradlew :androidApp:assembleRelease
```

检查产物：

```shell
ls -lh androidApp/build/outputs/apk/release/
ls -lh androidApp/build/outputs/mapping/release/mapping.txt
head -n 12 androidApp/build/outputs/mapping/release/mapping.txt
```

mapping header 应包含：

```text
# compiler: R8
# compiler_version: 9.1.29
# min_api: 24
# mapping_version: 2.2
# pg_map_id: ...
```

### 为什么固定 R8 9.1.29

第一次构建使用 AGP 8.13.2 内置 R8 时，输出了 Kotlin 2.4 metadata parse warnings。忽略它们会让“构建成功”掩盖兼容性风险。Android 官方兼容表要求 Kotlin 2.4 使用 R8 9.1.29，因此 settings 从官方 R8 repository 覆盖版本。

覆盖后 metadata warnings 消失。仍出现的 `resource provider does not support async parsing` 是 AGP provider 与独立 R8 组合的解析性能提示，不等同于 metadata 正确性警告。两者必须按文本和影响分别判断。

### 不要提交宽泛 keep rule

本项目只保留 source file 与 line number attributes：

```proguard
-keepattributes SourceFile,LineNumberTable
```

不要为了让堆栈“更好看”写：

```proguard
-keep class dev.kmpjourney.** { *; }
```

这会绕过 shrink/obfuscation，让你验证的不是实际 Release 风险。只有反射、JNI、序列化或第三方契约确实需要时，才加入最窄 keep rule。

## 第七步：把 mapping 和 APK 绑定到同一次构建

生成身份：

```shell
shasum -a 256 \
  androidApp/build/outputs/apk/release/androidApp-release-unsigned.apk

shasum -a 256 \
  androidApp/build/outputs/mapping/release/mapping.txt

rg '^# pg_map_id:' \
  androidApp/build/outputs/mapping/release/mapping.txt
```

发布记录至少保存：

```text
versionName + versionCode
Git commit
APK/AAB identity
mapping.txt + mapping identity + pg_map_id
R8/AGP/Kotlin versions
```

每次构建都可能覆盖 `mapping.txt`。同名文件不代表同一份映射。符号化时先匹配 build 身份，再运行 retrace。

典型命令为：

```shell
retrace mapping.txt obfuscated-stacktrace.txt
```

本实验的门禁进一步检查 mapping 中存在：

- Android App 的 `ExperimentListViewModel`；
- `RefreshExperimentsUseCase.kt` 的 source/line metadata；
- `RefreshObservability.kt` 的 source/line metadata。

### 可选：本地 Release smoke

仓库生成 unsigned Release。为了在本地模拟器验证，可复制产物并用本机临时/调试证书签名；不要修改发布配置让 Release 永久使用 debug signing。

示例流程：

```text
copy unsigned APK to ignored build directory
zipalign / apksigner sign the copied APK
apksigner verify
adb install -r signed-copy.apk
adb shell am start -W \
  -n dev.kmpjourney.pocketlab/dev.kmpjourney.pocketlab.android.MainActivity
```

本轮本地 smoke 的冷启动 `TotalTime` 为 1,414 ms，刷新事件为 628 ms。这两个单样本只证明 minified Release 可启动且诊断链可达，不能当启动或刷新预算。

## 第八步：理解 Kotlin 异常怎样过 Swift 边界

Kotlin suspend/普通方法抛出的异常并不会自动成为理想的 Swift `Error` API。对 Objective-C/Swift 导出时，只有契约允许的异常才能按 NSError 形式桥接；未覆盖的 Kotlin 异常可能成为未处理异常并终止进程。

这也是为什么本实验：

- 把可预期 failure 映射成稳定 report/value；
- 保持 cancellation 的原生取消语义；
- 不把任意异常 message 当产品文案；
- 不用日志后端吞掉未预期异常；
- 在最终 App archive 保存符号，而不是寄希望于现场字符串。

不要安装一个全局 handler 然后继续运行。捕获 crash 诊断和改变进程终止语义是两件事。

## 第九步：验证 dSYM UUID 和符号恢复

Xcode Release 配置应为：

```text
DEBUG_INFORMATION_FORMAT = dwarf-with-dsym
```

最终发布时保留 `.xcarchive` 最稳妥，因为它同时保存 App 与 dSYM。符号化前先比较 UUID：

```shell
dwarfdump --uuid path/to/AppBinary
dwarfdump --uuid path/to/App.app.dSYM
```

UUID 不一致就停止。不要尝试“差不多版本”的 dSYM。

### 本实验可运行的 Native 证明

当前 Intel Mac 可以构建 `iosX64` test executable：

```shell
./gradlew :shared:iosX64Test

dwarfdump --uuid \
  shared/build/bin/iosX64/debugTest/test.kexe

dwarfdump --uuid \
  shared/build/bin/iosX64/debugTest/test.kexe.dSYM
```

门禁再从 binary 找到诊断测试函数地址，并用：

```shell
atos -arch x86_64 \
  -o shared/build/bin/iosX64/debugTest/test.kexe.dSYM/Contents/Resources/DWARF/test.kexe \
  <address>
```

恢复 Kotlin 函数名。

本轮恢复到了 `StructuredRefreshDiagnosticsTest#appliedRefreshUsesOneCorrelationIdAndMonotonicDuration`。行号为 0，说明当前 K/N dSYM 能恢复函数但该符号点没有可用源码行；不要把函数恢复夸大成完整逐行符号化。

### 当前没有完成什么

Xcode 26.5 没有安装匹配的 iOS 26.5 runtime，连 generic Simulator destination 也不可用，因此最终 SwiftUI App 的 Release archive/dSYM 没有在本轮生成。工程配置已验证，Native executable 链路已验证，但二者不能合并写成“最终 App dSYM 已通过”。

在可用 Apple CI 上补做：

```text
xcodebuild archive
验证 archive 内 App/dSYM UUID
保存或上传匹配 symbols
制造受控 crash
下载 crash report 并完成 symbolication
```

## 第十步：知道何时离开日志

结构化事件能回答“这次刷新是什么终态、多久结束”，但以下问题要换工具：

| 问题 | Android | Apple |
|---|---|---|
| 主线程为何卡住 | Perfetto / system trace | Instruments Time Profiler |
| 某个区间跨线程多久 | trace section | OSLog signpost / Points of Interest |
| 真实用户 ANR/hang | Android vitals / ANR traces | MetricKit hang diagnostics |
| 崩溃聚合 | Play Console 或 crash backend | Organizer / MetricKit / crash backend |
| 版本分布是否退化 | metrics backend | MetricKit + metrics backend |

不要把每一行日志升级成 metric，也不要用一条 628 ms 事件宣布性能优化成功。

## 第十一步：运行完整可观测性门禁

```shell
cd ../02-first-kmp-module
./scripts/verify-observability.sh
```

它会执行：

```text
domain/shared JVM tests
shared iOS x64 tests
Kotlin/Native Debug framework
minified Android Release
R8 mapping/source-line checks
privacy allowlist static checks
Native binary/dSYM UUID check
atos Kotlin symbol restoration
Xcode Release dSYM config check
Apple header denylist
```

成功输出同时给出：

```text
Android version/build
R8 version + pg_map_id
APK SHA-256
mapping SHA-256
Kotlin/Native UUID
```

这些值是发布证据，不应该硬编码进源码。实验记录保存本轮样本，下一次构建应产生自己的身份。

## 一套故障演练 Runbook

收到“刷新后 App 崩了”时，按顺序处理：

1. 确认平台、App version/build、发生时间和可复现条件；
2. 找到 crash/ANR/hang 原始报告，不先猜业务原因；
3. Android 匹配对应 mapping，Apple 匹配 binary/dSYM UUID；
4. 完成符号化，确认首个属于本项目的 stack frame；
5. 用 operation ID 关联 started/finished 事件；
6. 判断它属于 expected failure、cancellation、unexpected exception、crash、ANR/hang 还是慢；
7. 若是慢，进入 Perfetto/Instruments，而不是继续加日志；
8. 若需要数据，先检查字段隐私与保留策略；
9. 写出可复现测试，再修复；
10. 用同类 Release 产物和符号链回归。

不要先做：

- 把所有异常转成 network error；
- 要求用户发送完整数据库或无限量日志；
- 用别的版本 mapping/dSYM 强行符号化；
- 因为 Debug 正常就关闭问题；
- 根据一次 duration 调整性能阈值。

## 验收清单

- [ ] 能解释四种刷新终态及其控制流。
- [ ] 能指出 cancellation catch 为什么必须排在普通异常之前。
- [ ] 能从 schema 中找出 correlation ID 和 monotonic duration。
- [ ] 能说明固定 record 为什么比任意 map 更易审计。
- [ ] 能证明异常 message、标题、URL 与数据库路径没有进入事件。
- [ ] 能构建 minified Android Release 并找到 mapping。
- [ ] 能解释 R8 metadata warning 与 async parsing warning 的差别。
- [ ] 能生成 APK/mapping SHA-256 和 `pg_map_id`。
- [ ] 能比较 Native binary/dSYM UUID。
- [ ] 能用 `atos` 恢复一个 Kotlin 函数名。
- [ ] 能说清当前最终 iOS App dSYM 仍缺哪一段验证。
- [ ] 能为日志、crash、trace、hang 和 metrics 选择正确工具。
- [ ] `verify-observability.sh` 通过。

## 进一步挑战

### 挑战一：增加数据库事务区间

为 SQLDelight merge 增加平台 trace/signpost，不改变领域事件 schema。比较“刷新总耗时”和“事务区间”的关系。

### 挑战二：建立受控 retrace fixture

添加一个不会进入生产路径的 Release-only fixture，保存混淆堆栈样本，让 CI 使用对应 mapping 自动 retrace 并检查源码方法名。

### 挑战三：接入 Apple Logger

把 iOS writer 从 `NSLog` 替换成 unified logging，固定 subsystem/category，并明确每个动态字段的 privacy。共同层测试和 Apple API surface 不应改变。

### 挑战四：设计远端观测策略

写一份不绑定厂商的决策草案，至少包含：采样、离线队列、同意、敏感字段、保留期、访问控制、删除请求、成本和 SDK 故障隔离。

### 挑战五：建立性能分位数

在同设备等级、同网络条件下收集足够样本，按版本比较 p50/p95/p99；先定义基线和噪声，再讨论告警阈值。

## 权威资料

- [Kotlin 时间测量与 TimeSource.Monotonic](https://kotlinlang.org/docs/time-measurement.html)
- [Kotlin/Native 调试与 dSYM](https://kotlinlang.org/docs/native-debugging.html)
- [Kotlin/Native 与 Swift/Objective-C 互操作](https://kotlinlang.org/docs/native-objc-interop.html)
- [Kotlin 协程调试](https://kotlinlang.org/docs/coroutines-debugging.html)
- [Android Retrace](https://developer.android.com/tools/retrace)
- [Android app optimization troubleshooting](https://developer.android.com/topic/performance/app-optimization/troubleshoot-the-optimization)
- [Kotlin 与 R8/D8 兼容版本](https://developer.android.com/build/kotlin-support)
- [Android 日志信息泄露风险](https://developer.android.com/privacy-and-security/risks/log-info-disclosure)
- [Android system tracing 与 Perfetto](https://developer.android.com/topic/performance/tracing)
- [Android ANR 诊断](https://developer.android.com/topic/performance/anrs/diagnose-and-fix-anrs)
- [Apple 构建调试信息](https://developer.apple.com/documentation/xcode/building-your-app-to-include-debugging-information)
- [Apple crash report 字段](https://developer.apple.com/documentation/xcode/examining-the-fields-in-a-crash-report)
- [Apple 查找缺失 dSYM](https://developer.apple.com/documentation/xcode/locating-a-missing-debug-symbol-file)
- [Apple unified logging](https://developer.apple.com/documentation/os/generating-log-messages-from-your-code)
- [Apple performance data 与 signpost](https://developer.apple.com/documentation/os/recording-performance-data)
- [Apple MetricKit](https://developer.apple.com/documentation/metrickit)
