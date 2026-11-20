package dev.kmpjourney.pocketlab.android.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.kmpjourney.pocketlab.android.ExperimentListItem
import dev.kmpjourney.pocketlab.android.ExperimentListUiState
import dev.kmpjourney.pocketlab.android.ExperimentSyncStatus
import dev.kmpjourney.pocketlab.android.RefreshNotice
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ExperimentListScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun syncedExperimentAndRefreshResultAreVisible() {
        composeRule.setContent {
            PocketLabTheme {
                ExperimentListScreen(
                    state = ExperimentListUiState(
                        experiments = listOf(experiment()),
                        notice = RefreshNotice.Applied(changedCount = 1),
                    ),
                    onRefresh = {},
                )
            }
        }

        composeRule.onNodeWithText("把手机留在客厅").assertIsDisplayed()
        composeRule.onNodeWithText("睡前把手机放在客厅，会让我平均提前 20 分钟入睡。")
            .assertIsDisplayed()
        composeRule.onNodeWithText("已同步").assertIsDisplayed()
        composeRule.onNodeWithText("同步完成：更新了 1 条记录").assertIsDisplayed()
    }

    @Test
    fun refreshActionDelegatesOneIntent() {
        var refreshCount = 0
        composeRule.setContent {
            PocketLabTheme {
                ExperimentListScreen(
                    state = ExperimentListUiState(experiments = listOf(experiment())),
                    onRefresh = { refreshCount += 1 },
                )
            }
        }

        composeRule.onNodeWithText("刷新").performClick()

        composeRule.runOnIdle {
            assertEquals(1, refreshCount)
        }
    }

    private fun experiment() = ExperimentListItem(
        id = "sleep-phone-outside-bedroom",
        title = "把手机留在客厅",
        hypothesis = "睡前把手机放在客厅，会让我平均提前 20 分钟入睡。",
        notes = "连续观察七天，并记录上床时间与实际入睡时间。",
        updatedAtEpochMilliseconds = 1_794_931_200_000,
        syncStatus = ExperimentSyncStatus.SYNCED,
    )
}
