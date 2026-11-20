@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.kmpjourney.pocketlab.sharedui

import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.ComposeUIViewController
import platform.UIKit.UIViewController

object PocketLabSharedUi {
    fun experimentSummaryViewController(
        title: String,
        hypothesis: String,
        status: String,
        onOpen: () -> Unit,
    ): UIViewController = ComposeUIViewController {
        ExperimentSummaryCard(
            model = ExperimentSummaryUiModel(
                title = title,
                hypothesis = hypothesis,
                status = ExperimentSummaryStatus.fromWireValue(status),
            ),
            onOpen = onOpen,
            modifier = Modifier.padding(16.dp),
        )
    }
}
