package dev.kmpjourney.shared

import kotlin.test.Test
import kotlin.test.assertTrue

class PlatformIosTest {
    @Test
    fun iosActualIsSelected() {
        assertTrue(platformName().startsWith("iOS "))
    }
}
