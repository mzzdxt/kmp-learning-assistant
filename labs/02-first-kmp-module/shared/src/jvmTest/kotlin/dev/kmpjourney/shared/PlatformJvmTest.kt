package dev.kmpjourney.shared

import kotlin.test.Test
import kotlin.test.assertEquals

class PlatformJvmTest {
    @Test
    fun jvmActualIsSelected() {
        assertEquals("JVM", platformName())
    }
}

