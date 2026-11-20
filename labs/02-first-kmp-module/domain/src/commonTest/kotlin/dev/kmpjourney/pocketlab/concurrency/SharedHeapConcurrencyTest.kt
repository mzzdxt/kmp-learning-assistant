package dev.kmpjourney.pocketlab.concurrency

import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.newSingleThreadContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(DelicateCoroutinesApi::class, ExperimentalCoroutinesApi::class)
class SharedHeapConcurrencyTest {
    @Test
    fun mutableLedgerCrossesTwoDispatchersWhenAccessIsSerialized() = runTest {
        val firstDispatcher = newSingleThreadContext("ledger-first")
        val secondDispatcher = newSingleThreadContext("ledger-second")
        val ledger = ConcurrentLedger()

        try {
            val observedCounts = coroutineScope {
                (1..200).map { index ->
                    async(if (index % 2 == 0) firstDispatcher else secondDispatcher) {
                        ledger.record("shared-experiment")
                    }
                }.awaitAll()
            }

            assertEquals((1..200).toSet(), observedCounts.toSet())
            assertEquals(mapOf("shared-experiment" to 200), ledger.snapshot())
        } finally {
            firstDispatcher.close()
            secondDispatcher.close()
        }
    }
}

/** A deliberately small model of shared mutable state; the Mutex owns its serialization rule. */
private class ConcurrentLedger {
    private val mutex = Mutex()
    private val counts = mutableMapOf<String, Int>()

    suspend fun record(experimentId: String): Int = mutex.withLock {
        val next = counts.getOrElse(experimentId) { 0 } + 1
        counts[experimentId] = next
        next
    }

    suspend fun snapshot(): Map<String, Int> = mutex.withLock { counts.toMap() }
}
