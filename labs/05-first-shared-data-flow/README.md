# 实验 05：第一条跨越 Android 与 iOS 的状态流

本实验继续使用累计工程：

```text
../02-first-kmp-module/shared
```

核心代码：

```text
src/commonMain/kotlin/dev/kmpjourney/pocketlab/DraftEditorStore.kt
src/commonTest/kotlin/dev/kmpjourney/pocketlab/DraftEditorStoreTest.kt
```

## 实验目标

- 把 Repository 与保存用例改成 `suspend`。
- 用 StateFlow 暴露平台无关的屏幕状态。
- 让平台宿主拥有 CoroutineScope。
- 用原子状态转换阻止重复提交。
- 使用虚拟时间在 commonTest 验证异步状态序列。

## 状态机

```text
                  ┌────────── REJECTED
IDLE ── save ──> SAVING ───── SAVED
  ^               └────────── FAILED
  | cancellation
  └───────────────────────────
```

从 `SAVED`、`REJECTED` 或 `FAILED` 再次编辑字段，会回到 `IDLE`。保存期间第二次 `save()` 返回 `false`，字段编辑被忽略。

## 依赖配置

```kotlin
commonMain.dependencies {
    api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
}

commonTest.dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
}
```

StateFlow 出现在公开 API 中，因此 Core 使用 `api`；测试工具只进入 commonTest。

## 运行

```shell
cd ../02-first-kmp-module
./gradlew :shared:allTests
```

## 已验证结果（2026-11-18）

| 目标 | 实验前 | Store 新测试 | 当前总数 | 失败 |
|---|---:|---:|---:|---:|
| JVM | 14 | 5 | 19 | 0 |
| Android Host | 14 | 5 | 19 | 0 |
| iOS x64 Simulator | 14 | 5 | 19 | 0 |

合计 57/57 通过。新增测试覆盖：

1. 字段编辑形成平台无关状态。
2. `IDLE → SAVING → SAVED` 的 Flow 发射序列。
3. 校验失败成为 REJECTED，Repository 不写入。
4. Repository 异常成为稳定 FAILED 状态。
5. 保存进行中拒绝第二次提交。

测试 Repository 延迟 1 秒，但 `runTest` 与 `advanceUntilIdle()` 使用虚拟时间，测试不需要真实等待。

## 重要边界

- `suspend` 只表示函数可挂起，不保证自动在后台线程执行。
- Store 不创建 Scope；Android/iOS 入口必须在生命周期结束时取消注入的 Scope。
- Store 不硬编码 dispatcher；真正的数据库实现负责选择合适执行上下文。
- StateFlow 保存“当前页面是什么样”，不是一次性 Toast/导航事件队列。
- Swift 消费 StateFlow 仍需桥接设计，本实验只先验证共同状态契约。

## 挑战

给 RecordingRepository 增加可控制的 `CompletableDeferred`，不使用 `delay` 手动决定保存何时完成。验证：

1. SAVING 期间字段编辑不会覆盖正在保存的快照。
2. 取消注入 Scope 后，状态回到 IDLE。
3. 被取消的保存不会产生 SAVED 状态。

