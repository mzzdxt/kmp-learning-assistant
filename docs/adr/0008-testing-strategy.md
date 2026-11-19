# ADR-0008：按风险分层，并在每个支持目标重新执行共同测试

- 状态：Accepted
- 日期：2026-11-19
- 决策范围：PocketLab 多平台自动化测试与 CI 门禁

## 背景

PocketLab 已包含共同领域规则、Ktor 请求、SQLDelight 数据库、协程状态流、Apple 互操作、Android ViewModel、Compose UI 与 SwiftUI App。

此前测试主要覆盖共同规则、平台 adapter、iOS bridge 和 Android ViewModel；两端 App 已能手工启动，但缺少：

- 一条同时穿过 remote、UseCase、Repository、SQLDelight transaction 与 Flow 的共同层集成测试；
- Android Compose semantics / intent 测试；
- 启动真实 SwiftUI App 并穿过 KMP 与数据库的 iOS UI smoke test；
- 对“不同测试场景数”与“跨目标执行数”的统一报告口径。

若只在 JVM 运行共同测试，会遗漏 Native compiler、driver 和 runtime 差异；若只使用端到端测试，则反馈慢、失败定位远，并重复大量业务分支。

## 决策

采用按责任与风险分层的测试策略：

1. 共同层纯规则与状态机放在 `commonTest`，数量最多；
2. 关键共同接缝使用真实实现集成，外部不稳定边界使用 deterministic fake / mock engine；
3. 每个支持 target 必须执行共同测试，不以 JVM 成功代替 Android Host 或 iOS Native；
4. `expect/actual`、平台 driver、Apple interop 与生命周期行为由平台 source set 测试；
5. Android ViewModel、Compose semantics 和 SwiftUI/XCTest 测试留在平台工程；
6. UI 测试只覆盖少量高价值可见行为，不重复共同层的所有业务分支；
7. 异步测试等待条件或使用虚拟调度器，不以固定 sleep 作为同步方式；
8. 测试数据与数据库必须隔离，测试可独立、任意顺序、重复执行；
9. 报告同时给出不同场景数与实际执行数；
10. CI 按反馈速度拆分快速 PR、平台 PR、主分支 UI smoke 与发布/定时车道。

## 当前分层

| 层 | 主要工具 | 责任 |
|---|---|---|
| 共同单元 | `kotlin.test`、coroutines-test | 规则、错误分类、状态机、用例编排 |
| 共同集成 | test SQL driver、真实 Repository / UseCase | transaction、mapping、Flow 与组件协议 |
| 平台 KMP | JVM / Android Host / iOS Native tests | actual、engine/driver、interop、取消与 close |
| Android presentation | JUnit、AndroidX ViewModel | 生命周期与 UI state 翻译 |
| Android UI | Compose UI Test | semantics、渲染与 intent |
| iOS UI | XCTest / XCUIAutomation | SwiftUI App、KMP framework、数据库与用户旅程 |

## 测试替身规则

- 纯规则测试可以 fake 自己定义的端口，以控制输入并精确定位；
- HTTP 请求与解码使用 Ktor MockEngine，不依赖公网；
- 共同集成测试不得把被验证的 Repository / database seam 替换为 fake；
- 端到端 UI 测试可以使用确定性 demo remote，但必须使用真实 App 装配、数据库和平台桥；
- 真实后端契约由独立 staging/contract 门禁承担，不伪装成本地单元测试；
- 不 mock Kotlin/Native 或 UI framework 内部实现，只断言本项目拥有的可见行为。

## 当前计数口径

2026-11-19 基线：

| 来源 | 不同场景 | 目标执行 |
|---|---:|---:|
| `commonTest` | 37 | 111 |
| JVM 平台 | 1 | 1 |
| Android Host 平台 | 1 | 1 |
| iOS 平台 / interop | 6 | 6 |
| Android ViewModel | 2 | 2 |
| Android Compose UI | 2 | 2 |
| iOS UI | 1 | 1 |
| **合计** | **50** | **124** |

“124”不能表述为 124 个不同业务场景；“50”也不能表述为已在 50 个平台环境验证。两者必须带口径。

## CI 门禁建议

### 快速 PR

- JVM common/platform tests；
- Android Host common/platform tests；
- Android ViewModel tests；
- schema / migration verification。

### 平台 PR

- iOS Native tests；
- Apple framework build/link；
- Android Compose instrumentation tests。

涉及 Apple 或 Android 平台边界的变更必须运行对应车道，不允许只依靠文件路径粗略跳过高风险测试。

### 主分支

- Android App assemble + UI smoke；
- iOS Xcode build + XCTest UI smoke；
- 保存测试报告、`.xcresult` 与失败截图。

### 定时 / 发布

- staging backend contract；
- 更多 OS/device matrix；
- 性能、启动时间、数据库 migration 与二进制检查。

## 后果

### 正面

- 共同规则只维护一套断言，同时获得多目标运行证据；
- 平台风险由最接近其所有权的测试捕获；
- 关键数据库与异步接缝不被全部 fake 掩盖；
- UI 测试数量受控，完整旅程仍有真实证明；
- 失败更容易映射到规则、driver、interop、presentation 或 UI 所有者；
- 计数口径避免用跨目标重复执行夸大业务覆盖。

### 成本

- 需要 Android emulator 和 iOS Simulator，CI 环境更复杂；
- Native 与 UI 车道比 JVM 慢；
- Xcode / CoreSimulator destination 状态可能造成环境失败；
- test driver 与生产 driver 的差异仍需平台测试与少量端到端测试补足；
- 团队必须维护测试数据隔离、报告附件和 flaky test 所有权。

## 被拒绝的方案

### 只在 JVM 运行 `commonTest`

反馈最快，但无法证明 Kotlin/Native compilation、Native driver、Android Host actual 或目标运行时行为，因此拒绝。

### 把所有测试都做成端到端 UI

看似最真实，但运行慢、定位差、容易受环境影响，还会把规则分支与 UI 操作重复组合，因此拒绝。

### 为每个 target 复制同一套测试源码

会制造维护分叉。能表达为平台无关语义的测试必须放在 `commonTest`，由 Gradle 为各 target 编译执行。

### 固定 70/20/10 测试比例

比例无法表达功能风险、平台边界与失败成本。测试形状应由责任和风险产生，比例只作为事后观察，不作为硬指标。

### 依赖公网完成普通 PR 测试

网络、服务端数据和鉴权不可控，会降低确定性并模糊失败所有权。真实协议验证单独进入 contract/staging 车道。

## Flaky test 处理

- 第一次偶发失败保存完整日志、设备信息和附件；
- 重跑用于判断稳定性，不用于把红灯自动改绿；
- 若需暂时隔离，必须记录所有者、原因、影响与恢复期限；
- 不通过扩大 sleep 或 timeout 隐藏竞态；
- 环境故障与产品故障分别分类，但都保留可追踪结果。

## 重新评估条件

- 支持 target 增减；
- 共享 UI 或共享 ViewModel 改变平台测试边界；
- Swift Export / 新并发模型改变 Apple interop 风险；
- CI 时长显著影响交付，需要基于历史失败率重新分车道；
- UI 测试持续 flaky，且证据显示责任已被更低层覆盖；
- 引入生产服务端与上传协议，需要 contract、outbox 和 migration 新门禁。

## 验证

- JVM 38/38、Android Host 38/38、iOS x64 43/43；
- Android ViewModel 2/2；
- Android Compose UI 在 Pixel 2 API 34 模拟器 2/2；
- iOS XCTest UI 在 iPhone 16 Pro iOS 18.4 模拟器 1/1；
- 50 个不同场景，124/124 次自动化执行；
- iOS UI 测试完成冷启动、3 条数据库记录、首次刷新和幂等刷新验证。
