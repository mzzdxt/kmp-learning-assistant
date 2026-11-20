# KMP 模块化：共享多少，由谁决定？

> 面向读者：已经有一个能运行、能测试、能生成 XCFramework 的 KMP 项目，开始担心 `shared` 越长越像“公共杂物间”的 Android/Kotlin 开发者
>
> 技术核对与实验验证日期：2026-11-20
>
> 实验版本：Kotlin 2.4.21、Gradle 8.14.4、AGP 8.13.2、Xcode 26.5、Intel macOS host

PocketLab 的 `shared` 曾经是一个模范模块。

业务规则放进去，Android 和 iOS 都能运行；Ktor 放进去，两端都能请求；SQLDelight 放进去，两端都能离线；Flow、并发桥、Apple facade、XCFramework 也陆续住了进来。

它每次都完成了任务。

直到有一天，我们打开目录，发现这里同时住着：

```text
草稿校验规则
保存和刷新用例
Repository 接口
Ktor client 与 DTO
SQLDelight schema 与 driver
Android/iOS/JVM 平台工厂
Swift callback bridge
Apple composition root
XCFramework 发布配置
```

所有东西都有理由“共享”。

问题也正出在这里：

> 能被多个平台共享，只说明它不是某个平台独占；并没有说明这些代码应该属于同一个模块。

`shared` 原本表达“共享”，最后却悄悄表达成“所有非 UI 代码”。

这篇文章不打算展示一张拥有十几个方框的架构图，然后宣布项目已经企业级。我们只做一次真实拆分：把 PocketLab 从一个 `shared` KMP 模块改成 `domain + shared`，再用三端测试、Android 编译、Apple header 和 XCFramework 证明边界真的存在。

更重要的是，我们要回答一个经常被“代码复用率”遮住的问题：

> KMP 项目究竟应该共享多少？这个决定应该由平台、框架、团队，还是业务来做？

## 先给答案

共享多少，不由“这段 Kotlin 能不能进 `commonMain`”决定。

它由四组事实共同决定：

1. **语义是否一致**：Android 与 iOS 对这条规则是否真的有同一种含义；
2. **变化原因是否一致**：它们会不会因为同一类需求一起修改；
3. **依赖方向是否稳定**：谁应该知道谁，谁必须永远不知道谁；
4. **消费者需要什么契约**：Kotlin App、Swift App、其他仓库和其他团队看到的 API 是否应相同。

PocketLab 最终采用：

```text
domain
  │ platform-neutral rules / models / ports / use cases
  ▼
shared
  │ Ktor / SQLDelight / platform adapters / Apple facade
  ├──────────────────────────────→ Android App
  └→ PocketLabShared.framework ──→ SwiftUI App
```

Android 仍能通过 `shared` 看见完整 domain API；Swift 只看见 Apple facade。

听起来像矛盾，实际正是这次实验最重要的结论：

> Gradle 的依赖传播，与 Apple framework 的 API 导出，是两份不同的契约。

## 先别急着建 module：你可能只是在整理文件夹

Android 开发者很熟悉这些动作：

```text
把 model 移进 domain 包
把 repository 移进 data 包
把 use case 移进 usecase 包
```

目录立刻整齐很多。

但如果它们仍位于同一个 Gradle module，`domain` package 依然可以 import SQLDelight，`data` package 也可以反向调用 UI helper。编译器不会在意你的文件夹名字。

KMP 里还有 source set，更容易让三种概念搅在一起。

### source set 回答“这份源码给哪些 target 用”

```text
commonMain → JVM + Android + iOS
iosMain    → iosX64 + iosSimulatorArm64 + iosArm64
```

它是平台共享关系。

### package 回答“声明叫什么名字”

```kotlin
package dev.kmpjourney.pocketlab.sync
```

它是 Kotlin 命名空间。

### module 回答“谁能依赖谁”

```text
:domain
:shared
:androidApp
```

它还是：

- 编译边界；
- 依赖传递边界；
- 测试任务边界；
- 缓存与增量构建边界；
- 可见性和发布边界；
- 团队所有权的候选边界。

所以这次迁移保留了原 package，只改变 Gradle module。

这是一个刻意的选择：

> 模块化不是为了让路径看起来像架构图，而是为了让不允许的依赖无法通过构建。

## 拆分前，先找“变化原因”

PocketLab 拆分前的 `shared` 不是因为代码量太大。真正的信号是它已经包含多种彼此独立的变化原因。

| 代码 | 主要变化原因 |
|---|---|
| Draft、Validator | 产品业务约束变化 |
| Save/Refresh UseCase | 流程与策略变化 |
| Repository / Remote ports | 领域所需能力变化 |
| DraftEditorStore | 交互状态机变化 |
| Ktor client / DTO | HTTP 协议与网络库变化 |
| SQLDelight schema / repository | 存储模型与数据库变化 |
| expect/actual factories | 平台能力与装配变化 |
| Apple bridge / facade | Swift API 与并发边界变化 |
| XCFramework config | 发布消费者与工具链变化 |

如果校验规则改变，不该要求 domain 理解 Darwin engine。

如果 Ktor 升级，不该让业务规则获得新的依赖权限。

如果 Swift API 改名，也不该迫使 Android 业务层跟着重命名。

这些变化原因给出了第一条真实边界：

```text
稳定业务语义
────────────── module boundary
平台与基础设施实现
```

## domain 里究竟放什么？

这次移进 `:domain/commonMain` 的内容包括：

```text
ExperimentDraft / SavedExperiment
DraftValidator
ExperimentRepository
ExperimentLocalStore
ExperimentRemoteDataSource
SaveDraftUseCase
RefreshExperimentsUseCase
DraftEditorStore
StudyStreakPolicy
```

它们并不都是“纯函数”。

`DraftEditorStore` 使用协程和 `StateFlow`；Use Case 会调用 suspend ports；Repository contract 描述持久化能力。domain 的标准从来不是“只能写数学函数”，而是：

> 它只依赖业务所需的抽象，不依赖能力如何在某个平台实现。

因此 domain 可以说：

```kotlin
interface ExperimentRepository {
    suspend fun save(experiment: SavedExperiment)
}
```

但它不知道实现是：

```text
SQLDelight
Room
Core Data
内存
远程 RPC
```

domain 也可以说：

```kotlin
interface EpochClock {
    fun nowMilliseconds(): Long
}
```

但它不知道 JVM 用 `System.currentTimeMillis()`，iOS 用 Core Foundation。

这就是端口的价值：领域可以要求能力，但没有权力选择基础设施。

## 新模块并没有生成第二个 framework

`domain/build.gradle.kts` 声明：

```kotlin
kotlin {
    jvm()

    android {
        namespace = "dev.kmpjourney.domain"
        compileSdk = 35
        minSdk = 24
        withHostTestBuilder {}.configure {}
    }

    iosX64()
    iosArm64()
    iosSimulatorArm64()
}
```

这里有 iOS targets，却没有：

```kotlin
binaries.framework { ... }
```

为什么？

因为 target 与最终分发物不是一回事。

domain 需要为 Native target 编译和测试，因此会生成 KLIB 等中间产物；Swift 并不需要直接消费一个 `PocketLabDomain.framework`。

这和 JVM 世界很像：一个内部 module 可以产生 jar 供另一个 module 编译链接，不代表所有 jar 都要直接交给最终 App 团队。

只是到了 Kotlin/Native，多个 framework 的代价更高，我们稍后会讲。

## 一条 StateFlow，决定了依赖必须是 api

`DraftEditorStore` 公开：

```kotlin
class DraftEditorStore(...) {
    val state: StateFlow<DraftEditorState>
}
```

于是 domain 使用：

```kotlin
commonMain.dependencies {
    api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
}
```

而不是 `implementation`。

这里没有“最佳实践口诀”，只有一个可验证问题：

> consumer 编译公开签名时，是否必须认识这个依赖的类型？

如果答案是“是”，它就是 API dependency，或者你需要重新设计公开签名。

把所有依赖都写成 `implementation` 看起来封装很好，但如果公共 ABI 已经出现 `StateFlow`，构建配置假装它不存在并不会创造封装。

反过来，把所有依赖都写成 `api` 也不是大方。它会：

- 扩大 consumer classpath；
- 增加版本协调；
- 放大 ABI 兼容承诺；
- 让底层库更容易渗入上层代码。

依赖可见性应该跟公共签名一致，而不是跟团队审美一致。

## shared 为什么用 api(project(":domain"))？

迁移前，Android App 只依赖：

```kotlin
implementation(project(":shared"))
```

App 内部直接使用了 `ExperimentRecord`、refresh use case 和其他领域类型。

如果 shared 改成：

```kotlin
implementation(project(":domain"))
```

那么 domain 只在 shared 自己的编译/运行 classpath 中可见，Android consumer 不能再透过 shared 编译这些 public types。

为了让这次拆分保持 Android source compatibility，PocketLab 使用：

```kotlin
commonMain.dependencies {
    api(project(":domain"))
}
```

于是 Android 的消费图仍然成立：

```text
androidApp
  └─ implementation(shared)
       └─ api(domain)
            └─ domain public declarations 可见
```

这不是唯一设计。

更明确的 Android graph 可以是：

```kotlin
implementation(project(":domain"))
implementation(project(":shared"))
```

它能让 App 自己声明使用了哪些 modules。PocketLab 暂时保留 umbrella 依赖，是为了把“建立 module 边界”和“修改消费者图”拆成两次可回滚变化。

专家式迁移不追求一步到终局，而是让每一步都能验证。

## 反直觉现场：Gradle api 不等于 Apple export

现在来到整次实验最容易踩坑的地方。

我们已经写了：

```kotlin
api(project(":domain"))
```

那么 domain 的 `DraftValidator`、Repository、Use Case 和 StateFlow store 会不会全部出现在 `PocketLabShared.framework` 的 Swift API 里？

不会。

Kotlin/Native framework 有单独的 export 语义。

若希望依赖 module 的 API 进入 framework，需要显式配置类似：

```kotlin
binaries.framework {
    export(project(":domain"))
}
```

并且该 dependency 需要在相应 source set 中是 `api` dependency。

也就是说：

```text
api(project)
  决定 Kotlin/Gradle consumer 的编译可见性

export(project)
  决定依赖 module declarations 是否进入 Apple framework API
```

PocketLab 只做前者，不做后者。

最终效果：

```text
domain machine code
  → 被 shared 使用
  → 链接进 static framework
  → Apple facade 内部正常工作

domain declarations
  → 未 export
  → 不进入 Objective-C header
  → Swift consumer 看不见
```

这是非常有价值的非对称：

| Consumer | 合适的契约 |
|---|---|
| Android/Kotlin | 完整领域模型、sealed result、Flow、suspend |
| Swift | Apple snapshot、report、AsyncSequence wrapper、composition root |

跨平台架构并不要求两个平台看到完全相同的 API。

恰恰相反，要求完全相同往往会得到一份对两边都别扭的“最低公分母 API”。

## 一个 umbrella framework，比多个 framework 更像模块化

“既然有两个 KMP modules，为什么不生成两个 frameworks？”

因为 Swift 看到几个文件，不等于 Kotlin 内部就有几个合理边界。

Kotlin 官方多模块文档提醒：iOS 通常通过一个 umbrella module 消费多个 shared modules。若直接生成多个 Kotlin/Native frameworks，它们可能重复包含共同依赖和 Kotlin runtime，进一步带来：

- binary size 增长；
- duplicated global state；
- 同一依赖类型出现多份，不再具有兼容 identity；
- 多套 framework 版本组合；
- 更复杂的 Xcode link/embed 配置；
- 内部模块结构泄漏给 Swift 团队。

PocketLab 因此保留：

```text
domain KLIB ───────┐
                   ├─→ shared umbrella ─→ PocketLabShared.framework
shared source ─────┘
```

Swift 只消费：

```swift
import PocketLabShared
```

内部以后从两个 modules 增长到五个，Swift 产品 API 也不必跟着变成五个 imports。

这才是封装：不是把每个内部边界都变成外部产物，而是让外部消费者只看见稳定入口。

## 为什么不打开 transitiveExport？

Kotlin/Native 的 export 默认不传递。

假设：

```text
shared → domain → another-library
```

export domain 不会默认把 `another-library` 全部继续 export。

你可以打开 `transitiveExport`，但官方文档明确提醒它会增加 framework 大小，并把更多 API 带入导出面。

更严重的是，它把 allowlist 变成了 dependency closure：

```text
我们决定公开什么
```

会退化成：

```text
底层恰好依赖什么，就一起公开什么
```

这对公共 API 治理几乎总是坏消息。

PocketLab 的 module gate 直接拒绝：

```text
transitiveExport
```

如果 Swift 真正需要一个依赖 module 的类型，就逐项 export，并把它当作一次产品 API 决策：

- Swift source name 是什么？
- nullability 是否自然？
- enum/sealed 是否能被穷尽？
- async 与 cancellation 是否成立？
- 版本升级是否兼容？
- 这项承诺由谁维护？

省一行 Gradle 配置，不值得换来不可审计的 API surface。

## 测试迁移暴露了一个“假干净”边界

把生产源码移进 domain 很容易让人产生满足感。

但我们移动 `RefreshExperimentsUseCaseTest` 时，编译器提醒了一个事实：测试直接创建了真实 SQLDelight repository。

原测试大致是：

```kotlin
val local = SqlDelightExperimentRepository(
    database = PocketLabSqlDatabase(driver),
)

val useCase = RefreshExperimentsUseCase(remote, local)
```

这个测试很好，它验证了真实 repository。

但它不是纯粹的 use case test。若把它一起搬进 domain，domain 的测试就必须依赖 SQLDelight，模块边界只在生产源码上干净。

所以我们把职责拆开。

domain test 使用端口 fake：

```kotlin
private class FakeExperimentLocalStore : ExperimentLocalStore {
    private val records = MutableStateFlow<List<ExperimentRecord>>(emptyList())

    override fun observeAll(): Flow<List<ExperimentRecord>> = records

    override suspend fun mergeServerSnapshot(
        experiments: List<ServerExperimentSnapshot>,
    ): Int {
        // 精确实现这个 use case 所需的 contract
    }
}
```

它回答：

> remote 成功或失败时，用例是否按 contract 编排 local port？

shared 继续保留：

- `SqlDelightExperimentRepositoryTest`；
- `PocketLabRefreshScenarioTest`。

后者真实穿过：

```text
Demo remote
→ RefreshExperimentsUseCase
→ SQLDelight repository
→ transaction
→ database Flow
```

它回答：

> domain contract 与真实 infrastructure 是否能一起工作？

模块化没有把所有东西都变成 fake。它只是让每层测试回答自己的问题。

## 测试数没增加，证据变清楚了

拆分前，全仓核心矩阵是：

```text
JVM 39
Android Host 39
iOS x64 47
```

拆分后：

| Target | domain | shared | 合计 |
|---|---:|---:|---:|
| JVM | 18 | 21 | 39 |
| Android Host | 18 | 21 | 39 |
| iOS x64 | 18 | 29 | 47 |

再加：

```text
Android ViewModel unit 2
Android Compose UI 2
iOS SwiftUI UI 1（上一完整基线）
```

仓库定义的完整矩阵仍是：

```text
54 个不同测试场景
130 次目标执行
```

本轮模块化后实际重跑 129/129。唯一没有重跑的是 iOS UI smoke：当前 Xcode 26.5 安装了 iOS 26.5 SDK，却没有匹配的 runtime，现有 iOS 17.5–18.4 devices 在 destination 发现阶段被判为不可用。它上一完整基线为 1/1，但本文不会把旧基线或 framework 构建成功写成“本轮 UI 已通过”。

这组数字很重要，因为模块拆分经常制造一种虚假的进步感：task 更多、报告更多，看起来测试“变多了”。

其实行为场景没有增加，只是所有权改变。

正确的描述不是“模块化新增了 54 条测试”，而是：

> 18 个共同场景现在由 domain 独立拥有并在三端执行；基础设施、平台和互操作场景仍由 shared 拥有；聚合覆盖不回退。

## 头文件从 2,267 行变成 888 行

真正让这次拆分变得肉眼可见的，是 Apple header。

模块化前，PocketLab 在上一阶段把 header 收窄到：

```text
2,267 行
```

拆分、内部化平台工厂，并禁止 export domain 后：

```text
888 行
```

减少：

```text
1,379 行，约 60.8%
```

更重要的不是行数，而是 denylist 结果：

```text
DraftValidator           0
SaveDraftUseCase         0
ExperimentRepository     0
ExperimentLocalStore     0
RemoteExperimentsResult  0
DraftEditorStore         0
StudyStreakPolicy        0
```

而 allowlist 仍在：

```text
ExperimentListClient
ExperimentSnapshot
RefreshReport
PocketLabFeature
CancellationToken
```

Android 得到了完整 domain API，Swift 得到了更小的产品 API。

这就是“共享多少，由消费者契约决定”的实际证据。

### 但 888 仍不是完美答案

打开 header，仍能看到一些 SQLDelight 生成声明。

原因不是 `@HiddenFromObjC` 写少了，而是 SQLDelight 生成代码与 Apple facade 仍位于同一个 framework-producing module：`:shared`。

这给出了下一条可能的边界：

```text
domain
  ↑
infrastructure (Ktor + SQLDelight)
  ↑
apple-api / shared umbrella
```

但我们没有立刻拆。

因为“还能再拆”永远成立。真正的问题是：

- SQL API 是否造成实际 Swift 兼容负担？
- infrastructure 是否需要独立 owner/version？
- header、build time 或 binary size 是否继续恶化？
- 新 module 的 Gradle 与 CI 成本是否值得？

架构债务应该被测量和排期，不应该被漂亮的最终图掩盖。

## 把架构原则变成会失败的脚本

一句“domain 不依赖 infrastructure”放在 README 里，半年后通常会变成考古材料。

PocketLab 新增：

```shell
./scripts/verify-module-boundaries.sh
```

它会先运行：

```text
:domain:jvmTest
:domain:testAndroidHostTest
:domain:iosX64Test
:shared:linkDebugFrameworkIosX64
```

然后检查：

```text
domain 没有 android.* imports
domain 没有 platform.* imports
domain 没有 io.ktor.* imports
domain 没有 app.cash.sqldelight.* imports
domain 不依赖 :shared
domain 不声明 framework/XCFramework
shared 必须 api(:domain)
shared 禁止 export(:domain)
shared 禁止 transitiveExport
Apple header 必须包含 facade
Apple header 禁止 domain symbols
```

实测输出：

```text
Module boundary contract passed.
domain -> platform/infrastructure imports: 0
shared -> domain for Kotlin consumers: api
shared -> domain for Apple consumers: linked, not exported
Apple header lines: 888
```

这还不是终极静态架构分析工具。简单 import scan 可能被 fully qualified name 绕过，也不理解动态 Gradle DSL。

但它已经把最重要的当前规则变成 CI 能理解的失败。

随着项目增长，可以升级为：

- Gradle dependency graph allowlist；
- Konsist/Detekt 自定义架构规则；
- binary API validation；
- normalized header diff；
- module-level build performance budget。

先让关键规则能失败，再逐步提高检测精度。

## “共享率”为什么是一个危险 KPI

假设团队宣布：

```text
本季度 KMP 共享率要达到 80%
```

开发者会自然优化这个指标：

- 平台文案塞进 common；
- Swift 不好用的 sealed/Flow 直接暴露；
- 生命周期逻辑强行抽象；
- 很薄的平台代码也包装三层接口；
- UI 差异被压进无穷 if/else；
- 所有非 UI 文件都搬进 shared。

数字会上升，系统未必更便宜。

更好的问题是：

| 问题 | 想优化的结果 |
|---|---|
| 哪些错误曾在两端重复出现？ | 共享稳定规则 |
| 哪些功能必须两端语义一致？ | 共享领域决策 |
| 哪些平台体验必须各自自然？ | 保留平台实现 |
| 哪些 API 让 Swift 付出额外成本？ | 建立 Apple facade |
| 哪些变更总让无关团队一起等待？ | 建立 module/ownership 边界 |
| 哪些 build/test 可独立缓存？ | 拆出有收益的编译单元 |

共享不是目标；减少重复决策、减少语义漂移和降低总变更成本才是。

## 模块边界最终由谁决定？

不是由某一个角色单独决定。

### 业务语义决定什么值得共享

标题必填、pending local write 不被远端覆盖，这些规则跨平台必须一致。

### 平台体验决定什么不该共享

返回手势、键盘、日期呈现、Swift async wrapper 和 Android lifecycle 不必假装相同。

### 构建与发布决定边界如何落地

Gradle module、KLIB、framework export、XCFramework 和 consumer classpath 会限制哪些图真正可行。

### 团队结构决定边界是否可持续

如果 data 团队独立发布、feature 团队有不同节奏，一个 module 可能造成协调瓶颈。若只有三个人，十几个 modules 可能只制造仪式。

### 测量结果决定什么时候继续拆

header 泄漏、build profile、binary size、cache hit、改动扩散和事故记录，比“Clean Architecture 应该有几层”更可靠。

所以真正的答案是：

> 产品语义提出边界，平台与工具链约束边界，团队拥有边界，测试和数据验证边界。

## 五个常见的模块化陷阱

### 陷阱一：一个 package 等于一个 module

结果是几十个微型 modules，依赖配置比业务代码还长。

**判断方法：** 是否需要阻止依赖、独立测试、独立 owner、独立发布或明显减少变更扩散？若都不是，package 可能已经足够。

### 陷阱二：所有 shared modules 都生成 framework

结果是多个 runtime/dependency copies、类型 identity 和 Xcode 集成问题。

**判断方法：** Swift 是否真的需要独立消费它？若只是内部边界，优先 KLIB dependency + 一个 umbrella framework。

### 陷阱三：为了省配置打开 transitiveExport

结果是 dependency graph 意外成为公共 Swift API。

**判断方法：** 每个 export 能否对应一个明确 Apple consumer use case？不能就不要导出。

### 陷阱四：只移动生产代码，不移动测试责任

结果是 domain test 仍依赖数据库和网络，或者所有真实集成测试被删除。

**判断方法：** domain test 用 ports，infrastructure test 验实现，integration test 验接缝；三者各自回答问题。

### 陷阱五：用模块数量证明架构成熟

结果是 build 更慢、导航更难、版本协调更多，却没有一条新约束。

**判断方法：** 新 module 建立了哪条之前不能执行的规则？如果回答只是“目录更清楚”，先用 package。

## 一份可执行的渐进拆分清单

如果你的 `shared` 也开始膨胀，可以按下面顺序做。

### 1. 记录现状

- target 与 source set 图；
- module dependencies；
- tests 场景数与目标执行数；
- Apple header symbols/行数；
- framework/XCFramework size；
- clean/incremental build time。

没有基线，就无法证明拆分改善了什么。

### 2. 按变化原因分类

不要先讨论 `:core`、`:base`、`:common` 这些名字。先写出每组代码为什么改变。

### 3. 选择一条最有价值的禁止边

例如：

```text
domain 不得依赖 database/network/platform
```

一次只建立一条主要边界。

### 4. 先建 module，再迁移最稳定的 API

保留 package 和 consumer source compatibility，减少同时变化的维度。

### 5. 审计公共签名

每个 `api` dependency 都必须能由 public ABI 解释；其余优先 `implementation`。

### 6. 单独设计 Apple export

不要假设 Gradle dependency 等于 framework API。列出 Swift allowlist，再决定 umbrella export。

### 7. 搬迁测试并保留集成层

端口测试进 domain，技术实现测试留 infrastructure，关键真实链路保留 integration。

### 8. 比较基线

- 原测试数是否保持？
- consumer 是否仍编译？
- header 是否按预期改变？
- build 是否改善或恶化？
- dependency graph 是否真的阻止反向依赖？

### 9. 写成门禁

README 会过时，失败的 CI 更有记忆力。

### 10. 停下来

不要因为第一次拆分有效，就顺手拆完所有 package。等待下一条有证据的边界。

## 当前 PocketLab 的诚实结论

本次迁移完成了这些事：

- `:domain` 独立拥有规则、模型、端口、用例与状态机；
- domain 不导入平台、Ktor 或 SQLDelight；
- domain 在 JVM、Android Host、iOS x64 各运行 18 个共同场景；
- `:shared` 单向依赖 domain；
- Android 通过 Gradle `api` 保持兼容；
- Swift 通过一个 umbrella framework 消费 facade；
- domain 没有被 framework export；
- Apple header 从 2,267 行降到 888 行；
- 仓库仍有 54 个场景、130 次设计矩阵；本轮可运行部分 129/129，iOS UI 保留上一完整基线 1/1 与明确的 runtime 缺口；
- 模块规则进入自动门禁。

它没有完成这些事：

- SQLDelight 生成 API 尚未完全离开 Apple header；
- data/network 尚未成为独立 module；
- Android App 尚未显式依赖 domain；
- Apple Silicon runtime tests 仍需 CI 补齐；
- 尚未用 Gradle profile 证明拆分改善了 build time；
- module ownership 仍由同一仓库团队承担。

这不是遗憾清单，而是架构工作的正常状态。

好的模块化不是一次画完所有边界，而是每次用一个真实问题、一条可执行约束和一组不回退的证据，让系统更容易继续变化。

## 最后，再回答标题

KMP 应该共享多少？

共享那些跨平台语义必须一致、能形成稳定依赖方向、并且总变更成本确实更低的部分。

谁决定？

```text
业务决定语义
平台决定体验
工具链决定可行性
团队决定所有权
测试与数据决定这条边界是否成立
```

而不是由 `commonMain` 的编译器单独决定，更不是由“共享率越高越好”决定。

当你能同时说清：

```text
为什么这段代码属于 domain
为什么 domain 不能依赖 infrastructure
为什么 Android 能看见它
为什么 Swift 不该看见它
为什么 iOS 只有一个 framework
为什么现在不继续拆
```

你才真正开始掌握 KMP 模块化。

## 动手挑战

打开自己的 KMP 项目，完成这五件事：

1. 列出当前 `shared` 中至少三种不同变化原因；
2. 画出 module graph，并标记一条绝不允许的反向依赖；
3. 找出 public API 中出现的第三方类型，核对它们应该是 `api` 还是重新封装；
4. 对比 Gradle `api` dependencies 与 Apple framework `export` allowlist；
5. 为边界写一个会失败的自动检查，并保存拆分前后的测试、header 和 build 数据。

如果做完后发现不值得新增 module，也是一项合格的专家判断。

## 本文实验入口

- [实验 14：把万能 shared 拆成可执行的依赖边界](../labs/14-kmp-modularization/README.md)
- [ADR-0012：领域模块独立，shared 作为唯一 Apple umbrella](../docs/adr/0012-domain-module-and-apple-umbrella.md)
- [主实验工程](../labs/02-first-kmp-module)

## 官方资料

- [Kotlin Multiplatform project configuration：多模块与 umbrella framework](https://kotlinlang.org/docs/multiplatform/multiplatform-project-configuration.html)
- [Build final Native binaries：framework dependency export](https://kotlinlang.org/docs/multiplatform/multiplatform-build-native-binaries.html)
- [Default hierarchy template：iosMain 等中间 source sets](https://kotlinlang.org/docs/multiplatform/multiplatform-hierarchy.html)
- [Recommended project structure：按项目需求选择结构](https://kotlinlang.org/docs/multiplatform/multiplatform-project-recommended-structure.html)

下一篇，我们会把“模块化后应该更快”这句直觉交给数据：拆开 Gradle 配置、Kotlin compilation、Native link、Xcode build 与缓存命中，找出 KMP 构建到底慢在哪里，以及什么优化只是把时间藏到了别处。
