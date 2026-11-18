# ADR-0005：以本地数据库为事实来源，并用 SQLDelight 保持三端可验证

- 状态：已接受
- 日期：2026-11-19
- 决策范围：PocketLab 本地持久化、远端刷新合并、同步状态与数据库选型

## 背景

PocketLab 已能保存草稿，也能通过 Ktor 获取远端实验，但两条路径尚未汇合。如果 UI 直接观察网络响应，断网时列表会消失；如果远端刷新直接覆盖本地数据，尚未上传的编辑可能丢失；如果 Android 与 iOS 各有数据库模型，排序、同步状态和冲突规则会再次分叉。

当前构建主机是 Intel macOS，iOS 测试 target 为 `iosX64`。数据库库的“支持 iOS”声明必须落实到这个具体 target，而不能只验证 Android 或 JVM。

## 决策

- 本地 SQLite 数据库是实验列表的唯一事实来源；UI 只观察本地 `Flow`。
- 本地保存先提交数据库并标记 `PENDING_UPLOAD`，不等待网络。
- 远端刷新只负责把快照事务性合并进本地数据库，不直接向 UI 返回列表。
- 合并规则为：
  - 本地不存在时插入远端记录并标记 `SYNCED`；
  - 本地为 `PENDING_UPLOAD` 时绝不由刷新覆盖；
  - 本地已同步时，仅接受时间戳更大的远端版本；
  - 相同或更旧的远端版本不改变本地数据。
- 一批远端记录在同一个数据库事务中合并，观察者不会看到半批状态。
- 使用 SQLDelight 2.4.1：schema 和查询写在共同 `.sq` 文件中，由插件生成类型安全 Kotlin API。
- JVM 使用 `sqlite-driver`，Android 使用 `android-driver`，iOS x64 使用 `native-driver`。
- 数据库 handle 显式拥有并关闭 `SqlDriver`。
- 保存的同步状态使用稳定字符串，并由 SQLite `CHECK` 约束限制为 `SYNCED` 或 `PENDING_UPLOAD`。
- 建立 `1.db` schema 基线并启用 `verifyMigrations`；未来升级使用 `<旧版本>.sqm`。

## 数据库选型理由

评估了 Room 3.0.3 与 SQLDelight 2.4.1。

Room 3 已面向 KMP 重构，采用 SQLiteDriver、KSP 与协程 API，对 Android 团队很有吸引力；在 Apple Silicon 的新项目中应继续认真评估它。但 Room 3.0.3 与其 Bundled SQLite 依赖没有发布 `iosX64` 变体，当前工程无法为 Intel iOS Simulator 解析依赖。

SQLDelight 2.4.1 仍发布 `native-driver-iosx64`，并让同一套 SQL、事务和查询监听在现有 JVM、Android Host、iOS x64 测试矩阵中真实执行。因此本次选择 SQLDelight。

这是一项受 target 矩阵约束的工程决策，不是“SQLDelight 永远优于 Room”。迁移到 Apple Silicon 后，应结合团队经验、生成方式、迁移工具、二进制体积与性能数据重新评估。

## 理由

- 本地事实来源让首屏、断网和刷新失败共享同一条读取路径。
- 本地先写把用户操作的可用性与网络状态解耦。
- `PENDING_UPLOAD` 是可持久化的同步意图，应用重启后仍可恢复。
- 事务保证一批服务器快照的原子可见性。
- SQL 位于共同源码，可让 schema、排序和过滤规则只实现一次，并在编译期检查。
- 平台 driver 保留 Android Context、Native SQLite 与 JDBC 的真实平台差异。
- 迁移基线使 schema 演进成为可验证构建资产，而不是上线时的临时脚本。

## 后果

正面：

- 保存后无需网络即可从共同 Flow 观察记录。
- 刷新失败不会清空缓存，待上传本地记录不会被远端覆盖。
- 三端对排序、同步状态和合并规则执行同一组数据库测试。
- SQL 修改会触发代码生成与编译检查；迁移可进入 CI。

代价与限制：

- SQLDelight 引入 Gradle 代码生成和三套 driver 依赖。
- Android Host 测试使用 JVM SQLite driver，不等同于 Android 设备上的 framework driver 集成测试。
- 当前 `PENDING_UPLOAD` 只表达待上传，没有重试次数、上次错误、退避时间或幂等 key。
- 当前“时间戳较新者胜”依赖服务器时间语义，不能解决真正的并发多设备编辑。
- 删除尚未建模；离线删除需要 tombstone 或独立 outbox，不能直接删行。
- 本轮只有远端读取与本地合并，没有上传 API，因此是离线优先骨架，不是完整双向同步协议。

## 拒绝的方案

### UI 同时观察数据库和网络响应

会产生两套事实来源，加载、错误、排序和竞态处理扩散到状态层。网络应该更新数据库，UI 继续只观察数据库。

### 刷新时清空表再写入服务器列表

会删除尚未上传的本地记录，也会在非事务实现中暴露空列表或半批状态。

### 为了使用 Room 而删除 iOS x64 测试

这会把库选型优先级放在目标真实性之上，并让“支持 iOS”只剩推断。迁移主机后可以重评 Room，但当前不能伪造已验证范围。

### 降级到仍发布 iosX64 的旧 Room 组合

会为了旧 host 引入已经落后的依赖与迁移路径，且掩盖生态正在收缩 x64 Apple target 的事实。

### 只用内存 fake 验证 Repository

fake 适合测试用例状态机，但无法证明 SQL schema、查询排序、driver、事务通知与生成代码在 Native 上工作。

### 仅用客户端时间戳做最终冲突解决

客户端时钟可漂移，也不能表达并发因果。当前时间戳规则只用于远端刷新已同步数据；完整上传协议应引入服务器 revision、ETag 或明确冲突版本。
