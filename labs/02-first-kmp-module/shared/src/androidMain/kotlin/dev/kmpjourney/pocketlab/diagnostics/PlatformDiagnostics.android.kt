package dev.kmpjourney.pocketlab.diagnostics

import android.util.Log

internal actual fun writePlatformDiagnostic(
    level: DiagnosticLevel,
    message: String,
) {
    when (level) {
        DiagnosticLevel.INFO -> Log.i(TAG, message)
        DiagnosticLevel.WARNING -> Log.w(TAG, message)
        DiagnosticLevel.ERROR -> Log.e(TAG, message)
    }
}

private const val TAG = "PocketLab"
