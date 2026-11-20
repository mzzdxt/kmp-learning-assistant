package dev.kmpjourney.pocketlab.diagnostics

import dev.kmpjourney.pocketlab.sync.RefreshFailureReason
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class StructuredRefreshDiagnosticsTest {
    @Test
    fun appliedRefreshUsesOneCorrelationIdAndMonotonicDuration() {
        val records = mutableListOf<RefreshDiagnosticRecord>()
        val operation = diagnostics(records).begin()

        operation.applied(receivedCount = 3, changedCount = 2)

        assertEquals(2, records.size)
        assertEquals(
            "schema=1 event=pocketlab.refresh phase=started level=info " +
                "operation_id=operation-7",
            records[0].render(),
        )
        assertEquals(
            "schema=1 event=pocketlab.refresh phase=finished level=info " +
                "operation_id=operation-7 duration_ms=42 outcome=applied " +
                "received_count=3 changed_count=2",
            records[1].render(),
        )
    }

    @Test
    fun expectedFailureHasStableReasonAndOptionalStatus() {
        val records = mutableListOf<RefreshDiagnosticRecord>()
        val operation = diagnostics(records).begin()

        operation.failed(RefreshFailureReason.SERVER, 503)

        assertEquals(
            "schema=1 event=pocketlab.refresh phase=finished level=warning " +
                "operation_id=operation-7 duration_ms=42 outcome=failed " +
                "failure_reason=server status_code=503",
            records.last().render(),
        )
    }

    @Test
    fun unexpectedExceptionRecordsTypeButNeverItsMessage() {
        val records = mutableListOf<RefreshDiagnosticRecord>()
        val operation = diagnostics(records).begin()
        val privateMessage = "token=secret title=private database=/Users/person/pocketlab.db"

        operation.crashed(IllegalStateException(privateMessage))

        val rendered = records.last().render()
        assertEquals(
            "schema=1 event=pocketlab.refresh phase=finished level=error " +
                "operation_id=operation-7 duration_ms=42 outcome=crashed " +
                "error_type=IllegalStateException",
            rendered,
        )
        assertFalse(privateMessage in rendered)
        assertFalse("secret" in rendered)
    }

    @Test
    fun dynamicTokensCannotInjectNewLogFieldsOrLines() {
        val record = RefreshDiagnosticRecord(
            phase = "finished",
            level = DiagnosticLevel.ERROR,
            operationId = "refresh-1\nstatus_code=200 token=secret",
            outcome = RefreshDiagnosticOutcome.CRASHED,
            errorType = "Bad\tType=Injected",
        )

        assertEquals(
            "schema=1 event=pocketlab.refresh phase=finished level=error " +
                "operation_id=refresh-1_status_code_200_token_secret " +
                "outcome=crashed error_type=Bad_Type_Injected",
            record.render(),
        )
        assertFalse('\n' in record.render())
        assertFalse('\t' in record.render())
    }

    @Test
    fun diagnosticBackendFailureIsContainedInsideTheAdapter() {
        val diagnostics = StructuredRefreshDiagnostics(
            sink = DiagnosticSink { error("log backend failed") },
            operationIdSource = OperationIdSource { "operation-7" },
            timerFactory = ElapsedTimerFactory { ElapsedTimer { 42 } },
        )

        val operation = diagnostics.begin()
        operation.cancelled()
    }

    private fun diagnostics(
        records: MutableList<RefreshDiagnosticRecord>,
    ): StructuredRefreshDiagnostics = StructuredRefreshDiagnostics(
        sink = DiagnosticSink(records::add),
        operationIdSource = OperationIdSource { "operation-7" },
        timerFactory = ElapsedTimerFactory { ElapsedTimer { 42 } },
    )
}
