package dev.kmpjourney.pocketlab.memory

import kotlin.experimental.ExperimentalNativeApi
import kotlin.native.ref.WeakReference
import kotlin.native.runtime.GC
import kotlin.native.runtime.NativeRuntimeApi
import kotlin.test.Test
import kotlin.test.assertNull

@OptIn(ExperimentalNativeApi::class, NativeRuntimeApi::class)
class NativeGarbageCollectorTest {
    @Test
    fun unreachablePureKotlinCycleIsCollected() {
        val weakFirstNode = createUnreachableCycle()

        GC.collect()

        assertNull(weakFirstNode.value)
    }

    private fun createUnreachableCycle(): WeakReference<CycleNode> {
        val first = CycleNode()
        val second = CycleNode()
        first.next = second
        second.next = first
        return WeakReference(first)
    }
}

private class CycleNode {
    var next: CycleNode? = null
}
