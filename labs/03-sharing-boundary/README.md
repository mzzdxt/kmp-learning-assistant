# 实验 03：为 PocketLab 划出第一条共享边界

本实验在实验 02 的可运行 KMP 工程上继续增量开发，避免复制一套工具链。代码位于：

```text
../02-first-kmp-module/shared/src/commonMain/kotlin/dev/kmpjourney/pocketlab
../02-first-kmp-module/shared/src/commonTest/kotlin/dev/kmpjourney/pocketlab
```

## 要解决的问题

用户提交实验草稿时，需要：

1. 校验标题与假设。
2. 失败时返回可映射到本地化文案的结构化错误。
3. 成功时规范化文本、生成 ID、读取时间并保存。
4. 保证失败路径不触发任何副作用。

## 边界设计

| 职责 | 归属 | 原因 |
|---|---|---|
| 标题/假设校验 | `commonMain` | 两端必须完全一致的产品规则 |
| 稳定错误码 | `commonMain` | 两端需要相同语义 |
| 中文/英文错误文案 | 原生 UI | 本地化与呈现可能不同 |
| 保存用例编排 | `commonMain` | 副作用顺序是业务规则 |
| Repository/Clock/ID 接口 | `commonMain` | 表达需要的能力，不指定技术 |
| 数据库、UUID、系统时间实现 | 平台/基础设施 | 依赖具体库和运行环境 |
| 表单与导航 | Android/iOS UI | 平台交互习惯不同 |

## 为什么这里不用 `expect/actual`

Repository、ID 和 Clock 都需要在测试中替换，而且同一平台将来可能同时存在生产实现、内存实现和迁移实现。普通接口允许多个实现；`expect/actual` 更适合薄而稳定的编译期平台接缝。

## 运行

```shell
cd ../02-first-kmp-module
./gradlew :shared:allTests
```

PocketLab 新增 6 条共享测试：

- 3 条校验器测试。
- 3 条保存用例测试。

它们会分别进入 JVM、Android Host 和 iOS 测试 compilation，所以实际新增 18 次跨目标执行。

## 已验证结果（2026-11-18）

| 目标 | 原有测试 | PocketLab 新测试 | 当前总数 | 失败 |
|---|---:|---:|---:|---:|
| JVM | 5 | 6 | 11 | 0 |
| Android Host | 5 | 6 | 11 | 0 |
| iOS x64 Simulator | 5 | 6 | 11 | 0 |

验证命令 `./gradlew :shared:allTests` 输出 `BUILD SUCCESSFUL`。数字来自三个目标生成的 JUnit XML，而不只是任务退出码。

这是实验 03 完成时的历史基线；实验 04 新增平台适配器契约测试后，累计数字继续增长。

## 观察点

1. `DraftProblem` 保存 reason code 而不是直接保存中文错误信息。
2. `SaveDraftUseCase` 先校验，再调用 ID、Clock、Repository。
3. commonTest 的 fake 是测试观察工具，不是伪装成生产架构的“万能内存层”。
4. 生产代码没有 `Context`、`UIDevice`、SQL 或 HTTP 类型。

## 挑战

给草稿增加一个可选的 `tags: List<String>`：每个标签去除空白、忽略空标签、不区分大小写去重，最多保留 5 个。先写 commonTest，再决定规范化逻辑属于模型、Validator 还是 UseCase，并在学习日志中解释理由。

