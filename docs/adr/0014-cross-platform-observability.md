# ADR-0014：用固定事件契约和构建身份定位跨平台故障

- 状态：Accepted
- 日期：2026-11-20
- 决策范围：PocketLab 刷新链路的日志、异常边界、耗时、Android 混淆映射与 Apple 符号文件

## 背景

PocketLab 的一次刷新会穿过 Android Compose 或 SwiftUI、平台 ViewModel、Kotlin suspend 用例、Ktor 和 SQLDelight。功能在两端都能运行以后，新的问题不再只是“结果对不对”，而是：

- 用户说刷新失败时，哪一次操作失败了？
- 业务失败、协程取消和程序缺陷是否被错误地记成同一类错误？
- Android Release 经 R8 混淆后，堆栈还能否还原？
- Apple crash report 是否能找到完全匹配的 dSYM？
- 日志是否泄露实验标题、笔记、服务器地址、数据库路径或异常消息？
- 一条耗时日志能否被误当成性能结论？

如果共同层直接依赖某个崩溃平台 SDK，领域用例会被供应商 API 污染；如果 Android 和 iOS 各自埋一套事件，同一个操作又会获得不同语义。另一方面，试图用一个全局 `catch` 把一切转换为稳定失败，会吞掉取消和程序缺陷，改变产品行为。

因此，可观测性必须成为一个有边界的工程契约，而不是散落的 `println`。

## 决策

### 先区分四种终态

刷新链路使用四种互不混淆的结果：

| 终态 | 含义 | 产品行为 | 诊断行为 |
|---|---|---|---|
| `applied` | 远端成功且本地事务完成 | 返回成功结果 | info，记录数量与耗时 |
| `failed` | 可预期的协议或网络失败 | 返回稳定业务失败 | warning，记录稳定 reason 和可选状态码 |
| `cancelled` | 调用方不再需要结果 | 重新抛出同一个取消异常 | info，记录取消和耗时 |
| `crashed` | 未预期的程序或基础设施异常 | 重新抛出同一个异常 | error，只记录异常类型 |

`CancellationException` 必须先于普通异常捕获并原样抛出。未预期异常同样只观察、不转换，保证崩溃语义、上层处理和测试行为不被日志改变。

### 领域层只拥有生命周期端口

`:domain` 定义 `RefreshDiagnostics` 与单次 `RefreshDiagnosticOperation`：

```text
begin
  └─ exactly one of applied / failed / cancelled / crashed
```

领域层不知道 Logcat、OSLog、崩溃供应商、序列化格式或文件系统。默认实现为 `None`，因此用例可独立使用。诊断后端在 `begin` 或终态写入时抛出的异常会被隔离，不能覆盖业务结果、取消或原始异常。

### 共同层定义固定 schema

共同层把生命周期翻译为版本化的结构化记录：

```text
schema=1 event=pocketlab.refresh phase=finished level=info
operation_id=<id> duration_ms=628 outcome=applied
received_count=3 changed_count=3
```

事件只允许以下字段：

- schema、事件名、阶段与级别；
- 单次操作 ID；
- monotonic duration；
- 稳定 outcome、failure reason 与可选 HTTP status；
- 接收和变更数量；
- 未预期异常的类型名；它只作受限现场线索，不作为跨版本稳定维度，应用类名可能被 R8 改名。

不提供任意 message 或 attributes map。实验标题、假设、笔记、token、URL、数据库路径、异常消息和原始响应没有进入 schema 的位置。字符串 token 会限制为 80 字符，并把换行和其他非允许字符替换为下划线，避免日志注入。

### 耗时只使用 monotonic clock

刷新耗时使用 `TimeSource.Monotonic`。墙上时钟可能因 NTP、时区、用户设置或系统校时向前或向后跳变，不适合计算 duration。事件不把单次耗时解释为性能预算；它只是定位一次操作的证据。

### 平台后端保持薄

共同层通过一个窄 `expect/actual` writer 输出同一份 schema：

- Android 使用 `android.util.Log`；
- JVM 使用 stdout/stderr，便于本地实验；
- 当前 iOS 实验适配器使用 `NSLog`。

iOS 生产应用应将这个适配器替换为 Apple unified logging 的 `Logger`/OSLog，并为动态敏感值显式选择隐私级别；性能区间使用 signpost，而不是扩大领域端口。当前 `NSLog` 是可运行的最小实验后端，不代表推荐的最终远端日志方案。

### Release 产物和符号文件必须成对保存

Android Release 开启 R8 minify 与 resource shrink，并保留 `SourceFile`、`LineNumberTable`。每次发布保存：

- APK/AAB 的版本名与 build number；
- 二进制 SHA-256；
- 完全匹配的 `mapping.txt`、其 SHA-256 与 `pg_map_id`；
- Git commit、工具链版本和发布环境。

Kotlin 2.4 metadata 需要兼容的 R8。当前 AGP 8.13.2 内置版本在实验中产生 metadata parse warnings，因此通过官方 R8 repository 固定 `9.1.29`，并由门禁检查 mapping header。R8 的 async parsing provider warning 只影响该组合的解析性能能力，不作为 metadata 正确性警告忽略或改写。

Apple Release App 配置为 `DWARF with dSYM`。归档必须同时保存 App/可执行文件、dSYM、版本/build、UUID 与 commit；符号化前先验证 binary 和 dSYM UUID 完全一致。静态 KMP framework 是最终 App 的链接输入，最终可执行文件及其 archive 的符号保留由 App 发布流水线负责。

当前门禁还用 Kotlin/Native `iosX64` test executable 与其 dSYM 证明：

1. 两者 UUID 相同；
2. `atos` 能恢复共同诊断测试的 Kotlin 函数名。

这证明符号链路可执行，不冒充本机已经生成了最终 iOS App Release archive。

### 日志、崩溃、trace 和 metrics 分层

四类信号解决不同问题：

- 结构化日志：某次操作发生了什么；
- crash/ANR/hang 诊断：进程为何终止或失去响应；
- trace/signpost：时间花在哪个线程与区间；
- metrics：一段时间内分布是否退化。

Android 的 Perfetto/系统 trace、Android vitals 与 ANR 分析，Apple 的 Instruments、OSLog signpost 与 MetricKit 都属于平台观测层。共同刷新事件可以提供相关 ID 和语义，但不替代这些工具。

### 门禁验证可定位性而非供应商接入

`scripts/verify-observability.sh` 必须验证：

- 领域与共同诊断测试通过；
- schema 使用 monotonic clock 且没有禁止字段；
- minified Android Release APK 和 mapping 均存在；
- R8 版本、应用类、domain/shared source-line metadata 可在 mapping 中找到；
- Android 版本为 `0.16.0 (16)`，并输出 APK/mapping SHA-256 与 `pg_map_id`；
- Native binary/dSYM UUID 匹配，`atos` 能恢复 Kotlin 函数名；
- Xcode Release 保留 dSYM 设置；
- 诊断实现没有泄露进 Apple header。

门禁不上传日志、不安装第三方 crash SDK，也不向外部服务发送用户数据。

## 当前证据

- domain 新增 5 个生命周期语义测试，shared 新增 6 个 schema/平台后端测试；
- domain 每个可运行目标 23 条，shared 为 JVM 27、Android Host 27、iOS x64 35；
- Android minified Release 在 Pixel 2 API 34 模拟器冷启动成功，并输出同一 correlation ID 的 started/finished 事件；
- 本次示例刷新为 628 ms、接收 3 条、变更 3 条；这是单次本机样本，不是性能 SLA；
- Android Release 使用 R8 9.1.29，最终未再出现 Kotlin 2.4 metadata parse warnings；
- Kotlin/Native binary 与 dSYM UUID 均为 `27989878-0ED1-33E9-99A6-73B55C1B20B5`，`atos` 恢复了诊断测试函数名；
- Xcode 26.5 未安装匹配的 iOS 26.5 runtime，因此本轮没有生成最终 iOS App Release archive，也没有声称验证其最终 dSYM。

完整产物身份、命令与限制见 `labs/16-observability/results/2026-11-20-diagnostic-chain.md`。

## 后果

### 正面

- Android 与 iOS 对同一次刷新使用相同事件语义；
- 业务失败、取消和缺陷保持各自行为，不再被一个大 `catch` 混合；
- 固定 allowlist 从类型结构上减少敏感信息进入日志的机会；
- monotonic duration 避免墙上时钟跳变造成负耗时或虚假峰值；
- minified Android 与 Native crash 都有可执行的符号恢复证据；
- 可观测后端可替换，而领域用例、Swift API 和产品行为不变。

### 成本

- 每个需要观察的用例都要定义稳定事件与终态契约；
- mapping、dSYM、二进制和版本元数据必须作为发布资产治理；
- `NSLog` 只是当前实验后端，生产 iOS 仍需接入 Logger/OSLog、隐私标记和采集策略；
- 没有远端聚合、采样、告警、保留期和用户同意策略，当前只能证明本地链路；
- schema 变更需要版本治理，不能随意改字段含义。

## 被拒绝的方案

### 在每个平台各写一套业务日志

会让 outcome、字段和隐私策略漂移，无法用同一语义比较双端，因此拒绝。

### 捕获所有 Throwable 并返回网络失败

会吞掉取消、程序缺陷甚至严重运行时错误，改变控制流并隐藏根因，因此拒绝。

### 直接记录异常 message 和完整对象

异常消息、DTO 与数据库对象可能包含 URL、token、用户输入或服务端正文；字段无界且难以审计，因此拒绝。

### 用墙上时间相减计算耗时

系统时钟可能跳变，duration 不可靠，因此拒绝。

### 只在 Debug 构建验证日志和堆栈

真实故障发生在经 R8/Release 链接后的产物；Debug 名称和行号不能证明 Release 可定位，因此拒绝。

### 提交一份 mapping 或 dSYM 长期复用

符号文件只对应特定 build。错配映射会产生看似合理但错误的堆栈，比没有符号更危险，因此拒绝。

### 立即绑定单一崩溃供应商

当前目标是先固定领域语义、隐私和符号资产契约。供应商 SDK、采样、上传和合规需要单独决策，因此延后。

## 重新评估条件

- 接入真实 API、认证、用户账户或远端日志平台；
- 出现需要跨进程/跨服务传播的 trace ID；
- 需要为刷新、启动、数据库或网络建立分位数性能预算；
- iOS 发布流水线能生成并上传最终 App archive/dSYM；
- Android Play 或 Apple Organizer 成为正式 crash/ANR 入口；
- schema 字段或隐私分类变化；
- Kotlin、AGP、R8、Xcode 升级改变符号格式或兼容矩阵；
- 迁移 Apple Silicon CI 并补齐 arm64 Simulator runtime 验证。

## 验证

- `scripts/verify-observability.sh` 通过；
- domain/shared JVM、Android Host 与 iOS x64 诊断测试通过；
- Android minified Release APK 在模拟器完成冷启动与刷新 smoke；
- R8 mapping 包含应用、domain 和 shared 的 source-line metadata；
- Native UUID/dSYM/`atos` 链路通过；
- Apple header 没有导出诊断实现；
- Xcode Release 配置为 `dwarf-with-dsym`；
- 最终 iOS App archive/dSYM 因本机 runtime 缺失仍未验证，并在实验记录中保留为明确缺口。
