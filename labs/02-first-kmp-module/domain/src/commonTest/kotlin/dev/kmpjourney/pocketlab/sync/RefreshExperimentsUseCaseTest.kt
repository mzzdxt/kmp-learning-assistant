package dev.kmpjourney.pocketlab.sync

import dev.kmpjourney.pocketlab.ExperimentRecord
import dev.kmpjourney.pocketlab.ExperimentLocalStore
import dev.kmpjourney.pocketlab.ExperimentSyncState
import dev.kmpjourney.pocketlab.SavedExperiment
import dev.kmpjourney.pocketlab.ServerExperimentSnapshot
import dev.kmpjourney.pocketlab.network.ExperimentRemoteDataSource
import dev.kmpjourney.pocketlab.network.RemoteExperiment
import dev.kmpjourney.pocketlab.network.RemoteExperimentsResult
import dev.kmpjourney.pocketlab.network.RemoteFailureReason
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class RefreshExperimentsUseCaseTest {
    @Test
    fun successfulRefreshChangesOnlyTheLocalSourceOfTruth() = runTest {
        testUseCase(
            remoteResult = RemoteExperimentsResult.Success(
                listOf(remote(id = "remote-1", updatedAt = 50)),
            ),
        ) { useCase, local ->
            assertEquals(
                RefreshExperimentsResult.Applied(receivedCount = 1, changedCount = 1),
                useCase.execute(),
            )
            assertEquals(
                listOf(
                    ExperimentRecord(
                        id = "remote-1",
                        title = "title-remote-1",
                        hypothesis = "hypothesis-remote-1",
                        notes = "notes-remote-1",
                        updatedAtEpochMilliseconds = 50,
                        syncState = ExperimentSyncState.SYNCED,
                    ),
                ),
                local.observeAll().first(),
            )
        }
    }

    @Test
    fun failedRefreshLeavesTheLocalSourceOfTruthUntouched() = runTest {
        testUseCase(
            remoteResult = RemoteExperimentsResult.Failure(
                reason = RemoteFailureReason.SERVER,
                statusCode = 503,
            ),
        ) { useCase, local ->
            val before = local.observeAll().first()

            assertEquals(
                RefreshExperimentsResult.Failed(
                    reason = RefreshFailureReason.SERVER,
                    statusCode = 503,
                ),
                useCase.execute(),
            )
            assertEquals(before, local.observeAll().first())
        }
    }

    private suspend fun testUseCase(
        remoteResult: RemoteExperimentsResult,
        block: suspend (RefreshExperimentsUseCase, FakeExperimentLocalStore) -> Unit,
    ) {
        val local = FakeExperimentLocalStore()
        val remote = object : ExperimentRemoteDataSource {
            override suspend fun fetchExperiments(): RemoteExperimentsResult = remoteResult
        }
        block(RefreshExperimentsUseCase(remote, local), local)
    }

    private fun remote(id: String, updatedAt: Long) = RemoteExperiment(
        id = id,
        title = "title-$id",
        hypothesis = "hypothesis-$id",
        notes = "notes-$id",
        updatedAtEpochMilliseconds = updatedAt,
    )

    /**
     * A domain test double: this test proves orchestration rules without knowing
     * whether production persistence is SQLDelight, Room, Core Data, or memory.
     */
    private class FakeExperimentLocalStore : ExperimentLocalStore {
        private val records = MutableStateFlow<List<ExperimentRecord>>(emptyList())

        override fun observeAll(): Flow<List<ExperimentRecord>> = records

        override suspend fun save(experiment: SavedExperiment) {
            records.value = records.value + ExperimentRecord(
                id = experiment.id,
                title = experiment.title,
                hypothesis = experiment.hypothesis,
                notes = experiment.notes,
                updatedAtEpochMilliseconds = experiment.savedAtEpochMilliseconds,
                syncState = ExperimentSyncState.PENDING_UPLOAD,
            )
        }

        override suspend fun pendingUploads(): List<ExperimentRecord> =
            records.value.filter { it.syncState == ExperimentSyncState.PENDING_UPLOAD }

        override suspend fun mergeServerSnapshot(
            experiments: List<ServerExperimentSnapshot>,
        ): Int {
            val byId = records.value.associateByTo(linkedMapOf()) { it.id }
            var changedCount = 0
            experiments.forEach { server ->
                val local = byId[server.id]
                val shouldApply = local == null ||
                    (
                        local.syncState == ExperimentSyncState.SYNCED &&
                            server.updatedAtEpochMilliseconds > local.updatedAtEpochMilliseconds
                    )
                if (shouldApply) {
                    byId[server.id] = ExperimentRecord(
                        id = server.id,
                        title = server.title,
                        hypothesis = server.hypothesis,
                        notes = server.notes,
                        updatedAtEpochMilliseconds = server.updatedAtEpochMilliseconds,
                        syncState = ExperimentSyncState.SYNCED,
                    )
                    changedCount += 1
                }
            }
            records.value = byId.values.toList()
            return changedCount
        }
    }
}
