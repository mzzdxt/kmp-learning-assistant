package dev.kmpjourney.pocketlab.sync

import dev.kmpjourney.pocketlab.ExperimentRecord
import dev.kmpjourney.pocketlab.ExperimentSyncState
import dev.kmpjourney.pocketlab.data.PocketLabSqlDatabase
import dev.kmpjourney.pocketlab.data.SqlDelightExperimentRepository
import dev.kmpjourney.pocketlab.data.createTestSqlDriver
import dev.kmpjourney.pocketlab.network.ExperimentRemoteDataSource
import dev.kmpjourney.pocketlab.network.RemoteExperiment
import dev.kmpjourney.pocketlab.network.RemoteExperimentsResult
import dev.kmpjourney.pocketlab.network.RemoteFailureReason
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
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
        block: suspend (RefreshExperimentsUseCase, SqlDelightExperimentRepository) -> Unit,
    ) {
        val driver = createTestSqlDriver()
        val local = SqlDelightExperimentRepository(
            database = PocketLabSqlDatabase(driver),
            queryDispatcher = UnconfinedTestDispatcher(),
        )
        val remote = object : ExperimentRemoteDataSource {
            override suspend fun fetchExperiments(): RemoteExperimentsResult = remoteResult
        }
        try {
            block(RefreshExperimentsUseCase(remote, local), local)
        } finally {
            driver.close()
        }
    }

    private fun remote(id: String, updatedAt: Long) = RemoteExperiment(
        id = id,
        title = "title-$id",
        hypothesis = "hypothesis-$id",
        notes = "notes-$id",
        updatedAtEpochMilliseconds = updatedAt,
    )
}
