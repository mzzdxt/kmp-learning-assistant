package dev.kmpjourney.pocketlab.interop

import dev.kmpjourney.pocketlab.ExperimentLocalStore
import dev.kmpjourney.pocketlab.ExperimentRecord
import dev.kmpjourney.pocketlab.sync.RefreshExperimentsResult
import dev.kmpjourney.pocketlab.sync.RefreshExperimentsUseCase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/**
 * A tiny Objective-C/Swift export boundary. Core Kotlin APIs remain Flow/suspend;
 * only the Apple adapter translates them to callbacks with explicit cancellation.
 * Production callbacks run on Dispatchers.Main so SwiftUI may consume them safely.
 */
class IosExperimentListBridge internal constructor(
    private val localStore: ExperimentLocalStore,
    private val refreshExperiments: RefreshExperimentsUseCase,
    dispatcher: CoroutineDispatcher,
) {
    constructor(
        localStore: ExperimentLocalStore,
        refreshExperiments: RefreshExperimentsUseCase,
    ) : this(localStore, refreshExperiments, Dispatchers.Main)

    private val scope = CoroutineScope(SupervisorJob() + dispatcher)

    fun subscribe(
        onChange: (List<ExperimentRecord>) -> Unit,
        onFailure: (String) -> Unit,
    ): NativeCancellable {
        val job = scope.launch {
            try {
                localStore.observeAll().collect(onChange)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Exception) {
                onFailure(failure.message ?: "Experiment observation failed")
            }
        }
        return NativeCancellable(job)
    }

    fun refresh(
        onResult: (RefreshExperimentsResult) -> Unit,
        onFailure: (String) -> Unit,
    ): NativeCancellable {
        val job = scope.launch {
            try {
                onResult(refreshExperiments.execute())
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Exception) {
                onFailure(failure.message ?: "Experiment refresh failed")
            }
        }
        return NativeCancellable(job)
    }

    /** Cancels every active subscription and refresh. A closed bridge is terminal. */
    fun close() {
        scope.cancel()
    }
}

class NativeCancellable internal constructor(
    private val job: Job,
) {
    val isCancelled: Boolean
        get() = job.isCancelled

    fun cancel() {
        job.cancel()
    }
}
