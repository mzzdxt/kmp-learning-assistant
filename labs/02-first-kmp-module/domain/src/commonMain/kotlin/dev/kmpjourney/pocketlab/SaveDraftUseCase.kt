package dev.kmpjourney.pocketlab

/** A common contract; the storage technology remains an implementation choice. */
interface ExperimentRepository {
    suspend fun save(experiment: SavedExperiment)
}

interface ExperimentIdGenerator {
    fun nextId(): String
}

interface EpochClock {
    fun nowMilliseconds(): Long
}

sealed interface SaveDraftResult {
    data class Saved(val experiment: SavedExperiment) : SaveDraftResult

    data class Rejected(val problems: List<DraftProblem>) : SaveDraftResult
}

class SaveDraftUseCase(
    private val repository: ExperimentRepository,
    private val idGenerator: ExperimentIdGenerator,
    private val clock: EpochClock,
    private val validator: DraftValidator = DraftValidator(),
) {
    suspend fun execute(draft: ExperimentDraft): SaveDraftResult {
        val problems = validator.validate(draft)
        if (problems.isNotEmpty()) return SaveDraftResult.Rejected(problems)

        val experiment = SavedExperiment(
            id = idGenerator.nextId(),
            title = draft.title.trim(),
            hypothesis = draft.hypothesis.trim(),
            notes = draft.notes.trim(),
            savedAtEpochMilliseconds = clock.nowMilliseconds(),
        )
        repository.save(experiment)
        return SaveDraftResult.Saved(experiment)
    }
}

