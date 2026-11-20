package dev.kmpjourney.pocketlab.android

import kotlinx.coroutines.flow.Flow

/**
 * Android-host contract for the experiment-list vertical slice.
 *
 * Existing UI and ViewModels depend on these host-owned values. A legacy data
 * source or the KMP adapter can sit behind the same seam during migration.
 */
internal interface ExperimentListGateway {
    fun observeExperiments(): Flow<List<ExperimentListItem>>

    suspend fun refresh(): ExperimentRefreshResult
}

data class ExperimentListItem(
    val id: String,
    val title: String,
    val hypothesis: String,
    val notes: String,
    val updatedAtEpochMilliseconds: Long,
    val syncStatus: ExperimentSyncStatus,
)

enum class ExperimentSyncStatus {
    SYNCED,
    PENDING_UPLOAD,
}

sealed interface ExperimentRefreshResult {
    data class Applied(val changedCount: Int) : ExperimentRefreshResult

    data class Failed(
        val reason: ExperimentRefreshFailure,
        val statusCode: Int?,
    ) : ExperimentRefreshResult
}

enum class ExperimentRefreshFailure {
    UNAUTHORIZED,
    SERVER,
    UNEXPECTED_STATUS,
    MALFORMED_RESPONSE,
    CONNECTION,
}
