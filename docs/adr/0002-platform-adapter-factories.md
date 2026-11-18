# ADR-0002：用 `expect/actual` 创建薄平台适配器

- 状态：已接受
- 日期：2026-11-18
- 决策范围：PocketLab 的生产 Clock 与 ID Generator 装配

## 背景

`SaveDraftUseCase` 已依赖普通的 `EpochClock` 与 `ExperimentIdGenerator` 接口。commonTest 注入确定性 fake；生产运行时需要 JVM/Android 的系统时间与 UUID，以及 iOS Core Foundation 的绝对时间与 Foundation 的 NSUUID。

## 决策

在 `commonMain` 声明两个 `expect` 工厂函数：

```kotlin
expect fun createPlatformClock(): EpochClock
expect fun createPlatformIdGenerator(): ExperimentIdGenerator
```

在 JVM、Android 与 iOS source set 使用平台 API提供对应 `actual`。共同工厂 `createPlatformSaveDraftUseCase(repository)` 只负责组合依赖。

领域模型、接口和用例不使用 `expect/actual`。测试仍可直接构造 `SaveDraftUseCase` 并注入 fake。

## 理由

- 平台差异很薄，只存在于适配器创建位置。
- 编译器保证每个 target 都提供实际工厂。
- 返回普通接口，保留多个实现和测试替换能力。
- common code 不需要认识 Java UUID、NSDate 或 NSUUID。
- `expect class` 当前仍是 Beta，且会把完整实现形状耦合到语言机制；本问题不需要它。

## 后果

正面：

- 每个 target 缺少工厂实现时在编译期失败。
- 平台 API 被限制在三个小文件中。
- 共同契约测试会在各 target 调用真实 `actual`。

代价：

- JVM 与 Android 的简单实现存在少量重复。
- `expect` 工厂形成静态装配路径；需要运行时选择实现的场景仍应使用 DI/显式参数。
- 当前时间直接依赖系统时钟，后续生产同步需要考虑时钟偏移；领域测试继续使用 fake。

## 拒绝的方案

### `expect class PlatformDependencies`

扩大了 expected declaration 的表面积，并把多个不相关能力捆绑在一起。新增一个依赖会迫使所有平台修改同一个大类。

### 在 `SaveDraftUseCase` 内直接调用平台函数

会让领域用例难以注入固定时间与 ID，破坏确定性测试。

### 所有生产装配都交给 `expect/actual`

静态平台匹配不适合环境切换、A/B 实现或测试替换。这里仅用它跨越最薄的编译期平台接缝。

