# KMP 出问题时，谁来作证：从一条 628 ms 日志到可符号化崩溃

> 系列第 16 篇：崩溃、日志与性能——跨平台问题如何定位

用户说：“刚才下拉刷新，App 好像卡了一下，然后数据没变。”

Android 同学打开 Logcat，看见一句：

```text
refresh failed
```

iOS 同学打开 Console，看见另一句：

```text
request cancelled
```

服务端说那一分钟没有 5xx。数据库同学说 transaction 没报错。产品经理又补充：

> 还有一位用户说 App 直接闪退了，应该是同一个问题吧？

此时团队有三种常见反应：

1. 在更多地方加 `println`；
2. 用一个巨大的 `try/catch` 把错误都包起来；
3. 接入一个崩溃平台，期待它自动解释所有事情。

但真正缺失的不是更多字符串，而是一条能回答问题的证据链：

```text
这是哪一次操作？
它在哪个阶段结束？
是业务失败、取消、异常、崩溃，还是只是慢？
Release 混淆后还能还原吗？
记录过程中有没有泄露用户数据？
```

这篇文章不准备列出一张“推荐日志库”清单。我们要在 PocketLab 的真实刷新链上做一次完整实验：同一份 Kotlin 业务逻辑，在 Android 和 iOS 上留下同一种语义；Android 经过 R8 后仍能定位；Kotlin/Native 的 binary 和 dSYM 能用 UUID 配对；同时不把用户标题、笔记和异常消息塞进日志。

最后，我们会得到这样两条真实的 Release 日志：

```text
schema=1 event=pocketlab.refresh phase=started level=info
operation_id=16e5521b-f52a-46e3-8ee2-f4c24405428b

schema=1 event=pocketlab.refresh phase=finished level=info
operation_id=16e5521b-f52a-46e3-8ee2-f4c24405428b
duration_ms=628 outcome=applied received_count=3 changed_count=3
```

真实输出是单行，这里为了阅读换行。

628 ms 不是这篇文章最重要的数字。最重要的是：我们知道它属于哪一次操作、是什么结果、来自哪个 Release build，也知道它没有资格独自成为“性能结论”。

## 先别急着加日志：一次刷新有四种结局

PocketLab 的刷新路径并不复杂：

```text
Compose / SwiftUI
       ↓
platform ViewModel
       ↓
RefreshExperimentsUseCase
       ↓
Ktor remote
       ↓
SQLDelight transaction
       ↓
database Flow → UI
```

但“没刷新成功”至少有四种完全不同的结局。

### 第一种：成功应用

远端返回数据，本地事务完成，UI 随数据库 Flow 更新：

```text
outcome=applied received_count=3 changed_count=3
```

这是正常业务结果。

### 第二种：可预期失败

例如 HTTP 503、连接不可用、响应协议不符合预期。PocketLab 已经把它们映射为稳定失败原因：

```text
outcome=failed failure_reason=server status_code=503
```

产品可以展示“服务暂不可用”，测试可以断言稳定 code。它不等于程序崩溃。

### 第三种：取消

用户离开页面、Swift Task 被取消、Android ViewModel 被清理，正在进行的刷新不再需要结果：

```text
outcome=cancelled
```

取消不是失败，更不是 crash。它是结构化并发的一部分。

### 第四种：未预期异常

例如某段基础设施代码违反了自己的契约，抛出没有被业务模型覆盖的异常：

```text
outcome=crashed error_type=IllegalStateException
```

我们可以在异常继续向上传播前观察它，但不能偷偷把它改名为“网络错误”。

这四种终态的区别可以压缩成一张表：

| 终态 | 返回给调用方 | 是否重新抛出 | 诊断级别 |
|---|---|---:|---|
| applied | 成功结果 | 否 | info |
| failed | 稳定业务失败 | 否 | warning |
| cancelled | 无结果 | 同一个取消异常 | info |
| crashed | 无结果 | 同一个原始异常 | error |

如果我们用一个 `catch (Exception)` 把后面三种都转为 `Failed(Network)`，日志确实“整齐”了，但程序语义被破坏了。

## 可观测性的第一原则：观察者不能改写现场

PocketLab 在 `:domain` module 中定义了一个很窄的端口：

```kotlin
interface RefreshDiagnostics {
    fun begin(): RefreshDiagnosticOperation
}

interface RefreshDiagnosticOperation {
    fun applied(receivedCount: Int, changedCount: Int)
    fun failed(reason: RefreshFailureReason, statusCode: Int?)
    fun cancelled()
    fun crashed(failure: Throwable)
}
```

它没有 Android `Log`，没有 Apple OSLog，没有 JSON，也没有某家崩溃平台的 SDK。

领域层只认识刷新生命周期：开始，然后恰好进入一个终态。

用例里的关键代码是：

```kotlin
suspend fun execute(): RefreshExperimentsResult {
    val operation = diagnostics.beginSafely()

    return try {
        when (val result = remote.fetchExperiments()) {
            is RemoteExperimentsResult.Success -> {
                val changedCount = local.mergeServerSnapshot(...)
                operation.observeSafely {
                    applied(
                        receivedCount = result.experiments.size,
                        changedCount = changedCount,
                    )
                }
                RefreshExperimentsResult.Applied(...)
            }

            is RemoteExperimentsResult.Failure -> {
                val reason = result.reason.toRefreshFailure()
                operation.observeSafely {
                    failed(reason, result.statusCode)
                }
                RefreshExperimentsResult.Failed(reason, result.statusCode)
            }
        }
    } catch (cancellation: CancellationException) {
        operation.observeSafely { cancelled() }
        throw cancellation
    } catch (failure: Exception) {
        operation.observeSafely { crashed(failure) }
        throw failure
    }
}
```

这里有三个容易被忽略的细节。

### CancellationException 必须先接

取消异常也是异常。普通 `Exception` catch 如果排在前面，就会把取消记成 crash。

更糟糕的是，如果 catch 把它转换成业务失败，调用方以为任务已经正常完成，结构化并发的取消信号在共享层被吃掉了。

所以顺序是契约，不是代码风格。

### 未预期异常只观察，不转换

`crashed(failure)` 记录异常类型，随后重新抛出同一个实例。

为什么不用 `failure.message`？我们稍后谈隐私。为什么不返回 `Failed`？因为业务失败和程序缺陷需要完全不同的恢复方式。

一个 503 可以提示重试；一个违反内部不变量的 `IllegalStateException` 应该让测试、crash reporter 或上层边界看见真相。

### 日志后端自己崩了，也不能替代原始结果

假设远端刷新成功，日志 SDK 却因为初始化问题抛异常。用户应该得到刷新成功，还是日志 SDK 失败？

答案显然是前者。

因此 `begin` 和终态写入都被 best-effort 隔离：

```kotlin
private inline fun RefreshDiagnosticOperation.observeSafely(
    block: RefreshDiagnosticOperation.() -> Unit,
) {
    try {
        block()
    } catch (_: Exception) {
        // Observability cannot change product behavior.
    }
}
```

这不是说“所有日志错误都应该沉默”。日志系统自身可以有健康指标，但它不能覆盖正在观察的业务结果或原始异常。

我们的测试甚至放进一个故意抛错的 diagnostics backend，证明有效刷新仍然返回成功。

## 共享的不是日志 API，而是事件语义

很多 KMP 项目会在 `androidMain` 写一套日志，在 `iosMain` 再写一套。

这样做短期很快，长期会出现有趣的分叉：

```text
Android: refresh_failed, code=server
iOS: network_error, message=Request cancelled
```

两端都“有日志”，但不能聚合、不能比较，甚至对取消是否算错误都没有共识。

PocketLab 把固定 schema 放在共同层，把最终写入留给平台：

```text
domain lifecycle
       ↓
common fixed record + monotonic timer
       ↓
expect writePlatformDiagnostic
       ↓
Android Log / JVM stdout / iOS NSLog
```

`expect/actual` 在这里像一个真正的插座：只有最后一厘米接触平台 API，事件字段和语义都留在共同层。

这也是 KMP 可观测性最值得共享的部分：

- 事件名；
- schema 版本；
- 操作起止关系；
- outcome 分类；
- 稳定 reason code；
- correlation ID；
- duration 的测量规则；
- 隐私 allowlist。

不必共享的部分包括：

- Logcat 与 unified logging 的具体 API；
- 平台 crash report 格式；
- Perfetto 与 Instruments 的操作方式；
- Play Console、Organizer 或第三方后台的集成；
- 每个平台的采集、上传和 consent 细节。

共享语义不等于强迫两个操作系统使用同一种观测工具。

## 一条好日志，先从“不允许写什么”开始

最常见的结构化日志 API 大概长这样：

```kotlin
log(
    event = "refresh",
    attributes = mapOf(
        "whatever" to value,
    ),
)
```

它非常灵活。

灵活到任何人都可以写：

```kotlin
"title" to experiment.title
"notes" to experiment.notes
"url" to request.url
"error" to exception.message
```

而 code review 要在成百上千个调用点中识别这些风险。

PocketLab 反过来设计：record 根本没有任意 attributes map。

```kotlin
data class RefreshDiagnosticRecord(
    val phase: String,
    val level: DiagnosticLevel,
    val operationId: String,
    val durationMilliseconds: Long? = null,
    val outcome: RefreshDiagnosticOutcome? = null,
    val receivedCount: Int? = null,
    val changedCount: Int? = null,
    val failureReason: RefreshFailureReason? = null,
    val statusCode: Int? = null,
    val errorType: String? = null,
)
```

它只容纳三类信息：

1. 发生了哪类事情；
2. 哪一次操作、多久、结果是什么；
3. 少量稳定且有界的诊断维度。

实验标题、假设、笔记、URL、数据库路径、token、异常消息和原始响应没有座位。

这不是“已经完成全部隐私治理”。operation ID、状态码和时间仍是需要治理的诊断数据，远端上传还要考虑用户同意、保留期、访问控制与删除策略。

但类型级 allowlist 把默认方向扳对了：新增敏感字段需要显式修改 record、测试和门禁，而不是随手在 map 里加一项。

Android 官方也明确提醒日志信息泄露风险：不要把敏感或无界数据写入日志，尤其避免让攻击者控制日志内容。可参考 [Android 日志信息泄露风险](https://developer.android.com/privacy-and-security/risks/log-info-disclosure)。

## 为什么连 exception.message 也不记录

只记录异常消息，看起来比记录完整对象安全得多：

```kotlin
error("refresh failed: ${failure.message}")
```

问题是 exception message 的作者可能不在当前模块。

底层库可能把这些内容放进去：

- 完整 URL 和 query；
- 本地文件或数据库路径；
- 服务端响应片段；
- 用户输入；
- 认证信息；
- 设备环境细节。

你无法仅凭 `message` 这个名字证明它安全。

PocketLab 只记录：

```text
error_type=IllegalStateException
```

它为现场提供一个比 message 更受限的线索，真正的调用栈由 crash report 和符号文件负责。还要注意：应用自定义异常的类名可能被 R8 改名，所以 `error_type` 不能直接充当跨版本稳定指标。若要聚合趋势，应另建人工维护的稳定 `error_category`，并继续用匹配 mapping 还原 Release 堆栈。

这也避免把用户可见文案绑在诊断字符串上。产品错误应该由稳定 failure reason 映射本地化文案，而不是从 exception message 猜测。

## 让一行日志变成两行的攻击

假设 operation ID 来自外部系统：

```text
abc\nlevel=error token=secret
```

如果原样拼接，日志查看器可能把它显示成第二条伪造记录。这叫日志注入。

PocketLab 对 token 做两件事：

- 最长 80 字符；
- 只保留字母、数字、`-`、`_`、`.`，其余替换为 `_`。

于是动态值不能插入换行或新的键值分隔结构。

这不是通用编码器，但对当前固定 token schema 足够明确。若未来输出 JSON，应使用真正的 serializer，不要手拼引号和转义。

## 为什么 duration 不能拿两个“当前时间”相减

很多代码会这样测耗时：

```kotlin
val startedAt = Clock.System.now()
doWork()
val duration = Clock.System.now() - startedAt
```

`Clock.System` 回答的是“墙上现在几点”。墙上时间可能因 NTP 校时、用户设置、时区变化或系统行为跳动。

duration 要回答的是“经过了多久”，它需要只向前推进的 monotonic source：

```kotlin
val mark = TimeSource.Monotonic.markNow()
doWork()
val duration = mark.elapsedNow().inWholeMilliseconds
```

Kotlin 官方的[时间测量文档](https://kotlinlang.org/docs/time-measurement.html)说明，默认 monotonic source 在 JVM 使用高精度单调时间源，在 Native 使用平台的稳定高精度时钟。

这能避免时间回拨造成负 duration，也能让 Android 和 iOS 使用同一测量语义。

但请注意这个边界：

> 计时工具正确，不代表样本数量足够。

628 ms 是一次真实运行。它可以帮助我们关联现场，却不能告诉我们：

- p50、p95、p99 是多少；
- 真机是否比模拟器更慢；
- 4G、Wi-Fi、离线重试有什么差别；
- 0.16.0 是否比 0.15.0 退化；
- 慢在网络、反序列化、事务还是 UI。

想回答这些问题，需要分布、分层和 trace，而不是把一条日志升级为 KPI。

## Android：Debug 堆栈好看，不代表 Release 能定位

Android 开发者很熟悉这样的堆栈：

```text
at dev.kmpjourney.pocketlab.sync.RefreshExperimentsUseCase.execute(
    RefreshExperimentsUseCase.kt:52
)
```

但正式包会经过 R8 shrink、optimize 和 obfuscate。类名、方法名和行号映射都可能变化。

PocketLab 第一次真正开启：

```kotlin
release {
    isMinifyEnabled = true
    isShrinkResources = true
    proguardFiles(
        getDefaultProguardFile("proguard-android-optimize.txt"),
        "proguard-rules.pro",
    )
}
```

我们只额外保留 source 和 line attributes：

```proguard
-keepattributes SourceFile,LineNumberTable
```

没有写一条“保住整个业务包”的宽泛规则。否则 Release 虽然容易读，却没有验证真实混淆风险。

构建后产生：

```text
androidApp/build/outputs/apk/release/androidApp-release-unsigned.apk
androidApp/build/outputs/mapping/release/mapping.txt
```

真正还原混淆堆栈的是 `mapping.txt`。Android 的 [Retrace 文档](https://developer.android.com/tools/retrace)说明了如何用 mapping 恢复原始堆栈。

最关键的一句话不是“保存 mapping”，而是：

> 保存与这个 APK/AAB 完全匹配的 mapping。

每次构建都可能生成不同映射，同名文件会被覆盖。拿错 mapping 可能得到一个看似正常、实际错误的堆栈，比完全没有符号更危险。

所以 PocketLab 的门禁输出：

```text
Android Release: 0.16.0 (16)
R8: 9.1.29
pg_map_id: 740c594d49ba8c9f...
APK SHA-256: c3ec4aaf8575ec9f...
mapping SHA-256: 55137bf439885e07...
```

发布记录至少要把这些信息与 Git commit 绑定。

## 一次“构建成功”但不该接受的 R8 实验

第一次开启 minify 时，构建成功了。

但 R8 输出了 Kotlin 2.4 metadata parse warnings。

这是最容易被一句“warning 又不是 error”掩盖的地方。

Android 官方维护 Kotlin 与 D8/R8 的兼容表。当前表中 Kotlin 2.4 需要 R8 9.1.29。参见 [Kotlin 与 D8/R8 版本要求](https://developer.android.com/build/kotlin-support)。

项目使用 AGP 8.13.2，内置版本没有满足这条实验基线，于是我们从官方 R8 repository 覆盖：

```kotlin
pluginManagement {
    buildscript {
        repositories {
            google()
            mavenCentral()
            maven("https://storage.googleapis.com/r8-releases/raw")
        }
        dependencies {
            classpath("com.android.tools:r8:9.1.29")
        }
    }
}
```

重新构建后，Kotlin metadata warnings 消失，mapping header 明确写着：

```text
# compiler: R8
# compiler_version: 9.1.29
# min_api: 24
# mapping_version: 2.2
```

构建仍有两条 `OrderedClassFileResourceProvider` 不支持 async parsing 的 warning。我们没有把所有 warning 粗暴归为一类：

- metadata parse warning 影响 Kotlin 版本兼容信心，不能接受；
- async parsing provider warning 是当前 AGP provider 与独立 R8 组合的构建性能提示，记录并等待工具链升级评估。

工程判断不是“看到黄色就忽略”，也不是“看到 warning 就停止”。要读懂它说的对象、阶段和后果。

## 真正把 minified Release 装进模拟器

只生成 APK 和 mapping 仍不够。我们把 unsigned Release 复制到 ignored build 目录，用本机调试证书给副本签名，再安装到 Pixel 2 API 34 模拟器。

这里有一条重要边界：

> 本地 smoke 可以给副本临时签名，但不能为了方便把 Release 配置永久改成 debug signing。

签名验证通过后冷启动：

```text
Status: ok
LaunchState: COLD
TotalTime: 1414 ms
```

随后 Logcat 输出同一 operation ID 的 started 和 finished：

```text
duration_ms=628 outcome=applied received_count=3 changed_count=3
```

没有 `AndroidRuntime` crash。

这次实验能够证明：

- R8 处理后的 App 真能启动；
- composition root 使用了带 diagnostics 的 use case；
- Android actual writer 真能写入 Logcat；
- correlation 与 schema 在真实运行中成立；
- mapping 对应这次 Release build。

它不能证明：

- 1,414 ms 是可接受的启动预算；
- 628 ms 是用户设备的刷新分布；
- 所有被混淆异常都已完成 retrace；
- 线上 crash 上传和告警已经建立。

把“证明了什么”和“尚未证明什么”写清楚，恰恰是可观测性工程的一部分。

## iOS：日志能看到，为什么 crash report 还是一团地址

iOS 侧的当前 writer 很简单：

```kotlin
internal actual fun writePlatformDiagnostic(
    level: DiagnosticLevel,
    message: String,
) {
    NSLog("PocketLab ${level.name}: $message")
}
```

它足以让共同 schema 在 Native 上真实执行，但不是最终生产建议。

Apple 的 unified logging 提供 level、subsystem/category、持久化和 privacy。生产 App 应把这个薄适配器换成 `Logger`/OSLog，让动态字段有明确隐私策略；需要分析时间区间时使用 signpost。可参考 Apple 的[生成日志消息](https://developer.apple.com/documentation/os/generating-log-messages-from-your-code)与[记录性能数据](https://developer.apple.com/documentation/os/recording-performance-data)。

无论日志后端多高级，它都不能替代 dSYM。

当 Release binary 被优化、符号被剥离，crash report 里的地址需要匹配的调试符号才能恢复函数和源码位置。

Xcode Release 配置应为：

```text
DEBUG_INFORMATION_FORMAT = dwarf-with-dsym
```

Apple 的[构建调试信息文档](https://developer.apple.com/documentation/xcode/building-your-app-to-include-debugging-information)建议 Release 使用 DWARF with dSYM。真正匹配的依据不是文件名，而是 UUID。

先查 binary：

```shell
dwarfdump --uuid path/to/AppBinary
```

再查 dSYM：

```shell
dwarfdump --uuid path/to/App.app.dSYM
```

两者不一致，就不要继续符号化。

Apple 也提供了[查找缺失调试符号](https://developer.apple.com/documentation/xcode/locating-a-missing-debug-symbol-file)与[理解 crash report 字段](https://developer.apple.com/documentation/xcode/examining-the-fields-in-a-crash-report)的官方说明。

## KMP static framework 的 dSYM 到底谁负责

PocketLab 使用 static Kotlin framework。

这意味着 framework 是最终 App 的链接输入，而不是独立运行的进程。Kotlin 代码、Native runtime 和其他对象最终进入 App executable。

因此发布证据要围绕最终 App archive 保存：

```text
.xcarchive
├── Products/Applications/PocketLab.app
└── dSYMs/PocketLab.app.dSYM
```

不要只拿一个 framework 文件夹就宣布“iOS 符号已保存”。最终链接、strip 和 archive 都发生在 Xcode App 这一侧。

这次实验的机器有一个现实限制：Xcode 26.5 没安装匹配的 iOS 26.5 runtime，连 generic Simulator destination 也不可用。最终 SwiftUI App Release archive 因此没有生成。

我们没有把这个失败藏掉，也没有用 Debug framework 冒充最终 App。

那本轮还能验证什么？

## 用 Kotlin Native test executable 验证符号链路

Intel Mac 可以运行 `iosX64Test`。Kotlin/Native 为 test executable 生成了：

```text
test.kexe
test.kexe.dSYM
```

两者 UUID 都是：

```text
27989878-0ED1-33E9-99A6-73B55C1B20B5
```

门禁用 `nm` 找到共同诊断测试函数的地址，再让 `atos` 使用 dSYM 恢复：

```text
StructuredRefreshDiagnosticsTest#
appliedRefreshUsesOneCorrelationIdAndMonotonicDuration()
```

这证明了三件事：

1. Native binary 和 dSYM 可以用 UUID 精确配对；
2. dSYM 确实能恢复 Kotlin 函数名；
3. 整个过程可以进入脚本门禁，而不是靠 Xcode UI 手工感觉。

同时，`atos` 的源码位置是 `StructuredRefreshDiagnosticsTest.kt:0`。所以证据边界只能写成“函数名恢复”，不能夸成“完整源码行已恢复”。

最终 App archive/dSYM 仍要在有匹配 runtime 的 Apple CI 补做。

这种诚实的缺口记录比一句“iOS 也支持 dSYM”更接近生产工程。

## Swift 边界上的异常，不会自动变成漂亮的 Error

Kotlin 异常跨 Objective-C/Swift 边界时需要显式契约。Kotlin 官方的 [Swift/Objective-C 互操作文档](https://kotlinlang.org/docs/native-objc-interop.html)说明，只有声明允许桥接的异常会转换为可被 Swift/Objective-C 处理的错误；未被契约覆盖的异常可能成为未处理异常并终止进程。

这对我们的设计有直接影响：

- 可预期 failure 变成稳定 report/value；
- Swift 文案从稳定 code 映射，而不是使用 Kotlin exception message；
- cancellation 继续传播到 Swift Task/Kotlin Job；
- 未预期异常不伪装成业务失败；
- 最终 App 必须保存可符号化的发布资产。

“在边界 catch 住所有异常”看似保护 Swift，实际可能让程序在已经破坏不变量后继续运行。

崩溃报告的目的，是在无法安全继续时保留证据；不是让所有故障都悄悄变成一个空列表。

## 日志不是 trace，trace 也不是 metrics

到这里，我们已经有结构化事件、mapping 和 dSYM。但这不代表所有性能问题都该继续加日志。

四类信号回答四类问题。

### 结构化日志：发生了什么

```text
refresh started
refresh finished as applied
duration 628 ms
```

适合关联一次操作和业务终态。

### Crash、ANR、hang：程序为何终止或失去响应

Android crash stack、ANR traces，Apple crash/hang diagnostics 会告诉你线程和调用栈。

Android 官方提供 [ANR 诊断指南](https://developer.android.com/topic/performance/anrs/diagnose-and-fix-anrs)。Apple 的 [MetricKit](https://developer.apple.com/documentation/metrickit)可以提供 crash、hang、CPU、磁盘与启动等诊断/指标数据。

### Trace 与 signpost：时间花在哪里

如果 628 ms 变成 6,280 ms，下一步不是再写十条 `println`，而是看区间与线程：

```text
DNS / connect / TLS
response body
JSON decode
SQL transaction
Flow delivery
main-thread render
```

Android 使用 [system tracing / Perfetto](https://developer.android.com/topic/performance/tracing)，Apple 使用 Instruments 和 signpost。

### Metrics：一段时间是否退化

真正的性能判断要看分布：

```text
p50 / p95 / p99
by app version
by device class
by network condition
by cold/warm state
```

metrics 能回答“0.16.0 的 p95 是否比 0.15.0 退化”，一条现场日志不能。

可以用一句话记住：

```text
日志讲故事
崩溃报告给尸检
trace 拆时间线
metrics 看群体趋势
```

## 可观测性门禁到底检查什么

PocketLab 新增了：

```shell
./scripts/verify-observability.sh
```

它不是检查“代码里有没有 Log”。它实际执行：

```text
domain/shared JVM tests
shared iOS x64 tests
Kotlin/Native Debug framework
minified Android Release
R8 mapping checks
schema privacy checks
Native binary/dSYM UUID check
atos symbol restoration
Xcode Release dSYM setting check
Apple header API denylist
```

门禁还拒绝这些字段进入诊断源：

```text
failure.message
experiment.title / hypothesis / notes
baseUrl
databaseName
Authorization
```

为什么用静态 denylist，而不是说“大家注意隐私”？

因为注意力不是可重复的构建输入。它会随时间、压力和 reviewer 改变。

denylist 也不是完美隐私证明。真正稳固的部分是固定 record 的 allowlist，静态检查只是再加一层防退化。

门禁最终输出当前 build 的身份：

```text
Android version/build
R8 version
pg_map_id
APK SHA-256
mapping SHA-256
Native UUID
```

这让“我有 mapping”和“我有这次 build 的 mapping”成为两个不同命题。

## 11 个新测试，分别在防什么

本轮不是只测 render string。

领域层新增 5 个测试：

1. 成功只产生 started + applied；
2. 可预期失败保持稳定 reason/status；
3. 取消被观察后原样重抛；
4. 未预期异常被观察后原样重抛；
5. 损坏的 diagnostics 不能破坏有效刷新。

共同层新增 6 个测试：

1. started/finished 使用同一 correlation ID；
2. duration 来自可控 monotonic timer；
3. expected failure 只输出稳定 reason/status；
4. crash 只输出类型，不输出带秘密的 message；
5. token 中的换行被清理；
6. 生产 platform sink 真能执行且不改变结果。

这些共同场景会在 JVM、Android Host 和 iOS x64 重复执行。

更新后的 module 聚合是：

| module | JVM | Android Host | iOS x64 |
|---|---:|---:|---:|
| domain | 23 | 23 | 23 |
| shared | 27 | 27 | 35 |
| 合计 | 50 | 50 | 58 |

11 个新增共同场景在三个目标形成 33 次执行。它们验证的是共同语义在不同编译目标仍成立，而不是“JVM 过了，所以 Native 大概也行”。

## 一份可以照着走的事故处理顺序

假设明天收到：“0.16.0 刷新后闪退”。不要从群里猜。

### 第一步：确认 build 身份

收集：

```text
platform
versionName / versionCode 或 iOS version/build
发生时间
设备/系统
是否可复现
```

没有 build 身份，就无法选择 mapping 或 dSYM。

### 第二步：取得原始诊断

区分：

```text
crash report
ANR / hang report
structured event
用户描述
```

用户说“卡死”不等于系统判定的 ANR；用户说“闪退”也可能是系统杀进程。

### 第三步：先符号化，再读业务

Android：选择对应 mapping，用 retrace 还原。

Apple：比较 binary/dSYM UUID，再 symbolicate。

不要拿“差不多版本”的符号文件试出一个看似合理的函数名。

### 第四步：关联单次操作

用 `operation_id` 配对 started/finished，检查：

- 是否有 terminal event；
- outcome 是什么；
- duration 多久；
- 状态码或稳定 reason；
- crash 是否发生在 terminal event 前后。

### 第五步：选择正确工具

- expected failure：检查协议/网络和产品恢复；
- cancellation：检查生命周期和桥接，不当成错误率；
- crash：读符号化 stack；
- ANR/hang：读线程与系统诊断；
- slow：进入 Perfetto/Instruments；
- distribution regression：看版本化 metrics。

### 第六步：写可复现测试再修

把根因放进它所属的层：

```text
领域状态机 → common test
Ktor/SQLDelight 接缝 → shared integration test
Android lifecycle → Android test
Swift bridge → iOS Native/Swift test
Release shrink/symbol → build gate
```

这样下一次同类问题会在发布前失败，而不是只在仪表盘上变红。

## 五个最危险的“看起来已经做了”

### “我们接了 crash SDK，所以可观测性完成了”

如果没有稳定事件、版本身份、matching symbols、隐私策略和处理流程，SDK 只是一个上传通道。

### “Debug 堆栈有行号，Release 也没问题”

R8、Native optimization、strip 和最终链接都可能改变现场。必须验证真正 Release 产物。

### “所有异常都 catch，用户体验更稳定”

吞掉取消和程序缺陷会让状态机继续运行在错误前提上。稳定不是沉默。

### “多记一点，出事时总会有用”

无界日志会带来隐私、成本、噪声和攻击面。先定义问题，再定义最少字段。

### “这次 628 ms，所以阈值设 700 ms”

单样本没有分布、对照和环境控制。它是现场线索，不是 SLO。

## 当前实验没有假装完成什么

这次里程碑完成了：

- 固定刷新生命周期；
- 双端一致 schema；
- 隐私 allowlist；
- monotonic duration；
- Android minified Release 与 mapping 身份；
- Native executable/dSYM UUID 和函数恢复；
- Xcode Release dSYM 配置；
- 可执行门禁。

它没有完成：

- 第三方 crash/logging vendor 接入；
- 远端采样、上传、告警和保留策略；
- 最终 SwiftUI App Release archive/dSYM 验证；
- 真实用户 p50/p95/p99；
- Perfetto/Instruments 的专项性能优化；
- Android ANR 与 Apple hang 的受控故障演练。

没有这些，不妨碍当前证据有效；但不能用当前证据替它们背书。

## 回到最初的问题

用户说：“刷新卡了一下，数据没变。”

现在我们的回答顺序不再是：

```text
Android 看起来像网络错误
iOS 好像取消了
要不多打点日志
```

而是：

```text
先确认 version/build
拿到原始 crash/ANR/event
用匹配 mapping 或 dSYM 符号化
用 operation ID 关联刷新
判断 outcome 与 duration
慢则进入 trace，崩则读 stack
最后把根因写成所属层的测试
```

KMP 没有让两个平台变成同一个平台。

它给了我们一个机会：把业务事件的意义、取消语义、隐私边界和测量规则只定义一次；再让 Android 与 iOS 用各自最擅长的工具保留现场。

真正成熟的跨平台可观测性，不是两端都打印了同一句话，而是：

> 同一次操作有同一种语义；不同平台的证据能回到同一个 build；观察过程不泄密，也不改写程序行为。

那条 628 ms 日志因此才有价值。

不是因为 628 这个数字漂亮，而是因为我们终于知道：它是谁、从哪里来、能证明什么，以及不能证明什么。

## 动手挑战

如果你正在做 KMP 项目，可以从一个最小用例开始：

1. 写出 success、expected failure、cancellation、unexpected exception 四种终态；
2. 为一次操作生成 correlation ID；
3. 用 `TimeSource.Monotonic` 计算 duration；
4. 用固定 data class 代替任意 attributes map；
5. 写测试证明 exception message 和用户输入不会出现；
6. 构建一次真正 minified Android Release，保存对应 mapping；
7. 比较一次 Native binary 与 dSYM UUID；
8. 写下当前还没验证的最终产物，不要用 Debug 证据代替。

完整可运行步骤见[实验 16：让跨平台故障留下同一条证据链](../labs/16-observability/README.md)，架构取舍见 [ADR-0014](../docs/adr/0014-cross-platform-observability.md)。

## 参考资料

- [Kotlin 时间测量](https://kotlinlang.org/docs/time-measurement.html)
- [Kotlin/Native 调试](https://kotlinlang.org/docs/native-debugging.html)
- [Kotlin/Native 与 Swift/Objective-C 互操作](https://kotlinlang.org/docs/native-objc-interop.html)
- [Kotlin 协程调试](https://kotlinlang.org/docs/coroutines-debugging.html)
- [Android Retrace](https://developer.android.com/tools/retrace)
- [Android app optimization troubleshooting](https://developer.android.com/topic/performance/app-optimization/troubleshoot-the-optimization)
- [Kotlin 与 D8/R8 兼容版本](https://developer.android.com/build/kotlin-support)
- [Android 日志信息泄露风险](https://developer.android.com/privacy-and-security/risks/log-info-disclosure)
- [Android system tracing](https://developer.android.com/topic/performance/tracing)
- [Android ANR 诊断](https://developer.android.com/topic/performance/anrs/diagnose-and-fix-anrs)
- [Apple 构建调试信息](https://developer.apple.com/documentation/xcode/building-your-app-to-include-debugging-information)
- [Apple crash report 字段](https://developer.apple.com/documentation/xcode/examining-the-fields-in-a-crash-report)
- [Apple 查找缺失 dSYM](https://developer.apple.com/documentation/xcode/locating-a-missing-debug-symbol-file)
- [Apple unified logging](https://developer.apple.com/documentation/os/generating-log-messages-from-your-code)
- [Apple performance data 与 signpost](https://developer.apple.com/documentation/os/recording-performance-data)
- [Apple MetricKit](https://developer.apple.com/documentation/metrickit)

> 本文涉及版本与实验结果验证于 2026-11-20：Kotlin 2.4.21、Gradle 8.14.4、AGP 8.13.2、R8 9.1.29、Xcode 26.5。工具链升级后应重新运行门禁，不要把版本结论永久化。
