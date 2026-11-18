package dev.kmpjourney.pocketlab

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class DraftSaveStatus {
    IDLE,
    SAVING,
    SAVED,
    REJECTED,
    FAILED,
}

data class DraftEditorState(
    val title: String = "",
    val hypothesis: String = "",
    val notes: String = "",
    val saveStatus: DraftSaveStatus = DraftSaveStatus.IDLE,
    val validationProblems: List<DraftProblem> = emptyList(),
    val savedExperiment: SavedExperiment? = null,
)

/**
 * A lifecycle-neutral state holder. The platform owns [scope] and must cancel it
 * when the corresponding screen or presentation model is destroyed.
 */
class DraftEditorStore(
    private val saveDraft: SaveDraftUseCase,
    private val scope: CoroutineScope,
) {
    private val mutableState = MutableStateFlow(DraftEditorState())
    val state: StateFlow<DraftEditorState> = mutableState.asStateFlow()

    fun updateTitle(value: String) = editUnlessSaving { copy(title = value) }

    fun updateHypothesis(value: String) = editUnlessSaving { copy(hypothesis = value) }

    fun updateNotes(value: String) = editUnlessSaving { copy(notes = value) }

    /** Returns false when a save is already running. */
    fun save(): Boolean {
        val draft = beginSaveOrNull() ?: return false

        scope.launch {
            try {
                when (val result = saveDraft.execute(draft)) {
                    is SaveDraftResult.Saved -> updateWhileSaving {
                        copy(
                            saveStatus = DraftSaveStatus.SAVED,
                            savedExperiment = result.experiment,
                        )
                    }

                    is SaveDraftResult.Rejected -> updateWhileSaving {
                        copy(
                            saveStatus = DraftSaveStatus.REJECTED,
                            validationProblems = result.problems,
                        )
                    }
                }
            } catch (cancellation: CancellationException) {
                updateWhileSaving { copy(saveStatus = DraftSaveStatus.IDLE) }
                throw cancellation
            } catch (_: Exception) {
                updateWhileSaving { copy(saveStatus = DraftSaveStatus.FAILED) }
            }
        }
        return true
    }

    private fun beginSaveOrNull(): ExperimentDraft? {
        while (true) {
            val current = mutableState.value
            if (current.saveStatus == DraftSaveStatus.SAVING) return null

            val saving = current.copy(
                saveStatus = DraftSaveStatus.SAVING,
                validationProblems = emptyList(),
                savedExperiment = null,
            )
            if (mutableState.compareAndSet(current, saving)) {
                return ExperimentDraft(
                    title = current.title,
                    hypothesis = current.hypothesis,
                    notes = current.notes,
                )
            }
        }
    }

    private inline fun editUnlessSaving(
        transform: DraftEditorState.() -> DraftEditorState,
    ) {
        mutableState.update { current ->
            if (current.saveStatus == DraftSaveStatus.SAVING) {
                current
            } else {
                current.transform().copy(
                    saveStatus = DraftSaveStatus.IDLE,
                    validationProblems = emptyList(),
                    savedExperiment = null,
                )
            }
        }
    }

    private inline fun updateWhileSaving(
        transform: DraftEditorState.() -> DraftEditorState,
    ) {
        mutableState.update { current ->
            if (current.saveStatus == DraftSaveStatus.SAVING) current.transform() else current
        }
    }
}

