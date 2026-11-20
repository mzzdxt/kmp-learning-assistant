package dev.kmpjourney.pocketlab.sharedui

import kotlin.test.Test
import kotlin.test.assertEquals

class ExperimentSummaryStatusTest {
    @Test
    fun decodesStableWireValues() {
        assertEquals(
            ExperimentSummaryStatus.SYNCED,
            ExperimentSummaryStatus.fromWireValue("synced"),
        )
        assertEquals(
            ExperimentSummaryStatus.PENDING_UPLOAD,
            ExperimentSummaryStatus.fromWireValue("pending_upload"),
        )
    }

    @Test
    fun unknownStatusFallsBackToPendingInsteadOfClaimingSynced() {
        assertEquals(
            ExperimentSummaryStatus.PENDING_UPLOAD,
            ExperimentSummaryStatus.fromWireValue("future_status"),
        )
    }
}
