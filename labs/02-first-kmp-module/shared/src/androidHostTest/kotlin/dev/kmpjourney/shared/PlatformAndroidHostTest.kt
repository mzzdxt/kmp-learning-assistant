package dev.kmpjourney.shared

import kotlin.test.Test
import kotlin.test.assertTrue

class PlatformAndroidHostTest {
    @Test
    fun androidActualIsSelected() {
        assertTrue(platformName().startsWith("Android "))
    }
}

