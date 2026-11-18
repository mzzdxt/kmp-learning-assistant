# KMM 到底是什么：同一份 Kotlin，为什么能跑在 Android 和 iOS？

> 面向读者：会 Kotlin、做过 Android，但没有接触过 KMP 的开发者  
> 技术核对日期：2026-11-17

第一次听说 KMM 时，我脑中出现的是一台神奇的“翻译机”：左边塞进去 Kotlin，右边吐出 Android 和 iOS App。

这个想象只对了一半。

Kotlin 确实能同时参与 Android 与 iOS 应用的构建，但 KMP 既不会把 Android App 塞进 iPhone，也不是在 iOS 上安装一台 JVM。它做的事情更朴素，也更强大：**同一份源代码，针对不同目标分别编译，最后进入各自平台的原生产物。**

理解这句话，你就拿到了 KMP 世界的第一把钥匙。

## 先把三个名字摆正

我们经常混用三个词：

- **KMP（Kotlin Multiplatform）**：Kotlin 的多平台能力。目标可以是 Android、iOS、桌面、Web 和服务端。
- **KMM（Kotlin Multiplatform Mobile）**：过去强调 Android+iOS 场景的叫法。今天讨论移动端时仍能听见，但官方主叙事已经统一到 KMP。
- **Compose Multiplatform**：建立在 KMP 之上的多平台 UI 框架。它让 UI 也可以共享，但它不是 KMP 本身。

所以，“使用 KMP”不等于“必须共享 UI”。你完全可以共享网络、数据库和业务逻辑，Android 继续用 Jetpack Compose，iOS 继续用 SwiftUI。

这恰恰是 KMP 最有意思的地方：**共享不是全有或全无，而是一只可调节的旋钮。**

## 一份代码，两条旅程

假设我们在共享模块里写了一个纯 Kotlin 函数：

```kotlin
fun greeting(name: String): String = "Hello, $name"
```

当构建 Android App 时，它大致经历：

```text
Kotlin 源码 → Kotlin/JVM 编译 → JVM 字节码 → Android 构建链 → APK/AAB
```

当构建 iOS App 时，它走的是另一条路：

```text
Kotlin 源码 → Kotlin/Native 编译 → 原生机器码/Framework → Xcode 链接 → iOS App
```

最终交付给 iPhone 的不是 JVM 字节码，而是 Kotlin/Native 生成并由 iOS 工程链接的原生二进制。iOS 不需要认识 Gradle，也不需要安装 JVM。

这也解释了一个常见问题：“KMP 的性能是不是一定像套了 WebView？”不是。它并没有以 Web 页面作为默认运行容器。但“原生编译”也不等于“所有代码必然和手写 Swift 一样快”；算法、对象模型、桥接成本、内存管理和调用边界仍然会影响性能。专家不会用口号代替测量。

## `commonMain` 是交集，不是另一个平台

KMP 工程里最常见的目录叫 `commonMain`。初学者很容易把它理解成“公共工具类收纳箱”。更准确的理解是：

> `commonMain` 表示所有目标平台共同承诺支持的 API 世界。

纯 Kotlin 集合、字符串处理、领域模型，以及支持 KMP 的协程、序列化库，都可以生活在这里。但 `android.content.Context` 只存在于 Android，`UIKit.UIViewController` 只存在于 iOS，它们不能凭空成为两端的交集。

于是，一个健康的共享模块通常长这样：

```text
shared
├── commonMain       领域规则、用例、共享数据层、接口
├── androidMain      Android 平台实现
└── iosMain          iOS 平台实现
```

注意：这不是“三份互不相干的代码”。`androidMain` 和 `iosMain` 是共同抽象在具体平台上的落点。

## 遇到平台能力怎么办？

假设业务日志需要当前设备名称。共享代码不能直接同时调用 Android 和 iOS API，我们有两类常用办法。

第一类是 `expect/actual`：

```kotlin
// commonMain
expect fun deviceName(): String

// androidMain
actual fun deviceName(): String = android.os.Build.MODEL

// iosMain
actual fun deviceName(): String = platform.UIKit.UIDevice.currentDevice.name
```

`commonMain` 声明“我期待每个平台提供这个能力”，每个平台给出自己的实际实现。编译器会检查每个目标是否都兑现了承诺。

第二类是普通接口与依赖注入：

```kotlin
// commonMain
interface DeviceInfo {
    val name: String
}

class CreateLog(private val deviceInfo: DeviceInfo) {
    fun invoke(message: String) = "[${deviceInfo.name}] $message"
}
```

Android 和 iOS 在各自入口创建实现，再注入共享逻辑。它更容易替换 fake、写测试，也允许一个平台存在多个实现。Kotlin 官方文档同样建议：能用普通语言结构解决时，优先考虑接口与工厂；不要因为 `expect/actual` 很“跨平台”就让它遍地开花。

一个简单判断是：

- 很薄、稳定、天然唯一的平台事实，例如平台名称，可考虑 `expect/actual` 函数或属性。
- 有行为、有状态、需要替换或测试的能力，例如定位、文件、网络监控，优先考虑接口注入。

## KMP 真正共享的是什么？

表面上共享的是代码，实际更有价值的是三样东西：

1. **业务规则只有一个事实来源。** 价格计算、状态转换、同步策略不再由两个团队分别“翻译”。
2. **测试可以跟着规则一起共享。** 同一组输入输出约束，会在目标构建中接受验证。
3. **平台差异被迫显式出现。** 哪部分相同，哪部分必须原生实现，会体现在 source set 和接口边界里。

但 KMP 不会自动消灭平台知识。要做好 iOS 集成，仍然需要理解 Xcode、Framework、Swift 互操作和 Apple 平台生命周期。KMP 专家不是“只会 Kotlin、绕过 iOS 的人”，而是能让共享逻辑和两端生态正确握手的人。

## 第一条架构原则：先寻找稳定的共同规则

最危险的问题是：“我们的代码最多能共享多少？”

它会诱导团队追求一个漂亮但空洞的复用率数字。更好的问题是：

> 哪些规则在 Android 和 iOS 上应该永远保持一致？哪些体验理应尊重平台差异？

订单金额、表单校验、离线同步状态机，通常属于前者。系统权限弹窗、导航手势、平台设计语言，往往属于后者。共享边界应该服务于变化边界，而不是服务于简历上的百分比。

## 现在你应该能回答三个问题

**Kotlin 为什么能跑在 iOS？**  
因为 Kotlin/Native 把面向 iOS 目标的 Kotlin 源码编译为原生二进制，再由 iOS 工程链接；不是在 iPhone 上运行 JVM。

**KMP 是否必须共享 UI？**  
不必须。KMP 可以只共享逻辑；Compose Multiplatform 让共享 UI 成为可选项。

**是不是所有代码都应该进 `commonMain`？**  
不是。只有真正跨目标成立、依赖也支持相应目标的代码，才适合进入共同 source set。

## 动手挑战：先别创建项目

拿你现在维护的一个 Android 功能，把类或职责写在纸上，分成三栏：

```text
稳定共同规则 | 需要平台能力但可抽象 | Android/iOS 原生体验
```

不要按“能否复制 Kotlin 文件”分类，而要按“变化原因是否相同”分类。然后挑出一个不依赖 Android SDK 的规则，它将成为我们下一篇文章里第一段真正跨越 Android 与 iOS 的代码。

下一篇，我们会拆开 KMP 工程的三个核心概念：**target、source set 和 compilation**。看完后，Gradle 脚本将不再像一份跨平台咒语。

## 参考资料

- [What is Kotlin Multiplatform](https://kotlinlang.org/docs/multiplatform/kmp-overview.html)
- [Kotlin Multiplatform quickstart](https://kotlinlang.org/docs/multiplatform/quickstart.html)
- [Use platform-specific APIs](https://kotlinlang.org/docs/multiplatform/multiplatform-connect-to-apis.html)
- [Expected and actual declarations](https://kotlinlang.org/docs/multiplatform/multiplatform-expect-actual.html)
- [Kotlin/Native memory management](https://kotlinlang.org/docs/native-memory-manager.html)

