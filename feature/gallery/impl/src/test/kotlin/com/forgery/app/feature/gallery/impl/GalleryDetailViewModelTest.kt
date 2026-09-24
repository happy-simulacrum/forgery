package com.forgery.app.feature.gallery.impl

import androidx.lifecycle.SavedStateHandle
import com.forgery.app.core.data.HistoryRepository
import com.forgery.app.core.model.HistoryItem
import com.forgery.app.core.testing.TestDispatcherRule
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

private class FakeDetailHistoryRepository(
    // New -> old order, as the real observeIds provides.
    initial: List<HistoryItem> = listOf(
        HistoryItem(9, "/img9.png", null, "{\"seed\":9}", "today"),
        HistoryItem(7, "/img7.png", null, "{\"seed\":7}", "today"),
        HistoryItem(5, "/img5.png", null, "{\"seed\":5}", "today"),
    ),
) : HistoryRepository {
    private val items = MutableStateFlow(initial)
    val deleted = mutableListOf<List<Long>>()
    var clears = 0

    override fun observePage(limit: Int, offset: Int): Flow<List<HistoryItem>> =
        items.map { it.drop(offset).take(limit) }

    override fun observeDetail(id: Long): Flow<HistoryItem?> =
        items.map { list -> list.firstOrNull { it.id == id } }

    override fun observeIds(): Flow<List<Long>> =
        items.map { list -> list.map { it.id } }

    override fun count(): Flow<Int> = items.map { it.size }

    override suspend fun add(imagePath: String, thumbPath: String?, paramsJson: String, date: String): Long {
        val id = (items.value.maxOfOrNull { it.id } ?: 0) + 1
        items.value = listOf(HistoryItem(id, imagePath, thumbPath, paramsJson, date)) + items.value
        return id
    }

    override suspend fun delete(ids: List<Long>) {
        deleted += ids
        items.value = items.value.filterNot { it.id in ids }
    }

    override suspend fun clear() {
        clears++
        items.value = emptyList()
    }
}

class GalleryDetailViewModelTest {

    @get:Rule
    val dispatcherRule = TestDispatcherRule()

    private fun viewModel(
        repo: FakeDetailHistoryRepository,
        id: Long = 7L,
    ) = GalleryDetailViewModel(SavedStateHandle(mapOf("id" to id)), repo)

    @Test
    fun `emits ids in order with page for route id`() = runTest {
        val vm = viewModel(FakeDetailHistoryRepository())
        val state = vm.uiState.firstSuccess()
        assertEquals(listOf(9L, 7L, 5L), state.ids)
        assertEquals(1, state.page)
        assertEquals(7L, state.item?.id)
        assertFalse(state.confirmDelete)
        assertFalse(state.deleted)
    }

    @Test
    fun `unknown id falls back to first`() = runTest {
        val vm = viewModel(FakeDetailHistoryRepository(), id = 999L)
        val state = vm.uiState.firstSuccess()
        assertEquals(0, state.page)
        assertEquals(9L, state.item?.id)
    }

    @Test
    fun `empty history emits null item`() = runTest {
        val vm = viewModel(FakeDetailHistoryRepository(emptyList()))
        val state = vm.uiState.firstSuccess()
        assertTrue(state.ids.isEmpty())
        assertNull(state.item)
        assertFalse(state.deleted)
    }

    @Test
    fun `page changed updates current item`() = runTest {
        val vm = viewModel(FakeDetailHistoryRepository())
        vm.uiState.firstSuccess()

        vm.onAction(GalleryDetailAction.PageChanged(0))
        assertEquals(9L, vm.uiState.firstSuccess().item?.id)

        vm.onAction(GalleryDetailAction.PageChanged(2))
        val state = vm.uiState.firstSuccess()
        assertEquals(2, state.page)
        assertEquals(5L, state.item?.id)
    }

    @Test
    fun `request then dismiss keeps item`() = runTest {
        val repo = FakeDetailHistoryRepository()
        val vm = viewModel(repo)
        vm.uiState.firstSuccess()

        vm.onAction(GalleryDetailAction.RequestDelete)
        assertTrue(vm.uiState.firstSuccess().confirmDelete)

        vm.onAction(GalleryDetailAction.DismissDelete)
        val state = vm.uiState.firstSuccess()
        assertFalse(state.confirmDelete)
        assertTrue(repo.deleted.isEmpty())
        assertEquals(7L, state.item?.id)
    }

    @Test
    fun `confirm deletes middle and lands on neighbor`() = runTest {
        val repo = FakeDetailHistoryRepository()
        val vm = viewModel(repo)
        vm.uiState.firstSuccess()

        vm.onAction(GalleryDetailAction.RequestDelete)
        vm.onAction(GalleryDetailAction.ConfirmDelete)

        assertEquals(listOf(listOf(7L)), repo.deleted)
        val state = vm.uiState.firstSuccess()
        assertFalse(state.confirmDelete)
        assertFalse(state.deleted)
        assertEquals(listOf(9L, 5L), state.ids)
        assertEquals(1, state.page)
        assertEquals(5L, state.item?.id)
    }

    @Test
    fun `confirm deletes last and steps back`() = runTest {
        val repo = FakeDetailHistoryRepository()
        val vm = viewModel(repo, id = 5L)
        assertEquals(2, vm.uiState.firstSuccess().page)

        vm.onAction(GalleryDetailAction.ConfirmDelete)

        assertEquals(listOf(listOf(5L)), repo.deleted)
        val state = vm.uiState.firstSuccess()
        assertFalse(state.deleted)
        assertEquals(listOf(9L, 7L), state.ids)
        assertEquals(1, state.page)
        assertEquals(7L, state.item?.id)
    }

    @Test
    fun `confirm deletes only and marks deleted`() = runTest {
        val repo = FakeDetailHistoryRepository(
            initial = listOf(
                HistoryItem(7, "/img7.png", null, "{\"seed\":7}", "today"),
            ),
        )
        val vm = viewModel(repo)
        vm.uiState.firstSuccess()

        vm.onAction(GalleryDetailAction.ConfirmDelete)

        assertEquals(listOf(listOf(7L)), repo.deleted)
        val state = vm.uiState.firstSuccess()
        assertFalse(state.confirmDelete)
        assertTrue(state.ids.isEmpty())
        assertNull(state.item)
        assertTrue(state.deleted)
    }

    private suspend fun StateFlow<GalleryDetailUiState>.firstSuccess(): GalleryDetailUiState.Success =
        first { it is GalleryDetailUiState.Success } as GalleryDetailUiState.Success
}
