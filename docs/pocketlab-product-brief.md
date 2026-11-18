# PocketLab 产品简报

## 一句话说明

PocketLab 是一个离线优先的个人实验记录器：把“我猜……”“我准备怎么验证……”“结果怎样”变成可追踪的小实验。

它不是为了展示 KMP API 而虚构的 Todo App。实验记录天然包含结构化规则、本地存储、搜索、同步状态、时间与冲突处理，足以让我们逐步遇到真实跨平台问题。

## 第一条用户旅程

```text
创建草稿 → 填写标题与假设 → 校验 → 离线保存 → 出现在实验列表
```

项目从“创建并保存草稿”启动；当前已经加入远端读取、本地数据库、刷新合并和 Apple 并发桥。上传、附件、搜索与原生 UI 继续增量加入。

## 共享业务约束

- 标题必填，去除首尾空白后最长 80 个字符。
- 假设必填，去除首尾空白后最长 240 个字符。
- 笔记可为空；保存时去除首尾空白。
- 校验失败时不得生成 ID、读取时钟或写入仓库。
- 成功保存必须包含稳定 ID 与保存时间。
- 校验结果使用稳定 reason code，而不是共享中文/英文提示文案。

这些规则在 Android 与 iOS 上含义相同，因此进入 `commonMain`。

## 暂不共享的职责

- 表单布局、键盘行为、返回手势与导航。
- 错误提示的本地化文案与视觉呈现。
- Android/iOS 应用生命周期。
- 数据库驱动和平台文件位置——先用仓库接口隔离，选型实验后再实现。
- 系统权限与通知。

## MVP 后续增量

1. 本地实验列表与详情（SQLDelight 列表数据源与 Flow 已完成，详情查询待实现）。
2. 按状态和关键字搜索。
3. Ktor 远端读取、同步与离线队列（远端读取与数据库刷新合并已完成，上传待实现）。
4. 冲突检测和可解释的同步状态机。
5. Android Compose 与 iOS SwiftUI 原生 UI。

## 当前验收标准

- 保存用例不引用 Android、UIKit、数据库或网络类型。
- 业务规则、错误码、用例编排和外部能力接口位于 `commonMain`。
- fake 位于 `commonTest`，不进入生产代码。
- 同一组测试在 JVM、Android Host 与 iOS Simulator 通过。
- Apple 专用互操作行为在 iOS Native 测试中验证，Swift 包装通过 Swift 6 严格并发 type-check。

## 当前共享状态流

保存草稿已从同步调用演进为：

```text
平台 UI 输入
→ DraftEditorStore
→ StateFlow<DraftEditorState>
→ suspend SaveDraftUseCase
→ suspend ExperimentRepository
```

平台 UI 负责生命周期与渲染，共享 Store 负责保存状态机、校验和重复提交约束。Apple Flow 取消桥已经完成编译与行为验证；Android Compose、SwiftUI 具体页面和真实界面生命周期将在下一阶段接入。

## 当前远端读取边界

PocketLab 已加入第一条共同网络管线：

```text
ExperimentRemoteDataSource
→ GET /v1/experiments
→ ContentNegotiation + kotlinx.serialization
→ DTO 映射与稳定失败分类
→ CIO / Android / Darwin 平台引擎
```

共同代码负责请求与协议语义，平台 source set 只选择 Ktor engine。公共接口不暴露 Ktor 类型、传输异常或 JSON DTO。远端列表读取已经通过 `RefreshExperimentsUseCase` 接入本地数据库；平台 UI 将在原生界面阶段只观察数据库 `Flow`。上传与离线队列尚未实现。

## 当前离线优先边界

远端读取已接入本地事实来源：

```text
本地保存 → SQLDelight → PENDING_UPLOAD ─┐
                                        ├→ 数据库 Flow → 平台 UI
Ktor 刷新 → 事务合并 → SYNCED ──────────┘
```

- UI 只观察数据库，不直接消费网络列表。
- pending 本地记录不会被远端刷新覆盖。
- 已同步记录只接受更新的服务器版本。
- 整批刷新在一个事务内合并。
- 当前只有远端读取与本地 pending 标记，尚未实现上传确认、重试、删除 tombstone 或完整 outbox。

## 当前 Swift Concurrency 边界

共同层继续使用原生异步抽象，Apple 边界只负责翻译：

```text
Flow / suspend
→ IosExperimentListBridge
→ callback + NativeCancellable
→ Swift AsyncStream / async
```

- 标准 Objective-C framework 是当前稳定产物。
- Flow collection 与 refresh 各自返回可取消的 Kotlin Job 句柄。
- Swift `AsyncStream.onTermination` 把 Task 取消传播回 Kotlin Job。
- 生产 callback 由 `Dispatchers.Main` 送达，SwiftUI 状态仍应由 `@MainActor` 隔离。
- Swift Export 已支持 suspend 与 Flow 的原生映射，但当前仍为 Alpha；等真实 iOS direct-integration 壳工程建立后再单独评估。

