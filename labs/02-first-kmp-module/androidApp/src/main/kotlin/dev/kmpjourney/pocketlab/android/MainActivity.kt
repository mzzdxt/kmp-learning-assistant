package dev.kmpjourney.pocketlab.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.kmpjourney.pocketlab.android.ui.ExperimentListScreen
import dev.kmpjourney.pocketlab.android.ui.PocketLabTheme

class MainActivity : ComponentActivity() {
    private val viewModel: ExperimentListViewModel by viewModels {
        ExperimentListViewModel.Factory(
            (application as PocketLabApplication).graph,
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            PocketLabTheme {
                val state by viewModel.uiState.collectAsStateWithLifecycle()
                ExperimentListScreen(
                    state = state,
                    onRefresh = viewModel::refresh,
                )
            }
        }
    }
}
