package dev.kmpjourney.pocketlab.android.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.kmpjourney.pocketlab.android.ExperimentListItem
import dev.kmpjourney.pocketlab.android.ExperimentListUiState
import dev.kmpjourney.pocketlab.android.ExperimentRefreshFailure
import dev.kmpjourney.pocketlab.android.ExperimentSyncStatus
import dev.kmpjourney.pocketlab.android.R
import dev.kmpjourney.pocketlab.android.RefreshNotice
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun ExperimentListScreen(
    state: ExperimentListUiState,
    onRefresh: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = stringResource(R.string.app_name),
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            text = stringResource(R.string.app_tagline),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                actions = {
                    TextButton(
                        onClick = onRefresh,
                        enabled = !state.isRefreshing,
                    ) {
                        Text(stringResource(R.string.refresh))
                    }
                },
            )
        },
    ) { contentPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding),
        ) {
            if (state.isRefreshing) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            state.notice?.let { notice ->
                RefreshNoticeBanner(notice)
            }
            if (state.experiments.isEmpty()) {
                EmptyState(
                    isRefreshing = state.isRefreshing,
                    onRefresh = onRefresh,
                    modifier = Modifier.weight(1f),
                )
            } else {
                ExperimentList(
                    experiments = state.experiments,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun RefreshNoticeBanner(notice: RefreshNotice) {
    val text = when (notice) {
        is RefreshNotice.Applied -> if (notice.changedCount == 0) {
            stringResource(R.string.refresh_no_change)
        } else {
            stringResource(R.string.refresh_applied, notice.changedCount)
        }

        is RefreshNotice.Failed -> stringResource(notice.reason.messageResource())
        RefreshNotice.Unexpected -> stringResource(R.string.refresh_unexpected)
    }
    Surface(
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
    ) {
        Text(
            text = text,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 10.dp),
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun EmptyState(
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.padding(32.dp),
        ) {
            Text(
                text = if (isRefreshing) {
                    stringResource(R.string.refreshing)
                } else {
                    stringResource(R.string.empty_title)
                },
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = stringResource(R.string.empty_body),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (!isRefreshing) {
                Button(onClick = onRefresh) {
                    Text(stringResource(R.string.refresh))
                }
            }
        }
    }
}

@Composable
private fun ExperimentList(
    experiments: List<ExperimentListItem>,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(experiments, key = ExperimentListItem::id) { experiment ->
            ExperimentCard(experiment)
        }
    }
}

@Composable
private fun ExperimentCard(experiment: ExperimentListItem) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.padding(18.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = experiment.title,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                SyncBadge(experiment.syncStatus)
            }
            LabeledText(
                label = stringResource(R.string.hypothesis_label),
                value = experiment.hypothesis,
            )
            if (experiment.notes.isNotBlank()) {
                LabeledText(
                    label = stringResource(R.string.notes_label),
                    value = experiment.notes,
                )
            }
            Spacer(Modifier.height(2.dp))
            Text(
                text = remember(experiment.updatedAtEpochMilliseconds) {
                    DateTimeFormatter
                        .ofLocalizedDate(FormatStyle.MEDIUM)
                        .withZone(ZoneId.systemDefault())
                        .format(Instant.ofEpochMilli(experiment.updatedAtEpochMilliseconds))
                },
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun LabeledText(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.Bold,
        )
        Text(text = value, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun SyncBadge(syncStatus: ExperimentSyncStatus) {
    val pending = syncStatus == ExperimentSyncStatus.PENDING_UPLOAD
    Surface(
        shape = RoundedCornerShape(50),
        color = if (pending) {
            MaterialTheme.colorScheme.secondaryContainer
        } else {
            MaterialTheme.colorScheme.primaryContainer
        },
    ) {
        Text(
            text = stringResource(
                if (pending) R.string.pending_upload else R.string.synced,
            ),
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            style = MaterialTheme.typography.labelMedium,
        )
    }
}

@StringRes
private fun ExperimentRefreshFailure.messageResource(): Int = when (this) {
    ExperimentRefreshFailure.UNAUTHORIZED -> R.string.refresh_unauthorized
    ExperimentRefreshFailure.SERVER -> R.string.refresh_server
    ExperimentRefreshFailure.UNEXPECTED_STATUS -> R.string.refresh_unexpected_status
    ExperimentRefreshFailure.MALFORMED_RESPONSE -> R.string.refresh_malformed
    ExperimentRefreshFailure.CONNECTION -> R.string.refresh_connection
}
