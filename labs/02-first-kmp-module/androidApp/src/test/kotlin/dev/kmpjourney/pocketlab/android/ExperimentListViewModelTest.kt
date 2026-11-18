package dev.kmpjourney.pocketlab.android

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
import dev.kmpjourney.pocketlab.sync.RefreshFailureReason
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

@OptIn(ExperimentalCoroutinesApi::class)
class ExperimentListViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun initialRefreshPublishesDatabaseRowsAndSuccessNotice() = runTest(dispatcher) {
        val local = FakeLocalStore()
        val remote = remote(RemoteExperimentsResult.Success(listOf(remoteRecord())))
        val viewModel = viewModel(local, remote)

        advanceUntilIdle()

        assertEquals(listOf("remote-1"), viewModel.uiState.value.experiments.map { it.id })
        assertEquals(RefreshNotice.Applied(changedCount = 1), viewModel.uiState.value.notice)
        assertFalse(viewModel.uiState.value.isRefreshing)
    }

    @Test
    fun failedRefreshKeepsCachedRowsVisible() = runTest(dispatcher) {
        val local = FakeLocalStore(listOf(record("cached")))
        val remote = remote(
            RemoteExperimentsResult.Failure(
                reason = RemoteFailureReason.SERVER,
                statusCode = 503,
            ),
        )
        val viewModel = viewModel(local, remote)

        advanceUntilIdle()

        assertEquals(listOf("cached"), viewModel.uiState.value.experiments.map { it.id })
        assertEquals(
            RefreshNotice.Failed(RefreshFailureReason.SERVER),
            viewModel.uiState.value.notice,
        )
    }

    private fun viewModel(
        local: FakeLocalStore,
        remote: ExperimentRemoteDataSource,
    ) = ExperimentListViewModel(
        localStore = local,
        refreshExperiments = RefreshExperimentsUseCase(remote, local),
    )

    private fun remote(result: RemoteExperimentsResult) = object : ExperimentRemoteDataSource {
        override suspend fun fetchExperiments(): RemoteExperimentsResult = result
    }

    private fun remoteRecord() = RemoteExperiment(
        id = "remote-1",
        title = "Remote",
        hypothesis = "hypothesis",
        notes = "notes",
        updatedAtEpochMilliseconds = 20,
    )

    private fun record(id: String) = ExperimentRecord(
        id = id,
        title = id,
        hypothesis = "hypothesis",
        notes = "notes",
        updatedAtEpochMilliseconds = 10,
        syncState = ExperimentSyncState.SYNCED,
    )

    private class FakeLocalStore(
        initial: List<ExperimentRecord> = emptyList(),
    ) : ExperimentLocalStore {
        private val records = MutableStateFlow(initial)

        override fun observeAll(): Flow<List<ExperimentRecord>> = records

        override suspend fun save(experiment: SavedExperiment) = Unit

        override suspend fun pendingUploads(): List<ExperimentRecord> = emptyList()

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
    }
}
