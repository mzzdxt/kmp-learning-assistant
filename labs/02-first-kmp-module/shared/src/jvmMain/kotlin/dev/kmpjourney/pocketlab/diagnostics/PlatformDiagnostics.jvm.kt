package dev.kmpjourney.pocketlab.diagnostics

internal actual fun writePlatformDiagnostic(
    level: DiagnosticLevel,
    message: String,
) {
    when (level) {
        DiagnosticLevel.INFO -> System.out.println("PocketLab I $message")
        DiagnosticLevel.WARNING -> System.err.println("PocketLab W $message")
        DiagnosticLevel.ERROR -> System.err.println("PocketLab E $message")
    }
}
