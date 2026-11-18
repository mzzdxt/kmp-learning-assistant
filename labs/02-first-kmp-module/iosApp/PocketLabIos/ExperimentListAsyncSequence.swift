import Foundation
@preconcurrency import PocketLabShared

struct ExperimentObservationError: LocalizedError, Sendable {
    let message: String

    var errorDescription: String? { message }
}

extension IosExperimentListBridge {
    func experiments() -> AsyncThrowingStream<[ExperimentRecord], any Error> {
        AsyncThrowingStream { continuation in
            let cancellation = NativeCancellationBox()
            let token = subscribe(
                onChange: { records in
                    continuation.yield(records)
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
        -> AsyncThrowingStream<any RefreshExperimentsResult, any Error> {
        AsyncThrowingStream(bufferingPolicy: .bufferingNewest(1)) { continuation in
            let cancellation = NativeCancellationBox()
            let token = refresh(
                onResult: { result in
                    continuation.yield(result)
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
            return result
        }
        throw CancellationError()
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
