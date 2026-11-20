# KMP 构建为什么慢：别先加内存，先把 4 分 37 秒拆开

> 系列第 15 篇：构建为什么慢，以及如何用数据优化

上一篇我们把 PocketLab 的万能 `shared` 拆成了 `domain + shared`。

边界清楚了，Apple header 也从 2,267 行降到 888 行。

然后一个新问题非常有存在感地走进房间：

```text
Release XCFramework 构建：4 分 37 秒
```

这时，一个 Android 开发者的手很容易形成肌肉记忆：

```properties
org.gradle.jvmargs=-Xmx8g
org.gradle.parallel=true
org.gradle.caching=true
kotlin.incremental.native=true
```

保存，重跑。

如果快了，我们会说参数有效；如果没快，再去搜索下一组“Gradle 提速神配置”。

这套操作有一个问题：

> 我们甚至还不知道，那 4 分 37 秒里究竟是谁在工作。

是 Gradle 配置项目？

是 Kotlin 编译？

是 SQLDelight 生成代码？

是 Kotlin/Native 把 KLIB 链进 framework？

还是我们在一次普通代码编辑后，错误地要求机器同时构建 device arm64、Simulator arm64 和 Simulator x86_64 三份 Release 产物？

这一篇不提供一份“所有 KMP 项目都应该复制”的配置。

我们做一件更接近工程师的事：

```text
把“慢”写成假设
→ 设计可比较实验
→ 查看 task 和编译器证据
→ 只保留能解释收益的改动
```

最后你会看到，PocketLab 确实变快了。

但最值钱的不是那几百毫秒，而是我们终于知道它为什么快。

---

## 一、先审问那个 4 分 37 秒

PocketLab 的 Apple 发布门禁做了这些事：

```text
Kotlin source
├── compile iosArm64 Release
├── compile iosSimulatorArm64 Release
├── compile iosX64 Release
├── link 3 组 static frameworks
├── assemble XCFramework
├── 检查 architectures / Info.plist / header
├── Swift 6 strict-concurrency type-check consumer
├── 生成本地 Swift package
├── ZIP
└── 计算 SwiftPM checksum
```

它慢吗？

作为“每保存一次 Kotlin 文件就执行”的命令，非常慢。

作为“准备发布不可变的三架构 Release 二进制”的命令，4 分 37 秒可能完全合理。

这里出现了构建性能的第一条原则：

> 不要先问“整个 build 多久”，先问“我为了获得哪一种反馈，运行了哪些 tasks”。

下面这些都叫“构建”，但它们不是同一种工作：

| 工作流 | 真正想知道什么 |
|---|---|
| `gradlew help` | Gradle 配置固定成本有多大？ |
| JVM tests | 一行业务规则改完是否仍正确？ |
| Android Host tests | Android target 的编译/适配是否正确？ |
| iOS Simulator Debug | Native 编译、互操作和运行是否正确？ |
| Release XCFramework | 多架构发布物能否交付给外部 consumer？ |

如果每天都用最后一行回答第一行的问题，优化再多缓存也救不了工作流。

这就像为了确认厨房里的盐够不够，每次都重新装修整家餐厅。

---

## 二、“缓存”不是一种东西

我们先解决最容易混淆的概念。

Gradle/Kotlin 构建输出里经常出现：

```text
UP-TO-DATE
FROM-CACHE
Configuration cache entry reused
Incremental compilation
```

它们看起来都在说“这次少做一点”，但保存的东西和生效阶段完全不同。

### 1. 依赖缓存：食材买过了

Gradle dependency cache 保存下载过的 plugin、module、metadata 和 artifacts。

它回答：

> Ktor、SQLDelight、Kotlin plugin 这些外部材料，本机是否已经有了？

第一次 checkout 慢在下载，之后不下载，通常是它的功劳。

它不意味着 `DraftValidator.kt` 已经编译过。

### 2. Up-to-date：菜还在桌上

一个 task 的 inputs 没变，outputs 也还在原位置，Gradle 可以直接跳过：

```text
> Task :domain:compileKotlinJvm UP-TO-DATE
```

同一工作区的日常循环里，这是非常便宜的路径。

也正因为如此，习惯性执行 `clean` 很可惜。

`clean` 做的事情，恰好是把这些仍然有效的本地 outputs 删除。

### 3. Build cache：保温柜里有同一份成品

假设 `build/` 已被删除，但 Gradle 以前见过完全相同的 task inputs，并保存了 outputs。

它可以恢复：

```text
> Task :domain:compileKotlinJvm FROM-CACHE
```

build cache 回答：

> 这道题是否有人用相同输入算过，结果能不能直接拿回来？

本地 outputs 还在时，`UP-TO-DATE` 通常比从 cache 拷贝更直接。

### 4. Configuration cache：排班表已经排好了

Gradle 并不是拿到 `jvmTest` 三个字就立刻执行。

普通运行大致经历：

```text
初始化
→ 读取 settings
→ 解析 build scripts/plugins
→ 创建并配置 projects/tasks
→ 解析 task graph
→ 执行 tasks
```

configuration cache 保存的是完成配置后可复用的 task graph 与相关状态。

命中时你会看到：

```text
Reusing configuration cache.
```

它不是 Kotlin class 文件缓存，也不是编译结果缓存。

tasks、Gradle arguments 或配置输入变化，都可能产生不同 key。

### 5. Kotlin incremental compilation：仍要做菜，但少重做几步

即使 Kotlin compile task 必须执行，编译器仍可能根据 source changes、classpath snapshots 和历史增量数据减少分析/生成范围。

它与 build cache 的边界是：

```text
incremental compilation
  → compile task 运行，内部尽量少做

build cache
  → 整个 compile task 的 outputs 被恢复，编译器不必工作

up-to-date
  → 本地 outputs 已有效，task 直接跳过
```

### 6. Kotlin/Native 的仓库

`~/.konan` 里还有 Kotlin/Native compiler/toolchain 和下载依赖。

CI 若每次都丢弃它，可能反复下载和准备同一套 Native 工具。

Kotlin/Native 也有 binary cache 和增量相关能力，但一些 Release 选项会涉及编译时间、优化程度、运行性能或二进制体积的取舍。

它们不是“看到 Native 慢就全开”的免费按钮。

把这一节压缩成一张表：

| 机制 | 主要复用对象 | 典型证据 |
|---|---|---|
| dependency cache | 外部依赖 artifacts | 不再下载/解析远程 artifact |
| up-to-date | 当前工作区已有 task outputs | `UP-TO-DATE` |
| build cache | 相同 inputs 的 task outputs | `FROM-CACHE` |
| configuration cache | 配置后的 task graph/state | `entry reused` |
| Kotlin incremental | compile task 内部分析/生成状态 | build report 的 IC reason/stats |
| Konan/Native cache | toolchain、klib/Native 中间结果 | 下载/Native cache 日志 |

从现在开始，再有人说“缓存开了还是慢”，我们的第一反应不该是怀疑 Gradle。

应该先问：

> 你说的是哪一种缓存？你期望哪一个阶段消失？日志里看到了什么？

---

## 三、测量之前，先让两次构建可以比较

性能数字最大的敌人，不是慢。

是不可比较。

第一次运行可能同时做这些事：

```text
启动 Gradle daemon
JVM JIT 预热
下载 dependency
下载 Kotlin/Native toolchain
填充 OS file cache
编译源码
写入 configuration cache
写入 build cache
冷启动 Simulator
```

第二次运行只剩：

```text
连接已经存在的 daemon
检查 inputs/outputs
复用 configuration cache
大量 tasks UP-TO-DATE
```

如果把这两个数字放在一起，然后宣布“某个参数提升 70%”，我们测到的其实是一锅混合物。

PocketLab 的 benchmark 先写下协议：

- 固定 tasks；
- 记录 host、JDK、Gradle、Kotlin、AGP、Xcode；
- 预热 daemon；
- configuration cache 的 store 与 reuse 分开；
- no-op 场景取 3–5 个样本；
- 使用 monotonic clock 测外部 wall time；
- clean 场景每个步骤明确执行 clean；
- 同时记录 `executed / UP-TO-DATE / FROM-CACHE`；
- 不把依赖下载混进编译基线；
- 原始日志只留本地，公开去标识化汇总。

脚本的入口很简单：

```shell
./scripts/benchmark-build.sh config-only 5
./scripts/benchmark-build.sh dev-noop 5
./scripts/benchmark-build.sh native-noop 3
./scripts/benchmark-build.sh dev-clean-cache 1
```

结果会写到被 Git 忽略的：

```text
build/benchmarks/<scenario>-<utc>-<pid>/
├── results.tsv
├── summary.md
└── logs/
```

为什么 raw logs 不直接提交？

Kotlin/Gradle 报告可能包含：

- 用户名与绝对路径；
- compiler arguments；
- dependency locations；
- CI workspace；
- 环境和构建结构信息。

构建报告也是数据资产，要考虑可见范围和保留期。

---

## 四、configuration cache 到底省了多少？

先运行最接近“只配置”的命令：

```text
help
```

在这台 Intel Mac、当前 PocketLab 工程上：

| 状态 | 样本 | 平均 | 最小 | 最大 |
|---|---:|---:|---:|---:|
| cache disabled | 5 | 1,245 ms | 1,191 ms | 1,324 ms |
| cache store | 1 | 1,254 ms | 1,254 ms | 1,254 ms |
| cache reuse | 5 | 916 ms | 882 ms | 930 ms |

reuse 相比 disabled 平均低约 26.4%。

然后换成日常开发工作流：

```text
:domain:jvmTest
:shared:jvmTest
:androidApp:testDebugUnitTest
```

| 状态 | 样本 | 平均 | 最小 | 最大 |
|---|---:|---:|---:|---:|
| cache disabled | 5 | 1,663 ms | 1,530 ms | 1,718 ms |
| cache store | 1 | 4,532 ms | 4,532 ms | 4,532 ms |
| cache reuse | 5 | 1,024 ms | 949 ms | 1,165 ms |

reuse 相比 disabled 平均低约 38.4%。

注意最有趣的一行：

```text
第一次 store：4532 ms
```

它比关闭 configuration cache 更慢。

这不是失败。

第一次需要序列化和保存配置状态，收益发生在相同 invocation 的后续 reuse。

所以准确结论是：

> 当前 PocketLab 的重复 JVM 开发任务，从 configuration cache reuse 获得约 0.6 秒平均收益；第一次 store 有额外成本。

不准确结论是：

> 打开 configuration cache，所有构建都会快 38.4%。

还有一个很小却真实的细节：我们的 benchmark 给 Kotlin report 增加 label。当 label 改变，Gradle 会告诉我们 configuration cache key 受 Gradle property 变化影响，需要重新计算。

为了测量而引入的参数，也可能改变被测系统。

这就是为什么基准测试需要写协议，而不是手敲两次命令截图。

---

## 五、我们差点发布了一个错误的“提升 48%”

第一次 clean/build-cache 实验出现了一组很诱人的数字：

```text
clean compile：23.077 s
下一次运行：11.911 s
```

看起来后者快了约 48%。

标题都可以想好了：

> 一行配置，让 KMP 构建速度翻倍

但打开日志后，第二次只有：

```text
1 task FROM-CACHE
```

这根本不足以证明 11 秒的差异来自 build cache。

里面同时混入了 daemon、配置调度、文件系统 cache 和机器状态。

所以这组结论被删除，重新设计场景。

第二次受控序列是：

| 步骤 | Wall time | Task outcomes |
|---|---:|---|
| clean compile，关闭 build cache | 11,655 ms | 49 executed |
| clean 后 cache population/restore | 2,408 ms | 21 executed，28 from cache |
| 再 clean 后 warm restore | 1,510 ms | 21 executed，28 from cache |
| outputs 保留的 no-op | 1,039 ms | 2 executed，47 up-to-date |

这一次，我们可以明确说：

- warm restore 的确有 28 个 tasks `FROM-CACHE`；
- no-op 更快，因为 47 个 tasks 的本地 outputs 仍然 `UP-TO-DATE`；
- build cache 开启不代表所有 tasks 都能或都需要从 cache 恢复；
- 第一个所谓“cache population”已经命中本机旧 cache，因此不能冒充全新的 remote runner。

这段实验最重要的产物，不是 1.510 秒。

而是一个判断方法：

> wall time 描述人等了多久；task outcomes 才能帮助解释为什么。

优秀的性能分析不是给每个数字都编出一个理由。

而是看到证据不足时，愿意写下：

```text
这组结果无效，重测。
```

---

## 六、打开 Kotlin 编译器的黑箱

Gradle 告诉我们哪个 task 花时间。

Kotlin build report 可以继续告诉我们，Kotlin task 里面发生了什么。

PocketLab 开启：

```properties
kotlin.build.report.output=file,json
```

text report 适合人工阅读，JSON 可以给后续趋势分析。

报告中能看到：

- Gradle configuration time；
- Kotlin task/compilation time；
- code analysis 与 code generation；
- incremental rebuild reason；
- analyzed lines；
- Native compiler phases；
- 某些 GC、classpath 和 build attributes。

我们先强制一次单架构 Native Debug framework：

```shell
./gradlew :shared:linkDebugFrameworkIosX64 \
  --rerun-tasks \
  -Pkotlin.build.report.label=native-debug-rerun
```

外部 wall time：

```text
19.80 s
```

报告分解：

| 指标 | 时间 |
|---|---:|
| 全部 Gradle task time | 19.758 s |
| Kotlin task time | 18.323 s |
| `domain:compileKotlinIosX64` | 5.355 s |
| `shared:compileKotlinIosX64` | 2.749 s |
| `shared:linkDebugFrameworkIosX64` | 9.955 s |
| SQLDelight interface generation | 1.665 s |

Kotlin tasks 占全部 task time 的 91.5%。

再看最大的 link task：

| 编译器阶段 | 时间 |
|---|---:|
| LinkKLibs | 3.267 s |
| analysis | 5.385 s |
| backend | 3.337 s |
| IR lowering | 1.341 s |
| IR linking | 3.314 s |
| code generation | 1.513 s |
| object files | 0.588 s |
| native linker | 0.534 s |

现在可以回答：

> 这一次 Native forced build 的主要成本不是 Gradle configuration，而是 Kotlin/Native 编译和链接。

configuration cache 仍然有用。

我们测单架构 Native no-op：

```text
disabled  1562 ms
store     1612 ms
reuse     1080 ms
```

reuse 平均低约 30.9%。

但它优化的是“已有 outputs 后重复调用 task graph”的体验。

它不可能把必须发生的 18 秒 Kotlin 编译工作变没。

---

## 七、19.8 秒与 4 分 37 秒，谁是真的？

两个都是真的。

但它们回答不同问题。

```text
19.8 s
→ 单一 iosX64
→ Debug
→ forced rerun
→ link one framework

4 min 37 s
→ iosArm64 + iosSimulatorArm64 + iosX64
→ Release
→ assemble XCFramework
→ header/metadata/Swift consumer/package/ZIP/checksum gate
```

target 数量不同、build type 不同、优化不同、最终产物不同。

把两者直接相除，得到的“提升倍数”没有意义。

它们共同支持的结论是：

> 日常 Apple 开发应选择 host-compatible 的单一 Debug target；多架构 Release XCFramework 留在 release lane。

KMP 会让同一份 common source 为多个 targets 分别编译。

这是它能输出各平台原生产物的必然结果，不是 Gradle 偷懒。

所以任务选择本身就是性能设计。

---

## 八、模块化真的能加速吗？我们改两次代码看看

上一篇刚拆完 `domain` 和 `shared`，很容易写出一句漂亮但危险的话：

> 模块化之后，增量编译更快了。

这句话需要实验。

PocketLab 的依赖大致是：

```text
domain
  ↓
shared
  ↓
Android App
```

我们做两种临时改动。

### 实验 A：只改私有实现

把私有 validation helper 中：

```kotlin
value.trim().length > maximumLength
```

临时改成语义等价的：

```kotlin
value.trim().length >= maximumLength + 1
```

然后运行同一组开发 tasks，并关闭 build cache，避免整个 task 被旧输出替代。

观察到：

```text
domain JVM/Android compilation      executed
shared JVM/Android compilation      UP-TO-DATE
Android App compilation             UP-TO-DATE
受 runtime classpath 影响的 tests    executed
```

这证明 domain 的实现改动没有让下游 compile classpath ABI 改变，`shared` 和 App compilation 被跳过。

模块边界确实挡住了不必要的编译传播。

但故事还没结束。

Kotlin report 同时出现：

```text
REBUILD_REASON:
Incremental compilation might be incorrect (KT-62686)

Non-incremental compilation will be performed
```

也就是说，改动的 common source 在当前 Kotlin 2.4.21 场景里触发了 domain compile task 的非增量 fallback。

所以我们能说：

> 模块/classpath 边界避免了下游 compilation。

不能说：

> 编译器只重新编译了那一行或那一个文件。

工程结论必须停在证据边界上。

### 实验 B：改公共 ABI

我们临时给 `DraftValidator` 增加一个 public method。

相同 tasks、`--no-build-cache`，结果：

```text
domain compile                 executed
shared compile                 executed
Android App compile            executed
49 actionable tasks:
17 executed, 32 up-to-date
```

公共 ABI 变化沿依赖图传播。

这不是模块化失败，而是正确行为：消费者需要根据新 classpath ABI 重新确认自己能否编译。

于是“模块化能否加速”的更准确答案是：

```text
边界稳定、实现变化频繁
→ 有机会减少下游重新编译

公共 API 频繁变化
→ 依赖它的 modules 仍会被影响

module 数继续增加
→ 配置、task graph、KLIB/link inputs 也有成本
```

模块不是越多越快。

稳定的依赖方向和克制的公共 API，才可能带来增量收益。

---

## 九、为什么 tests 可能重跑，而下游 compilation 不重跑？

这也是实验 A 中很容易疑惑的地方。

私有实现没有改变 ABI，所以消费者源码不必重新编译。

但 runtime 行为可能改变。

下游 tests 的 runtime classpath 包含新的 `domain` artifact，它们需要重新执行，才能证明行为仍然正确。

```text
compile avoidance ≠ test avoidance
```

如果 Gradle 因为 ABI 没变就永远跳过所有下游 tests，那么私有实现里引入的行为 bug 会被漏掉。

所以“tests 又跑了”不一定是浪费。

先问这个 test task 的 inputs 是否真的发生变化，以及它承担什么风险证明。

---

## 十、我们最终只加了两行配置

PocketLab 原本已经有：

```properties
org.gradle.caching=true
```

这次新增：

```properties
org.gradle.configuration-cache=true
kotlin.build.report.output=file,json
```

为什么不多加一些？

### 没有把 heap 改成 8 GB

当前没有 OOM、频繁 full GC 或内存压力证据。

更大的 heap 不等于更快，还可能挤压 Kotlin daemon、Native compiler、Simulator 和系统文件 cache。

### 没有默认打开 `org.gradle.parallel=true`

configuration cache 本身会启用项目内 task parallel execution 能力。当前项目没有独立 A/B 证明额外 flag 有净收益，所以不为了“配置看起来专业”继续堆开关。

### 没有打开 `kotlin.incremental.native=true`

它仍是需要验证的 Beta 能力。

我们要测的不只是速度，还包括：

- 修改集是否正确触发重编译；
- clean 与 incremental outputs 是否一致；
- Xcode/direct integration 是否稳定；
- Kotlin/Gradle 升级后是否仍兼容。

### 没有开启 Native Release binary cache flags

某些选项可能优先构建时间而牺牲部分编译优化。

Release 决策必须一起测：

```text
build time
final App startup/runtime
binary size
crash/symbolication
```

只有构建快，不足以证明 Release 方案更好。

### 没有部署 remote build cache

remote cache 不是“把一个 URL 填进 Gradle”。

它是一项供应链能力：

- 谁能写？
- fork PR 是否只能读？
- cache 污染怎样恢复？
- key 是否包含 OS/architecture/toolchain？
- artifact 是否包含敏感信息？
- 网络传输是否比重新计算更贵？
- 淘汰和存储成本如何控制？

单人小项目还没有跨机器重复计算的收益证据，因此先不引入。

---

## 十一、让优化能力也有门禁

配置写进 `gradle.properties` 不代表它永远生效。

plugin 升级、build logic 修改或某个新 task 不兼容，都可能让 configuration cache 悄悄退化。

PocketLab 新增：

```shell
./scripts/verify-build-performance.sh
```

它检查：

```text
build cache property enabled
configuration cache property enabled
benchmark script syntax valid
Kotlin text report generated
Kotlin JSON report generated
repeated dev task really reused configuration cache
configuration-cache problems fail the build
```

注意它没有写：

```text
构建必须少于 1024 ms
```

在一台共享开发机上，后台负载、温度、文件系统和 daemon 状态就能让毫秒值波动。

因此我们把两类门禁分开：

```text
正确性/能力门禁
→ cache/report 是否能用
→ 可以硬失败

性能趋势
→ p50 / p95 / 相对历史退化
→ 需要稳定 CI 样本后再设预算
```

性能预算当然重要。

只是预算应该建立在可重复的数据分布上，而不是一台笔记本的一次最好成绩。

---

## 十二、给 KMP 工程设计五条反馈车道

优化构建最有效的动作，经常不是让同一个 task graph 更快。

而是不要在每次反馈里运行不相关的 task graph。

PocketLab 现在有五条车道。

### 1. 领域/基础设施 JVM 快速车道

```shell
./gradlew :domain:jvmTest :shared:jvmTest
```

适合绝大多数共同规则、Ktor MockEngine、SQLDelight JVM 集成反馈。

它快，但不能证明 Native interop 或 Android adapter。

### 2. Android 风险车道

```shell
./gradlew \
  :domain:testAndroidHostTest \
  :shared:testAndroidHostTest \
  :androidApp:testDebugUnitTest
```

Compose UI instrumentation 根据相关变更和 CI 策略运行。

### 3. Apple Debug 车道

当前 Intel host：

```shell
./gradlew :domain:iosX64Test :shared:iosX64Test
./gradlew :shared:linkDebugFrameworkIosX64
```

Apple Silicon 应换成 `iosSimulatorArm64`。

target 是平台加 architecture，不是复制脚本时可以忽略的名字。

### 4. 合并验证车道

```shell
./gradlew :domain:allTests :shared:allTests
./scripts/verify-module-boundaries.sh
./scripts/verify-apple-api.sh
```

这里追求风险覆盖，不追求每次按键后都完成。

### 5. Release 分发车道

```shell
./scripts/verify-apple-distribution.sh
```

它负责多架构 Release XCFramework、header、metadata、Swift consumer、ZIP 和 checksum。

这就是那 4 分 37 秒应该居住的地方。

一旦把反馈按风险分层，我们不需要假装 JVM tests 能证明 iOS，也不需要每次 JVM 编辑都支付完整 iOS Release 成本。

---

## 十三、CI 缓存什么，为什么也要写威胁模型？

KMP CI 常见的高价值缓存包括：

```text
Gradle wrapper / dependency cache
Gradle build cache
Kotlin/Native ~/.konan
条件合适时的 configuration cache
```

但“把整个目录压缩上传”并不是完整设计。

### Cache key 要包含真实输入

通常至少考虑：

- OS 与 CPU architecture；
- JDK/Gradle/Kotlin/AGP；
- build scripts/settings；
- dependency lock/version catalog；
- Xcode/Konan 相关版本；
- 会影响任务输出的环境模式。

key 太宽，错误地复用不兼容结果。

key 太窄，命中率接近零。

### 写权限比读权限危险

如果公开 PR 可以向团队 remote cache 写入结果，恶意或错误构建就可能污染后续可信构建。

更稳妥的起点通常是：

```text
trusted main/release CI   read + write
untrusted fork PR         read-only 或完全隔离
```

具体策略取决于 artifact 信任模型，不能只看命中率。

### Cache 不替代 tests

从 cache 恢复的产物仍需要经过目标 lane 的验证。

cache 表示“相同输入以前算过”，不是“这个版本已经满足今天的发布责任”。

---

## 十四、三个常见优化幻觉

### 幻觉 1：`clean` 更可靠

Gradle 的 task inputs/outputs、up-to-date 与 build cache 本来就是正确性模型的一部分。

如果一个普通增量 build 会产生错误结果，应该定位未声明的 input/output 或 plugin bug，而不是要求所有开发者永远 clean。

`clean` 适合：

- 测 clean build；
- 验证 release reproducibility；
- 诊断陈旧/损坏 outputs；
- 模拟 cache restore。

不适合成为每次开发的开场仪式。

### 幻觉 2：模块越多，增量越快

模块能建立 classpath/ABI 边界，也会增加：

- project configuration；
- task graph；
- dependency coordination；
- KLIB 和 link inputs；
- build scripts 与 ownership 成本。

按变化原因和稳定 API 拆模块，可能更快。

按文件夹机械拆模块，可能只是得到更多 tasks。

### 幻觉 3：一次最快值就是优化成果

性能结论至少需要：

- 明确场景；
- store/reuse 状态；
- 多样本；
- min/max 或分布；
- task outcomes；
- 改动前后同条件；
- 已知限制。

更成熟的 CI 会看 p50/p95 和相对历史趋势，而不是只保存一次冠军成绩。

---

## 十五、这次实验真正改变了什么

代码功能没有变化。

但工程获得了四样东西。

### 1. 可重复的 benchmark harness

我们不再靠回忆说“昨天好像更快”。

场景、tasks、cache state、wall time 和 task summary 会一起保存。

### 2. 可观察的 Kotlin 编译器

Native link 为什么慢、common source 为什么 fallback、分析与 codegen 各花多久，都能从 report 找证据。

### 3. 可验证的性能配置

configuration cache 与 reports 不是写完就忘记的配置，而是由 gate 持续验证。

### 4. 与风险匹配的反馈车道

JVM、Android、Apple Debug、全目标与 Release distribution 不再挤进同一条命令。

这是比“再加一个 flag”更长久的优化。

---

## 十六、如果你要在自己的项目复现

不要先复制 PocketLab 的数字。

复制方法。

### 第 1 步：写下三个真实工作流

例如：

```text
改领域规则后的最小反馈
改 Android UI 后的反馈
准备 Apple Release 的完整反馈
```

### 第 2 步：记录基线

至少记录：

```text
command/tasks
clean/no-op/change type
toolchain/host
cache state
wall time
task outcomes
```

### 第 3 步：打开报告，不急着调参数

先看：

- configuration 是否占大头；
- 哪些 tasks 最慢；
- Kotlin compile 还是 codegen/link；
- 是否反复下载依赖/Konan；
- 是否因公共 ABI 扩散；
- 是否选了过多 targets/build types。

### 第 4 步：一次只改一类变量

例如先验证 configuration cache，再验证 task selection。

同时改 heap、parallel、daemon、Native cache，最后即使快了也无法解释。

### 第 5 步：验证正确性与反面场景

cache reuse 快，不代表 cache store、clean CI 或 Release 也快。

私有实现改动快，不代表公共 API 大改也快。

Debug link 快，不代表 Release App runtime 没有代价。

### 第 6 步：把结论写成决策，而不是传说

记录：

- 为什么开启；
- 当前证据；
- 没开启什么；
- 风险和隐私；
- 何时重测。

半年后升级 Kotlin 时，这比一串没有出处的 properties 更有用。

---

## 十七、回到开头：4 分 37 秒需要被消灭吗？

不一定。

如果发布频率要求它必须在 2 分钟内完成，我们需要继续优化：

- 稳定 Apple Silicon CI；
- 保存 Konan 与 Gradle caches；
- 分析三个 Release targets 的各自成本；
- 评估 module/KLIB/link graph；
- 在正确性和最终 App 性能约束下测试 Native caches；
- 研究并行 runner 或产物复用；
- 给 p50/p95 设置预算。

但如果日常开发已经使用约一秒的 warm JVM lane、约 20 秒的单架构 forced Native Debug，而 4 分 37 秒只在发布前运行，那么问题已经从：

> KMP 太慢，怎么办？

变成了更准确的：

> 我们愿意为一次多架构 Release 交付支付多少时间，下一项证据显示应该优化哪里？

这两个问题看起来只差一点。

工程成熟度差了很远。

---

## 本篇小结

1. “构建慢”必须先绑定具体 tasks、状态、targets 和反馈目标。
2. dependency cache、up-to-date、build cache、configuration cache 与 incremental compilation 不是一回事。
3. configuration cache 优化配置阶段；第一次 store 可能更慢，收益发生在 reuse。
4. 判断 build cache 要看 `FROM-CACHE`，不能只看 wall time。
5. Kotlin build report 能把 Native compilation/linking 和 incremental fallback 从黑箱变成证据。
6. PocketLab 的单架构 Native forced build 中，Kotlin tasks 占 task time 的 91.5%；配置优化不能消除必须执行的编译。
7. 私有实现改动可被模块 ABI 边界隔离；公共 API 改动会正确传播到下游。
8. 模块越多不必然越快，稳定边界与克制 API 才能减少无效传播。
9. 日常 JVM、Android、Apple Debug、合并矩阵和 Release distribution 应是不同车道。
10. 优化只开启有证据的配置，并写清未采用方案与重测条件。

---

## 给读者的挑战

不要改任何 Gradle 参数，先在自己的工程完成下面四件事：

1. 找出一个 1 秒级 no-op workflow 和一个最慢的 Release workflow；
2. 把最慢 workflow 的前三个 tasks 与 task outcomes 记录下来；
3. 分别做一次私有实现改动和公共 API 改动，画出重新执行的 module graph；
4. 删除一个“大家都说会快、但项目没有证据”的构建参数。

如果做完后你发现真正的问题不是编译器，而是每次都运行了错误的 tasks——恭喜，你已经得到了一次非常成功的构建优化。

下一篇，我们会进入另一个更像侦探工作的主题：

> 当问题跨过 Kotlin、Swift、Android 和 Native runtime，崩溃、日志与性能应该怎样串成一条可定位的证据链？

---

## 官方参考

- [Gradle Build Performance](https://docs.gradle.org/current/userguide/performance.html)
- [Gradle Configuration Cache](https://docs.gradle.org/current/userguide/configuration_cache.html)
- [Enabling the Configuration Cache](https://docs.gradle.org/current/userguide/configuration_cache_enabling.html)
- [Inspecting Gradle Builds](https://docs.gradle.org/current/userguide/inspect.html)
- [Kotlin compilation and caches](https://kotlinlang.org/docs/gradle-compilation-and-caches.html)
- [Improving Kotlin/Native compilation time](https://kotlinlang.org/docs/native-improving-compilation-time.html)
- [Kotlin Gradle best practices](https://kotlinlang.org/docs/gradle-best-practices.html)
- [Optimize your Android build](https://developer.android.com/build/optimize-your-build)

> 验证环境：Kotlin 2.4.21、Gradle 8.14.4、AGP 8.13.2、JDK 17、Xcode 26.5、macOS x86_64；2026-11-20。macOS x64 Kotlin/Native host 已弃用，本文耗时只代表本机实验，不应用作 Apple Silicon 或 CI 的绝对基线。
