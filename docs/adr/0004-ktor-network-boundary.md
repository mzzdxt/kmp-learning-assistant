# ADR-0004：共享请求语义，按平台选择 Ktor 引擎

- 状态：已接受
- 日期：2026-11-18
- 决策范围：PocketLab 远端实验列表的网络边界、错误模型与资源生命周期

## 背景

PocketLab 需要从 `/v1/experiments` 获取远端实验。请求路径、JSON 协议、状态码语义与 DTO 映射在 Android 和 iOS 上相同；底层网络栈、证书与代理行为、调试工具和系统生命周期则不同。

如果两端各写一套请求，协议解释容易漂移。如果把 Ktor 的 `HttpResponse`、异常或 DTO 直接暴露给领域层，基础设施细节又会扩散到 Store 与 UI。

## 决策

- 在 `commonMain` 定义 `ExperimentRemoteDataSource`、`RemoteExperiment` 与稳定的 `RemoteExperimentsResult`。
- 在 `commonMain` 用 Ktor Client 实现请求、Content Negotiation、kotlinx.serialization 解码、DTO 映射与状态码分类。
- 使用内部 `expect/actual createPlatformHttpClient()` 选择引擎：JVM 使用 CIO，Android 使用 Android engine，iOS x64 使用 Darwin engine。
- 引擎依赖只进入各自平台 source set；Ktor 类型、传输异常和序列化 DTO 不进入公共数据源契约。
- 设置 `expectSuccess = false`，由共同代码显式区分 401、5xx 与其他非 2xx 状态。
- JSON 配置 `ignoreUnknownKeys = true`，允许服务端添加共同客户端暂不使用的字段；缺失必填字段仍视为格式错误。
- `CancellationException` 必须继续抛出；转换失败映射为 `MALFORMED_RESPONSE`，其他传输异常映射为 `CONNECTION`。
- `PocketLabNetwork` 拥有 `HttpClient` 并暴露 `close()`；应用装配层负责按应用或功能生命周期复用并关闭实例。
- `commonTest` 使用 Ktor `MockEngine` 验证请求与响应，不依赖公网；平台测试额外验证真实引擎能够创建和关闭。

## 理由

- Ktor 统一的是请求管线，不会把 iOS 变成 JVM；Darwin engine 仍使用 Apple 平台网络能力。
- 共同协议只实现一次，可让 Android 与 iOS 对同一响应得到相同领域结果。
- 显式的错误代数比跨平台异常稳定，平台 UI 可以自行决定文案与交互。
- 在引擎层测试可以同时验证 URL、HTTP method、headers、状态码和 JSON 解码，比只 fake Repository 更接近真实网络边界。
- 长生命周期 `HttpClient` 能复用连接与线程资源；显式关闭让所有权可见。

## 后果

正面：

- 两端共享请求、协议兼容策略、DTO 映射和错误分类。
- Android、Darwin、CIO 的选择集中在三个很薄的平台文件。
- commonTest 可确定性覆盖成功、鉴权失败、服务端失败、未知状态、坏 JSON、断网与取消。
- 将来替换引擎或加入认证插件，不需要改变领域数据源接口。

代价：

- Ktor 与 serialization 增加依赖和最终二进制体积，后续需要实际测量。
- MockEngine 不能证明 TLS、代理、证书、重定向或平台网络权限一定正确，仍需设备/Simulator 集成测试。
- 当前错误模型刻意较粗，尚未覆盖超时、限流、离线队列和可重试性。
- `PocketLabNetwork` 的关闭仍依赖宿主正确执行；后续装配层需建立清晰所有权。

## 拒绝的方案

### Android 用 Retrofit、iOS 用 URLSession，各自实现协议

会复制路径、DTO 与错误映射，也让两端对服务端演进产生不同解释。只有当平台网络需求差异大到共同实现反而妨碍维护时才重新评估。

### 在公共接口返回 `HttpResponse` 或抛出 Ktor 异常

会让调用方依赖传输库、状态码处理散落，并使未来替换实现成为破坏性变更。

### 依赖 Ktor 自动发现默认引擎

自动选择适合快速开始，但本实验需要明确观察“共同请求 + 平台引擎”的装配关系。显式依赖也能避免 classpath 变化悄悄改变选择。

### 用真实公网完成单元测试

公网响应、DNS 和连接质量不可控，测试慢且不确定。真实后端契约应由独立集成测试覆盖，而不是替代共同单元测试。
