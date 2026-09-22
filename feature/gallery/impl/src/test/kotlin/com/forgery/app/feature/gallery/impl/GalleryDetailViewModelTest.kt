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
    initial: List<HistoryItem> = listOf(
        HistoryItem(7, "/img7.png", null, "{\"seed\":7}", "today"),
    ),
) : HistoryRepository {
    private val items = MutableStateFlow(initial)
    val deleted = mutableListOf<List<Long>>()
    var clears = 0

    override fun observePage(limit: Int, offset: Int): Flow<List<HistoryItem>> =
        items.map { it.drop(offset).take(limit) }

    override fun observeDetail(id: Long): Flow<HistoryItem?> =
        items.map { list -> list.firstOrNull { it.id == id } }

    override fun count(): Flow<Int> = items.map { it.size }

    override suspend fun add(imagePath: String, thumbPath: String?, paramsJson: String, date: String): Long {
        val id = (items.value.maxOfOrNull { it.id } ?: 0) + 1
        items.value = items.value + HistoryItem(id, imagePath, thumbPath, paramsJson, date)
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
    fun `emits item for route id`() = runTest {
        val vm = viewModel(FakeDetailHistoryRepository())
        val state = vm.uiState.firstSuccess()
        assertEquals(7L, state.item?.id)
        assertFalse(state.confirmDelete)
        assertFalse(state.deleted)
    }

    @Test
    fun `missing id emits null item`() = runTest {
        val vm = viewModel(FakeDetailHistoryRepository(), id = 999L)
        assertNull(vm.uiState.firstSuccess().item)
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
    fun `confirm deletes and marks deleted`() = runTest {
        val repo = FakeDetailHistoryRepository()
        val vm = viewModel(repo)
        vm.uiState.firstSuccess()

        vm.onAction(GalleryDetailAction.RequestDelete)
        vm.onAction(GalleryDetailAction.ConfirmDelete)

        assertEquals(listOf(listOf(7L)), repo.deleted)
        val state = vm.uiState.firstSuccess()
        assertFalse(state.confirmDelete)
        assertTrue(state.deleted)
    }

    private suspend fun StateFlow<GalleryDetailUiState>.firstSuccess(): GalleryDetailUiState.Success =
        first { it is GalleryDetailUiState.Success } as GalleryDetailUiState.Success
}
