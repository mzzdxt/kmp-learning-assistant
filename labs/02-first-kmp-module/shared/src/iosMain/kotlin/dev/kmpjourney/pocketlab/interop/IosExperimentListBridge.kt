@file:OptIn(
    kotlin.experimental.ExperimentalObjCName::class,
    kotlin.experimental.ExperimentalObjCRefinement::class,
)

package dev.kmpjourney.pocketlab.interop

import dev.kmpjourney.pocketlab.ExperimentLocalStore
import dev.kmpjourney.pocketlab.ExperimentRecord
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
import kotlin.native.ObjCName
import kotlin.native.ShouldRefineInSwift

/**
 * A tiny Objective-C/Swift export boundary. Core Kotlin APIs remain Flow/suspend;
 * only the Apple adapter translates them to callbacks with explicit cancellation.
 * Production callbacks run on Dispatchers.Main so SwiftUI may consume them safely.
 */
@ObjCName(swiftName = "ExperimentListClient")
class IosExperimentListBridge internal constructor(
    private val localStore: ExperimentLocalStore,
    private val refreshExperiments: RefreshExperimentsUseCase,
    dispatcher: CoroutineDispatcher,
) {
    internal constructor(
        localStore: ExperimentLocalStore,
        refreshExperiments: RefreshExperimentsUseCase,
    ) : this(localStore, refreshExperiments, Dispatchers.Main)

    private val scope = CoroutineScope(SupervisorJob() + dispatcher)

    /**
     * Low-level callback primitive refined by `experiments()` in Swift.
     * The generated method stays callable by the wrapper but is hidden from
     * normal Swift autocomplete.
     */
    @ShouldRefineInSwift
    fun subscribe(
        onChange: (List<IosExperimentSnapshot>) -> Unit,
        onFailure: (IosInteropFailure) -> Unit,
    ): NativeCancellable {
        val job = scope.launch {
            try {
                localStore.observeAll().collect { records ->
                    onChange(records.map(ExperimentRecord::toIosSnapshot))
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Exception) {
                onFailure(
                    IosInteropFailure(
                        code = "observation_failed",
                        message = failure.message ?: "Experiment observation failed",
                    ),
                )
            }
        }
        return NativeCancellable(job)
    }

    /** Low-level callback primitive refined by `refreshOnce()` in Swift. */
    @ShouldRefineInSwift
    fun refresh(
        onResult: (IosRefreshReport) -> Unit,
        onFailure: (IosInteropFailure) -> Unit,
    ): NativeCancellable {
        val job = scope.launch {
            try {
                onResult(refreshExperiments.execute().toIosReport())
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Exception) {
                onFailure(
                    IosInteropFailure(
                        code = "refresh_failed",
                        message = failure.message ?: "Experiment refresh failed",
                    ),
                )
            }
        }
        return NativeCancellable(job)
    }

    /** Cancels every active subscription and refresh. A closed bridge is terminal. */
    fun close() {
        scope.cancel()
    }
}

@ObjCName(swiftName = "CancellationToken")
class NativeCancellable internal constructor(
    private val job: Job,
) {
    val isCancelled: Boolean
        get() = job.isCancelled

    fun cancel() {
        job.cancel()
    }
}
