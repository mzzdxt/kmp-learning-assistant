# ADR-0013：用分层基线和可验证能力治理构建性能

- 状态：Accepted
- 日期：2026-11-20
- 决策范围：PocketLab 本地与 CI 构建性能、缓存、编译报告和任务工作流

## 背景

PocketLab 现在包含 `domain`、`shared`、Android App、三个 Apple targets、SQLDelight、Ktor、Swift type-check 与 XCFramework 分发。上一里程碑的 Release XCFramework gate 需要约 4 分 37 秒，而日常 JVM no-op 只需约 1 秒。

只问“整个 build 多久”会把多种完全不同的成本揉在一起：

- Gradle 初始化与配置；
- task graph 和 up-to-date 检查；
- Kotlin/JVM、Android 与 Kotlin/Native 编译；
- SQLDelight 等代码生成；
- Native framework 链接；
- test process、Simulator 与 Xcode；
- Release 多架构组装和分发验证。

Gradle configuration cache、build cache、up-to-date checks、Kotlin incremental compilation 与 Kotlin/Native caches 也解决不同问题。未经测量就同时打开所有 flags，无法知道哪项有效，也可能引入兼容性、正确性或 Release 性能风险。

## 决策

### 建立分层场景，不发布一个“总构建时间”

基线至少分为：

1. `config-only`：`help`，观察配置固定成本；
2. `dev-noop`：日常 JVM tests，观察重复工作流；
3. `dev-clean-cache`：区分 clean compilation、build-cache restore 与 up-to-date no-op；
4. `incremental-change`：分别观察私有实现和公共 ABI 改动；
5. `native-noop`：观察 Native task graph 的重复调用；
6. `native-forced-debug`：观察真实单架构 Native 编译/链接阶段；
7. `release-distribution`：只在发布/合并门禁观察多架构 Release XCFramework。

`scripts/benchmark-build.sh` 保存 monotonic wall time、configuration-cache state 和 task summary。正式结论必须同时引用 Gradle task outcomes；不得只凭 `BUILD SUCCESSFUL in Ns` 推断 cache 命中。

### 启用三项已验证能力

在 `gradle.properties` 保持：

```properties
org.gradle.caching=true
org.gradle.configuration-cache=true
kotlin.build.report.output=file,json
```

- build cache 复用相同 task inputs 的 outputs；
- configuration cache 复用配置阶段得到的 task graph/state；
- Kotlin file report 服务人工诊断，JSON 为后续趋势分析提供机器输入。

configuration cache 以 problems=`fail` 验证，不把 `warn` 当长期逃生舱。`scripts/verify-build-performance.sh` 必须证明：配置开启、benchmark shell 合法、Kotlin text/JSON reports 能生成、重复开发任务实际复用 configuration cache。

门禁验证“能力可用”，不设置本机毫秒级硬阈值。耗时门禁容易被 runner 噪声误触发；在获得稳定 CI 历史分布后，再用百分位/相对退化预算治理趋势。

### 按开发意图选择最小 task lane

- fast domain/data loop：`:domain:jvmTest :shared:jvmTest`；
- Android risk：Android host tests、App unit/Compose UI；
- Apple development：当前 host 的一个 Debug framework 或可运行 Simulator test；
- merge matrix：所有受支持且当前 host 可运行的 target tests；
- release lane：Release XCFramework、header/Swift consumer 和分发验证。

日常开发不运行完整 Release XCFramework，也不默认执行 `clean`。Release lane 慢并不自动证明需要低层 compiler flag；先确认是不是选择了比当前反馈目标更大的任务。

### CI 缓存有明确边界

CI 应在 runner/安全策略允许时保留：

- Gradle dependency 与 wrapper cache；
- Gradle build cache；
- Kotlin/Native `~/.konan` toolchain/dependency cache；
- configuration cache 仅在其 inputs、工作区路径和 CI 模式稳定时复用。

cache key 必须包含 Gradle/Kotlin/AGP、lockfiles/构建脚本和 runner architecture 等影响 inputs 的维度。cache 不是测试替代品，恢复后仍执行相应验证任务。

当前不引入 remote build cache。它需要先设计信任边界、凭据、只读/写入角色、污染恢复、淘汰与成本。公开 PR 不应默认拥有写权限。

### 优化必须有触发证据

暂不启用以下设置：

- 增大 heap：只有在 build scan、GC、OOM 或内存压力显示 JVM memory 是瓶颈时调整；
- `org.gradle.parallel=true`：configuration cache 已允许项目内 task 并行，本项目没有独立 A/B 证据证明额外 flag 有净收益；
- `kotlin.incremental.native=true`：Beta 能力，需用目标改动集单独验证正确性和收益；
- Native Release binary cache：可能以编译时间换优化程度，必须同时测 build time、App startup/runtime 与 binary size；
- 关闭优化/减少 targets：只可在明确的 Debug lane 使用，不能污染 Release 交付承诺。

### 公开资料不提交原始报告

Kotlin/Gradle reports 可能包含绝对路径、编译参数、环境和依赖信息。仓库提交人工整理后的、去标识化的实验记录；原始 logs/reports 位于 ignored `build/`。在 CI 发布 artifacts 前必须审查可见范围和保留期。

## 当前证据

- `help` 的 configuration-cache reuse 相比 disabled 平均低约 26.4%；
- 日常 JVM no-op reuse 相比 disabled 平均低约 38.4%，但首次 store 更慢；
- Native Debug link no-op reuse 相比 disabled 平均低约 30.9%；
- clean 无 build cache 为 11.655 s；受控 warm restore 有 28 个 `FROM-CACHE` tasks，为 1.510 s；本地 outputs 存在时 no-op 为 1.039 s；
- 单一 `iosX64` Debug forced rerun 为 19.80 s，Kotlin tasks 占全部 task time 的 91.5%，link task 约 9.955 s；
- 私有实现改动没有触发 `shared`/App 重新编译，但 common source 因 KT-62686 fallback 进行了 domain 非增量 compilation；
- 公共 API 改动触发 domain、shared 和 Android App 相关 compilation，49 tasks 中 17 executed。

完整环境、协议和限制见 `labs/15-build-performance/results/2026-11-20-intel-mac.md`。

## 后果

### 正面

- “慢”可以定位到配置、缓存、编译、链接或任务选择，不再靠感觉调 flags；
- 开发者获得约一秒级的 warm JVM feedback，同时保留完整多目标验证；
- 机器可验证 configuration cache 与 compiler reports 没有在升级中静默失效；
- 私有实现/公共 ABI 的传播差异可以指导模块边界和 API 设计；
- Native Release 成本被放进正确 lane，而不是压在每次 Kotlin 编辑之后。

### 成本

- benchmark 需要控制 daemon/cache/task 状态，记录和解释成本上升；
- configuration cache key 对 tasks、参数和配置 inputs 敏感，频繁改变 report label 等参数会导致重新 store；
- file/JSON reports 增加本地磁盘输出，需要清理和隐私审查；
- 当前只有单机短样本，尚不能给 CI 制定可靠的绝对 SLA；
- cache 会让错误基线“看起来很快”，实验必须显式区分 clean、restore 和 no-op。

## 被拒绝的方案

### 每次先 `clean`，保证结果可靠

Gradle inputs/outputs、up-to-date 与 build cache 本来就是正确性模型。无差别 clean 主动删除最快的本地反馈，只在验证 clean build、诊断陈旧输出或 release reproducibility 时使用，因此拒绝作为日常流程。

### 直接给 Gradle 更多内存

当前没有 OOM、频繁 full GC 或 heap 压力证据。内存不是万能速度旋钮，过大 heap 还会挤压系统/并行进程，因此拒绝。

### 一次打开所有实验性 Native flags

无法分辨收益来源，也无法同时验证 correctness、Release runtime 和 binary size，因此拒绝。

### 用 Release XCFramework 时间代表 KMP 开发体验

它包含多个 architecture、Release optimizations、framework/link/assembly 和 consumer checks，与编辑领域规则后的反馈不是同一工作流，因此拒绝。

### 立即部署 remote cache

当前单人学习仓库没有跨 runner 数据证明收益超过运维与信任成本，因此延后。

## 重新评估条件

- 升级 Gradle、Kotlin、AGP、Xcode 或新增重型 plugin；
- 迁移 Apple Silicon，增加/移除 targets 或改变 CI runner；
- module graph、KSP/代码生成或 Swift Export 发生变化；
- CI 连续样本显示 p50/p95 明显退化；
- Gradle/Kotlin 报告显示配置、GC、classpath snapshot 或某个 compiler phase 成为新瓶颈；
- remote cache 的跨开发者/CI 重复计算达到可量化规模；
- Native incremental/binary cache 能力稳定，并能通过本项目 correctness 与 Release 性能验证。

## 验证

- `scripts/verify-build-performance.sh` 通过；
- `scripts/benchmark-build.sh config-only 5`、`dev-noop 5`、`native-noop 3`、`dev-clean-cache 1` 已执行；
- Kotlin file/JSON build reports 已生成并用于 Native phase 与 incremental reason 分析；
- 所有临时源代码改动均已还原；
- JVM/Android Host/iOS x64 为 39/39/47，Android unit/Compose UI 为 2/2、2/2；
- module boundary、Apple API/Swift 6 与 distribution gates 均通过；warm distribution gate 为 2 min 26 s（8 executed、2 from cache、9 up-to-date），未冒充 cold 基线；
- iOS UI 上一完整基线为 1/1，本轮仍受 Xcode 26.5 缺少匹配 runtime 阻断。
