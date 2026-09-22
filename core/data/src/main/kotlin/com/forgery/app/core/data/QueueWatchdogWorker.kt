package com.forgery.app.core.data

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.ExistingWorkPolicy
import androidx.work.workDataOf
import com.forgery.app.core.database.QueueStateDao
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/**
 * Watchdog: re-enqueues [GenerationWorker] when the persisted queue state
 * says a batch was mid-flight (process death / reboot). Ports the
 * `GenerationWatchdogWorker` + `ACTION_START_FOREGROUND_SERVICE` resume path.
 * Scheduled as 15-min periodic work on every [QueueRepository.enqueue].
 */
@HiltWorker
class QueueWatchdogWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val queueDao: QueueStateDao,
) : CoroutineWorker(appContext, params) {

    companion object {
        const val UNIQUE_WATCHDOG = "forgery_queue_watchdog"
        const val TAG_WATCHDOG = "forgery_watchdog"
    }

    override suspend fun doWork(): Result {
        val state = queueDao.get()
        if (state != null && state.running && state.currentIndex < state.total) {
            val request = OneTimeWorkRequestBuilder<GenerationWorker>()
                .setInputData(
                    workDataOf(
                        GenerationWorker.KEY_HOST to state.host,
                        GenerationWorker.KEY_JOBS to state.jobsJson,
                        GenerationWorker.KEY_ORIGIN to state.origin,
                    ),
                )
                .addTag(GenerationWorker.TAG_QUEUE)
                .build()
            WorkManager.getInstance(applicationContext)
                .enqueueUniqueWork(GenerationWorker.UNIQUE_QUEUE, ExistingWorkPolicy.KEEP, request)
        }
        return Result.success()
    }
}
