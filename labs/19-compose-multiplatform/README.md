# 实验 19：先共享一张卡片，再决定要不要共享整个 UI

## 实验目标

这一轮不创建一个独立 Compose Multiplatform Hello World，也不把 PocketLab 现有 SwiftUI 页面直接重写。

我们要回答一个更窄、也更有工程价值的问题：

> 在不改变现有 business/data ownership 的前提下，能否把一个高重复、低平台耦合的叶子组件做成可被 Android 与 iOS 消费的独立 shared UI 产物？

完成后，你应该能解释：

- KMP 与 Compose Multiplatform 为什么不是同一个选择；
- 共享组件、页面、导航和整 App 为什么是四种决策；
- 为什么 `sharedLogic` 与 `sharedUi` 应该分开；
- 当前版本、AGP/API baseline 与 Apple target 如何共同约束方案；
- 交叉编译为什么不能冒充 iOS runtime 测试；
- Apple header 为什么仍需治理；
- 如何用真实 spike 决定扩大、维持或停止 shared UI。

## 实验范围

候选组件是 `ExperimentSummaryCard`：

```text
title + hypothesis + sync status + open intent
```

它可以拥有：

- presentation model；
- 布局、颜色和字体；
- 可见状态文案；
- accessibility state description；
- button role；
- `onOpen` callback。

它不能拥有：

- domain record；
- repository 或 use case；
- Ktor / SQLDelight；
- navigation stack；
- lifecycle / ViewModel；
- coroutine scope；
- production implementation selection。

## 当前架构

```text
production:

domain → shared → Android Compose / iOS SwiftUI

optional spike:

sharedUi
├── commonMain: ExperimentSummaryCard
├── commonTest: status wire contract
├── Android: AAR
└── iOS: PocketLabSharedUi.framework → UIViewController factory
```

`domain`、`shared` 和 `androidApp` 都不依赖 `sharedUi`。因此实验失败或删除时，不需要回滚产品数据或生产 UI。

## 文件结构

```text
labs/19-compose-multiplatform/
├── README.md
└── results/
    └── 2026-11-20-shared-ui-spike.md

labs/02-first-kmp-module/
├── sharedUi/
│   ├── build.gradle.kts
│   └── src/
│       ├── commonMain/.../ExperimentSummaryCard.kt
│       ├── commonTest/.../ExperimentSummaryStatusTest.kt
│       └── iosMain/.../PocketLabSharedUi.kt
└── scripts/
    ├── compose-shared-ui-smoke.swift
    └── verify-compose-shared-ui.sh
```

## 第一步：固定要验证的工程基线

本次不把 shared UI spike 与 Android 构建链升级合并：

```text
Kotlin 2.4.21
AGP 8.13.2
Gradle 8.14.4
compileSdk 36
minSdk 24
Intel macOS host
```

这样，一旦失败，可以区分：

- Compose UI scope 问题；
- dependency variant 问题；
- Android build-chain 问题；
- Apple architecture/host 问题；
- 资源峰值问题。

## 第二步：建立独立 `sharedUi` module

root plugin：

```kotlin
id("org.jetbrains.compose") version "1.11.1" apply false
```

module plugin：

```kotlin
plugins {
    kotlin("multiplatform")
    id("com.android.kotlin.multiplatform.library")
    id("org.jetbrains.compose")
    kotlin("plugin.compose")
}
```

Apple targets 只有：

```kotlin
iosArm64()
iosSimulatorArm64()
```

没有 `iosX64()`。Compose Multiplatform 从 1.11.1 起已经移除 Apple x86_64 artifacts；在当前 Intel host 上声明它只会制造无法解析的 variant。

## 第三步：不把业务依赖拖进 UI

`sharedUi` 只依赖：

```kotlin
implementation("org.jetbrains.compose.runtime:runtime:1.11.1")
implementation("org.jetbrains.compose.foundation:foundation:1.11.1")
implementation("org.jetbrains.compose.ui:ui:1.11.1")
```

本轮不加入 Material 3，也不依赖 `:domain` 或 `:shared`。

原因不是 Material 3 永远不能用，而是 Compose Multiplatform 1.11.x 对应的 Material 3 artifact 仍是 alpha；把它同时加入会让 spike 混入第二个稳定性变量。

## 第四步：建立最小 Apple API

共同 Composable 对 Android/Kotlin 可见；iOS 只需要一个 factory：

```kotlin
object PocketLabSharedUi {
    fun experimentSummaryViewController(
        title: String,
        hypothesis: String,
        status: String,
        onOpen: () -> Unit,
    ): UIViewController
}
```

`ExperimentSummaryUiModel` 与 `ExperimentSummaryStatus` 使用 `@HiddenFromObjC`，避免内部 presentation types 进入 framework ABI。

Swift smoke 在 `@MainActor` 下创建 view controller，并启用：

```text
-swift-version 6
-strict-concurrency=complete
-warnings-as-errors
```

## 第五步：运行门禁

```shell
cd labs/02-first-kmp-module
./scripts/verify-compose-shared-ui.sh
```

门禁执行：

1. 检查 Compose Multiplatform 1.11.1 与 compileSdk 36 baseline；
2. 拒绝 `iosX64` 和本轮未批准的 Material 3；
3. 运行 Android host tests；
4. 编译 arm64 Simulator test executable；
5. 生成 Android AAR；
6. 生成 iOS device 与 Simulator arm64 Release frameworks；
7. 用 Swift 6 strict concurrency type-check device consumer；
8. 扫描 Apple header 的 factory allowlist 与 model denylist；
9. 禁止 common UI 导入平台、network、database 和业务实现；
10. 禁止 production modules 反向依赖 `sharedUi`；
11. 输出 artifact bytes、SHA-256 和 header line count。

Native 链接使用 `--max-workers=1`。这是一次实测后的约束：三个链接任务并行时，当前 2 GiB daemon 会发生 OOM；串行可以在相同 heap 完成，但 wall time 更长。

## 三次尝试

### 1. Compose Multiplatform 1.12.2 + `iosX64`

结果：依赖解析失败。

原因：1.11.1 起不再发布 Apple x86_64 Compose artifacts。

### 2. Compose Multiplatform 1.12.2 + arm64 Apple targets

结果：Android Debug/test 与 iOS device Debug Framework 成功，Swift smoke 成功；Android Release AAR metadata gate 失败。

原因：1.12.2 映射的 Jetpack Compose 1.12.1 要求 compileSdk 37 / AGP 9.1，超过当前 compileSdk 36 / AGP 8.13.2 baseline。

### 3. Compose Multiplatform 1.11.1 + foundation-only

结果：最终门禁通过。

这不表示 1.11.1 是永久生产版本，只证明共享 UI 可以在当前 baseline 被隔离验证。正式采用必须同时批准构建链升级与 UI dependency 升级计划。

## 最终输出

```text
Compose shared UI contract passed.
Compose Multiplatform: 1.11.1 on AGP 8.13.2 / compileSdk 36
Android host contract tests: 2/2
Android AAR: 16460 bytes
iOS device Release framework binary: 103941976 bytes
iOS arm64 Simulator framework: cross-compiled
Swift 6 strict-concurrency consumer: passed
Apple header lines: 157; presentation-model leaks: 0
```

完整哈希和过程见[实验结果](results/2026-11-20-shared-ui-spike.md)。

## 证据覆盖与缺口

| 问题 | 本轮证据 |
|---|---|
| common UI 能否为 Android 编译/测试 | 是，2/2 host tests |
| 能否生成 Android AAR | 是 |
| 能否生成 iPhone arm64 Release framework | 是 |
| 能否生成 Apple Silicon Simulator framework/test binary | 是，交叉编译 |
| 能否从 Swift 6 调用 factory | 是，strict type-check |
| 能否在 Intel host 运行 arm64 Simulator | 否 |
| 真实 iPhone rendering | 未验证 |
| VoiceOver / Dynamic Type | 未验证 |
| 输入、焦点、navigation | 不在本轮 scope |
| 最终 App 包体增量 | 未验证 |
| 真实设备 frame / memory | 未验证 |

`103,941,976 bytes` 是 static framework binary，不是最终 App 下载增量。没有 before/after Release App，就不能据此给包体下结论。

## 当前决策

```text
native UI remains default
sharedUi remains optional
one leaf component may continue to the next spike
full screen/navigation migration is not approved
```

生产接入前还需要：

- Apple Silicon CI 运行 `iosSimulatorArm64Test`；
- iPhone 上实际渲染；
- VoiceOver 与 Dynamic Type；
- Android/iOS screenshot 对照；
- Release App before/after size、startup、frame 与 memory；
- Compose/AGP/API 当前版本升级方案；
- iOS owner 对容器、诊断和回滚的共同签字。

## 本实验没有证明什么

- 没有证明 Compose Multiplatform 比 SwiftUI 更好；
- 没有证明所有页面都应该共享；
- 没有证明 framework 大小等于 App 增量；
- 没有证明交叉编译等于 Simulator runtime 通过；
- 没有证明 1.11.1 应长期锁定；
- 没有证明增加 Gradle heap 是唯一性能优化；
- 没有改变 PocketLab 0.17.0/build 17 的 production runtime。

## 延伸挑战

为同一张卡片补齐下一层证据，但一次只选一项：

1. 在 Apple Silicon CI 运行 arm64 Simulator test；
2. 加入 screenshot baseline；
3. 在真实 iPhone 验证 VoiceOver 和 Dynamic Type；
4. 把 shared card 通过宿主容器接入一个 non-production screen；
5. 建同一 Release App 的包体与 frame before/after；
6. 尝试升级到 Compose 1.12.x + AGP 9.1/API 37，并记录迁移扩散面。

每次挑战都要同时写回滚条件。不要把多个未知数合成一次“大迁移”。

## 权威资料

- [Compose Multiplatform compatibility and versions](https://kotlinlang.org/docs/multiplatform/compose-compatibility-and-versioning.html)
- [What's new in Compose Multiplatform 1.11.1](https://kotlinlang.org/docs/multiplatform/whats-new-compose-111.html)
- [Recommended Kotlin Multiplatform project structure](https://kotlinlang.org/docs/multiplatform/multiplatform-project-recommended-structure.html)
- [Native application entry points](https://kotlinlang.org/docs/multiplatform/compose-multiplatform-entry-points.html)
- [Integration with UIKit](https://kotlinlang.org/docs/multiplatform/compose-uikit-integration.html)
- [Integration with SwiftUI](https://kotlinlang.org/docs/multiplatform/compose-swiftui-integration.html)
