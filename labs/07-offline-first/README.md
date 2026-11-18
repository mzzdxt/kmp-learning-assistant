# 实验 07：让数据库成为离线世界的事实来源

本实验继续使用累计工程：

```text
../02-first-kmp-module/shared
```

核心代码：

```text
src/commonMain/sqldelight/
├── databases/1.db
└── dev/kmpjourney/pocketlab/data/Experiment.sq

src/commonMain/kotlin/dev/kmpjourney/pocketlab/
├── ExperimentCatalog.kt
├── data/
│   ├── PocketLabDatabase.kt
│   └── SqlDelightExperimentRepository.kt
└── sync/RefreshExperimentsUseCase.kt
```

## 实验目标

- 用真实 SQLite 实现原来的 `ExperimentRepository`。
- 让本地数据库成为列表唯一事实来源。
- 本地保存立即可见，并持久化为 `PENDING_UPLOAD`。
- 把远端刷新结果事务性合并到本地，而不是直接交给 UI。
- 防止待上传的本地写入被服务器快照覆盖。
- 在 JVM、Android Host、iOS x64 上运行同一组数据库测试。
- 从第一版 schema 开始建立迁移基线。

## 离线优先数据流

```text
创建草稿
   |
   v
SaveDraftUseCase
   |
   v
SQLDelight Repository -- 写入 PENDING_UPLOAD
   |
   v
SQLite Flow ----------------------------> UI
   ^
   |
事务合并
   |
RefreshExperimentsUseCase
   |
   v
Ktor RemoteDataSource
```

UI 不观察 Ktor 返回的列表。网络成功时更新数据库，网络失败时数据库不变，所以在线与离线只有一条读取路径。

## 为什么本机选择 SQLDelight

本轮先验证了 Room 3.0.3。Room 3 已是面向 KMP 的现代实现，但其稳定发布物与 Bundled SQLite 依赖不包含 `iosX64` 变体，无法满足当前 Intel macOS 上的 iOS Simulator target。

SQLDelight 2.4.1 仍提供 `native-driver-iosx64`，因此能保留原有三端验收矩阵：

| 方案 | JVM | Android | iosArm64 / iosSimulatorArm64 | iosX64 | 本实验采用 |
|---|---:|---:|---:|---:|---:|
| Room 3.0.3 | 是 | 是 | 是 | 否 | 否 |
| SQLDelight 2.4.1 | 是 | 是 | 是 | 是 | 是 |

这不是通用排名。迁移到 Apple Silicon 后，应基于当时版本、团队经验、迁移工具、性能和体积重新比较。

## Gradle 与 driver

```kotlin
plugins {
    id("app.cash.sqldelight")
}

commonMain.dependencies {
    implementation("app.cash.sqldelight:runtime:2.4.1")
    implementation("app.cash.sqldelight:coroutines-extensions:2.4.1")
}

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

共同代码依赖 SQLDelight runtime；真正打开 SQLite 的 driver 属于平台 source set。

## Schema 与类型安全查询

`Experiment.sq` 同时定义表和查询：

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

SQLDelight 在构建期解析 SQL 并生成 `PocketLabSqlDatabase`、`ExperimentQueries` 和 Kotlin 参数类型。Repository 不拼接 SQL 字符串。

## 本地保存

本地记录不会先请求服务器：

```text
SavedExperiment
→ 数据库事务
→ UPDATE；若 0 行则 INSERT
→ sync_state = PENDING_UPLOAD
→ Flow 发射新列表
```

没有使用 `INSERT OR REPLACE`，因为 SQLite 的 REPLACE 可能删除旧行再插入；加入外键或触发器后容易产生意外副作用。

## 远端合并规则

对服务器快照中的每条记录：

| 本地状态 | 远端条件 | 决策 |
|---|---|---|
| 不存在 | 任意 | 插入为 `SYNCED` |
| `PENDING_UPLOAD` | 任意时间戳 | 保留本地 |
| `SYNCED` | 远端更新 | 用远端替换 |
| `SYNCED` | 远端相同或更旧 | 保留本地 |

整批合并位于同一 transaction，Flow 观察者只看到完整批次。

这只是刷新规则，不是完整冲突协议。客户端时间戳不能可靠表达多设备并发；后续上传应使用服务器 revision、ETag 或显式冲突版本。

## 运行

```shell
cd ../02-first-kmp-module
./gradlew :shared:allTests :shared:verifySqlDelightMigration
```

## 已验证结果（2026-11-19）

| 目标 | 实验前 | 数据库/刷新新测试 | 当前总数 | 失败/错误/跳过 |
|---|---:|---:|---:|---:|
| JVM | 27 | 9 | 36 | 0 |
| Android Host | 27 | 9 | 36 | 0 |
| iOS x64 Simulator | 27 | 9 | 36 | 0 |

合计 108/108 通过。新增测试覆盖：

1. 本地保存从空列表发射为 `PENDING_UPLOAD`。
2. 待上传记录按最旧优先排列。
3. 远端快照插入为 `SYNCED`，列表按更新时间倒序。
4. 较新的远端版本替换已同步本地版本。
5. 较旧远端版本不能回滚本地版本。
6. 待上传本地版本即使时间戳较旧，也不会被远端覆盖。
7. 批量合并只发布完整事务结果。
8. 刷新成功通过数据库改变事实来源。
9. 刷新失败保持本地数据不变。

数据库测试使用真正 driver：JVM 与 Android Host 使用 JDBC 内存 SQLite，iOS x64 使用 Native 内存 SQLite。Android Host 的 JDBC 验证不等于 Android 设备 driver 集成，后续仍需 instrumentation 测试。

## 迁移基线

构建脚本启用了：

```kotlin
schemaOutputDirectory.set(
    file("src/commonMain/sqldelight/databases")
)
verifyMigrations.set(true)
```

`databases/1.db` 是 v1 schema 基线。未来升级到 v2 时：

1. 更新 `.sq` 中的最新 schema。
2. 新建 `1.sqm`，写出 v1 → v2 的 SQL。
3. 运行 `verifySqlDelightMigration`。
4. 写数据迁移行为测试，不只验证表结构。

首次生成基线与验证应分两条 Gradle 命令执行：

```shell
./gradlew :shared:generateCommonMainPocketLabSqlDatabaseSchema
./gradlew :shared:verifySqlDelightMigration
```

把两个任务放进同一次调用，在当前 Gradle/插件组合中会触发未声明 task dependency 的验证错误；日常 CI 只需运行第二条。

## 本实验刻意没有完成什么

- 没有上传 endpoint，也没有把 `PENDING_UPLOAD` 标记为 `SYNCED` 的确认协议。
- 没有删除 tombstone；直接删行无法在离线后告诉服务器发生过删除。
- 没有重试次数、退避时间、幂等 key 和后台调度。
- 没有解决真正的多设备并发冲突。
- 没有测试 Android framework SQLite driver 或真实文件路径。

这些不是遗漏，而是下一阶段同步协议必须显式回答的问题。

## 观察点

1. 去掉合并 transaction，观察批量写入时 Flow 是否可能看到中间状态。
2. 去掉 `PENDING_UPLOAD` 保护，用一个更新的服务器时间戳覆盖本地草稿。
3. 把 UI 数据源改成远端返回值，模拟 503 后思考缓存列表放在哪里。
4. 在 `.sq` 中把 `updated_at_ms` 改成 TEXT，观察生成 API 和 Repository 编译错误。
5. 临时加入非法 `sync_state`，观察 SQLite `CHECK` 约束如何拒绝脏数据。

## 挑战：实现可恢复的离线删除

不要直接 `DELETE FROM experiments`。设计并验证：

1. 本地删除后 UI 立即隐藏记录。
2. 重启应用后删除意图仍存在。
3. 上传删除使用稳定 operation id，重复请求保持幂等。
4. 服务器确认前，刷新不能让记录“复活”。
5. 确认后清理 tombstone。

完成后再判断：同步元数据应该继续放在业务表，还是升级成独立 outbox 表。
