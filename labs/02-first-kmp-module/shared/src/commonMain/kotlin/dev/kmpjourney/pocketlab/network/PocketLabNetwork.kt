package dev.kmpjourney.pocketlab.network

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.call.body
import io.ktor.client.engine.HttpClientEngineConfig
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.ContentConvertException
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CancellationException
import kotlinx.io.IOException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

internal class PocketLabNetwork(
    private val client: HttpClient,
    baseUrl: String,
) : ExperimentRemoteDataSource {
    private val rootUrl = baseUrl.trimEnd('/')

    init {
        require(rootUrl.startsWith("https://") || rootUrl.startsWith("http://")) {
            "baseUrl must start with http:// or https://"
        }
    }

    override suspend fun fetchExperiments(): RemoteExperimentsResult = try {
        val response = client.get("$rootUrl/v1/experiments")
        if (response.status == HttpStatusCode.OK) {
            RemoteExperimentsResult.Success(
                experiments = response.body<List<ExperimentResponseDto>>().map { it.toDomain() },
            )
        } else {
            response.status.toFailure()
        }
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (_: ContentConvertException) {
        RemoteExperimentsResult.Failure(RemoteFailureReason.MALFORMED_RESPONSE)
    } catch (_: SerializationException) {
        RemoteExperimentsResult.Failure(RemoteFailureReason.MALFORMED_RESPONSE)
    } catch (_: IOException) {
        RemoteExperimentsResult.Failure(RemoteFailureReason.CONNECTION)
    }

    fun close() {
        client.close()
    }
}

internal fun createPlatformPocketLabNetwork(baseUrl: String): PocketLabNetwork =
    PocketLabNetwork(createPlatformHttpClient(), baseUrl)

internal expect fun createPlatformHttpClient(): HttpClient

internal fun <T : HttpClientEngineConfig> HttpClientConfig<T>.installPocketLabDefaults() {
    expectSuccess = false
    install(ContentNegotiation) {
        json(
            Json {
                ignoreUnknownKeys = true
            },
        )
    }
}

@Serializable
private data class ExperimentResponseDto(
    val id: String,
    val title: String,
    val hypothesis: String,
    val notes: String = "",
    @SerialName("updated_at_ms")
    val updatedAtEpochMilliseconds: Long,
) {
    fun toDomain(): RemoteExperiment = RemoteExperiment(
        id = id,
        title = title,
        hypothesis = hypothesis,
        notes = notes,
        updatedAtEpochMilliseconds = updatedAtEpochMilliseconds,
    )
}

private fun HttpStatusCode.toFailure(): RemoteExperimentsResult.Failure =
    RemoteExperimentsResult.Failure(
        reason = when (value) {
            HttpStatusCode.Unauthorized.value -> RemoteFailureReason.UNAUTHORIZED
            in 500..599 -> RemoteFailureReason.SERVER
            else -> RemoteFailureReason.UNEXPECTED_STATUS
        },
        statusCode = value,
    )

