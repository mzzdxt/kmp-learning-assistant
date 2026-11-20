# Compose Multiplatform 要不要上？一份决策清单

> 适合读者：已经知道 KMP 能共享 Kotlin 逻辑，正在犹豫要不要连 UI 一起共享的 Android / Kotlin 开发者。
>
> 本文版本敏感信息与实验结果验证于 2026-11-20。

## 开场：同一张卡片，为什么非要写两遍？

PocketLab 的 Android 和 iOS 都要显示一张“个人实验摘要卡片”：

- 标题；
- 假设；
- 同步状态；
- 一个“查看实验”按钮。

Android 已经用 Compose 写了一遍，iOS 又要用 SwiftUI 写一遍。

看到这里，熟悉 Compose 的 Android 开发者很难不问：

> 既然 Kotlin 逻辑都共享了，为什么不顺手把 Composable 也放进 `commonMain`？

这个问题很合理，但它偷偷合并了四个不同的决定：

1. 要不要共享一个叶子组件？
2. 要不要共享一个完整页面？
3. 要不要共享导航、生命周期和平台交互？
4. 要不要让同一套 UI framework 接管整个 App？

它们不是同一个开关。

我用一个真实 `sharedUi` spike 试了三轮版本组合，生成 Android AAR 和 iOS Release Framework，再让 Swift 6 严格并发模式消费它。最终结论是：

> PocketLab 现在不迁移完整页面和导航；保留原生 UI 为默认，只允许在独立 `sharedUi` module 中实验高重复、低平台耦合的叶子组件。

这不是“Compose Multiplatform 不成熟”。官方已经把 Android、iOS 和 desktop 标为 Stable，Web/Wasm 仍为 Beta。[Kotlin Multiplatform FAQ](https://kotlinlang.org/docs/multiplatform/faq.html)

它表达的是另一件事：

> 框架的稳定性回答“能不能用于生产”，项目决策还要回答“谁拥有 UI、迁移什么、付出什么，以及怎么退出”。

## 一、先把 KMP 和 Compose Multiplatform 分开

KMP 与 Compose Multiplatform 经常一起出现，但不是同一个概念。

### KMP 决定代码如何跨目标编译

```text
Kotlin common code
├── Kotlin/JVM → Android
└── Kotlin/Native → iOS framework / native binary
```

它允许我们选择共享范围：

- 只共享一个校验函数；
- 共享 domain 与 use case；
- 共享 network 和 database；
- 共享 state holder；
- 也可以继续共享 UI。

### Compose Multiplatform 决定一部分 UI 如何共同实现

```text
@Composable common UI
├── Android → Jetpack Compose artifacts / Android rendering stack
└── iOS     → Compose Multiplatform + Skia / UIKit container
```

在 Android 上，Compose Multiplatform 会使用 Google 发布的 Jetpack Compose artifacts；不同 Compose Multiplatform 版本会映射到不同 Jetpack Compose 版本。[官方兼容表](https://kotlinlang.org/docs/multiplatform/compose-compatibility-and-versioning.html)

在 iOS 上，共同 Composable 通常通过 `ComposeUIViewController` 进入 UIKit，再被 UIKit 或 SwiftUI 容器接住。它不是把 Kotlin UI 翻译成 SwiftUI view。[官方 iOS entry point](https://kotlinlang.org/docs/multiplatform/compose-multiplatform-entry-points.html)

因此，下面两种架构都属于 KMP：

```text
方案 A：共享逻辑，原生 UI

Android Compose ─┐
                 ├→ shared Kotlin business/data
iOS SwiftUI ─────┘
```

```text
方案 B：共享逻辑 + 共享 UI

Android host ─┐
              ├→ shared Compose UI → shared Kotlin business/data
iOS host ─────┘
```

选择 KMP，并不会自动替你选择方案 B。

## 二、真正的决策单位不是“UI”，而是 UI ownership

“共享 UI”这个词太宽了。更有用的做法，是先指出要共享到哪一层。

| 层级 | 例子 | 新增 ownership | 风险 |
|---|---|---|---|
| 叶子组件 | 卡片、徽标、空状态 | 绘制、样式、基础语义 | 较低 |
| 组合区域 | 表单区、列表 item、过滤栏 | 局部状态、焦点、输入 | 中等 |
| 完整页面 | 列表、详情、编辑页 | 页面状态、加载、错误、平台控件 | 较高 |
| 导航流 | 登录、编辑、支付流程 | back stack、deep link、生命周期 | 很高 |
| 整个 App | shell、主题、导航、全部页面 | UI runtime 与发布拓扑 | 最高 |

从叶子组件走到整 App，不只是多写几个 Composable。

你会依次接管：

- 文本、颜色、间距；
- state 与事件；
- 输入法、焦点和手势；
- 导航和 deep link；
- 相机、地图、WebView、分享等平台 view；
- accessibility 语义；
- 窗口、scene 和生命周期；
- 截图、UI 自动化和诊断；
- Android/iOS 两边的构建与发布依赖。

共享范围越大，平台宿主越像一个容器。这个结果可能正是你想要的，也可能与现有团队 ownership 完全相反。

## 三、先问六个“谁负责”，再问复用率

### 1. 谁拥有视觉实现？

如果 Android 与 iOS 追求同一套品牌化布局、动画和交互，复用 Composable 很有吸引力。

如果产品明确要求跟随各平台最新组件、布局习惯和系统变化，两套原生 UI 可能更便宜。这里的“便宜”不是代码行更少，而是平台 owner 能直接使用系统能力，不必等待共同抽象。

### 2. 谁拥有导航？

共享一张卡片，不要求共享导航。

共享整个页面时，要明确点击事件如何进入宿主：

```kotlin
ExperimentSummaryCard(
    model = model,
    onOpen = { hostNavigator.openExperiment(id) },
)
```

这里让宿主持有导航，是一种明确选择。若 Composable 自己操作共同 back stack，则 navigation ownership 已经迁入 shared UI。

两者都能工作，但不能把后者伪装成“只是共享视图”。

### 3. 谁拥有生命周期和状态？

同一份 UI 可以消费：

- 平台 ViewModel 提供的不可变 snapshot；
- common state holder；
- 直接连接 repository 的 presenter。

这三种形状的取消、恢复、后台行为和测试边界完全不同。

PocketLab 当前让业务与数据留在已有 `domain + shared`，`sharedUi` spike 只接收 UI value 和 callback。这样测试一张卡片时，不会顺便启动数据库、网络和刷新用例。

### 4. 谁负责平台控件？

UIKit view 可以嵌入 Compose，Compose 也可以嵌入 UIKit/SwiftUI 宿主；官方文档给出了 `ComposeUIViewController`、`UIKitView` 和容器集成方式。[UIKit integration](https://kotlinlang.org/docs/multiplatform/compose-uikit-integration.html)

“可以嵌入”不等于“集成成本为零”。地图、相机、富文本、WebView、支付或厂商 SDK 仍需要：

- view 生命周期；
- delegate/callback；
- 布局与测量；
- accessibility；
- 键盘、焦点和手势协调；
- platform-specific diagnostics。

如果页面核心就是深度平台控件，先做这个控件的 spike，不要先做一个漂亮的静态首页。

### 5. 谁负责资源和设计系统？

需要盘点：

- 字体授权和 fallback；
- 图片与多语言资源；
- dark mode 与动态颜色；
- Dynamic Type / font scale；
- RTL；
- platform-specific spacing 与 control size；
- 设计 token 的来源和发布方式。

共享像素不一定等于共享设计系统。反过来，两套原生 UI 也可以通过 token、组件规范和 screenshot corpus 共享设计语言。

### 6. 谁负责质量和事故？

一个 shared Composable 同时影响 Android 与 iOS，意味着：

- 一次 UI 改动可能同时阻断两端发布；
- 一条 accessibility 回归可能跨两个平台扩散；
- 修复者需要理解共同 UI 和平台容器；
- 测试矩阵必须包含至少两个 rendering target；
- 回滚单位要与发布拓扑匹配。

复用减少了重复实现，也扩大了共同故障域。两者必须同时写进决策。

## 四、PocketLab 的最小 spike：只共享一张叶子卡片

为了避免 demo 自动变成架构，我们建立了独立 module：

```text
domain  → shared → Android / SwiftUI production UI

sharedUi (optional spike)
├── commonMain: one ExperimentSummaryCard
├── androidMain: Android AAR
└── iosMain: UIViewController factory
```

刻意没有这些依赖：

```text
sharedUi -X→ domain
sharedUi -X→ shared
sharedUi -X→ Ktor
sharedUi -X→ SQLDelight
androidApp -X→ sharedUi
```

也就是说，它目前不是生产 App 的新依赖，只是一个有边界的候选。

### 共同 UI model 只表达展示

```kotlin
data class ExperimentSummaryUiModel(
    val title: String,
    val hypothesis: String,
    val status: ExperimentSummaryStatus,
)
```

没有 domain record、repository、navigation destination 或 lifecycle owner。

未知状态不会被误写成“已同步”：

```kotlin
fun fromWireValue(value: String): ExperimentSummaryStatus =
    entries.firstOrNull { it.wireValue == value } ?: PENDING_UPLOAD
```

两条共同测试锁定了 wire value 与保守 fallback。

### Composable 拥有 presentation，不拥有业务

```kotlin
@Composable
fun ExperimentSummaryCard(
    model: ExperimentSummaryUiModel,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
)
```

组件负责：

- 布局；
- 颜色和字体；
- 同步状态的可见文案；
- `stateDescription`；
- 按钮 `Role`；
- 把点击意图交还宿主。

组件不负责：

- 从数据库查询；
- 发起网络刷新；
- 创建 coroutine scope；
- 导航；
- 保存或恢复页面状态；
- 关闭 iOS resource。

### iOS 只消费一个 `UIViewController` 工厂

```kotlin
object PocketLabSharedUi {
    fun experimentSummaryViewController(
        title: String,
        hypothesis: String,
        status: String,
        onOpen: () -> Unit,
    ): UIViewController = ComposeUIViewController {
        ExperimentSummaryCard(
            model = ExperimentSummaryUiModel(
                title = title,
                hypothesis = hypothesis,
                status = ExperimentSummaryStatus.fromWireValue(status),
            ),
            onOpen = onOpen,
        )
    }
}
```

Swift 调用面保持很小：

```swift
@MainActor
func buildSharedExperimentCard() {
    _ = PocketLabSharedUi.shared.experimentSummaryViewController(
        title: "把手机留在客厅",
        hypothesis: "睡前把手机放在客厅，会让我更早入睡。",
        status: "synced",
        onOpen: {}
    )
}
```

第一版 framework header 仍导出了 `ExperimentSummaryUiModel` 与 enum。它们对 Swift 没有必要，却会成为 Apple ABI。

我们用 `@HiddenFromObjC` 隐藏 presentation model 后：

```text
Apple header: 244 lines → 157 lines
presentation-model leaks: 2 types → 0
```

这个小插曲很重要：共享 UI 不会让跨语言 API 治理消失，只是改变了导出面。

## 五、三次版本尝试，比“最新版当然最好”更有用

当前工程基线是：

```text
Kotlin 2.4.21
AGP 8.13.2
Gradle 8.14.4
Android compileSdk 36
Intel macOS host
```

### 尝试一：Compose Multiplatform 1.12.2 + `iosX64`

失败发生在依赖解析：runtime、foundation 和 UI 都没有匹配的 `iosX64` variant。

这不是仓库坏了。官方 1.11.1 release note 已明确：Apple x86_64 artifacts 被全部移除，包括 `iosX64` 和 `macosX64`。[Compose Multiplatform 1.11.1 changes](https://kotlinlang.org/docs/multiplatform/whats-new-compose-111.html)

当前 Intel Mac 因而遇到一个非常具体的限制：

```text
旧 shared logic module → 仍可运行 iosX64Test
新 sharedUi module     → 无 iosX64 artifact 可用
```

“KMP 的 iOS 测试能跑”不能自动推出“当前 Compose Multiplatform UI 测试也能在这台机器上跑”。

### 尝试二：1.12.2，只保留 arm64 Apple targets

去掉 `iosX64` 后：

- Android host compile/test 成功；
- `iosArm64` Debug Framework 成功；
- Swift consumer type-check 成功；
- `iosSimulatorArm64` 测试可以编译，却不能在 Intel host 运行。

但在生成 Android Release AAR 时，AAR metadata gate 失败：Compose 1.12.2 对应的 Jetpack Compose 1.12.1 要求 compileSdk 37 与 AGP 9.1，而当前项目是 compileSdk 36 / AGP 8.13.2。

官方兼容表也显示 Compose Multiplatform 1.12.2 映射到 Jetpack Compose 1.12.1，1.11.1 映射到 1.11.2。[版本映射表](https://kotlinlang.org/docs/multiplatform/compose-compatibility-and-versioning.html#jetpack-compose-artifacts-used)

这时有两个合理选择：

1. 为一张实验卡片升级整个 Android build chain；
2. 先验证仍兼容当前基线的 Compose Multiplatform 版本。

我们选择第二个。spike 的目标是判断 shared UI 边界，不是借机混入 AGP 9.1 迁移。

### 尝试三：1.11.1 + foundation-only UI

Compose Multiplatform 1.11.1 在现有 AGP/API 基线上成功构建。

我们没有为了方便直接加入该系列的 alpha Material 3 artifact，而是用 runtime、foundation 和 UI 写最小卡片。这样实验回答的是 Compose shared UI 与工程基线能否共存，不让另一个 pre-stable dependency 改变问题。

结论不是“应该永远停留在 1.11.1”。恰恰相反：

> 若生产接入必须停留旧 UI 版本才能避免升级构建链，版本治理本身就是采用成本；应该设升级窗口，不该把 spike 版本永久化。

## 六、最终门禁到底证明了什么？

运行：

```shell
cd labs/02-first-kmp-module
./scripts/verify-compose-shared-ui.sh
```

2026-11-20 的最终结果：

| 证据 | 结果 |
|---|---|
| Android host contract tests | 2/2 |
| Android AAR | 16,460 bytes |
| iOS device Release framework binary | 103,941,976 bytes |
| iOS Simulator arm64 Release framework | 交叉编译成功 |
| Swift 6 complete strict concurrency | type-check 通过 |
| Apple header | 157 行 |
| presentation model 泄漏 | 0 |
| production module 引用 `sharedUi` | 0 |

### 不能从 104 MB 推导最终 App 增量

这里测的是一个未进入最终 App 的 static framework binary，不是 App Store thinning 后的 IPA，也没有同一个 App 的 before/after 对照。

所以它能证明“这个产物不是零成本”，不能证明“用户下载包一定增加 104 MB”。二进制预算必须用同一 Release App、同一链接和 strip 配置做差分。

### 不能把交叉编译写成运行通过

本机可以生成 `iosSimulatorArm64` Framework 和测试 executable，但 Intel CPU 不能运行 arm64 Simulator test。

真正的 UI runtime 证据仍缺：

- Apple Silicon Simulator；
- 真实 iPhone；
- rendering screenshot；
- VoiceOver；
- Dynamic Type；
- 输入、焦点和导航；
- Instruments frame/memory。

### 并行链接还暴露了 2 GiB OOM

第一次把 Debug test、device Release 和 Simulator Release 三个 Kotlin/Native 链接任务并行运行时，2 GiB Gradle daemon 发生 `OutOfMemoryError`。

把门禁改为 `--max-workers=1` 后，同样的 heap 能完成全部任务，但一次非全量门禁用了 10 分 10 秒；随后 up-to-date + configuration-cache reuse 为 2 秒。

这说明：

- 失败来自并发峰值，不是单个 framework 永远无法链接；
- 串行能降低峰值，却增加 wall time；
- 是否加 heap、拆 CI lane 或升级 runner，必须继续测量；
- 不应只凭一次 OOM 宣布 Compose 不可用，也不应隐藏它。

## 七、决策清单：什么时候值得上？

下面不是打分后自动选型的万能表。它的作用是暴露需要证据的地方。

### A. 产品与体验

- [ ] Android/iOS 的目标交互真的相同，而不只是数据相同。
- [ ] 品牌一致性比紧跟平台控件变化更重要。
- [ ] Dynamic Type、RTL、dark mode 和 accessibility 验收标准已写明。
- [ ] 核心页面里的地图、相机、WebView、富文本等平台 view 已列清。
- [ ] 设计团队接受一套共同 rendering system 的能力与限制。

### B. 共享范围

- [ ] 能明确说出先共享“组件、页面、流程还是整 App”。
- [ ] 第一阶段不同时迁移 UI、导航、状态、database 和 network。
- [ ] `sharedLogic` 与 `sharedUI` 可以独立依赖和发布。
- [ ] 原生 UI module 不会为了用业务逻辑被迫引入 Compose 依赖。
- [ ] callback、navigation 和 platform service 的 owner 已确定。

官方推荐结构也明确指出：只要存在原生 UI consumer，就值得把 `sharedLogic` 与 `sharedUI` 分开，避免不需要 UI 的模块引入 Compose dependencies。[推荐工程结构](https://kotlinlang.org/docs/multiplatform/multiplatform-project-recommended-structure.html)

### C. 团队 ownership

- [ ] Kotlin/Compose owner 能同时处理 Android 与 iOS rendering 问题。
- [ ] iOS owner 参与评审，而不是只负责把 framework 接上。
- [ ] on-call 知道 crash 属于 Kotlin、Compose、Skia、UIKit container 还是宿主。
- [ ] 两端 release 节奏和共同 UI 发布方式相容。
- [ ] 新成员能在合理时间内运行、调试和预览共同 UI。

### D. 工具链与依赖

- [ ] Kotlin、Compose Multiplatform、Jetpack Compose、AGP、Gradle、Xcode 版本矩阵已验证。
- [ ] 所需 UI library 在 Android/iOS 目标都有匹配 variant。
- [ ] CI 有 Apple Silicon runtime，不依赖已移除的 Apple x86_64 target。
- [ ] Release AAR/framework/XCFramework 实际构建，而不只跑 Debug preview。
- [ ] 升级、锁版本和安全修复的 owner/时间预算明确。

### E. 质量证据

- [ ] common presentation logic 有确定性测试。
- [ ] Android 和 iOS 至少各有一次真实 rendering smoke。
- [ ] Swift/Objective-C header 有 allowlist/denylist。
- [ ] accessibility、输入法、旋转/窗口和后台恢复有平台验收。
- [ ] Release App 做过 startup、frame、memory 和最终包体 before/after。

### F. 退出策略

- [ ] shared UI 通过宿主契约接入，而不是渗入所有 feature。
- [ ] feature flag 的作用域和数据兼容性已定义。
- [ ] 失败时能退回原生实现，不需要回滚数据库。
- [ ] spike 有截止日期、成功阈值和删除条件。
- [ ] 何时扩大到下一层、何时停止，已经写成重审条件。

如果大量问题只能回答“以后再说”，最合理的下一步通常不是全面迁移，而是缩小 spike。

## 八、四类适合信号与四类警报

### 适合信号

1. **高重复的品牌 UI**：两端布局、动画和交互几乎相同，重复实现已成为交付瓶颈。
2. **Kotlin/Compose 团队是真正 owner**：不是只有 Android 会 Kotlin，而是有人负责 iOS 集成、体验和发布。
3. **平台控件不是页面核心**：大部分 UI 可由共同组件表达，少量 native view 有清晰边界。
4. **同节奏发布**：两端能共同升级 UI dependency，并有跨平台测试 lane。

### 警报

1. **产品核心是深度平台能力**：相机管线、AR、复杂文本、系统扩展或频繁变化的新 SDK。
2. **两端体验刻意不同**：共享会制造大量 `if (platform)`，最终只共享了文件位置。
3. **iOS 没有 owner**：把 SwiftUI 换成 Kotlin UI 不会消灭 UIKit、Xcode、签名、崩溃和审核知识。
4. **采用理由只有复用率**：没有发布、质量、性能、退出和团队模型。

## 九、最安全的采用阶梯

不要从“原生 UI”直接跳到“全 App shared UI”。

### 第 0 阶：只共享设计语言

共享 token、文案 key、截图语料和验收标准，UI 仍分别实现。

### 第 1 阶：无状态叶子组件

例如 badge、空状态、摘要卡片。输入是 UI value，输出是 callback。

验收重点：视觉、语义、header、构建产物。

### 第 2 阶：局部状态组件

加入输入、焦点、动画或短生命周期 state。

验收重点：键盘、恢复、accessibility、平台行为差异。

### 第 3 阶：完整页面

页面 loading/error/content 与平台 view 进入共同边界。

验收重点：生命周期、性能、navigation handoff、诊断。

### 第 4 阶：共享导航流或 App shell

只有前面证据支持，才让共同 UI runtime 拥有更大的 back stack 和发布面。

每升一级，都要重新回答：

```text
新增了什么 ownership？
减少了什么重复？
扩大了什么故障域？
现在还怎么退出？
```

## 十、PocketLab 的当前决定

PocketLab 继续保持：

```text
Android Compose / ViewModel ─┐
                             ├→ sharedLogic: domain + data
iOS SwiftUI / ViewModel ─────┘

optional sharedUi spike → one leaf component only
```

规则如下：

1. 原生 Compose/SwiftUI 仍是生产默认；
2. `sharedUi` 与 `domain/shared` 分离，不进入已有 umbrella framework；
3. 第一候选只限高重复、低平台耦合的 presentation component；
4. shared UI 不拥有 navigation、lifecycle、persistence、network 或 domain record；
5. Apple 侧只导出窄 `UIViewController` factory，不导出内部 UI model；
6. 生产接入前必须在 Apple Silicon 和真实 iPhone 补 runtime/accessibility evidence；
7. 升级到当前 Compose Multiplatform/AGP/API 基线后重新测 Release build、包体和资源峰值；
8. 没有证据支持前，不把一张卡片 spike 扩写成“全 App shared UI 战略”。

这是一项“有条件继续实验”的决定，不是永久拒绝。

## 十一、什么时候重新评估？

出现任一条件时重开 ADR：

- 两端 UI 重复成为主要交付瓶颈；
- 新 feature 要求高度一致的品牌布局与动画；
- Apple Silicon CI 已覆盖 Simulator runtime；
- 当前 Android build chain 升到 Compose 1.12.x 所需基线；
- 真实设备证明 accessibility、输入和性能满足预算；
- iOS 团队愿意共同拥有 shared UI runtime；
- 原生平台 view 的 adapter 成本低于两端重复页面成本；
- 产品需要 desktop，并能从同一 UI 获得新增收益。

反过来，如果每个共同页面都充满平台条件分支、升级长期阻塞发布、或 on-call 无法定位故障，就应缩回上一层。

## 十二、最常见的五个误区

### 误区 1：Compose Multiplatform Stable，所以无需 spike

Stable 是生态承诺，不是你家 dependency graph、设计系统、平台 SDK 和 CI 的自动验收。

### 误区 2：会 Jetpack Compose，就已经会 iOS UI 工程

Composable 心智模型可复用，但 UIKit/SwiftUI 容器、Xcode、签名、runtime、accessibility 和 Apple 发布仍然存在。

### 误区 3：共享 UI 后就不需要 Swift

只要 App 使用 Apple SDK、系统扩展或原生宿主，Swift/Objective-C interop 和平台知识仍是生产能力。

### 误区 4：一张 demo 页面通过，整 App 就能迁

静态页面绕开了最昂贵的部分：导航、输入、恢复、平台 view、诊断和发布。

### 误区 5：不共享 UI 就浪费了 KMP

KMP 的价值是按边界共享。让共同业务规则只有一个事实来源，同时让平台 UI 保持自主，本身就是完整架构，不是“只用了半套”。

## 结语：不要问“能不能共享”，要问“共同拥有到哪里”

Compose Multiplatform 已经不是只能做玩具的技术。但“生产可用”与“当前项目应全面采用”之间，仍然隔着 ownership、版本、平台能力、质量证据和退出策略。

这次实验最有价值的，不是成功生成了一个 framework，而是让隐藏成本逐个现形：

- Apple x86_64 target 已被移除；
- 最新 Compose 版本要求升级 Android build chain；
- Apple header 会泄漏不必要的 presentation model；
- Intel host 能交叉编译却不能运行 arm64 Simulator；
- 多个 Native 链接并行会撞上 2 GiB heap 峰值；
- 一张卡片的成功不能替完整页面背书。

真正成熟的决策，不会把这些写成“框架不行”，也不会把它们从汇报里删掉。

它会说：

> 我们知道要共享哪一层，知道证据覆盖了什么，知道还缺什么，也知道什么时候扩大和什么时候退出。

这才是 Compose Multiplatform 的正确打开方式。

## 动手挑战

不要直接复制本文结论。给你自己的 App 选一个最小候选：

1. 只选一个叶子组件；
2. 写出它不拥有的职责；
3. 让 Android 和 iOS 生产各自的 Release consumer；
4. 检查 Apple header；
5. 做至少一次 accessibility 验收；
6. 记录最终 App before/after，而不是 framework 文件夹大小；
7. 写下扩大、停止和删除 spike 的条件。

如果第 2 步写不出来，先别写第一个 Composable。

## 参考资料

- [Compose Multiplatform compatibility and versions](https://kotlinlang.org/docs/multiplatform/compose-compatibility-and-versioning.html)
- [What's new in Compose Multiplatform 1.11.1](https://kotlinlang.org/docs/multiplatform/whats-new-compose-111.html)
- [Recommended Kotlin Multiplatform project structure](https://kotlinlang.org/docs/multiplatform/multiplatform-project-recommended-structure.html)
- [Native application entry points](https://kotlinlang.org/docs/multiplatform/compose-multiplatform-entry-points.html)
- [Integration with UIKit](https://kotlinlang.org/docs/multiplatform/compose-uikit-integration.html)
- [Integration with SwiftUI](https://kotlinlang.org/docs/multiplatform/compose-swiftui-integration.html)
- [Kotlin Multiplatform FAQ](https://kotlinlang.org/docs/multiplatform/faq.html)
