package dev.kmpjourney.pocketlab.network

data class RemoteExperiment(
    val id: String,
    val title: String,
    val hypothesis: String,
    val notes: String,
    val updatedAtEpochMilliseconds: Long,
)

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

interface ExperimentRemoteDataSource {
    suspend fun fetchExperiments(): RemoteExperimentsResult
}

