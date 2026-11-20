@file:OptIn(kotlin.experimental.ExperimentalObjCRefinement::class)

package dev.kmpjourney.pocketlab.diagnostics

import dev.kmpjourney.pocketlab.ExperimentLocalStore
import dev.kmpjourney.pocketlab.createPlatformIdGenerator
import dev.kmpjourney.pocketlab.network.ExperimentRemoteDataSource
import dev.kmpjourney.pocketlab.sync.RefreshDiagnosticOperation
import dev.kmpjourney.pocketlab.sync.RefreshDiagnostics
import dev.kmpjourney.pocketlab.sync.RefreshExperimentsUseCase
import dev.kmpjourney.pocketlab.sync.RefreshFailureReason
import kotlin.native.HiddenFromObjC
import kotlin.time.TimeSource

/** Creates the production refresh use case with the current platform log adapter. */
@HiddenFromObjC
fun createObservedRefreshExperimentsUseCase(
    remote: ExperimentRemoteDataSource,
    local: ExperimentLocalStore,
): RefreshExperimentsUseCase {
    val ids = createPlatformIdGenerator()
    return RefreshExperimentsUseCase(
        remote = remote,
        local = local,
        diagnostics = StructuredRefreshDiagnostics(
            sink = PlatformDiagnosticSink,
            operationIdSource = OperationIdSource(ids::nextId),
        ),
    )
}

internal enum class DiagnosticLevel {
    INFO,
    WARNING,
    ERROR,
}

internal enum class RefreshDiagnosticOutcome(val wireValue: String) {
    APPLIED("applied"),
    FAILED("failed"),
    CANCELLED("cancelled"),
    CRASHED("crashed"),
}

/**
 * Fixed-schema record: there is intentionally no arbitrary message or attributes map.
 * Product titles, notes, URLs, database paths, tokens, and exception messages cannot fit.
 */
internal data class RefreshDiagnosticRecord(
    val phase: String,
    val level: DiagnosticLevel,
    val operationId: String,
    val durationMilliseconds: Long? = null,
    val outcome: RefreshDiagnosticOutcome? = null,
    val receivedCount: Int? = null,
    val changedCount: Int? = null,
    val failureReason: RefreshFailureReason? = null,
    val statusCode: Int? = null,
    val errorType: String? = null,
) {
    fun render(): String = buildList {
        add("schema=1")
        add("event=pocketlab.refresh")
        add("phase=$phase")
        add("level=${level.name.lowercase()}")
        add("operation_id=${operationId.asSafeToken()}")
        durationMilliseconds?.let { add("duration_ms=$it") }
        outcome?.let { add("outcome=${it.wireValue}") }
        receivedCount?.let { add("received_count=$it") }
        changedCount?.let { add("changed_count=$it") }
        failureReason?.let { add("failure_reason=${it.name.lowercase()}") }
        statusCode?.let { add("status_code=$it") }
        errorType?.let { add("error_type=${it.asSafeToken()}") }
    }.joinToString(" ")
}

internal fun interface DiagnosticSink {
    fun emit(record: RefreshDiagnosticRecord)
}

internal fun interface OperationIdSource {
    fun next(): String
}

internal fun interface ElapsedTimer {
    fun elapsedMilliseconds(): Long
}

internal fun interface ElapsedTimerFactory {
    fun start(): ElapsedTimer
}

internal object MonotonicElapsedTimerFactory : ElapsedTimerFactory {
    override fun start(): ElapsedTimer {
        val mark = TimeSource.Monotonic.markNow()
        return ElapsedTimer { mark.elapsedNow().inWholeMilliseconds.coerceAtLeast(0) }
    }
}

internal class StructuredRefreshDiagnostics(
    private val sink: DiagnosticSink,
    private val operationIdSource: OperationIdSource,
    private val timerFactory: ElapsedTimerFactory = MonotonicElapsedTimerFactory,
) : RefreshDiagnostics {
    override fun begin(): RefreshDiagnosticOperation {
        val operationId = operationIdSource.next().asSafeToken()
        val timer = timerFactory.start()
        sink.emitSafely(
            RefreshDiagnosticRecord(
                phase = "started",
                level = DiagnosticLevel.INFO,
                operationId = operationId,
            ),
        )
        return Operation(
            sink = sink,
            operationId = operationId,
            timer = timer,
        )
    }

    private class Operation(
        private val sink: DiagnosticSink,
        private val operationId: String,
        private val timer: ElapsedTimer,
    ) : RefreshDiagnosticOperation {
        private var terminalOutcomeEmitted = false

        override fun applied(receivedCount: Int, changedCount: Int) {
            emitTerminal(
                RefreshDiagnosticRecord(
                    phase = "finished",
                    level = DiagnosticLevel.INFO,
                    operationId = operationId,
                    durationMilliseconds = elapsedMilliseconds(),
                    outcome = RefreshDiagnosticOutcome.APPLIED,
                    receivedCount = receivedCount,
                    changedCount = changedCount,
                ),
            )
        }

        override fun failed(reason: RefreshFailureReason, statusCode: Int?) {
            emitTerminal(
                RefreshDiagnosticRecord(
                    phase = "finished",
                    level = DiagnosticLevel.WARNING,
                    operationId = operationId,
                    durationMilliseconds = elapsedMilliseconds(),
                    outcome = RefreshDiagnosticOutcome.FAILED,
                    failureReason = reason,
                    statusCode = statusCode,
                ),
            )
        }

        override fun cancelled() {
            emitTerminal(
                RefreshDiagnosticRecord(
                    phase = "finished",
                    level = DiagnosticLevel.INFO,
                    operationId = operationId,
                    durationMilliseconds = elapsedMilliseconds(),
                    outcome = RefreshDiagnosticOutcome.CANCELLED,
                ),
            )
        }

        override fun crashed(failure: Throwable) {
            emitTerminal(
                RefreshDiagnosticRecord(
                    phase = "finished",
                    level = DiagnosticLevel.ERROR,
                    operationId = operationId,
                    durationMilliseconds = elapsedMilliseconds(),
                    outcome = RefreshDiagnosticOutcome.CRASHED,
                    errorType = failure::class.simpleName ?: "Throwable",
                ),
            )
        }

        private fun elapsedMilliseconds(): Long =
            timer.elapsedMilliseconds().coerceAtLeast(0)

        private fun emitTerminal(record: RefreshDiagnosticRecord) {
            check(!terminalOutcomeEmitted) { "Refresh diagnostics already has a terminal outcome" }
            terminalOutcomeEmitted = true
            sink.emitSafely(record)
        }
    }
}

private object PlatformDiagnosticSink : DiagnosticSink {
    override fun emit(record: RefreshDiagnosticRecord) {
        writePlatformDiagnostic(record.level, record.render())
    }
}

private fun DiagnosticSink.emitSafely(record: RefreshDiagnosticRecord) {
    try {
        emit(record)
    } catch (_: Exception) {
        // Diagnostics must never replace the result or exception being diagnosed.
    }
}

private fun String.asSafeToken(): String =
    take(MAX_TOKEN_LENGTH)
        .map { character ->
            when {
                character.isLetterOrDigit() -> character
                character == '-' || character == '_' || character == '.' -> character
                else -> '_'
            }
        }
        .joinToString("")
        .ifEmpty { "unknown" }

internal expect fun writePlatformDiagnostic(
    level: DiagnosticLevel,
    message: String,
)

private const val MAX_TOKEN_LENGTH = 80
