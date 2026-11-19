# ADR-0010：以 Apple facade 和 Swift value wrapper 管理导出 API

- 状态：Accepted
- 日期：2026-11-20
- 决策范围：PocketLab Kotlin/Native → Objective-C framework → Swift 6 API surface

## 背景

PocketLab 当前通过 Kotlin/Native static Objective-C framework 接入 SwiftUI。里程碑 8 已建立 callback/cancellation bridge，里程碑 9 接入 SwiftUI，里程碑 11 解释 Native runtime 与 ARC/GC 边界。

现有 Swift 调用仍直接看见 `IosExperimentListBridge`、`ExperimentRecord`、`RefreshExperimentsResult` sealed hierarchy、共同 enum 与字符串 failure。Swift ViewModel 需要运行时 `as?` 判断 Kotlin subtype，Kotlin reference payload 又需要宽泛的 `UncheckedSendable` 包装。

原始 framework header 有 2,834 行，且暴露 bridge 构造依赖、repository/use case 类型和底层 callback。能够生成 header 不能证明 API 对 Swift 安全、自然或可演进。

Swift Export 在 Kotlin 2.4 已支持 enums、sealed hierarchy、suspend 与 Flow，但官方仍标为 Alpha，breaking changes、direct integration 与 generic erasure 等限制仍需单独评估。

## 决策

### 导出 allowlist

- Kotlin `public` 不自动等于 Apple framework `public`；
- 模块内实现优先改为 `internal`；
- 仍需对 Kotlin 公开、但 Apple 不消费的 declarations 使用 `@HiddenFromObjC`；
- 隐藏接口时，同时隐藏公开实现类，保持继承图可见性一致；
- 当前单模块用 annotations 渐进收口，模块化阶段建立独立 `apple-api` facade module。

### Apple facade 类型

- Swift 列表不再直接消费共同 `ExperimentRecord`；
- `IosExperimentSnapshot` 只导出 Apple UI 需要的 immutable fields；
- 共同 `ExperimentSyncState` 不导出，当前 UI 语义收窄为 `isPendingUpload`；
- `RefreshExperimentsResult` 不直接导出，改为固定 primitive/string report；
- nullable status code 使用 presence flag + primitive value，避免 Objective-C nullable primitive boxing；
- bridge failure 使用稳定 machine code + diagnostic message。

### Swift 名称与 refinement

- 使用 `@ObjCName(swiftName = ...)` 把实现名转换为能力名；
- `IosExperimentListBridge` 在 Swift 中是 `ExperimentListClient`；
- raw subscribe/refresh callback 使用 `@ShouldRefineInSwift`，只作为 `__subscribe` / `__refresh` wrapper primitive；
- 这些 annotations 当前为 experimental，Kotlin 升级必须重跑 header 与 Swift source gate。

### Swift 产品 API

- callback payload 立即复制为 `Experiment: Identifiable, Sendable`；
- refresh report 映射为穷尽 `RefreshOutcome` 与 `RefreshRejection`；
- stable failure code 未识别时进入 `.unknown(code:statusCode:)`，支持版本退化；
- `Flow` 继续映射为 `AsyncThrowingStream`，终止继续传播到 Kotlin cancellation token；
- `@unchecked Sendable` 只保留在受 `NSLock` 保护的 token box，不包裹业务 payload；
- SwiftUI 文案、日期与 UI state 继续属于平台层。

### 错误语义

- 预期 refresh failure 是 `RefreshOutcome.rejected` value；
- 意外 observation/refresh exception 转成 `PocketLabClientError`；
- cancellation 是控制流，不显示为用户错误；
- Kotlin 不变量错误不统一吞成普通网络失败；
- 不为了统一形式滥用 `@Throws`，只有声明清晰异常契约时才采用。

### API 门禁

`scripts/verify-apple-api.sh` 必须：

- 构建 framework；
- 断言预期 Swift names；
- 断言 raw callback 为 `swift_private`；
- 拒绝共同 record、sealed result 和内部构造器泄漏；
- 用 Swift 6 complete strict concurrency type-check 完整 App source。

Adapter tests 继续验证 snapshot、report、failure code、cancellation 与 close；XCTest UI 验证真实 App 行为不变。

### Swift Export

- 当前不替换稳定 Objective-C framework 发布路径；
- 允许在独立实验分支/module 并行验证；
- 只有在所需类型、并发、依赖、构建和迁移门禁全部通过后再决定切换；
- 不把 Alpha 标签作为永久拒绝理由，也不把新映射能力当作立即迁移理由。

## 当前证据

### Header surface

- 原始 header：2,834 行；
- 加 Apple DTO、尚未隐藏共同类型：2,888 行；
- 收窄共同类型图后：2,267 行；
- `ExperimentRecord`、`RefreshExperimentsResult`、`initWithLocalStore` 不再出现；
- raw callbacks 带 `swift_private`；
- Apple facade KDoc 出现在 header。

行数不是质量目标；symbol 与 signature diff 才是契约证据。

### Swift API

- Swift ViewModel 不再使用 `any RefreshExperimentsResult` 或 subtype casts；
- SwiftUI 不再依赖 Kotlin `ExperimentRecord` / `ExperimentSyncState`；
- stream payload 是 Swift Sendable value array；
- refresh 使用可穷尽 Swift switch；
- Swift 6 complete strict-concurrency type-check 通过。

### Tests

- 新增 Apple snapshot mapping 与 stable failure/status mapping 两个 iOS 场景；
- JVM 39/39、Android Host 39/39、iOS x64 47/47；
- Android ViewModel 2/2、Compose UI 2/2、iOS UI 1/1；
- 54 个不同场景，130/130 次目标执行。

## 后果

### 正面

- Swift source 不再理解 Kotlin sealed hierarchy、共同 enum 或数据库 record；
- 实现命名、构造依赖和 raw callbacks 从推荐 API 消失；
- Swift 6 静态并发检查覆盖业务 payload；
- stable code 与 unknown case 支持 framework/App 版本差异；
- header、Swift source 与 runtime 都有自动证据；
- 未来切换 Swift Export 时有同一套质量基准。

### 成本

- Apple snapshot/report 与 Swift value/enum 产生明确的边界映射代码；
- list 更新会复制为 Swift value array；
- experimental export annotations 需要 Kotlin 升级审计；
- header pattern gate 不是完整 ABI checker，需要后续引入 normalized API snapshot；
- 当前单模块仍有其他共同 declarations 出现在 header，最终需 facade module 物理隔离；
- Intel-only script 在迁往 arm64 host 时必须同步更新。

## 被拒绝的方案

### 直接把所有共同 public declarations 导出

会把 repository、database、sealed hierarchy 与依赖类型固定成 Apple contract，因此拒绝。

### 只在 Swift 写 extension，不收窄 Kotlin export

能改善调用语法，却让 raw callbacks 和实现 types 继续污染补全与 ABI，因此拒绝。

### 让 Swift 继续动态 cast Kotlin sealed subtypes

缺少穷尽检查，新增 subtype 容易静默进入 fallback，因此拒绝。

### 用一个全局 `@unchecked Sendable` 包住所有 Kotlin payload

会关闭最需要的 Swift 6 数据竞态检查，因此拒绝。豁免仅限 token synchronization box。

### 所有失败统一抛异常

会把预期协议结果变成异常控制流，并丢失稳定领域语义，因此拒绝。

### 立刻切换 Swift Export

其能力方向符合目标，但当前仍为 Alpha，且需要验证完整 module surface、依赖和迁移成本，因此先平行实验。

## 重新评估条件

- Apple API 数量增长到 annotations 难以维护；
- shared module 拆分进入里程碑 14；
- Swift Export 达到 Beta/Stable 或当前 blockers 消失；
- 需要面向多个外部 iOS App 进行 binary distribution；
- header diff 出现频繁非预期变化；
- Instruments 显示 snapshot copy 成为实际瓶颈；
- framework 与 Swift shell 需要独立版本发布。

## 验证

- `scripts/verify-apple-api.sh` 通过；
- JVM 39/39、Android Host 39/39、iOS x64 47/47；
- Android ViewModel 2/2、Android Compose UI 2/2、iOS XCTest UI 1/1；
- 54 个不同场景，130/130 次自动化执行；
- SQLDelight migration verifier、Debug/Release framework 与最终 Xcode link 继续通过。
