import PocketLabSharedUi

@MainActor
func buildSharedExperimentCard() {
    _ = PocketLabSharedUi.shared.experimentSummaryViewController(
        title: "把手机留在客厅",
        hypothesis: "睡前把手机放在客厅，会让我更早入睡。",
        status: "synced",
        onOpen: {}
    )
}
