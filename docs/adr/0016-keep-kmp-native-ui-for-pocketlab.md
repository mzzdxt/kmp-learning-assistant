# ADR-0016：PocketLab 继续使用 KMP 共享逻辑并保留原生 UI

- 状态：已接受
- 日期：2026-11-20
- 决策范围：PocketLab 当前 Android/iOS 产品与团队，不作为通用框架排名

## 背景

PocketLab 已经拥有：

- Kotlin/Compose Android App；
- Swift/SwiftUI iOS App；
- 共享 domain、network、SQLDelight repository 与 refresh use case；
- Kotlin/Swift 宿主契约和 composition-root 切换点；
- Android Release、Apple framework/distribution、Swift API 与 observability 门禁。

进入技术负责人阶段后，需要确认继续投资 KMP 是否仍然合理，而不是因为仓库已经写了 Kotlin 就默认延续。

候选方案为：

1. KMP 共享业务与数据，保留 Compose/SwiftUI；
2. Flutter 统一 Dart application 与 UI；
3. React Native 统一 TypeScript/React feature 与 UI；
4. 纯原生，Android/iOS 分别实现 App 逻辑。

Compose Multiplatform shared UI 不在本决策范围，将作为独立 UI ownership 决策评估。

## 决策方法

使用 `labs/18-mobile-strategy/decision-model.json` 记录：

- 11 个 scenario criteria；
- 每个候选的 PocketLab-specific rating；
- 不可由平均值补偿的 hard gates；
- 场景权重；
- expected winner regression assertion。

计算顺序固定为：

```text
architecture facts
→ hard gates
→ weighted fit
→ candidate
→ implementation spike when evidence is close
```

评分不是性能 benchmark，也不是供应商能力排名。它让假设可见并支持敏感性讨论。

## PocketLab 的硬约束

### 直接拥有原生 UI

Compose 和 SwiftUI 继续由平台 owner 维护。当前迁移目标不包含用另一套 rendering system 替换两端 UI。

模型 gate：

```text
native_ui_ownership >= 5
```

### 保留存量原生资产

现有 Android/iOS module、测试、发布、诊断与平台生命周期不能作为一个 big-bang launch 被替换。

模型 gate：

```text
preserve_native_assets >= 5
```

Flutter 与 React Native 都支持 brownfield embedding，但在本轮候选定义中，它们会改变目标 feature 的 UI/runtime owner，因此不通过这两个 gate。这个结论不否定它们的 add-to-app 能力。

## 决策

PocketLab 继续采用：

```text
Android Compose / ViewModel ─┐
                             ├→ host contracts → KMP business/data
iOS SwiftUI / ViewModel ─────┘
```

具体规则：

1. KMP 默认共享跨端业务规则、数据一致性、network/database orchestration 和稳定 domain semantics；
2. Android/iOS UI、导航、生命周期和平台特性默认由各自平台实现；
3. shared scope 由用户旅程和数据 ownership 决定，不以代码复用率为目标；
4. KMP 类型通过 platform adapter/host contract 进入宿主，不扩散到 UI；
5. 任何新共享范围都必须保留 Release 构建、测试、诊断和回滚证据；
6. 相近替代方案必须做同题 Release spike，不用 Hello World benchmark 决定；
7. Compose Multiplatform UI 作为独立候选审查，不能默认为 KMP 的附带结果。

## 当前结果

PocketLab 场景中：

| Candidate | Gate | Weighted fit |
|---|---|---:|
| KMP + native UI | pass | 92.2 |
| Pure native | pass | 77.8 |
| React Native | fail | 57.8 |
| Flutter | fail | 52.8 |

纯原生也通过硬约束，但继续复制两端 business/data rules，与当前最主要问题不匹配。

KMP 的优势来自当前存量资产、Kotlin team fit、纵向迁移能力和共享业务目标。若这些输入变化，结果也必须变化。

## 被拒绝的方案

### 用最高代码复用率直接选框架

复用率会随分母、生成代码、测试、平台 adapter 与 UI 范围变化。它不表达故障边界、团队 ownership、发布拓扑或用户体验。

### 把四个 Hello World 的启动时间当作性能结论

空白应用没有真实 plugin/module、database、navigation、diagnostics 和 feature dependency graph。它只能验证工具链安装。

### Flutter 重写当前 App

Flutter 对强品牌一致性和单团队 greenfield 很有吸引力，也支持 add-to-app。当前 PocketLab 没有替换 native UI 的需求，重写会扩大迁移面而不直接解决 business rule duplication。

### React Native 重写当前 App

React Native 对既有 React/TypeScript 团队更匹配，New Architecture 也提供 native component/module 边界。PocketLab 当前没有这类团队约束，改写语言和 runtime ownership 缺少收益来源。

### 回到纯原生并复制 shared rules

纯原生最直接地使用平台能力，也保持简单运行时边界。但 PocketLab 已经用跨目标测试证明共同业务规则有价值，复制它们会重新引入语义漂移。

### 让 weighted score 覆盖 hard gate

不可接受的法规、平台、资产或人员约束不能用另一个高分抵消。修改 gate 必须是产品/组织决策，不能只是 spreadsheet 调参。

## 后果

### 正面

- 继续利用已验证的 shared domain/data 能力；
- 两端保留原生 UI、生命周期和平台 API 直接控制；
- 迁移单位保持为可发布、可回滚的纵向切片；
- 技术选择有明确输入、反例和重审条件；
- Flutter、React Native 与纯原生仍可在输入改变时重新进入候选。

### 成本

- Android/iOS UI 继续存在两份实现与测试；
- Swift interop、framework distribution 与 Kotlin/Native build 仍需治理；
- 平台 adapters 和 host value mapping 会增加边界代码；
- 需要双平台 owner，而不是假设一个 Kotlin 团队自动拥有 iOS 体验；
- 共享范围过大或过小都可能让当前判断失效。

## 重新评估条件

出现任一条件时重新运行实验：

- UI 重复成为主要交付瓶颈；
- 产品要求 Android/iOS/Desktop/Web 使用一套 rendering system；
- React/TypeScript 团队成为 mobile 的固定 owner；
- 新产品完全 greenfield 且没有 native assets；
- 平台 API 或硬件集成成为产品核心；
- KMP interop、Native build、Apple distribution 或招聘成本持续超过预算；
- 关键依赖的支持状态改变；
- 当前 hard gates 被产品和平台 owners 正式修改。

如果剩余候选差距小于 5 分，必须先做同题 Release spike，再更新本 ADR。

所有 rating 都是可挑战输入。例如 deep-platform 场景把 KMP 的 `platform_api_velocity` 记为 4，是因为该场景要求核心能力进入 shared orchestration；若 feature 可以完整留在 native side，应改为 5 并重新计算，而不是保护既有 winner。

## 验证

```shell
cd labs/18-mobile-strategy
./scripts/verify-mobile-strategy.sh
```

门禁验证四种策略、11 个 criteria、四个场景、hard-gate-first 顺序和四个不同 winner。完整结果见 `labs/18-mobile-strategy/results/2026-11-20-decision-model.md`。
