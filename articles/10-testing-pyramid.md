# KMP 测试金字塔：一次验证，两端真的能放心吗？

> 面向读者：已经会写 Android 单元测试，准备为 KMP 项目建立双端质量门禁的 Kotlin 开发者
>
> 技术核对与实验验证日期：2026-11-19
>
> 实验版本：Kotlin 2.4.21、kotlinx-coroutines-test 1.11.0、SQLDelight 2.4.1、Compose BOM 2026.06.01、Xcode 26.5、Swift 6.3.2

PocketLab 已经能在两台模拟器里显示同一批实验记录。

这时最容易说出一句很有传播力、也很危险的话：

> “业务逻辑写一遍，测试一次，Android 和 iOS 就都放心了。”

听起来正是 KMP 的魅力。可如果这句话是真的，下面这些事故由谁发现？

- SQLDelight 的 JVM driver 正常，Native driver 的行为却不同；
- `Flow` 在 Kotlin 测试里正常，到了 Swift bridge 忘记传播取消；
- Android ViewModel 把失败状态映射错了；
- Compose 按钮看得见，却没有把点击交给 ViewModel；
- SwiftUI 冷启动时没有把 KMP framework、SQLite 和数据库文件真正串起来。

共享代码减少的是重复实现，不是运行时差异。KMP 更准确的承诺应该是：

> 一次编写测试意图，在每个目标上重新取得证据。

这篇文章不画一座只有“单元、集成、UI”三个标签的漂亮金字塔。我们会给 PocketLab 补上一条真实的跨组件集成测试、2 条 Android Compose UI 测试和 1 条 iOS 端到端 UI 测试，然后解释每一层到底在防什么风险。

## 先拆掉一个数字游戏：场景数不等于执行数

本次实验结束后，PocketLab 有两组都正确的数字：

- **50 个不同测试场景**；
- **124 次自动化测试执行**。

为什么相差这么多？因为 `commonTest` 中的 37 个场景会分别进入 JVM、Android Host 和 iOS x64 的测试 compilation：

| 测试来源 | 不同场景 | 实际执行 |
|---|---:|---:|
| `commonTest` | 37 | 111（37 × 3） |
| JVM 平台测试 | 1 | 1 |
| Android Host 平台测试 | 1 | 1 |
| iOS 平台与互操作测试 | 6 | 6 |
| Android ViewModel 单测 | 2 | 2 |
| Android Compose 仪器测试 | 2 | 2 |
| iOS UI 测试 | 1 | 1 |
| **合计** | **50** | **124** |

因此：

- 场景数回答“定义了多少种行为”；
- 执行数回答“这些行为经过了多少个目标与运行时”；
- 两者都不等于代码覆盖率；
- 任意一个数字单独拿出来，都可能制造虚假的安全感。

Kotlin 官方测试文档也明确建议：共同测试与平台专用测试都需要，并且测试应在每一个预期目标上运行。`commonTest` 是共享测试源码，不是一个脱离平台的神秘运行时。

## KMP 的测试空间不是一座塔，而是一张二维地图

经典测试金字塔仍有价值：越接近底层，测试通常越多、越快、定位越精确；越接近真实 App，测试越少、越慢，但能穿过更多接缝。

KMP 多出第二条轴：**运行目标**。

```text
                         业务穿透深度

          小量   ┌──────────────────────────────┐
                 │ Android / iOS 端到端冒烟测试 │
                 ├──────────────────────────────┤
                 │ Compose / SwiftUI 平台 UI    │
                 ├──────────────────────────────┤
                 │ 平台 adapter / interop 合约  │
                 ├──────────────────────────────┤
                 │ 共同层跨组件集成测试          │
          大量   ├──────────────────────────────┤
                 │ 共同层纯规则与状态机单元测试  │
                 └──────────────────────────────┘
                    JVM     Android      iOS
                           运行目标
```

纵轴回答“穿过多少真实组件”，横轴回答“在哪个编译器、driver、运行时和平台框架中成立”。

这张图带来一个重要结论：

> JVM 上的一百条共同测试，不能替代 iOS 上的一次共同测试；iOS 上的一条 UI 测试，也不能替代共同层的一百个规则分支。

它们证明的不是同一件事。

## 第一层：纯共同规则，追求快和精确

PocketLab 的校验、保存用例、同步合并策略、错误分类和状态转换都在 `commonTest` 中验证。

这一层适合回答：

- 空标题会得到什么稳定错误码？
- 校验失败时是否禁止生成 ID 和写数据库？
- pending 本地记录会不会被远端覆盖？
- 取消是否继续作为控制流抛出？
- 状态序列是否为 `IDLE → SAVING → SAVED`？

这里大量使用 fake，因为我们想把某一条规则与外部实现隔离。例如测试保存用例时，fake clock 比读取真实系统时间更好：它能精确证明保存值，而不是只证明“时间大概是现在”。

但 fake 有一个天然盲点：

> 当每个组件都被替身包围时，我们可能只证明了自己对接口的想象能互相配合。

所以还需要下一层。

## 第二层：共同层集成，不 mock 自己的关键接缝

本次新增的 `PocketLabRefreshScenarioTest` 穿过了这条路径：

```text
deterministic demo remote
        ↓
RefreshExperimentsUseCase
        ↓
SqlDelightExperimentRepository
        ↓
真实 test SqlDriver + transaction
        ↓
Flow<List<ExperimentRecord>>
```

它验证的不是某个类，而是一句用户可感知的事实：

> 第一次刷新原子写入 3 条已同步记录；观察者只看到空快照和完整快照；第二次刷新不产生变化。

核心断言可以概括为：

```kotlin
assertEquals(
    RefreshExperimentsResult.Applied(3, 3),
    useCase.execute(),
)

assertEquals(
    listOf(emptyList(), expectedThreeIds),
    emissions,
)

assertEquals(
    RefreshExperimentsResult.Applied(3, 0),
    useCase.execute(),
)
```

注意这里真实与替身的边界：

- remote 是确定性样例，不访问公网；
- UseCase、Repository、SQLDelight schema、transaction 和 Flow 都是真实实现；
- 每个 target 使用自己的 test driver actual。

它不证明真实 TLS 或生产服务端可用，那属于契约测试或预发布环境；它证明共同组件之间的协议没有在重构中断裂。

一个实用规则是：

> 在单元测试里 fake 端口，在集成测试里保留关键接缝，在端到端测试里只保留少量高价值旅程。

## 第三层：平台合约，专门寻找“同名不同命”

`expect/actual`、Ktor engine、SQL driver、Kotlin/Native 导出和 Swift 互操作都不是共同测试能完全覆盖的。

PocketLab 因此保留：

- JVM 平台工厂测试；
- Android Host 平台工厂测试；
- iOS `actual` 测试；
- 5 条 `IosExperimentListBridge` 专用测试，覆盖 Flow callback、refresh、单任务取消、异常和整体关闭。

共同测试在 iOS 运行，证明共同语义能落到 Native runtime；iOS 专用测试则验证只有 Apple 边界才存在的责任。

不要把同一份测试复制到 `jvmTest`、`androidHostTest` 和 `iosX64Test`。能放进 `commonTest` 的行为只写一次；只有平台拥有的行为才进入平台 source set。

## 第四层：平台 ViewModel 与 UI，把责任留在拥有者附近

Android ViewModel 单测已经验证：

- 首次刷新把数据库记录与成功提示组合成 UI state；
- 失败时缓存记录仍然可见。

这两条不应该搬进共同测试，因为 `viewModelScope`、AndroidX `ViewModel` 和资源映射属于 Android。

本次又为纯 Compose Screen 加了两条仪器测试。

第一条把一个已同步的 `ExperimentListUiState` 直接交给 Composable，验证标题、假设、同步 badge 与刷新结果都进入 semantics tree；第二条点击“刷新”，验证页面只向外委托一次 intent。

```kotlin
composeRule.onNodeWithText("把手机留在客厅").assertIsDisplayed()
composeRule.onNodeWithText("已同步").assertIsDisplayed()
composeRule.onNodeWithText("刷新").performClick()
```

这里故意不启动真实网络和数据库。UI 组件拥有的责任只有两件事：正确渲染 state，正确上报 action。Compose 测试框架会基于语义树查询，并为 Compose 工作提供自动同步；测试里不需要 `Thread.sleep(1000)` 猜界面何时稳定。

## 第五层：只保留一条能穿到底的 iOS 旅程

iOS UI 测试走得更远：

1. 为本次测试生成唯一数据库名；
2. 用 `XCUIApplication` 冷启动 SwiftUI App；
3. App 创建真实 KMP feature；
4. demo remote 经 UseCase 写入 SQLDelight；
5. Kotlin Flow 经 bridge 进入 Swift ViewModel；
6. SwiftUI 显示 3 条记录和“同步完成”；
7. 测试点击“刷新”；
8. 页面显示“已是最新状态”。

```swift
let app = XCUIApplication()
app.launchEnvironment["POCKETLAB_DATABASE_NAME"] =
    "pocketlab-ui-\(UUID().uuidString).db"
app.launch()

XCTAssertTrue(
    app.staticTexts["把手机留在客厅"].waitForExistence(timeout: 20)
)
app.buttons["刷新"].tap()
XCTAssertTrue(
    app.staticTexts["已是最新状态"].waitForExistence(timeout: 10)
)
```

唯一数据库名很重要。若所有运行复用 `pocketlab-demo.db`，第一次刷新可能因为上次遗留数据直接得到 `changedCount = 0`，测试就会依赖执行历史。

Apple 已推荐新单元测试考虑 Swift Testing，但 UI automation 仍由 XCTest / XCUIAutomation 承担。本实验的目标是操作真实 App，因此使用 XCTest 是合适的边界。

为什么 iOS 只有一条 UI 测试？因为这一层最慢、失败诊断最远，也最容易受 Simulator 与系统动画影响。校验、网络错误、事务冲突等分支已经在更低层精确覆盖，没必要在 UI 里重新排列组合。

## 测试替身不是越少越高级

“全部使用真实依赖”听起来比 mock 更接近生产，但它常常让测试同时依赖网络、时钟、数据库和线程调度。失败时只知道整条链红了，不知道哪条规则错了。

PocketLab 按边界选择替身：

| 想证明什么 | 选择 |
|---|---|
| 纯校验与状态机 | fake repository / fake clock |
| HTTP 请求与解码 | Ktor `MockEngine` |
| UseCase 与数据库能否协作 | 真实 Repository + test SQL driver |
| Compose 是否渲染并上报 intent | 直接注入 UI state |
| SwiftUI 与 KMP 全链是否接通 | 真实 App + 唯一 test database |
| 真实服务端协议是否兼容 | 后续 contract / staging test |

替身的目标不是让测试变绿，而是控制变量。真实依赖的目标也不是显得高级，而是验证最值得担心的接缝。

## 不要等待时间，要等待条件

多平台异步测试最常见的坏味道是：

```kotlin
delay(1_000)
```

或者：

```swift
sleep(2)
```

这类等待既慢又不可靠：机器快时浪费，机器慢时仍会失败。

PocketLab 使用三种确定性策略：

- Kotlin coroutine test scheduler 与 `runCurrent()` 推进共同层任务；
- Compose test rule 等待界面空闲并查询 semantics；
- XCTest `waitForExistence(timeout:)` 等待具体可见条件。

timeout 仍然存在，但它是失败上限，不是业务同步机制。

## CI 不该只有一个巨大的 `allTests`

本地可以一次跑完整矩阵，CI 更适合按反馈速度拆车道：

| 车道 | 建议任务 | 目的 |
|---|---|---|
| 快速 PR | JVM common、Android Host、Android ViewModel | 数分钟内发现多数规则回归 |
| 平台 PR | iOS Native、framework link、Android UI | 验证 driver、interop 与平台框架 |
| 主分支 | 双端完整构建与 UI smoke | 验证最终装配和真实旅程 |
| 定时/发布 | staging contract、性能、迁移与更多设备 | 捕获环境和长期回归 |

不要让所有 PR 因一个偶发 Simulator 问题排队，也不要因为 UI 测试较慢就永久不运行它。分层的意义之一，就是让失败能被路由给正确的所有者。

## 本次真实验证结果

| 验证 | 结果 |
|---|---:|
| JVM common + platform | 38/38 |
| Android Host common + platform | 38/38 |
| iOS x64 common + platform / interop | 43/43 |
| Android ViewModel JVM | 2/2 |
| Android Compose UI（API 34） | 2/2 |
| iOS XCTest UI（iOS 18.4） | 1/1 |
| 不同测试场景 | 50 |
| 自动化执行总数 | 124/124 |

新增的共同集成场景分别在 JVM、Android Host 和 iOS x64 运行，所以共享模块由上一里程碑的 116 次执行增加到 119 次。再加上 Android 平台壳的 4 次和 iOS UI 的 1 次，得到 124 次。

这次还有一个很有 KMP 味道的环境插曲：Xcode 26.5 最初只列出 generic Simulator destination，但 `simctl` 可以看到 iOS 17.5–18.4 runtimes。显式启动 iOS 18.4 设备并指定 device id 后，`xcodebuild test` 成功完成。它提醒我们：

> 测试代码、测试产物、Simulator runtime 和 Xcode destination 发现，是四个不同故障面。

## 五种常见的虚假安全感

### 1. “commonTest 在 JVM 绿了，所以 iOS 也没问题”

错误。你只证明了 JVM compilation、依赖和运行时。

### 2. “每个平台都跑了，所以不用平台测试”

错误。共同测试不知道 Swift bridge 的取消，也不知道 Compose 的 semantics。

### 3. “端到端测试最真实，所以把所有分支都放进去”

错误。它会得到一套慢、脆、定位困难的测试，并重复更低层已经擅长的工作。

### 4. “100% coverage 就等于没有风险”

覆盖率只说明代码被执行，不说明断言有意义，也不说明另一个 target 执行过。

### 5. “偶发失败重跑就好”

重跑可以用来采集证据，不能成为长期策略。flaky test 必须有所有者、失败附件、隔离期限和修复结论；否则团队最终只会学会忽略红灯。

## 一套可执行的设计清单

为下一个 KMP 功能加测试时，按顺序问：

1. 这条行为由共同层还是平台层拥有？
2. 最小、最快的测试层能否准确证明它？
3. 哪个接缝如果全部使用 fake，会产生盲点？
4. 共同测试是否在每个支持 target 上实际执行？
5. 是否存在只有某个平台才有的生命周期、driver 或 FFI 风险？
6. UI 测试是在证明可见行为，还是重复业务分支？
7. 测试能否独立运行，不依赖顺序、旧数据库、真实时钟和公网？
8. 报告展示的是不同场景数，还是执行数？

能回答这八个问题，比背诵“70% 单元、20% 集成、10% UI”更接近工程判断。比例可以作为观察结果，不能成为设计起点。

## 读者挑战

完成配套实验后，尝试这五项扩展：

1. 让 demo remote 返回 `CONNECTION`，在共同集成测试中证明数据库快照不变；
2. 为 Compose 增加失败 banner 测试，但不要启动 Repository；
3. 为 iOS UI 测试保存失败截图与 `.xcresult`，比较断言失败和 App crash 的证据差异；
4. 把 iOS 唯一数据库名删除，连续执行两次，亲手观察顺序依赖；
5. 设计一份 CI matrix，明确哪些测试阻塞 PR、哪些只阻塞发布，以及失败由谁处理。

## 写在最后

“一次验证，两端放心”不是指只按一次 Run，也不是指用一条 UI 测试巡视全世界。

它真正成立需要三个条件：

- 一条共同规则只定义一次；
- 这条规则在每个目标上重新执行；
- 每个平台拥有的风险，由平台自己的测试补上。

共享测试让 Android 和 iOS 少写一套业务断言；目标矩阵让我们没有忘记两端仍是不同运行时；少量端到端测试则证明那些独立正确的零件真的能一起工作。

最好的 KMP 测试策略，不是让测试数量看起来很多，而是让每一次失败都能回答：哪一层的哪一个承诺被打破了。

下一篇，我们会进入 Kotlin/Native 更深处：同一份 Kotlin 经过 Native 编译、进入 Objective-C/Swift 世界后，内存、对象生命周期和并发模型究竟发生了什么。

## 官方资料

- [Kotlin Multiplatform：运行测试](https://kotlinlang.org/docs/multiplatform/multiplatform-run-tests.html)
- [Kotlin API 指南：为 Multiplatform 编写并在所有目标运行测试](https://kotlinlang.org/docs/api-guidelines-build-for-multiplatform.html)
- [Android Developers：Testing in Compose](https://developer.android.com/develop/ui/compose/testing)
- [Android Developers：Compose BOM](https://developer.android.com/develop/ui/compose/bom)
- [Apple：Testing with Xcode](https://developer.apple.com/documentation/xcode/testing)
- [Apple：XCTest](https://developer.apple.com/documentation/xctest/)
- [Apple：Asynchronous tests and expectations](https://developer.apple.com/documentation/xctest/asynchronous-tests-and-expectations)
- [Apple：Swift Testing](https://developer.apple.com/documentation/Testing)
