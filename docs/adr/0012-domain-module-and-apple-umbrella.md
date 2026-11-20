# ADR-0012：领域模块独立，shared 作为唯一 Apple umbrella

- 状态：Accepted
- 日期：2026-11-20
- 决策范围：PocketLab KMP 模块边界、Gradle API 传递与 Apple framework 导出

## 背景

PocketLab 的规则、状态机、端口、SQLDelight、Ktor、平台工厂与 Apple facade 原本全部位于 `:shared`。source set 已能区分共同代码与平台实现，但 Gradle 模块层面仍只有一个发布和依赖边界。

这带来四个问题：

1. 领域代码可以在不经意间导入 Ktor、SQLDelight 或平台 API；
2. 领域测试使用真实 SQLDelight repository，无法证明用例只依赖端口；
3. Android 需要完整 Kotlin 领域 API，而 Swift 只需要收窄后的 Apple facade，两类消费者却共用一套可见性心智模型；
4. 将来继续增长时，所有变更都会进入同一个编译、所有权和发布单元。

KMP source set 与 Gradle module 解决不同问题。source set 表达“哪些 targets 共享源码”；module 表达“谁可以依赖谁、哪些依赖进入消费者编译 classpath、什么可以独立构建和发布”。只整理 package 或目录不能建立后者。

## 决策

### 模块图

采用下面的依赖方向：

```text
domain ─────────────────────────────────────┐
  ▲                                         │
  │ ordinary Kotlin ports                   │ api(project)
  │                                         ▼
shared: Ktor + SQLDelight + platform adapters + Apple composition
  ▲                                         │
  │ implementation(project)                 │ one umbrella framework
  │                                         ▼
Android App                             SwiftUI App
```

- 新建 `:domain` KMP module；
- `:shared` 依赖 `:domain`，反向依赖禁止；
- Android App 继续只依赖 `:shared`；
- `:shared` 继续生成唯一的 `PocketLabShared` framework / XCFramework；
- `:domain` 不生成 Apple framework。

模块名与 Kotlin package 是两套命名空间。迁移时保留现有 `dev.kmpjourney.*` packages，避免仅为“看起来模块化”制造无意义的 import 和消费者源码变更。

### domain 的职责

`domain/commonMain` 拥有：

- 草稿与保存模型；
- 校验规则；
- Repository、Clock、ID Generator、本地存储和 remote data source 端口；
- `SaveDraftUseCase`、`RefreshExperimentsUseCase`；
- `DraftEditorStore` 与其状态机；
- Study streak 示例规则。

`domain` 不允许导入：

- Android、UIKit/Foundation 等平台 API；
- Ktor；
- SQLDelight；
- `:shared`。

`DraftEditorStore.state` 公开 `StateFlow`，因此 `kotlinx-coroutines-core` 是 `domain` 的 `api` dependency，而不是只在实现内部可见的 dependency。

### shared 的职责

`:shared` 保留：

- Ktor client、DTO 与 JVM/Android/Darwin engines；
- SQLDelight schema、drivers 与 repository implementation；
- `expect/actual` 平台工厂；
- demo remote；
- Apple callback/cancellation bridge、DTO 与 composition root；
- framework、XCFramework 和 Apple distribution 配置。

平台工厂只服务 module 内部装配，使用 `internal`。Android 需要直接创建的 demo remote 保持 Kotlin `public`，但用 `@HiddenFromObjC` 排除 Apple 导出。

### Kotlin API 与 Apple export 是两份契约

`:shared` 使用：

```kotlin
commonMain.dependencies {
    api(project(":domain"))
}
```

这让 Kotlin/Gradle 消费者通过 `:shared` 编译时仍能看到 `:domain` 的公开类型。它不等于把 `:domain` API 导出到 Objective-C/Swift framework。

Apple framework 故意不配置：

```kotlin
export(project(":domain"))
```

结果是 domain 代码会被链接进最终 static framework，供 `:shared` 的 Apple facade 实现使用，但 domain declarations 不进入 framework header。

禁止 `transitiveExport`。它会扩大导出闭包、构建成本、API 面与二进制体积，并让“谁是产品 API”变得不可审计。

### 测试所有权

- 领域规则、状态机与用例测试迁移到 `domain/commonTest`；
- `RefreshExperimentsUseCaseTest` 改用 `ExperimentLocalStore` 内存 fake，不再引用 SQLDelight；
- 真实 SQLDelight repository、Ktor、平台 adapter 与端到端刷新留在 `shared`；
- 两个 modules 都在 JVM、Android Host 与 iOS x64 执行其共同测试；
- Android/iOS UI tests 保持原平台所有权。

当前矩阵：

| Target | domain | shared | 聚合 |
|---|---:|---:|---:|
| JVM | 18 | 21 | 39 |
| Android Host | 18 | 21 | 39 |
| iOS x64 | 18 | 29 | 47 |

模块变化不应伪装成新增测试。仓库仍定义 54 个不同场景、130 次目标执行；本轮完成 129/129，上一完整基线的 1 条 iOS UI smoke 因 Xcode 26.5 缺少匹配 runtime 未能重跑。

### 自动门禁

`scripts/verify-module-boundaries.sh` 必须验证：

- domain 三个可运行 targets 的测试通过；
- domain 源码未导入平台、Ktor 或 SQLDelight API；
- domain 不依赖 shared，也不声明 framework；
- shared 对 Kotlin 消费者使用 `api(project(":domain"))`；
- shared 没有对 Apple framework `export` domain，也没有 `transitiveExport`；
- Apple header 保留 facade，并拒绝领域规则、用例、端口和状态类型。

Apple API gate 与 distribution gate同时拒绝 domain symbol 回归。

## 当前证据

- `domain`：JVM 18/18、Android Host 18/18、iOS x64 18/18；
- `shared`：JVM 21/21、Android Host 21/21、iOS x64 29/29；
- Android app 仍只声明 `implementation(project(":shared"))`，编译和 2 条 ViewModel tests 通过；
- Swift 6 complete strict-concurrency type-check 继续通过；
- Apple header 从模块化前的 2,267 行降至 888 行；
- header 中不存在 `DraftValidator`、`SaveDraftUseCase`、`ExperimentRepository`、`ExperimentLocalStore`、`RemoteExperimentsResult`、`DraftEditorStore` 或 `StudyStreakPolicy`；
- `ExperimentListClient`、`PocketLabFeature` 等 Apple facade 保持可见。

888 行不是“API 已经完美”的证明。SQLDelight 生成声明仍与 Apple facade 位于同一个 binary-producing module，因此仍会进入 header。它是后续把 infrastructure 拆为非导出依赖 module 的可测量理由，而不是本次偷偷扩大范围的借口。

## 后果

### 正面

- 领域依赖方向现在能由构建系统和脚本执行，而不只靠文档约定；
- use case tests 可以独立于数据库、HTTP 与平台 runtime 快速运行；
- Android 保留完整 Kotlin API，Swift 只得到产品级 facade；
- iOS 仍只集成一个 umbrella framework，不引入多个 Kotlin runtimes/type copies；
- Apple API surface 显著收窄；
- 未来可以分别衡量 domain 与 infrastructure 的编译、测试和所有权成本。

### 成本

- Gradle project、target tasks 与测试报告数量增加；
- 同名 package 跨 module 后，查找声明必须结合 module，而不能只看 package；
- `api` / `implementation` / Apple `export` 需要团队理解三种不同的传播语义；
- 修改公共 domain API 仍可能同时影响 Android 编译和 shared 的 Apple facade 实现；
- SQLDelight 生成 API 仍污染当前 umbrella header，尚未完成最终 apple-api/infrastructure 分离。

## 被拒绝的方案

### 继续保留一个 shared module，只整理 package

目录整洁不能阻止领域层导入基础设施，也不能建立独立构建和发布边界，因此拒绝。

### 每个业务 package 都拆一个 module

当前代码量不足以证明如此细粒度边界，反而会增加 Gradle 配置、依赖图和所有权协调成本，因此拒绝按文件夹机械拆分。

### domain 直接生成并交付第二个 Apple framework

Apple consumer 会面对多个 framework、重复依赖与潜在 Kotlin runtime/type identity 问题；官方也建议通常由一个 umbrella framework 聚合。因此拒绝。

### 把 domain 全量 export 到 umbrella framework

Swift 当前不需要 Repository、StateFlow state holder 或 sealed results；导出会把 Kotlin 内部模型变成 Apple 兼容承诺，并削弱既有 facade，因此拒绝。

### 开启 transitiveExport

它隐藏实际 export allowlist，并可能显著扩大 API 和 binary，因此拒绝。

### 把所有公共依赖都声明成 implementation

`StateFlow` 出现在 domain 公共签名，隐藏 coroutines compile dependency 会让 Kotlin 消费者得到不完整 classpath，因此拒绝。

## 重新评估条件

- SQLDelight/Ktor 声明继续扩大 Apple header 或造成兼容负担；
- data/network 团队需要独立所有权、版本或发布节奏；
- Gradle configuration/build profile 证明模块数量已成为主要成本；
- 多个 feature 需要独立按需交付；
- Swift Export 稳定并改变 module-to-module export 语义；
- dynamic framework、App Extension 或多宿主引入新的 runtime/linkage 约束；
- Android 不再需要通过 shared umbrella 传递 domain，而改为显式依赖多个 modules。

## 验证

- `scripts/verify-module-boundaries.sh` 通过；
- `scripts/verify-apple-api.sh` 通过；
- JVM、Android Host 与 iOS x64 聚合仍为 39/39/47；
- Android unit 与 Compose UI 保持 2/2、2/2；iOS UI 上一完整基线为 1/1，本轮在 destination 发现阶段被缺失的 iOS 26.5 runtime 阻断；
- Apple distribution gate 使用 0.14.0 / build 14 重新构建并验证 XCFramework。
