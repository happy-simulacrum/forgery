package com.forgery.app.app

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
import org.junit.Rule
import org.junit.Test

private class FakeQueueRepository(
    jobs: List<QueueJob> = emptyList(),
    results: List<QueueResult> = emptyList(),
) : QueueRepository {
    private val jobsFlow = MutableStateFlow(jobs)
    private val resultsFlow = MutableStateFlow(results)

    override fun observeSnapshot(): Flow<QueueSnapshot?> = MutableStateFlow(null).asStateFlow()
    override fun observeJobs(): Flow<List<QueueJob>> = jobsFlow.asStateFlow()
    override fun observeResults(): Flow<List<QueueResult>> = resultsFlow.asStateFlow()
    override suspend fun stage(jobs: List<QueueJob>, origin: String) = Unit
    override suspend fun start() = Unit
    override suspend fun enqueueImmediate(jobs: List<QueueJob>, origin: String) = Unit
    override suspend fun cancel() = Unit
    override suspend fun removeJob(jobId: String): Boolean = false
    override suspend fun moveJob(jobId: String, toPendingIndex: Int): Boolean = false
    override suspend fun clearCompleted() = Unit
    override suspend fun clearPending() = Unit

    fun emitResults(results: List<QueueResult>) {
        resultsFlow.value = results
    }
}

class QueueBadgeViewModelTest {

    @get:Rule
    val dispatcherRule = TestDispatcherRule()

    @Test
    fun `empty queue emits zero`() = runTest {
        val vm = QueueBadgeViewModel(FakeQueueRepository())
        assertEquals(0, vm.pendingCount.first())
    }

    @Test
    fun `jobs without results are all pending`() = runTest {
        val jobs = listOf(
            QueueJob("1", "one", "txt", "m", "{}"),
            QueueJob("2", "two", "txt", "m", "{}"),
        )
        val vm = QueueBadgeViewModel(FakeQueueRepository(jobs = jobs))
        assertEquals(2, vm.pendingCount.first())
    }

    @Test
    fun `jobs with results are excluded from pending count`() = runTest {
        val jobs = listOf(
            QueueJob("1", "one", "txt", "m", "{}"),
            QueueJob("2", "two", "txt", "m", "{}"),
            QueueJob("3", "three", "txt", "m", "{}"),
        )
        val results = listOf(QueueResult("1", "one", listOf("/a.png"), null))
        val vm = QueueBadgeViewModel(FakeQueueRepository(jobs = jobs, results = results))
        assertEquals(2, vm.pendingCount.first())
    }

    @Test
    fun `count follows result updates`() = runTest {
        val jobs = listOf(
            QueueJob("1", "one", "txt", "m", "{}"),
            QueueJob("2", "two", "txt", "m", "{}"),
        )
        val repo = FakeQueueRepository(jobs = jobs)
        val vm = QueueBadgeViewModel(repo)
        assertEquals(2, vm.pendingCount.first())
        repo.emitResults(listOf(QueueResult("1", "one", listOf("/a.png"), null)))
        assertEquals(1, vm.pendingCount.first())
        repo.emitResults(
            listOf(
                QueueResult("1", "one", listOf("/a.png"), null),
                QueueResult("2", "two", listOf("/b.png"), null),
            ),
        )
        assertEquals(0, vm.pendingCount.first())
    }

    @Test
    fun `badge text shows full count up to 99`() {
        assertEquals("0", formatBadgeCount(0))
        assertEquals("5", formatBadgeCount(5))
        assertEquals("99", formatBadgeCount(99))
    }

    @Test
    fun `badge text truncates above 99`() {
        assertEquals("99+", formatBadgeCount(100))
        assertEquals("99+", formatBadgeCount(1250))
    }
}
