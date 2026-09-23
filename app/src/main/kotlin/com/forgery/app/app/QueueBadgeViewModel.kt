package com.forgery.app.app

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.forgery.app.core.data.QueueRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@HiltViewModel
class QueueBadgeViewModel @Inject constructor(
    queueRepository: QueueRepository,
) : ViewModel() {

    val pendingCount: StateFlow<Int> = combine(
        queueRepository.observeJobs(),
        queueRepository.observeResults(),
    ) { jobs, results ->
        jobs.count { job -> results.none { it.jobId == job.id } }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = 0,
    )
}

/** Badge text for the QUE tab: full count, truncated so the circle keeps its shape. */
internal fun formatBadgeCount(count: Int): String = if (count > 99) "99+" else count.toString()
