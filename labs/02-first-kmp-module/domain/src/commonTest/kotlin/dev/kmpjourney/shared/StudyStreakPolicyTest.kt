package dev.kmpjourney.shared

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class StudyStreakPolicyTest {
    private val policy = StudyStreakPolicy()

    @Test
    fun noLessonMeansEmptyDay() {
        assertEquals(
            DayResult.EMPTY,
            policy.evaluate(StudyDay(completedLessonCount = 0, reflectionWritten = true)),
        )
    }

    @Test
    fun lessonWithoutReflectionMeansPracticed() {
        assertEquals(
            DayResult.PRACTICED,
            policy.evaluate(StudyDay(completedLessonCount = 1, reflectionWritten = false)),
        )
    }

    @Test
    fun lessonAndReflectionMeansDeepLearning() {
        assertEquals(
            DayResult.DEEP_LEARNING,
            policy.evaluate(StudyDay(completedLessonCount = 1, reflectionWritten = true)),
        )
    }

    @Test
    fun negativeLessonCountIsRejected() {
        assertFailsWith<IllegalStateException> {
            policy.evaluate(StudyDay(completedLessonCount = -1, reflectionWritten = false))
        }
    }
}

