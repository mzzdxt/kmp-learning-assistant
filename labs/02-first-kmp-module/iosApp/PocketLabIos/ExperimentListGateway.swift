import Foundation
@preconcurrency import PocketLabShared

/// A Swift-owned seam that lets the existing host switch implementations at composition time.
@MainActor
protocol ExperimentListGateway: AnyObject {
    func experiments() -> AsyncThrowingStream<[Experiment], any Error>
    func refresh() async throws -> RefreshOutcome
    func close()
}

/// The only production adapter that translates the KMP facade into the Swift host contract.
@MainActor
final class KmpExperimentListGateway: ExperimentListGateway {
    private let feature: PocketLabFeature

    init(feature: PocketLabFeature) {
        self.feature = feature
    }

    func experiments() -> AsyncThrowingStream<[Experiment], any Error> {
        feature.experimentList.experiments()
    }

    func refresh() async throws -> RefreshOutcome {
        try await feature.experimentList.refreshOnce()
    }

    func close() {
        feature.close()
    }
}
