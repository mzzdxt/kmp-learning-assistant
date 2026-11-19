import SwiftUI
@preconcurrency import PocketLabShared

@main
@MainActor
struct PocketLabIosApp: App {
    @StateObject private var viewModel = ExperimentListViewModel(
        feature: IosPocketLabFeatures.shared.demo(
            databaseName: ProcessInfo.processInfo.environment["POCKETLAB_DATABASE_NAME"]
                .flatMap { $0.isEmpty ? nil : $0 }
                ?? "pocketlab-demo.db"
        )
    )

    var body: some Scene {
        WindowGroup {
            ExperimentListScreen(viewModel: viewModel)
        }
    }
}
