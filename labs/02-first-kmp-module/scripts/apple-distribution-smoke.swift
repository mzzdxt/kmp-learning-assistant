import PocketLabShared

func verifyPocketLabDistributionSurface() {
    let feature: PocketLabFeature = PocketLabFeatures.shared.demo(
        databaseName: "distribution-smoke.db"
    )
    let client: ExperimentListClient = feature.experimentList
    let cancellationToken: CancellationToken? = nil

    _ = client
    _ = cancellationToken
    feature.close()
}
