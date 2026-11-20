@file:OptIn(kotlin.experimental.ExperimentalObjCRefinement::class)

package dev.kmpjourney.pocketlab.sync

import dev.kmpjourney.pocketlab.ExperimentLocalStore
import dev.kmpjourney.pocketlab.ServerExperimentSnapshot
import dev.kmpjourney.pocketlab.network.ExperimentRemoteDataSource
import dev.kmpjourney.pocketlab.network.RemoteExperimentsResult
import dev.kmpjourney.pocketlab.network.RemoteFailureReason
import kotlinx.coroutines.CancellationException
import kotlin.native.HiddenFromObjC

@HiddenFromObjC
enum class RefreshFailureReason {
    UNAUTHORIZED,
    SERVER,
    UNEXPECTED_STATUS,
    MALFORMED_RESPONSE,
    CONNECTION,
}

@HiddenFromObjC
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

@HiddenFromObjC
class RefreshExperimentsUseCase(
    private val remote: ExperimentRemoteDataSource,
    private val local: ExperimentLocalStore,
    private val diagnostics: RefreshDiagnostics = RefreshDiagnostics.None,
) {
    suspend fun execute(): RefreshExperimentsResult {
        val operation = diagnostics.beginSafely()
        return try {
            when (val result = remote.fetchExperiments()) {
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
                    operation.observeSafely {
                        applied(
                            receivedCount = result.experiments.size,
                            changedCount = changedCount,
                        )
                    }
                    RefreshExperimentsResult.Applied(
                        receivedCount = result.experiments.size,
                        changedCount = changedCount,
                    )
                }

                is RemoteExperimentsResult.Failure -> {
                    val reason = result.reason.toRefreshFailure()
                    operation.observeSafely { failed(reason, result.statusCode) }
                    RefreshExperimentsResult.Failed(
                        reason = reason,
                        statusCode = result.statusCode,
                    )
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
}

private fun RefreshDiagnostics.beginSafely(): RefreshDiagnosticOperation = try {
    begin()
} catch (_: Exception) {
    RefreshDiagnostics.None.begin()
}

private inline fun RefreshDiagnosticOperation.observeSafely(
    block: RefreshDiagnosticOperation.() -> Unit,
) {
    try {
        block()
    } catch (_: Exception) {
        // Observability is deliberately best-effort and cannot change product behavior.
    }
}

private fun RemoteFailureReason.toRefreshFailure(): RefreshFailureReason = when (this) {
    RemoteFailureReason.UNAUTHORIZED -> RefreshFailureReason.UNAUTHORIZED
    RemoteFailureReason.SERVER -> RefreshFailureReason.SERVER
    RemoteFailureReason.UNEXPECTED_STATUS -> RefreshFailureReason.UNEXPECTED_STATUS
    RemoteFailureReason.MALFORMED_RESPONSE -> RefreshFailureReason.MALFORMED_RESPONSE
    RemoteFailureReason.CONNECTION -> RefreshFailureReason.CONNECTION
}
