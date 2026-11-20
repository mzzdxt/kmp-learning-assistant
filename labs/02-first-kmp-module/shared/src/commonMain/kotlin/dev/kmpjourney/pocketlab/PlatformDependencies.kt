package dev.kmpjourney.pocketlab

/**
 * Compile-time platform seams. Domain code still depends on ordinary interfaces;
 * expect/actual is confined to creating the production adapters.
 */
internal expect fun createPlatformClock(): EpochClock

internal expect fun createPlatformIdGenerator(): ExperimentIdGenerator

internal fun createPlatformSaveDraftUseCase(
    repository: ExperimentRepository,
): SaveDraftUseCase = SaveDraftUseCase(
    repository = repository,
    idGenerator = createPlatformIdGenerator(),
    clock = createPlatformClock(),
)

