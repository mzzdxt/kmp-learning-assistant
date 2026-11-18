# `expect/actual` 像插座，但别把整栋楼焊在上面

> 面向读者：已经理解 KMP source set，正在设计平台能力边界的开发者  
> 技术核对与 JVM、Android、iOS 实机验证日期：2026-11-18

KMP 初学者第一次看到 `expect/actual`，通常会有一种终于找到“跨平台魔法”的兴奋：

```kotlin
expect class Database
expect class Logger
expect class Navigator
expect class PermissionManager
```

共同代码里列愿望，各平台负责实现。看起来所有差异都被优雅收编。

等项目变大以后，每加一个 target，都要补齐一面巨大的 actual 墙；每改一个 expected class，所有平台同时震动。原本想隔离平台，结果把整个架构绑定到了编译器匹配机制。

`expect/actual` 的确很有用。但它更像墙上的插座：让薄薄的平台能力穿过边界。插座不应该变成整栋楼的结构，也不该把每件电器焊死在墙上。

这一篇，我们用 PocketLab 的系统时间与 ID 生成器，看看怎样把它用在恰当的位置。

## 先说本质：它是编译期声明匹配

共同 source set 可以写：

```kotlin
expect fun createPlatformClock(): EpochClock
```

这个函数没有函数体。它表达的是：

> 每个消费这段共同代码的目标，必须提供一个签名兼容的实现。

JVM source set 提供：

```kotlin
actual fun createPlatformClock(): EpochClock = object : EpochClock {
    override fun nowMilliseconds(): Long = System.currentTimeMillis()
}
```

iOS source set 提供自己的 `actual`。编译某个 target 时，Kotlin 编译器把 expected declaration 与该 target 可见的 actual declaration 匹配起来。

这里没有运行时反射，没有 ServiceLoader，也不是：

```kotlin
if (platform == IOS) { ... }
```

匹配发生在编译期。缺少 actual 的 target 无法完成编译。

## 四条基本规则

使用 expected 与 actual declarations 时，至少记住：

1. `expect` 位于共同或中间 source set，不包含实现。
2. 对每个最终 target，都必须存在一条可见的 `actual` 路径。
3. expected 与 actual 必须位于同一个 package。
4. 声明种类与签名必须兼容，包括名称、参数和返回类型。

编译器会替你检查这些规则，这正是它比运行时平台判断可靠的地方。

但编译器只保证“匹配”，不保证：

- 两端返回相同单位；
- 时间使用相同纪元；
- UUID 具有业务需要的稳定性；
- API 易于测试；
- 架构边界合理。

语言机制不会替你完成契约设计。

## PocketLab 为什么既用接口，又用 `expect/actual`？

上一实验中，保存用例依赖普通接口：

```kotlin
interface EpochClock {
    fun nowMilliseconds(): Long
}

interface ExperimentIdGenerator {
    fun nextId(): String
}
```

这些接口非常重要，因为领域测试需要注入固定时间和固定 ID：

```kotlin
SaveDraftUseCase(
    repository = repository,
    idGenerator = FixedIdGenerator("experiment-42"),
    clock = FixedClock(1_700_000_000_123),
)
```

如果 `SaveDraftUseCase` 直接调用全局平台函数，测试将依赖真实时间和随机数，变得不确定。

生产运行时又需要一个默认装配方式。于是我们只在组合边界声明：

```kotlin
expect fun createPlatformClock(): EpochClock

expect fun createPlatformIdGenerator(): ExperimentIdGenerator
```

共同工厂负责组装：

```kotlin
fun createPlatformSaveDraftUseCase(
    repository: ExperimentRepository,
): SaveDraftUseCase = SaveDraftUseCase(
    repository = repository,
    idGenerator = createPlatformIdGenerator(),
    clock = createPlatformClock(),
)
```

这形成了清晰分工：

```text
普通接口       → 领域需要什么、如何替换
expect/actual  → 这个 target 默认怎样创建适配器
```

它们不是互斥方案，而是解决不同维度的问题。

## 三个平台的 actual 长什么样？

### JVM

```kotlin
actual fun createPlatformClock(): EpochClock = object : EpochClock {
    override fun nowMilliseconds(): Long = System.currentTimeMillis()
}

actual fun createPlatformIdGenerator(): ExperimentIdGenerator =
    object : ExperimentIdGenerator {
        override fun nextId(): String = UUID.randomUUID().toString()
    }
```

### Android

Android 也能使用 Java 的时间与 UUID，因此实现几乎相同。这里保留少量重复是有意的：JVM 与 Android 是两个 KMP targets，未来它们可能因 API、依赖或测试环境不同而分化。

不要为了消灭六行重复，立刻创建一个所有 target 都难以理解的 source set 层级。抽象也有成本。

### iOS

iOS 使用 Foundation 的 `NSUUID`，时间则通过 Core Foundation 转换为 Unix epoch milliseconds：

```kotlin
actual fun createPlatformClock(): EpochClock = object : EpochClock {
    override fun nowMilliseconds(): Long =
        (
            (CFAbsoluteTimeGetCurrent() +
                kCFAbsoluteTimeIntervalSince1970) * 1_000
        ).toLong()
}

actual fun createPlatformIdGenerator(): ExperimentIdGenerator =
    object : ExperimentIdGenerator {
        override fun nextId(): String = NSUUID().UUIDString()
    }
```

共同层只看到 `Long` 和 `String`。Core Foundation、NSUUID 和 Java UUID 都停留在平台 source set。

这就是一只合格的“插座”：接口很小，平台类型不会顺着电线爬进领域代码。

## 一个真实互操作坑：头文件存在，不等于 Kotlin 写法靠猜

第一次实现 iOS Clock 时，我们写了：

```kotlin
NSDate().timeIntervalSince1970
```

本机 iPhoneSimulator SDK 的 `NSDate.h` 确实声明了 `timeIntervalSince1970` 属性。但 Kotlin/Native 2.4.21 当前 compilation 的可见声明无法解析这个访问形式，编译失败：

```text
Unresolved reference 'timeIntervalSince1970'
```

我们没有添加 suppress，也没有换一个看起来相似的方法名碰运气。检查 SDK 头文件后，选择了同样明确的 C API：

```c
CFAbsoluteTime CFAbsoluteTimeGetCurrent(void);
const CFTimeInterval kCFAbsoluteTimeIntervalSince1970;
```

Kotlin/Native 实现编译通过，并由契约测试验证结果位于 2020—2100 的 Unix epoch milliseconds 区间。

这个过程提醒我们，平台互操作至少有三层事实：

```text
平台 SDK 原始声明
→ cinterop 生成的 Kotlin 可见声明
→ 业务需要的语义和单位
```

头文件证明 API 存在，编译器证明 Kotlin 侧怎样调用，测试证明转换语义正确。三者缺一不可。

## 为什么不直接写 `expect class Clock`？

可以写，并不等于应该写。

expected/actual class 会让类的构造方式、成员形状和平台实现通过语言机制绑定在一起。对框架适配或必须继承平台类型的少数场景，它可能有价值；但 Kotlin 官方目前仍将 expected/actual classes 标记为 Beta。

我们的需求只是“给我一个实现 `EpochClock` 的默认对象”。一个 expected factory function 已经足够：

```kotlin
expect fun createPlatformClock(): EpochClock
```

它有几个好处：

- expected 表面积只有一个函数；
- 返回普通接口；
- 平台具体类保持私有；
- commonTest 可以绕开 factory 注入 fake；
- 将来可替换 DI 装配而不修改领域类型。

原则不是“永远不用 expected class”，而是：**先使用最小声明形状。**

## 四种平台能力接入方式，怎么选？

### 1. 直接使用多平台库

如果成熟 KMP 库已经提供统一实现，优先评估它。你可能根本不需要自己创建平台 seam。

代价是引入依赖、接受其语义和发布节奏，需要检查支持的 targets 与维护状态。

### 2. 普通接口 + 平台入口注入

适合：

- 有状态能力；
- 多个实现；
- 测试替换；
- 运行时环境切换；
- 平台 App 本来就有 composition root。

Repository、网络 client、定位、权限协调器通常更接近这一类。

### 3. `expect/actual` 函数或属性

适合：

- 很薄的编译期平台事实；
- 每个 target 必须提供；
- common code 需要统一入口；
- 不需要运行时选择。

例如默认 adapter factory、平台名称或某些无法由库覆盖的小型 API bridge。

### 4. 只在平台 source set 直接调用

如果能力只服务平台 UI 或入口，common code 根本不需要知道它。直接留在 `androidMain`、`iosMain` 或 App module，往往是最简单的设计。

不是每个差异都需要“跨平台抽象”。

## 中间 source set 可以减少 Apple 平台重复

当前实验只有 `iosX64()`，所以 actual 位于 `iosX64Main`。

真实项目通常同时声明：

```kotlin
iosArm64()
iosSimulatorArm64()
```

默认层级模板会建立共同的 `iosMain`。如果两个 targets 的 Foundation 实现相同，可以把 actual 放在 `iosMain`，由两个 leaf targets 共同可见：

```text
commonMain: expect
    |
iosMain: actual using Foundation
    |-- iosArm64Main
    `-- iosSimulatorArm64Main
```

actual 不一定只能写在最底层 target source set；它可以写在覆盖一组 targets 的中间 source set，只要每条最终编译路径都能看到唯一匹配实现。

## 五个危险信号

### 1. 一个 expected class 包含几十个能力

`PlatformManager` 同时负责文件、权限、分享、网络和导航。每次修改都迫使所有 targets 同步，说明插座已经变成配电室。

### 2. common code 到处直接调用全局 expect 函数

这会让测试无法替换真实时间、随机数和系统状态。把全局调用限制在组合边界，领域仍依赖普通接口。

### 3. actual 返回平台类型给共同层

如果 expected API 需要 `Any` 或平台包装器才能通过签名，说明平台边界正在泄漏。

### 4. 用 `expect/actual` 代替运行时策略

同一个 Android target 需要 production、staging 和 fake 三种实现，target 匹配并不能解决环境选择。使用 DI 或显式参数。

### 5. 新增 target 时需要复制大块代码

新增 desktop 或 web 后要实现几十个 actual，说明 expected surface 过大。重新判断哪些能力应该用多平台库、普通接口或干脆不共享。

## 不只测试“有 actual”，还要测试契约

本实验新增三条 commonTest：

1. Clock 返回合理的 Unix epoch milliseconds。
2. ID 非空，连续两次不同。
3. 平台 factory 组装出的 UseCase 能完成保存。

运行：

```shell
./gradlew :shared:allTests
```

结果：

| 目标 | 新增 actual 契约 | 累计测试 | 失败 |
|---|---:|---:|---:|
| JVM | 3 | 14 | 0 |
| Android Host | 3 | 14 | 0 |
| iOS x64 Simulator | 3 | 14 | 0 |

合计 42/42 通过。

同一份 commonTest 在三个 target test compilations 中分别链接实际 adapter。它不仅证明 actual 存在，还证明三套实现遵守共同语义。

## 一张决策图

```text
平台差异是否需要被 common code 看见？
├── 否 → 留在平台 source set / App module
└── 是
    ├── 是否已有合适的多平台库？
    │   └── 是 → 评估后直接使用
    └── 否
        ├── 是否需要 fake、多个实现或运行时切换？
        │   └── 是 → 普通接口 + 显式装配/DI
        └── 否
            └── 是否是薄而稳定的 target 事实？
                ├── 是 → 小型 expect/actual 函数或属性
                └── 否 → 重新划分边界
```

## 最后的心智模型

`expect/actual` 保证的是：

> 编译这个 target 时，这个声明一定有平台版本。

普通接口保证的是：

> 这段业务只依赖一种能力契约，实现可以替换。

多平台库提供的是：

> 已有人替你实现并维护多 target 差异。

平台代码表达的是：

> 这个差异根本没必要进入共同世界。

把四者分清，`expect/actual` 就不再是到处挥舞的魔法，而是一只尺寸合适、位置清楚、可以测试的插座。

下一篇，我们进入 PocketLab 的第一条真正数据流：让保存结果变成可观察状态，并开始引入协程与 Flow。

## 动手挑战

为 PocketLab 增加一个平台 `DeviceLabelProvider`：

1. 先定义领域真正需要的返回语义。
2. 判断它是否需要普通接口、`expect/actual` factory，还是只应留在 UI。
3. 写一条 common contract test。
4. 临时删除一个 actual，观察编译失败发生在哪个 target。
5. 在学习日志中解释为什么没有创建 `expect class PlatformManager`。

## 参考资料

- [Expected and actual declarations](https://kotlinlang.org/docs/multiplatform/multiplatform-expect-actual.html)
- [Use platform-specific APIs](https://kotlinlang.org/docs/multiplatform/multiplatform-connect-to-apis.html)
- [Share code on platforms](https://kotlinlang.org/docs/multiplatform/multiplatform-share-on-platforms.html)
- [Hierarchical project structure](https://kotlinlang.org/docs/multiplatform/multiplatform-hierarchy.html)
- [Kotlin/Native interoperability with Objective-C](https://kotlinlang.org/docs/native-objc-interop.html)

