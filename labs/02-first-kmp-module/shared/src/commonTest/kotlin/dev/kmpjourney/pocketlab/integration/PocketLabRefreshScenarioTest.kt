package dev.kmpjourney.pocketlab.integration

import dev.kmpjourney.pocketlab.ExperimentSyncState
import dev.kmpjourney.pocketlab.data.PocketLabSqlDatabase
import dev.kmpjourney.pocketlab.data.SqlDelightExperimentRepository
import dev.kmpjourney.pocketlab.data.createTestSqlDriver
import dev.kmpjourney.pocketlab.demo.DemoExperimentRemoteDataSource
import dev.kmpjourney.pocketlab.sync.RefreshExperimentsResult
import dev.kmpjourney.pocketlab.sync.RefreshExperimentsUseCase
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class PocketLabRefreshScenarioTest {
    @Test
    fun demoRefreshCommitsOneCompleteSnapshotAndIsIdempotent() = runTest {
        val driver = createTestSqlDriver()
        val repository = SqlDelightExperimentRepository(
            database = PocketLabSqlDatabase(driver),
            queryDispatcher = UnconfinedTestDispatcher(testScheduler),
        )
        val useCase = RefreshExperimentsUseCase(
            remote = DemoExperimentRemoteDataSource(latencyMilliseconds = 0),
            local = repository,
        )
        val emissions = mutableListOf<List<String>>()

        try {
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                repository.observeAll()
                    .take(2)
                    .toList()
                    .mapTo(emissions) { records -> records.map { it.id } }
            }
            runCurrent()

            assertEquals(
                RefreshExperimentsResult.Applied(receivedCount = 3, changedCount = 3),
                useCase.execute(),
            )
            runCurrent()

            assertEquals(
                listOf(
                    emptyList(),
                    listOf(
                        "sleep-phone-outside-bedroom",
                        "focus-one-tab",
                        "walk-after-lunch",
                    ),
                ),
                emissions,
            )
            assertEquals(
                setOf(ExperimentSyncState.SYNCED),
                repository.observeAll().first().map { it.syncState }.toSet(),
            )
            assertEquals(
                RefreshExperimentsResult.Applied(receivedCount = 3, changedCount = 0),
                useCase.execute(),
            )
        } finally {
            driver.close()
        }
    }
}
