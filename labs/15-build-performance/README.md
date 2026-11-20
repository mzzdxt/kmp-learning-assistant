# 实验 15：把“构建很慢”拆成可以验证的假设

## 实验目标

完成本实验后，你应该能用数据回答：

1. Gradle configuration cache、build cache 与 up-to-date 有什么区别？
2. Kotlin incremental compilation 又缓存了什么？
3. 为什么一次 `BUILD SUCCESSFUL in 2s` 仍可能不是“编译只需 2 秒”？
4. 为什么 configuration-cache store 可能比关闭缓存更慢？
5. 如何证明 build cache 真正命中，而不是只看到 wall time 下降？
6. 私有实现改动和公共 ABI 改动怎样沿 KMP module graph 传播？
7. Kotlin/Native 慢在 Gradle 配置、Kotlin 编译还是 framework 链接？
8. 为什么日常开发不应反复组装完整 Release XCFramework？
9. 哪些优化应该现在开启，哪些必须等待证据？

主工程仍位于：

```text
../02-first-kmp-module
```

本实验的固定结论记录在：

```text
results/2026-11-20-intel-mac.md
```

## 前置条件

- 已完成[实验 14](../14-kmp-modularization/README.md)，理解 `domain → shared → platform app` 依赖图；
- 本机有 JDK 17、Ruby 与 `rg`；
- Apple 实验需要 Xcode；
- 知道当前 host 能运行哪种 iOS Simulator target；
- 先关闭会持续占用 CPU/磁盘的任务，记录机器与工具链版本。

## 第一条规则：先说“哪个工作流慢”

下面五句话看似都叫构建慢，处理方式完全不同：

```text
./gradlew help 也慢
改一行业务规则后 JVM tests 慢
第一次 checkout 后依赖下载慢
Kotlin/Native Debug framework 冷链接慢
三个架构的 Release XCFramework 发布慢
```

不要从“Gradle 慢”直接跳到：

```properties
org.gradle.jvmargs=-Xmx8g
org.gradle.parallel=true
kotlin.incremental.native=true
```

先定义：

- command/tasks；
- clean、no-op 还是改动后；
- daemon 和依赖是否预热；
- build type 与 targets；
- cache 开关和 cache state；
- wall time 与 task outcomes；
- 重复次数和机器环境。

否则两次数字没有可比性。

## 第二条规则：把六种复用机制分开

### Gradle dependency cache

保存下载过的 plugins、modules 和 metadata，主要减少网络与 artifact resolution 成本。它回答：

> 这个外部依赖是否已经在本机？

它不代表本项目源码已经编译。

### Up-to-date check

当 task 的 inputs 与 outputs 没变，而且 outputs 仍在原位置时，Gradle 可以跳过 task：

```text
> Task :domain:compileKotlinJvm UP-TO-DATE
```

它通常是同一 working copy 里最便宜的复用。执行 `clean` 会删除 outputs，也就主动丢掉这条捷径。

### Gradle build cache

build cache 按 task inputs 找到以前产生的 outputs，即使当前 `build/` 已被删除，也可能恢复：

```text
> Task :domain:compileKotlinJvm FROM-CACHE
```

它回答：

> 相同输入的这个 task，是否有人已经算过结果？

本地 outputs 还在时，Gradle 通常直接使用 up-to-date，不必从 cache 拷回。

### Gradle configuration cache

Gradle 普通运行先解析 settings/build scripts、plugins、projects、tasks 和依赖关系，再执行 tasks。configuration cache 保存的是完成配置后可复用的 task graph/state：

```text
Configuration cache entry stored.
Reusing configuration cache.
```

它跳过后续相同 invocation 的大部分配置，不保存 Kotlin class 文件。tasks、Gradle arguments 或 configuration inputs 改变，可能得到不同 cache key。

### Kotlin incremental compilation

这是一个 Kotlin compile task 内部的复用。编译器根据 source/classpath changes，尽量减少重新分析和生成的范围。

它与 build cache 的区别：

- incremental compilation：这次 task 必须运行，但内部少做一些；
- build cache：整个 task output 可恢复，编译器甚至不必启动；
- up-to-date：本地 output 已有效，task 直接跳过。

### Kotlin/Native toolchain 与 binary caches

`~/.konan` 包含 Native compiler/toolchain 和下载依赖；CI 每次清空会重复付出很大成本。Native binary caches 还能缓存部分 klib 编译结果，但某些 Release 配置会改变优化取舍，不能只看构建时间就开启。

可以把它们想成餐厅：

```text
dependency cache       食材已经采购
configuration cache    桌位和出餐流程已经排好
up-to-date             菜还在桌上，直接端走
build cache            同配方成品在保温柜，取出即可
incremental compiler   仍要做菜，但只重做受影响步骤
Native caches          原生工具与部分半成品留在专用仓库
```

这不是严格实现模型，但能避免把所有“缓存”当成同一个开关。

## 第一步：验证性能配置契约

项目现在开启：

```properties
org.gradle.caching=true
org.gradle.configuration-cache=true
kotlin.build.report.output=file,json
```

运行：

```shell
cd ../02-first-kmp-module
./scripts/verify-build-performance.sh
```

门禁会验证：

- build cache 属性存在；
- configuration cache 属性存在；
- benchmark script 通过 shell syntax check；
- 强制 Kotlin/JVM compilation 能产生 text 与 JSON report；
- 日常开发 task 连续执行后真的显示 configuration cache reuse；
- configuration-cache problems 使用 `fail`。

它故意不检查“必须小于 1,100 ms”。wall time 会随 host、后台负载、文件系统和 daemon 波动。正确性能力适合硬门禁；短期本机耗时更适合趋势记录。

### 一个真实的报告路径陷阱

Kotlin 文档将 `kotlin.build.report.file.output_dir` 描述为 file/JSON 输出目录。当前 Kotlin 2.4.21 实验中，text report 会进入指定临时目录，JSON report 仍写入默认：

```text
build/reports/kotlin-build
```

门禁因此比较默认目录中 JSON 的前后最新文件，并检查 report label。不要为了让脚本“看起来符合文档”而假装实际输出不存在。

## 第二步：测 configuration cache

### 纯配置近似

```shell
./scripts/benchmark-build.sh config-only 5
```

脚本执行：

1. 预热 daemon；
2. 关闭 configuration cache，测 5 次；
3. 单独测第一次 store；
4. 测 5 次 reuse。

结果写入被忽略的：

```text
build/benchmarks/config-only-<UTC>-<pid>/
├── results.tsv
├── summary.md
└── logs/
```

为什么不直接连续跑两次并比较？

- 第一次同时包含 daemon/JIT/file cache warming；
- 第一次 cache-enabled 是 store，不是 reuse；
- 单样本无法看出正常波动；
- Gradle 自报秒数会四舍五入，外部 monotonic 毫秒更适合小项目。

### 日常任务

```shell
./scripts/benchmark-build.sh dev-noop 5
```

固定 tasks：

```text
:domain:jvmTest
:shared:jvmTest
:androidApp:testDebugUnitTest
```

PocketLab 的实测平均值：

```text
disabled  1663 ms
store     4532 ms
reuse     1024 ms
```

正确解释：

- 重复 invocation 的 reuse 在当前机器/项目快约 38.4%；
- 第一次 store 反而更慢；
- 它不承诺所有 build 都快 38.4%；
- report label、tasks 或参数变化会让 configuration cache 重建。

## 第三步：用任务结果证明 build cache

运行：

```shell
./scripts/benchmark-build.sh dev-clean-cache 1
```

场景依次执行：

```text
clean → no-build-cache compile
clean → cache population/restore
clean → warm build-cache restore
no clean → up-to-date no-op
```

重点查看 `task_summary` 与日志，而不是只看时间：

```text
49 executed
21 executed, 28 from cache
21 executed, 28 from cache
2 executed, 47 up-to-date
```

为什么 restore 仍有 21 tasks executed？

- 不是所有 tasks 都 cacheable；
- lifecycle/check tasks 可能没有可缓存 outputs；
- 某些 outputs 或 inputs 需要本地重新产生/检查；
- “build cache on”不等于“所有 task FROM-CACHE”。

### 故意做一次错误推断

如果某次 clean build 是 23 秒，下一次是 12 秒，但日志只有 1 个 `FROM-CACHE`，可以说 build cache 让它快 48% 吗？

不能。还可能混入：

- daemon/JIT warming；
- configuration cache/parallel scheduling；
- OS file cache；
- 后台负载；
- 不同 task inputs。

性能分析的专业性不在于给每个数字编一个原因，而在于敢于把证据不足的数字标为无效。

## 第四步：读 Kotlin build report

手动强制一次 JVM compile：

```shell
./gradlew :domain:compileKotlinJvm \
  --rerun-tasks \
  -Pkotlin.build.report.label=manual-jvm
```

报告默认位于：

```text
build/reports/kotlin-build
```

观察：

- Gradle configuration time；
- task time；
- Kotlin compiler run time；
- code analysis/code generation；
- analyzed lines；
- incremental rebuild reason；
- GC 与 build attributes。

`--rerun-tasks` 会主动让任务执行，适合检查 report pipeline，不适合代表日常增量编译。

不要直接把原始 JSON 上传公众号附件。它可能暴露：

- 用户名和绝对路径；
- compiler arguments；
- dependency locations；
- CI workspace 和环境细节。

公开时使用去标识化的汇总表。

## 第五步：观察改动怎样传播

这个实验必须在临时分支或明确可还原的工作区完成。

### A. 改私有实现，不改 ABI

在 `domain` 的私有 validation helper 中做语义等价改写，然后运行：

```shell
./gradlew \
  :domain:jvmTest \
  :shared:jvmTest \
  :androidApp:testDebugUnitTest \
  --no-build-cache
```

本项目观察到：

```text
domain compile     executed
shared compile     up-to-date
android compile    up-to-date
affected tests     executed
```

模块/classpath snapshot 阻止了无关下游 compilation。

但是 Kotlin report 同时写出：

```text
Incremental compilation might be incorrect (KT-62686)
Non-incremental compilation will be performed
```

因此不要把结果表述成“只重新编译那一个 Kotlin 文件”。我们只证明了 task/module 传播范围，没有证明 compiler task 内部的文件级范围。

### B. 改公共 ABI

临时给 `DraftValidator` 增加 public method，再用 `--no-build-cache` 运行同一组 tasks。

本项目观察到：

```text
domain compile                 executed
shared compile                 executed
androidApp compile             executed
49 actionable: 17 executed
```

原因是消费者 compile classpath 的 ABI 发生变化。模块边界不是魔法防火墙；真正的公共契约变化本来就应该传播。

实验完成后还原源码，并用：

```shell
git diff -- domain/src/commonMain
```

确认没有临时改动残留。

## 第六步：拆开 Kotlin/Native 冷成本

先测 outputs 已存在时的重复 invocation：

```shell
./scripts/benchmark-build.sh native-noop 3
```

然后强制一个 host-compatible Debug framework：

```shell
./gradlew :shared:linkDebugFrameworkIosX64 \
  --rerun-tasks \
  -Pkotlin.build.report.label=native-debug-rerun
```

本机实测：

```text
wall time                              19.80 s
Kotlin tasks / all task time           18.323 / 19.758 s
domain compileKotlinIosX64              5.355 s
shared compileKotlinIosX64              2.749 s
shared linkDebugFrameworkIosX64         9.955 s
```

这次构建中 Kotlin tasks 占 task time 的 91.5%。即使 configuration cache 把配置阶段优化到近乎看不见，必须执行的 Native compilation/linking 仍在。

进一步看 link report：

```text
LinkKLibs      3.267 s
analysis       5.385 s
IR linking     3.314 s
backend        3.337 s
codegen        1.513 s
native linker  0.534 s
```

于是优化顺序变得清楚：

1. 开发时只选需要的 Debug target；
2. 保持 module/API 边界，减少受影响输入；
3. 保存 Gradle/Konan caches；
4. 再评估 compiler/Native experimental flags；
5. Release 多架构发布留在 release lane。

## 第七步：设计反馈车道

### 快速 JVM 车道

```shell
./gradlew :domain:jvmTest :shared:jvmTest
```

适合领域规则、网络/数据库共同逻辑的日常反馈。它不能证明 Android/iOS 特定 adapter 正确。

### Android 车道

```shell
./gradlew \
  :domain:testAndroidHostTest \
  :shared:testAndroidHostTest \
  :androidApp:testDebugUnitTest
```

Compose UI instrumentation 需要 emulator/device，按相关变更和 CI 分层执行。

### Apple Debug 车道

当前 Intel host：

```shell
./gradlew :domain:iosX64Test :shared:iosX64Test
./gradlew :shared:linkDebugFrameworkIosX64
```

Apple Silicon 应使用 `iosSimulatorArm64` 对应任务。不要只因为脚本里写着 x64 就复制到所有机器。

### 全目标合并车道

```shell
./gradlew :domain:allTests :shared:allTests
./scripts/verify-module-boundaries.sh
./scripts/verify-apple-api.sh
```

### Release 分发车道

```shell
./scripts/verify-apple-distribution.sh
```

上一轮三个 Release architecture 的 gate 约 4 分 37 秒。它是一项真实发布成本，但不是每次保存 Kotlin 文件后都应支付的成本。

## 第八步：把 CI cache 当成供应链能力

建议保留：

```text
Gradle wrapper/dependencies
Gradle local build cache
Kotlin/Native ~/.konan
必要时可复用的 configuration cache
```

但必须设计：

- key 是否包含 OS/architecture 与工具链版本；
- build scripts/lockfiles 变化怎样失效；
- fork PR 是否只有只读权限；
- 谁可以写 remote cache；
- cache 污染如何清除；
- 是否把 secrets、路径或报告一起上传；
- 保存成本是否小于重复计算成本。

cache 命中后仍需执行 tests。cache 加速计算，不改变验收责任。

## 哪些优化这次没有做

### 没有增加 heap

当前没有 OOM、频繁 full GC 或 build report 内存证据。`-Xmx` 不是“越大越快”。

### 没有打开 Native incremental Beta

`kotlin.incremental.native=true` 仍需项目级 correctness/收益 A/B。不能只因为名字里有 incremental 就默认安全有益。

### 没有打开 Release binary cache flags

编译更快若以 Release optimization、启动或运行性能为代价，必须同时用最终 App 测量。当前没有这组证据。

### 没有建立 remote build cache

单人小项目尚未证明跨机器重复计算足以覆盖服务、权限和污染治理成本。

### 没有把所有检查塞进一个 task

一键全量门禁方便发布，但不应该成为唯一开发入口。反馈目标不同，task graph 就应该不同。

## 验收清单

- [ ] 能口述 configuration cache 与 build cache 保存的对象不同；
- [ ] 能从 `UP-TO-DATE`、`FROM-CACHE`、`executed` 判断复用路径；
- [ ] 能解释为什么 cache store 与 clean build 不能直接当 reuse；
- [ ] 完成 config-only、dev-noop、dev-clean-cache 三组实验；
- [ ] 读取一次 Kotlin text/JSON build report；
- [ ] 找出一次 Kotlin incremental rebuild reason；
- [ ] 比较私有实现与公共 ABI 改动的 task 传播；
- [ ] 拆出一次 Native compile/link phase；
- [ ] 为本机定义 fast、platform、merge、release 四条车道；
- [ ] `verify-build-performance.sh` 通过；
- [ ] 所有临时源代码改动已还原；
- [ ] 在学习日志记录一个被日志推翻的性能猜测。

## 思考题

1. 如果 configuration cache reuse 快 300 ms，但每次开发都改变 Gradle property，应该优化哪里？
2. remote cache 命中率 80%，却让 clean build 更慢，应该检查网络、artifact 大小还是 task 粒度？
3. 私有改动没有触发下游 compile，却触发下游 tests，这是浪费吗？哪些 runtime 行为可能变化？
4. Native link 是最大 task 时，拆更多 modules 一定会更快吗？KLIB 数量和链接输入会带来什么成本？
5. CI 应用绝对秒数还是相对历史百分位做性能门禁？怎样避免 runner 噪声？
6. 如果一个实验性 cache flag让 build 快 20%，但 Release App 启动慢 5%，你会如何决策？

## 官方资料

- [Gradle Build Performance](https://docs.gradle.org/current/userguide/performance.html)
- [Gradle Configuration Cache](https://docs.gradle.org/current/userguide/configuration_cache.html)
- [Enabling the Configuration Cache](https://docs.gradle.org/current/userguide/configuration_cache_enabling.html)
- [Inspecting Builds](https://docs.gradle.org/current/userguide/inspect.html)
- [Kotlin compilation and caches](https://kotlinlang.org/docs/gradle-compilation-and-caches.html)
- [Kotlin/Native compilation time](https://kotlinlang.org/docs/native-improving-compilation-time.html)
- [Kotlin Gradle best practices](https://kotlinlang.org/docs/gradle-best-practices.html)
- [Optimize your Android build](https://developer.android.com/build/optimize-your-build)
