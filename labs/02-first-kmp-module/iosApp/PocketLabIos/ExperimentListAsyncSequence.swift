import Foundation
@preconcurrency import PocketLabShared

struct Experiment: Identifiable, Sendable {
    let id: String
    let title: String
    let hypothesis: String
    let notes: String
    let updatedAtEpochMilliseconds: Int64
    let isPendingUpload: Bool

    init(snapshot: ExperimentSnapshot) {
        id = snapshot.id
        title = snapshot.title
        hypothesis = snapshot.hypothesis
        notes = snapshot.notes
        updatedAtEpochMilliseconds = snapshot.updatedAtEpochMilliseconds
        isPendingUpload = snapshot.isPendingUpload
    }
}

enum RefreshOutcome: Sendable, Equatable {
    case updated(changedCount: Int32)
    case unchanged
    case rejected(RefreshRejection)

    init(report: RefreshReport) {
        if report.isSuccessful {
            self = report.changedCount == 0
                ? .unchanged
                : .updated(changedCount: report.changedCount)
            return
        }

        self = .rejected(
            RefreshRejection(
                code: report.failureCode ?? "unknown",
                statusCode: report.hasFailureStatusCode
                    ? report.failureStatusCode
                    : nil
            )
        )
    }
}

enum RefreshRejection: Sendable, Equatable {
    case unauthorized
    case server(statusCode: Int32?)
    case unexpectedStatus(statusCode: Int32?)
    case malformedResponse
    case connection
    case unknown(code: String, statusCode: Int32?)

    init(code: String, statusCode: Int32?) {
        switch code {
        case "unauthorized": self = .unauthorized
        case "server": self = .server(statusCode: statusCode)
        case "unexpected_status": self = .unexpectedStatus(statusCode: statusCode)
        case "malformed_response": self = .malformedResponse
        case "connection": self = .connection
        default: self = .unknown(code: code, statusCode: statusCode)
        }
    }
}

struct PocketLabClientError: LocalizedError, Sendable {
    let code: String
    let message: String

    var errorDescription: String? {
        switch code {
        case "observation_failed":
            return "暂时无法读取本地实验"
        case "refresh_failed":
            return "刷新失败，本地数据仍可阅读"
        default:
            return "操作失败，请稍后重试"
        }
    }
}

extension ExperimentListClient {
    /// Kotlin/Native Objective-C exports do not declare `Sendable`. The bridge
    /// invokes callbacks on Dispatchers.Main and immediately copies snapshots
    /// into Swift value types before crossing an AsyncSequence boundary.
    func experiments() -> AsyncThrowingStream<[Experiment], any Error> {
        AsyncThrowingStream { continuation in
            let cancellation = NativeCancellationBox()
            let token = __subscribe(
                onChange: { snapshots in
                    continuation.yield(snapshots.map(Experiment.init(snapshot:)))
                },
                onFailure: { failure in
                    continuation.finish(
                        throwing: PocketLabClientError(
                            code: failure.code,
                            message: failure.message
                        )
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
        -> AsyncThrowingStream<RefreshOutcome, any Error> {
        AsyncThrowingStream(bufferingPolicy: .bufferingNewest(1)) { continuation in
            let cancellation = NativeCancellationBox()
            let token = __refresh(
                onResult: { report in
                    continuation.yield(RefreshOutcome(report: report))
                    continuation.finish()
                },
                onFailure: { failure in
                    continuation.finish(
                        throwing: PocketLabClientError(
                            code: failure.code,
                            message: failure.message
                        )
                    )
                }
            )
            cancellation.install(token)
            continuation.onTermination = { @Sendable _ in
                cancellation.cancel()
            }
        }
    }

    func refreshOnce() async throws -> RefreshOutcome {
        for try await result in refreshResults() {
            return result
        }
        throw CancellationError()
    }
}

private final class NativeCancellationBox: @unchecked Sendable {
    private let lock = NSLock()
    private var token: CancellationToken?
    private var cancelled = false

    func install(_ token: CancellationToken) {
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
