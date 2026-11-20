package dev.kmpjourney.pocketlab.android

import dev.kmpjourney.pocketlab.ExperimentLocalStore
import dev.kmpjourney.pocketlab.ExperimentRecord
import dev.kmpjourney.pocketlab.ExperimentSyncState
import dev.kmpjourney.pocketlab.SavedExperiment
import dev.kmpjourney.pocketlab.ServerExperimentSnapshot
import dev.kmpjourney.pocketlab.network.ExperimentRemoteDataSource
import dev.kmpjourney.pocketlab.network.RemoteExperimentsResult
import dev.kmpjourney.pocketlab.network.RemoteFailureReason
import dev.kmpjourney.pocketlab.sync.RefreshExperimentsUseCase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

class KmpExperimentListGatewayTest {
    @Test
    fun mapsKmpDatabaseRecordsIntoAndroidOwnedValues() = runTest {
        val local = FakeLocalStore(
            listOf(
                record("synced", ExperimentSyncState.SYNCED),
                record("pending", ExperimentSyncState.PENDING_UPLOAD),
            ),
        )
        val gateway = gateway(local, RemoteExperimentsResult.Success(emptyList()).asRemote())

        val items = gateway.observeExperiments().first()

        assertEquals(listOf("synced", "pending"), items.map { it.id })
        assertEquals(
            listOf(ExperimentSyncStatus.SYNCED, ExperimentSyncStatus.PENDING_UPLOAD),
            items.map { it.syncStatus },
        )
    }

    @Test
    fun mapsKmpSuccessIntoAndroidOwnedResult() = runTest {
        val local = FakeLocalStore(changedCount = 2)
        val gateway = gateway(local, RemoteExperimentsResult.Success(emptyList()).asRemote())

        assertEquals(ExperimentRefreshResult.Applied(2), gateway.refresh())
    }

    @Test
    fun mapsStableKmpFailureWithoutLeakingTheSealedHierarchy() = runTest {
        val gateway = gateway(
            FakeLocalStore(),
            RemoteExperimentsResult.Failure(RemoteFailureReason.SERVER, 503).asRemote(),
        )

        assertEquals(
            ExperimentRefreshResult.Failed(ExperimentRefreshFailure.SERVER, 503),
            gateway.refresh(),
        )
    }

    @Test
    fun cancellationCrossesTheMigrationAdapterUnchanged() = runTest {
        val cancellation = CancellationException("host left the screen")
        val gateway = gateway(FakeLocalStore(), remote { throw cancellation })

        val thrown = assertFailsWith<CancellationException> { gateway.refresh() }

        assertSame(cancellation, thrown)
    }

    private fun gateway(
        local: FakeLocalStore,
        remote: ExperimentRemoteDataSource,
    ) = KmpExperimentListGateway(
        localStore = local,
        refreshExperiments = RefreshExperimentsUseCase(remote, local),
    )

    private fun RemoteExperimentsResult.asRemote(): ExperimentRemoteDataSource = remote { this }

    private fun remote(
        block: suspend () -> RemoteExperimentsResult,
    ): ExperimentRemoteDataSource = object : ExperimentRemoteDataSource {
        override suspend fun fetchExperiments(): RemoteExperimentsResult = block()
    }

    private class FakeLocalStore(
        initial: List<ExperimentRecord> = emptyList(),
        private val changedCount: Int = 0,
    ) : ExperimentLocalStore {
        private val records = MutableStateFlow(initial)

        override fun observeAll(): Flow<List<ExperimentRecord>> = records

        override suspend fun pendingUploads(): List<ExperimentRecord> = emptyList()

        override suspend fun mergeServerSnapshot(
            experiments: List<ServerExperimentSnapshot>,
        ): Int = changedCount

        override suspend fun save(experiment: SavedExperiment) = Unit
    }

    private companion object {
        fun record(id: String, syncState: ExperimentSyncState) = ExperimentRecord(
            id = id,
            title = "title-$id",
            hypothesis = "hypothesis-$id",
            notes = "notes-$id",
            updatedAtEpochMilliseconds = 1_000,
            syncState = syncState,
        )
    }
}
