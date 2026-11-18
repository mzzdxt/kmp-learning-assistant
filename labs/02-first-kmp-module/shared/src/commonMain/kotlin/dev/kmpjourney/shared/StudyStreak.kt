package dev.kmpjourney.shared

/** A platform-independent rule: the same input must mean the same thing everywhere. */
data class StudyDay(
    val completedLessonCount: Int,
    val reflectionWritten: Boolean,
)

enum class DayResult {
    EMPTY,
    PRACTICED,
    DEEP_LEARNING,
}

class StudyStreakPolicy {
    fun evaluate(day: StudyDay): DayResult = when {
        day.completedLessonCount < 0 -> error("Lesson count cannot be negative")
        day.completedLessonCount == 0 -> DayResult.EMPTY
        day.reflectionWritten -> DayResult.DEEP_LEARNING
        else -> DayResult.PRACTICED
    }
}

