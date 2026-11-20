package dev.kmpjourney.shared

/**
 * A deliberately tiny platform seam. The common code knows what it needs,
 * while each target supplies the implementation.
 */
internal expect fun platformName(): String

internal fun learningMessage(result: DayResult): String =
    "${platformName()}: today is ${result.name.lowercase().replace('_', ' ')}"

