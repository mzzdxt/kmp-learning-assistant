package dev.kmpjourney.pocketlab.android

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class ExperimentListUiState(
    val experiments: List<ExperimentListItem> = emptyList(),
    val isRefreshing: Boolean = false,
    val notice: RefreshNotice? = null,
)

sealed interface RefreshNotice {
    data class Applied(val changedCount: Int) : RefreshNotice
    data class Failed(val reason: ExperimentRefreshFailure) : RefreshNotice
    data object Unexpected : RefreshNotice
}

class ExperimentListViewModel internal constructor(
    private val gateway: ExperimentListGateway,
    autoRefresh: Boolean = true,
) : ViewModel() {
    private val refreshPhase = MutableStateFlow<RefreshPhase>(RefreshPhase.Idle)
    private var refreshJob: Job? = null

    val uiState = combine(gateway.observeExperiments(), refreshPhase) { experiments, phase ->
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
                when (val result = gateway.refresh()) {
                    is ExperimentRefreshResult.Applied ->
                        RefreshPhase.Applied(result.changedCount)

                    is ExperimentRefreshResult.Failed ->
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
        data class Failed(val reason: ExperimentRefreshFailure) : RefreshPhase
        data object Unexpected : RefreshPhase
    }

    class Factory(
        private val graph: AndroidPocketLabGraph,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(ExperimentListViewModel::class.java))
            return ExperimentListViewModel(
                gateway = graph.experimentListGateway,
            ) as T
        }
    }
}
