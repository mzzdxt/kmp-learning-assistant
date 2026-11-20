package dev.kmpjourney.pocketlab.interop

import dev.kmpjourney.pocketlab.ExperimentLocalStore
import dev.kmpjourney.pocketlab.ExperimentRecord
import dev.kmpjourney.pocketlab.ExperimentSyncState
import dev.kmpjourney.pocketlab.SavedExperiment
import dev.kmpjourney.pocketlab.ServerExperimentSnapshot
import dev.kmpjourney.pocketlab.network.ExperimentRemoteDataSource
import dev.kmpjourney.pocketlab.network.RemoteExperiment
import dev.kmpjourney.pocketlab.network.RemoteExperimentsResult
import dev.kmpjourney.pocketlab.network.RemoteFailureReason
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
        val failures = mutableListOf<IosInteropFailure>()

        val token = bridge.subscribe(
            onChange = { snapshots -> received += snapshots.map { it.id } },
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
        val results = mutableListOf<IosRefreshReport>()

        val failures = mutableListOf<IosInteropFailure>()
        val token = bridge.refresh(results::add, failures::add)
        runCurrent()

        assertFalse(token.isCancelled)
        assertEquals(1, results.single().receivedCount)
        assertEquals(1, results.single().changedCount)
        assertTrue(results.single().isSuccessful)
        assertEquals(null, results.single().failureCode)
        assertEquals(listOf("remote-1"), local.value.map { it.id })
        assertEquals(emptyList(), failures)
    }

    @Test
    fun subscriptionMapsSharedRecordToAStableAppleSnapshot() = runTest {
        val local = FakeLocalStore()
        val bridge = bridge(local, successfulRemote(), UnconfinedTestDispatcher(testScheduler))
        val snapshots = mutableListOf<IosExperimentSnapshot>()

        bridge.subscribe(
            onChange = { values -> snapshots += values },
            onFailure = { failure -> error("Unexpected failure callback: ${failure.message}") },
        )
        runCurrent()
        local.emit(
            ExperimentRecord(
                id = "pending-1",
                title = "Pending",
                hypothesis = "hypothesis",
                notes = "notes",
                updatedAtEpochMilliseconds = 42,
                syncState = ExperimentSyncState.PENDING_UPLOAD,
            ),
        )
        runCurrent()

        val snapshot = snapshots.single()
        assertEquals("pending-1", snapshot.id)
        assertEquals(42, snapshot.updatedAtEpochMilliseconds)
        assertTrue(snapshot.isPendingUpload)
    }

    @Test
    fun failedRefreshUsesStableCodeAndUnboxedStatusContract() = runTest {
        val local = FakeLocalStore()
        val remote = object : ExperimentRemoteDataSource {
            override suspend fun fetchExperiments(): RemoteExperimentsResult =
                RemoteExperimentsResult.Failure(
                    reason = RemoteFailureReason.SERVER,
                    statusCode = 503,
                )
        }
        val bridge = bridge(local, remote, UnconfinedTestDispatcher(testScheduler))
        val reports = mutableListOf<IosRefreshReport>()

        bridge.refresh(
            onResult = reports::add,
            onFailure = { failure -> error("Unexpected failure callback: ${failure.message}") },
        )
        runCurrent()

        val report = reports.single()
        assertFalse(report.isSuccessful)
        assertEquals("server", report.failureCode)
        assertTrue(report.hasFailureStatusCode)
        assertEquals(503, report.failureStatusCode)
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
        val results = mutableListOf<IosRefreshReport>()

        val token = bridge.refresh(
            onResult = results::add,
            onFailure = { failure -> error("Unexpected failure callback: ${failure.message}") },
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
        val results = mutableListOf<IosRefreshReport>()
        val failures = mutableListOf<IosInteropFailure>()

        bridge.refresh(results::add, failures::add)
        runCurrent()

        assertEquals(emptyList(), results)
        assertEquals(listOf("refresh_failed"), failures.map { it.code })
        assertEquals(listOf("database unavailable"), failures.map { it.message })
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
