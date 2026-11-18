package dev.kmpjourney.pocketlab

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class PlatformDependenciesContractTest {
    @Test
    fun platformClockReturnsPlausibleEpochMilliseconds() {
        val value = createPlatformClock().nowMilliseconds()

        assertTrue(value in START_OF_2020..START_OF_2100)
    }

    @Test
    fun platformIdGeneratorReturnsNonBlankUniqueValues() {
        val generator = createPlatformIdGenerator()

        val first = generator.nextId()
        val second = generator.nextId()

        assertTrue(first.isNotBlank())
        assertTrue(second.isNotBlank())
        assertNotEquals(first, second)
    }

    @Test
    fun platformFactoryComposesAWorkingSaveUseCase() = runTest {
        val repository = RecordingRepository()
        val result = createPlatformSaveDraftUseCase(repository).execute(
            ExperimentDraft(
                title = "Adapter experiment",
                hypothesis = "Every target supplies working dependencies",
                notes = "",
            ),
        )

        val saved = assertIs<SaveDraftResult.Saved>(result).experiment
        assertEquals(saved, assertNotNull(repository.saved))
        assertTrue(saved.id.isNotBlank())
        assertTrue(saved.savedAtEpochMilliseconds in START_OF_2020..START_OF_2100)
    }

    private class RecordingRepository : ExperimentRepository {
        var saved: SavedExperiment? = null

        override suspend fun save(experiment: SavedExperiment) {
            saved = experiment
        }
    }

    private companion object {
        const val START_OF_2020 = 1_577_836_800_000L
        const val START_OF_2100 = 4_102_444_800_000L
    }
}

