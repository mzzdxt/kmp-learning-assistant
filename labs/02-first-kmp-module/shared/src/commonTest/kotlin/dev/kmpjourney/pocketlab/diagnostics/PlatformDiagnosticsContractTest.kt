package dev.kmpjourney.pocketlab.diagnostics

import dev.kmpjourney.pocketlab.ExperimentLocalStore
import dev.kmpjourney.pocketlab.ExperimentRecord
import dev.kmpjourney.pocketlab.SavedExperiment
import dev.kmpjourney.pocketlab.ServerExperimentSnapshot
import dev.kmpjourney.pocketlab.network.ExperimentRemoteDataSource
import dev.kmpjourney.pocketlab.network.RemoteExperimentsResult
import dev.kmpjourney.pocketlab.sync.RefreshExperimentsResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class PlatformDiagnosticsContractTest {
    @Test
    fun productionFactoryEmitsThroughTheCurrentPlatformWithoutChangingTheResult() = runTest {
        val useCase = createObservedRefreshExperimentsUseCase(
            remote = object : ExperimentRemoteDataSource {
                override suspend fun fetchExperiments(): RemoteExperimentsResult =
                    RemoteExperimentsResult.Success(emptyList())
            },
            local = EmptyLocalStore,
        )

        assertEquals(
            RefreshExperimentsResult.Applied(receivedCount = 0, changedCount = 0),
            useCase.execute(),
        )
    }

    private object EmptyLocalStore : ExperimentLocalStore {
        override fun observeAll(): Flow<List<ExperimentRecord>> = emptyFlow()

        override suspend fun pendingUploads(): List<ExperimentRecord> = emptyList()

        override suspend fun mergeServerSnapshot(
            experiments: List<ServerExperimentSnapshot>,
        ): Int = 0

        override suspend fun save(experiment: SavedExperiment) = Unit
    }
}
