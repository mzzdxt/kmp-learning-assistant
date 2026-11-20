@file:OptIn(kotlin.experimental.ExperimentalObjCName::class)

package dev.kmpjourney.pocketlab.interop

import dev.kmpjourney.pocketlab.ExperimentRecord
import dev.kmpjourney.pocketlab.ExperimentSyncState
import dev.kmpjourney.pocketlab.sync.RefreshExperimentsResult
import dev.kmpjourney.pocketlab.sync.RefreshFailureReason
import kotlin.native.ObjCName

/**
 * Immutable Apple-facing experiment snapshot.
 *
 * The shared domain model stays platform-neutral. This type deliberately exports
 * only the fields the Apple UI consumes and replaces the Kotlin enum with a
 * Boolean so Swift does not need to understand a non-exhaustive imported enum.
 */
@ObjCName(swiftName = "ExperimentSnapshot")
class IosExperimentSnapshot internal constructor(
    val id: String,
    val title: String,
    val hypothesis: String,
    val notes: String,
    val updatedAtEpochMilliseconds: Long,
    val isPendingUpload: Boolean,
)

/**
 * Closed result shape for one refresh operation.
 *
 * Nullable Kotlin primitives become boxed NSNumber subclasses in Objective-C
 * export, so the optional status is represented by a value plus an explicit flag.
 */
@ObjCName(swiftName = "RefreshReport")
class IosRefreshReport internal constructor(
    val isSuccessful: Boolean,
    val receivedCount: Int,
    val changedCount: Int,
    val failureCode: String?,
    val hasFailureStatusCode: Boolean,
    val failureStatusCode: Int,
)

/** A stable machine code plus diagnostic text for an unexpected bridge failure. */
@ObjCName(swiftName = "InteropFailure")
class IosInteropFailure internal constructor(
    val code: String,
    val message: String,
)

internal fun ExperimentRecord.toIosSnapshot() = IosExperimentSnapshot(
    id = id,
    title = title,
    hypothesis = hypothesis,
    notes = notes,
    updatedAtEpochMilliseconds = updatedAtEpochMilliseconds,
    isPendingUpload = syncState == ExperimentSyncState.PENDING_UPLOAD,
)

internal fun RefreshExperimentsResult.toIosReport(): IosRefreshReport = when (this) {
    is RefreshExperimentsResult.Applied -> IosRefreshReport(
        isSuccessful = true,
        receivedCount = receivedCount,
        changedCount = changedCount,
        failureCode = null,
        hasFailureStatusCode = false,
        failureStatusCode = 0,
    )

    is RefreshExperimentsResult.Failed -> IosRefreshReport(
        isSuccessful = false,
        receivedCount = 0,
        changedCount = 0,
        failureCode = reason.appleCode,
        hasFailureStatusCode = statusCode != null,
        failureStatusCode = statusCode ?: 0,
    )
}

private val RefreshFailureReason.appleCode: String
    get() = when (this) {
        RefreshFailureReason.UNAUTHORIZED -> "unauthorized"
        RefreshFailureReason.SERVER -> "server"
        RefreshFailureReason.UNEXPECTED_STATUS -> "unexpected_status"
        RefreshFailureReason.MALFORMED_RESPONSE -> "malformed_response"
        RefreshFailureReason.CONNECTION -> "connection"
    }
