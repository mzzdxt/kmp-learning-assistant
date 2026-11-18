package dev.kmpjourney.pocketlab.data

import dev.kmpjourney.pocketlab.ExperimentRecord
import dev.kmpjourney.pocketlab.ExperimentSyncState
import dev.kmpjourney.pocketlab.SavedExperiment
import dev.kmpjourney.pocketlab.ServerExperimentSnapshot
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class SqlDelightExperimentRepositoryTest {
    @Test
    fun localSaveIsImmediatelyVisibleAndPendingUpload() = runTest {
        testRepository { repository ->
            val emissions = mutableListOf<List<ExperimentRecord>>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                repository.observeAll().take(2).toList(emissions)
            }
            runCurrent()

            repository.save(saved(id = "local-1", updatedAt = 20))
            runCurrent()

            assertEquals(emptyList(), emissions.first())
            assertEquals(
                listOf(record(id = "local-1", updatedAt = 20, pending = true)),
                emissions.last(),
            )
        }
    }

    @Test
    fun pendingUploadsAreOldestFirst() = runTest {
        testRepository { repository ->
            repository.save(saved(id = "newer", updatedAt = 30))
            repository.save(saved(id = "older", updatedAt = 10))

            assertEquals(
                listOf("older", "newer"),
                repository.pendingUploads().map { it.id },
            )
        }
    }

    @Test
    fun serverSnapshotInsertsSyncedRowsAndListIsNewestFirst() = runTest {
        testRepository { repository ->
            val changed = repository.mergeServerSnapshot(
                listOf(server(id = "older", updatedAt = 10), server(id = "newer", updatedAt = 30)),
            )

            assertEquals(2, changed)
            assertEquals(
                listOf(
                    record(id = "newer", updatedAt = 30, pending = false),
                    record(id = "older", updatedAt = 10, pending = false),
                ),
                repository.observeAll().first(),
            )
        }
    }

    @Test
    fun newerServerVersionReplacesSyncedRow() = runTest {
        testRepository { repository ->
            repository.mergeServerSnapshot(listOf(server("same", 10, title = "old")))

            val changed = repository.mergeServerSnapshot(
                listOf(server("same", 20, title = "new")),
            )

            assertEquals(1, changed)
            assertEquals("new", repository.observeAll().first().single().title)
        }
    }

    @Test
    fun olderServerVersionDoesNotRollBackSyncedRow() = runTest {
        testRepository { repository ->
            repository.mergeServerSnapshot(listOf(server("same", 20, title = "new")))

            val changed = repository.mergeServerSnapshot(
                listOf(server("same", 10, title = "old")),
            )

            assertEquals(0, changed)
            assertEquals("new", repository.observeAll().first().single().title)
        }
    }

    @Test
    fun pendingLocalWriteWinsEvenWhenServerTimestampIsNewer() = runTest {
        testRepository { repository ->
            repository.save(saved("same", 10, title = "local"))

            val changed = repository.mergeServerSnapshot(
                listOf(server("same", 999, title = "server")),
            )

            assertEquals(0, changed)
            assertEquals(
                record("same", 10, title = "local", pending = true),
                repository.observeAll().first().single(),
            )
        }
    }

    @Test
    fun batchMergePublishesOneCompleteSnapshot() = runTest {
        testRepository { repository ->
            val emissions = mutableListOf<List<ExperimentRecord>>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                repository.observeAll().take(2).toList(emissions)
            }
            runCurrent()

            repository.mergeServerSnapshot(
                listOf(server("one", 10), server("two", 20)),
            )
            runCurrent()

            assertEquals(listOf(0, 2), emissions.map { it.size })
        }
    }

    private suspend fun testRepository(
        block: suspend (SqlDelightExperimentRepository) -> Unit,
    ) {
        val driver = createTestSqlDriver()
        val repository = SqlDelightExperimentRepository(
            database = PocketLabSqlDatabase(driver),
            queryDispatcher = UnconfinedTestDispatcher(),
        )
        try {
            block(repository)
        } finally {
            driver.close()
        }
    }

    private fun saved(
        id: String,
        updatedAt: Long,
        title: String = id,
    ) = SavedExperiment(
        id = id,
        title = title,
        hypothesis = "hypothesis-$id",
        notes = "notes-$id",
        savedAtEpochMilliseconds = updatedAt,
    )

    private fun server(
        id: String,
        updatedAt: Long,
        title: String = id,
    ) = ServerExperimentSnapshot(
        id = id,
        title = title,
        hypothesis = "hypothesis-$id",
        notes = "notes-$id",
        updatedAtEpochMilliseconds = updatedAt,
    )

    private fun record(
        id: String,
        updatedAt: Long,
        title: String = id,
        pending: Boolean,
    ) = ExperimentRecord(
        id = id,
        title = title,
        hypothesis = "hypothesis-$id",
        notes = "notes-$id",
        updatedAtEpochMilliseconds = updatedAt,
        syncState = if (pending) {
            ExperimentSyncState.PENDING_UPLOAD
        } else {
            ExperimentSyncState.SYNCED
        },
    )
}
