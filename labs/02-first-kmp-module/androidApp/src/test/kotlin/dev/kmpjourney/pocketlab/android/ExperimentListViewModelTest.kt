package dev.kmpjourney.pocketlab.android

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
    fun legacyGatewayCanPowerTheHostViewModelBeforeCutover() = runTest(dispatcher) {
        val gateway = LegacyExperimentListGateway(
            refreshResult = ExperimentRefreshResult.Applied(changedCount = 1),
            refreshedRecords = listOf(item("remote-1")),
        )
        val viewModel = ExperimentListViewModel(gateway)

        advanceUntilIdle()

        assertEquals(listOf("remote-1"), viewModel.uiState.value.experiments.map { it.id })
        assertEquals(RefreshNotice.Applied(changedCount = 1), viewModel.uiState.value.notice)
        assertFalse(viewModel.uiState.value.isRefreshing)
    }

    @Test
    fun failedRefreshKeepsLegacyCachedRowsVisible() = runTest(dispatcher) {
        val gateway = LegacyExperimentListGateway(
            initialRecords = listOf(item("cached")),
            refreshResult = ExperimentRefreshResult.Failed(
                reason = ExperimentRefreshFailure.SERVER,
                statusCode = 503,
            ),
        )
        val viewModel = ExperimentListViewModel(gateway)

        advanceUntilIdle()

        assertEquals(listOf("cached"), viewModel.uiState.value.experiments.map { it.id })
        assertEquals(
            RefreshNotice.Failed(ExperimentRefreshFailure.SERVER),
            viewModel.uiState.value.notice,
        )
    }

    /** Simulates the pre-KMP implementation behind the Android-owned contract. */
    private class LegacyExperimentListGateway(
        initialRecords: List<ExperimentListItem> = emptyList(),
        private val refreshResult: ExperimentRefreshResult,
        private val refreshedRecords: List<ExperimentListItem> = initialRecords,
    ) : ExperimentListGateway {
        private val records = MutableStateFlow(initialRecords)

        override fun observeExperiments(): Flow<List<ExperimentListItem>> = records

        override suspend fun refresh(): ExperimentRefreshResult {
            if (refreshResult is ExperimentRefreshResult.Applied) {
                records.value = refreshedRecords
            }
            return refreshResult
        }
    }

    private fun item(id: String) = ExperimentListItem(
        id = id,
        title = id,
        hypothesis = "hypothesis",
        notes = "notes",
        updatedAtEpochMilliseconds = 10,
        syncStatus = ExperimentSyncStatus.SYNCED,
    )
}
