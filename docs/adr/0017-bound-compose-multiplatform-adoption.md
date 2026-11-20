# ADR-0017：Compose Multiplatform 只作为独立叶子组件候选

- 状态：已接受
- 日期：2026-11-20
- 决策范围：PocketLab 当前 Android/iOS production UI 与 `sharedUi` 实验

## 背景

PocketLab 已采用：

```text
Android Compose / ViewModel ─┐
                             ├→ KMP domain + data
iOS SwiftUI / ViewModel ─────┘
```

ADR-0016 决定继续 KMP shared logic + native UI，但要求把 Compose Multiplatform 作为独立 UI ownership 候选审查，而不是默认为 KMP 的附带结果。

两端存在可见 UI 重复，例如实验摘要卡片。需要确认 shared UI 能否在不改变 navigation、lifecycle、database、network 与 production release ownership 的前提下创造价值。

## 候选范围

本轮只评估一个 leaf component：

```text
ExperimentSummaryCard
= title + hypothesis + status + open intent
```

不评估：

- 完整实验列表页；
- navigation/back stack/deep link；
- 共同 ViewModel；
- platform view integration；
- 整 App shared UI shell；
- production rollout。

## 实验证据

新建独立 `:sharedUi` module，且 production `:domain`、`:shared`、`:androidApp` 均不依赖它。

### 版本兼容

1. Compose Multiplatform 1.12.2 + `iosX64`：因 1.11.1 起移除 Apple x86_64 artifacts 而无法解析；
2. 1.12.2 + arm64 Apple targets：Debug/iOS consumer 可构建，但 Android Release AAR 要求 compileSdk 37 / AGP 9.1，超过当前 36 / 8.13.2 baseline；
3. 1.11.1 + foundation-only：在当前 baseline 完整通过。

本轮没有加入 1.11.x alpha Material 3 artifact，也没有借 spike 升级整个 Android build chain。

### 构建与 consumer

- Android host contract tests：2/2；
- Android AAR：生成成功；
- iOS device Release framework：生成成功；
- iOS Simulator arm64 Release framework/test executable：交叉编译成功；
- Swift 6 complete strict concurrency consumer：通过；
- Apple header：157 行；
- internal presentation model leaks：0。

Intel host 无法运行 arm64 Simulator test，因此不记录 iOS runtime passed。

### 资源峰值

三个 Kotlin/Native link tasks 并行时，当前 2 GiB Gradle daemon OOM。`--max-workers=1` 在相同 heap 完成全部产物，但增加 wall time。

当前证据只支持治理并发峰值，不支持无条件提高 heap，也不支持用一次 wall time 推导日常开发速度。

## 决策

PocketLab 采用“有边界的继续实验”：

```text
production default = Android Compose + iOS SwiftUI
optional candidate = isolated sharedUi leaf component
```

具体规则：

1. `sharedLogic` 与 `sharedUi` 保持独立 modules；
2. 原生 UI consumer 不因业务逻辑被迫依赖 Compose Multiplatform；
3. `sharedUi` 当前只拥有 presentation、accessibility semantics 与宿主 callback；
4. navigation、lifecycle、persistence、network 和 domain records 不进入 `sharedUi`；
5. iOS 只消费窄 `UIViewController` factory，内部 UI models 使用 `@HiddenFromObjC`；
6. `sharedUi` 不进入现有 `PocketLabShared` umbrella framework；
7. production modules 在完成下一轮 runtime/quality evidence 前不得依赖 `sharedUi`；
8. Apple x86_64 不再作为 shared UI target；Apple Silicon CI 负责 Simulator runtime；
9. Native link gate 在当前 2 GiB baseline 使用 single worker，CI 资源调整需独立 benchmark；
10. Compose/AGP/API 版本升级作为单独迁移批准，不由 UI spike 隐式触发。

## 被拒绝的方案

### 立即把现有 SwiftUI 页面改成共同 Composable

一张静态卡片没有验证页面 lifecycle、navigation、输入、platform view、VoiceOver、Dynamic Type 或真实设备性能，不能为完整页面背书。

### 把 `sharedUi` 塞进现有 `shared` umbrella

这会让只需要业务/数据的 consumer 也引入 Compose dependencies，并扩大既有 framework、ABI 和构建故障域。

### 为 spike 直接升级 AGP 9.1 / compileSdk 37

这会把 Android build-chain migration 与 UI ownership 混成一个实验，失败原因和回滚范围不可分。

### 继续声明 `iosX64`

Compose Multiplatform 1.11.1+ 不发布 Apple x86_64 artifacts。隐藏或绕过这个事实不会产生可运行 runtime。

### 用 framework binary 大小宣布 App 包体成本

static framework 是链接输入，不是最终 App 下载物。包体必须比较同配置 Release App 的 before/after。

### 通过增大 heap 隐藏并行 OOM

当前已有 single-worker 对照证明问题是并发峰值。heap、workers 和 CI lane 需要独立 A/B，不能把资源修改当无成本修复。

## 正面后果

- 可以验证共享 UI 价值，而不改动 production runtime；
- Kotlin/Compose 开发者能复用已有技能；
- 一个组件同时约束 Android/iOS presentation 与 accessibility semantics；
- 依赖、ABI、构建与回滚范围保持可见；
- 失败时可直接删除 optional module，不涉及产品数据；
- 为 Apple Silicon CI、真实设备和 build-chain migration 提供明确待办。

## 成本

- 新增 Compose UI dependency/version matrix；
- iOS 仍需要 UIKit/SwiftUI container 与 Xcode expertise；
- Apple x86_64 消失后，当前 Intel host 无法运行 shared UI Simulator test；
- Kotlin/Native link 增加构建时间与峰值资源；
- shared UI 改动会形成跨平台共同故障域；
- 原生 UI 与 optional shared UI 并存期间需要控制重复和过期时间；
- 完整页面采用仍需大量未完成证据。

## 扩大范围的准入条件

从 leaf component 扩大到 stateful component 或完整页面前，必须同时满足：

- Apple Silicon `iosSimulatorArm64Test` 实际运行；
- 真实 iPhone rendering smoke；
- VoiceOver、Dynamic Type、dark mode 和 RTL 验收；
- Android/iOS screenshot 或可审查视觉证据；
- Release App startup/frame/memory/size before/after；
- navigation/lifecycle/platform-view owner 明确；
- 当前 Compose/AGP/API upgrade path 已验证；
- iOS owner 接受共同 runtime、诊断与 on-call；
- rollback exercise 不依赖数据回滚。

## 缩回或删除条件

出现任一条件时停止扩大或删除 spike：

- 为平台差异累积大量条件分支；
- UI dependency upgrade 持续阻断任一平台发布；
- accessibility 或 platform view 无法满足产品预算；
- CI/build cost 超出批准预算且没有可验证优化路径；
- iOS production ownership 实际仍无人承担；
- 两端重复实现成本低于共同容器与故障域成本；
- spike 在约定 review window 内没有进入可验收 runtime。

## 验证

```shell
cd labs/02-first-kmp-module
./scripts/verify-compose-shared-ui.sh
```

详细过程和 artifact hashes 见 `labs/19-compose-multiplatform/results/2026-11-20-shared-ui-spike.md`。
