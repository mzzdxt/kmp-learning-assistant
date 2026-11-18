import SwiftUI
@preconcurrency import PocketLabShared

@main
@MainActor
struct PocketLabIosApp: App {
    @StateObject private var viewModel = ExperimentListViewModel(
        feature: IosPocketLabFeatures.shared.demo(
            databaseName: "pocketlab-demo.db"
        )
    )

    var body: some Scene {
        WindowGroup {
            ExperimentListScreen(viewModel: viewModel)
        }
    }
}
