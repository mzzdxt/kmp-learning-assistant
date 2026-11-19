import Foundation
@preconcurrency import PocketLabShared

struct ExperimentObservationError: LocalizedError, Sendable {
    let message: String

    var errorDescription: String? { message }
}

extension IosExperimentListBridge {
    /// Kotlin/Native Objective-C exports do not declare `Sendable`. The bridge
    /// invokes both callbacks on Dispatchers.Main; this box records that narrow,
    /// manually-audited concurrency boundary instead of weakening Swift 6 checks
    /// for the whole app.
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

struct UncheckedSendable<Value>: @unchecked Sendable {
    let value: Value

    init(_ value: Value) {
        self.value = value
    }
}

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
