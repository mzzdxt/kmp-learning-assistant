package dev.kmpjourney.pocketlab

/**
 * Compile-time platform seams. Domain code still depends on ordinary interfaces;
 * expect/actual is confined to creating the production adapters.
 */
expect fun createPlatformClock(): EpochClock

expect fun createPlatformIdGenerator(): ExperimentIdGenerator

fun createPlatformSaveDraftUseCase(
    repository: ExperimentRepository,
): SaveDraftUseCase = SaveDraftUseCase(
    repository = repository,
    idGenerator = createPlatformIdGenerator(),
    clock = createPlatformClock(),
)

