# ADR-0007：原生 UI 配合平台 ViewModel，共享数据与业务内核

- 状态：Accepted
- 日期：2026-11-19
- 决策范围：PocketLab 第一版实验列表 UI

## 背景

PocketLab 已在 `commonMain` 中具备模型、远端读取、刷新 UseCase、SQLDelight 本地事实来源和 Flow。Apple source set 也已经提供带显式取消的异步桥。

下一步需要 Android Compose 与 iOS SwiftUI 真实页面。可选方案包括：

1. Compose Multiplatform 共享 UI；
2. 原生 UI + 共享 Kotlin ViewModel；
3. 原生 UI + 平台 ViewModel + 共享数据/领域层；
4. 两端各自实现完整 feature。

当前团队学习目标包含理解 Android/iOS 生命周期、Swift 互操作和共享边界，且第一版只有一个列表页面。此时最大风险不是 UI 重复，而是把平台生命周期、文案和 FFI 限制反向塞进共同层。

## 决策

选择方案 3：

- Android 使用 Jetpack Compose 与 AndroidX `ViewModel`；
- iOS 使用 SwiftUI 与 `@MainActor ObservableObject`；
- 两端共享 `ExperimentRecord`、Repository、SQLDelight schema、Flow、Refresh UseCase、失败 reason 与同步规则；
- 两端 UI 都只读取数据库事实来源，不直接消费 remote response；
- Android 生命周期使用 `viewModelScope` 与 `collectAsStateWithLifecycle`；
- iOS 生命周期使用 SwiftUI `.task`、`AsyncThrowingStream` 和显式 Kotlin cancellation token；
- 文案、日期格式、列表组件、空状态、导航与交互属于平台层；
- iOS 通过 `IosPocketLabFeature` 作为 composition root，显式关闭 bridge、network 和 database；
- Kotlin/Native 导出对象的 `Sendable` 豁免只允许出现在经过 Main-dispatcher / MainActor 审计的窄盒子中。

## 所有权

| 资源或状态 | 所有者 | 结束条件 |
|---|---|---|
| Android database handle | `AndroidPocketLabGraph` | Android process 结束 |
| Android UI state production | Android ViewModel | `onCleared` / `viewModelScope` 取消 |
| Compose collection | Composable lifecycle | Lifecycle 离开 active state |
| iOS database/network/bridge | `IosPocketLabFeature` | feature `close()` |
| Swift observable state | `ExperimentListViewModel` | ViewModel 释放 |
| SwiftUI observation task | SwiftUI `.task` | View identity 消失或 task 取消 |
| Kotlin Flow Job for iOS | `IosExperimentListBridge` | token cancel 或 bridge close |

## 后果

### 正面

- commonMain 保持平台无关，不依赖 UI framework；
- 两端使用原生生命周期、本地化、预览、无障碍与交互组件；
- UI 变化不会迫使共享模块发布新的视觉 API；
- 业务规则仍只有一份，离线与同步语义不会分叉；
- Swift 互操作复杂度被限制在 Apple bridge / ViewModel；
- 可以用真实重复度数据决定未来是否共享 ViewModel 或 UI。

### 成本

- 两端各有一份 ViewModel、UI state 与界面代码；
- loading/notice 等呈现状态可能重复；
- 新字段需要同时接入 Compose 与 SwiftUI；
- iOS 仍需维护 AsyncSequence adapter 和 FFI 并发审计；
- 视觉一致性依赖设计规范，而不是一份渲染代码。

## 被拒绝的方案

### 立即使用 Compose Multiplatform 共享 UI

可显著提高 UI 代码复用，也可能适合后续桌面端。但当前里程碑需要先建立原生宿主、direct integration、Swift 取消与最终链接基线；同时引入共享 UI 会把问题空间扩大到导航、资源、可访问性与 iOS 组件适配。

拒绝的是“现在就上”，不是永久拒绝 Compose Multiplatform。

### 立即共享 Kotlin ViewModel

官方支持共享 ViewModel，这对两端结构高度一致的页面很有价值。当前列表的状态很小，平台 ViewModel 还能直接使用各自生命周期与本地化系统；为删除少量重复代码而新增 Swift 观察包装、生命周期约定和导出 API，收益不足。

### 两端各自实现 Repository 和刷新逻辑

会复制离线规则、失败分类、数据库合并与 pending 保护，直接破坏 KMP 的核心收益，因此不接受。

## 重新评估条件

满足任一条件时重新评估共享 ViewModel：

- 三个以上页面重复同一套复杂状态机；
- 平台 ViewModel 代码逐行相同且平台差异很薄；
- Swift Export 或成熟生成工具显著降低状态流与生命周期适配成本；
- 团队愿意由共享模块拥有更高层 presentation API。

满足任一条件时评估 Compose Multiplatform UI：

- 两端产品设计长期高度一致；
- UI 重复成为可量化的主要交付瓶颈；
- 桌面端加入并能放大共享收益；
- iOS 的无障碍、输入、导航、性能和调试体验通过真实原型；
- 已定义平台 fallback 与回滚边界。

## 验证

- JVM 37、Android Host 37、iOS x64 42 条共享/平台测试通过；
- Android ViewModel 2 条单测通过；
- Android debug APK 成功构建并在 API 34 模拟器冷启动；
- Xcode Swift 6 App 成功编译、链接并在 iOS 17.5 模拟器启动；
- 两端均显示由共享 demo remote 经 SQLDelight 写入的 3 条记录；
- SQLDelight migration verifier 与 Apple framework link 继续通过。
