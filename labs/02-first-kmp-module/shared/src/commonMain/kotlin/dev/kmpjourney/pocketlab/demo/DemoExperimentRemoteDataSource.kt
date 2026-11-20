@file:OptIn(kotlin.experimental.ExperimentalObjCRefinement::class)

package dev.kmpjourney.pocketlab.demo

import dev.kmpjourney.pocketlab.network.ExperimentRemoteDataSource
import dev.kmpjourney.pocketlab.network.RemoteExperiment
import dev.kmpjourney.pocketlab.network.RemoteExperimentsResult
import kotlinx.coroutines.delay
import kotlin.native.HiddenFromObjC

/** Deterministic sample data for the two native UI shells; never a production backend. */
@HiddenFromObjC
class DemoExperimentRemoteDataSource(
    private val latencyMilliseconds: Long = 300,
) : ExperimentRemoteDataSource {
    override suspend fun fetchExperiments(): RemoteExperimentsResult {
        if (latencyMilliseconds > 0) delay(latencyMilliseconds)
        return RemoteExperimentsResult.Success(experiments)
    }

    private val experiments = listOf(
        RemoteExperiment(
            id = "sleep-phone-outside-bedroom",
            title = "把手机留在客厅",
            hypothesis = "睡前把手机放在客厅，会让我平均提前 20 分钟入睡。",
            notes = "连续观察七天，并记录上床时间与实际入睡时间。",
            updatedAtEpochMilliseconds = 1_794_931_200_000,
        ),
        RemoteExperiment(
            id = "focus-one-tab",
            title = "工作时只留一个浏览器标签页",
            hypothesis = "减少可见任务会降低切换冲动，让 45 分钟专注块更完整。",
            notes = "记录中断次数，而不是只凭当天感受判断。",
            updatedAtEpochMilliseconds = 1_794_844_800_000,
        ),
        RemoteExperiment(
            id = "walk-after-lunch",
            title = "午饭后散步十二分钟",
            hypothesis = "短距离散步会减少下午两点的困倦感。",
            notes = "用 1–5 分记录困倦程度，避免只挑成功的日子。",
            updatedAtEpochMilliseconds = 1_794_758_400_000,
        ),
    )
}
