package com.forgery.app.feature.queue.impl

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.forgery.app.core.data.QueueRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class QueueViewModel @Inject constructor(
    private val queueRepository: QueueRepository,
) : ViewModel() {

    private val pendingDialogFlow = MutableStateFlow<PendingClearDialogState?>(null)

    val uiState: StateFlow<QueueUiState> = combine(
        queueRepository.observeSnapshot(),
        queueRepository.observeJobs(),
        queueRepository.observeResults(),
        pendingDialogFlow,
    ) { snapshot, jobs, results, dialog ->
        QueueUiState.Success(
            snapshot = snapshot,
            jobs = jobs,
            results = results,
            pendingDialog = dialog,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = QueueUiState.Loading,
    )

    fun onAction(action: QueueAction) {
        when (action) {
            QueueAction.Start -> viewModelScope.launch { queueRepository.start() }
            QueueAction.Cancel -> viewModelScope.launch { queueRepository.cancel() }
            QueueAction.RequestClearPending -> {
                val current = uiState.value as? QueueUiState.Success ?: return
                val snapshot = current.snapshot
                pendingDialogFlow.value = if (snapshot?.running == true) {
                    val from = (snapshot.currentIndex + 1).coerceIn(0, current.jobs.size)
                    val upcoming = current.jobs.drop(from).count { job ->
                        current.results.none { it.jobId == job.id }
                    }
                    PendingClearDialogState.Running(upcoming)
                } else {
                    PendingClearDialogState.Idle(current.pendingJobs.size)
                }
            }
            QueueAction.ConfirmClearPending -> viewModelScope.launch {
                queueRepository.clearPending()
                pendingDialogFlow.value = null
            }
            QueueAction.DismissClearDialog -> {
                pendingDialogFlow.value = null
            }
            QueueAction.RequestClearCompleted -> viewModelScope.launch {
                queueRepository.clearCompleted()
            }
            QueueAction.ConfirmClearCompleted -> viewModelScope.launch {
                queueRepository.clearCompleted()
            }
            is QueueAction.DeleteJob -> viewModelScope.launch {
                queueRepository.removeJob(action.jobId)
            }
            is QueueAction.MoveJob -> viewModelScope.launch {
                queueRepository.moveJob(action.jobId, action.toPendingIndex)
            }
        }
    }
}

sealed interface QueueAction {
    data object Start : QueueAction
    data object Cancel : QueueAction
    data object RequestClearPending : QueueAction
    data object ConfirmClearPending : QueueAction
    data object DismissClearDialog : QueueAction
    data object RequestClearCompleted : QueueAction
    data object ConfirmClearCompleted : QueueAction
    data class DeleteJob(val jobId: String) : QueueAction
    data class MoveJob(val jobId: String, val toPendingIndex: Int) : QueueAction
}
