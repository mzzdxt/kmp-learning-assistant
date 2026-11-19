import Foundation
@preconcurrency import PocketLabShared

struct ExperimentObservationError: LocalizedError, Sendable {
    let message: String

    var errorDescription: String? { message }
}

/// Swift 5.5+ imports the generated completion-handler method as async/throws.
/// This is concise, but cancelling the Swift Task does not reliably cancel the Kotlin coroutine.
func refreshDirectly(
    with useCase: RefreshExperimentsUseCase
) async throws -> any RefreshExperimentsResult {
    try await useCase.execute()
}

extension IosExperimentListBridge {
    /// Turns the callback export into the abstraction Swift callers expect.
    func experiments()
        -> AsyncThrowingStream<UncheckedSendable<[ExperimentRecord]>, any Error> {
        AsyncThrowingStream { continuation in
            let cancellation = NativeCancellationBox()
            let token = subscribe(
                onChange: { records in
                    continuation.yield(UncheckedSendable(records))
                },
                onFailure: { message in
                    continuation.finish(
                        throwing: ExperimentObservationError(message: message)
                    )
                }
            )
            cancellation.install(token)
            continuation.onTermination = { @Sendable _ in
                cancellation.cancel()
            }
        }
    }

    /// A one-element stream keeps Swift Task cancellation wired to the Kotlin Job.
    private func refreshResults()
        -> AsyncThrowingStream<
            UncheckedSendable<any RefreshExperimentsResult>,
            any Error
        > {
        AsyncThrowingStream(bufferingPolicy: .bufferingNewest(1)) { continuation in
            let cancellation = NativeCancellationBox()
            let token = refresh(
                onResult: { result in
                    continuation.yield(UncheckedSendable(result))
                    continuation.finish()
                },
                onFailure: { message in
                    continuation.finish(
                        throwing: ExperimentObservationError(message: message)
                    )
                }
            )
            cancellation.install(token)
            continuation.onTermination = { @Sendable _ in
                cancellation.cancel()
            }
        }
    }

    func refreshOnce() async throws -> any RefreshExperimentsResult {
        for try await result in refreshResults() {
            return result.value
        }
        throw CancellationError()
    }
}

/// Kotlin/Native's Objective-C export has no Swift `Sendable` metadata. The
/// Kotlin bridge promises Main-dispatcher callbacks; this narrow box records
/// that manually audited boundary without disabling Swift 6 checks globally.
struct UncheckedSendable<Value>: @unchecked Sendable {
    let value: Value

    init(_ value: Value) {
        self.value = value
    }
}

/// Handles the race where a Swift task is cancelled before Kotlin returns its token.
private final class NativeCancellationBox: @unchecked Sendable {
    private let lock = NSLock()
    private var token: NativeCancellable?
    private var cancelled = false

    func install(_ token: NativeCancellable) {
        lock.lock()
        if cancelled {
            lock.unlock()
            token.cancel()
        } else {
            self.token = token
            lock.unlock()
        }
    }

    func cancel() {
        lock.lock()
        cancelled = true
        let current = token
        token = nil
        lock.unlock()
        current?.cancel()
    }
}
