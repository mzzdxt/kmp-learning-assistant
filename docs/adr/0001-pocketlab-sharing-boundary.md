# ADR-0001：PocketLab 首个功能的共享边界

- 状态：已接受
- 日期：2026-11-18
- 决策范围：创建并保存实验草稿

## 背景

PocketLab 的 Android 与 iOS 客户端都需要创建草稿。校验、规范化、ID/时间赋值与“校验失败不得写入”的行为必须保持一致；UI、文案和最终持久化技术可能按平台或后续选型变化。

## 决策

放入 `commonMain`：

- `ExperimentDraft`、`SavedExperiment` 领域数据。
- `DraftValidator` 与稳定的 field/reason code。
- `SaveDraftUseCase` 编排行为。
- `ExperimentRepository`、`ExperimentIdGenerator`、`EpochClock` 普通 Kotlin 接口。

留在平台/基础设施层：

- 仓库、ID 生成器和时钟的生产实现。
- Android Compose / iOS SwiftUI 表单与错误文案映射。
- 导航、生命周期与系统能力。

本功能不使用 `expect/actual`。这些外部能力有状态、需要 fake，并且未来可能在同一平台提供多个实现；普通接口更适合依赖替换和测试。

## 理由

共享边界按“变化原因”划分：产品规则变化时两端应一起变；数据库、系统 API 或 UI 体验变化时不应迫使领域代码跟随变化。该边界允许先用内存 fake 验证用例，之后再选择 SQLDelight、Room KMP 或平台数据库实现。

## 后果

正面：

- 规则只有一个事实来源。
- commonTest 可以验证副作用顺序，而不仅是字符串校验。
- 平台层只负责适配，不复制业务判断。
- 数据库选型可以延后，不阻塞领域用例。

代价：

- 平台入口需要显式装配接口实现。
- 接口跨 Swift 边界时仍需评估导出体验。
- 当前同步接口只是第一阶段设计；接入数据库与网络后会迁移到 `suspend`/`Flow`，需要新的并发契约。

## 拒绝的方案

### 所有类都用 `expect/actual`

会把领域模型绑定为“每个平台只能有一套实现”，测试替换困难，也没有解决真正的平台差异。

### 直接把 Android Repository 搬进 commonMain

Android 类型和生命周期不是共同契约；即使通过包装隐藏类型，也会把平台假设带进共享层。

### 先选数据库，再设计用例

会让表结构和驱动 API 主导领域边界。当前需求只需要“保存一个已验证记录”的能力，数据库是实现细节。

