package dev.kmpjourney.pocketlab

import kotlinx.coroutines.flow.Flow

enum class ExperimentSyncState {
    SYNCED,
    PENDING_UPLOAD,
}

data class ExperimentRecord(
    val id: String,
    val title: String,
    val hypothesis: String,
    val notes: String,
    val updatedAtEpochMilliseconds: Long,
    val syncState: ExperimentSyncState,
)

/** A platform-neutral snapshot that is safe to merge into the local source of truth. */
data class ServerExperimentSnapshot(
    val id: String,
    val title: String,
    val hypothesis: String,
    val notes: String,
    val updatedAtEpochMilliseconds: Long,
)

interface ExperimentLocalStore : ExperimentRepository {
    fun observeAll(): Flow<List<ExperimentRecord>>

    suspend fun pendingUploads(): List<ExperimentRecord>

    /**
     * Merges a server snapshot atomically and returns the number of inserted or replaced rows.
     * Pending local writes always win until the upload protocol acknowledges them.
     */
    suspend fun mergeServerSnapshot(experiments: List<ServerExperimentSnapshot>): Int
}
