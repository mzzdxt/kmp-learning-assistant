@file:OptIn(kotlin.experimental.ExperimentalObjCRefinement::class)

package dev.kmpjourney.pocketlab.sync

import kotlin.native.HiddenFromObjC

/**
 * A domain-owned lifecycle port for one refresh invocation.
 *
 * Implementations may emit logs, traces, or metrics, but must never change the
 * refresh result when their own backend fails.
 */
@HiddenFromObjC
fun interface RefreshDiagnostics {
    fun begin(): RefreshDiagnosticOperation

    companion object {
        val None: RefreshDiagnostics = RefreshDiagnostics { NoOpRefreshDiagnosticOperation }
    }
}

/** One operation has exactly one terminal outcome after [RefreshDiagnostics.begin]. */
@HiddenFromObjC
interface RefreshDiagnosticOperation {
    fun applied(receivedCount: Int, changedCount: Int)

    fun failed(reason: RefreshFailureReason, statusCode: Int?)

    fun cancelled()

    fun crashed(failure: Throwable)
}

private object NoOpRefreshDiagnosticOperation : RefreshDiagnosticOperation {
    override fun applied(receivedCount: Int, changedCount: Int) = Unit

    override fun failed(reason: RefreshFailureReason, statusCode: Int?) = Unit

    override fun cancelled() = Unit

    override fun crashed(failure: Throwable) = Unit
}
