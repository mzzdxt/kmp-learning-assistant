# 实验 10：为 KMP 建立能定位风险的测试金字塔

## 实验目标

完成本实验后，你应该能用代码与测试报告回答：

1. `commonTest` 是“只测试一次”，还是在每个 target 重新执行？
2. 单元测试、共同层集成测试、平台测试与 UI 测试分别证明什么？
3. 为什么测试场景数与执行数必须分开报告？
4. 什么依赖应该 fake，什么接缝应该保留真实实现？
5. Compose semantics 与 XCTest UI 如何避免固定 sleep？
6. Android 与 iOS 各自拥有的风险由哪一层兜底？

## 前置条件

- 已完成[实验 09](../09-native-ui/README.md)。
- JDK 17、Android SDK、一个 API 34 Android emulator 与 Xcode 可用。
- 当前实验机为 Intel Mac，因此 Kotlin/Native 使用 `iosX64`；Apple Silicon 应改为 `iosSimulatorArm64`。
- 主工程位于 `../02-first-kmp-module`。

## 本次测试地图

```text
少量  iOS App UI smoke ───── SwiftUI → KMP → SQLDelight
      Android Compose UI ─── state render + intent
      平台 ViewModel/interop ─ lifecycle + cancellation
      共同层集成 ─────────── remote → use case → DB → Flow
大量  共同层单元 ─────────── rules + state machines

        JVM            Android Host / Device            iOS
```

## 第一步：运行共同层跨组件场景

新增测试：

```text
shared/src/commonTest/kotlin/dev/kmpjourney/pocketlab/integration/
  PocketLabRefreshScenarioTest.kt
```

它使用：

- 确定性的 `DemoExperimentRemoteDataSource(latencyMilliseconds = 0)`；
- 真实 `RefreshExperimentsUseCase`；
- 真实 `SqlDelightExperimentRepository`；
- 每个 target 对应的 test SQL driver；
- 真实数据库 Flow 与 transaction。

运行：

```shell
cd ../02-first-kmp-module
./gradlew \
  :shared:jvmTest \
  :shared:testAndroidHostTest \
  :shared:iosX64Test
```

预期：

| target | 测试执行 |
|---|---:|
| JVM | 38/38 |
| Android Host | 38/38 |
| iOS x64 | 43/43 |

检查三个报告目录，你会在每一个目录看到同名集成测试 XML：

```text
shared/build/test-results/jvmTest/
shared/build/test-results/testAndroidHostTest/
shared/build/test-results/iosX64Test/
```

这就是“一次编写，多目标执行”的直接证据。

## 第二步：读懂集成测试的证明边界

该测试验证：

1. 初始 Flow 发出空列表；
2. 第一次刷新收到并写入 3 条记录；
3. transaction 只发布完整的 3 条快照；
4. 所有记录都是 `SYNCED`；
5. 第二次刷新 `changedCount == 0`。

它没有访问公网，因此不证明 TLS、DNS、鉴权或生产服务端契约。不要把 deterministic remote 叫做“真实网络集成测试”。

尝试把 Repository 换成 fake，再列出哪些风险会消失：SQL mapping、transaction、query ordering、Flow invalidation 和平台 driver 都不再被验证。

## 第三步：编译并运行 Android Compose UI 测试

Android 测试依赖包括：

```kotlin
androidTestImplementation(platform("androidx.compose:compose-bom:2026.06.01"))
androidTestImplementation("androidx.compose.ui:ui-test-junit4")
androidTestImplementation("androidx.test.ext:junit:1.3.0")
debugImplementation("androidx.compose.ui:ui-test-manifest")
```

测试文件：

```text
androidApp/src/androidTest/kotlin/
  dev/kmpjourney/pocketlab/android/ui/ExperimentListScreenTest.kt
```

先编译测试 APK：

```shell
./gradlew :androidApp:assembleDebugAndroidTest
```

确认 emulator：

```shell
adb devices
```

运行：

```shell
./gradlew :androidApp:connectedDebugAndroidTest
```

预期在 API 34 device 上得到 2/2：

- 已同步记录与刷新 banner 可见；
- 点击“刷新”只调用一次传入 callback。

测试直接向 Composable 注入 UI state，不启动 Ktor 或 SQLDelight。它验证的是 UI 自己拥有的渲染与 intent 转发责任。

结果报告：

```text
androidApp/build/reports/androidTests/connected/debug/
androidApp/build/outputs/androidTest-results/connected/debug/
```

## 第四步：理解 Compose semantics

Compose 测试不是在屏幕像素里 OCR 文本。`onNodeWithText` 查询的是 semantics tree。

试做：

1. 给刷新按钮只保留图标，移除可访问 label；
2. 重新运行测试；
3. 观察测试和无障碍语义如何一起退化；
4. 恢复有意义的 content description 或文字。

UI 可测试性与无障碍通常使用同一套语义基础设施，这不是巧合。

## 第五步：生成包含 UI Test target 的 Xcode 工程

生成器现在会创建：

```text
PocketLabIos
PocketLabIosUITests
```

工程已提交，无需日常重复生成。只有在确认 `.xcodeproj` 中没有手工修改时，才先移走旧工程再运行：

```shell
ruby iosApp/generate_xcode_project.rb
```

检查 scheme：

```shell
xcodebuild \
  -project iosApp/PocketLabIos.xcodeproj \
  -scheme PocketLabIos \
  -list
```

## 第六步：运行 iOS 端到端 UI 测试

测试文件：

```text
iosApp/PocketLabIosUITests/PocketLabIosUITests.swift
```

先查看可用设备：

```shell
xcrun simctl list devices available
```

选择一个 device id 后运行：

```shell
GRADLE_USER_HOME="$PWD/../../.gradle-user-home" \
xcodebuild test \
  -project iosApp/PocketLabIos.xcodeproj \
  -scheme PocketLabIos \
  -destination 'platform=iOS Simulator,id=<device-id>' \
  -derivedDataPath build/xcode-derived-data \
  CODE_SIGNING_ALLOWED=NO
```

本次在 iPhone 16 Pro / iOS 18.4 模拟器执行 1/1，通过约 12 秒。

它验证：

- App 冷启动；
- SwiftUI navigation 与 3 条数据库记录出现；
- 首次提示更新 3 条；
- 点击刷新；
- 第二次提示“已是最新状态”。

测试通过 launch environment 注入唯一数据库名：

```swift
app.launchEnvironment["POCKETLAB_DATABASE_NAME"] =
    "pocketlab-ui-\(UUID().uuidString).db"
```

删除这段隔离后连续运行两次，观察旧数据如何让首次 `changedCount` 变成 0。这是典型的测试顺序依赖。

## 第七步：分别运行 Android ViewModel 与完整矩阵

Android ViewModel：

```shell
./gradlew :androidApp:testDebugUnitTest
```

完整 Kotlin / Android 矩阵：

```shell
./gradlew \
  :shared:jvmTest \
  :shared:testAndroidHostTest \
  :shared:iosX64Test \
  :shared:verifySqlDelightMigration \
  :androidApp:testDebugUnitTest \
  :androidApp:connectedDebugAndroidTest
```

再单独执行上一节的 `xcodebuild test`。

本次基线（2026-11-19）：

| 来源 | 不同场景 | 执行 |
|---|---:|---:|
| commonTest | 37 | 111 |
| JVM / Android Host / iOS 平台 KMP | 8 | 8 |
| Android ViewModel | 2 | 2 |
| Android Compose UI | 2 | 2 |
| iOS UI | 1 | 1 |
| **总计** | **50** | **124/124** |

## 第八步：练习失败定位

依次制造四个小故障，每次先猜哪一层会红：

1. 把 demo 数据第一条 ID 改掉：共同集成测试应在三个 targets 失败；
2. 把 Android `R.string.synced` 改成其他文案：Compose UI 测试失败，共同测试仍绿；
3. 删掉 iOS toolbar 的刷新按钮：XCTest UI 失败，Kotlin 测试仍绿；
4. 让 iOS App 忽略 `POCKETLAB_DATABASE_NAME`：单次可能通过，重复执行开始暴露顺序依赖。

完成后恢复代码。这个练习比单纯看 124 个绿点更重要，因为它建立“失败 → 所有者 → 诊断入口”的映射。

## 本次真实踩坑

### `kotlin-test` 不会自动出现在 Android instrumentation classpath

Android UI 测试最初复用了 `kotlin.test.assertEquals`，但 `androidTest` 只显式引入 Compose 与 AndroidX Test，编译失败。最终使用已经存在的 JUnit `Assert.assertEquals`，没有为一个断言额外扩大依赖。

### Compose 旧版 test rule API 已弃用

当前 Compose 版本提示 `androidx.compose.ui.test.junit4.createComposeRule` 已弃用，因此测试使用 `androidx.compose.ui.test.junit4.v2.createComposeRule`。不要无视编译期迁移提示，把刚新增的测试建立在已知旧入口上。

### Xcode destination 列表可能晚于 `simctl`

本机 `xcodebuild -showdestinations` 一度只显示 generic destination，但 `simctl` 能列出 iOS 17.5–18.4 设备。显式 boot iOS 18.4 设备并传入 id 后，`xcodebuild test` 成功。

这是本机 Xcode / CoreSimulator 状态，不应变成业务代码 workaround。CI 应固定 Xcode 与匹配 runtime，并把 destination discovery 作为环境健康检查。

## 挑战题

1. 给共同集成测试加入 remote failure，证明旧快照完全不变。
2. 为 Compose 空状态的主按钮增加测试，处理页面上可能同时存在两个“刷新”节点的问题。
3. 在 iOS UI 测试失败时添加 screenshot attachment。
4. 设计一条 staging contract test，但让它不阻塞普通离线开发。
5. 为 4 个 CI 车道标注平均耗时、触发条件和失败所有者。

## 验收标准

只有当你能解释下面这句话，才算完成：

> PocketLab 的共同规则只写一套测试，但会在 JVM、Android Host 与 iOS Native 分别执行；平台 ViewModel、Compose semantics、Swift bridge 和 SwiftUI 端到端旅程由各自平台测试补齐。50 个场景形成 124 次执行，两组数字证明的事情不同。

对应决策记录：[ADR-0008](../../docs/adr/0008-testing-strategy.md)

对应文章：[KMP 测试金字塔：一次验证，两端真的能放心吗？](../../articles/10-testing-pyramid.md)
