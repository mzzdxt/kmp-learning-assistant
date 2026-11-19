# 实验 11：拆开 Kotlin/Native 的编译、共享堆与 ARC 边界

## 实验目标

完成本实验后，你应该能用真实产物和测试回答：

1. KLIB、static framework 与最终 iOS App 分别是什么阶段？
2. “没有 JVM”为什么不等于“没有 Kotlin runtime”？
3. 现代 Kotlin/Native 为什么不再需要 `freeze()`？
4. 共享 heap 与线程安全有什么区别？
5. 纯 Kotlin 引用环与 Swift/Kotlin 混合引用环为什么结果不同？
6. GC、Job cancellation、resource close 与 UI lifecycle 为什么必须分开？
7. 如何用日志、signpost、`nm` 和最终 archive 建立性能/体积证据？

## 前置条件

- 已完成[实验 10](../10-testing-pyramid/README.md)。
- JDK 17、Xcode 与 Kotlin/Native toolchain 可用。
- 当前实验机为 Intel Mac，因此 target 是 `iosX64`；Apple Silicon 应改为 `iosSimulatorArm64`。
- Kotlin 2.4.21 已对 `macos_x64` host 给出弃用警告；这是一条迁移信号，不要把本实验 target 当作新 CI 基线。
- 主工程位于 `../02-first-kmp-module`。

## 实验地图

```text
Kotlin sources
    │ compileKotlinIosX64
    ▼
KLIB: serialized IR + link metadata + manifest
    │ linkDebug/ReleaseFrameworkIosX64
    ▼
static framework: archive + ObjC header + module map
    │ Xcode final link (+ system libraries)
    ▼
iOS App Mach-O

运行时：Kotlin shared heap + tracing GC ↔ Swift/ObjC ARC
并发：可跨线程访问 + 显式 Mutex/atomic/owner
```

## 第一步：生成并拆开 KLIB

执行：

```shell
cd ../02-first-kmp-module
./gradlew :shared:compileKotlinIosX64
```

查看产物：

```shell
find shared/build/classes/kotlin/iosX64/main/klib/shared \
  -maxdepth 4 -type f

du -sh shared/build/classes/kotlin/iosX64/main/klib/shared

sed -n '1,80p' \
  shared/build/classes/kotlin/iosX64/main/klib/shared/default/manifest
```

本次基线：

```text
KLIB directory: 332 KB
compiler_version=2.4.21
builtins_platform=NATIVE
native_targets=ios_x64
```

观察 `ir/`、`linkdata/` 和 manifest。回答：这里是否已经存在可由 iOS Simulator 直接启动的 Mach-O？答案是否定的；KLIB 仍是 Kotlin/Native library representation。

## 第二步：对比 Debug 与 Release framework

执行：

```shell
./gradlew \
  :shared:linkDebugFrameworkIosX64 \
  :shared:linkReleaseFrameworkIosX64
```

检查内部二进制：

```shell
file shared/build/bin/iosX64/debugFramework/\
PocketLabShared.framework/PocketLabShared

lipo -info shared/build/bin/iosX64/debugFramework/\
PocketLabShared.framework/PocketLabShared

ls -lh \
  shared/build/bin/iosX64/{debug,release}Framework/\
PocketLabShared.framework/PocketLabShared
```

若 shell 不展开中间 brace，请分别执行两条 `ls`。

本次结果：

| 产物 | 类型 | 架构 | 大小 |
|---|---|---|---:|
| Debug | static `ar` archive | x86_64 | 约 67 MB |
| Release | static `ar` archive | x86_64 | 约 12 MB |

不要把这些数字直接写成“App 增量体积”。接下来还有 Xcode final link、dead stripping、符号和 App archive packaging。

## 第三步：检查 Swift 实际进口的 API

查看 header：

```shell
HEADER=shared/build/bin/iosX64/releaseFramework/\
PocketLabShared.framework/Headers/PocketLabShared.h

wc -l "$HEADER"
rg -n 'PLSExperimentRecord|PLSIosPocketLabFeature|subscribeOnChange' \
  "$HEADER"
```

本次 header 有 2,834 行。

寻找：

- Kotlin class → Objective-C `@interface`；
- Kotlin interface → `@protocol`；
- callback → Objective-C block；
- suspend → completion handler；
- framework prefix `PLS`；
- Kotlin package 名是否保留。

这一步用于建立“能导出”和“Swift 好用”之间的距离。下一实验会系统整理 API surface。

## 第四步：证明 static framework 仍有最终链接责任

执行：

```shell
nm -g \
  shared/build/bin/iosX64/releaseFramework/\
PocketLabShared.framework/PocketLabShared \
  | rg 'sqlite3_open'
```

预期看到：

```text
U _sqlite3_open_v2
```

`U` 表示 archive 引用了但没有定义该符号。PocketLab iOS App 的 linker flags 因此包含：

```text
-framework PocketLabShared -lsqlite3
```

挑战：临时移除 `-lsqlite3` 后构建 App，记录 final link 的 undefined symbols；随后恢复。

## 第五步：运行共享 heap 并发实验

测试文件：

```text
shared/src/commonTest/kotlin/
  dev/kmpjourney/pocketlab/concurrency/SharedHeapConcurrencyTest.kt
```

它创建两个明确的 single-thread dispatcher，让 200 个 coroutine 交替访问同一个 `ConcurrentLedger`：

```kotlin
val firstDispatcher = newSingleThreadContext("ledger-first")
val secondDispatcher = newSingleThreadContext("ledger-second")
```

状态由 `Mutex` 保护：

```kotlin
suspend fun record(id: String): Int = mutex.withLock {
    val next = counts.getOrElse(id) { 0 } + 1
    counts[id] = next
    next
}
```

运行：

```shell
./gradlew \
  :shared:jvmTest \
  :shared:testAndroidHostTest \
  :shared:iosX64Test
```

预期这个 commonTest 在三个 targets 都通过。

实验结论不是“Map 跨线程绝对安全”，而是：

```text
现代 shared heap → 同一对象可以跨线程访问
Mutex              → 复合读改写不会并发进入
```

生产代码不要为每个对象创建 `newSingleThreadContext`；本测试只用它把两个执行边界显性化。

## 第六步：反证“可共享 = 线程安全”

在临时分支或做完立即恢复的前提下：

1. 删除 `ConcurrentLedger` 的 Mutex；
2. 把操作数量从 200 增加到 20,000；
3. 重复运行 JVM 与 iOS tests；
4. 记录丢更新、集合异常或偶尔仍通过的次数；
5. 恢复 Mutex。

即使十次都碰巧通过，也没有获得 happens-before 或互斥证明。并发测试能暴露竞态，不能用“没复现”证明竞态不存在。

## 第七步：运行 Native GC 引用环实验

测试文件：

```text
shared/src/iosX64Test/kotlin/
  dev/kmpjourney/pocketlab/memory/NativeGarbageCollectorTest.kt
```

测试创建两个相互引用的 Kotlin 对象，只返回第一个对象的 `WeakReference`。helper 返回后，强引用环与 roots 断开：

```kotlin
val weakFirstNode = createUnreachableCycle()
GC.collect()
assertNull(weakFirstNode.value)
```

运行：

```shell
./gradlew :shared:iosX64Test
```

报告应包含：

```text
NativeGarbageCollectorTest: 1 test, 0 failures
```

`GC.collect()` 和 Native runtime API 在这里用于诊断测试。不要复制到页面生命周期或普通业务流程。

## 第八步：给 cycle 增加一个 root

创建一个测试专用全局列表：

```kotlin
private val roots = mutableListOf<Any>()
```

把 `first` 放进去后再次 collect，弱引用应仍有值；执行 `roots.clear()` 后在独立函数边界重新 collect，才应被回收。

这个变体让“tracing from roots”从概念变成可观测事实。完成后删除测试全局状态，保持测试互相隔离。

## 第九步：画出 GC 与 ARC 的不同失效方式

纯 Kotlin 环：

```text
Kotlin A ↔ Kotlin B
没有 root → GC 可回收
```

纯 Swift 环：

```text
Swift A ↔ Swift B
双方 retain count > 0 → ARC 不回收
```

混合环：

```text
Swift ViewModel → Kotlin Bridge → Swift callback / ViewModel
```

列出 PocketLab 现有的断环点：

- `[weak self]`（需要捕获 Swift owner 时）；
- `NativeCancellable.cancel()`；
- `AsyncStream.onTermination`；
- `IosPocketLabFeature.close()`；
- ViewModel `stopManualWork()`。

若一个 callback 可以长期存活，却没有弱捕获、取消或 close，它就是审计目标。

## 第十步：用工具看 GC，而不是猜 GC

调试日志可通过 Native compiler 参数启用：

```text
-Xruntime-logs=gc=info
```

Apple Instruments signpost 可在实验分支的 `gradle.properties` 临时启用：

```properties
kotlin.native.binary.enableSafepointSignposts=true
```

然后：

1. 在 Xcode 选择 Product → Profile；
2. 选择 `os_signpost` 或含 signpost 的模板；
3. subsystem 设为 `org.kotlinlang.native.runtime`；
4. category 设为 `safepoint`；
5. 运行 refresh 与列表滚动；
6. 把 GC pause 与主线程、数据库和 SwiftUI 时间线对齐。

完成诊断后恢复 property。不要在没有问题证据时永久修改 collector 或关闭 GC。

## 第十一步：核对完整测试数字

本次新增：

- 1 个 common concurrency 场景，在三个 targets 执行 3 次；
- 1 个 iOS Native GC 场景，执行 1 次。

当前基线：

| 来源 | 不同场景 | 执行 |
|---|---:|---:|
| commonTest | 38 | 114 |
| JVM / Android Host / iOS 平台 KMP | 9 | 9 |
| Android ViewModel | 2 | 2 |
| Android Compose UI | 2 | 2 |
| iOS UI | 1 | 1 |
| **总计** | **52** | **128/128** |

目标分布：JVM 39、Android Host 39、iOS x64 45、Android 壳 4、iOS UI 1。

## 本次真实观察

### Release Native link 明显比 Debug 增量构建慢

同一命令中 Debug 已 up-to-date，Release 完整链接使总任务耗时约 1 分 51 秒。不要把这个数字当通用 benchmark，但它与官方“Release LTO 更昂贵”的解释一致。日常开发优先 Debug，发布门禁再跑 Release。

### framework 体积不是 App 体积

Debug archive 67 MB、Release 12 MB 的差异很大，但二者仍是 linker 输入。必须用最终 App archive 做同条件对照，不能拿 framework 文件大小对产品承诺。

### 官方文档中的 GC 细节会继续演进

不要把某个 collector 名称或默认参数写成永久架构事实。更稳定的知识是 shared heap、tracing reachability、显式同步，以及如何用 runtime logs 和 Instruments 观察当前版本。

## 挑战题

1. 用 `GC.lastGCInfo()` 记录一次 collection 前后 heap 指标，并标注 API 稳定性。
2. 在 interop 循环中制造大量临时 `NSString`，比较有无 `autoreleasepool` 的 Instruments 曲线。
3. 给 Swift 写一个强引用 callback cycle，用 `weak` 与显式 cancel 分别修复。
4. 构建 `iosSimulatorArm64` 版本，比较两个 framework 的 `lipo -info`。
5. 对最终 `.app` 而不是 framework 做 Release 大小前后对照。
6. 把 `Mutex` 状态改造成单 owner + Channel，比较 API、吞吐和关闭协议。

## 验收标准

只有当你能解释下面这段话，才算完成：

> Kotlin/Native 通过 KLIB 与 LLVM 生成不依赖 JVM 的原生二进制，但最终程序仍携带 Kotlin runtime 和 tracing GC。现代对象位于共享 heap，不再 freeze；跨线程正确性仍由 Mutex、atomic 或状态所有者保证。Swift ARC 与 Kotlin GC 在互操作边界协作，却无法自动打破混合强引用环，任务与资源也必须显式 cancel/close。

对应决策记录：[ADR-0009](../../docs/adr/0009-native-memory-concurrency.md)

对应文章：[Kotlin/Native 深水区：没有 JVM 之后，内存和并发到底谁管？](../../articles/11-kotlin-native-runtime.md)
