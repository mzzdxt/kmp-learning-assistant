package dev.kmpjourney.pocketlab.data

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import dev.kmpjourney.pocketlab.ExperimentLocalStore
import dev.kmpjourney.pocketlab.ExperimentRecord
import dev.kmpjourney.pocketlab.ExperimentSyncState
import dev.kmpjourney.pocketlab.SavedExperiment
import dev.kmpjourney.pocketlab.ServerExperimentSnapshot
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow

class SqlDelightExperimentRepository(
    private val database: PocketLabSqlDatabase,
    private val queryDispatcher: CoroutineDispatcher = Dispatchers.Default,
) : ExperimentLocalStore {
    private val queries = database.experimentQueries

    override fun observeAll(): Flow<List<ExperimentRecord>> =
        queries.selectAll(::mapExperiment)
            .asFlow()
            .mapToList(queryDispatcher)

    override suspend fun save(experiment: SavedExperiment) {
        database.transaction {
            put(
                id = experiment.id,
                title = experiment.title,
                hypothesis = experiment.hypothesis,
                notes = experiment.notes,
                updatedAtEpochMilliseconds = experiment.savedAtEpochMilliseconds,
                syncState = ExperimentSyncState.PENDING_UPLOAD,
            )
        }
    }

    override suspend fun pendingUploads(): List<ExperimentRecord> =
        queries.selectPendingUploads(::mapExperiment).executeAsList()

    override suspend fun mergeServerSnapshot(
        experiments: List<ServerExperimentSnapshot>,
    ): Int = database.transactionWithResult {
        var changedRows = 0
        experiments.forEach { server ->
            val local = queries.selectById(
                id = server.id,
                mapper = { _, _, _, _, updatedAt, syncState ->
                    RowMetadata(updatedAt, syncState)
                },
            ).executeAsOneOrNull()

            val shouldApply = when {
                local == null -> true
                local.syncState == ExperimentSyncState.PENDING_UPLOAD.name -> false
                server.updatedAtEpochMilliseconds > local.updatedAtEpochMilliseconds -> true
                else -> false
            }

            if (shouldApply) {
                put(
                    id = server.id,
                    title = server.title,
                    hypothesis = server.hypothesis,
                    notes = server.notes,
                    updatedAtEpochMilliseconds = server.updatedAtEpochMilliseconds,
                    syncState = ExperimentSyncState.SYNCED,
                )
                changedRows += 1
            }
        }
        changedRows
    }

    private fun put(
        id: String,
        title: String,
        hypothesis: String,
        notes: String,
        updatedAtEpochMilliseconds: Long,
        syncState: ExperimentSyncState,
    ) {
        val updatedRows = queries.updateRecord(
            title,
            hypothesis,
            notes,
            updatedAtEpochMilliseconds,
            syncState.name,
            id,
        ).value
        if (updatedRows == 0L) {
            queries.insertRecord(
                id,
                title,
                hypothesis,
                notes,
                updatedAtEpochMilliseconds,
                syncState.name,
            )
        }
    }
}

private data class RowMetadata(
    val updatedAtEpochMilliseconds: Long,
    val syncState: String,
)

private fun mapExperiment(
    id: String,
    title: String,
    hypothesis: String,
    notes: String,
    updatedAtEpochMilliseconds: Long,
    syncState: String,
): ExperimentRecord = ExperimentRecord(
    id = id,
    title = title,
    hypothesis = hypothesis,
    notes = notes,
    updatedAtEpochMilliseconds = updatedAtEpochMilliseconds,
    syncState = when (syncState) {
        ExperimentSyncState.SYNCED.name -> ExperimentSyncState.SYNCED
        ExperimentSyncState.PENDING_UPLOAD.name -> ExperimentSyncState.PENDING_UPLOAD
        else -> error("Unknown persisted sync state: $syncState")
    },
)
