# Kotlin/Native 深水区：没有 JVM 之后，内存和并发到底谁管？

> 面向读者：熟悉 JVM/Android，已经把第一个 KMP framework 接入 SwiftUI，准备理解 Native 运行时的 Kotlin 开发者
>
> 技术核对与实验验证日期：2026-11-19
>
> 实验版本：Kotlin 2.4.21、kotlinx-coroutines 1.11.0、Xcode 26.5、Swift 6.3.2、iOS x64 Simulator

第一次看到 Kotlin/Native 这个名字时，我脑中出现的是一条非常干净的公式：

```text
Kotlin → LLVM → 机器码
```

没有 JVM，没有字节码，没有虚拟机。听起来剩下的世界应该像 C：对象由编译器“直接变成机器码”，内存最好也由 ARC 顺便接管，并发问题或许会随着旧时代的 `freeze()` 一起消失。

这组想象一半正确，一半非常危险。

Kotlin/Native 的确把 Kotlin 编译为无需 JVM 的原生二进制；但 framework 里不只有你的业务指令，还包含 Kotlin 标准库、对象模型、异常、协程所需能力、垃圾回收器和 Objective-C 互操作胶水。Swift ARC 与 Kotlin GC 会在边界合作，却不会合并成同一种所有权模型。现代共享堆也允许对象跨线程，但“允许访问”从来不等于“自动线程安全”。

这一篇，我们不靠记忆旧博客。PocketLab 会做四件可复现实验：

1. 拆开真实 KLIB、Debug/Release framework 与 Objective-C header；
2. 让一个可变对象跨两个显式单线程 dispatcher，并用 `Mutex` 保证 200 次更新不丢失；
3. 在 iOS Native 测试中制造纯 Kotlin 引用环，用弱引用证明 GC 可以回收它；
4. 把 Kotlin GC 与 Swift ARC 的交界、混合引用环和析构线程写成工程约束。

## 先说结论：没有 JVM，不等于没有运行时

Android 上的 Kotlin 通常经过：

```text
Kotlin source
→ Kotlin/JVM bytecode
→ DEX
→ ART
→ Android process
```

iOS 上的 Kotlin/Native 经过：

```text
commonMain + iosX64Main
→ Kotlin IR / KLIB
→ Kotlin/Native backend + LLVM
→ Kotlin runtime + dependencies + interop glue
→ static PocketLabShared.framework
→ Xcode final link
→ iOS process
```

两条链的差异不是“有运行时”和“没有运行时”，而是：

- Android 依赖设备上的 ART 执行托管字节码；
- Kotlin/Native 把面向目标架构的原生代码与所需 Native runtime 一起放进最终程序；
- iPhone 不需要安装 JVM，但 Kotlin 对象仍然需要分配、类型检查、异常处理和垃圾回收。

所以“原生编译”不等于：

- 所有 Kotlin 对象都变成栈变量；
- Swift ARC 独自管理 Kotlin heap；
- 没有 GC pause；
- 共享可变对象天然无竞态；
- Debug framework 的磁盘大小就是 App 增量体积。

## 第一站：KLIB 不是 iPhone 能直接执行的文件

PocketLab 执行 `compileKotlinIosX64` 后，得到：

```text
shared/build/classes/kotlin/iosX64/main/klib/shared/
```

本次实际大小约 332 KB。目录中可以看到：

```text
default/
├── ir/
│   ├── bodies.knb
│   ├── irDeclarations.knd
│   ├── types.knt
│   └── ...
├── linkdata/
│   ├── module
│   └── package_*/
└── manifest
```

manifest 给出了比文件名更可靠的事实：

```text
compiler_version=2.4.21
builtins_platform=NATIVE
native_targets=ios_x64
unique_name=first-kmp-module:shared
```

它还列出 Ktor Darwin、SQLDelight Native driver、coroutines、serialization 与 Kotlin platform libraries 等依赖。

KLIB 是 Kotlin/Native 的库交换格式，保存序列化 IR、链接 metadata、依赖和目标信息；它服务于后续 Native 编译与链接，不是 `.class`，也不是可以直接安装到模拟器的 Mach-O 可执行文件。

可以把它理解为：

> 编译器已经理解了这份 Kotlin，但 Xcode 还没有得到最终可链接的 Apple 产物。

## 第二站：framework 是目录，里面这次是一座静态库

执行：

```shell
./gradlew \
  :shared:linkDebugFrameworkIosX64 \
  :shared:linkReleaseFrameworkIosX64
```

得到两套 framework。对内部二进制执行 `file` 和 `lipo -info`，真实结果是：

```text
Debug:   current ar archive, x86_64, 67 MB
Release: current ar archive random library, x86_64, 12 MB
```

这说明三件事。

### 1. `.framework` 不保证里面是动态库

PocketLab 明确设置了：

```kotlin
binaries.framework {
    baseName = "PocketLabShared"
    isStatic = true
}
```

所以真正的 `PocketLabShared` 文件是 `ar` 静态 archive。Xcode 在构建 App 时从中选择对象文件并做最终链接，而不是在运行时加载一份独立 Kotlin 动态库。

### 2. target 包含体系结构

`lipo` 报告它只有 `x86_64` slice。这能运行在当前 Intel iOS Simulator，不能拿去给 arm64 真机或 Apple Silicon Simulator 使用。

“iOS framework”不是一个完整 target 描述；CPU 架构仍然是 ABI 的一部分。面向多架构分发时，需要对应 targets 并组装 XCFramework，而不是给文件改个名字。

本机 Kotlin 2.4.21 构建还明确警告：`macos_x64` Native host 已弃用，将在未来版本停止分发 compiler。本文保留 `iosX64` 是为了让现有 Intel Mac 实验可复现，不代表新项目应继续投资 Intel CI；工程迁移方向是 Apple Silicon host、`iosSimulatorArm64` 与真机 `iosArm64`。

### 3. 67 MB 不是 App 增加了 67 MB

Debug archive 含调试信息、未优化代码和许多可供 linker 选择的对象；Release 会进行更重的优化与链接时处理。本机 Release archive 只有约 12 MB，却花了明显更长时间生成。

即使是 12 MB，也不能直接当作最终 IPA 增量：静态链接、dead stripping、符号与 App 打包还会继续改变结果。只有对同一份最终 App archive 做前后对照，才能回答产品体积问题。

Kotlin 官方也提醒：Release Native binary 默认会做昂贵的链接时优化，`linkRelease*` 不适合作为日常反馈任务。Debug 快不是因为它“更接近 Native”，而是因为它少做了很多发布优化。

## 第三站：Objective-C header 是 Swift 看见的门面

framework 里还有：

```text
Headers/PocketLabShared.h
Modules/module.modulemap
```

本次生成的 header 有 2,834 行。`ExperimentRecord` 变成 Objective-C interface，`ExperimentLocalStore` 变成 protocol，`subscribe` 的 Kotlin callback 变成 block，`suspend` 方法出现 completion handler。

```objc
@interface PLSExperimentRecord : PLSBase
...
- (PLSNativeCancellable *)subscribeOnChange:
    (void (^)(NSArray<PLSExperimentRecord *> *))onChange
    onFailure:(void (^)(NSString *))onFailure;
```

Swift 并不是直接读取 Kotlin IR。当前稳定 framework 路径是：

```text
Kotlin declaration
→ Objective-C compatible export
→ generated header / module
→ Swift importer
→ Swift-facing API
```

这也解释了为什么包名会消失、泛型会降级、`Flow<T>` 不会自动成为漂亮的 `AsyncSequence<T>`。编译为机器码和设计出好用的 Swift API，是两个不同问题；下一篇会专门处理后者。

## 静态 framework 成功，为什么 App 仍可能链接失败？

用 `nm` 检查 PocketLab 的 archive，可以看到：

```text
U _sqlite3_open_v2
```

`U` 表示该符号仍未在 archive 内定义。SQLDelight Native driver 最终调用系统 SQLite，真正的 App linker 仍要提供 `libsqlite3`。

因此前一里程碑的 Xcode target 必须包含：

```text
-framework PocketLabShared -lsqlite3
```

这条证据非常适合拆掉另一个误解：

> Native framework 生成成功，只证明 Kotlin/Native linker 产出了库；不证明最终 App 的所有系统符号已经闭合。

## Kotlin 对象生活在哪里：现代共享堆

现代 Kotlin/Native memory manager 把对象放在共享 heap 中，任意线程都可以访问。不可达对象由 tracing GC 周期性回收。

这里最重要的词是“不可达”，而不是“引用计数变成零”。GC 从 roots 出发追踪对象图：

```text
roots
  ├── 当前栈上的局部变量
  ├── 全局对象
  └── 运行时持有的引用
          ↓
      可达对象保留

没有从 roots 到达的对象图 → 可回收
```

因此两个 Kotlin 对象互相强引用，并不必然泄漏：只要整个环与 roots 断开，tracing GC 就能判断它们整体不可达。

PocketLab 的 iOS 专用测试真的创建了这个环：

```kotlin
private fun createUnreachableCycle(): WeakReference<CycleNode> {
    val first = CycleNode()
    val second = CycleNode()
    first.next = second
    second.next = first
    return WeakReference(first)
}
```

测试只保留弱引用，然后强制完成一次 GC：

```kotlin
val weakFirstNode = createUnreachableCycle()

GC.collect()

assertNull(weakFirstNode.value)
```

它在 iOS x64 Native runner 通过。这不是用 `GC.collect()` 教生产代码管理内存；恰恰相反，它是一个诊断实验，用来证明对象生命周期由 tracing reachability 决定。

官方把 `GC` runtime API 标为不稳定，并明确说 `lastGCInfo()` 适合测试与调试。业务代码不应该在每个页面退出时手动 collect，更不应该为了“消除 pause”把 GC 设置为 `noop`——那只会让 Kotlin 对象持续累积。

## 2026 年还需要 `freeze()` 吗？不需要，而且不该再教

很多 KMM 旧文章会告诉你：

- 跨线程前先 `freeze()`；
- 可变对象 freeze 后再修改会抛 `InvalidMutabilityException`；
- 顶层共享状态要加 `@SharedImmutable`；
- coroutines 要选 `native-mt` 版本。

这些描述对应的是 legacy memory manager。

当前官方迁移文档给出的时间线非常明确：

- 新 memory manager 从 Kotlin 1.7.20 起默认启用；
- legacy memory manager 在 Kotlin 1.9.20 完全移除；
- freezing 已弃用且始终关闭；
- `freeze()`、`isFrozen`、`FreezingException`、`InvalidMutabilityException` 等旧 API 应删除；
- coroutines 不应再使用 `native-mt` 后缀。

所以今天看到“这个 StateFlow 必须先 freeze 才能给 iOS 多线程使用”，第一反应应该是检查文章日期和 Kotlin 版本，而不是把 freeze 加回项目。

但旧限制消失不代表并发正确性免费到来。

## 共享堆解决“能不能访问”，不解决“同时修改会怎样”

考虑一个普通可变 Map：

```kotlin
private val counts = mutableMapOf<String, Int>()
```

现代 Kotlin/Native 允许两个线程拿到同一个 `ConcurrentLedger`。这只说明 runtime 不会要求对象图先 freeze；若两个线程同时执行读改写，仍然是数据竞态。

PocketLab 的共同测试刻意创建两个单线程 dispatcher：

```kotlin
val firstDispatcher = newSingleThreadContext("ledger-first")
val secondDispatcher = newSingleThreadContext("ledger-second")
```

200 个 coroutine 交替在两边修改同一对象。`ConcurrentLedger` 用 `Mutex` 声明唯一的序列化规则：

```kotlin
suspend fun record(experimentId: String): Int = mutex.withLock {
    val next = counts.getOrElse(experimentId) { 0 } + 1
    counts[experimentId] = next
    next
}
```

最终验证：

```kotlin
assertEquals((1..200).toSet(), observedCounts.toSet())
assertEquals(mapOf("shared-experiment" to 200), ledger.snapshot())
```

同一测试在 JVM、Android Host 和 iOS Native 都通过。它同时证明两件事：

1. 同一个 Kotlin 可变对象可以跨 Native worker-backed dispatcher 使用，无需 freeze；
2. 正确结果来自 `Mutex`，不是来自共享 heap。

测试里使用两个显式线程是为了把边界变得肉眼可见。生产代码通常不该为每个对象创建线程；应该优先使用结构化并发、已有 dispatcher、状态所有者或 actor-like 消息循环。

## 四种可靠的共享状态策略

现代 KMP 项目不需要 freeze，但仍要选择并发所有权。

### 1. 不共享可变状态

优先传递 immutable snapshot：

```kotlin
data class ExperimentRecord(...)
Flow<List<ExperimentRecord>>
```

PocketLab 的 SQLDelight Flow 每次发布完整列表。消费者读取快照，不拿到 Repository 内部可变集合。

### 2. 把状态限制在一个所有者

让一个 coroutine/actor 处理消息，外部只发送 intent。这样正确性来自串行所有权，而不是每个调用点都记得加锁。

### 3. 用 Mutex 或 atomic 明确保护

当没有自然的单线程所有者，而多个 coroutine 必须偶尔修改同一状态时，`Mutex.withLock` 是清晰选择。原子类型适合更小的独立状态；复杂不变量仍需要更高层序列化。

### 4. 在 UI 边界使用平台隔离

Android 状态由 `viewModelScope` 和主线程 UI 规则管理；Swift 状态放在 `@MainActor`。Kotlin callback 是否从主线程到达，必须由 bridge 显式保证，不能靠“调用是从 Swift 主线程开始的”推断。

## GC 遇见 ARC：两套自动内存管理机制没有合并

Swift/Objective-C 使用 ARC：编译器插入 retain/release，对象强引用数归零时可析构。

Kotlin/Native 使用 tracing GC：对象在一次 collection 中被判定不可达后回收。

当对象跨过 framework 边界，Kotlin/Native runtime 会维护二者之间的引用，但两种生命周期仍不同。

### Swift 对象进入 Kotlin 后，可能比纯 Swift 活得久

纯 Swift 临时对象通常在 ARC release 后很快 `deinit`；一旦它被传给 Kotlin，释放可能要等 Kotlin GC 处理 interop reference。不要用“函数返回了”推断 deinit 已发生。

### 析构线程不是一句“永远在主线程”

官方规则包含条件：Swift/Objective-C 对象若从主线程传入 Kotlin，且 main dispatch queue 正常处理，默认会在主线程完成相关释放；如果对象从其他线程传入，或主队列未处理，可能在专用 GC 线程释放。

`kotlin.native.binary.objcDisposeOnMain=false` 还能明确改为专用 GC 线程。

因此 `deinit` 不应偷偷依赖任意 UI 操作。如果资源必须在特定 actor 或 queue 关闭，应设计显式 `close()` / `stop()` 协议，而不是等待回收器挑一个时机。

### suspend completion handler 也不保证回主线程

官方 ARC integration 文档直接提醒：从 Swift 调 Kotlin suspend API，completion handler 可能在非主线程调用。

这就是 PocketLab Apple bridge 为什么在 Kotlin 侧选择 `Dispatchers.Main`，Swift ViewModel 又使用 `@MainActor`。一边是 callback delivery contract，一边是 UI state isolation；两者都不能用 ARC 或 GC 替代。

## 纯 Kotlin 环能回收，混合引用环为什么更麻烦？

纯 Kotlin 环：

```text
Kotlin A ─strong→ Kotlin B
    ↑                 │
    └────strong───────┘

与 roots 断开 → tracing GC 可整体回收
```

纯 Swift 环：

```text
Swift A ─strong→ Swift B
   ↑                │
   └────strong──────┘

双方 retain count 都不归零 → ARC 无法回收
```

混合环更危险：

```text
Swift ViewModel ─strong→ Kotlin Bridge
       ↑                       │
       └──── strong callback ──┘
```

Swift 对象让 Kotlin 对象仍可达，Kotlin callback 又 retain Swift 对象。两套机制各自在自己的模型中看到“还活着”，整个环不会自动被其中一边神奇识别。

官方结论很直接：混合环中只要包含 Objective-C 对象，Kotlin GC 无法单独打破它。解决方式是设计弱边：Swift 使用 `[weak self]` / `weak` / `unowned`，或提供显式取消，让 Kotlin 不再持有 callback。

PocketLab 的 `NativeCancellable`、`AsyncStream.onTermination` 与 feature `close()` 不只是协程优雅写法，也是在主动切断跨运行时所有权链。

## autoreleasepool 什么时候才是正确工具？

如果 Kotlin 长循环反复调用 Objective-C API，循环中产生的 autoreleased 临时对象可能在默认 pool drain 前持续堆积。官方建议在这类 interop hot loop 中用 `autoreleasepool { ... }` 缩短临时对象寿命。

它不是每个 Kotlin 函数都要套的性能咒语。使用前先确认：

- 循环确实跨 Objective-C/Swift interop；
- Instruments 或 GC stable-ref 日志显示对象持续增长；
- pool 的粒度不会引入明显开销。

没有证据就到处加 pool，与没有证据就手动 `GC.collect()` 是同一种问题：把运行时旋钮当成架构。

## GC pause 怎么看？用 Instruments，不用肉眼猜

Kotlin/Native GC 算法仍在演进。官方文档提供的诊断入口比背诵某一版本的 collector 名称更有长期价值：

- `-Xruntime-logs=gc=info`：把 GC 日志写到 stderr；
- `kotlin.native.binary.enableSafepointSignposts=true`：在 Apple 平台向 Instruments 暴露 GC pause signposts；
- Instruments 中筛选 subsystem `org.kotlinlang.native.runtime`、category `safepoint`；
- `GC.lastGCInfo()`：测试和调试时读取最近一次 collection 指标；
- VM Tracker：查看使用默认 allocator 时带 Kotlin mmap tag 的内存。

排查一次“页面偶尔卡顿”，正确顺序是：

```text
复现卡顿
→ Time Profiler / signpost 对齐时间线
→ 判断是 GC pause、主线程工作、数据库还是 SwiftUI layout
→ 再决定优化对象分配、并发或渲染
```

看到 Kotlin/Native 就先调 GC 参数，会让你优化一个尚未被证明存在的问题。

## 内存回收不等于生命周期结束

这一点值得单独写在墙上：

```text
GC/ARC：对象内存何时可回收
Job.cancel：异步工作何时停止
close：数据库、网络与订阅何时释放
UI lifecycle：结果何时仍允许更新画面
```

它们不是同一个开关。

如果一个 Flow collection 仍然活着，它可能成为 GC root 链的一部分；即使对象最终可回收，也不代表你可以让网络请求和数据库 listener 一直等到回收发生。

因此 PocketLab 继续坚持：

- coroutine 由明确 Scope 拥有；
- 每次 Apple 订阅返回可取消 token；
- Swift Task termination 传播回 Kotlin Job；
- feature 显式关闭 network、bridge 与 database；
- `deinit`/GC 是最后的内存机制，不是业务生命周期 API。

## 这次实验的真实验证矩阵

新增两个不同场景：

- 共同并发测试在 JVM、Android Host、iOS x64 各执行一次；
- Native GC 引用环测试只在 iOS x64 执行。

当前结果：

| 验证 | 结果 |
|---|---:|
| JVM common + platform | 39/39 |
| Android Host common + platform | 39/39 |
| iOS x64 common + platform / interop / GC | 45/45 |
| Android ViewModel JVM | 2/2 |
| Android Compose UI | 2/2 |
| iOS XCTest UI | 1/1 |
| 不同测试场景 | 52 |
| 自动化执行总数 | 128/128 |
| Debug static framework | x86_64，约 67 MB |
| Release static framework | x86_64，约 12 MB |

共同模块本身有 123 次目标执行：38 个 common 场景运行三遍，加上 JVM 1、Android Host 1、iOS 专用 7。再加 Android 壳 4 次和 iOS UI 1 次，得到 128。

## 六个最该淘汰的 Kotlin/Native 误解

### 1. “Native 没有 runtime”

没有 JVM，但有 Kotlin/Native runtime、对象模型、GC 与 interop 支持。

### 2. “Swift ARC 会管理所有 Kotlin 对象”

Kotlin heap 由 tracing GC 管理；ARC 只管理 Swift/Objective-C 所有权，两者在边界协作。

### 3. “跨线程仍要 freeze”

legacy memory manager 已移除。现代共享堆允许跨线程访问；要补的是同步，不是 freeze。

### 4. “允许共享就不会 data race”

共享 heap 是可访问性，`Mutex`、atomic、actor 或线程限制才是正确性。

### 5. “framework 多大，App 就增加多大”

静态 archive 还要经过最终链接与 dead stripping；必须比较最终 App archive。

### 6. “等 GC 就会自动取消任务并关闭资源”

GC 回收内存，不表达业务生命周期。Job、listener、database 和 network 都需要显式所有者。

## 给 Android 开发者的一张翻译表

| JVM/Android 经验 | Kotlin/Native 对照 | 需要修正的直觉 |
|---|---|---|
| JVM bytecode + ART | LLVM backend + native binary | Native 仍携带运行时能力 |
| JVM heap + GC | Native shared heap + tracing GC | 算法与调优旋钮不同 |
| `synchronized` / Lock | `Mutex` / atomic / confinement | common code 优先用跨平台抽象 |
| Main Looper | Darwin main queue / Swift MainActor | completion 不自动回主线程 |
| Java weak reference | `kotlin.native.ref.WeakReference` | 主要用于诊断或缓存语义 |
| LeakCanary | Instruments + GC logs/signposts | 混合 ARC/GC 环需人工审计 |
| AAR | KLIB / framework / XCFramework | 库格式与最终链接阶段不同 |

这张表用来迁移问题意识，不代表两边实现完全等价。

## 本篇的工程决策

PocketLab 从现在起遵守：

1. 不引入 `freeze()`、`@SharedImmutable`、`native-mt` 等 legacy memory manager 方案；
2. 跨线程共享 immutable snapshot，必须共享可变状态时显式使用 Mutex、atomic 或单一所有者；
3. Swift UI 状态保持 `@MainActor`，Kotlin callback 的 dispatcher 由 bridge 明确声明；
4. 跨 Swift/Kotlin callback 默认按可能形成强引用环审计，并提供取消或弱引用边；
5. 资源与异步任务显式 close/cancel，不等待 GC/ARC；
6. GC runtime API 只用于测试、诊断和受控 profiling，不进入日常业务控制流；
7. 性能与体积结论必须来自 Release App、Instruments 和最终 archive，不从 Debug framework 外推；
8. KLIB、framework、Xcode final link 和 Simulator launch 继续作为独立验证阶段。

## 读者挑战

1. 临时移除 `ConcurrentLedger` 的 Mutex，把并发次数放大，观察 JVM 与 Native 的错误是否稳定可复现，并解释为什么“偶尔正确”没有证明线程安全；
2. 给 GC 测试保留一个全局强引用，验证弱引用不再被清空，再移除该 root；
3. 用 `nm` 找到 `_sqlite3_open_v2` 未定义符号，然后从 Xcode 移除 `-lsqlite3` 观察最终链接失败；
4. 用 Instruments safepoint signposts 录制一次 demo refresh，判断 300 ms demo delay 与 GC pause 是否有关；
5. 在 Swift 写一个 ViewModel ↔ Kotlin callback 强引用环，再用 `[weak self]` 与 token cancel 分别打破它；
6. 构建 arm64 Simulator framework，与 x86_64 结果组成 XCFramework，解释为什么 `lipo` 与 XCFramework 解决的问题不同。

## 写在最后

Kotlin/Native 最容易被两个极端误解：一种把它想成“换了语法的 Swift”，另一种仍活在必须 freeze 一切的旧 memory manager 时代。

真实模型更有趣：

- Kotlin 被编译成原生机器码，却带着自己的对象与 GC 运行时；
- 对象可以自由跨线程，却仍需要你设计并发所有权；
- Kotlin GC 能回收纯 Kotlin 环，却无法替 Swift ARC 打破混合强引用环；
- framework 可以生成成功，却仍要由 Xcode 完成系统库与 App 的最终链接；
- 自动内存管理减少了手工 release，却没有替你管理 Job、listener 和产品生命周期。

理解这些之后，Kotlin/Native 就不再是一只黑箱。下一篇我们会站到 Swift 使用者一侧：哪些 Kotlin API 虽然“成功导出”，却让 Swift 调用者非常痛苦？又怎样用命名、类型、错误和 wrapper 设计出真正像 Swift 的边界？

## 官方资料

- [Kotlin/Native 概览](https://kotlinlang.org/docs/native-overview.html)
- [Kotlin/Native libraries 与 KLIB 格式](https://kotlinlang.org/docs/native-libraries.html)
- [Kotlin/Native memory management](https://kotlinlang.org/docs/native-memory-manager.html)
- [迁移到现代 Kotlin/Native memory manager](https://kotlinlang.org/docs/native-migration-guide.html)
- [Kotlin/Native 与 Swift/Objective-C ARC](https://kotlinlang.org/docs/native-arc-integration.html)
- [Kotlin/Native binary options](https://kotlinlang.org/docs/native-binary-options.html)
- [改进 Kotlin/Native 编译时间](https://kotlinlang.org/docs/native-improving-compilation-time.html)
- [Swift/Objective-C interoperability](https://kotlinlang.org/docs/native-objc-interop.html)
- [Shared mutable state and concurrency](https://kotlinlang.org/docs/shared-mutable-state-and-concurrency.html)
- [Kotlin/Native as an Apple framework](https://kotlinlang.org/docs/apple-framework.html)
