# 实验 12：把“能从 Swift 调用”改造成“像 Swift 一样使用”

## 实验目标

完成本实验后，你应该能用生成物和调用代码回答：

1. Kotlin `public` 为什么不等于 Apple framework `public`？
2. `@HiddenFromObjC`、`@ObjCName` 与 `@ShouldRefineInSwift` 分别解决什么问题？
3. Kotlin sealed result、enum、nullable primitive 和 collection 到 Swift 后会损失什么？
4. 为什么 Apple 专用 DTO 有时比直接复用共同 data class 更安全？
5. callback、`AsyncSequence`、cancellation token 与 Swift `Sendable` 如何组合？
6. 如何把生成 header、Swift 6 type-check 与运行测试变成 API 门禁？
7. 2026 年的 Swift Export 已经能做什么，为什么当前发布路径仍保留 Objective-C framework？

## 前置条件

- 已完成[实验 11](../11-kotlin-native-runtime/README.md)。
- JDK 17、Xcode、Kotlin/Native toolchain 与 `rg` 可用。
- 当前 Intel Mac 使用 `iosX64`；Apple Silicon 应把 target 与 Swift `-target` 改为 arm64 Simulator。
- 主工程位于 `../02-first-kmp-module`。

## 实验地图

```text
common model / use case
        │ @HiddenFromObjC
        ▼
Apple facade + snapshot/report
        │ @ObjCName
        ▼
Objective-C header
        │ @ShouldRefineInSwift
        ▼
hidden raw callbacks (__subscribe / __refresh)
        │ Swift extension
        ▼
AsyncSequence + native Swift struct / enum
        │
        ▼
SwiftUI ViewModel
```

## 第一步：记录改造前的真实 Swift surface

先构建里程碑 11 的 framework，或用 Git 查看上一提交的 header 证据：

```shell
cd ../02-first-kmp-module
./gradlew :shared:linkReleaseFrameworkIosX64
```

检查：

```shell
HEADER=shared/build/bin/iosX64/releaseFramework/\
PocketLabShared.framework/Headers/PocketLabShared.h

wc -l "$HEADER"

rg -n \
  'IosExperimentListBridge|RefreshExperimentsResult|ExperimentRecord|subscribeOnChange' \
  "$HEADER"
```

里程碑 11 的基线是 2,834 行。关键 API 包含：

```text
IosExperimentListBridge
NativeCancellable
NSArray<ExperimentRecord *>
id<RefreshExperimentsResult>
onFailure: (NSString *)
```

不要先评价 Kotlin 源码；只从 Swift 消费者角度列问题。

## 第二步：画出允许导出的最小集合

PocketLab 的 SwiftUI 列表真正需要：

```text
PocketLabFeatures.demo(databaseName:)
PocketLabFeature.experimentList
ExperimentListClient.experiments()
ExperimentListClient.refreshOnce()
Experiment.id/title/hypothesis/notes/time/pending
RefreshOutcome
close / cancellation
```

它不需要：

```text
ExperimentLocalStore
SqlDelightExperimentRepository
RefreshExperimentsUseCase
RefreshExperimentsResult sealed hierarchy
ExperimentSyncState
Kotlin Flow
```

把两组列表写下来。没有 allowlist，之后就无法判断 header 中新增一个 public type 是功能还是泄漏。

## 第三步：隐藏共同实现图

共同 Kotlin API 仍需 public，但 Apple framework 不消费的类型使用：

```kotlin
@HiddenFromObjC
data class ExperimentRecord(...)

@HiddenFromObjC
interface ExperimentLocalStore : ExperimentRepository

@HiddenFromObjC
sealed interface RefreshExperimentsResult
```

同时把 bridge 的组装构造器改为 `internal`：

```kotlin
internal constructor(
    localStore: ExperimentLocalStore,
    refreshExperiments: RefreshExperimentsUseCase,
)
```

### 观察一次有价值的编译失败

如果只隐藏 `ExperimentLocalStore`，却不隐藏公开的 `SqlDelightExperimentRepository`，Native 编译会报：

```text
Only '@HiddenFromObjC' declaration can be a subtype
of '@HiddenFromObjC' declaration.
```

给实现类也加 `@HiddenFromObjC` 后再构建。

结论：可见性约束沿继承图传播，不是文本过滤器。

## 第四步：用调用者语言重新命名

Kotlin 实现类保留明确的内部命名，Swift 名称改为能力名：

```kotlin
@ObjCName(swiftName = "ExperimentListClient")
class IosExperimentListBridge

@ObjCName(swiftName = "PocketLabFeature")
class IosPocketLabFeature

@ObjCName(swiftName = "CancellationToken")
class NativeCancellable
```

重新构建后检查：

```shell
rg -n 'swift_name\("(ExperimentListClient|PocketLabFeature|CancellationToken)' \
  "$HEADER"
```

注意 Objective-C symbol 仍可叫 `PLSIosExperimentListBridge`；Swift source name 已经是 `ExperimentListClient`。

`@ObjCName` 当前需要 experimental opt-in。把 framework header 与 Swift compile 作为升级门禁。

## 第五步：建立 Apple snapshot

文件：

```text
shared/src/iosX64Main/kotlin/
  dev/kmpjourney/pocketlab/interop/AppleApiModels.kt
```

它不直接暴露共同 `ExperimentRecord`，而是：

```kotlin
@ObjCName(swiftName = "ExperimentSnapshot")
class IosExperimentSnapshot internal constructor(
    val id: String,
    val title: String,
    val hypothesis: String,
    val notes: String,
    val updatedAtEpochMilliseconds: Long,
    val isPendingUpload: Boolean,
)
```

观察两个设计选择：

- constructor 为 internal，Swift 只能消费 Kotlin 产生的 snapshot；
- UI 只需要 pending Boolean，因此不导出共同 enum。

在 Swift callback 中立即复制为：

```swift
struct Experiment: Identifiable, Sendable
```

这样 `AsyncThrowingStream` 的 element 不再需要 `UncheckedSendable<[ExperimentRecord]>`。

## 第六步：把 sealed result 改成稳定 report

Kotlin Objective-C export 不会把 sealed interface 恢复成原生、穷尽 Swift enum。

边界改为：

```kotlin
@ObjCName(swiftName = "RefreshReport")
class IosRefreshReport internal constructor(
    val isSuccessful: Boolean,
    val receivedCount: Int,
    val changedCount: Int,
    val failureCode: String?,
    val hasFailureStatusCode: Boolean,
    val failureStatusCode: Int,
)
```

Swift wrapper 再恢复平台 enum：

```swift
enum RefreshOutcome: Sendable, Equatable {
    case updated(changedCount: Int32)
    case unchanged
    case rejected(RefreshRejection)
}
```

这里使用 `hasFailureStatusCode + failureStatusCode`，避免 Kotlin `Int?` 在 Objective-C 中变成 boxed number。

挑战：临时改回 `Int?`，比较 header。

## 第七步：把 raw callback 标成 refinement primitive

Kotlin 方法加：

```kotlin
@ShouldRefineInSwift
fun subscribe(...): NativeCancellable

@ShouldRefineInSwift
fun refresh(...): NativeCancellable
```

header 应包含：

```text
__attribute__((swift_private))
```

Swift wrapper 仍能调用：

```swift
client.__subscribe(...)
client.__refresh(...)
```

但它们不再与 `experiments()` / `refreshOnce()` 一起污染正常补全。

注意：annotation 不会自动生成 async wrapper。取消、buffering 和错误翻译仍由 Swift extension 负责。

## 第八步：给错误建立机器码

意外 bridge failure 不再只传字符串：

```kotlin
@ObjCName(swiftName = "InteropFailure")
class IosInteropFailure internal constructor(
    val code: String,
    val message: String,
)
```

当前 code：

```text
observation_failed
refresh_failed
```

预期业务失败仍进入 `RefreshOutcome.rejected`，不作为 exception。

Swift `RefreshRejection` 保留：

```swift
case unknown(code: String, statusCode: Int32?)
```

这用于处理新版 framework 与旧 Swift 壳的版本差异。

## 第九步：验证生成 header 的变化

构建：

```shell
./gradlew :shared:linkDebugFrameworkIosX64
```

检查：

```shell
HEADER=shared/build/bin/iosX64/debugFramework/\
PocketLabShared.framework/Headers/PocketLabShared.h

wc -l "$HEADER"

rg -n \
  'ExperimentListClient|ExperimentSnapshot|RefreshReport|InteropFailure|swift_private' \
  "$HEADER"

rg -n \
  'ExperimentRecord|RefreshExperimentsResult|initWithLocalStore' \
  "$HEADER"
```

本次阶段数据：

| 阶段 | header 行数 |
|---|---:|
| 原始 | 2,834 |
| 加 Apple DTO、尚未隐藏共同类型 | 2,888 |
| 收窄后 | 2,267 |

最后一条 `rg` 应无结果。行数不是质量分，但 surface diff 是证据。

## 第十步：运行自动 API 门禁

执行：

```shell
scripts/verify-apple-api.sh
```

脚本会验证：

- `ExperimentListClient`、`ExperimentSnapshot`、`RefreshReport` 等 Swift names 存在；
- callback 标有 `swift_private`；
- 共同 record、sealed result 和内部构造器没有泄漏；
- Swift 6 complete strict concurrency 能 type-check 完整 App source。

预期：

```text
Apple API contract and Swift 6 type-check passed.
```

当前脚本针对 Intel `iosX64`。迁往 Apple Silicon 时，需要把 framework task/path 与 Swift target 一起迁移；不能只改一个字符串。

## 第十一步：运行 Kotlin adapter tests

新增两个 iOS 专用测试：

```text
subscriptionMapsSharedRecordToAStableAppleSnapshot
failedRefreshUsesStableCodeAndUnboxedStatusContract
```

运行：

```shell
./gradlew :shared:iosX64Test
```

第一个证明共同 record 与 enum 被 Apple snapshot 隔离；第二个证明 503 被映射为：

```text
failureCode=server
hasFailureStatusCode=true
failureStatusCode=503
```

## 第十二步：运行 SwiftUI 端到端验证

如果 Xcode 能直接发现 Simulator：

```shell
GRADLE_USER_HOME="$PWD/../.gradle-user-home" \
xcodebuild test \
  -project iosApp/PocketLabIos.xcodeproj \
  -scheme PocketLabIos \
  -destination 'platform=iOS Simulator,id=<device-id>' \
  -derivedDataPath build/xcode-derived-data \
  CODE_SIGNING_ALLOWED=NO
```

UI test 应继续验证：

- 3 条 snapshot 被 Swift value model 渲染；
- 首次刷新报告更新 3 条；
- 再次刷新得到 `.unchanged`；
- 取消和 close 没有改变用户行为。

## 第十三步：核对完整测试数字

当前基线：

| 来源 | 不同场景 | 执行 |
|---|---:|---:|
| commonTest | 38 | 114 |
| JVM / Android Host 平台 KMP | 2 | 2 |
| iOS 平台 / interop / GC / API | 9 | 9 |
| Android ViewModel | 2 | 2 |
| Android Compose UI | 2 | 2 |
| iOS UI | 1 | 1 |
| **总计** | **54** | **130/130** |

目标分布：JVM 39、Android Host 39、iOS x64 47、Android 壳 4、iOS UI 1。

## Swift Export 对照实验

Kotlin 2.4 当前 Swift Export 已支持 native Swift enum、sealed hierarchy、suspend、Flow `AsyncSequence`、packages 与多模块。

但它仍是 Alpha，并有 direct integration、generic erasure 和迁移工具等限制。

不要直接替换现有发布路径。建立平行实验：

1. 配置单独的 Swift Export module name；
2. 用同一组场景生成 API；
3. 对照类型穷尽性、Sendable、包名、泛型与取消；
4. 让现有 Xcode App 在实验分支编译；
5. 用 API diff 与迁移成本决定是否切换。

## 挑战题

1. 去掉一个 `@HiddenFromObjC`，让门禁准确失败；
2. 给 `RefreshExperimentsResult` 新增 subtype，并设计旧 Swift 壳的退化行为；
3. 把 snapshot 改回 `ExperimentRecord`，记录 Swift 6 所需的 `@unchecked Sendable` 范围；
4. 为 `PocketLabClientError.code` 建立平台文案映射，不直接显示 Kotlin diagnostic message；
5. 给 API gate 增加 normalized header snapshot，而不是只检查 allow/deny symbols；
6. 将 Apple facade 拆到独立 module，比较 header 行数与依赖图；
7. 用 Swift Export 重做同一 API，写一份逐项迁移报告。

## 验收标准

只有当你能解释下面这段话，才算完成：

> Kotlin public API、Objective-C header 和 Swift 产品 API 是三个不同层次。先用 internal / HiddenFromObjC 决定可见性，再用 ObjCName、Apple DTO 与 refinement primitive 塑造稳定边界，最后在 Swift 中恢复 value、enum、async 与 Sendable 语义。生成物必须由 header gate、Swift 6 type-check、adapter tests 和 UI runtime 共同验证。

对应决策记录：[ADR-0010](../../docs/adr/0010-swift-api-surface.md)

对应文章：[为 Swift 设计 Kotlin API：能导出，不等于好用](../../articles/12-designing-kotlin-api-for-swift.md)
