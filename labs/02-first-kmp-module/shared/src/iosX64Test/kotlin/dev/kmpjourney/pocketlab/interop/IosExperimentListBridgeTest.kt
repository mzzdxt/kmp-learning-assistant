package dev.kmpjourney.pocketlab.interop

import dev.kmpjourney.pocketlab.ExperimentLocalStore
import dev.kmpjourney.pocketlab.ExperimentRecord
import dev.kmpjourney.pocketlab.ExperimentSyncState
import dev.kmpjourney.pocketlab.SavedExperiment
import dev.kmpjourney.pocketlab.ServerExperimentSnapshot
import dev.kmpjourney.pocketlab.network.ExperimentRemoteDataSource
import dev.kmpjourney.pocketlab.network.RemoteExperiment
import dev.kmpjourney.pocketlab.network.RemoteExperimentsResult
import dev.kmpjourney.pocketlab.sync.RefreshExperimentsResult
import dev.kmpjourney.pocketlab.sync.RefreshExperimentsUseCase
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class IosExperimentListBridgeTest {
    @Test
    fun subscriptionForwardsValuesUntilSwiftCancelsItsToken() = runTest {
        val local = FakeLocalStore()
        val bridge = bridge(local, successfulRemote(), UnconfinedTestDispatcher(testScheduler))
        val received = mutableListOf<List<String>>()
        val failures = mutableListOf<String>()

        val token = bridge.subscribe(
            onChange = { records -> received += records.map { it.id } },
            onFailure = failures::add,
        )
        runCurrent()
        local.emit(record("first"))
        runCurrent()

        token.cancel()
        local.emit(record("ignored"))
        runCurrent()

        assertEquals(listOf(emptyList(), listOf("first")), received)
        assertEquals(emptyList(), failures)
        assertTrue(token.isCancelled)
    }

    @Test
    fun refreshReportsResultAndWritesThroughTheSharedUseCase() = runTest {
        val local = FakeLocalStore()
        val bridge = bridge(local, successfulRemote(), UnconfinedTestDispatcher(testScheduler))
        val results = mutableListOf<RefreshExperimentsResult>()

        val failures = mutableListOf<String>()
        val token = bridge.refresh(results::add, failures::add)
        runCurrent()

        assertFalse(token.isCancelled)
        assertEquals(
            listOf<RefreshExperimentsResult>(
                RefreshExperimentsResult.Applied(receivedCount = 1, changedCount = 1),
            ),
            results,
        )
        assertEquals(listOf("remote-1"), local.value.map { it.id })
        assertEquals(emptyList(), failures)
    }

    @Test
    fun cancellingRefreshDoesNotInvokeSwiftCompletion() = runTest {
        val local = FakeLocalStore()
        val remote = object : ExperimentRemoteDataSource {
            override suspend fun fetchExperiments(): RemoteExperimentsResult {
                awaitCancellation()
            }
        }
        val bridge = bridge(local, remote, UnconfinedTestDispatcher(testScheduler))
        val results = mutableListOf<RefreshExperimentsResult>()

        val token = bridge.refresh(
            onResult = results::add,
            onFailure = { message -> error("Unexpected failure callback: $message") },
        )
        runCurrent()
        token.cancel()
        runCurrent()

        assertTrue(token.isCancelled)
        assertEquals(emptyList(), results)
    }

    @Test
    fun unexpectedRefreshExceptionUsesFailureCallback() = runTest {
        val local = FakeLocalStore()
        val remote = object : ExperimentRemoteDataSource {
            override suspend fun fetchExperiments(): RemoteExperimentsResult =
                error("database unavailable")
        }
        val bridge = bridge(local, remote, UnconfinedTestDispatcher(testScheduler))
        val results = mutableListOf<RefreshExperimentsResult>()
        val failures = mutableListOf<String>()

        bridge.refresh(results::add, failures::add)
        runCurrent()

        assertEquals(emptyList(), results)
        assertEquals(listOf("database unavailable"), failures)
    }

    @Test
    fun closingBridgeCancelsAllOwnedJobs() = runTest {
        val local = FakeLocalStore()
        val bridge = bridge(local, successfulRemote(), UnconfinedTestDispatcher(testScheduler))
        val first = bridge.subscribe({}, {})
        val second = bridge.subscribe({}, {})
        runCurrent()

        bridge.close()
        runCurrent()

        assertTrue(first.isCancelled)
        assertTrue(second.isCancelled)
    }

    private fun bridge(
        local: FakeLocalStore,
        remote: ExperimentRemoteDataSource,
        dispatcher: CoroutineDispatcher,
    ) = IosExperimentListBridge(
        localStore = local,
        refreshExperiments = RefreshExperimentsUseCase(remote, local),
        dispatcher = dispatcher,
    )

    private fun successfulRemote() = object : ExperimentRemoteDataSource {
        override suspend fun fetchExperiments(): RemoteExperimentsResult =
            RemoteExperimentsResult.Success(
                listOf(
                    RemoteExperiment(
                        id = "remote-1",
                        title = "Remote",
                        hypothesis = "hypothesis",
                        notes = "notes",
                        updatedAtEpochMilliseconds = 20,
                    ),
                ),
            )
    }

    private fun record(id: String) = ExperimentRecord(
        id = id,
        title = id,
        hypothesis = "hypothesis",
        notes = "notes",
        updatedAtEpochMilliseconds = 10,
        syncState = ExperimentSyncState.SYNCED,
    )

    private class FakeLocalStore : ExperimentLocalStore {
        private val records = MutableStateFlow<List<ExperimentRecord>>(emptyList())
        val value: List<ExperimentRecord>
            get() = records.value

        override fun observeAll(): Flow<List<ExperimentRecord>> = records

        override suspend fun save(experiment: SavedExperiment) {
            emit(
                ExperimentRecord(
                    id = experiment.id,
                    title = experiment.title,
                    hypothesis = experiment.hypothesis,
                    notes = experiment.notes,
                    updatedAtEpochMilliseconds = experiment.savedAtEpochMilliseconds,
                    syncState = ExperimentSyncState.PENDING_UPLOAD,
                ),
            )
        }

        override suspend fun pendingUploads(): List<ExperimentRecord> =
            value.filter { it.syncState == ExperimentSyncState.PENDING_UPLOAD }

        override suspend fun mergeServerSnapshot(
            experiments: List<ServerExperimentSnapshot>,
        ): Int {
            records.value = experiments.map { experiment ->
                ExperimentRecord(
                    id = experiment.id,
                    title = experiment.title,
                    hypothesis = experiment.hypothesis,
                    notes = experiment.notes,
                    updatedAtEpochMilliseconds = experiment.updatedAtEpochMilliseconds,
                    syncState = ExperimentSyncState.SYNCED,
                )
            }
            return experiments.size
        }

        fun emit(record: ExperimentRecord) {
            records.value = listOf(record)
        }
    }
}
