package dev.kmpjourney.pocketlab.diagnostics

import platform.Foundation.NSLog

internal actual fun writePlatformDiagnostic(
    level: DiagnosticLevel,
    message: String,
) {
    // The schema stays platform-neutral. A production app may swap this adapter
    // for OSLog/Logger without changing domain or refresh behavior.
    NSLog("PocketLab ${level.name}: $message")
}
