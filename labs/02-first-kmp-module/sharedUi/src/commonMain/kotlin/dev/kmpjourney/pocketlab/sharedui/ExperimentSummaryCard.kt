@file:OptIn(kotlin.experimental.ExperimentalObjCRefinement::class)

package dev.kmpjourney.pocketlab.sharedui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.native.HiddenFromObjC

@HiddenFromObjC
enum class ExperimentSummaryStatus(
    val wireValue: String,
    val visibleLabel: String,
    val accessibilityLabel: String,
) {
    SYNCED(
        wireValue = "synced",
        visibleLabel = "已同步",
        accessibilityLabel = "同步状态：已同步",
    ),
    PENDING_UPLOAD(
        wireValue = "pending_upload",
        visibleLabel = "待上传",
        accessibilityLabel = "同步状态：等待上传",
    ),
    ;

    companion object {
        fun fromWireValue(value: String): ExperimentSummaryStatus =
            entries.firstOrNull { status -> status.wireValue == value } ?: PENDING_UPLOAD
    }
}

@HiddenFromObjC
data class ExperimentSummaryUiModel(
    val title: String,
    val hypothesis: String,
    val status: ExperimentSummaryStatus,
)

/**
 * A deliberately small shared-UI candidate.
 *
 * It owns presentation and accessibility semantics, but not navigation,
 * lifecycle, persistence, networking, or domain records.
 */
@Composable
fun ExperimentSummaryCard(
    model: ExperimentSummaryUiModel,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(Color(0xFFFFFFFF), RoundedCornerShape(16.dp))
            .border(1.dp, Color(0xFFE7E0EC), RoundedCornerShape(16.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            BasicText(
                text = model.title,
                style = TextStyle(
                    color = Color(0xFF1D1B20),
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold,
                ),
                modifier = Modifier.weight(1f),
            )
            StatusPill(model.status)
        }
        BasicText(
            text = model.hypothesis,
            style = TextStyle(
                color = Color(0xFF49454F),
                fontSize = 14.sp,
                lineHeight = 20.sp,
            ),
        )
        Box(
            modifier = Modifier
                .background(Color(0xFF6750A4), RoundedCornerShape(50))
                .clickable(onClick = onOpen)
                .semantics { role = Role.Button }
                .padding(horizontal = 18.dp, vertical = 10.dp),
        ) {
            BasicText(
                text = "查看实验",
                style = TextStyle(
                    color = Color.White,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                ),
            )
        }
    }
}

@Composable
private fun StatusPill(status: ExperimentSummaryStatus) {
    Box(
        modifier = Modifier
            .background(
                color = when (status) {
                    ExperimentSummaryStatus.SYNCED -> Color(0xFFD8EFD3)
                    ExperimentSummaryStatus.PENDING_UPLOAD -> Color(0xFFFFE1A8)
                },
                shape = RoundedCornerShape(50),
            )
            .semantics {
                stateDescription = status.accessibilityLabel
            }
            .padding(horizontal = 10.dp, vertical = 4.dp),
    ) {
        BasicText(
            text = status.visibleLabel,
            style = TextStyle(
                color = Color(0xFF1D1B20),
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
            ),
        )
    }
}
