# 实验 06：同一个请求，三套 Ktor 引擎

本实验继续使用累计工程：

```text
../02-first-kmp-module/shared
```

核心代码：

```text
src/commonMain/kotlin/dev/kmpjourney/pocketlab/network/
├── ExperimentRemoteDataSource.kt
└── PocketLabNetwork.kt

src/jvmMain/.../network/PlatformHttpClient.jvm.kt
src/androidMain/.../network/PlatformHttpClient.android.kt
src/iosX64Main/.../network/PlatformHttpClient.ios.kt
src/commonTest/.../network/PocketLabNetworkTest.kt
```

## 实验目标

- 在 `commonMain` 只写一次请求、JSON 解码与错误映射。
- 让 JVM 使用 CIO、Android 使用 Android engine、iOS 使用 Darwin engine。
- 不把 Ktor 类型、DTO 或平台异常泄漏到公共数据源契约。
- 用 MockEngine 在三端确定性验证成功与失败路径。
- 让 `HttpClient` 的生命周期所有权显式可见。

## 请求管线

```text
ExperimentRemoteDataSource
          |
          v
共同 GET /v1/experiments
          |
          v
ContentNegotiation + kotlinx.serialization
          |
          +-------- JVM --------> CIO
          +-------- Android ----> Android engine
          `-------- iOS x64 ----> Darwin / NSURLSession
```

Ktor 统一请求 API 与插件管线；真正发出字节的仍是目标平台对应引擎。

## 依赖配置

```kotlin
plugins {
    kotlin("plugin.serialization")
}

commonMain.dependencies {
    implementation("io.ktor:ktor-client-core:3.6.1")
    implementation("io.ktor:ktor-client-content-negotiation:3.6.1")
    implementation("io.ktor:ktor-serialization-kotlinx-json:3.6.1")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
}

commonTest.dependencies {
    implementation("io.ktor:ktor-client-mock:3.6.1")
}

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

这些库没有出现在公开接口签名中，因此使用 `implementation`。serialization Gradle plugin 版本跟随 Kotlin 2.4.21；JSON runtime 是独立发布的 1.11.0。

## 稳定的公共契约

公共接口返回业务可理解的结果，而不是 `HttpResponse`：

```kotlin
interface ExperimentRemoteDataSource {
    suspend fun fetchExperiments(): RemoteExperimentsResult
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

当前分类为鉴权失败、服务端失败、未预期状态、响应格式错误和连接失败。取消不是失败结果，必须继续传播。

## 运行

```shell
cd ../02-first-kmp-module
./gradlew :shared:allTests
```

## 已验证结果（2026-11-18）

| 目标 | 实验前 | 网络层新测试 | 当前总数 | 失败/错误/跳过 |
|---|---:|---:|---:|---:|
| JVM | 19 | 8 | 27 | 0 |
| Android Host | 19 | 8 | 27 | 0 |
| iOS x64 Simulator | 19 | 8 | 27 | 0 |

合计 81/81 通过。新增测试覆盖：

1. 请求 URL 与 GET method 正确，JSON 映射为领域模型，未知字段被忽略。
2. 401 映射为 `UNAUTHORIZED`。
3. 503 映射为 `SERVER`，并保留状态码。
4. 418 映射为 `UNEXPECTED_STATUS`，不会被误报为断网。
5. 非法 JSON 映射为 `MALFORMED_RESPONSE`。
6. 传输异常映射为 `CONNECTION`。
7. `CancellationException` 继续抛出。
8. 每个 target 的真实平台引擎都能创建和关闭。

## 这组测试没有证明什么

- 没有真的访问 PocketLab 服务端。
- 没有验证 TLS、证书、代理、重定向或 Android 网络权限。
- “引擎可创建”不等于设备网络集成已经完成。
- MockEngine 验证的是客户端请求/解码契约，不是服务端契约。

后续接入测试服务器时，应另建少量集成测试；不要让公网替代快速、确定的 commonTest。

## 观察点

1. 注释掉 `iosX64Main` 的 Darwin 依赖，观察 actual 文件为何无法编译。
2. 把 `ignoreUnknownKeys` 改为 `false`，运行成功响应测试，观察服务端新增字段怎样破坏旧客户端。
3. 把 `expectSuccess` 改回默认值，观察 401/503 在到达显式状态码分支前发生什么。
4. 在公共接口中临时返回 `HttpResponse`，观察 Ktor 依赖如何污染消费方 API。
5. 注释掉取消异常的单独 catch，观察它如何被误分类为网络失败。

## 挑战

为 429 Too Many Requests 增加稳定的 `RATE_LIMITED` 分类，并做到：

1. 从响应头读取可选 `Retry-After`，但不要把 Ktor header 类型暴露给领域层。
2. 为秒数和 HTTP 日期两种格式明确兼容策略。
3. 用 MockEngine 写 commonTest，不访问真实服务器。
4. 思考重试应该属于网络层、Repository、同步队列，还是 UI。
