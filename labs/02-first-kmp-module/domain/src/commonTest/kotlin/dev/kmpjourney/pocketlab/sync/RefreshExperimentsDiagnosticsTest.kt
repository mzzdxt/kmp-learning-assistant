package dev.kmpjourney.pocketlab.sync

import dev.kmpjourney.pocketlab.ExperimentLocalStore
import dev.kmpjourney.pocketlab.ExperimentRecord
import dev.kmpjourney.pocketlab.SavedExperiment
import dev.kmpjourney.pocketlab.ServerExperimentSnapshot
import dev.kmpjourney.pocketlab.network.ExperimentRemoteDataSource
import dev.kmpjourney.pocketlab.network.RemoteExperiment
import dev.kmpjourney.pocketlab.network.RemoteExperimentsResult
import dev.kmpjourney.pocketlab.network.RemoteFailureReason
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

class RefreshExperimentsDiagnosticsTest {
    @Test
    fun appliedRefreshHasOneStartedAndOneAppliedOutcome() = runTest {
        val diagnostics = RecordingRefreshDiagnostics()
        val result = RefreshExperimentsUseCase(
            remote = RemoteExperimentsResult.Success(listOf(remoteExperiment)).asRemote(),
            local = LocalStore(changedCount = 1),
            diagnostics = diagnostics,
        ).execute()

        assertEquals(RefreshExperimentsResult.Applied(1, 1), result)
        assertEquals(
            listOf("started", "applied:1:1"),
            diagnostics.events,
        )
    }

    @Test
    fun expectedFailureKeepsItsStableReasonAndStatus() = runTest {
        val diagnostics = RecordingRefreshDiagnostics()
        val result = RefreshExperimentsUseCase(
            remote = RemoteExperimentsResult.Failure(
                reason = RemoteFailureReason.SERVER,
                statusCode = 503,
            ).asRemote(),
            local = LocalStore(),
            diagnostics = diagnostics,
        ).execute()

        assertEquals(
            RefreshExperimentsResult.Failed(RefreshFailureReason.SERVER, 503),
            result,
        )
        assertEquals(
            listOf("started", "failed:SERVER:503"),
            diagnostics.events,
        )
    }

    @Test
    fun cancellationIsObservedAndRethrown() = runTest {
        val diagnostics = RecordingRefreshDiagnostics()
        val cancellation = CancellationException("screen closed")
        val useCase = RefreshExperimentsUseCase(
            remote = remote { throw cancellation },
            local = LocalStore(),
            diagnostics = diagnostics,
        )

        val thrown = assertFailsWith<CancellationException> { useCase.execute() }

        assertSame(cancellation, thrown)
        assertEquals(listOf("started", "cancelled"), diagnostics.events)
    }

    @Test
    fun unexpectedExceptionIsObservedWithoutBeingConvertedToBusinessFailure() = runTest {
        val diagnostics = RecordingRefreshDiagnostics()
        val failure = IllegalStateException("database path and user content must stay private")
        val useCase = RefreshExperimentsUseCase(
            remote = RemoteExperimentsResult.Success(listOf(remoteExperiment)).asRemote(),
            local = LocalStore(failure = failure),
            diagnostics = diagnostics,
        )

        val thrown = assertFailsWith<IllegalStateException> { useCase.execute() }

        assertSame(failure, thrown)
        assertEquals(listOf("started", "crashed:IllegalStateException"), diagnostics.events)
    }

    @Test
    fun brokenDiagnosticsCannotBreakAValidRefresh() = runTest {
        val result = RefreshExperimentsUseCase(
            remote = RemoteExperimentsResult.Success(listOf(remoteExperiment)).asRemote(),
            local = LocalStore(changedCount = 1),
            diagnostics = RefreshDiagnostics { error("telemetry backend unavailable") },
        ).execute()

        assertEquals(RefreshExperimentsResult.Applied(1, 1), result)
    }

    private class RecordingRefreshDiagnostics : RefreshDiagnostics {
        val events = mutableListOf<String>()

        override fun begin(): RefreshDiagnosticOperation {
            events += "started"
            return object : RefreshDiagnosticOperation {
                override fun applied(receivedCount: Int, changedCount: Int) {
                    events += "applied:$receivedCount:$changedCount"
                }

                override fun failed(reason: RefreshFailureReason, statusCode: Int?) {
                    events += "failed:$reason:$statusCode"
                }

                override fun cancelled() {
                    events += "cancelled"
                }

                override fun crashed(failure: Throwable) {
                    events += "crashed:${failure::class.simpleName}"
                }
            }
        }
    }

    private class LocalStore(
        private val changedCount: Int = 0,
        private val failure: Exception? = null,
    ) : ExperimentLocalStore {
        override fun observeAll(): Flow<List<ExperimentRecord>> = emptyFlow()

        override suspend fun pendingUploads(): List<ExperimentRecord> = emptyList()

        override suspend fun mergeServerSnapshot(experiments: List<ServerExperimentSnapshot>): Int {
            failure?.let { throw it }
            return changedCount
        }

        override suspend fun save(experiment: SavedExperiment) = Unit
    }

    private fun RemoteExperimentsResult.asRemote(): ExperimentRemoteDataSource = remote { this }

    private fun remote(
        block: suspend () -> RemoteExperimentsResult,
    ): ExperimentRemoteDataSource = object : ExperimentRemoteDataSource {
        override suspend fun fetchExperiments(): RemoteExperimentsResult = block()
    }

    private companion object {
        val remoteExperiment = RemoteExperiment(
            id = "remote-1",
            title = "A title that must never enter diagnostics",
            hypothesis = "A hypothesis that must never enter diagnostics",
            notes = "Private notes",
            updatedAtEpochMilliseconds = 1_000,
        )
    }
}
