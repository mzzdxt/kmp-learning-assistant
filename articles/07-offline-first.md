# 离线优先：不是“加一层缓存”，而是重新决定谁说了算

> 面向读者：熟悉 Room、Repository 与 Flow，第一次设计 KMP 离线数据层的 Android 开发者  
> 技术核对与三端验证日期：2026-11-19  
> 实验版本：Kotlin 2.4.21、SQLDelight 2.4.1、Ktor 3.6.1

地铁刚钻进隧道，你在 PocketLab 里写下一条实验：

> 每天睡前把手机放到客厅，会不会更容易早睡？

点击保存，App 转了两秒，然后告诉你：“网络连接失败，请稍后重试。”

技术上，这个提示没有说谎。产品上，它荒谬得很诚实：一条只属于你自己的文字，明明已经在手机里，却因为一台遥远服务器暂时不可达而拒绝存在。

“给网络层加个缓存”通常是我们的第一反应。但缓存只回答“能不能暂时读旧数据”，没有回答更重要的问题：

- 用户刚保存的内容算不算已经成功？
- 本地和服务器不一致时，谁说了算？
- App 重启后，谁还记得哪些操作没上传？
- 刷新到一半失败，UI 应该看到半批数据吗？
- 本地编辑与远端更新相遇，谁可以覆盖谁？

离线优先不是一个缓存开关，而是一组架构承诺。

这一篇，我们让 PocketLab 的 SQLite 数据库成为唯一事实来源，用 SQLDelight 在 JVM、Android 与 iOS 上执行同一份 schema、查询和事务，并建立第一版同步规则。

过程中还发生了一次比“成功接入数据库”更有价值的失败：我们先尝试 Room 3，但它的稳定发布物无法解析当前 `iosX64` target。这个失败会告诉我们，为什么“支持 iOS”远远不够精确。

## 先定义离线优先，而不是先选数据库

本实验接受四条不变量。

### 不变量一：读取只有一个事实来源

UI 永远观察本地数据库：

```text
SQLite Flow → Store / UI
```

网络响应不能绕过数据库直接成为第二条 UI 数据流。

### 不变量二：本地操作先完成

用户保存实验时：

```text
写入本地 → UI 立即可见 → 稍后上传
```

“保存成功”表示本地意图已经可靠持久化，不表示服务器已经确认。

### 不变量三：同步意图必须活过进程重启

待上传状态不能只存在于内存 Boolean、Coroutine 或 WorkManager 参数里。记录本身需要携带可恢复的 `PENDING_UPLOAD` 状态。

### 不变量四：同步合并必须原子可见

一批远端记录要么完整进入数据库，要么都不进入。观察者不能先看到第一条、再看到第五条，最后才得到完整列表。

先有这四条，我们才有资格讨论 Room、SQLDelight、SQLite driver 和 Flow。

## 最容易写出的错误架构：两条都像真相的数据流

假设 Repository 同时暴露：

```kotlin
val cachedExperiments: Flow<List<Experiment>>
suspend fun refresh(): List<Experiment>
```

平台 UI 很容易写成“先显示 cache，网络成功后显示 response，网络失败后继续显示 cache”。看似合理，实际上事实来源已经分裂：

- cache 与 response 排序不同时听谁的？
- 用户刷新过程中本地新增一条，response 没有它，显示哪个列表？
- 页面重建时 response 已丢失，状态怎么恢复？
- Android 与 iOS 是否会复制同一套竞态处理？

真正的 offline-first 路径应该是：

```text
                 ┌──────────────┐
网络 refresh ──> │              │
                 │  本地 SQLite  │ ── Flow ──> UI
本地 save ─────> │              │
                 └──────────────┘
```

网络成功只改变数据库；网络失败则不改变数据库。UI 从始至终只认识一条 Flow。错误可以作为“上一次刷新失败”的元数据展示，但列表内容不切换来源。

## Room 还是 SQLDelight？先让 target 矩阵发言

对于 Android 开发者，Room 是最自然的候选。

截至本文验证日期，稳定版 Room 3.0.3 已经是明确面向 Kotlin Multiplatform 的大版本：使用 `androidx.room3` 新包，完全建立在 KMP SQLiteDriver API 上，只生成 Kotlin、只使用 KSP，并采用协程或 Flow 数据访问。

于是我们真的把 Room 3.0.3、SQLite 2.7.1、KSP 2.3.12 接进项目，然后执行：

```shell
./gradlew \
  :shared:compileKotlinJvm \
  :shared:compileAndroidMain \
  :shared:compileKotlinIosX64
```

JVM 与 Android 能解析，iOS x64 失败。Gradle 给出的证据非常具体：Room runtime 提供 `iosArm64` 与 `iosSimulatorArm64` variant，却没有消费者需要的 `ios_x64` variant；Bundled SQLite 也有同样问题。

这不是“Room 不支持 iOS”，而是：

> Room 3.0.3 支持现代 ARM Apple targets，但没有发布当前工程需要的 `iosX64` 架构变体。

当前机器是 Intel Mac，能运行的 iOS Simulator target 正是 `iosX64`。如果为了保留 Room 而删除 iOS 测试，我们就会得到一句没有证据的“理论上支持 iOS”。

接着验证 SQLDelight 2.4.1。它仍发布 `native-driver-iosx64`，三端编译全部通过，所以本轮选择 SQLDelight。

| 方案 | JVM | Android | ARM iOS | iosX64 | 当前三端实测 |
|---|---:|---:|---:|---:|---:|
| Room 3.0.3 | 是 | 是 | 是 | 否 | 不满足 |
| SQLDelight 2.4.1 | 是 | 是 | 是 | 是 | 满足 |

这张表有两个重要限定：它描述的是本文日期与版本，不是永恒事实；它描述的是当前工程 target，不是库的通用优劣排名。

迁移到 Apple Silicon 后，`iosSimulatorArm64` 会成为主目标。那时 Room 3 值得重新评估，尤其是已有大量 Room 经验和实体模型的 Android 团队。

专家决策不是为喜欢的库找理由，而是让目标、版本、团队和验证能力共同裁决。

## “支持 iOS”为什么是一个不完整句子？

KMP 依赖解析面对的不是抽象平台标签，而是具体 variant：

```text
iosArm64             真机 ARM64
iosSimulatorArm64    Apple Silicon Simulator
iosX64               Intel Simulator
```

库只发布前两个，不能满足第三个消费者。这与第二篇里学到的 target 心智模型完全呼应：target 包含平台和体系结构。到了真实生态依赖面前，这不再是术语题，而是构建能否成立。

当前 Kotlin 编译器已提示 macOS x64 Native host 将在未来移除，越来越多库也不再发布 `iosX64` 变体。当前实验保留 `iosX64`，是因为它仍能在这台 Intel Mac 上提供真实 iOS 执行证据；长期解决方案是迁移构建主机，不是永远锁住旧依赖。

## SQLDelight 共享了什么？

SQLDelight 的核心思路不是“把 Kotlin data class 变成表”，而是：

> SQL 是源码，Kotlin API 是编译产物。

Gradle 配置数据库：

```kotlin
plugins {
    id("app.cash.sqldelight")
}

sqldelight {
    databases {
        create("PocketLabSqlDatabase") {
            packageName.set("dev.kmpjourney.pocketlab.data")
        }
    }
}
```

共同 source set 中的 `.sq` 文件同时定义 schema 和查询。插件在构建时生成 `PocketLabSqlDatabase`、`ExperimentQueries`、Kotlin 参数与结果类型、schema create/migrate 实现，以及查询变更通知代码。

因此：

```sql
selectById:
SELECT id, title, hypothesis, notes, updated_at_ms, sync_state
FROM experiments
WHERE id = ?;
```

会生成接受非空 `String id` 的 Kotlin 查询函数。列类型、参数数量或 mapper 对不上，问题发生在编译期，而不是用户运行到某个页面时。

SQLDelight 不是“不写 SQL”；它是“让 SQL 成为可编译接口”。

## 依赖仍然遵循共同 runtime、平台 driver

共同代码依赖 runtime 与协程扩展：

```kotlin
commonMain.dependencies {
    implementation("app.cash.sqldelight:runtime:2.4.1")
    implementation(
        "app.cash.sqldelight:coroutines-extensions:2.4.1"
    )
}
```

各平台装配 driver：

```kotlin
jvmMain.dependencies {
    implementation("app.cash.sqldelight:sqlite-driver:2.4.1")
}

androidMain.dependencies {
    implementation("app.cash.sqldelight:android-driver:2.4.1")
}

iosX64Main.dependencies {
    implementation("app.cash.sqldelight:native-driver:2.4.1")
}
```

这与 Ktor 的 engine 结构很像：

```text
共同查询 / schema
        |
        +── JVM: JDBC SQLite
        +── Android: Android SQLite
        `── iOS: Native SQLite
```

共享的是 schema、SQL 和 Repository 语义；打开数据库的机制仍然不同。

## 第一版 schema：同步状态也是数据

表结构是：

```sql
CREATE TABLE experiments (
  id TEXT NOT NULL PRIMARY KEY,
  title TEXT NOT NULL,
  hypothesis TEXT NOT NULL,
  notes TEXT NOT NULL,
  updated_at_ms INTEGER NOT NULL,
  sync_state TEXT NOT NULL
    CHECK (sync_state IN ('SYNCED', 'PENDING_UPLOAD'))
);
```

`sync_state` 没有留成随便写的 String。SQLite `CHECK` 约束保证数据库只能保存当前模型认识的状态。

共同领域层使用 enum：

```kotlin
enum class ExperimentSyncState {
    SYNCED,
    PENDING_UPLOAD,
}
```

Repository 在边界完成 String 与 enum 的映射。如果数据库里出现未知值，我们选择失败并暴露问题，而不是悄悄当作已同步。

同步状态不是 UI 装饰，它是恢复同步意图所需的持久业务数据。如果它只存在于内存，App 一重启，“稍后上传”就会变成“永远忘记上传”。

## 为什么领域模型不直接使用生成的数据库行？

共同 API 暴露：

```kotlin
data class ExperimentRecord(
    val id: String,
    val title: String,
    val hypothesis: String,
    val notes: String,
    val updatedAtEpochMilliseconds: Long,
    val syncState: ExperimentSyncState,
)
```

而不是 SQLDelight 自动生成的表类型。

理由与网络 DTO 分层相同：表字段会因索引、迁移和存储优化变化；领域模型可能合并多个表；数据库用 String 保存的状态，领域更适合 enum；将来 SQLDelight 换成 Room，也不应该破坏全部调用方。

共享数据库实现并不意味着让数据库库成为公共领域协议。

## 本地保存：先持久化意图，再考虑网络

原来的 `SaveDraftUseCase` 只依赖：

```kotlin
interface ExperimentRepository {
    suspend fun save(experiment: SavedExperiment)
}
```

现在由 SQLDelight 实现：

```kotlin
override suspend fun save(experiment: SavedExperiment) {
    database.transaction {
        put(
            id = experiment.id,
            title = experiment.title,
            hypothesis = experiment.hypothesis,
            notes = experiment.notes,
            updatedAtEpochMilliseconds =
                experiment.savedAtEpochMilliseconds,
            syncState = PENDING_UPLOAD,
        )
    }
}
```

保存没有调用 Ktor。成功返回只代表校验通过、本地事务已提交、上传意图已持久化。UI 可以立即显示记录，并标注“待同步”。

网络稍后成功，才把状态转成 `SYNCED`。本轮还没有上传 endpoint，所以我们刻意不伪造这一半流程。

## 为什么不用 `INSERT OR REPLACE`？

SQLite 的 `REPLACE` 不是普通 UPDATE。发生唯一键冲突时，它可能删除旧行再插入新行。

今天只有一张表，似乎没问题；明天加入附件外键、触发器或审计字段，delete + insert 语义可能带来级联删除、rowid 变化和意外回调。

本实现使用兼容旧 SQLite 的两步策略：

```text
事务开始
  UPDATE ... WHERE id = ?
  如果 updatedRows == 0：INSERT ...
事务提交
```

SQLDelight 2.4 生成的 mutation API 会返回更新行数，因此不需要额外 SELECT。把两步放进事务，既避免竞争窗口，也让观察者只收到最终状态。

## 列表 Flow 来自查询失效，而不是手工 emit

查询定义排序：

```sql
selectAll:
SELECT id, title, hypothesis, notes, updated_at_ms, sync_state
FROM experiments
ORDER BY updated_at_ms DESC, id ASC;
```

Repository 把它转换为 Flow：

```kotlin
override fun observeAll(): Flow<List<ExperimentRecord>> =
    queries.selectAll(::mapExperiment)
        .asFlow()
        .mapToList(queryDispatcher)
```

SQLDelight 知道 Query 依赖 `experiments` 表。写入后，它通知 listener，Flow 重新执行查询并发射最新列表。

Store 不需要同时维护数据库内容、MutableStateFlow 列表副本和网络 response 列表副本。数据库就是列表状态的持久来源。

`queryDispatcher` 通过构造参数注入，默认使用 `Dispatchers.Default`；测试可以注入 TestDispatcher，避免把真实线程策略焊死在 Repository 中。

## 远端刷新不是“把 response return 给 UI”

Ktor 数据源返回远端模型后，刷新用例先映射成平台无关服务器快照：

```kotlin
data class ServerExperimentSnapshot(
    val id: String,
    val title: String,
    val hypothesis: String,
    val notes: String,
    val updatedAtEpochMilliseconds: Long,
)
```

然后合并数据库：

```kotlin
class RefreshExperimentsUseCase(
    private val remote: ExperimentRemoteDataSource,
    private val local: ExperimentLocalStore,
) {
    suspend fun execute(): RefreshExperimentsResult =
        when (val result = remote.fetchExperiments()) {
            is Success -> {
                val changed = local.mergeServerSnapshot(
                    result.experiments.map { it.toSnapshot() }
                )
                Applied(result.experiments.size, changed)
            }
            is Failure -> result.toRefreshFailure()
        }
}
```

`Applied` 只返回计数，不返回列表。列表仍由数据库 Flow 发射，这样 Android 与 iOS 不会各自决定怎样把 response 塞进当前页面状态。

## 合并规则：保护尚未离开设备的数据

每条服务器记录进入事务后，先读取同 ID 本地元数据：

```kotlin
val shouldApply = when {
    local == null -> true
    local.syncState == PENDING_UPLOAD -> false
    server.updatedAt > local.updatedAt -> true
    else -> false
}
```

| 本地状态 | 服务器版本 | 结果 |
|---|---|---|
| 不存在 | 任意 | 插入 `SYNCED` |
| `PENDING_UPLOAD` | 任意 | 保留本地 |
| `SYNCED` | 更新 | 替换成本次服务器版本 |
| `SYNCED` | 相同或更旧 | 忽略 |

为什么 `PENDING_UPLOAD` 优先级最高？因为服务器还没见过本地修改。一次普通 refresh 不应删除用户尚未上传的意图。

如果直接采用“时间戳更大者胜”，客户端时钟慢五分钟就可能让刚编辑的内容被旧服务器数据覆盖。

保护 pending 不是完整冲突解决，但它守住最低安全线：未确认上传的本地写入，不能被只读刷新静默覆盖。

## 批量合并为什么必须是一个 transaction？

假设服务器返回 100 条记录，每次 upsert 都立即通知 Flow：

```text
0 条 → 1 条 → 2 条 → ... → 100 条
```

UI 可能重组 100 次；第 47 条写入失败时，观察者还可能已经见到半批快照。

本实现使用：

```kotlin
database.transactionWithResult {
    serverRecords.forEach { mergeOne(it) }
    changedRows
}
```

事务同时保证数据原子性与观察原子性。测试不只断言数据库最后有两条记录，而是收集 Flow：

```kotlin
assertEquals(listOf(0, 2), emissions.map { it.size })
```

如果出现 `[0, 1, 2]`，说明 UI 看到了中间状态，测试会失败。

## 网络失败为什么不清空缓存？

刷新失败不执行本地 mutation：

```text
刷新前数据库 = A
远端 503
刷新后数据库 = A
```

UI 可以同时展示上一次成功同步的数据、“刷新失败”提示和待上传标记。错误状态与内容状态不必互相摧毁。

这比 catch 后返回 `emptyList()` 重要得多。空列表可能被解释成“用户没有实验”，真实含义却是“这次没能联系服务器”。

## 当前时间戳规则为什么还不够？

对已经 `SYNCED` 的记录，本实验用 `updated_at_ms` 判断远端是否更新。它能完成最小刷新实验，却不是生产级冲突协议。

时间戳有三个问题：客户端时钟会漂移；相同毫秒内可能多次写入；时间先后不能表达两个设备是否基于同一版本编辑。

更可靠的上传协议通常需要服务器权威版本，例如单调 revision、ETag / If-Match、每条记录的 server version，或冲突时同时提供 local、remote、base 三个版本。

本实验只保证 refresh 不回滚已同步新版本，也不覆盖 pending 本地版本。不要把它包装成“冲突问题已经解决”。

## 待上传状态与 outbox 是什么关系？

当前只有一种本地 mutation：保存实验。因此把 `PENDING_UPLOAD` 放在业务行里，足以恢复“这条记录需要上传”。

当功能增长到创建、编辑、删除、附件、失败重试和多步事务时，独立 outbox 更合适：

```text
outbox_operation
├── operation_id
├── entity_id
├── kind
├── payload / revision
├── attempt_count
├── next_attempt_at
└── last_error
```

业务表与 outbox 必须在同一个本地 transaction 写入，避免“数据改了但同步意图没记下”或反过来。

本轮不提前建立复杂 outbox，是因为还没有上传协议；但 `PENDING_UPLOAD` 已经让同步意图具备持久性，并暴露了未来演进方向。

## 离线删除为什么尤其危险？

如果用户离线删除记录，直接执行 `DELETE`，本地看起来正确，但同步器重启后已经找不到“要删除哪个 ID”。下一次 refresh 还可能把服务器记录重新插回，出现数据复活。

常见方案是 tombstone：记录仍存在，但 `is_deleted = true` 且 `sync_state = PENDING_UPLOAD`。UI 隐藏它，同步器仍能上传删除意图；服务器确认后再物理清理。另一种方案是把删除写入独立 outbox。

这说明 offline-first 不是给 SELECT 加缓存，而是让每一种 mutation 都有可恢复、可重放、可确认的生命周期。

## 三个平台怎样打开同一套数据库？

共同代码只需要 `SqlDriver`：

```kotlin
fun openPocketLabDatabase(
    driver: SqlDriver,
): PocketLabDatabaseHandle = PocketLabDatabaseHandle(
    database = PocketLabSqlDatabase(driver),
    driver = driver,
)
```

Android 需要 Context：

```kotlin
AndroidSqliteDriver(
    schema = PocketLabSqlDatabase.Schema,
    context = context.applicationContext,
    name = "pocketlab.db",
)
```

iOS Native driver 需要文件名：

```kotlin
NativeSqliteDriver(
    schema = PocketLabSqlDatabase.Schema,
    name = "pocketlab.db",
)
```

JVM 使用 JDBC URL：

```kotlin
JdbcSqliteDriver(
    url = "jdbc:sqlite:$path",
    properties = Properties(),
    schema = PocketLabSqlDatabase.Schema,
)
```

数据库 handle 暴露 `close()`，显式关闭 driver。与 HttpClient 一样，长期资源必须有所有者。

这里没有强行写一个无参 `expect createDatabase()`，因为 Android 创建数据库合理地需要 Context。把 Context 隐藏在全局单例里，只是为了让 expect 签名好看，会牺牲依赖透明度。

平台创建 driver，再交给共同工厂。这是接口注入与平台工厂比大面积 `expect/actual` 更灵活的又一个例子。

## Android Host 测试到底用了什么？

commonTest 声明测试 driver 工厂：

```kotlin
internal expect fun createTestSqlDriver(): SqlDriver
```

JVM 与 Android Host 使用 JDBC 内存数据库：

```kotlin
JdbcSqliteDriver(
    url = JdbcSqliteDriver.IN_MEMORY,
    properties = Properties(),
    schema = PocketLabSqlDatabase.Schema,
)
```

iOS x64 使用真正 Native 内存 driver：

```kotlin
inMemoryDriver(PocketLabSqlDatabase.Schema)
```

Android Host 测试证明共同 SQL 与生成代码能进入 hostTest compilation，Repository、事务、Flow 和合并规则正确，JDBC SQLite 也能执行这些查询。

它没有证明 Android framework driver、Context 文件路径和设备 SQLite 行为一定正确。那需要 instrumentation 测试。

测试结论必须与 driver 事实一致，不能因为任务名包含 Android 就把 JVM host test 说成设备测试。

## 九条新测试，三端执行 27 次

本次新增：

1. 本地保存立即进入 Flow，并标记 pending。
2. 待上传记录按最旧优先排列。
3. 远端记录插入并按更新时间排序。
4. 较新远端版本替换 synced 本地版本。
5. 较旧远端版本不能回滚本地版本。
6. pending 本地版本不会被远端覆盖。
7. 批量合并只发射完整事务快照。
8. refresh 成功通过数据库改变事实来源。
9. refresh 失败保持本地数据不变。

运行：

```shell
./gradlew :shared:allTests :shared:verifySqlDelightMigration
```

结果：

| 目标 | 新增测试 | 累计测试 | 失败/错误/跳过 |
|---|---:|---:|---:|
| JVM | 9 | 36 | 0 |
| Android Host | 9 | 36 | 0 |
| iOS x64 Simulator | 9 | 36 | 0 |

累计 108/108 通过，migration verifier 同时通过。

这些结果证明三端共享 schema、生成查询、事务和合并语义；它们仍不证明磁盘空间不足、进程被杀、文件加密、Android 设备 driver 或真实后台同步行为。

## Schema 是产品历史，不是实现细节

数据库上线后，旧用户不会自动获得一份全新空库。他们带着 v1 数据升级到 v2、v3。

SQLDelight 中：

- `.sq` 描述最新空数据库应长什么样；
- `1.sqm` 描述从 v1 升到 v2；
- `2.sqm` 描述从 v2 升到 v3；
- `1.db` 保存第一版 schema 基线。

构建配置：

```kotlin
schemaOutputDirectory.set(
    file("src/commonMain/sqldelight/databases")
)
verifyMigrations.set(true)
```

本轮已经生成 `databases/1.db`，并运行：

```shell
./gradlew :shared:verifySqlDelightMigration
```

未来增加 `1.sqm` 后，verifier 会从基线应用 migration，再比较最终 schema 与最新 `.sq`。

但结构相等仍不代表数据迁移正确。例如把一个字段拆成两个字段时，必须另外测试旧数据怎样转换。

迁移验收至少有三层：SQL 能执行、最终 schema 正确、真实旧数据语义正确。只通过第一层，不足以上线。

## 一个真实 Gradle 陷阱：生成基线和验证不要同批调度

第一次我们运行：

```shell
./gradlew \
  :shared:generateCommonMainPocketLabSqlDatabaseSchema \
  :shared:verifySqlDelightMigration
```

Gradle 8.14.4 报告 implicit dependency：verify 读取了 generate 的输出，但 SQLDelight 插件没有在这两个被同时调度的任务之间声明依赖。

正确工作流是首次单独生成：

```shell
./gradlew :shared:generateCommonMainPocketLabSqlDatabaseSchema
```

提交 `1.db` 后，日常 CI 只运行：

```shell
./gradlew :shared:verifySqlDelightMigration
```

第二条已独立验证通过。

这个问题不是 migration 失败，而是 task graph 声明问题。诊断时区分“SQL 错”“schema 不匹配”和“Gradle 输入输出关系错”，能省掉大量无效改动。

## 七个常见反模式

### 1. 把数据库叫 cache，网络叫 source of truth

这样本地写入永远只是临时状态，断网体验与冲突行为很难稳定。移动端离线优先场景中，本地数据库通常是 UI 的直接事实来源，服务器是同步伙伴。

### 2. 网络成功后直接更新 StateFlow，顺便写数据库

内存状态与数据库可能因进程终止或写入失败分叉。先提交数据库，让查询 Flow 自然更新状态。

### 3. refresh 前清空表

会删除 pending 本地记录，并制造空列表闪烁。应在事务中按身份与版本合并。

### 4. 用客户端时间戳声称解决了冲突

时间戳只能实现有限 LWW，不能表达并发因果。需要服务器 revision 或显式冲突协议。

### 5. 待同步队列只放内存

进程重启后意图消失。同步工作必须由持久数据驱动。

### 6. 离线删除直接删行

删除意图随记录一起消失，refresh 会让数据复活。使用 tombstone 或 outbox。

### 7. 因为库“支持 iOS”就不检查 variant

平台名不是 target。必须验证项目真实 architecture、host 和 driver 组合。

## Room 与 SQLDelight 应该怎样重新比较？

迁移到 Apple Silicon 后，不要因为这次失败永远排除 Room。可以用同一个垂直切片重新比较：

| 维度 | Room 3 | SQLDelight |
|---|---|---|
| 模型入口 | Kotlin annotations / DAO | SQL schema / queries |
| 代码生成 | KSP | SQLDelight compiler |
| SQL 可见性 | Query annotation 为主 | SQL 是一等源码 |
| Android 团队熟悉度 | 通常更高 | 需要 SQL-first 心智 |
| target 覆盖 | 看具体版本 variant | 看具体 driver variant |
| 迁移体验 | Room migrations/schema | `.sqm` + schema baseline |
| 低层控制 | SQLiteDriver API | driver + 直接 SQL |

然后实际测量 clean/incremental build 时间、iOS framework 体积、冷启动与首查时间、大批量事务性能、migration 诊断、Swift API 和团队维护成本。

不要用“Android 官方”或“跨平台老牌”替代工程数据。

## 一张表收住职责

| 职责 | 放在哪里 | 原因 |
|---|---|---|
| schema 与查询 | commonMain `.sq` | 三端语义相同 |
| 领域记录与同步状态 | commonMain | 产品语义相同 |
| 本地 merge transaction | commonMain Repository | 冲突保护相同 |
| 远端 DTO 映射 | commonMain network | 协议相同 |
| refresh 编排 | commonMain use case | 数据流顺序相同 |
| SQLite driver | 平台 source set | 打开机制不同 |
| Android Context / 文件路径 | Android | 平台生命周期与文件系统 |
| iOS 文件位置与备份策略 | iOS | Apple 平台政策 |
| 后台调度 | 平台入口 | OS 限制不同 |
| UI 同步提示 | 原生 UI | 呈现和本地化不同 |

## 最后的心智模型

- Offline-first 首先是事实来源决策，不是缓存 API。
- UI 观察数据库；网络只更新数据库。
- 本地 mutation 先持久化，再异步同步。
- 同步意图必须在进程重启后仍存在。
- pending 本地写不能被只读 refresh 覆盖。
- 批量 merge 必须事务化，保证数据与观察原子性。
- 时间戳规则是最小策略，不是完整冲突协议。
- SQLDelight 共享 SQL 与生成 API，driver 仍然分平台。
- “支持 iOS”必须展开成具体 architecture variant。
- schema migration 是产品历史，必须从 v1 开始验证。
- 测试要说清用了哪个 driver，以及没有证明什么。

至此，PocketLab 已经具备一条真正能离线生存的数据路径：

```text
用户保存
→ 本地事务
→ PENDING_UPLOAD
→ Flow 更新 UI
→ 远端 refresh 事务合并
→ 缓存始终可读
```

但还有一半最难的路没有走：上传。

下一阶段会继续构建同步协议或进入 Swift Concurrency 互操作，届时需要回答 operation id、幂等、重试、取消、后台执行和服务器确认怎样共同改变本地状态。

## 动手挑战：让删除也能离线

设计一个可恢复的删除流程：

```text
VISIBLE
  → PENDING_DELETE
  → DELETE_CONFIRMED
  → PURGED
```

要求：

1. 点击删除后 UI 立即隐藏记录。
2. App 重启后仍知道要上传哪个删除。
3. refresh 不能让 pending delete 的记录复活。
4. 删除请求带稳定 operation id，重复发送保持幂等。
5. 服务器确认后才物理清理 tombstone。
6. commonTest 覆盖进程重启等价场景与冲突 refresh。

完成后，再决定同步元数据继续放在业务表，还是演进为独立 outbox。

## 参考资料

- [Room 3 release notes](https://developer.android.com/jetpack/androidx/releases/room3)
- [Room for Kotlin Multiplatform](https://developer.android.com/kotlin/multiplatform/room)
- [SQLDelight multiplatform SQLite](https://sqldelight.github.io/sqldelight/2.4.0/multiplatform_sqlite/)
- [SQLDelight coroutines extensions](https://sqldelight.github.io/sqldelight/latest/multiplatform_sqlite/coroutines/)
- [SQLDelight migrations](https://sqldelight.github.io/sqldelight/latest/native_sqlite/migrations/)
- [SQLDelight releases](https://github.com/sqldelight/sqldelight/releases)
- [Kotlin/Native target support](https://kotlinlang.org/docs/native-target-support.html)
