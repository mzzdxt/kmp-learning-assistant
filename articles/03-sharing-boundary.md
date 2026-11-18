# `commonMain` 不是公共垃圾场：哪些代码值得共享？

> 面向读者：已经能运行 KMP 工程，开始思考真实项目架构的 Android 开发者  
> 技术核对与三端验证日期：2026-11-18

KMP 项目跑起来以后，团队很快会进入一个危险阶段：

> “这个类没有直接引用 `Context`，是不是也能搬进 `commonMain`？”

搬一个，能编译。再搬十个，复用率上升。几个月后，`commonMain` 里住进了网络、数据库、埋点、导航、格式化、权限包装器和一个叫 `PlatformManager` 的庞然大物。

Android 与 iOS 的代码确实少了，任何平台改动却都要穿过共享层。团队得到的不是复用，而是一栋所有房间共用同一只电闸的房子。

问题出在哪里？

**能被共同编译，只是语法条件；值得共同维护，才是架构条件。**

这一篇，我们用 PocketLab 的第一个真实用例“保存实验草稿”，建立一套可以反复使用的共享边界判断法。

## `commonMain` 只给了你能力边界，没有替你做架构决策

从编译器视角看，代码只要满足这些条件，就可能进入 `commonMain`：

- 使用公共 Kotlin 语言与标准库能力；
- 依赖支持所需 targets；
- 不直接引用只存在于单个平台的 API；
- 所有 `expect` 都能在目标平台找到匹配的 `actual`。

但编译器回答不了这些问题：

- Android 与 iOS 对“正确”的定义真的一样吗？
- 两端会因为同一个原因修改这段代码吗？
- 为了共享而创建的抽象，比两端各写几行代码更稳定吗？
- 这个 API 导出到 Swift 后还自然吗？
- 故障发生时，哪一层团队负责？

所以，`commonMain` 不是“所有平台都能编译的代码集合”那么简单。它还是团队对一件事的承诺：

> 这段语义在所有目标上应该保持一致，并由同一个变化过程维护。

## 先问五个问题，再问“能不能共享”

面对一个候选职责，按顺序问：

### 1. 正确性定义是否相同？

“标题去除空白后不能为空”在 Android 与 iOS 上没有不同解释，适合共享。

“用户点击返回时发生什么”则受导航栈、手势和平台体验影响，不一定相同。

### 2. 它们是否因为同一个原因变化？

产品把标题上限从 80 改成 100，两端必须一起变化——这是共同业务规则。

iOS 改用新的 Keychain API，不应迫使 Android 重新发布——这是平台实现变化。

### 3. 平台差异是“能力实现不同”，还是“语义本身不同”？

读取当前时间：需要的语义相同，实现来源可能不同。可以在共同用例中依赖 `Clock` 接口。

权限请求：不仅 API 不同，授权模型、弹窗时机和拒绝后的 UX 也不同。通常应保留在平台层，只把授权结果转换为共享层需要的业务状态。

### 4. 抽象能否用平台无关的稳定类型表达？

下面的接口很稳定：

```kotlin
interface EpochClock {
    fun nowMilliseconds(): Long
}
```

如果接口参数不得不塞进 `Any`、平台对象包装器，或充满 `isAndroid` 开关，说明边界位置可能错了。

### 5. 共享之后，测试和所有权是否更清楚？

共享校验规则后，一组测试能在多个编译后端执行，收益明确。

共享一个只有 iOS 会修改的 UIKit 包装层，只会让所有权变模糊。

这五问比“代码复用率能提高多少”更接近真实维护成本。

## PocketLab：先从用户规则，而不是数据库开始

PocketLab 是一个离线优先的个人实验记录器。第一条旅程很简单：

```text
输入标题与假设 → 校验 → 生成 ID 和时间 → 保存草稿
```

产品规则是：

- 标题必填，最长 80 个字符；
- 假设必填，最长 240 个字符；
- 笔记可为空；
- 保存时去除首尾空白；
- 校验失败时，不得生成 ID、读取时钟或写入仓库。

这些规则在两端必须一致。于是它们进入 `commonMain`。

注意我们的起点不是：“选 SQLDelight 还是 Room KMP？”

数据库是实现手段。领域真正需要的能力只有一句话：

```kotlin
interface ExperimentRepository {
    fun save(experiment: SavedExperiment)
}
```

先表达需要什么，再决定谁来做。这个顺序让数据库选型不再阻塞业务用例。

## 共享模型要保存语义，不要偷偷保存 UI

草稿模型只描述用户输入：

```kotlin
data class ExperimentDraft(
    val title: String,
    val hypothesis: String,
    val notes: String,
)
```

校验失败时，我们没有直接返回“标题不能为空”：

```kotlin
enum class DraftField {
    TITLE,
    HYPOTHESIS,
}

enum class DraftProblemCode {
    REQUIRED,
    TOO_LONG,
}

data class DraftProblem(
    val field: DraftField,
    val code: DraftProblemCode,
    val maximumLength: Int? = null,
)
```

为什么不共享错误文案？

因为“标题不能为空”同时包含两件事：

1. `TITLE + REQUIRED`：稳定业务语义。
2. 如何翻译、展示、朗读、排版：平台 UI 和本地化策略。

共享第一部分，两端各自把它映射为资源字符串。这样业务规则仍只有一个事实来源，SwiftUI 与 Compose 也不必接受一个为另一端设计的展示模型。

## 用例共享的是行为顺序，不只是几条 if

很多所谓共享层只有 data class 和 Validator，真正的业务顺序仍在两端 ViewModel 里各写一份。

PocketLab 把保存顺序也做成共同用例：

```kotlin
class SaveDraftUseCase(
    private val repository: ExperimentRepository,
    private val idGenerator: ExperimentIdGenerator,
    private val clock: EpochClock,
    private val validator: DraftValidator = DraftValidator(),
) {
    fun execute(draft: ExperimentDraft): SaveDraftResult {
        val problems = validator.validate(draft)
        if (problems.isNotEmpty()) {
            return SaveDraftResult.Rejected(problems)
        }

        val experiment = SavedExperiment(
            id = idGenerator.nextId(),
            title = draft.title.trim(),
            hypothesis = draft.hypothesis.trim(),
            notes = draft.notes.trim(),
            savedAtEpochMilliseconds = clock.nowMilliseconds(),
        )
        repository.save(experiment)
        return SaveDraftResult.Saved(experiment)
    }
}
```

这段代码共享了一个重要不变量：**先验证，后发生副作用。**

如果两端只共享 Validator，Android 可能先生成 ID 再校验，iOS 可能校验后仍读取时钟。功能表面一样，埋点、序列号和测试行为已经分叉。

共享有价值的往往不是代码行，而是不可分叉的业务顺序。

## 为什么这里优先普通接口，而不是 `expect/actual`？

我们需要三类外部能力：

```kotlin
interface ExperimentRepository {
    fun save(experiment: SavedExperiment)
}

interface ExperimentIdGenerator {
    fun nextId(): String
}

interface EpochClock {
    fun nowMilliseconds(): Long
}
```

它们有几个共同特点：

- 测试中需要替换；
- 一个平台可能存在多个实现；
- 实现可能随环境变化，而不只随 target 变化；
- 它们是用例依赖，不是 Kotlin 编译器必须知道的平台事实。

普通接口允许 Android 同时拥有数据库实现、内存实现和迁移实现，也允许 commonTest 注入 fake。

如果把整个 Repository 写成 `expect class`，每个 target 更像被强制绑定到一个“官方实现”。这没有增加跨平台能力，反而削弱了替换性。

可以用这个经验判断：

| 场景 | 优先选择 |
|---|---|
| 薄、稳定、编译期唯一的平台事实 | `expect/actual` 函数或属性 |
| 有状态、需 fake、可能有多个实现 | 普通接口 + 平台装配 |
| 完全相同的纯规则 | 普通 common Kotlin |
| 语义和体验都因平台而异 | 留在平台层 |

`expect/actual` 是语言机制，不是架构层，也不是依赖注入框架。

## “接口在 commonMain”不等于“实现必须平台专属”

这里还有一个容易过早决定的问题。

我们现在只把 `ExperimentRepository` 接口放进共同层。未来实现有多种可能：

- 使用支持 KMP 的数据库库，让大部分实现也在 common source set；
- 共享查询和映射，但在 Android/iOS 注入不同驱动；
- 两端使用完全不同的持久化实现；
- 测试与 Preview 使用内存实现。

接口边界保留了这些选择。它没有预先承诺“实现一定不同”，只承诺领域不会直接依赖某个驱动 API。

好的抽象不是替未来猜答案，而是让未来仍有选择。

## 哪些东西明确没有进入共同层？

我们做了一张边界表：

| 职责 | 归属 | 原因 |
|---|---|---|
| 标题/假设校验 | `commonMain` | 两端正确性定义相同 |
| 保存顺序 | `commonMain` | 共同业务不变量 |
| Repository/Clock/ID 接口 | `commonMain` | 稳定能力契约 |
| 错误 reason code | `commonMain` | 共同语义 |
| 本地化错误文案 | 原生 UI | 翻译与呈现策略 |
| 表单布局和键盘行为 | Android/iOS UI | 平台体验不同 |
| 导航与返回手势 | Android/iOS UI | 生命周期和交互不同 |
| 数据库驱动 | 基础设施层 | 技术选型尚未确定 |
| 权限请求 | 平台层 | API 与 UX 都不同 |

注意，“暂不共享”不是失败。它是一个明确决策：共享收益不足以支付耦合成本。

## 三种常见的伪共享

### 1. 用包装器把平台类型伪装成公共类型

```kotlin
class SharedContext(val raw: Any)
```

`Any` 没有消除平台依赖，只是让编译器不能帮助你。调用方仍然知道里面装的是 Android Context 或 iOS 对象。

更好的边界是表达真正需要的能力，例如 `FileStore.write(bytes)`，而不是传递“万能环境”。

### 2. 把平台分支集中到一个 God Object

```kotlin
if (platform.isAndroid) { ... } else { ... }
```

偶尔的分支并非原罪，但如果业务代码不断询问平台身份，说明平台差异没有被放到边界实现中。共同规则开始知道太多部署细节。

### 3. 为提高复用率共享不稳定的 UI 状态

把所有导航事件、弹窗状态、键盘焦点和平台手势塞进一个共享 ViewModel，短期看代码复用率很高，长期可能迫使两端采用最低公分母体验。

真正的问题不是“共享 ViewModel 对不对”，而是它包含的状态是否具有相同语义和生命周期。可共享的是决策，不一定是每个像素级交互。

## 测试结果：这条边界带来了什么？

本次增加 6 条 commonTest：

- 3 条 Validator 边界测试；
- 3 条 SaveDraftUseCase 行为测试。

其中一条测试明确验证：校验失败时 Repository 没有写入，ID Generator 和 Clock 的调用次数都是 0。

运行：

```shell
./gradlew :shared:allTests
```

得到：

| 目标 | PocketLab 新测试 | 累计测试 | 失败 |
|---|---:|---:|---:|
| JVM | 6 | 11 | 0 |
| Android Host | 6 | 11 | 0 |
| iOS x64 Simulator | 6 | 11 | 0 |

6 条共享测试被三个目标的 test compilation 消费，所以新增的是 18 次真实目标执行。加上前一实验的测试，目前共 33 次，全部通过。

这才是共享的可度量收益之一：不是少写了多少行，而是一个业务不变量由同一组测试在多个目标上持续守护。

## 一份可以带回项目的决策清单

准备把一个职责放进 `commonMain` 前，逐项回答：

- [ ] Android 与 iOS 对它的正确性定义是否相同？
- [ ] 两端是否会因为同一个业务原因修改它？
- [ ] API 能否只使用稳定的公共类型？
- [ ] 依赖是否支持所有目标，并有可接受的维护状态？
- [ ] 共享后是否减少规则分叉，而不只是减少文件数量？
- [ ] 平台体验是否仍能独立演进？
- [ ] 测试能否证明共享带来的约束？
- [ ] Swift 调用体验是否可接受？
- [ ] 谁拥有这段共享代码，失败时谁负责？

前两项答不上来时，先别急着共享。一个小型重复实现，可能比一个错误的共同抽象更便宜。

## 最后的心智模型

我们可以把 KMP 代码分成四种：

```text
共同语义 + 共同实现 → common Kotlin
共同语义 + 不同实现 → common 接口 + 平台/基础设施实现
共同语义 + 薄平台事实 → 谨慎使用 expect/actual
不同语义 + 不同体验 → 保留原生
```

共享的目标从来不是让 `commonMain` 最大，而是让**真正不能分叉的东西只有一个事实来源**。

下一篇，我们会专门拆解 `expect/actual`：它像墙上的插座，适合让薄平台能力接入共同代码；但如果把整栋楼的电器都焊死在插座上，维护成本会迅速失控。

## 动手挑战

给 PocketLab 草稿增加 `tags: List<String>`，规则是：

- 去除首尾空白；
- 忽略空标签；
- 不区分大小写去重；
- 最多保留 5 个。

先写 commonTest，再决定规范化应该发生在模型、Validator 还是 UseCase。不要只提交代码，还要写一句“它为什么会和两端一起变化”。

## 参考资料

- [Use platform-specific APIs](https://kotlinlang.org/docs/multiplatform/multiplatform-connect-to-apis.html)
- [Expected and actual declarations](https://kotlinlang.org/docs/multiplatform/multiplatform-expect-actual.html)
- [Add dependencies to source sets](https://kotlinlang.org/docs/multiplatform/multiplatform-add-dependencies.html)
- [Kotlin Multiplatform project structure](https://kotlinlang.org/docs/multiplatform/multiplatform-discover-project.html)
- [Kotlin API guidelines for multiplatform libraries](https://kotlinlang.org/docs/api-guidelines-build-for-multiplatform.html)

