# ADR-0018：用机器可检查的发布合同治理 KMP 生产发布

- 状态：已接受
- 日期：2026-11-20
- 决策范围：PocketLab release identity、团队 ownership、兼容窗口、放量、产物留存与回滚

## 背景

PocketLab 已具备 shared logic、Android/iOS hosts、Release artifacts、测试、API、诊断、增量迁移和 shared UI 决策证据。但这些能力分布在不同 Gradle tasks、shell gates、Xcode settings、ADR 和实验结果中。

此前 `0.17.0/build 17` 分别硬编码在 Android、KMP framework 与 Xcode generator。即使当前数值一致，下一次发布仍可能只更新其中两处。

更重要的是，“能构建 Release”没有回答：

- 谁能批准和停止 rollout；
- Android/iOS/KMP binary 版本如何绑定；
- 线上混合版本支持多久；
- database/log schema 如何演进；
- feature flag、binary forward fix 与数据恢复分别由谁执行；
- 哪些证据缺失时必须声明 not ready；
- 事故后怎样找到完全匹配的 binary、mapping 与 dSYM。

## 决策

PocketLab 使用版本控制中的 `release-contract.json` 作为发布治理合同，并由 Ruby 标准库 verifier 检查。合同不替代 CI、商店、监控或 on-call 系统；它定义这些系统必须提供的输入和停止条件。

### 单一 release identity 来源

新增 `labs/02-first-kmp-module/release.properties`：

```properties
versionName=0.17.0
buildNumber=17
databaseSchema=1
diagnosticSchema=1
```

Android App、KMP framework 与 Xcode generator 必须读取同一文件。已生成 `.pbxproj` 由 gate 检查 drift。

发布版本、数据库 schema 和诊断 schema 保持独立字段，因为它们有不同的演进原因。单一来源不等于把所有协议压成一个版本号。

### 明确 owner 与 backup

以下 area 必须各有不同的 primary/backup：

```text
release_command
shared_runtime
android_app
ios_app
data_migration
observability
```

required gate 必须绑定合法 owner area 和 evidence。`everyone`、空 owner 或同一个 primary/backup 被拒绝。

学习仓库暂用角色 handle。真实生产落地必须映射到人员、值班和 escalation system。

### 兼容窗口优先于同步上线假设

移动端默认存在多个 build：

- 示例 max build gap 为 2；
- server contract 在窗口内 additive；
- consumer 忽略未知字段；
- 字段删除等待 minimum supported version 前移；
- database 使用 expand/migrate/contract；
- 任一时刻只有一个 active data writer；
- destructive down migration 禁止作为常规 rollback；
- 默认通过 forward fix 处理数据兼容问题。

`max gap=2` 是当前策略输入，真实版本分布改变时必须重审。

### 分平台 staged rollout

Android release policy：

```text
internal → 1% → 10% → 50% → 100% → cleanup window
```

iOS automatic update policy遵循 App Store phased release：

```text
1% → 2% → 5% → 10% → 20% → 50% → 100%
```

每次 promotion 前读取按 platform/version/build/cohort 切片的 crash-free、refresh failure、p95 duration 与 migration errors。

halt/pause 只阻止继续扩散，不能让已经更新的用户强制降级。iOS 手动下载也可能绕过 automatic update phase。

### 三种 rollback 分开治理

配置 rollback：

- 只对真实实现并读取的 flag 生效；
- 在稳定 session/process scope 选择；
- 不能逆转已写数据。

Binary rollback：

- halt/pause rollout；
- 发布更高 build number 的 forward fix；
- 不假设商店强制 downgrade 已更新用户。

Data rollback：

- 默认 forward fix；
- restore 需要 incident approval、backup/restore drill 与受影响范围评估；
- 禁止把 destructive down migration 写成一键回滚。

### Immutable artifact manifest

每个 Release 必须保存：

- Android binary、mapping、SHA-256、`pg_map_id`、版本和工具链；
- Apple xcarchive、dSYM、UUID、SHA-256、版本和工具链；
- 远程 shared binary 的 XCFramework ZIP、SwiftPM checksum、header hash 与版本；
- 与 commit、release decision 和 rollout history 的关联。

同一版本坐标不得覆盖不同 bytes。当前 retention contract 为至少 400 天。

### Feature flag 是有期限的迁移资产

每个 flag 必须声明：

- default；
- stable selection scope；
- owner；
- expiry；
- removal condition；
- single-writer constraint。

planned JSON entry 不等于已实现的远端 kill switch。100% rollout 后仍需观察窗口和 cleanup change。

### Readiness 由 required gates 推导

`release_ready` 不能手写成愿望。所有 required gates 为 passed 时才为 true；blocked/not_implemented gate 列表必须与 expected blockers 完全相同。

当前结果：

```text
required gates passed: 5/8
release_ready: false
blockers:
- ios_app_runtime
- artifact_manifest
- production_rollout_backend
```

合同验证通过只表示 `false` 是诚实、一致且可审查的结论。

## 机器可验证契约

`labs/20-production-governance/scripts/verify_release_contract.rb` 检查：

- release identity 与 repo source；
- Android/KMP/Xcode/generated project drift；
- database/diagnostic schema；
- owners、gates 和 evidence；
- rollout percentage/order/observation window；
- promotion metric slicing；
- flag expiry/removal/single writer；
- config/binary/data rollback boundary；
- artifact manifest required fields；
- blockers 与 readiness derivation。

门禁还必须拒绝 6 个 mutation fixtures，证明错误输入确实失败，而不只是 happy path 通过。

## 当前证据

- 0.17.0/build 17 已提升为三端共同 properties source；
- Android/shared Gradle scripts 编译并读取该 source；
- Xcode generator 语法和生成设置读取该 source；
- generated project drift 为 0；
- 6 个 ownership areas 各有 primary/backup role；
- Android/iOS rollout contract 分开表达；
- 三种 rollback capability 分开表达；
- 6/6 negative mutations 被拒绝；
- readiness 正确保持 false。

## 被拒绝的方案

### 只维护一份发布 checklist 文档

文档适合解释原因，但无法自动发现版本漂移、owner 缺失、rollout 倒序或虚假 ready 声明。保留文档，同时增加 executable contract。

### 所有模块各自管理版本

适合真正独立发布和兼容承诺的 products/libraries；当前 Android、iOS 与 shared framework 属于同一 PocketLab release identity，各自硬编码只会增加漂移。

### 用一个版本号表达 App、database 和 diagnostics

这些协议演进原因不同。把它们绑成一个数字会制造无意义升级，也不能清楚表达 compatibility。

### 有 feature flag 就算可回滚

flag 不能降级 binary、逆转数据或覆盖启动前 crash；未实现的 flag 更没有运行时能力。

### halt rollout 等于版本撤回

已经拿到版本的用户仍然运行该 build。正确动作是限制继续扩散并发布 forward fix。

### 默认提供 down migration

真实数据可能被新版本转换、合并或上传。破坏性降级很容易制造二次事故，默认采用 expand/migrate/contract 与 forward fix。

### 所有 gate 都写成 passed 才好看

这会把 dashboard 变成汇报装饰。缺最终 iOS runtime/archive 和真实 rollout 时，not ready 才是正确输出。

## 后果

### 正面

- 三端 release identity 不再靠人工同步；
- 发布责任和停止权限可审查；
- 多版本兼容、rollout 与 rollback 不再混为一句“可灰度”；
- 产物、symbol 与 binary identity 有统一要求；
- 机器会拒绝错误 readiness；
- 未完成生产能力保持显式 blocker；
- 版本治理与业务功能 PR 可以分开评审。

### 成本

- contract 与 repo 配置都需要维护；
- owner/backup、SLO 和 artifact retention 需要组织承诺；
- Xcode generated project 仍需再生和 drift 检查；
- 真实 flag/cohort/store API 尚未接入；
- 真实 backup/restore drill 尚未执行；
- 发布 promotion 增加等待时间，但减少爆炸半径；
- shared library 若跨仓发布，还需新增 Kotlin binary API validation。

## 重新评估条件

- Android、iOS 与 shared binary 改为独立 release trains；
- shared Kotlin API 发布给跨仓消费者；
- 最低支持版本或强制升级策略改变；
- 数据库出现第二 writer、outbox 或云端同步；
- 新法规改变 artifact/log retention；
- 使用新的 feature flag/cohort provider；
- App Store/Play rollout semantics 改变；
- KMP/Compose/AGP/Xcode 版本升级改变 compatibility matrix；
- 真实 SLO 和样本量支持更具体 promotion thresholds。

## 验证

```shell
cd labs/20-production-governance
./scripts/verify_release_contract.rb
```

完整结果见 `labs/20-production-governance/results/2026-11-20-release-contract.md`。
