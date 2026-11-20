# KMP、Flutter、React Native 与纯原生：别只比较代码复用率

> 系列第 18 篇：技术负责人怎样选择移动端技术路线

会议室里放着四页方案。

Android 负责人说：

> KMP 可以复用现有 Kotlin，业务逻辑共享以后，两端不再各修一次 bug。

Flutter 负责人说：

> 既然要跨平台，为什么不连 UI 一起共享？一个团队交付两端才是真效率。

Web 负责人说：

> 我们已经有二十个 React/TypeScript 工程师，React Native 才能复用真正稀缺的资源——人。

iOS 负责人说：

> 每次新系统能力发布，我们都不想等 plugin、wrapper 或 framework 适配。纯原生最可控。

四个人都拿出了一张图。横轴是开发时间，纵轴是代码复用率，每条线都恰好证明自己的方案最好。

CTO 看了十分钟，问了一个让会议安静下来的问题：

> 你们说的“复用”，到底复用了什么？谁还在写平台代码？出了问题谁值班？

这才是技术路线选择真正的起点。

KMP、Flutter、React Native 与纯原生不是四种不同语法，而是四种不同的 ownership 设计：谁拥有 UI，谁拥有业务规则，代码在哪个运行时执行，平台能力经过哪条边界，团队怎样发布、升级和排障。

如果只比较“共享了百分之多少代码”，就像买房只比较公摊率，却不问地点、结构、维护和产权。

这篇文章会做两件事：

1. 把四种方案还原成真实架构，而不是产品口号；
2. 用一个可执行的约束模型，让四种方案分别在不同场景胜出。

最后 PocketLab 仍会选择 KMP + 原生 UI。但那不是预设答案，而是当前产品、资产和团队约束共同推出来的结果。

## 先纠正一个不对称比较

Flutter 与 React Native 首先是跨平台应用/UI 方案，纯原生是两套平台实现，而 KMP 本身是一种可以选择共享范围的技术。

KMP 可以只共享：

```text
validation
domain rules
networking
database
use cases
```

也可以再用 Compose Multiplatform 共享 UI。

如果一会儿拿“只共享 domain 的 KMP”比较 UI 复用率，一会儿又拿“共享全部 Compose UI 的 KMP”比较原生体验，KMP 一列就变成了会移动的靶子。

所以本文固定候选：

> KMP 共享业务与数据，Android 使用 Jetpack Compose，iOS 使用 SwiftUI。

Compose Multiplatform 会在下一篇单独作为 UI ownership 决策。Kotlin 官方也明确区分了“共享逻辑、保留原生 UI”和“进一步共享 UI”两条路径，推荐工程结构甚至建议为 native UI consumer 与 shared UI consumer 分离 module。参见 [KMP overview](https://kotlinlang.org/docs/multiplatform/kmp-overview.html) 与 [recommended project structure](https://kotlinlang.org/docs/multiplatform/multiplatform-project-recommended-structure.html)。

比较范围清楚以后，四种架构才真正可比。

## 纯原生共享得最少，边界也最直接

纯原生的结构很容易理解：

```text
Android product
→ Kotlin
→ Jetpack Compose / Android APIs

iOS product
→ Swift
→ SwiftUI / Apple APIs
```

两端可以拥有相似的 layer、命名和产品规则，但 App implementation 默认分别存在。

它并不是“零共享”。团队仍然可以共享：

- OpenAPI/GraphQL schema；
- 设计 token；
- 测试语料；
- analytics event schema；
- 后端契约；
- 产品验收标准。

不共享的是运行在 App 内的实现。

它的主要优势不是某种抽象的“性能最好”，而是 ownership 直接：Android 问题在 Android 栈，iOS 问题在 Apple 栈，新平台 API 可以立刻由平台代码使用。

代价也同样直接：同一条业务规则可能实现两次、测试两次、修复两次。两端发布节奏、边界条件和错误映射容易逐渐分叉。

纯原生适合的不是“追求极致的团队”这种空话，而是共享收益小于共享边界成本的产品。例如：

- 产品核心就是最新平台能力；
- Android 与 iOS 交互本来就显著不同；
- 两端有稳定、完整、独立的团队；
- 业务规则主要在服务端，客户端很薄；
- 一个额外 runtime/interop boundary 无法进入故障域。

## KMP 共享规则，但不接管宿主

本文的 KMP 结构是：

```text
Android Compose / ViewModel ─┐
                             ├→ host adapter → shared Kotlin
iOS SwiftUI / ViewModel ─────┘                 ↓
                                        network + database
```

Android 直接消费 JVM bytecode。iOS 通过 Kotlin/Native framework、Swift export/Objective-C interop 或平台 wrapper 消费共同能力。

它的关键不是“把 Kotlin 跑在 iPhone 上”，而是让同一条业务语义只有一个 owner：

```text
refresh merge rule
offline conflict policy
validation
authorization decision
money/date calculation
database migration semantics
```

UI、导航、生命周期、权限弹窗和平台体验仍由 Android/iOS 各自掌握。

这让 KMP 很适合已经有两套原生 App 的 brownfield：可以从一条 use case 或一个 repository 开始，而不必换掉 screen。第 17 篇已经让 PocketLab 的 Compose/SwiftUI ViewModel 依赖宿主契约，KMP 只出现在 adapter 与 composition root。

但 KMP 没有消灭平台成本。团队仍要治理：

- Kotlin/Swift API 形状；
- coroutine 与 Swift concurrency；
- Kotlin/Native compile/link；
- framework/XCFramework 分发；
- 两端 UI 实现；
- platform `actual` 与 native SDK adapter；
- 跨 module 的 ownership。

如果团队只有 Android 工程师，却没有人对 SwiftUI 体验、Xcode build 和 Apple release 负责，“使用 KMP”不会自动长出 iOS 能力。

## Flutter 共享的是一套 rendering system

Flutter 官方架构把 App 分成 Dart application、Flutter framework、engine 和 platform embedder。开发期 Dart VM 支持 stateful hot reload，Release 在 native 平台编译为机器码；engine 负责栅格化，embedder 连接操作系统事件、渲染 surface 与平台服务。参见 [Flutter architectural overview](https://docs.flutter.dev/resources/architectural-overview)。

简化后是：

```text
Dart widgets + application state
→ Flutter framework
→ Flutter engine
→ Android / iOS embedder
→ platform services
```

Flutter 的优势不只是“一份 Dart 代码”。更重要的是，产品拥有一套跨平台 rendering vocabulary：layout、painting、gesture、animation、theme 和 component 都能在同一个系统里表达。

这对以下产品很有吸引力：

- 强品牌视觉，而不是追随每个平台的系统组件；
- greenfield，几乎没有 native UI 资产；
- 一个 mobile team 必须同时交付两端；
- UI 迭代远多于平台特性接入；
- 还要覆盖 desktop 或 Web，并接受各 target 的差异。

平台能力并没有消失。相机、支付、系统分享、地图或自有 SDK 需要 plugin、platform channel、FFI 或 native code。

Flutter 也支持 [add-to-app](https://docs.flutter.dev/add-to-app)：Flutter view 可以进入存量 Android/iOS App，甚至形成 native→Flutter→native 的混合导航。但“可以嵌入”不等于“嵌入没有成本”。官方架构说明 engine 需要初始化 Dart runtime、isolate 和 rendering surface，并建议在展示首个 Flutter screen 前预热。

所以 Flutter 的核心问题不是“像不像原生”，而是：

> 我们是否愿意让这套 rendering system 成为产品 UI 的 owner，并长期承担 engine、plugin 与 native embedding 边界？

如果答案是愿意，它可能非常高效；如果产品硬要求 Compose/SwiftUI 直接拥有主界面，它就不是当前候选。

## React Native 共享 React feature，并连接 native components

React Native 的表面体验是用 TypeScript/JavaScript 和 React component 描述 mobile UI。

当前架构不能再用一句“JS 通过老 bridge 调原生”概括。官方 [New Architecture](https://reactnative.dev/architecture/landing-page) 使用 JSI 连接 JavaScript 与 C++/native 对象，Fabric 负责新的 rendering system，Turbo Native Modules 提供 native module 边界；New Architecture 从 0.76 起已默认开启。

简化后是：

```text
TypeScript + React
→ Hermes + React renderer
→ Fabric components / TurboModules through JSI
→ Android / iOS
```

React Native 的独特组织价值是 React/TypeScript team fit。

如果公司已经有成熟 Web feature team、React component 思维、TypeScript 基础设施与 JavaScript on-call，移动端可以复用的不只是某些函数，还包括：

- 招聘与培养路径；
- 状态管理经验；
- lint/test/tooling 习惯；
- 部分 domain/package；
- feature ownership 模型。

这不代表 Web component 可以原样复制到 mobile，也不代表 CSS/DOM 语义会自动存在。React Native 使用自己的 core/native components，平台差异和 native module 仍需要处理。

它也支持 [integration with existing apps](https://reactnative.dev/docs/integration-with-existing-apps)：官方文档明确支持向存量 App 添加一个 view、screen 或 user flow。当前 iOS integration 需要 app-owned lifecycle/factory，Android 需要 React host/activity 和 Metro/Release bundle 流程。

因此 React Native 的关键问题是：

> React/TypeScript 是否真的是我们已经拥有并准备让它承担 mobile on-call 的能力？

如果答案是否，单纯因为“JavaScript 人多”而选它，可能只是把平台复杂度交给一群没有 native ownership 的人。

## 四种方案真正共享的东西不同

把架构压成一张表：

| 方案 | 共同 owner | UI owner | 主要边界 | 默认仍然平台化的部分 |
|---|---|---|---|---|
| 纯原生 | 服务端契约/规范 | Compose + SwiftUI | Android/iOS 两套实现 | 业务规则、UI、release |
| KMP + 原生 UI | Kotlin domain/data | Compose + SwiftUI | Kotlin/Native + host adapter | UI、导航、生命周期 |
| Flutter | Dart application/UI | Flutter rendering system | engine/embedder/channel/plugin | native SDK 与 host integration |
| React Native | TypeScript/React feature | React + native components | Hermes/JSI/Fabric/TurboModule | native SDK、host integration、部分组件 |

这张表比“复用率 80%”更有用，因为它告诉你哪里会出现第二种语言、第二套工具链和第二组 on-call 知识。

## 代码复用率是一道分母题

假设一个团队说 Flutter 复用了 90%，另一个团队说 KMP 复用了 60%。谁更高效？

不知道。

先问分母里有没有：

- generated code；
- tests；
- platform adapters；
- build scripts；
- design system；
- native SDK wrapper；
- analytics/diagnostics；
- migration compatibility；
- CI/release code。

再问一行代码的成本是否相同。

一个跨运行时的 camera plugin 可能只有 500 行，却决定发布风险；两端各自 3,000 行 declarative UI 也许结构清楚、很少同时修改。

更危险的是把共享率当 OKR。团队会为了提高数字，把 platform-specific code 塞进条件分支，或者让 UI 依赖不适合导出的 shared type。数字上升，边界反而变差。

比共享率更有决策价值的指标是：

```text
同一业务变更需要改几个 owner？
一次故障跨越几个 runtime/toolchain？
一个平台新能力多久能进入生产？
同一用户旅程由几个团队发布？
升级失败时能否回滚一个 feature slice？
```

## 先写 hard gate，再给偏好打分

技术选型会上最常见的表格是：

```text
性能 30%
效率 30%
生态 20%
招聘 20%
```

然后每个人给自己喜欢的框架打 5 分。

问题不只是评分主观，而是平均值允许荒谬补偿：

```text
不满足法规边界 -5
开发体验很好 +5
总分 0，可以接受
```

不可以。

必须先区分：

### Hard gate

不满足就退出候选：

- 法规与数据驻留；
- 必须支持的平台/系统版本；
- 必须保留的存量资产；
- 已确定的交付团队；
- 不可降级的 accessibility/offline/device 能力；
- 没有 adapter 也必须立即使用的平台 API；
- 不允许进入故障域的 runtime。

### Weighted preference

可以互相权衡：

- Kotlin/React/Dart team fit；
- UI 共享程度；
- 构建反馈；
- 招聘供给；
- 品牌一致性；
- brownfield 便利；
- plugin/library 成熟度。

顺序必须是：

```text
hard constraints
→ eligible candidates
→ weighted fit
→ spike
```

不是先算总分，再给结果补一个理由。

## PocketLab 的两个 gate

PocketLab 已经有 Compose 与 SwiftUI，而且迁移目标是共享 business/data，不是替换 UI。

因此声明：

```text
native_ui_ownership >= 5
preserve_native_assets >= 5
```

四种候选在当前模型中的结果：

| Strategy | Gate | Weighted fit |
|---|---|---:|
| KMP with native UI | pass | 92.2 |
| Pure native | pass | 77.8 |
| React Native | fail | 57.8 |
| Flutter | fail | 52.8 |

Flutter 与 React Native 不是因为“技术不行”而退出。它们都支持存量集成，但本文候选会改变目标 feature 的 UI/runtime owner，不满足 PocketLab 当前 gate。

纯原生通过 gate，却会重新复制已经由共同测试验证的业务规则。

所以 KMP 是当前候选。

注意用词：候选，不是自然法则。

## 换一个场景，Flutter 会领先

现在清空 PocketLab 的历史，换成：

```text
全新产品
一个 mobile feature team
强品牌视觉
一套共享 UI 是 launch constraint
平台特性深度一般
```

新的 gate 是：

```text
share_ui_delivery >= 4
```

结果：

| Strategy | Gate | Weighted fit |
|---|---|---:|
| Flutter | pass | 85.6 |
| React Native | pass | 80.8 |
| KMP + native UI | fail | 57.6 |
| Pure native | fail | 38.4 |

Flutter 领先，但只领先 4.8 分。

小于 5 分意味着什么？

不是把 Flutter 的某个 rating 从 4 改成 5，让表格看起来更确定。

而是停止调表，做同题 spike。

## 再换一个团队，React Native 会领先

场景：

```text
现有 React/TypeScript feature team
他们是首个 mobile release 的固定 owner
需要共享 UI
允许为设备能力写 native module
```

hard gate：

```text
react_web_team_fit >= 4
```

只有 React Native 通过，weighted fit 为 82.0。

这时最应该质疑的不是 React Native 的分数，而是 gate：这个团队真的固定吗？他们是否也承担 mobile production on-call？是否有人能 review Kotlin/Swift native module？

如果答案都是肯定的，React Native 是合理候选。

如果“Web 人多”只代表可以借几个人三个月，长期 owner 仍然是 Android/iOS 团队，那么原 gate 是假的，模型必须重算。

## 平台 API 是产品本身时，纯原生会领先

最后一个场景：

```text
产品核心依赖新 OS API
UI 本身就是平台体验
不等待也不维护跨平台 adapter
共享业务规则价值有限
```

hard gates：

```text
native_ui_ownership >= 5
platform_api_velocity >= 5
```

结果出现一个有趣现象：

```text
KMP weighted fit: 88.6
Pure native weighted fit: 87.1
```

KMP 总分更高，却没通过 platform API gate。纯原生仍然胜出。

这不是模型矛盾，而是在提醒团队：

> 一个明确不可接受的架构属性，不能被十个“挺不错”平均掉。

这里的 KMP `platform_api_velocity=4` 是一条可挑战假设：场景假定核心能力还要进入 shared orchestration，因此团队需要维护平台 adapter。如果该 feature 可以完整留在 native side，不等待 shared API，那么应把 rating 调成 5 并重算。透明模型的价值正是让这个分歧暴露出来，而不是把它藏进“跨平台会慢一点”的口号。

当然，如果 KMP 只用于与该 feature 无关的独立 module，它仍可留在同一个 App。技术路线可以是 portfolio，不必要求整个公司只有一种答案。

## Brownfield 不是 KMP 的专属优势

KMP 常被称为渐进式方案，这是真的，但不完整。

Flutter 官方支持 add-to-app，可以嵌入一个或多个 Flutter view/screen；React Native 官方也支持向现有 App 添加单个 view 或 user flow；纯原生本来就能按 native screen 渐进变化。

真正差异是迁移单位：

```text
KMP         → logic/data module or feature seam
Flutter     → Flutter-rendered view/screen/module
React Native→ React-rendered view/screen/module
Pure native → native module/screen/component
```

如果你的目标是“保留 UI，只统一业务规则”，KMP 的迁移单位吻合。

如果目标是“新 screen 由一个团队共享实现”，Flutter/RN 的迁移单位更吻合。

不要问“谁支持渐进迁移”。四者都能。

要问：

> 它允许我以产品真正想替换的那一层为单位迁移吗？

## 性能不是框架名，而是具体工作负载

“纯原生最快”“Flutter 接近原生”“RN 新架构没有 bridge”“KMP 是 native performance”都可能包含真实片段，也都不足以替你的 App 下结论。

需要测量的工作负载至少包括：

- process cold/warm start；
- 首个可交互 screen；
- 长列表与图片；
- animation/frame timing；
- database/network 并发；
- 大对象跨边界；
- camera/audio/location stream；
- memory peak 与后台恢复；
- binary/download delta；
- Release crash stack 恢复。

而且必须统一：

```text
同一设备
同一 OS
同一产品旅程
同一数据集
同一 Release optimization
同一 cold/warm definition
同一统计窗口
```

一次模拟器数字只能证明路径可达。

Flutter engine、Hermes/JSI、Kotlin/Native 或 Swift runtime 的存在是架构事实，不是性能结论。只有真实 workload 的分位数和 profile 能告诉你瓶颈在哪。

## “一套代码”不会自动变成“一支团队”

技术选型经常把组织画成这样：

```text
before: Android team + iOS team
after:  cross-platform team
```

现实更像：

```text
feature owners
platform owners
native SDK owners
build/release owners
design/accessibility owners
on-call owners
```

Flutter/RN 可以让 feature UI 由一个团队实现，但平台 plugin、store release 和疑难 crash 仍需要 native 深度。

KMP 可以让 domain/data 有一个 owner，但 native UI 和 Apple consumer 仍需两端 ownership。

纯原生可以让平台责任最清楚，但产品行为一致性要靠契约、测试和协作维持。

所以团队能力不能只统计 headcount。至少要画：

| 能力 | 当前 owner | 方案改变后 owner | 夜间故障谁处理 |
|---|---|---|---|
| feature UI | ？ | ？ | ？ |
| native SDK | ？ | ？ | ？ |
| shared/domain | ？ | ？ | ？ |
| build/release | ？ | ？ | ？ |
| crash/performance | ？ | ？ | ？ |
| dependency upgrade | ？ | ？ | ？ |

最后一列填不出来，说明方案还停留在 demo owner，而不是 production owner。

## 同题 spike，不能各做各的广告

当两个候选接近时，最糟糕的 spike 是：

```text
Flutter 做炫酷动画
React Native 做一个列表
KMP 跑一组 domain tests
纯原生展示最新系统控件
```

四个 demo 分别命中了自己的强项，无法比较。

正确方法是同一条用户旅程：

```text
进入实验列表
→ 本地数据库首屏
→ 远端分页
→ 下拉刷新
→ 中途取消
→ 打开系统相机和分享
→ 模拟 503 与离线
→ 触发一个可符号化 crash
```

共同交付物：

- Android/iOS Release artifacts；
- 两台真实设备录屏；
- accessibility audit；
- cold/warm startup distribution；
- frame timing、memory、binary delta；
- clean/no-op/incremental build；
- crash/source-map/symbol recovery；
- native dependency upgrade；
- owner 与 on-call runbook。

Spike 还要有停止时间。两周后没有证据，就结束实验，而不是让四套工程悄悄进入 production。

## 升级成本必须进入选型

大多数方案演示的是创建新工程，生产团队支付的是第三年升级。

要提前演练：

- Android Gradle Plugin/Xcode/SDK 升级；
- Kotlin/Native/Gradle/plugin 升级；
- Flutter stable 与关键 plugin 升级；
- React Native、React、Hermes、Metro 与 native module 升级；
- target/minimum OS 变化；
- store policy 与 privacy manifest；
- crash symbols/source maps；
- 被弃用 API 的迁移窗口。

当前官方版本也说明变化从未停止。核对本文时，Flutter stable 为 3.50，React Native latest 为 0.88；下一次正式决策时必须重新看 release/support 状态，而不是复制这篇文章的版本号。参见 [Flutter release notes](https://docs.flutter.dev/release/release-notes) 与 [React Native releases](https://reactnative.dev/releases/overview)。

真正值得记录的是：

```text
谁负责升级？
多久升级一次？
允许落后几个 release？
关键依赖不兼容时有什么退路？
升级能否在一周内恢复完整 Release evidence？
```

## PocketLab 为什么继续 KMP

PocketLab 的当前事实是：

- 两端原生 UI 已经存在；
- 共同复杂度集中在业务规则、离线数据库、同步和网络；
- Android 团队的 Kotlin 能力可以直接进入 shared；
- iOS 仍需要 Swift-friendly API 与 lifecycle ownership；
- 第 17 篇已经建立 host contract 和 composition-root rollback；
- Release、Apple distribution、Swift 6 与 observability 已有门禁。

因此继续：

```text
KMP shared business/data
+ Android Compose
+ iOS SwiftUI
```

不启动：

```text
Flutter rewrite
React Native rewrite
回到纯原生复制 shared rules
```

这不是 sunk cost decision。真正支持 KMP 的不是“已经写了很多 Kotlin”，而是当前想解决的问题正好是跨端业务语义重复，同时现有 UI 必须保留。

如果未来问题变成“UI 重复拖慢所有交付”，输入就变了。届时应该评估 Compose Multiplatform、Flutter 或 React Native，而不是拿今天的 ADR 阻止讨论。

完整决策见 [ADR-0016](../docs/adr/0016-keep-kmp-native-ui-for-pocketlab.md)。

## 七种常见选型失败

### 先选赢家，再设计权重

表格只是把偏好伪装成小数点。

### 把代码复用率当用户价值

用户不会为 shared LOC 付费，只会感受到交付速度、质量和体验。

### 比较不同 scope

用 KMP domain-only 对比 Flutter full UI，再说前者复用率低，没有意义。

### 用 Debug Hello World 比性能

它没有真实依赖、数据、平台能力、Release optimization 和诊断链。

### 忽略 native escape hatch 的 owner

“必要时写原生”不是计划。必须有具体团队 review、发布和 on-call。

### 把 vendor architecture claim 当生产证据

官方文档说明能力和边界；你的 Release spike 才说明当前 App 是否满足预算。

### 没有重审条件

技术路线变成身份认同，输入变化也不允许重新计算。

## 一份可以带进评审会的决策卡

```text
Product journey:
Current native assets:
Target platforms:
UI owner required:
Shared behavior required:
Platform APIs and devices:
Hard gates:
Weighted preferences:
Current team and 24/7 owners:
Runtime boundaries accepted:
Release and rollback unit:
Two closest candidates:
Spike scope and deadline:
Performance budgets:
Upgrade rehearsal:
Decision owner:
Review date:
Reversal triggers:
```

评审会上先批准 hard gates，再看 score。否则任何人都能通过调权重得到自己想要的答案。

## 回到会议室

现在 CTO 再问：“复用了什么，谁还写平台代码，出了问题谁值班？”

四个方案都能给出诚实答案：

```text
纯原生：复用契约，平台团队分别拥有实现与故障。
KMP：复用业务与数据，平台团队拥有 UI，共同团队拥有 shared。
Flutter：复用 application/UI，platform owner 负责 plugin 与 host 边界。
React Native：复用 React feature，JS 与 native owners 共同负责 runtime 边界。
```

这时讨论不再是“哪个框架最先进”，而是：

> 哪种 ownership 与我们真正的产品、资产、团队和故障预算匹配？

PocketLab 的答案是 KMP + 原生 UI。

另一个团队完全可能得到 Flutter、React Native 或纯原生。只要输入透明、gate 可审查、spike 同题、退出条件明确，那就是一份比“复用率最高”成熟得多的技术决策。

## 动手挑战

运行本轮实验：

```shell
cd labs/18-mobile-strategy
./scripts/verify-mobile-strategy.sh
ruby scripts/evaluate_strategy.rb --markdown
```

然后：

1. 用你的产品替换四个示例 scenario；
2. 删除一个与你无关的 criterion；
3. 新增一个真正不可妥协的 gate；
4. 让 Android、iOS、Web、QA 和 SRE 分别挑战 rating；
5. 找出 eligible top two；
6. 如果差距小于 5 分，写一份两周同题 spike；
7. 为最终决定写 owner、review date 和 reversal trigger。

如果改动一个 rating 就让 winner 翻转，不要继续美化表格。

那正是模型在告诉你：还需要证据。

完整步骤和可执行模型见[实验 18：别先选框架，先写不能妥协的约束](../labs/18-mobile-strategy/README.md)。

## 参考资料

- [Kotlin Multiplatform overview](https://kotlinlang.org/docs/multiplatform/kmp-overview.html)
- [KMP recommended project structure](https://kotlinlang.org/docs/multiplatform/multiplatform-project-recommended-structure.html)
- [KMP 与 Flutter 的共享范围说明](https://kotlinlang.org/docs/multiplatform/kotlin-multiplatform-flutter.html)
- [Flutter architectural overview](https://docs.flutter.dev/resources/architectural-overview)
- [Flutter add-to-app](https://docs.flutter.dev/add-to-app)
- [Flutter supported platforms](https://docs.flutter.dev/reference/supported-platforms)
- [Flutter platform channels](https://docs.flutter.dev/platform-integration/platform-channels)
- [React Native New Architecture](https://reactnative.dev/architecture/landing-page)
- [React Native integration with existing apps](https://reactnative.dev/docs/integration-with-existing-apps)
- [React Native native platform](https://reactnative.dev/docs/native-platform)
- [React Native releases](https://reactnative.dev/releases/overview)
- [Android Compose migration strategy](https://developer.android.com/develop/ui/compose/migrate/strategy)
- [SwiftUI UIKit integration](https://developer.apple.com/documentation/swiftui/uikit-integration)

> 本文的架构资料与版本状态核对于 2026-11-20：Flutter stable 3.50、React Native latest 0.88。PocketLab 没有安装 Flutter SDK、React Native dependencies，也没有用空白工程伪造性能结论；可执行实验验证的是决策过程，不是四种 runtime 的速度。PocketLab mobile binary 仍为 0.17.0/build 17，因为本轮没有修改进入 App 的代码。
