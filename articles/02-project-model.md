# 一张图看懂 KMP 工程：target、compilation 和 source set 到底是什么？

> 面向读者：熟悉 Android Gradle，但第一次打开 KMP 工程的开发者  
> 技术核对与实机验证日期：2026-11-18

第一次打开 KMP 模板工程，很容易产生一种“看懂了目录，却没看懂工程”的感觉：

```text
commonMain
androidMain
iosMain
```

名字都认识，问题却接踵而来：

- `commonMain` 最终会被打成什么包？
- `iosMain` 是不是一个 iOS target？
- 为什么还有 `iosX64Main`、`iosSimulatorArm64Main`？
- `commonTest` 到底运行一次还是运行很多次？
- Gradle 里的 target、compilation、source set 是包含关系，还是三种不同叫法？

这些问题的根源，是我们习惯拿 Android 单平台工程的模型去套多平台工程。

这一篇不背目录名。我们用一个真的跑过 JVM、Android 与 iOS 测试的最小工程，把 KMP 的构建模型拆开。

## 先记住这条链

```text
Project
  └── Target（去哪里）
        └── Compilation（为了什么而编译）
              └── consumes Source Sets（使用哪些源码与依赖）
                    └── produces classes / klib / binary（产生平台产物）
```

如果只记一句话：

> target 决定目的地，compilation 决定这次编译的用途，source set 决定这次编译能看到哪些代码和依赖。

下面逐个拆开。

## Target：不是“平台标签”，而是具体编译目的地

在实验工程里，我们声明了三个 target：

```kotlin
kotlin {
    jvm()
    android { /* ... */ }
    iosX64()
}
```

它们分别告诉 Kotlin Gradle Plugin：

- 为普通 JVM 生成字节码。
- 为 Android 生成可进入 AAR 的 JVM 字节码，并接入 Android SDK。
- 为 x86_64 iOS Simulator 生成 Native 代码。

这里最容易忽略的是体系结构。

`iosX64()` 和 `iosSimulatorArm64()` 都面向 iOS Simulator，却不是同一个 target：前者服务 Intel/x86_64 host，后者服务 Apple Silicon/arm64 host。我们的机器是 x86_64，最初照着新款 Mac 模板写 `iosSimulatorArm64()`，Gradle 直接给出 architecture mismatch 并禁用测试任务。

所以 target 不是一句模糊的“我要支持 iOS”。它至少隐含了：

```text
操作系统 + 执行环境 + CPU/ABI + 编译后端
```

这也是为什么 CI 机器架构会影响 KMP 构建，而不仅影响构建速度。

## Compilation：同一个目的地，也有不同出发理由

每个 target 通常不只编译一次。

JVM target 默认有：

```text
jvm/main
jvm/test
```

新的 Android-KMP library target 在我们的配置里有：

```text
android/main
android/hostTest
```

iOS x64 target 有：

```text
iosX64/main
iosX64/test
```

这些就是 compilation。`main` 为生产代码服务，`test` 或 `hostTest` 为测试代码服务。不同 compilation 可以拥有不同的编译参数、依赖、入口和产物。

这解释了为什么“测试 target”这个说法不够准确：通常 target 还是 JVM、Android 或 iOS；测试是这个 target 下的一次 compilation 和 test run。

## Source set：不是文件夹，而是一组可复用的源码与依赖

`commonMain`、`androidMain`、`iosX64Main` 都是 source set。

文件夹只是它们的默认物理位置：

```text
src/commonMain/kotlin
src/androidMain/kotlin
src/iosX64Main/kotlin
```

source set 的本质是一个逻辑集合，它描述：

- 有哪些 Kotlin 源码；
- 有哪些资源；
- 能使用哪些依赖；
- 依赖哪些其他 source set；
- 会被哪些 compilation 消费。

关键关系不是“目录并排”，而是依赖图。

在实验中，可以把主代码关系理解为：

```text
                 commonMain
                /     |      \
          jvmMain  androidMain  iosX64Main
             |         |           |
          JVM main  Android main  iOS main
          compilation compilation compilation
```

`commonMain` 自己不是 target，也不会独立“运行”。它被多个平台 compilation 消费，并在对应后端下分别编译。

同一份 `StudyStreakPolicy.kt` 因此会经历多次编译：

- 进入 JVM 字节码；
- 进入 Android 字节码；
- 进入 iOS Native klib，最后链接到测试可执行文件。

“共享代码只编译一次，然后到处运行”并不是 KMP 的模型。更准确的是：

> 共享同一份源码和语义约束，针对每个 target 分别编译。

## `commonTest` 为什么能测三遍？

实验把四条领域规则测试放在：

```text
src/commonTest/kotlin/.../StudyStreakPolicyTest.kt
```

然后执行：

```shell
./gradlew :shared:jvmTest
./gradlew :shared:testAndroidHostTest
./gradlew :shared:iosX64Test
```

最终报告显示：

| 目标 | 共享规则测试 | 平台测试 | 结果 |
|---|---:|---:|---|
| JVM | 4 | 1 | 5/5 通过 |
| Android Host | 4 | 1 | 5/5 通过 |
| iOS x64 Simulator | 4 | 1 | 5/5 通过 |

`commonTest` 不是在某个神秘的“公共测试虚拟机”里只运行一次。它被各平台的测试 compilation 消费，所以四条规则在三个目标里实际执行了 12 次。

这件事很重要。共享测试验证的不只是业务算法，还验证了它在不同编译后端、标准库实现和运行环境下仍然成立。

## `expect/actual` 在模型中的位置

共享代码还声明了一个很薄的平台接缝：

```kotlin
// commonMain
expect fun platformName(): String
```

三个平台 source set 分别兑现承诺：

```kotlin
// jvmMain
actual fun platformName(): String = "JVM"

// androidMain
actual fun platformName(): String =
    "Android ${android.os.Build.VERSION.SDK_INT}"

// iosX64Main
actual fun platformName(): String =
    UIDevice.currentDevice.systemName()
```

编译 JVM main 时，只把 `commonMain + jvmMain` 放进可见范围；编译 iOS main 时，则使用 `commonMain + iosX64Main`。编译器不是在运行时做 `if (platform == ...)`，而是在每个 target 的编译期匹配对应 `actual`。

这也解释了两个现象：

1. `commonMain` 不能直接引用 `android.os.Build`，因为并非所有消费它的 compilation 都拥有 Android SDK。
2. 少写一个 target 的 `actual`，对应 target 会在编译期失败，而不是等用户运行到那一行才崩溃。

## 2026 年的新变化：Android target 不要照抄旧教程

很多文章仍然这样配置 Android：

```kotlin
plugins {
    id("com.android.library")
}

kotlin {
    androidTarget()
}
```

对新工程，这已经不是首选路径。Google 提供了专门的 Android-KMP library plugin：

```kotlin
plugins {
    kotlin("multiplatform")
    id("com.android.kotlin.multiplatform.library")
}

kotlin {
    android {
        namespace = "dev.kmpjourney.shared"
        compileSdk = 35
        minSdk = 24

        withHostTestBuilder {}.configure {}
    }
}
```

它有几个值得理解的设计选择：

- 面向 KMP library，而不是把完整 Android application 插件塞进共享模块。
- 单 variant，不支持传统 build type 和 product flavor。
- Host test 与 device test 默认关闭，需要显式启用。
- Android 配置位于 KMP `kotlin {}` 中，不再有顶层 Android extension。

如果产品需要真正的 Android App，通常建立独立 Android application module，再依赖共享 KMP library。共享模块与平台入口模块是两种职责，不要为了少一个 module 把它们揉在一起。

## 一个真实版本坑：兼容不是看一张表

我们第一次尝试的版本是：

```text
Kotlin 2.4.21
AGP 9.3.1
Gradle 8.13
```

Kotlin 的兼容表分别允许 Kotlin 2.4.21 配合 AGP 9.3.1，也允许它配合 Gradle 8.13。看起来都在范围内，配置却失败了：

```text
ProjectDependency DependencyHandler.project()
```

原因是还有第三条边：**AGP 9.3 自己要求 Gradle 9.5 以上。**

最终实验固定为：

```text
Kotlin 2.4.21
AGP 8.13.2
Gradle 8.14.4
JDK 17
Xcode 26.5
```

这套组合完成了三端测试。

以后检查 KMP 工具链，至少要看这张关系图：

```text
Kotlin ↔ Gradle
Kotlin ↔ AGP
Kotlin ↔ Xcode
AGP    ↔ Gradle
Gradle ↔ JDK
```

“每个版本都很新”不是兼容性证据；“任意两项看起来支持”也不够。版本矩阵是图，不是一条线。

## 构建产物会告诉你真相

运行后查看 `shared/build`：

```text
classes/kotlin/jvm/...
classes/kotlin/iosX64/main/klib/...
bin/iosX64/debugTest/test.kexe
test-results/jvmTest/...
test-results/testAndroidHostTest/...
test-results/iosX64Test/...
```

这里能直接看到两种世界：

- JVM/Android 一侧出现 class、jar 与 Android 构建产物。
- Kotlin/Native 一侧先产生 klib，再链接出 `test.kexe` 原生测试二进制。

当概念记不牢时，不妨去看产物。构建目录通常比架构图更诚实。

## iOS 测试为什么“卡了三分钟”？

第一次运行 `iosX64Test` 时，链接已经完成，任务却几分钟没有输出。进程采样显示它停在：

```text
dyld4::prepareSim → mmap
```

这发生在 Simulator 动态装载阶段，Kotlin 测试入口尚未开始。最终测试报告显示：5 条测试全部通过，测试代码只执行约 14 ms。

这个小插曲给了我们一个比 API 更重要的诊断框架：

```text
Gradle 配置
→ 依赖解析
→ Kotlin 编译
→ Native 链接
→ Simulator / dyld 装载
→ 测试框架启动
→ 测试逻辑执行
→ 报告生成
```

“任务卡住”只是表象。先确定卡在哪一层，再决定看 Gradle、编译器、Xcode、模拟器还是业务代码。

## 到这里，三个概念应该分开了

**Target 是什么？**  
一个具体编译目的地，包含平台、执行环境与体系结构等信息。

**Compilation 是什么？**  
某个 target 为特定用途执行的一次编译，例如 main、test 或 Android hostTest。

**Source set 是什么？**  
一组可被 compilation 消费的源码、资源和依赖，以及它们之间的可见性关系。它不是 target，也不等同于磁盘文件夹。

## 动手挑战

打开实验工程，依次做四件事：

1. 运行 `./gradlew :shared:tasks --group verification`，找出 target 对应的测试任务。
2. 删除 `iosX64Main` 中的 `actual fun platformName()`，记录编译错误属于哪一层。
3. 恢复代码，把 `android.os.Build` 移到 `commonMain`，解释为什么 IDE/编译器拒绝它。
4. 修改一条 `StudyStreakPolicy` 规则，运行 `:shared:allTests`，确认同一份测试在哪些目标重新执行。

下一篇，我们讨论一个比语法更影响项目寿命的问题：**什么应该放进 `commonMain`，什么不应该？** 我们会从“最大化复用”转向“让变化原因保持一致”。

## 参考资料

- [Kotlin Multiplatform project structure](https://kotlinlang.org/docs/multiplatform/multiplatform-discover-project.html)
- [Multiplatform Gradle DSL reference](https://kotlinlang.org/docs/multiplatform/multiplatform-dsl-reference.html)
- [Configure compilations](https://kotlinlang.org/docs/multiplatform/multiplatform-configure-compilations.html)
- [Hierarchical project structure](https://kotlinlang.org/docs/multiplatform/multiplatform-hierarchy.html)
- [Set up the Android Gradle library plugin for KMP](https://developer.android.com/kotlin/multiplatform/plugin)
- [KMP compatibility guide](https://kotlinlang.org/docs/multiplatform/multiplatform-compatibility-guide.html)
- [Android Gradle plugin and Gradle compatibility](https://developer.android.com/build/releases/about-agp)

