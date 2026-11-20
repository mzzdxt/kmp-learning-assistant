package dev.kmpjourney.pocketlab.android

import dev.kmpjourney.pocketlab.ExperimentLocalStore
import dev.kmpjourney.pocketlab.ExperimentSyncState
import dev.kmpjourney.pocketlab.sync.RefreshExperimentsResult
import dev.kmpjourney.pocketlab.sync.RefreshExperimentsUseCase
import dev.kmpjourney.pocketlab.sync.RefreshFailureReason
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** The only Android production adapter that translates the KMP feature into host values. */
internal class KmpExperimentListGateway(
    private val localStore: ExperimentLocalStore,
    private val refreshExperiments: RefreshExperimentsUseCase,
) : ExperimentListGateway {
    override fun observeExperiments(): Flow<List<ExperimentListItem>> =
        localStore.observeAll().map { records ->
            records.map { record ->
                ExperimentListItem(
                    id = record.id,
                    title = record.title,
                    hypothesis = record.hypothesis,
                    notes = record.notes,
                    updatedAtEpochMilliseconds = record.updatedAtEpochMilliseconds,
                    syncStatus = when (record.syncState) {
                        ExperimentSyncState.SYNCED -> ExperimentSyncStatus.SYNCED
                        ExperimentSyncState.PENDING_UPLOAD -> ExperimentSyncStatus.PENDING_UPLOAD
                    },
                )
            }
        }

    override suspend fun refresh(): ExperimentRefreshResult =
        when (val result = refreshExperiments.execute()) {
            is RefreshExperimentsResult.Applied ->
                ExperimentRefreshResult.Applied(result.changedCount)

            is RefreshExperimentsResult.Failed ->
                ExperimentRefreshResult.Failed(
                    reason = result.reason.toHostFailure(),
                    statusCode = result.statusCode,
                )
        }
}

private fun RefreshFailureReason.toHostFailure(): ExperimentRefreshFailure = when (this) {
    RefreshFailureReason.UNAUTHORIZED -> ExperimentRefreshFailure.UNAUTHORIZED
    RefreshFailureReason.SERVER -> ExperimentRefreshFailure.SERVER
    RefreshFailureReason.UNEXPECTED_STATUS -> ExperimentRefreshFailure.UNEXPECTED_STATUS
    RefreshFailureReason.MALFORMED_RESPONSE -> ExperimentRefreshFailure.MALFORMED_RESPONSE
    RefreshFailureReason.CONNECTION -> ExperimentRefreshFailure.CONNECTION
}
