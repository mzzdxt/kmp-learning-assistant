# Compose Multiplatform shared UI spike 结果

- 验证日期：2026-11-20
- 主机：Intel macOS x86_64
- 范围：一个 `ExperimentSummaryCard` 叶子组件
- 生产接入：否
- App 版本：仍为 0.17.0/build 17

## 工具链

```text
Kotlin 2.4.21
Compose Multiplatform 1.11.1 (final successful candidate)
Android Gradle Plugin 8.13.2
Gradle 8.14.4
compileSdk 36
minSdk 24
Swift 6 complete strict concurrency
Gradle daemon max heap 2 GiB
```

## 候选范围

`sharedUi` 拥有：

- UI-only model；
- presentation 与 accessibility semantics；
- `onOpen` intent；
- Android AAR；
- iOS `UIViewController` factory。

`sharedUi` 不依赖：

- `:domain`；
- `:shared`；
- Ktor；
- SQLDelight；
- navigation/lifecycle/ViewModel；
- production Android/iOS UI。

## 版本尝试

### Compose Multiplatform 1.12.2 + `iosX64`

结果：依赖解析失败。

runtime、foundation 与 UI 没有 `iosX64` variant。官方 1.11.1 起已移除 Apple x86_64 artifacts。

### Compose Multiplatform 1.12.2 + arm64 Apple targets

结果：

- Android compile/test 成功；
- iOS arm64 Debug framework 成功；
- Swift 6 strict consumer 成功；
- Android Release AAR metadata check 失败。

失败要求：

```text
compileSdk 37
Android Gradle Plugin 9.1
```

当前 baseline 是 compileSdk 36 / AGP 8.13.2，所以没有把 build-chain migration 混入本 spike。

### Compose Multiplatform 1.11.1 + foundation-only

结果：最终 gate 成功。

没有加入 1.11.x alpha Material 3 dependency。

## 最终 gate

命令：

```shell
cd labs/02-first-kmp-module
./scripts/verify-compose-shared-ui.sh
```

最终输出：

```text
Compose shared UI contract passed.
Compose Multiplatform: 1.11.1 on AGP 8.13.2 / compileSdk 36
Android host contract tests: 2/2
Android AAR: 16460 bytes, sha256=72925a5126339c78942591e1d2dc48c4c8776c2f3cefda466548e5d7318e1bbe
iOS device Release framework binary: 103941976 bytes, sha256=ae36fb33512b164815f8bef16ec85ba1af3446844aa854debc8a5d81662aa70a
iOS arm64 Simulator framework: cross-compiled; runtime execution depends on an Apple Silicon host
Swift 6 strict-concurrency consumer: passed
Apple header lines: 157; presentation-model leaks: 0
Scope: one leaf card; navigation/lifecycle/data/network ownership: native or shared logic, not shared UI
```

## 产物清单

| Artifact | Bytes | SHA-256 |
|---|---:|---|
| Android AAR | 16,460 | `72925a5126339c78942591e1d2dc48c4c8776c2f3cefda466548e5d7318e1bbe` |
| iOS device Release binary | 103,941,976 | `ae36fb33512b164815f8bef16ec85ba1af3446844aa854debc8a5d81662aa70a` |
| iOS Simulator arm64 Release binary | 103,781,192 | `b2be60799fd36cc01d68a64ab515caaab6a72640672d3899eb1e7cf9f8a59cff` |
| Apple header | 6,698 / 157 lines | `6fe83e5a03e5113ea7aa37c021797687a8dc88faaeb326c627712ec7885d516b` |

这些字节数只描述中间产物。static framework binary 不是最终 App 下载增量；需要相同 Release App 的 before/after 才能得出包体结论。

## 测试

Android host XML：

```text
tests=2
skipped=0
failures=0
errors=0
```

覆盖：

1. `synced` / `pending_upload` wire values 稳定；
2. 未知状态保守退化为 pending，不会错误宣称已同步。

iOS Simulator arm64 test executable 编译并链接成功，但 Gradle 明确标记 runtime test：

```text
iosSimulatorArm64Test SKIPPED
architecture mismatch: target ARM64, host X64
```

因此本报告不记录 iOS runtime test passed。

## Swift / Apple API

严格 consumer 参数：

```text
-warnings-as-errors
-swift-version 6
-strict-concurrency=complete
-target arm64-apple-ios15.0
```

结果：通过。

初版 header 为 244 行，包含 `ExperimentSummaryUiModel` 与 `ExperimentSummaryStatus`。加入 `@HiddenFromObjC` 后，最终 header 为 157 行，只保留所需 `PocketLabSharedUi` factory，presentation-model leaks 为 0。

## 构建资源证据

第一次同时运行：

```text
linkDebugTestIosSimulatorArm64
linkReleaseFrameworkIosArm64
linkReleaseFrameworkIosSimulatorArm64
```

2 GiB daemon 发生 Java heap OOM。

把 gate 固定为 `--max-workers=1` 后：

- 同一 heap 完成所有 artifacts；
- 一次部分 up-to-date run 为 10m10s；
- 随后 configuration-cache reuse + outputs up-to-date 为 2s；
- 没有为了隐藏峰值修改全局 `org.gradle.jvmargs`。

该证据支持“并行 Native linking 需要独立资源预算”，不支持“Compose 构建永远需要 10 分钟”。

## 当前判断

```text
Decision: bounded continuation
```

- 原生 Compose / SwiftUI 继续拥有 production UI；
- `sharedUi` 保持独立 optional module；
- 只允许一个 leaf component 进入下一步 non-production runtime spike；
- full screen、navigation 与 App shell 暂不进入 shared UI；
- 生产采用等待 Apple Silicon/iPhone runtime、accessibility 与 Release App before/after；
- 当前 Compose/AGP/API build-chain migration 必须单独批准。

## 未完成证据

- Apple Silicon `iosSimulatorArm64Test` runtime；
- iPhone rendering；
- VoiceOver / Dynamic Type；
- screenshot diff；
- 输入、焦点、platform view；
- Release App startup/frame/memory；
- final App binary/download delta；
- Compose 1.12.x + AGP 9.1/API 37 migration；
- production rollback exercise。
