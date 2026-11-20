# 实验 02：让同一组规则接受 JVM、Android 与 iOS 检验

这个实验把三个容易混淆的概念变成可以观察的构建结果：

- **target**：要为哪种平台生成代码，例如 JVM、Android、iOS x64 Simulator。
- **compilation**：某个 target 的一次编译用途，例如 `main`、`test`、Android 的 `hostTest`。
- **source set**：交给 compilation 的一组源码和依赖。它是代码组织与依赖关系，不是最终二进制。

## 工程中有什么

`StudyStreakPolicy` 现在位于 `domain/commonMain`，不依赖平台 API。它的测试位于 `domain/commonTest`，会被不同目标的测试 compilation 复用。实验 14 已把原来的单一 shared 工程拆为 `domain + shared`；本页前半仍保留实验 02 当时的最小心智模型与基线。

`platformName()` 在 `commonMain` 中只有 `expect` 声明，在三个平台 source set 中分别提供 `actual`：

```text
commonMain
├── jvmMain                 → JVM 字节码
├── androidMain             → Android AAR 中的 JVM 字节码
└── iosMain                 → 共同 Apple 原生实现
    ├── iosX64              → Intel iOS Simulator
    ├── iosSimulatorArm64   → Apple Silicon iOS Simulator
    └── iosArm64            → iPhone / iPad device
```

当前工程的模块依赖为：

```text
domain: rules + models + ports + use cases + state machines
  ↓ api dependency for Kotlin consumers; linked but not exported to Apple
shared: Ktor + SQLDelight + platform adapters + Apple facade
  ├→ androidApp
  └→ PocketLabShared framework → iosApp
```

完整拆分过程与 `api` / framework `export` 的区别见[实验 14](../14-kmp-modularization/README.md)。

## 运行

在本目录执行：

```shell
./gradlew :domain:jvmTest :shared:jvmTest
./gradlew :domain:testAndroidHostTest :shared:testAndroidHostTest
./gradlew :domain:iosX64Test :shared:iosX64Test
```

一次运行并汇总全部目标：

```shell
./gradlew :domain:allTests :shared:allTests
```

验证模块依赖和 Apple 导出边界：

```shell
./scripts/verify-module-boundaries.sh
```

验证 Gradle 缓存与 Kotlin 编译报告能力：

```shell
./scripts/verify-build-performance.sh
```

验证固定诊断 schema、Android R8 mapping 与 Native dSYM：

```shell
./scripts/verify-observability.sh
```

门禁会输出当前 Android APK/mapping SHA-256、`pg_map_id` 与 Native UUID。完整故障分类、隐私边界和符号化实验见[实验 16](../16-observability/README.md)。

验证 Android/Swift 宿主契约、KMP adapter 与单一写入权威：

```shell
./scripts/verify-incremental-adoption.sh
```

门禁会用 Android legacy fixture 与 Swift compile-only fixture 证明同一 ViewModel 可切换实现，同时拒绝 KMP 类型扩散到宿主 UI 和 production dual write。完整迁移方法见[实验 17](../17-incremental-adoption/README.md)。

按场景测量配置、开发任务、Native no-op 与 clean/cache restore：

```shell
./scripts/benchmark-build.sh config-only 5
./scripts/benchmark-build.sh dev-noop 5
./scripts/benchmark-build.sh native-noop 3
./scripts/benchmark-build.sh dev-clean-cache 1
```

结果写入 ignored `build/benchmarks`。完整方法、实测数据与解释见[实验 15](../15-build-performance/README.md)。

任务名以实际 Gradle 输出为准。可以用下面的命令观察全部测试任务：

```shell
./gradlew :domain:tasks --group verification
./gradlew :shared:tasks --group verification
```

## 观察点

1. 删除任一平台的 `actual`，重新编译对应 target，记录编译器错误。
2. 把 `android.os.Build` 移进 `commonMain`，观察为什么无法解析。
3. 修改 `StudyStreakPolicy` 的规则，只改一处，再运行三个目标的测试。
4. 对比 `shared/build/classes` 与 `shared/build/bin`，寻找 JVM 与 Native 的不同产物。

## 版本选择

- Kotlin 2.4.21（当前稳定版）
- Android Gradle Plugin 8.13.2
- Gradle 8.14.4
- Android compileSdk 35 / minSdk 24

这些组合同时满足 Kotlin、AGP 与 Gradle 三方兼容要求。实验刻意使用 Google 新的 Android-KMP library plugin；旧教程里的 `com.android.library + androidTarget()` 正在迁移，不应成为新工程的默认示范。

工程当前同时声明 `iosX64()`、`iosSimulatorArm64()` 与 `iosArm64()`，共同实现位于 `iosMain` / `iosTest`。本机是 x86_64，所以只运行 `iosX64Test`；它仍能交叉编译另外两个 Release frameworks，并组装 XCFramework。Apple Silicon CI 需要实际运行 `iosSimulatorArm64Test`。这是 host 执行能力与 target 产物能力的区别，不是命名偏好。

## 实验 02 基线结果（2026-11-18）

| 测试目标 | 共享规则测试 | 平台实现测试 | 结果 |
|---|---:|---:|---|
| JVM | 4 | 1 | 5/5 通过 |
| Android Host | 4 | 1 | 5/5 通过 |
| iOS x64 Simulator | 4 | 1 | 5/5 通过 |

这是实验 02 完成时的基线。该工程随后被实验 03 继续使用，新增了 PocketLab 的 6 条共享测试，当时达到每个目标 11 条、合计 33 条。后续实验仍会累积增长；保留基线数字，是为了展示 `commonTest` 如何随功能增长被每个目标重复消费。

最终验证命令：

```shell
./gradlew :domain:allTests :shared:allTests
```

输出为 `BUILD SUCCESSFUL`。机器上的全局 Gradle 缓存不在工作区，自动验证时使用了显式的 `--gradle-user-home`；普通本地开发直接使用上面的命令即可。

iOS 第一次运行有两个容易误判的阶段：先下载 Kotlin/Native 的 LLVM 等依赖，然后冷启动 Simulator。此次测试进程在 `dyld` 装载阶段等待约 3 分钟，但测试代码本身仅执行约 14 ms。报告位于：

- `domain/build/reports/tests/{jvmTest,testAndroidHostTest,iosX64Test}/index.html`
- `shared/build/reports/tests/{jvmTest,testAndroidHostTest,iosX64Test}/index.html`

当前 Kotlin 2.4.21 会提示 macOS x64 Native host 已弃用。这不影响本次结果，但意味着后续长期开发应迁移到 Apple Silicon；不要用隐藏警告的配置假装风险不存在。

## 本次真实踩坑

1. Kotlin 兼容表显示 Kotlin 2.4.21 可配合 AGP 9.3.1，也可配合 Gradle 8.13，但这不代表 **AGP 9.3.1 与 Gradle 8.13** 彼此兼容。AGP 9.3 至少要求 Gradle 9.5。最终选择 AGP 8.13.2 + Gradle 8.14.4。
2. 最初按 Apple Silicon 模板声明了 `iosSimulatorArm64()`，Gradle 明确禁用了它，因为当前 host 是 x86_64。改成 `iosX64()` 后才得到可运行目标。
3. iOS 冷启动期间测试二进制停在 `dyld4::prepareSim`，不能据此判断业务测试挂死。最终 XML 证明 5 条用例全部执行成功。

这三个问题分别属于版本兼容、目标架构和运行环境。诊断 KMP 构建时，先判断失败发生在哪一层，比反复修改业务代码更重要。
