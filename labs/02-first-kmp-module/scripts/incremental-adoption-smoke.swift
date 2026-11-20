import Foundation

/// Compile-only fixture for the pre-KMP implementation behind the Swift-owned seam.
@MainActor
private final class LegacyExperimentListGatewayFixture: ExperimentListGateway {
    func experiments() -> AsyncThrowingStream<[Experiment], any Error> {
        AsyncThrowingStream { continuation in
            continuation.yield([])
            continuation.finish()
        }
    }

    func refresh() async throws -> RefreshOutcome {
        .unchanged
    }

    func close() {}
}

@MainActor
private func typecheckLegacyHostCutover() {
    let gateway: any ExperimentListGateway = LegacyExperimentListGatewayFixture()
    let viewModel = ExperimentListViewModel(gateway: gateway)
    viewModel.requestRefresh()
    viewModel.stopManualWork()
}
