package dev.kmpjourney.pocketlab.android

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import dev.kmpjourney.pocketlab.ExperimentLocalStore
import dev.kmpjourney.pocketlab.ExperimentRecord
import dev.kmpjourney.pocketlab.sync.RefreshExperimentsResult
import dev.kmpjourney.pocketlab.sync.RefreshExperimentsUseCase
import dev.kmpjourney.pocketlab.sync.RefreshFailureReason
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class ExperimentListUiState(
    val experiments: List<ExperimentRecord> = emptyList(),
    val isRefreshing: Boolean = false,
    val notice: RefreshNotice? = null,
)

sealed interface RefreshNotice {
    data class Applied(val changedCount: Int) : RefreshNotice
    data class Failed(val reason: RefreshFailureReason) : RefreshNotice
    data object Unexpected : RefreshNotice
}

class ExperimentListViewModel internal constructor(
    private val localStore: ExperimentLocalStore,
    private val refreshExperiments: RefreshExperimentsUseCase,
    autoRefresh: Boolean = true,
) : ViewModel() {
    private val refreshPhase = MutableStateFlow<RefreshPhase>(RefreshPhase.Idle)
    private var refreshJob: Job? = null

    val uiState = combine(localStore.observeAll(), refreshPhase) { experiments, phase ->
        ExperimentListUiState(
            experiments = experiments,
            isRefreshing = phase == RefreshPhase.Refreshing,
            notice = when (phase) {
                is RefreshPhase.Applied -> RefreshNotice.Applied(phase.changedCount)
                is RefreshPhase.Failed -> RefreshNotice.Failed(phase.reason)
                RefreshPhase.Unexpected -> RefreshNotice.Unexpected
                RefreshPhase.Idle,
                RefreshPhase.Refreshing,
                -> null
            },
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = ExperimentListUiState(),
    )

    init {
        if (autoRefresh) refresh()
    }

    fun refresh() {
        if (refreshJob?.isActive == true) return
        refreshJob = viewModelScope.launch {
            refreshPhase.value = RefreshPhase.Refreshing
            refreshPhase.value = try {
                when (val result = refreshExperiments.execute()) {
                    is RefreshExperimentsResult.Applied ->
                        RefreshPhase.Applied(result.changedCount)

                    is RefreshExperimentsResult.Failed ->
                        RefreshPhase.Failed(result.reason)
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                RefreshPhase.Unexpected
            }
        }
    }

    private sealed interface RefreshPhase {
        data object Idle : RefreshPhase
        data object Refreshing : RefreshPhase
        data class Applied(val changedCount: Int) : RefreshPhase
        data class Failed(val reason: RefreshFailureReason) : RefreshPhase
        data object Unexpected : RefreshPhase
    }

    class Factory(
        private val graph: AndroidPocketLabGraph,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(ExperimentListViewModel::class.java))
            return ExperimentListViewModel(
                localStore = graph.repository,
                refreshExperiments = graph.refreshExperiments,
            ) as T
        }
    }
}
