# Ktor：同一个请求，在三套网络引擎上发生了什么？

> 面向读者：熟悉 Retrofit、OkHttp 与 Kotlin Coroutines，第一次在 KMP 中共享网络层的 Android 开发者  
> 技术核对与三端验证日期：2026-11-18  
> 实验版本：Kotlin 2.4.21、Ktor 3.6.1、kotlinx.serialization JSON 1.11.0

假设 Android 和 iOS 都要读取 PocketLab 的实验列表：

```http
GET /v1/experiments
```

Android 开发者的第一反应可能是 Retrofit + OkHttp；iOS 同事则会想到 URLSession。

于是一个看似自然的分工出现了：两端各写一套请求。

几个月后，服务端把 `updatedAt` 改成 `updated_at_ms`，又新增一个 401 语义。Android 把 401 解释成登录过期，iOS 把它归到“网络错误”；一端忽略新字段，另一端解码失败；两个看起来相同的 App 开始生活在两套协议里。

Ktor Client 解决的核心问题，不只是“让 Kotlin 能发 HTTP 请求”，而是：

> 让请求、协议解析和失败语义只定义一次，同时让每个平台继续使用适合自己的网络引擎。

这篇不做一个只会打印 JSON 的 Demo。我们会把 PocketLab 的网络边界真正接进 `commonMain`，并回答这些更接近生产的问题：

1. Ktor 到底共享了什么，哪些事情仍由平台完成？
2. 为什么同一段请求会走 CIO、Android 与 Darwin 三种引擎？
3. DTO、领域模型和公共错误模型为什么必须分开？
4. 为什么 503、坏 JSON、断网和协程取消绝不能都叫“网络异常”？
5. 怎样不用公网，在 JVM、Android 与 iOS 上验证同一份请求契约？

## 先看全景：共享的是剧本，不是摄影机

本次数据流如下：

```text
Android UI                              iOS UI
    |                                      |
    `---------------.----------------------'
                    |
       ExperimentRemoteDataSource
                    |
       GET /v1/experiments（共同）
                    |
     ContentNegotiation + JSON（共同）
                    |
       DTO → RemoteExperiment（共同）
                    |
          Ktor HttpClient Engine
              /       |       \
             /        |        \
          CIO      Android     Darwin
          JVM       Android   iOS/NSURLSession
```

上半部分是共同语义：

- 请求路径与 method；
- headers 与认证策略；
- JSON 协议；
- DTO 到领域模型的映射；
- 状态码与失败分类。

最下方是平台执行：

- 建立连接；
- TLS 与证书处理；
- 代理和系统网络配置；
- socket、线程/事件循环与平台回调；
- 真正发送和接收字节。

Ktor 没有在 iPhone 里偷偷启动一台 JVM。共同 Kotlin 会为 iOS 编译成 Native 代码，Darwin engine 再连接 Apple 平台网络能力；Android/JVM 则生成 JVM 字节码，使用各自 engine。

所以更准确的说法是：

> Ktor 统一客户端管线，engine 负责把这条管线落到具体平台。

## 第一步：先设计不认识 Ktor 的公共契约

我们没有先写 `HttpClient.get()`，而是先问：调用方真正需要知道什么？

```kotlin
interface ExperimentRemoteDataSource {
    suspend fun fetchExperiments(): RemoteExperimentsResult
}
```

成功时返回共同业务模型：

```kotlin
data class RemoteExperiment(
    val id: String,
    val title: String,
    val hypothesis: String,
    val updatedAtEpochMilliseconds: Long,
)
```

失败时返回稳定分类：

```kotlin
enum class RemoteFailureReason {
    UNAUTHORIZED,
    SERVER,
    UNEXPECTED_STATUS,
    MALFORMED_RESPONSE,
    CONNECTION,
}

sealed interface RemoteExperimentsResult {
    data class Success(
        val experiments: List<RemoteExperiment>,
    ) : RemoteExperimentsResult

    data class Failure(
        val reason: RemoteFailureReason,
        val statusCode: Int? = null,
    ) : RemoteExperimentsResult
}
```

这里故意没有出现：

- `HttpResponse`；
- `HttpStatusCode`；
- `ResponseException`；
- Java `IOException`；
- Darwin 的 `NSError`；
- 服务端 JSON DTO。

为什么？因为它们是传输实现的语言，不是调用方的业务语言。

Store 关心“需要重新登录”“服务暂时不可用”“响应不兼容”，不应该知道 Ktor 用了哪个异常类。否则 Ktor 一旦升级、替换，或者某个平台出现不同底层异常，变化会沿着 Repository、UseCase、State 一路扩散到 UI。

这就是网络层的第一条边界：

> 可以共享基础设施实现，但公共契约仍应由业务需要决定。

## 第二步：把 plugin 与 runtime 分清

根构建脚本声明 serialization compiler plugin：

```kotlin
plugins {
    kotlin("multiplatform") version "2.4.21" apply false
    kotlin("plugin.serialization") version "2.4.21" apply false
}
```

shared module 启用它：

```kotlin
plugins {
    kotlin("multiplatform")
    kotlin("plugin.serialization")
}
```

然后在 `commonMain` 加入 Ktor 管线与 JSON runtime：

```kotlin
commonMain.dependencies {
    implementation("io.ktor:ktor-client-core:3.6.1")
    implementation("io.ktor:ktor-client-content-negotiation:3.6.1")
    implementation("io.ktor:ktor-serialization-kotlinx-json:3.6.1")
    implementation(
        "org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0"
    )
}
```

这里有两个独立版本概念：

1. `kotlin("plugin.serialization")` 是编译器插件，版本跟 Kotlin plugin 对齐。
2. `kotlinx-serialization-json` 是运行时库，有自己的发布节奏。

它们都叫 serialization，但不是“版本号必须完全相同的同一个东西”。编译器插件为 `@Serializable` 类型生成 serializer；runtime 提供 JSON 编解码 API 与格式实现。

截至本文验证日期，Ktor 3.6.1 是正式发布版本，kotlinx.serialization 1.11.0 是稳定版本。版本敏感结论应以官方 release 与兼容说明为准，而不是从旧博客复制一组数字。

## 第三步：DTO 不是领域模型的另一种拼写

服务端 JSON 是：

```json
{
  "id": "remote-1",
  "title": "Battery experiment",
  "hypothesis": "Dark mode uses less energy",
  "updated_at_ms": 1700000000123,
  "future_server_field": "ignored"
}
```

网络实现内部定义 DTO：

```kotlin
@Serializable
private data class ExperimentResponseDto(
    val id: String,
    val title: String,
    val hypothesis: String,
    @SerialName("updated_at_ms")
    val updatedAtEpochMilliseconds: Long,
) {
    fun toDomain(): RemoteExperiment = RemoteExperiment(
        id = id,
        title = title,
        hypothesis = hypothesis,
        updatedAtEpochMilliseconds = updatedAtEpochMilliseconds,
    )
}
```

DTO 是远端协议在客户端的投影；领域模型是应用愿意承诺的语义。此刻字段相似，不代表它们应该是同一个类型。

分开后，我们才有地方处理：

- 服务端字段重命名；
- 单位转换；
- 旧字段兼容；
- 可空值默认策略；
- 多个 DTO 合并成一个领域模型；
- 服务端枚举到稳定领域枚举的映射。

如果把 `@SerialName` 洒满领域模型，远端 schema 会逐渐变成整个 App 的内部结构。

DTO 被声明为 `private` 不是为了炫技，而是在编译期写下边界：调用方不可能意外依赖它。

## 第四步：共同配置 HttpClient 管线

Android 开发者可以把 Ktor 的 plugin 想成一条可组合的客户端拦截管线，但不要强行把每个概念与 OkHttp interceptor 一一对应。

本实验的共同默认配置是：

```kotlin
internal fun <T : HttpClientEngineConfig>
    HttpClientConfig<T>.installPocketLabDefaults() {
    expectSuccess = false
    install(ContentNegotiation) {
        json(
            Json {
                ignoreUnknownKeys = true
            },
        )
    }
}
```

`ContentNegotiation` 根据响应的 Content-Type 选择转换器，JSON 转换器再调用 kotlinx.serialization 生成的 serializer，把 body 解码成指定类型。

### 为什么忽略未知字段？

移动客户端不会与服务端同时升级。

服务端今天增加一个旧客户端用不到的字段，不应该让已经安装在用户手机里的版本突然无法解码。`ignoreUnknownKeys = true` 允许协议做兼容性扩展。

但这不等于“什么坏数据都吞掉”。如果 `id` 或 `updated_at_ms` 这样的必填字段缺失、类型错误，解码仍然失败。策略是：

> 对新增信息宽容，对既有承诺严格。

### 为什么关闭 `expectSuccess`？

Ktor 在 `expectSuccess = true` 时会根据非成功状态抛出响应异常。那并不是错，但本实验希望在一个地方显式完成状态码分类：

```kotlin
if (response.status == HttpStatusCode.OK) {
    // decode
} else {
    response.status.toFailure()
}
```

这样 401、503 和 418 都先作为服务器已经成功返回的 HTTP 响应被观察，再映射到稳定结果。

HTTP 失败响应与“根本没有收到 HTTP 响应”是两回事。关闭自动抛出可以让这个区别在代码中非常醒目。

## 第五步：共同代码只写一次请求

真正的请求实现很短：

```kotlin
class PocketLabNetwork internal constructor(
    private val client: HttpClient,
    baseUrl: String,
) : ExperimentRemoteDataSource {
    private val rootUrl = baseUrl.trimEnd('/')

    override suspend fun fetchExperiments(): RemoteExperimentsResult = try {
        val response = client.get("$rootUrl/v1/experiments")

        if (response.status == HttpStatusCode.OK) {
            RemoteExperimentsResult.Success(
                response.body<List<ExperimentResponseDto>>()
                    .map { it.toDomain() },
            )
        } else {
            response.status.toFailure()
        }
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (_: ContentConvertException) {
        RemoteExperimentsResult.Failure(
            RemoteFailureReason.MALFORMED_RESPONSE,
        )
    } catch (_: SerializationException) {
        RemoteExperimentsResult.Failure(
            RemoteFailureReason.MALFORMED_RESPONSE,
        )
    } catch (_: IOException) {
        RemoteExperimentsResult.Failure(
            RemoteFailureReason.CONNECTION,
        )
    }
}
```

它负责的是协议事实：请求哪个 endpoint、什么响应才解码、怎样把传输世界翻译成共同结果。

它不负责：

- 在 UI 上显示中文还是英文；
- 401 时弹登录页还是静默刷新 token；
- 503 是否立刻重试；
- 数据是否先落本地数据库；
- 冲突时保留远端还是本地版本。

这些属于认证、同步、Repository 或产品状态机。网络层不应该因为“最先看见错误”就包办全部决策。

## 第六步：相同配置，注入不同 engine

共同代码只声明一个很薄的工厂：

```kotlin
internal expect fun createPlatformHttpClient(): HttpClient
```

JVM 使用 CIO：

```kotlin
internal actual fun createPlatformHttpClient(): HttpClient =
    HttpClient(CIO) {
        installPocketLabDefaults()
    }
```

Android 使用 Android engine：

```kotlin
internal actual fun createPlatformHttpClient(): HttpClient =
    HttpClient(Android) {
        installPocketLabDefaults()
    }
```

iOS x64 使用 Darwin engine：

```kotlin
internal actual fun createPlatformHttpClient(): HttpClient =
    HttpClient(Darwin) {
        installPocketLabDefaults()
    }
```

相应依赖只进入平台 source set：

```kotlin
jvmMain.dependencies {
    implementation("io.ktor:ktor-client-cio:3.6.1")
}

androidMain.dependencies {
    implementation("io.ktor:ktor-client-android:3.6.1")
}

iosX64Main.dependencies {
    implementation("io.ktor:ktor-client-darwin:3.6.1")
}
```

这正是上一篇“插座原则”的延续。

领域行为没有变成 `expect/actual`。只有“在这个 target 上用什么 engine 创建默认 client”这一件薄、唯一、编译期确定的平台事实使用它。

项目也可以把 `HttpClient` 从平台装配层直接注入，不用 `expect/actual`。本实验选择工厂，是为了明确展示 source set 与 engine 的对应关系；当一个平台需要多个 client、测试替换或不同租户配置时，显式依赖注入通常更灵活。

## 三种 engine 意味着什么？

Engine 不是换了一个类名那么简单。它决定 Ktor 请求最终怎样进入平台网络栈。

### CIO：JVM 的协程 I/O engine

本项目的纯 JVM target 用 CIO 提供快速反馈。它不等于 Android，也不应该被误写成“Android 测试已经覆盖”。JVM target 的价值是便宜、快速地验证共同逻辑。

### Android engine：Android 平台实现

Android target 明确使用 Android engine。这使平台依赖和真实装配可见，也方便未来处理 Android 特有的网络安全配置、权限与调试。

### Darwin engine：Apple 平台实现

iOS target 使用 Darwin engine。请求仍是共同的 `client.get()`，但底层会进入 Darwin/Apple 网络能力，而不是把 CIO 复制进 iPhone。

这就是 KMP 最值得反复练习的结构：

```text
共同语义稳定
      +
平台机制可替换
```

Ktor 文档也支持让依赖体系选择可用默认 engine。那对快速开始很方便。本实验显式写出三套 engine，是为了让装配不被 classpath 隐藏；生产项目可以根据复杂度选择，但要确保选择是可观察、可测试的。

## 失败不是一个 Boolean，而是一组不同事实

看看四种场景：

### 场景一：503

DNS 成功、连接成功、服务器也确实返回了合法 HTTP 响应，只是服务暂时不可用。

```kotlin
RemoteExperimentsResult.Failure(
    reason = RemoteFailureReason.SERVER,
    statusCode = 503,
)
```

### 场景二：418

客户端收到了响应，但当前协议没有定义它的业务含义：

```kotlin
Failure(UNEXPECTED_STATUS, statusCode = 418)
```

它不能被误报成 CONNECTION，否则排查时会让人去检查 Wi-Fi，而服务器明明已经回话。

### 场景三：200 + 坏 JSON

HTTP 层成功，但 payload 不满足客户端协议：

```kotlin
Failure(MALFORMED_RESPONSE)
```

这通常指向服务端契约漂移、灰度版本不兼容或数据损坏。它与断网的告警、重试策略都不同。

### 场景四：传输中断

可能是离线、DNS 失败、连接拒绝、超时或底层 engine 错误。当前最小模型统一为：

```kotlin
Failure(CONNECTION)
```

这还不够生产级。将来可以进一步区分 offline、timeout、TLS、rate limit 和可重试性。但“暂时分类较粗”和“所有东西都叫网络错误”不是一回事：前者是明确、可演进的模型，后者是丢失事实。

## `CancellationException` 为什么必须单独处理？

网络请求是 suspend 操作。页面离开、任务超时或父 Scope 取消时，协程通过取消信号结束。

如果宽泛地写成：

```kotlin
catch (_: Exception) {
    Failure(CONNECTION)
}
```

取消可能被包装成一个看起来可重试的网络失败。上层会收到“断网”，甚至启动重试，而用户其实已经离开页面。

正确顺序是：

```kotlin
catch (cancellation: CancellationException) {
    throw cancellation
} catch (_: IOException) {
    Failure(CONNECTION)
}
```

本实验的最终实现捕获跨平台 `kotlinx.io.IOException`，而不是所有 `Exception`。这样传输失败会映射为 `CONNECTION`，编程错误和未建模异常则不会被伪装成断网。取消属于结构化并发的控制流，不属于业务错误流。

这个原则与上一篇 StateFlow 实验完全一致：无论异常发生在 Repository 还是 Ktor，请不要把协程生命周期信号吞成普通失败。

## HttpClient 不是“每次请求 new 一个”

`HttpClient` 持有 engine、连接资源与插件状态，应该被复用，而不是每个 API 方法创建一次。

本实验让 `PocketLabNetwork` 拥有 client：

```kotlin
fun close() {
    client.close()
}
```

公开工厂创建完整实例：

```kotlin
fun createPlatformPocketLabNetwork(baseUrl: String): PocketLabNetwork =
    PocketLabNetwork(createPlatformHttpClient(), baseUrl)
```

真实 App 中，应用装配层可以按 application、登录会话或功能组件的生命周期持有它，并在所有者结束时关闭。

这里的关键不是一定要用某个 DI 框架，而是回答三个问题：

1. 谁创建 client？
2. 谁复用它？
3. 谁负责关闭？

如果三个答案都是“不知道”，迟早会得到资源泄漏、连接无法复用或测试难以隔离的问题。

## 用 MockEngine 测试管线，而不是测试互联网

网络单元测试最不应该依赖的东西，就是互联网。

Ktor 的 MockEngine 位于 commonTest 依赖：

```kotlin
commonTest.dependencies {
    implementation("io.ktor:ktor-client-mock:3.6.1")
}
```

测试创建使用相同共同配置的 client：

```kotlin
private fun createMockNetwork(
    handler: suspend MockRequestHandleScope.(
        HttpRequestData,
    ) -> HttpResponseData,
): PocketLabNetwork {
    val client = HttpClient(MockEngine(handler)) {
        installPocketLabDefaults()
    }
    return PocketLabNetwork(client, "https://api.pocketlab.test/")
}
```

成功测试既检查请求，也检查协议解析：

```kotlin
val network = createMockNetwork { request ->
    observedUrl = request.url.toString()
    observedMethod = request.method

    respond(
        content = validJson,
        status = HttpStatusCode.OK,
        headers = jsonHeaders,
    )
}

val result = network.fetchExperiments()

assertEquals(
    "https://api.pocketlab.test/v1/experiments",
    observedUrl,
)
assertEquals(HttpMethod.Get, observedMethod)
assertIs<RemoteExperimentsResult.Success>(result)
```

这比 fake `ExperimentRemoteDataSource` 多验证了一层：

- URL 是否拼对；
- method 是否正确；
- response Content-Type 是否触发 JSON converter；
- `@SerialName` 是否生效；
- 未知字段是否被忽略；
- DTO 是否正确映射为领域模型。

失败测试则让 engine 返回 401、503、418、坏 JSON、I/O 异常和取消信号，逐一验证分类。

## 同一份 commonTest，三端执行 24 次

新增八条网络测试：

1. 成功响应、URL、method、未知字段与 DTO 映射。
2. 401 → `UNAUTHORIZED`。
3. 503 → `SERVER`。
4. 418 → `UNEXPECTED_STATUS`。
5. 坏 JSON → `MALFORMED_RESPONSE`。
6. 传输异常 → `CONNECTION`。
7. 取消继续抛出。
8. 当前 target 的真实 engine 能创建并关闭。

运行：

```shell
./gradlew :shared:allTests
```

结果：

| 目标 | 网络层测试 | 项目累计 | 失败/错误/跳过 |
|---|---:|---:|---:|
| JVM | 8 | 27 | 0 |
| Android Host | 8 | 27 | 0 |
| iOS x64 Simulator | 8 | 27 | 0 |

共同网络测试在三个 target 共执行 24 次；累计 81/81 通过。

这里有一个很重要的措辞：

> 我们验证了共同请求管线在三个 target 的 compilation/runtime 中工作，不等于验证了三端真实联网行为。

MockEngine 不会证明：

- TLS 握手正确；
- 证书 pinning 正确；
- Android Manifest 已声明网络权限；
- iOS ATS 配置正确；
- 系统代理、重定向和 cookie 行为符合预期；
- 真正服务端仍遵守 schema。

“真实 engine 能创建和关闭”的测试，只证明平台装配与依赖完整，不证明它成功访问了服务器。

专家级测试不是把所有测试都变成端到端，而是为每一层准确命名它证明了什么。

后续应再补两类测试：

1. 少量设备/Simulator 集成测试，验证真实 engine 与测试服务器。
2. 服务端契约测试，验证 schema 与兼容策略。

它们不能取代快速、确定、覆盖错误分支的 commonTest；commonTest 也不能冒充它们。

## 为什么这些 Ktor 依赖都是 `implementation`？

上一篇里，StateFlow 出现在公共 API，因此 coroutines Core 使用 `api`。

这次公共契约没有暴露 Ktor 类型：

```kotlin
suspend fun fetchExperiments(): RemoteExperimentsResult
```

`HttpClient`、`HttpResponse`、Content Negotiation 与 DTO 都是实现细节，所以 Ktor 依赖使用 `implementation`。

这是一个很实用的边界检查方法：

> 如果把某个依赖从消费方编译 classpath 隐藏后，公共 API 就无法解析，说明你已经把它暴露出去了。

不要机械背“库模块都用 api”或“所有依赖都用 implementation”。看公开签名事实。

## MockEngine 与 fake Repository，该选谁？

两者验证不同边界。

### fake `ExperimentRemoteDataSource`

适合测试 UseCase、Store 和同步状态机。它快速、与 HTTP 无关，可以精确安排业务结果。

### Ktor MockEngine

适合测试网络实现：URL、headers、状态码、Content Negotiation、序列化与 DTO 映射。

### 真实测试服务器

适合验证 engine、TLS、认证中间件和客户端—服务端契约。

测试替身不是越“真实”越高级。选择与待验证边界最贴近的那一层。

## 六个常见反模式

### 1. 公共接口直接返回 `HttpResponse`

调用方被迫理解 Ktor，状态码处理散落各处，替换网络实现会成为破坏性变更。

### 2. DTO 直接充当领域模型

服务端字段、可空策略和版本兼容污染整个业务层，远端 schema 的每次变化都扩大影响面。

### 3. `catch (Exception)` 把所有东西变成“断网”

503、坏 JSON、编程错误和取消被抹平成同一状态，既误导用户也破坏可观测性。

### 4. 每个请求创建一个 HttpClient

连接和 engine 资源无法合理复用，生命周期也没人负责。

### 5. 单元测试访问公网

DNS、服务状态和网络质量让测试随机失败；错误分支又很难稳定构造。

### 6. 因为 Ktor 跨平台，就假设平台差异消失

证书、代理、权限、后台策略和调试工具仍然属于平台。共同 API 降低重复，不会消灭操作系统。

## 什么时候不该强行共享网络实现？

Ktor 很适合协议与业务语义高度一致的客户端，但共享不是宗教。

以下情况值得重新评估：

- 某端必须使用高度定制、生态绑定很深的网络 SDK；
- 两端协议本身不同，而不是偶尔的平台配置不同；
- 已有成熟原生网络层，迁移收益低于互操作和组织成本；
- 安全、流媒体或后台传输必须深度使用平台专属 API；
- 团队所有权要求原生团队独立发布网络层。

即使不共享具体请求，仍可以共享领域接口、错误语义和契约测试思路。

成熟的 KMP 决策不是“能放 commonMain 就放”，而是寻找最稳定的共同变化轴。

## 一张表收住边界

| 层次 | 本实验共享吗 | 原因 |
|---|---|---|
| endpoint 与 HTTP method | 共享 | 两端协议相同 |
| JSON DTO 与映射 | 共享但私有 | 协议相同，不向领域泄漏 |
| 状态码分类 | 共享 | 产品语义一致 |
| Ktor client plugins | 共享配置 | 管线策略一致 |
| CIO / Android / Darwin engine | 分平台 | 底层机制不同 |
| 证书、代理、权限 | 分平台/按需配置 | 系统能力与政策不同 |
| UI 错误文案 | 不共享 | 本地化与交互属于平台 UI |
| 重试与离线同步 | 尚未决定 | 需要更高层产品语义 |

## 最后的心智模型

- Ktor Client 是共同请求管线，不是一套取代操作系统的网络栈。
- Engine 把共同请求落到具体 target 的网络能力。
- DTO 表达远端协议，领域模型表达应用承诺。
- HTTP 失败、解析失败、传输失败和取消是四类不同事实。
- `expect/actual` 适合薄引擎工厂，不适合把整个网络层复制三遍。
- HttpClient 的创建、复用与关闭必须有明确所有者。
- MockEngine 验证请求管线；真实 engine 集成需要另一层测试。
- “三端都编译”只是起点，“三端对同一协议得到同一语义”才是共享网络的价值。

下一篇，我们会把远端数据放回 PocketLab 的真正主线：离线优先。

届时问题会从“怎样发请求”升级为：本地数据库才是 UI 的事实来源时，网络结果如何合并？断网操作怎样排队？同步失败怎样恢复？冲突到底由谁解释？

那将是 Repository 从接口变成架构边界的时刻。

## 动手挑战

给 429 Too Many Requests 增加 `RATE_LIMITED`：

1. 读取可选的 `Retry-After`。
2. 不把 Ktor headers 类型放进公共结果。
3. 用 MockEngine 覆盖 header 存在、缺失和非法三种情况。
4. 决定“何时重试”应该位于网络层、Repository、同步队列还是 UI，并写下理由。
5. 保证取消发生时不会进入重试。

最后再问自己一句：如果服务端给出 HTTP 日期而不是秒数，你的公共模型应该保存字符串、epoch，还是一个更高层的“最早重试时间”？

这个答案，就是协议 DTO 与领域模型为什么不能偷懒合并。

## 参考资料

- [Ktor Client engines](https://ktor.io/docs/client-engines.html)
- [Ktor Client serialization and ContentNegotiation](https://ktor.io/docs/client-serialization.html)
- [Ktor releases](https://github.com/ktorio/ktor/releases)
- [kotlinx.serialization 入门](https://kotlinlang.org/docs/serialization.html)
- [kotlinx.serialization releases](https://github.com/Kotlin/kotlinx.serialization/releases)
- [Kotlin Multiplatform expect/actual](https://kotlinlang.org/docs/multiplatform/multiplatform-expect-actual.html)
- [Ktor MockEngine API](https://api.ktor.io/ktor-client/ktor-client-mock/io.ktor.client.engine.mock/-mock-engine/index.html)
