package com.forgery.app.feature.queue.impl

import com.forgery.app.core.common.overallProgress
import com.forgery.app.core.data.QueueRepository
import com.forgery.app.core.model.QueueJob
import com.forgery.app.core.model.QueueResult
import com.forgery.app.core.model.QueueSnapshot
import com.forgery.app.core.testing.TestDispatcherRule
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

private class FakeQueueRepository(
    snapshot: QueueSnapshot? = null,
    jobs: List<QueueJob> = emptyList(),
    results: List<QueueResult> = emptyList(),
) : QueueRepository {
    private val snapshotFlow = MutableStateFlow(snapshot)
    private val jobsFlow = MutableStateFlow(jobs)
    private val resultsFlow = MutableStateFlow(results)
    var cancels = 0
    var starts = 0
    var clearPendingCalls = 0
    var clearCompletedCalls = 0
    val removes = mutableListOf<String>()
    val moves = mutableListOf<Pair<String, Int>>()
    override fun observeSnapshot(): Flow<QueueSnapshot?> = snapshotFlow.asStateFlow()
    override fun observeJobs(): Flow<List<QueueJob>> = jobsFlow.asStateFlow()
    override fun observeResults(): Flow<List<QueueResult>> = resultsFlow.asStateFlow()
    fun emitSnapshot(snapshot: QueueSnapshot?) {
        snapshotFlow.value = snapshot
    }
    override suspend fun stage(jobs: List<QueueJob>, origin: String) = Unit
    override suspend fun start() {
        starts++
        snapshotFlow.value = snapshotFlow.value?.copy(running = true)
    }
    override suspend fun enqueueImmediate(jobs: List<QueueJob>, origin: String) = Unit
    override suspend fun removeJob(jobId: String): Boolean {
        val snapshot = snapshotFlow.value
        val jobs = jobsFlow.value
        val index = jobs.indexOfFirst { it.id == jobId }
        if (index < 0) return false
        if (snapshot?.running == true && index == snapshot.currentIndex) return false
        removes += jobId
        jobsFlow.value = jobs.filterIndexed { i, _ -> i != index }
        resultsFlow.value = resultsFlow.value.filter { it.jobId != jobId }
        return true
    }
    override suspend fun moveJob(jobId: String, toPendingIndex: Int): Boolean {
        val jobs = jobsFlow.value
        val doneIds = resultsFlow.value.map { it.jobId }.toSet()
        val pending = jobs.filter { it.id !in doneIds }.toMutableList()
        val from = pending.indexOfFirst { it.id == jobId }
        if (from < 0 || toPendingIndex !in pending.indices) return false
        moves += jobId to toPendingIndex
        val moving = pending.removeAt(from)
        pending.add(toPendingIndex, moving)
        jobsFlow.value = jobs.filter { it.id in doneIds } + pending
        return true
    }
    override suspend fun cancel() {
        cancels++
        snapshotFlow.value = snapshotFlow.value?.copy(running = false)
    }
    override suspend fun clearPending() {
        clearPendingCalls++
        val snapshot = snapshotFlow.value
        val jobs = jobsFlow.value
        jobsFlow.value = if (snapshot?.running == true) {
            val at = (snapshot.currentIndex + 1).coerceIn(0, jobs.size)
            jobs.take(at)
        } else {
            val doneIds = resultsFlow.value.map { it.jobId }.toSet()
            jobs.filter { it.id in doneIds }
        }
    }
    override suspend fun clearCompleted() {
        clearCompletedCalls++
        val doneIds = resultsFlow.value.map { it.jobId }.toSet()
        jobsFlow.value = jobsFlow.value.filter { it.id !in doneIds }
        resultsFlow.value = resultsFlow.value.filter { it.jobId !in doneIds }
    }
}

class QueueViewModelTest {

    @get:Rule
    val dispatcherRule = TestDispatcherRule()

    @Test
    fun `snapshot jobs and results are exposed`() = runTest {
        val jobs = listOf(QueueJob("1", "a cat", "txt", "m", "{}"))
        val results = listOf(QueueResult("1", "a cat", listOf("/a.png"), null))
        val vm = QueueViewModel(
            FakeQueueRepository(
                snapshot = QueueSnapshot(true, 0, 1, "single", null),
                jobs = jobs,
                results = results,
            ),
        )
        val state = vm.uiState.first { it is QueueUiState.Success } as QueueUiState.Success
        assertEquals(true, state.snapshot?.running)
        assertEquals(1, state.jobs.size)
        assertTrue(state.statusOf(jobs.first()) is JobStatus.Done)
    }

    @Test
    fun `failed result maps to Failed status`() = runTest {
        val jobs = listOf(QueueJob("9", "x", "txt", "m", "{}"))
        val results = listOf(QueueResult("9", "x", emptyList(), "boom"))
        val vm = QueueViewModel(FakeQueueRepository(jobs = jobs, results = results))
        val state = vm.uiState.first { it is QueueUiState.Success } as QueueUiState.Success
        val status = state.statusOf(jobs.first())
        assertTrue(status is JobStatus.Failed)
        assertEquals("boom", (status as JobStatus.Failed).message)
    }

    @Test
    fun `cancel delegates to repository`() = runTest {
        val repo = FakeQueueRepository(
            snapshot = QueueSnapshot(true, 0, 1, "single", null),
        )
        val vm = QueueViewModel(repo)
        vm.uiState.first { it is QueueUiState.Success }
        vm.onAction(QueueAction.Cancel)
        assertEquals(1, repo.cancels)
        val state = vm.uiState.first { it is QueueUiState.Success } as QueueUiState.Success
        assertEquals(false, state.snapshot?.running)
    }

    @Test
    fun `start delegates to repository`() = runTest {
        val jobs = listOf(QueueJob("1", "a cat", "txt", "m", "{}"))
        val repo = FakeQueueRepository(
            snapshot = QueueSnapshot(false, 0, 1, "single", null),
            jobs = jobs,
        )
        val vm = QueueViewModel(repo)
        vm.uiState.first { it is QueueUiState.Success }
        vm.onAction(QueueAction.Start)
        assertEquals(1, repo.starts)
        val state = vm.uiState.first { it is QueueUiState.Success } as QueueUiState.Success
        assertEquals(true, state.snapshot?.running)
    }

    @Test
    fun `pending and completed selectors split jobs preserving order`() = runTest {
        val jobs = listOf(
            QueueJob("1", "one", "txt", "m", "{}"),
            QueueJob("2", "two", "txt", "m", "{}"),
            QueueJob("3", "three", "txt", "m", "{}"),
        )
        val results = listOf(
            QueueResult("1", "one", listOf("/a.png"), null),
            QueueResult("2", "two", emptyList(), "boom"),
        )
        val vm = QueueViewModel(FakeQueueRepository(jobs = jobs, results = results))
        val state = vm.uiState.first { it is QueueUiState.Success } as QueueUiState.Success
        assertEquals(listOf("3"), state.pendingJobs.map { it.id })
        assertEquals(listOf("1", "2"), state.completedJobs.map { it.id })
    }

    @Test
    fun `only completed leaves pending empty so header hides START`() = runTest {
        val jobs = listOf(
            QueueJob("1", "one", "txt", "m", "{}"),
            QueueJob("2", "two", "txt", "m", "{}"),
        )
        val results = listOf(
            QueueResult("1", "one", listOf("/a.png"), null),
            QueueResult("2", "two", listOf("/b.png"), null),
        )
        val vm = QueueViewModel(FakeQueueRepository(jobs = jobs, results = results))
        val state = vm.uiState.first { it is QueueUiState.Success } as QueueUiState.Success
        assertTrue(state.pendingJobs.isEmpty())
        assertEquals(2, state.completedJobs.size)
    }

    @Test
    fun `pending present keeps START condition true`() = runTest {
        val jobs = listOf(
            QueueJob("1", "one", "txt", "m", "{}"),
            QueueJob("2", "two", "txt", "m", "{}"),
        )
        val results = listOf(QueueResult("1", "one", listOf("/a.png"), null))
        val vm = QueueViewModel(FakeQueueRepository(jobs = jobs, results = results))
        val state = vm.uiState.first { it is QueueUiState.Success } as QueueUiState.Success
        assertTrue(state.pendingJobs.isNotEmpty())
        assertEquals(listOf("2"), state.pendingJobs.map { it.id })
        assertEquals(listOf("1"), state.completedJobs.map { it.id })
    }

    @Test
    fun `request pending when idle shows Idle dialog with pending count`() = runTest {
        val jobs = listOf(
            QueueJob("1", "one", "txt", "m", "{}"),
            QueueJob("2", "two", "txt", "m", "{}"),
        )
        val results = listOf(QueueResult("1", "one", listOf("/a.png"), null))
        val vm = QueueViewModel(
            FakeQueueRepository(
                snapshot = QueueSnapshot(false, 0, 2, "queue", null),
                jobs = jobs,
                results = results,
            ),
        )
        vm.uiState.first { it is QueueUiState.Success }
        vm.onAction(QueueAction.RequestClearPending)
        val state = vm.uiState.first { it is QueueUiState.Success } as QueueUiState.Success
        val dialog = state.pendingDialog
        assertTrue(dialog is PendingClearDialogState.Idle)
        assertEquals(1, (dialog as PendingClearDialogState.Idle).count)
    }

    @Test
    fun `request pending when running shows Running dialog with upcoming count`() = runTest {
        val jobs = listOf(
            QueueJob("1", "one", "txt", "m", "{}"),
            QueueJob("2", "two", "txt", "m", "{}"),
            QueueJob("3", "three", "txt", "m", "{}"),
        )
        val vm = QueueViewModel(
            FakeQueueRepository(
                snapshot = QueueSnapshot(true, 0, 3, "queue", null),
                jobs = jobs,
            ),
        )
        vm.uiState.first { it is QueueUiState.Success }
        vm.onAction(QueueAction.RequestClearPending)
        val state = vm.uiState.first { it is QueueUiState.Success } as QueueUiState.Success
        val dialog = state.pendingDialog
        assertTrue(dialog is PendingClearDialogState.Running)
        assertEquals(2, (dialog as PendingClearDialogState.Running).upcoming)
    }

    @Test
    fun `confirm pending delegates to repository and closes dialog`() = runTest {
        val jobs = listOf(
            QueueJob("1", "one", "txt", "m", "{}"),
            QueueJob("2", "two", "txt", "m", "{}"),
        )
        val results = listOf(QueueResult("1", "one", listOf("/a.png"), null))
        val repo = FakeQueueRepository(
            snapshot = QueueSnapshot(false, 0, 2, "queue", null),
            jobs = jobs,
            results = results,
        )
        val vm = QueueViewModel(repo)
        vm.uiState.first { it is QueueUiState.Success }
        vm.onAction(QueueAction.RequestClearPending)
        var state = vm.uiState.first { it is QueueUiState.Success } as QueueUiState.Success
        assertTrue(state.pendingDialog is PendingClearDialogState.Idle)
        vm.onAction(QueueAction.ConfirmClearPending)
        assertEquals(1, repo.clearPendingCalls)
        state = vm.uiState.first {
            it is QueueUiState.Success && it.pendingDialog == null
        } as QueueUiState.Success
        assertNull(state.pendingDialog)
        assertEquals(listOf("1"), state.jobs.map { it.id })
        assertEquals(listOf("1"), state.completedJobs.map { it.id })
        assertEquals(emptyList<String>(), state.pendingJobs.map { it.id })
    }

    @Test
    fun `dismiss closes pending dialog without clearing`() = runTest {
        val jobs = listOf(QueueJob("1", "one", "txt", "m", "{}"))
        val repo = FakeQueueRepository(
            snapshot = QueueSnapshot(false, 0, 1, "queue", null),
            jobs = jobs,
        )
        val vm = QueueViewModel(repo)
        vm.uiState.first { it is QueueUiState.Success }
        vm.onAction(QueueAction.RequestClearPending)
        var state = vm.uiState.first { it is QueueUiState.Success } as QueueUiState.Success
        assertTrue(state.pendingDialog is PendingClearDialogState.Idle)
        vm.onAction(QueueAction.DismissClearDialog)
        state = vm.uiState.first {
            it is QueueUiState.Success && it.pendingDialog == null
        } as QueueUiState.Success
        assertNull(state.pendingDialog)
        assertEquals(0, repo.clearPendingCalls)
    }

    @Test
    fun `completed confirm delegates to repository`() = runTest {
        val jobs = listOf(
            QueueJob("1", "one", "txt", "m", "{}"),
            QueueJob("2", "two", "txt", "m", "{}"),
        )
        val results = listOf(QueueResult("1", "one", listOf("/a.png"), null))
        val repo = FakeQueueRepository(jobs = jobs, results = results)
        val vm = QueueViewModel(repo)
        vm.uiState.first { it is QueueUiState.Success }
        vm.onAction(QueueAction.ConfirmClearCompleted)
        assertEquals(1, repo.clearCompletedCalls)
        val state = vm.uiState.first { it is QueueUiState.Success } as QueueUiState.Success
        assertEquals(listOf("2"), state.jobs.map { it.id })
    }

    @Test
    fun `delete delegates to repository and drops the job`() = runTest {
        val jobs = listOf(
            QueueJob("1", "one", "txt", "m", "{}"),
            QueueJob("2", "two", "txt", "m", "{}"),
        )
        val repo = FakeQueueRepository(jobs = jobs)
        val vm = QueueViewModel(repo)
        vm.uiState.first { it is QueueUiState.Success }
        vm.onAction(QueueAction.DeleteJob("1"))
        assertEquals(listOf("1"), repo.removes)
        val state = vm.uiState.first { it is QueueUiState.Success } as QueueUiState.Success
        assertEquals(listOf("2"), state.jobs.map { it.id })
    }

    @Test
    fun `delete of executing job is refused`() = runTest {
        val jobs = listOf(
            QueueJob("1", "one", "txt", "m", "{}"),
            QueueJob("2", "two", "txt", "m", "{}"),
        )
        val repo = FakeQueueRepository(
            snapshot = QueueSnapshot(true, 0, 2, "queue", null),
            jobs = jobs,
        )
        val vm = QueueViewModel(repo)
        vm.uiState.first { it is QueueUiState.Success }
        vm.onAction(QueueAction.DeleteJob("1"))
        assertTrue(repo.removes.isEmpty())
        val state = vm.uiState.first { it is QueueUiState.Success } as QueueUiState.Success
        assertEquals(listOf("1", "2"), state.jobs.map { it.id })
        assertEquals("1", state.executingJobId)
    }

    @Test
    fun `move delegates to repository and reorders pending`() = runTest {
        val jobs = listOf(
            QueueJob("1", "one", "txt", "m", "{}"),
            QueueJob("2", "two", "txt", "m", "{}"),
            QueueJob("3", "three", "txt", "m", "{}"),
        )
        val repo = FakeQueueRepository(jobs = jobs)
        val vm = QueueViewModel(repo)
        vm.uiState.first { it is QueueUiState.Success }
        vm.onAction(QueueAction.MoveJob("3", 0))
        assertEquals(listOf("3" to 0), repo.moves)
        val state = vm.uiState.first { it is QueueUiState.Success } as QueueUiState.Success
        assertEquals(listOf("3", "1", "2"), state.pendingJobs.map { it.id })
    }

    @Test
    fun `executingJobId is null when idle or pointer out of range`() = runTest {
        val jobs = listOf(QueueJob("1", "one", "txt", "m", "{}"))
        var vm = QueueViewModel(
            FakeQueueRepository(
                snapshot = QueueSnapshot(false, 0, 1, "queue", null),
                jobs = jobs,
            ),
        )
        var state = vm.uiState.first { it is QueueUiState.Success } as QueueUiState.Success
        assertNull(state.executingJobId)

        vm = QueueViewModel(
            FakeQueueRepository(
                snapshot = QueueSnapshot(true, 5, 1, "queue", null),
                jobs = jobs,
            ),
        )
        state = vm.uiState.first { it is QueueUiState.Success } as QueueUiState.Success
        assertNull(state.executingJobId)
    }

    @Test
    fun `running snapshot passes jobProgress through for dual bars`() = runTest {
        val vm = QueueViewModel(
            FakeQueueRepository(
                snapshot = QueueSnapshot(true, 1, 4, "queue", null, jobProgress = 0.5f),
            ),
        )
        val state = vm.uiState.first { it is QueueUiState.Success } as QueueUiState.Success
        val snapshot = state.snapshot
        // Bars are pure UI derived from the snapshot: ViewModel must pass values as-is.
        assertEquals(0.5f, snapshot?.jobProgress)
        assertEquals(1, snapshot?.currentIndex)
        assertEquals(4, snapshot?.total)
        // Screen computes Queue M% as overallProgress(currentIndex, total, jobProgress).
        val expectedQueue = overallProgress(1, 4, 0.5f)
        assertEquals(
            expectedQueue,
            overallProgress(
                snapshot?.currentIndex ?: -1,
                snapshot?.total ?: -1,
                snapshot?.jobProgress ?: -1f,
            ),
        )
    }

    @Test
    fun `grown total after append lowers overall progress`() = runTest {
        val repo = FakeQueueRepository(
            snapshot = QueueSnapshot(true, 1, 4, "queue", null, jobProgress = 0.5f),
        )
        val vm = QueueViewModel(repo)
        var state = vm.uiState.first {
            it is QueueUiState.Success && it.snapshot?.total == 4
        } as QueueUiState.Success
        val before = overallProgress(
            state.snapshot!!.currentIndex,
            state.snapshot!!.total,
            state.snapshot!!.jobProgress,
        )
        // Appending jobs bumps total while currentIndex/jobProgress stay: no extra
        // ViewModel code needed, recomputation derives from the fresh snapshot.
        repo.emitSnapshot(QueueSnapshot(true, 1, 6, "queue", null, jobProgress = 0.5f))
        state = vm.uiState.first {
            it is QueueUiState.Success && it.snapshot?.total == 6
        } as QueueUiState.Success
        val after = overallProgress(
            state.snapshot!!.currentIndex,
            state.snapshot!!.total,
            state.snapshot!!.jobProgress,
        )
        assertEquals(1, state.snapshot?.currentIndex)
        assertTrue(after < before)
    }
}
