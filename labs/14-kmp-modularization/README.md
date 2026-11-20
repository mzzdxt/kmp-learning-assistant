# 实验 14：把万能 shared 拆成可执行的依赖边界

## 实验目标

完成本实验后，你应该能用真实构建回答：

1. source set、Kotlin package 与 Gradle module 分别建立什么边界？
2. 为什么“代码都能放进 commonMain”不代表应该放进同一个 module？
3. `api`、`implementation` 与 Kotlin/Native framework `export` 有什么不同？
4. 为什么 Android 可以透过 shared 使用 domain，而 Swift 不应该看见同一批类型？
5. 多个 KMP modules 为什么通常仍只给 iOS 一个 umbrella framework？
6. 怎样让依赖方向、禁止导出和测试责任成为自动门禁？
7. 模块拆得越多，是否就越专业？

主工程仍位于：

```text
../02-first-kmp-module
```

## 前置条件

- 已完成[实验 13](../13-apple-distribution/README.md)；
- 理解 `commonMain`、平台 source sets 与 target；
- 理解 Apple facade、framework header 与 XCFramework；
- 本机可运行 JDK 17、Gradle Wrapper、Xcode/Swift 与 `rg`；
- 当前 Intel host 运行 `iosX64Test`，Apple Silicon 应补 `iosSimulatorArm64Test`。

## 先看问题：一个 module 里已经有六种变化原因

模块化前，`:shared` 同时包含：

```text
shared/commonMain
├── draft model + validation
├── SaveDraftUseCase + DraftEditorStore
├── refresh use case + ports
├── Ktor client + wire DTO
├── SQLDelight repository + schema
└── demo data source

shared/platformMain
├── clock / id actuals
├── HTTP engines
├── database drivers
└── Apple interop + composition
```

它能编译、能测试、也能发布，所以问题并不表现为红色错误。

问题是每个概念都处在同一个依赖和发布单元：

- 改校验规则与升级 Ktor 都触碰 `:shared`；
- use case test 可以顺手创建真实 SQLDelight repository；
- domain 文件可以顺手 import infrastructure；
- framework producer 会考虑同 module 的全部 public declarations；
- 团队只能口头约定“不要乱依赖”。

本实验不是为了增加目录，而是把约定升级为构建失败。

## 第一步：区分三种常被混淆的边界

### source set

source set 回答：

> 哪些 targets 复用这一组源码和依赖？

例如：

```text
commonMain → JVM + Android + iOS
iosMain    → iosX64 + iosSimulatorArm64 + iosArm64
```

它不阻止 `commonMain` 中的领域规则依赖同一 module 的 Ktor 或 SQLDelight。

### Kotlin package

package 回答：

> 声明的命名空间是什么？import 如何书写？

把 `dev.kmpjourney.pocketlab.sync` 改名为 `domain.sync` 只改变名字。package 可以跨 source set，也可以跨 module；它不是 Gradle dependency edge。

### Gradle module

module 回答：

> 谁能依赖谁？依赖怎样传递？什么能独立编译、测试和发布？

因此本实验保留原 package，专注改变真正的依赖边界。

## 第二步：先按变化原因画边界

不要先数文件。先把声明按变化原因归类：

| 声明 | 为什么变化 | 新所有者 |
|---|---|---|
| Draft、SavedExperiment | 产品语义变化 | domain |
| Validator、Save/Refresh UseCase | 业务规则变化 | domain |
| Repository / remote / clock ports | 领域所需能力变化 | domain |
| DraftEditorStore | 跨平台交互状态机变化 | domain |
| Ktor client / DTO / engine | HTTP 协议或库变化 | shared |
| SQLDelight schema / repository / driver | 持久化变化 | shared |
| expect/actual factory | 平台装配变化 | shared |
| Apple facade | Swift 产品 API 变化 | shared |
| framework/XCFramework | Apple 发布变化 | shared |

目标依赖图：

```text
domain
  │
  ▼
shared ─────────→ PocketLabShared.framework ─────────→ SwiftUI
  │
  └──────────────────────────────────────────────────→ Android App
```

箭头表示“上层依赖下层”。`domain` 不认识 `shared`。

## 第三步：创建没有 framework 的 domain KMP module

在 `settings.gradle.kts` 注册：

```kotlin
include(":domain")
include(":shared")
include(":androidApp")
```

`domain/build.gradle.kts` 声明与产品相同的可验证 targets：

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

注意这里没有：

```kotlin
binaries.framework { ... }
XCFramework(...)
```

domain 能产生 JVM jar、Android library artifacts 和 Native KLIB，不代表必须变成一个 iOS framework。

KLIB 是模块间 Native 编译输入；framework 才是给 Xcode/Swift consumer 的最终二进制边界。

## 第四步：公开签名决定依赖是 api 还是 implementation

`DraftEditorStore` 公开：

```kotlin
val state: StateFlow<DraftEditorState>
```

消费者编译这个签名时必须认识 `StateFlow`。因此 domain 配置：

```kotlin
commonMain.dependencies {
    api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
}
```

判断方法不是“我希望少传递依赖”，而是：

> 依赖类型是否出现在 module 的公共 ABI/API 中？

若只在私有实现中使用，优先 `implementation`；若消费者编译公开签名需要它，使用 `api` 或重新设计签名。

`api` 会扩大 Kotlin consumer compile classpath，也会增加兼容承诺。不要把所有依赖都设成 `api`，也不要为了“封装”让公共签名的 classpath 残缺。

## 第五步：移动规则、端口和用例

迁移后的 `domain/commonMain`：

```text
domain/src/commonMain/kotlin/dev/kmpjourney/
├── pocketlab/
│   ├── DraftEditorStore.kt
│   ├── DraftValidator.kt
│   ├── ExperimentCatalog.kt
│   ├── ExperimentDraft.kt
│   ├── SaveDraftUseCase.kt
│   ├── network/ExperimentRemoteDataSource.kt
│   └── sync/RefreshExperimentsUseCase.kt
└── shared/StudyStreak.kt
```

这里保留现有 package。移动 Gradle module 不要求改业务词汇。

用下面的负面清单检查 domain：

```text
android.*             禁止
platform.*            禁止
io.ktor.*             禁止
app.cash.sqldelight.* 禁止
project(":shared")   禁止
```

这不是说 domain 永远不能使用任何 library。它可以使用真正属于领域抽象的跨平台库；禁止的是当前决策中由 infrastructure/platform 所有的依赖。

## 第六步：让 shared 依赖 domain

`shared/build.gradle.kts` 使用：

```kotlin
commonMain.dependencies {
    api(project(":domain"))

    implementation("io.ktor:ktor-client-core:3.6.1")
    implementation("app.cash.sqldelight:runtime:2.4.1")
    // ...
}
```

为什么是 `api(project(":domain"))`？

Android App 当前只声明：

```kotlin
implementation(project(":shared"))
```

它的 Application、ViewModel 和 Compose UI 仍直接使用 domain 的 `ExperimentRecord`、`RefreshExperimentsUseCase` 等 Kotlin 类型。`api` 保留了这份传递可见性，不要求为了模块拆分立刻改 Android consumer graph。

这是一项兼容迁移策略，不是说 App 永远只能依赖 umbrella。将来 Android 可显式依赖 `:domain` 与 `:shared`，让 dependency ownership 更直接。

## 第七步：最容易误解的一行——api 不等于 export

很多 Android 开发者会自然推断：

```text
shared api(domain)
→ domain 会进入 shared framework header
```

实际并非如此。

Gradle 的 `api(project(...))` 决定 Kotlin/Gradle consumer 编译 classpath。Kotlin/Native framework 的 `export(project(...))` 才决定依赖 module 的 declarations 是否进入 Apple framework API。

PocketLab 故意只写前者：

```kotlin
api(project(":domain"))
```

并且不写：

```kotlin
export(project(":domain"))
```

于是：

```text
Android / Kotlin consumer
shared ── api ──→ domain declarations 可见

Swift consumer
PocketLabShared framework
├── domain machine code：已链接，facade 内部可用
└── domain declarations：未 export，header 不可见
```

“代码进入 binary”与“声明成为 consumer API”是两件事。

## 第八步：为什么只保留一个 Apple umbrella framework

如果让 `domain` 与 `shared` 都生成 framework，Swift 看起来可以更自由地组合：

```text
PocketLabDomain.framework
PocketLabShared.framework
```

但 Kotlin/Native framework 不是普通 Java jar。多个 framework 可能分别携带共享依赖、runtime 和类型副本，造成：

- 重复代码与体积；
- 同名 Kotlin 类型来自不同 binary 时 identity 不兼容；
- 全局状态或 singleton 被复制；
- Xcode 集成与版本组合增多；
- Swift consumer 被迫理解内部 module graph。

PocketLab 选择一个 `PocketLabShared` umbrella：

```text
domain KLIB ─┐
             ├─→ shared link ─→ PocketLabShared.framework
shared code ─┘
```

umbrella module 同时是 Apple composition root。Swift 只知道产品能力，不知道内部是一个还是十个 Kotlin modules。

## 第九步：拒绝 transitiveExport

Kotlin/Native dependency export 默认不递归。若 A export B，而 B 又依赖 C，C 不会自动全部成为 A 的 framework API。

`transitiveExport = true` 看似省配置，实际上会：

- 隐藏真实 API allowlist；
- 扩大 header；
- 禁用更多 dead-code elimination 机会；
- 增加 build time 与 binary size；
- 让一次底层依赖升级变成 Swift source compatibility 事件。

因此门禁直接拒绝 `transitiveExport`。

若 Swift 真正需要某个依赖声明，应逐项 `export`，并把它当作产品 API 评审，而不是依赖图便利设置。

## 第十步：让测试也服从 module ownership

领域测试迁移到：

```text
domain/src/commonTest
```

其中最值得观察的是 `RefreshExperimentsUseCaseTest`。

迁移前它创建：

```kotlin
SqlDelightExperimentRepository(
    database = PocketLabSqlDatabase(driver),
)
```

这能测真实数据路径，却不能单独证明 use case 只依赖 `ExperimentLocalStore` contract。

迁移后使用内存 fake：

```kotlin
private class FakeExperimentLocalStore : ExperimentLocalStore {
    private val records = MutableStateFlow<List<ExperimentRecord>>(emptyList())

    override fun observeAll(): Flow<List<ExperimentRecord>> = records

    override suspend fun mergeServerSnapshot(
        experiments: List<ServerExperimentSnapshot>,
    ): Int {
        // deterministic contract implementation for use-case tests
    }
}
```

真实 SQLDelight 并没有失去测试。它仍由 `shared/commonTest` 的两层证据覆盖：

1. `SqlDelightExperimentRepositoryTest` 验证 repository/transaction/merge；
2. `PocketLabRefreshScenarioTest` 穿过 demo remote → use case → 真实 repository → Flow。

这叫分层，不叫把真实依赖都 fake 掉。

## 第十一步：运行模块矩阵

执行：

```shell
cd ../02-first-kmp-module

./gradlew \
  :domain:jvmTest \
  :domain:testAndroidHostTest \
  :domain:iosX64Test \
  :shared:jvmTest \
  :shared:testAndroidHostTest \
  :shared:iosX64Test \
  :androidApp:testDebugUnitTest
```

本机结果：

| Target | domain | shared | 聚合 |
|---|---:|---:|---:|
| JVM | 18/18 | 21/21 | 39/39 |
| Android Host | 18/18 | 21/21 | 39/39 |
| iOS x64 | 18/18 | 29/29 | 47/47 |

加上：

| 平台层 | 结果 |
|---|---:|
| Android ViewModel unit | 2/2 |
| Android Compose instrumentation | 2/2 |
| iOS SwiftUI XCTest | 上一完整基线 1/1；本轮未执行 |

仓库定义的完整矩阵仍为：

```text
54 个不同测试场景
130 次目标执行
```

本轮实际重跑 129/129。Xcode 26.5 只有 iOS 26.5 SDK、没有匹配的 Simulator/platform runtime；CoreSimulator 中的 iOS 17.5–18.4 devices 虽能启动，却被 Xcode 判为不可用，因此 UI test 在 destination 发现阶段停止。模块化只是重新分配测试所有权，没有凭空创造行为覆盖；环境发现失败也不能冒充第 130 次行为执行。

## 第十二步：用 header 验证 Apple API 没被 Gradle api 误导

生成 Debug framework：

```shell
./gradlew :shared:linkDebugFrameworkIosX64
```

检查：

```shell
HEADER=shared/build/bin/iosX64/debugFramework/\
PocketLabShared.framework/Headers/PocketLabShared.h

wc -l "$HEADER"

rg 'DraftValidator|SaveDraftUseCase|ExperimentRepository|\
ExperimentLocalStore|RemoteExperimentsResult|DraftEditorStore' \
  "$HEADER"
```

结果：

- 模块化前：2,267 行；
- 模块化后：888 行；
- 减少 1,379 行，约 60.8%；
- 上述 domain symbols：0 个；
- `ExperimentListClient` 与 `PocketLabFeature` 仍存在。

行数只是趋势指标，symbol allow/deny 才是契约。编译器版本可能改变 helper declarations，不能把“必须恰好 888 行”写成脆弱门禁。

还要诚实观察：header 里仍有 SQLDelight 生成类型。原因是 SQLDelight code 仍与 Apple facade 位于 `:shared` 这个 framework-producing module。

本实验不假装它们不存在，也不为了追求漂亮数字立刻再拆五个 modules。它把这项债务变成下一次可验证的候选：若继续影响 Swift API、编译或所有权，再拆 `:infrastructure` / `:apple-api`。

## 第十三步：运行架构门禁

执行：

```shell
./scripts/verify-module-boundaries.sh
```

门禁检查：

```text
domain 三端 tests
domain 禁止 platform/Ktor/SQLDelight imports
domain 禁止依赖 shared
domain 禁止生成 framework
shared 必须 api(domain)
shared 禁止 export(domain)
shared 禁止 transitiveExport
Apple facade 必须存在
domain symbols 必须从 header 消失
```

成功输出包括：

```text
Module boundary contract passed.
domain -> platform/infrastructure imports: 0
shared -> domain for Kotlin consumers: api
shared -> domain for Apple consumers: linked, not exported
Apple header lines: 888
```

再运行既有 Apple 门禁：

```shell
./scripts/verify-apple-api.sh
./scripts/verify-apple-distribution.sh
```

旧门禁也加入 domain denylist，避免某人以后“为了 Swift 方便”无意间把整个领域层重新 export。

## 第十四步：做三组故障实验

### 故障 A：让 domain import Ktor

在 domain 任一文件加入：

```kotlin
import io.ktor.client.HttpClient
```

即使你再加 dependency 让它编译，module boundary gate 仍应失败。编译能力不是架构许可。

### 故障 B：把 domain 改成 implementation

将 shared 的：

```kotlin
api(project(":domain"))
```

改为：

```kotlin
implementation(project(":domain"))
```

重新编译 Android App，观察只依赖 shared 的 consumer 为什么找不到 domain declarations。

### 故障 C：export domain

在 framework binary 中加入：

```kotlin
export(project(":domain"))
```

重新生成 header，比较行数和 symbols。然后运行门禁，确认它拒绝这次 API surface 扩张。

## 不是越碎越好：模块拆分的成本表

每增加一个 module，至少增加：

- Gradle project configuration；
- target/compilation/task 图；
- dependency version 与 variant 解析；
- module 间 ABI/API 协调；
- 测试报告与 CI cache key；
- IDE navigation 与 ownership 规则；
- 发布或兼容策略的潜在边界。

因此拆分前至少回答一个问题：

| 触发信号 | module 能提供的价值 |
|---|---|
| 需要禁止反向依赖 | 构建级方向约束 |
| 需要独立测试/缓存 | 独立 task 与 artifact |
| 团队所有权不同 | review 与发布边界 |
| 消费者只需要子集 | 依赖/产物裁剪 |
| 变更频率显著不同 | 减少无关重编译与协调 |
| 需要独立版本 | 明确兼容契约 |

如果唯一理由是“这个文件夹有十个文件”，先别拆。

## Android 与 iOS 为什么可以有不同消费图

当前 Android 仍走 umbrella：

```text
androidApp → shared api→ domain
```

iOS 只消费：

```text
SwiftUI → PocketLabShared.framework facade
```

它们不必镜像。

Android/Kotlin 能理解 domain 的 sealed interfaces、coroutines 与普通泛型；Swift consumer 更适合稳定 facade、value snapshots、显式 cancellation 和平台错误类型。模块化的目标不是让两个平台看到相同依赖图，而是让每个 consumer 得到合适且受治理的契约。

## 官方结论与本实验如何对应

Kotlin 官方文档明确指出：

- 一个项目可以有多个 shared modules；Android 可以分别依赖或通过 umbrella 依赖；
- iOS 通常消费一个 umbrella framework；
- 多个 Kotlin/Native frameworks 可能复制依赖、状态和类型；
- `api(project(...))` 是 `export(project(...))` 的必要条件，但不会自动导出 framework API；
- export 默认不递归，`transitiveExport` 会扩大 binary/API，官方不建议轻率启用；
- 默认 hierarchy template 会建立 `iosMain` 等中间 source sets；
- 推荐结构取决于产品、团队与依赖关系，不存在唯一目录模板。

对应资料：

- [KMP project configuration](https://kotlinlang.org/docs/multiplatform/multiplatform-project-configuration.html)
- [Build final Native binaries](https://kotlinlang.org/docs/multiplatform/multiplatform-build-native-binaries.html)
- [Default hierarchy template](https://kotlinlang.org/docs/multiplatform/multiplatform-hierarchy.html)
- [Recommended project structure](https://kotlinlang.org/docs/multiplatform/multiplatform-project-recommended-structure.html)

## 挑战题

1. 把 `api(project(":domain"))` 改成 `implementation`，保存 Android compile error 并解释 classpath；
2. 临时 export domain，记录 header 增量和新增 Swift names；
3. 开启 `transitiveExport`，比较 framework 大小、link time 与 header；
4. 给 gate 增加 `domain` dependency report allowlist；
5. 在 Apple Silicon CI 补 `domain:iosSimulatorArm64Test` 与 `shared:iosSimulatorArm64Test`；
6. 用 Gradle Build Scan 或 profile 比较拆分前后的配置/编译时间；
7. 设计 `:infrastructure` module，把 SQLDelight/Ktor 移出 framework producer，再比较 header；
8. 设计 `:apple-api` umbrella，只保留 facade 与 composition，评估额外 module 的真实收益；
9. 让 Android App 显式依赖 `:domain` 与 `:shared`，比较依赖所有权；
10. 为五人、二十人和跨仓团队分别画 module ownership，不允许复用同一答案。

## 验收标准

只有当你能解释下面这段话，才算完成：

> source set 决定源码被哪些 targets 复用，module 决定依赖、编译与发布边界，package 只决定命名空间。PocketLab 把平台中立的规则、端口与用例放进 domain，让 shared 单向依赖它；Gradle `api` 保留 Kotlin consumer 的传递可见性，但 Apple framework 不 `export` domain，只由一个 umbrella 暴露 Swift facade。模块数量由依赖方向、变化原因、团队所有权和测量结果决定，而不是由“共享率”或文件数决定。

对应决策记录：[ADR-0012](../../docs/adr/0012-domain-module-and-apple-umbrella.md)

对应文章：[KMP 模块化：共享多少，由谁决定？](../../articles/14-kmp-modularization.md)
