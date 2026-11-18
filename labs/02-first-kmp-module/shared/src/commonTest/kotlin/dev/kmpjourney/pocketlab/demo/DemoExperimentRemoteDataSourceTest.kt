package dev.kmpjourney.pocketlab.demo

import dev.kmpjourney.pocketlab.network.RemoteExperimentsResult
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class DemoExperimentRemoteDataSourceTest {
    @Test
    fun exposesStableCrossPlatformPreviewData() = runTest {
        val result = DemoExperimentRemoteDataSource(latencyMilliseconds = 0).fetchExperiments()

        val success = assertIs<RemoteExperimentsResult.Success>(result)
        assertEquals(3, success.experiments.size)
        assertEquals(
            listOf(
                "sleep-phone-outside-bedroom",
                "focus-one-tab",
                "walk-after-lunch",
            ),
            success.experiments.map { it.id },
        )
    }
}
