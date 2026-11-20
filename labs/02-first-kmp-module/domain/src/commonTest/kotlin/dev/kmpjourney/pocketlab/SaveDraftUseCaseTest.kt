package dev.kmpjourney.pocketlab

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class SaveDraftUseCaseTest {
    @Test
    fun rejectedDraftDoesNotTouchSideEffectDependencies() = runTest {
        val repository = RecordingRepository()
        val idGenerator = CountingIdGenerator()
        val clock = CountingClock()
        val useCase = SaveDraftUseCase(repository, idGenerator, clock)

        val result = useCase.execute(
            ExperimentDraft(title = "", hypothesis = "", notes = "ignored"),
        )

        assertIs<SaveDraftResult.Rejected>(result)
        assertNull(repository.saved)
        assertEquals(0, idGenerator.calls)
        assertEquals(0, clock.calls)
    }

    @Test
    fun validDraftIsNormalizedAndSavedWithInjectedIdentityAndTime() = runTest {
        val repository = RecordingRepository()
        val useCase = SaveDraftUseCase(
            repository = repository,
            idGenerator = FixedIdGenerator("experiment-42"),
            clock = FixedClock(1_700_000_000_123),
        )

        val result = useCase.execute(
            ExperimentDraft(
                title = "  Does dark mode save battery?  ",
                hypothesis = " OLED devices will consume less power. ",
                notes = " Measure at equal brightness. \n",
            ),
        )

        val saved = assertIs<SaveDraftResult.Saved>(result).experiment
        assertEquals("experiment-42", saved.id)
        assertEquals("Does dark mode save battery?", saved.title)
        assertEquals("OLED devices will consume less power.", saved.hypothesis)
        assertEquals("Measure at equal brightness.", saved.notes)
        assertEquals(1_700_000_000_123, saved.savedAtEpochMilliseconds)
        assertEquals(saved, repository.saved)
    }

    @Test
    fun resultAndRepositoryReceiveTheSameImmutableValue() = runTest {
        val repository = RecordingRepository()
        val result = SaveDraftUseCase(
            repository = repository,
            idGenerator = FixedIdGenerator("id-1"),
            clock = FixedClock(99),
        ).execute(
            ExperimentDraft(title = "Title", hypothesis = "Hypothesis", notes = ""),
        )

        assertEquals(
            assertIs<SaveDraftResult.Saved>(result).experiment,
            repository.saved,
        )
    }

    private class RecordingRepository : ExperimentRepository {
        var saved: SavedExperiment? = null

        override suspend fun save(experiment: SavedExperiment) {
            saved = experiment
        }
    }

    private class FixedIdGenerator(private val id: String) : ExperimentIdGenerator {
        override fun nextId(): String = id
    }

    private class FixedClock(private val value: Long) : EpochClock {
        override fun nowMilliseconds(): Long = value
    }

    private class CountingIdGenerator : ExperimentIdGenerator {
        var calls = 0

        override fun nextId(): String {
            calls += 1
            return "unused"
        }
    }

    private class CountingClock : EpochClock {
        var calls = 0

        override fun nowMilliseconds(): Long {
            calls += 1
            return 0
        }
    }
}

