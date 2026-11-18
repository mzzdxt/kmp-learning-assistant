# 第一条跨越 Android 与 iOS 的数据流：从 `suspend` 到 `StateFlow`

> 面向读者：熟悉 Android Coroutines/Flow，第一次把异步状态放进 KMP 共同代码的开发者  
> 技术核对与三端验证日期：2026-11-18  
> 依赖版本：kotlinx.coroutines 1.11.0

前四篇里，PocketLab 已经能校验并保存一条实验草稿。但它仍然像一个命令行函数：调用 `execute()`，等待结果返回。

真实 UI 需要的不是一个最终答案，而是一段时间里的变化：

```text
空闲 → 保存中 → 成功
             ↘ 校验失败
             ↘ 存储失败
```

Android Compose 和 iOS SwiftUI 都需要观察这条数据流，重绘界面、禁止重复提交、显示字段错误，并在生命周期结束时停止工作。

这一篇，我们把同步用例升级成 KMP 共同状态机。重点不是背 `StateFlow` API，而是回答四个工程问题：

1. 谁拥有 CoroutineScope？
2. `suspend` 到底承诺了什么？
3. 什么应该成为状态，什么不应该？
4. 怎样在 JVM、Android、iOS 上确定性测试状态序列？

## 第一步：Repository 变成 suspend，但别脑补线程

原来的接口是同步的：

```kotlin
interface ExperimentRepository {
    fun save(experiment: SavedExperiment)
}
```

数据库和网络都可能挂起，所以改为：

```kotlin
interface ExperimentRepository {
    suspend fun save(experiment: SavedExperiment)
}
```

保存用例也随之变成 suspend：

```kotlin
suspend fun execute(draft: ExperimentDraft): SaveDraftResult
```

这里必须拆掉一个常见误解：

> `suspend` 不等于“自动开子线程”，也不等于“运行在 IO dispatcher”。

它只表示这个函数可以挂起，并在以后恢复执行。实际运行在哪个线程或执行器上，取决于调用方的 CoroutineContext，以及实现内部是否切换 context。

因此，我们没有在 UseCase 里写：

```kotlin
withContext(Dispatchers.IO) { ... }
```

领域用例并不知道 Repository 是 SQL、内存、网络，还是一个本身已经异步的 Native API。真正需要阻塞 I/O 调度的实现，应在基础设施边界处理；测试则可以完全使用 TestDispatcher。

把 dispatcher 写死在 UseCase 里，会让“业务顺序”和“执行策略”粘在一起。

## 第二步：先设计状态机，再写 Flow

我们需要的状态不是几个互不相关的 Boolean：

```kotlin
isLoading: Boolean
hasError: Boolean
isSaved: Boolean
```

三个 Boolean 能组合出八种情况，其中很多没有意义，例如同时 loading、error、saved。

先定义互斥状态：

```kotlin
enum class DraftSaveStatus {
    IDLE,
    SAVING,
    SAVED,
    REJECTED,
    FAILED,
}
```

再把页面渲染所需数据放进不可变 State：

```kotlin
data class DraftEditorState(
    val title: String = "",
    val hypothesis: String = "",
    val notes: String = "",
    val saveStatus: DraftSaveStatus = DraftSaveStatus.IDLE,
    val validationProblems: List<DraftProblem> = emptyList(),
    val savedExperiment: SavedExperiment? = null,
)
```

它表达的是“页面现在是什么样”，而不是“刚刚发生了什么”。新订阅者拿到当前值，就能完整渲染。

状态转换如下：

```text
                  ┌────────── REJECTED
IDLE ── save ──> SAVING ───── SAVED
  ^               └────────── FAILED
  | cancellation
  └───────────────────────────
```

从终态继续编辑，回到 IDLE。保存期间不接受第二次提交。

先画状态机的好处是：Flow 只是运输方式，业务允许哪些状态仍然清楚可见。

## 第三步：只暴露只读 StateFlow

Store 内部持有可变状态：

```kotlin
private val mutableState = MutableStateFlow(DraftEditorState())

val state: StateFlow<DraftEditorState> = mutableState.asStateFlow()
```

平台 UI 只能观察，不能这样做：

```kotlin
store.state.value = state.copy(saveStatus = SAVED)
```

为什么这很重要？

如果 Android 或 iOS 可以直接修改 MutableStateFlow，共享状态机就不再是事实来源。任何一端都能绕过校验，把页面写进不可能状态。

封装可变性不是 Kotlin 风格问题，而是跨平台契约问题。

## 第四步：Scope 由平台宿主拥有

Store 的构造函数是：

```kotlin
class DraftEditorStore(
    private val saveDraft: SaveDraftUseCase,
    private val scope: CoroutineScope,
)
```

它没有内部创建：

```kotlin
CoroutineScope(SupervisorJob() + Dispatchers.Main)
```

原因很直接：共同 Store 不知道自己的真正生命周期。

- Android 上可能跟随 ViewModel、Navigation entry 或某个 retained component。
- iOS 上可能跟随 ObservableObject、UIViewController 或手工持有的 presentation model。
- 测试中则跟随 TestScope。

谁知道生命周期，谁创建并取消 Scope。Store 只在这个 Scope 里启动任务。

这条规则也避免了最难查的一类泄漏：页面早已消失，共享 Scope 仍在持有 Repository、状态和回调。

## 原子进入 SAVING，阻止双击竞态

如果实现是：

```kotlin
if (state.value.saveStatus == SAVING) return
state.value = state.value.copy(saveStatus = SAVING)
```

“检查”和“写入”是两步。两个并发调用可能都在写入前看到 IDLE，于是同时保存。

Store 使用 `MutableStateFlow.compareAndSet`：

```kotlin
private fun beginSaveOrNull(): ExperimentDraft? {
    while (true) {
        val current = mutableState.value
        if (current.saveStatus == DraftSaveStatus.SAVING) return null

        val saving = current.copy(
            saveStatus = DraftSaveStatus.SAVING,
            validationProblems = emptyList(),
            savedExperiment = null,
        )
        if (mutableState.compareAndSet(current, saving)) {
            return ExperimentDraft(
                title = current.title,
                hypothesis = current.hypothesis,
                notes = current.notes,
            )
        }
    }
}
```

只有一个调用能把它看到的 current 原子替换为 saving。失败的调用重新读取状态，最终发现 SAVING 并返回 false。

这比在 Android Button 上加 `enabled = false` 更接近真正的约束。UI 禁用是体验；Store 防重是业务状态机的最后一道门。

## 启动协程后，怎样映射结果？

保存入口捕获开始时的草稿快照，然后启动协程：

```kotlin
fun save(): Boolean {
    val draft = beginSaveOrNull() ?: return false

    scope.launch {
        try {
            when (val result = saveDraft.execute(draft)) {
                is SaveDraftResult.Saved -> /* SAVED */
                is SaveDraftResult.Rejected -> /* REJECTED */
            }
        } catch (cancellation: CancellationException) {
            // 恢复状态，然后继续传播取消
            throw cancellation
        } catch (_: Exception) {
            // 映射成稳定 FAILED
        }
    }
    return true
}
```

两点值得注意。

### CancellationException 不能吞

协程取消是结构化并发的控制信号，不是普通业务失败。我们可以在退出前恢复 UI 状态，但必须重新抛出 cancellation，让上层 Scope 的取消语义继续生效。

如果 `catch (Exception)` 在前面并吞掉它，页面销毁后任务可能继续表现得像失败重试流程。

### 平台异常不要直接成为 UI API

当前 Store 把其他 Exception 映射为 `FAILED`，没有把 SQLException、NSError 或 Java IOException 塞进共同 State。

这只是第一步。接入数据库与网络后，我们会建立稳定的错误分类。但无论如何，UI 状态不应依赖某个平台的异常类型。

## 保存期间为什么暂时禁止编辑？

本实验选择保守策略：SAVING 期间字段更新被忽略。

否则会出现一个设计问题：用户修改了标题，但正在保存的是旧快照；保存完成时，页面该显示新草稿还是旧的 SavedExperiment？

生产应用可以采用 operation token、版本号或独立 draft/saved state 支持并发编辑。但在没有明确产品语义前，禁止编辑比悄悄覆盖更诚实。

这也说明状态机设计不是纯技术问题。每条并发策略背后都有 UX 选择。

## StateFlow、SharedFlow、Channel 怎么分？

这个阶段只需要一个简单判断：

### StateFlow：当前事实

- 始终有当前值；
- 新 collector 立即获得当前状态；
- 适合页面重建和声明式渲染；
- 相等值会被合并；
- collect 通常不会自行完成。

`DraftEditorState` 属于这一类。

### SharedFlow：广播发生过的值

适合多个订阅者观察广播，并可配置 replay/buffer。它不天然代表完整当前页面。

### Channel：逐条交付

更接近生产者—消费者队列，每个元素通常由一个接收者处理。

Toast、导航是否应使用 SharedFlow/Channel，不要凭口诀决定。先问：订阅者暂时不存在时，这个信息应该丢失、缓存还是成为持久状态？旋转屏幕后是否应再次处理？

本实验没有一次性事件，所以不为“以后可能需要”提前创建事件总线。

## 为什么 coroutines 依赖用了 `api`？

构建脚本中：

```kotlin
commonMain.dependencies {
    api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
}
```

因为公开 API 暴露了：

```kotlin
StateFlow<DraftEditorState>
```

消费 shared module 的代码必须能解析 StateFlow 类型，所以 coroutines Core 属于公开 API 依赖。

测试工具则只在测试编译中出现：

```kotlin
commonTest.dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
}
```

依赖的 `api`/`implementation` 不只是打包细节，它反映模块边界是否把某个库的类型暴露给消费者。

## 用虚拟时间测试状态序列

Repository fake 故意延迟一秒：

```kotlin
override suspend fun save(experiment: SavedExperiment) {
    calls += 1
    delay(1_000)
}
```

测试没有 `Thread.sleep(1000)`，而是：

```kotlin
@Test
fun successfulSaveEmitsIdleSavingAndSaved() = runTest {
    val store = createStore(repositoryWithDelay)
    val statuses = mutableListOf<DraftSaveStatus>()

    backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
        store.state
            .map { it.saveStatus }
            .distinctUntilChanged()
            .take(3)
            .toList(statuses)
    }

    store.save()
    assertEquals(SAVING, store.state.value.saveStatus)
    advanceUntilIdle()

    assertEquals(listOf(IDLE, SAVING, SAVED), statuses)
}
```

`runTest` 提供 TestScope 和虚拟时间调度器。`advanceUntilIdle()` 推进挂起任务直到队列空闲，所以一秒业务延迟不会变成一秒墙上时间。

比“测试最后是不是 SAVED”更重要的是，我们验证了消费者实际观察到的顺序。

## 为什么 commonTest 能在 iOS 上跑协程？

`kotlinx-coroutines-core` 与 `kotlinx-coroutines-test` 都提供多平台变体。commonTest 源码分别进入 JVM、Android Host 和 iOS x64 的 test compilation。

本次新增五条 Store 测试：

1. 编辑字段更新平台无关 State。
2. 成功路径发射 IDLE、SAVING、SAVED。
3. 无效输入进入 REJECTED，Repository 调用次数为 0。
4. Repository 异常进入 FAILED。
5. SAVING 期间第二次 save 返回 false，最终只写一次。

运行：

```shell
./gradlew :shared:allTests
```

结果：

| 目标 | 新增 Store 测试 | 累计测试 | 失败 |
|---|---:|---:|---:|
| JVM | 5 | 19 | 0 |
| Android Host | 5 | 19 | 0 |
| iOS x64 Simulator | 5 | 19 | 0 |

合计 57/57 通过。

## Android 与 iOS 还需要做什么？

共同 StateFlow 已经存在，不等于两端 UI 自动完成。

Android 需要在正确生命周期中 collect，例如把 Flow 转为 Compose State，并在界面离开时取消收集。

iOS 需要决定怎样把 Kotlin Flow 暴露为 Swift 友好的观察机制：可以手写包装，也可以评估生成 AsyncSequence/回调桥接的库。这个选择涉及取消、错误、线程和 Swift API 形状，后续会专门实验。

这里有一条重要的专家原则：

> “Kotlin 能导出给 Swift”只是互操作起点；“Swift 调用自然、生命周期正确”才是完成。

## 五个常见反模式

### 1. Store 自己创建永不取消的 Scope

页面消失后任务继续运行，持有状态与依赖。生命周期所有权变得不可见。

### 2. 所有 suspend 函数都包 `Dispatchers.IO`

不区分真正阻塞 I/O、异步 API 和纯计算，测试也被迫接触真实 dispatcher。

### 3. 暴露 MutableStateFlow

平台层能跳过用例直接篡改状态，共同状态机失效。

### 4. 用多个 Boolean 拼状态

产生互相矛盾的组合，竞态下更难恢复。

### 5. 只断言最终值

漏掉重复 SAVING、短暂错误、顺序颠倒等 UI 真正能观察到的问题。

## 一张边界图

```text
Android Compose                    iOS SwiftUI
      |                                |
      | 生命周期 Scope / Flow bridge   | 生命周期 Scope / Flow bridge
      `---------------.----------------'
                      |
              DraftEditorStore
                      |
           StateFlow<DraftEditorState>
                      |
          suspend SaveDraftUseCase
                      |
        suspend ExperimentRepository
                      |
              数据库实现（下一步）
```

共享的是状态语义与业务顺序；平台保留生命周期和 UI 集成。这条边界比“把 ViewModel 复制到 commonMain”更准确。

## 最后的心智模型

- `suspend` 描述可挂起，不描述线程。
- CoroutineScope 描述生命周期所有权。
- Dispatcher 描述执行策略。
- StateFlow 描述可观察的当前状态。
- 不可变 State 描述 UI 可以信任的事实。
- commonTest 验证的不只是结果，还有状态序列和竞态约束。

下一篇，我们会给这条数据流接上第一种真实外部能力：Ktor Client。相同的请求 API 会在 Android 与 iOS 使用不同网络引擎，这将再次检验“共同语义、平台实现”的边界。

## 动手挑战

使用 `CompletableDeferred<Unit>` 代替 Repository 的 delay，手动控制保存完成时机，然后验证：

1. Scope 被取消后状态回到 IDLE。
2. 取消后永远不会出现 SAVED。
3. SAVING 期间字段编辑不会改变正在保存的快照。
4. 不使用真实 sleep。

## 参考资料

- [Kotlin coroutines overview](https://kotlinlang.org/docs/coroutines-overview.html)
- [Coroutines guide](https://kotlinlang.org/docs/coroutines-guide.html)
- [StateFlow API](https://kotlinlang.org/api/kotlinx.coroutines/kotlinx-coroutines-core/kotlinx.coroutines.flow/-state-flow/)
- [kotlinx-coroutines-test](https://kotlinlang.org/api/kotlinx.coroutines/kotlinx-coroutines-test/)
- [runTest API](https://kotlinlang.org/api/kotlinx.coroutines/kotlinx-coroutines-test/kotlinx.coroutines.test/run-test.html)
- [Kotlin Multiplatform coroutines FAQ](https://kotlinlang.org/docs/multiplatform/faq.html)

