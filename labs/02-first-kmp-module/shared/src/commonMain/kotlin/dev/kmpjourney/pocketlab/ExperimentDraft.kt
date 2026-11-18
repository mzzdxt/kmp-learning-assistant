package dev.kmpjourney.pocketlab

/** User input before it receives an id or persistence metadata. */
data class ExperimentDraft(
    val title: String,
    val hypothesis: String,
    val notes: String,
)

/** A validated, normalized draft that is safe to hand to persistence. */
data class SavedExperiment(
    val id: String,
    val title: String,
    val hypothesis: String,
    val notes: String,
    val savedAtEpochMilliseconds: Long,
)

