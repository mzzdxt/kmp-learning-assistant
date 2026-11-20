# 实验 18：别先选框架，先写不能妥协的约束

## 实验目标

这一轮不创建四个 Hello World，也不拿模拟器上的一次启动时间决定五年技术路线。

我们要把“选 KMP、Flutter、React Native 还是纯原生”改造成一套可以审查、复算和推翻的决策过程：

```text
架构事实
→ 业务硬约束
→ 场景权重
→ 候选排序
→ time-boxed spike
→ 决策与重审条件
```

完成后，你应该能解释：

- 为什么代码复用率不能单独决定技术路线；
- 四种方案到底共享什么、由谁渲染 UI、在哪跨越平台边界；
- 为什么 brownfield、greenfield、团队语言和平台 API 深度会改变答案；
- 为什么 hard gate 必须先于 weighted score；
- 什么时候应该继续调研，什么时候必须写真实 spike；
- 为什么 PocketLab 继续选择 KMP + 原生 UI，却不等于 KMP 普遍优于其他方案。

## 实验范围

本文把 KMP 方案限定为：

```text
KMP shared business/data
+ Android Jetpack Compose
+ iOS SwiftUI
```

Compose Multiplatform 共享 UI 是另一种架构，会在实验 19 单独评估。若把它偷偷算进这一轮，`KMP` 一列就会同时代表两种不同的 UI ownership，比较结果失去意义。

四个候选是：

| 候选 | 共同实现的主要部分 | UI owner | 关键平台边界 |
|---|---|---|---|
| KMP + 原生 UI | Kotlin 业务与数据模块 | Compose / SwiftUI | Kotlin/JVM 与 Kotlin/Native interop |
| Flutter | Dart 应用与 widget tree | Flutter framework/engine | plugin、platform channel、embedder |
| React Native | TypeScript/React feature | React + native components | JSI、TurboModule、Fabric |
| 纯原生 | 默认不共享 App 实现 | Compose / SwiftUI | 服务端契约与产品规范 |

## 为什么不做四个 Hello World benchmark

一个空页面的启动时间不能代表：

- 你的真实依赖图；
- 相机、蓝牙、支付或推送接入；
- 数据库迁移和离线同步；
- 列表滚动、动画和可访问性；
- Release 混淆、符号化和线上诊断；
- CI 构建与 framework/package 发布；
- 团队第一次升级大版本的成本。

更糟的是，四个样例很容易使用不同的依赖、编译模式、缓存状态和 UI 复杂度，最后得到精确但没有可比性的数字。

本实验先判断哪些候选值得进入 spike。真正的性能实验只在剩余候选之间，使用同一条用户旅程、同一份验收标准和 Release 构建进行。

## 文件结构

```text
labs/18-mobile-strategy/
├── README.md
├── decision-model.json
├── results/
│   └── 2026-11-20-decision-model.md
└── scripts/
    ├── evaluate_strategy.rb
    └── verify-mobile-strategy.sh
```

`decision-model.json` 是可审查的输入，Ruby 脚本只是确定性计算器。结论不能藏在脚本分支里。

## 第一步：先记录架构事实

决策前先回答四个问题：

```text
谁拥有 UI？
谁拥有共同业务规则？
跨平台调用经过什么边界？
存量 App 能按什么单位迁移？
```

### KMP + 原生 UI

```text
Compose UI ─┐
            ├→ platform adapter → shared Kotlin → platform services
SwiftUI UI ─┘
```

KMP 共享范围可以很小：一个校验器、一条 use case、一个 repository 或一个完整业务模块。UI 继续调用平台框架，代价是两端 UI 仍各自实现，Swift interop 和 Apple framework 也需要治理。

### Flutter

```text
Dart widgets + application logic
→ Flutter framework
→ rendering engine + embedder
→ platform plugins/services
```

Flutter 主要价值不是“Dart 比 Kotlin 或 Swift 快”，而是同一个 widget/rendering system 负责多端 UI。需要平台能力时，进入 plugin、platform channel 或 native embedding 边界。

### React Native

```text
TypeScript + React
→ Hermes / React renderer
→ Fabric components + TurboModules through JSI
→ native platform
```

React Native 的当前 New Architecture 已经不是“所有调用都经过旧异步 bridge”的旧模型。它仍然存在 JavaScript/native ownership 边界，只是 JSI、Fabric 和 TurboModules 改变了边界形状和成本。

### 纯原生

```text
Android product → Kotlin + Compose + Android APIs
iOS product     → Swift + SwiftUI + Apple APIs
```

纯原生不是“没有共享”。团队仍可共享 API schema、设计语言、测试语料和产品规则；默认不共享的是 App 实现。它减少跨运行时边界，却要承担两端实现、验证和协作成本。

## 第二步：把硬约束与偏好分开

偏好可以加权，硬约束不可以被平均。

PocketLab 声明两个 gate：

```text
native_ui_ownership >= 5
preserve_native_assets >= 5
```

原因不是团队“更喜欢原生”，而是当前 Compose 与 SwiftUI 已经是产品资产，迁移目标是共享业务与数据，不是替换 UI。

如果某候选没通过 gate，即使它在其他指标上得分很高，也不能成为当前候选。团队需要先修改产品约束，而不是调大另一个权重把它救回来。

适合作为 gate 的条件通常包括：

- 法规或安全边界；
- 必须保留的存量资产；
- 必须覆盖的目标平台；
- 已签署的发布时间和可用团队；
- 必须直接使用且没有成熟 adapter 的平台能力；
- 可访问性、离线或设备能力的不可降级要求。

“我熟悉 Kotlin”通常是权重，不是 gate。只有当人员和交付窗口已经固定，它才可能升级为硬约束。

## 第三步：定义场景权重

模型使用 11 个 criterion：

```text
preserve_native_assets
share_business_rules
share_ui_delivery
native_ui_ownership
platform_api_velocity
brownfield_reversibility
single_mobile_team
kotlin_team_fit
react_web_team_fit
runtime_boundary_simplicity
branded_ui_consistency
```

每个场景为它们赋予 `0..5` 权重。`0` 表示该场景不靠这个 criterion 做决定，不表示能力不存在。

评分公式是：

```text
weighted fit = Σ(rating × weight) / Σ(5 × weight) × 100
```

它只负责让输入一致地相乘，不能把主观假设变成客观事实。

## 第四步：把 rating 写成可争论的假设

每个 strategy 对每个 criterion 的 rating 范围为 `0..5`。

例如，本轮给 KMP + 原生 UI 的 `share_ui_delivery` 记为 0，不是说 KMP 不能共享 UI，而是这一候选明确选择原生 UI。若团队要评估 Compose Multiplatform，应新增第五个候选，不能事后修改 KMP 一列的含义。

Flutter 的 `native_ui_ownership` 较低，也不表示 Flutter 无法嵌入平台 view。它表示产品主 UI 由 Flutter widget/rendering system 拥有，而不是 Android/iOS UI toolkit 直接拥有。

React Native 的 `react_web_team_fit` 较高，不表示 Web 代码可以无修改复制到 mobile；它表示语言、React 心智模型和部分团队能力可以复用。

每个 rating 都应能被以下证据挑战：

- 官方架构和集成文档；
- 当前依赖所需 plugin/module 的维护状态；
- 团队实际技能清单；
- 存量模块依赖图；
- 一个 time-boxed Release spike；
- 真实设备的性能与诊断证据。

## 第五步：运行决策门禁

本机只需要 Ruby 标准库：

```shell
cd labs/18-mobile-strategy
./scripts/verify-mobile-strategy.sh
```

门禁验证：

- JSON schema 所需字段完整；
- strategy/criterion id 唯一；
- 每个候选恰好评价全部 criterion；
- rating 与 gate threshold 都在 `0..5`；
- 每个场景有有效权重和至少一个 eligible candidate；
- expected winner 与实际计算一致；
- 四个示例分别由四种策略胜出，模型没有编码“万能冠军”；
- Markdown report 可以确定性生成。

成功输出：

```text
Mobile strategy model passed.
4 strategies, 11 criteria, 4 scenarios
Hard constraints run before weighted fit.
Each strategy wins one scenario; no universal winner is encoded.
Mobile strategy report rendered.
Hard gates -> scenario weights -> candidate -> implementation spike
```

## 第六步：解释四个结果

### PocketLab brownfield migration

| Strategy | Gate | Weighted fit |
|---|---|---:|
| KMP with native UI | pass | 92.2 |
| Pure native | pass | 77.8 |
| React Native | fail | 57.8 |
| Flutter | fail | 52.8 |

KMP 胜出不是因为“KMP 分数永远最高”，而是它同时保留原生 UI/存量资产并共享业务规则。纯原生也通过 gate，但无法直接消除两端业务规则重复。

### Greenfield branded product

| Strategy | Gate | Weighted fit |
|---|---|---:|
| Flutter | pass | 85.6 |
| React Native | pass | 80.8 |

两者只差 4.8 分。模型明确要求：差距低于 5 分时不能直接拍板，必须进入同题 spike。

### React web team extends to mobile

`react_web_team_fit >= 4` 是当前交付 gate，只有 React Native 通过。这里最应该审查的不是分数，而是这个人员约束是否真的不可改变。

### Deep platform product

纯原生的 weighted fit 是 87.1，KMP 是 88.6；但 KMP 没通过 `platform_api_velocity >= 5`，所以纯原生仍是候选。

这正是 hard gate 存在的意义：总分不能补偿一个明确不可接受的系统属性。

当前 KMP rating 为 4，因为场景假定核心平台能力必须进入 shared orchestration，需要团队维护 adapter。如果该 feature 可以完整留在 native side，应把 rating 改为 5 并重算。模型要暴露这条假设，不能把它伪装成框架固有定律。

## 第七步：为相近候选设计同题 spike

Flutter 与 React Native 的绿地场景需要 spike。不要各自做最擅长的 demo，而要做同一题：

```text
登录后进入实验列表
→ 从本地数据库首屏显示
→ 拉取远端分页数据
→ 下拉刷新并取消一次请求
→ 打开系统分享和相机
→ 模拟 503 与离线
→ 记录 crash、slow frame、memory 和 build identity
```

共同验收项：

| 维度 | 证据 |
|---|---|
| 用户体验 | 两端真实设备录屏、可访问性检查、导航与返回行为 |
| 性能 | Release cold/warm start、frame timing、memory、binary delta |
| 平台能力 | 相机、分享、deep link、后台/权限生命周期 |
| 故障定位 | JavaScript/Dart/native stack、source map/symbol、operation id |
| 工程效率 | clean/no-op/incremental build、CI cache、依赖恢复 |
| 升级 | 主版本升级演练、一个关键 plugin/module 的兼容性 |
| 组织 | feature owner、platform owner、on-call 与 review 路径 |

Spike 必须设截止时间和退出条件。它是降低不确定性，不是偷偷启动第五套生产工程。

## 第八步：写决策和重审条件

PocketLab 当前决定继续 KMP + 原生 UI。理由是：

- 已有 Kotlin/Compose 与 Swift/SwiftUI 资产必须保留；
- 目标是共享业务、数据库和网络规则，不是统一视觉实现；
- 第 17 个实验已经证明宿主契约可以按纵向切片迁移和回滚；
- 当前团队的 Kotlin 能力可以直接复用；
- Android/iOS 仍可直接使用平台 UI 与生命周期能力。

这个决定在以下条件出现时重新评估：

- 产品变成强品牌、弱平台差异且 UI 重复成本成为主要瓶颈；
- Web/TypeScript 团队成为 mobile 的主要交付 owner；
- 目标扩展到桌面/Web 且统一 UI 的价值高于平台差异；
- Kotlin/Swift interop、Native build 或 Apple 分发成本持续越过预算；
- 关键平台 SDK 无法在共享边界下可靠交付；
- 组织无法维持 Android/iOS UI 双端 ownership。

决策记录见 [ADR-0016](../../docs/adr/0016-keep-kmp-native-ui-for-pocketlab.md)。

## 把模型改成你的项目

1. 复制 `decision-model.json` 到一个实验分支；
2. 删除与产品无关的 criterion；
3. 让产品、Android、iOS、Web、QA 和 SRE 分别写 gate；
4. 为每个 rating 附一条仓库证据或 spike 假设；
5. 运行门禁并查看 eligible runner-up gap；
6. 对相近候选做同题 spike；
7. 写出 winner、被拒绝方案与重审条件；
8. 三个月后用实际数据重算，而不是保护旧结论。

如果修改 rating 后 winner 很容易翻转，这不是模型失败，而是在告诉你：当前证据不足以支持长期承诺。

## 本实验没有证明什么

- 没有安装 Flutter SDK 或创建 Flutter production app；
- 没有安装 React Native dependencies 或启动 Metro；
- 没有比较空白 App 的启动、包体或帧率；
- 没有声称一个 0–100 分数可以跨公司复用；
- 没有评估 Compose Multiplatform shared UI；
- 没有把官方产品描述当成 PocketLab 的生产验证；
- 没有因为当前选择 KMP 就停止观察替代方案。

## 权威资料

- [Kotlin Multiplatform overview](https://kotlinlang.org/docs/multiplatform/kmp-overview.html)
- [KMP project structure：原生 UI 与 shared UI 分离](https://kotlinlang.org/docs/multiplatform/multiplatform-project-recommended-structure.html)
- [Flutter architectural overview](https://docs.flutter.dev/resources/architectural-overview)
- [Flutter add-to-app](https://docs.flutter.dev/add-to-app)
- [Flutter supported platforms](https://docs.flutter.dev/reference/supported-platforms)
- [React Native New Architecture](https://reactnative.dev/architecture/landing-page)
- [React Native integration with existing apps](https://reactnative.dev/docs/integration-with-existing-apps)
- [React Native native platform APIs](https://reactnative.dev/docs/native-platform)
- [Android Compose migration strategy](https://developer.android.com/develop/ui/compose/migrate/strategy)
- [SwiftUI UIKit integration](https://developer.apple.com/documentation/swiftui/uikit-integration)

本实验的官方文档与版本状态核对于 2026-11-20：Flutter stable 3.50，React Native latest 0.88。版本会变化，架构输入和依赖健康度必须在正式决策时重新验证。
