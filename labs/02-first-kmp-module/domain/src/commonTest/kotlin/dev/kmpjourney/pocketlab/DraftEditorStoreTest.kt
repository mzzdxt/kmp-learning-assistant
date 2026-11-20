package dev.kmpjourney.pocketlab

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class DraftEditorStoreTest {
    @Test
    fun editingUpdatesAPlatformNeutralState() = runTest {
        val store = createStore(repository = RecordingRepository())

        store.updateTitle("Battery experiment")
        store.updateHypothesis("Dark mode saves energy")
        store.updateNotes("Use equal brightness")

        assertEquals(
            DraftEditorState(
                title = "Battery experiment",
                hypothesis = "Dark mode saves energy",
                notes = "Use equal brightness",
            ),
            store.state.value,
        )
    }

    @Test
    fun successfulSaveEmitsIdleSavingAndSaved() = runTest {
        val repository = RecordingRepository(delayMilliseconds = 1_000)
        val store = createStore(repository)
        store.updateTitle("Title")
        store.updateHypothesis("Hypothesis")
        val statuses = mutableListOf<DraftSaveStatus>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            store.state
                .map { it.saveStatus }
                .distinctUntilChanged()
                .take(3)
                .toList(statuses)
        }

        assertTrue(store.save())
        assertEquals(DraftSaveStatus.SAVING, store.state.value.saveStatus)
        advanceUntilIdle()

        assertEquals(
            listOf(
                DraftSaveStatus.IDLE,
                DraftSaveStatus.SAVING,
                DraftSaveStatus.SAVED,
            ),
            statuses,
        )
        assertNotNull(store.state.value.savedExperiment)
        assertEquals(1, repository.calls)
    }

    @Test
    fun invalidDraftBecomesRejectedWithoutWriting() = runTest {
        val repository = RecordingRepository()
        val store = createStore(repository)

        assertTrue(store.save())
        advanceUntilIdle()

        assertEquals(DraftSaveStatus.REJECTED, store.state.value.saveStatus)
        assertEquals(2, store.state.value.validationProblems.size)
        assertEquals(0, repository.calls)
        assertNull(store.state.value.savedExperiment)
    }

    @Test
    fun repositoryFailureBecomesStableFailedState() = runTest {
        val repository = RecordingRepository(failure = IllegalStateException("disk full"))
        val store = createStore(repository)
        store.updateTitle("Title")
        store.updateHypothesis("Hypothesis")

        assertTrue(store.save())
        advanceUntilIdle()

        assertEquals(DraftSaveStatus.FAILED, store.state.value.saveStatus)
        assertNull(store.state.value.savedExperiment)
        assertEquals(1, repository.calls)
    }

    @Test
    fun secondSaveIsRejectedWhileFirstSaveIsRunning() = runTest {
        val repository = RecordingRepository(delayMilliseconds = 1_000)
        val store = createStore(repository)
        store.updateTitle("Title")
        store.updateHypothesis("Hypothesis")

        assertTrue(store.save())
        assertFalse(store.save())
        advanceUntilIdle()

        assertEquals(1, repository.calls)
        assertEquals(DraftSaveStatus.SAVED, store.state.value.saveStatus)
    }

    private fun kotlinx.coroutines.test.TestScope.createStore(
        repository: RecordingRepository,
    ): DraftEditorStore = DraftEditorStore(
        saveDraft = SaveDraftUseCase(
            repository = repository,
            idGenerator = FixedIdGenerator(),
            clock = FixedClock(),
        ),
        scope = this,
    )

    private class RecordingRepository(
        private val delayMilliseconds: Long = 0,
        private val failure: Throwable? = null,
    ) : ExperimentRepository {
        var calls = 0

        override suspend fun save(experiment: SavedExperiment) {
            calls += 1
            if (delayMilliseconds > 0) delay(delayMilliseconds)
            failure?.let { throw it }
        }
    }

    private class FixedIdGenerator : ExperimentIdGenerator {
        override fun nextId(): String = "id-1"
    }

    private class FixedClock : EpochClock {
        override fun nowMilliseconds(): Long = 1_700_000_000_123
    }
}

