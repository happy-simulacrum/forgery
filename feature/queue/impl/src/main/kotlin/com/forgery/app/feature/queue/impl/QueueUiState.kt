package com.forgery.app.feature.queue.impl

import com.forgery.app.core.model.QueueJob
import com.forgery.app.core.model.QueueResult
import com.forgery.app.core.model.QueueSnapshot

sealed interface QueueUiState {
    data object Loading : QueueUiState

    data class Success(
        val snapshot: QueueSnapshot? = null,
        val jobs: List<QueueJob> = emptyList(),
        val results: List<QueueResult> = emptyList(),
        val pendingDialog: PendingClearDialogState? = null,
    ) : QueueUiState {
        val pendingJobs: List<QueueJob>
            get() = jobs.filter { job -> results.none { it.jobId == job.id } }

        val completedJobs: List<QueueJob>
            get() = jobs.filter { job -> results.any { it.jobId == job.id } }

        fun statusOf(job: QueueJob): JobStatus {
            val result = results.firstOrNull { it.jobId == job.id }
            val error = result?.error
            return when {
                error != null -> JobStatus.Failed(error)
                result != null -> JobStatus.Done(result.files.size)
                snapshot?.running == true -> JobStatus.Pending
                else -> JobStatus.Idle
            }
        }
    }

    data class Error(val message: String) : QueueUiState
}

sealed interface JobStatus {
    data object Idle : JobStatus
    data object Pending : JobStatus
    data class Done(val images: Int) : JobStatus
    data class Failed(val message: String) : JobStatus
}

sealed interface PendingClearDialogState {
    data class Idle(val count: Int) : PendingClearDialogState
    data class Running(val upcoming: Int) : PendingClearDialogState
}
