package dev.kmpjourney.pocketlab.network

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class PocketLabNetworkTest {
    @Test
    fun successDecodesDtoMapsDomainAndIgnoresUnknownFields() = runTest {
        var observedUrl: String? = null
        var observedMethod: HttpMethod? = null
        val network = createMockNetwork { request ->
            observedUrl = request.url.toString()
            observedMethod = request.method
            respondJson(
                content = """
                    [
                      {
                        "id": "remote-1",
                        "title": "Battery experiment",
                        "hypothesis": "Dark mode uses less energy",
                        "updated_at_ms": 1700000000123,
                        "future_server_field": "ignored"
                      }
                    ]
                """.trimIndent(),
            )
        }

        try {
            val result = assertIs<RemoteExperimentsResult.Success>(
                network.fetchExperiments(),
            )

            assertEquals("https://api.pocketlab.test/v1/experiments", observedUrl)
            assertEquals(HttpMethod.Get, observedMethod)
            assertEquals(
                listOf(
                    RemoteExperiment(
                        id = "remote-1",
                        title = "Battery experiment",
                        hypothesis = "Dark mode uses less energy",
                        notes = "",
                        updatedAtEpochMilliseconds = 1_700_000_000_123,
                    ),
                ),
                result.experiments,
            )
        } finally {
            network.close()
        }
    }

    @Test
    fun unauthorizedResponseBecomesStableFailure() = runTest {
        val network = createMockNetwork {
            respondJson("{}", HttpStatusCode.Unauthorized)
        }

        try {
            assertEquals(
                RemoteExperimentsResult.Failure(
                    reason = RemoteFailureReason.UNAUTHORIZED,
                    statusCode = 401,
                ),
                network.fetchExperiments(),
            )
        } finally {
            network.close()
        }
    }

    @Test
    fun serverResponsePreservesStatusWithoutLeakingKtorTypes() = runTest {
        val network = createMockNetwork {
            respondJson("{}", HttpStatusCode.ServiceUnavailable)
        }

        try {
            assertEquals(
                RemoteExperimentsResult.Failure(
                    reason = RemoteFailureReason.SERVER,
                    statusCode = 503,
                ),
                network.fetchExperiments(),
            )
        } finally {
            network.close()
        }
    }

    @Test
    fun otherHttpStatusIsNotMisclassifiedAsConnectionFailure() = runTest {
        val network = createMockNetwork {
            respondJson("{}", HttpStatusCode(418, "I'm a teapot"))
        }

        try {
            assertEquals(
                RemoteExperimentsResult.Failure(
                    reason = RemoteFailureReason.UNEXPECTED_STATUS,
                    statusCode = 418,
                ),
                network.fetchExperiments(),
            )
        } finally {
            network.close()
        }
    }

    @Test
    fun malformedJsonIsDifferentFromTransportFailure() = runTest {
        val network = createMockNetwork {
            respondJson("[{\"id\":")
        }

        try {
            assertEquals(
                RemoteExperimentsResult.Failure(RemoteFailureReason.MALFORMED_RESPONSE),
                network.fetchExperiments(),
            )
        } finally {
            network.close()
        }
    }

    @Test
    fun transportExceptionBecomesConnectionFailure() = runTest {
        val network = createMockNetwork {
            throw IOException("offline")
        }

        try {
            assertEquals(
                RemoteExperimentsResult.Failure(RemoteFailureReason.CONNECTION),
                network.fetchExperiments(),
            )
        } finally {
            network.close()
        }
    }

    @Test
    fun cancellationIsRethrownInsteadOfBecomingConnectionFailure() = runTest {
        val network = createMockNetwork {
            throw CancellationException("screen closed")
        }

        try {
            assertFailsWith<CancellationException> {
                network.fetchExperiments()
            }
        } finally {
            network.close()
        }
    }

    @Test
    fun targetEngineCanBeCreatedAndClosed() {
        val network = createPlatformPocketLabNetwork("https://api.pocketlab.test")
        network.close()
        assertTrue(true)
    }

    private fun createMockNetwork(
        handler: suspend io.ktor.client.engine.mock.MockRequestHandleScope.(
            io.ktor.client.request.HttpRequestData,
        ) -> io.ktor.client.request.HttpResponseData,
    ): PocketLabNetwork {
        val client = HttpClient(MockEngine(handler)) {
            installPocketLabDefaults()
        }
        return PocketLabNetwork(client, "https://api.pocketlab.test/")
    }

    private fun io.ktor.client.engine.mock.MockRequestHandleScope.respondJson(
        content: String,
        status: HttpStatusCode = HttpStatusCode.OK,
    ) = respond(
        content = content,
        status = status,
        headers = headersOf(
            HttpHeaders.ContentType,
            ContentType.Application.Json.toString(),
        ),
    )
}

