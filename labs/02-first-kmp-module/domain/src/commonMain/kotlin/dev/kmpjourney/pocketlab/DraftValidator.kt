package dev.kmpjourney.pocketlab

enum class DraftField {
    TITLE,
    HYPOTHESIS,
}

/** Stable reason codes; Android and iOS are free to render localized messages. */
enum class DraftProblemCode {
    REQUIRED,
    TOO_LONG,
}

data class DraftProblem(
    val field: DraftField,
    val code: DraftProblemCode,
    val maximumLength: Int? = null,
)

class DraftValidator {
    fun validate(draft: ExperimentDraft): List<DraftProblem> = buildList {
        validateRequiredText(
            value = draft.title,
            field = DraftField.TITLE,
            maximumLength = TITLE_MAX_LENGTH,
        )
        validateRequiredText(
            value = draft.hypothesis,
            field = DraftField.HYPOTHESIS,
            maximumLength = HYPOTHESIS_MAX_LENGTH,
        )
    }

    private fun MutableList<DraftProblem>.validateRequiredText(
        value: String,
        field: DraftField,
        maximumLength: Int,
    ) {
        when {
            value.isBlank() -> add(DraftProblem(field, DraftProblemCode.REQUIRED))
            value.trim().length > maximumLength -> add(
                DraftProblem(
                    field = field,
                    code = DraftProblemCode.TOO_LONG,
                    maximumLength = maximumLength,
                ),
            )
        }
    }

    companion object {
        const val TITLE_MAX_LENGTH = 80
        const val HYPOTHESIS_MAX_LENGTH = 240
    }
}

