package dev.kmpjourney.pocketlab.sync

import dev.kmpjourney.pocketlab.ExperimentLocalStore
import dev.kmpjourney.pocketlab.ServerExperimentSnapshot
import dev.kmpjourney.pocketlab.network.ExperimentRemoteDataSource
import dev.kmpjourney.pocketlab.network.RemoteExperimentsResult
import dev.kmpjourney.pocketlab.network.RemoteFailureReason

enum class RefreshFailureReason {
    UNAUTHORIZED,
    SERVER,
    UNEXPECTED_STATUS,
    MALFORMED_RESPONSE,
    CONNECTION,
}

sealed interface RefreshExperimentsResult {
    data class Applied(
        val receivedCount: Int,
        val changedCount: Int,
    ) : RefreshExperimentsResult

    data class Failed(
        val reason: RefreshFailureReason,
        val statusCode: Int? = null,
    ) : RefreshExperimentsResult
}

class RefreshExperimentsUseCase(
    private val remote: ExperimentRemoteDataSource,
    private val local: ExperimentLocalStore,
) {
    suspend fun execute(): RefreshExperimentsResult = when (val result = remote.fetchExperiments()) {
        is RemoteExperimentsResult.Success -> {
            val changedCount = local.mergeServerSnapshot(
                result.experiments.map { experiment ->
                    ServerExperimentSnapshot(
                        id = experiment.id,
                        title = experiment.title,
                        hypothesis = experiment.hypothesis,
                        notes = experiment.notes,
                        updatedAtEpochMilliseconds = experiment.updatedAtEpochMilliseconds,
                    )
                },
            )
            RefreshExperimentsResult.Applied(
                receivedCount = result.experiments.size,
                changedCount = changedCount,
            )
        }

        is RemoteExperimentsResult.Failure -> RefreshExperimentsResult.Failed(
            reason = result.reason.toRefreshFailure(),
            statusCode = result.statusCode,
        )
    }
}

private fun RemoteFailureReason.toRefreshFailure(): RefreshFailureReason = when (this) {
    RemoteFailureReason.UNAUTHORIZED -> RefreshFailureReason.UNAUTHORIZED
    RemoteFailureReason.SERVER -> RefreshFailureReason.SERVER
    RemoteFailureReason.UNEXPECTED_STATUS -> RefreshFailureReason.UNEXPECTED_STATUS
    RemoteFailureReason.MALFORMED_RESPONSE -> RefreshFailureReason.MALFORMED_RESPONSE
    RemoteFailureReason.CONNECTION -> RefreshFailureReason.CONNECTION
}
