# 实验 04：把 `expect/actual` 限制在墙上的插座

本实验继续使用累计 KMP 工程：

```text
../02-first-kmp-module/shared
```

## 实验目标

比较两类看似相近、职责不同的抽象：

- 普通接口：领域层描述需要的能力，支持 fake 和多个实现。
- `expect/actual`：编译器为每个 target 选择一个平台声明实现。

我们保留 `EpochClock` 与 `ExperimentIdGenerator` 普通接口，只用 `expect/actual` 创建生产适配器。

## Source set 分布

```text
commonMain
└── expect createPlatformClock / createPlatformIdGenerator
    ├── jvmMain     → System.currentTimeMillis + UUID
    ├── androidMain → System.currentTimeMillis + UUID
    └── iosX64Main  → CFAbsoluteTime + NSUUID
```

共同保存用例只看到 `EpochClock`、`ExperimentIdGenerator`，没有任何平台类型。

## 为什么不是 `expect class Repository`

Repository 有状态、需要测试替换，而且未来可能同时存在内存、数据库、同步和迁移实现。它的多实现需求来自运行时与环境，不只是 target。把它做成 expected class 会混淆语言匹配和依赖注入。

Clock/ID 的工厂则足够薄：共同组合代码只需要“给我默认生产适配器”，每个 target 都必须提供一个实现。

## 运行

```shell
cd ../02-first-kmp-module
./gradlew :shared:allTests
```

新增 3 条 commonTest 契约：

1. 平台时钟返回合理的 epoch milliseconds。
2. 平台 ID 非空且连续两次不同。
3. common factory 能使用真实 adapters 完成保存。

它们会在三个 targets 中分别调用对应 `actual`，实际新增 9 次执行。

## 已验证结果（2026-11-18）

| 目标 | 实验前 | 新增契约 | 当前总数 | 失败 |
|---|---:|---:|---:|---:|
| JVM | 11 | 3 | 14 | 0 |
| Android Host | 11 | 3 | 14 | 0 |
| iOS x64 Simulator | 11 | 3 | 14 | 0 |

合计 42/42 通过。三个 `PlatformDependenciesContractTest` 报告分别来自对应 target 的 JUnit XML。

这是实验 04 完成时的历史基线；实验 05 引入 StateFlow 后，累计测试继续增长。

首次 iOS 编译并未通过：当前 Kotlin/Native declaration set 无法解析预期的 `NSDate().timeIntervalSince1970` 访问形式。根据本机 iPhoneSimulator SDK 的 `CFDate.h`，实现改为：

```kotlin
(CFAbsoluteTimeGetCurrent() + kCFAbsoluteTimeIntervalSince1970) * 1_000
```

随后 `compileKotlinIosX64` 与全部运行时契约测试通过。这个过程说明：平台 API “头文件里存在”不等于 Kotlin cinterop 中一定以直觉形式出现；以编译器声明和实际测试为准。

## 故障注入

1. 临时删除 `iosX64Main` 的 `actual fun createPlatformClock()`。
2. 运行 `./gradlew :shared:compileKotlinIosX64`。
3. 观察编译器指出缺失 actual，而不是等运行时崩溃。
4. 恢复实现后，把返回类型改成具体平台类，观察 expected/actual 签名为何无法匹配。

## 观察重点

- expected 与 actual 必须处于同一 package，签名兼容。
- 匹配发生在编译期，不是运行时平台判断。
- commonTest 调用 expect 声明，目标测试 compilation 链接相应 actual。
- `expect/actual` 解决“target 提供什么”；普通接口解决“对象如何替换与组合”。

