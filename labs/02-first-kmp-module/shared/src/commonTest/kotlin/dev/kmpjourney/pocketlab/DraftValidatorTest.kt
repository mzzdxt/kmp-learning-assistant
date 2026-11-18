package dev.kmpjourney.pocketlab

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DraftValidatorTest {
    private val validator = DraftValidator()

    @Test
    fun blankRequiredFieldsReturnStableReasonCodes() {
        val problems = validator.validate(
            ExperimentDraft(title = "  ", hypothesis = "\n", notes = "optional"),
        )

        assertEquals(
            listOf(
                DraftProblem(DraftField.TITLE, DraftProblemCode.REQUIRED),
                DraftProblem(DraftField.HYPOTHESIS, DraftProblemCode.REQUIRED),
            ),
            problems,
        )
    }

    @Test
    fun valuesAtMaximumLengthAreAccepted() {
        val problems = validator.validate(
            ExperimentDraft(
                title = "T".repeat(DraftValidator.TITLE_MAX_LENGTH),
                hypothesis = "H".repeat(DraftValidator.HYPOTHESIS_MAX_LENGTH),
                notes = "",
            ),
        )

        assertTrue(problems.isEmpty())
    }

    @Test
    fun valuesOverMaximumLengthCarryTheLimitAsData() {
        val problems = validator.validate(
            ExperimentDraft(
                title = "T".repeat(DraftValidator.TITLE_MAX_LENGTH + 1),
                hypothesis = "H".repeat(DraftValidator.HYPOTHESIS_MAX_LENGTH + 1),
                notes = "",
            ),
        )

        assertEquals(
            listOf(
                DraftProblem(
                    field = DraftField.TITLE,
                    code = DraftProblemCode.TOO_LONG,
                    maximumLength = DraftValidator.TITLE_MAX_LENGTH,
                ),
                DraftProblem(
                    field = DraftField.HYPOTHESIS,
                    code = DraftProblemCode.TOO_LONG,
                    maximumLength = DraftValidator.HYPOTHESIS_MAX_LENGTH,
                ),
            ),
            problems,
        )
    }
}

