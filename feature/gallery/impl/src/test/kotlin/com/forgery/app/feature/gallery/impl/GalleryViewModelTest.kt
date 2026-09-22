package com.forgery.app.feature.gallery.impl

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

private class FakeHistoryRepository : HistoryRepository {
    private val items = MutableStateFlow(
        (1L..120L).map {
            HistoryItem(it, "/img$it.png", null, "{\"seed\":$it}", "today")
        },
    )
    val deleted = mutableListOf<List<Long>>()
    var clears = 0

    override fun observePage(limit: Int, offset: Int): Flow<List<HistoryItem>> =
        items.map { it.drop(offset).take(limit) }

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

class GalleryViewModelTest {

    @get:Rule
    val dispatcherRule = TestDispatcherRule()

    @Test
    fun `first page shows 50 of 120`() = runTest {
        val vm = GalleryViewModel(FakeHistoryRepository())
        val state = vm.uiState.firstSuccess()
        assertEquals(50, state.items.size)
        assertEquals(120, state.total)
        assertEquals(3, state.totalPages)
        assertEquals(0, state.page)
    }

    @Test
    fun `paging clamps at bounds`() = runTest {
        val vm = GalleryViewModel(FakeHistoryRepository())
        vm.uiState.firstSuccess()
        vm.onAction(GalleryAction.PrevPage)
        assertEquals(0, vm.uiState.firstSuccess().page)
        repeat(5) { vm.onAction(GalleryAction.NextPage) }
        val state = vm.uiState.firstSuccess()
        assertEquals(2, state.page)
        assertEquals(20, state.items.size)
    }

    @Test
    fun `long-press enters selecting mode and toggles selection`() = runTest {
        val vm = GalleryViewModel(FakeHistoryRepository())
        val id = vm.uiState.firstSuccess().items.first().id

        vm.onAction(GalleryAction.ItemLongClicked(id))

        val state = vm.uiState.firstSuccess()
        assertTrue(state.selecting)
        assertEquals(setOf(id), state.selection)
    }

    @Test
    fun `tap in selecting mode toggles, tap outside mode is ignored`() = runTest {
        val vm = GalleryViewModel(FakeHistoryRepository())
        val first = vm.uiState.firstSuccess()
        val id = first.items.first().id
        val other = first.items[1].id

        // Outside select mode ItemClicked is ignored by the ViewModel
        // (the Screen navigates to detail instead).
        vm.onAction(GalleryAction.ItemClicked(id))
        assertFalse(vm.uiState.firstSuccess().selecting)
        assertTrue(vm.uiState.firstSuccess().selection.isEmpty())

        // Enter select mode, then tap toggles.
        vm.onAction(GalleryAction.EnterSelect)
        vm.onAction(GalleryAction.ItemClicked(other))
        assertEquals(setOf(other), vm.uiState.firstSuccess().selection)
        vm.onAction(GalleryAction.ItemClicked(other))
        assertTrue(vm.uiState.firstSuccess().selection.isEmpty())
        // Still in select mode after toggling off.
        assertTrue(vm.uiState.firstSuccess().selecting)
    }

    @Test
    fun `enter and exit select resets selection`() = runTest {
        val vm = GalleryViewModel(FakeHistoryRepository())
        val id = vm.uiState.firstSuccess().items.first().id

        vm.onAction(GalleryAction.EnterSelect)
        assertTrue(vm.uiState.firstSuccess().selecting)

        vm.onAction(GalleryAction.ItemClicked(id))
        assertEquals(setOf(id), vm.uiState.firstSuccess().selection)

        vm.onAction(GalleryAction.ExitSelect)
        val state = vm.uiState.firstSuccess()
        assertFalse(state.selecting)
        assertTrue(state.selection.isEmpty())
    }

    @Test
    fun `request all then dismiss keeps data`() = runTest {
        val repo = FakeHistoryRepository()
        val vm = GalleryViewModel(repo)
        vm.uiState.firstSuccess()

        vm.onAction(GalleryAction.RequestDeleteAll)
        assertEquals(GalleryConfirm.All, vm.uiState.firstSuccess().confirm)

        vm.onAction(GalleryAction.DismissDelete)
        val state = vm.uiState.firstSuccess()
        assertNull(state.confirm)
        assertEquals(0, repo.clears)
        assertEquals(120, state.total)
    }

    @Test
    fun `request all then confirm clears and resets`() = runTest {
        val repo = FakeHistoryRepository()
        val vm = GalleryViewModel(repo)
        vm.uiState.firstSuccess()

        vm.onAction(GalleryAction.EnterSelect)
        vm.onAction(GalleryAction.RequestDeleteAll)
        vm.onAction(GalleryAction.ConfirmDelete)

        val state = vm.uiState.firstSuccess()
        assertEquals(1, repo.clears)
        assertNull(state.confirm)
        assertFalse(state.selecting)
        assertTrue(state.selection.isEmpty())
        assertEquals(0, state.page)
        assertEquals(0, state.total)
    }

    @Test
    fun `request selected then confirm deletes and resets selecting`() = runTest {
        val repo = FakeHistoryRepository()
        val vm = GalleryViewModel(repo)
        val first = vm.uiState.firstSuccess()
        val id = first.items.first().id

        vm.onAction(GalleryAction.ItemLongClicked(id))
        vm.onAction(GalleryAction.RequestDeleteSelected)
        assertEquals(GalleryConfirm.Selected(listOf(id)), vm.uiState.firstSuccess().confirm)

        vm.onAction(GalleryAction.ConfirmDelete)

        assertEquals(listOf(listOf(id)), repo.deleted)
        val state = vm.uiState.firstSuccess()
        assertNull(state.confirm)
        assertTrue(state.selection.isEmpty())
        assertFalse(state.selecting)
    }

    @Test
    fun `request single then confirm deletes one`() = runTest {
        val repo = FakeHistoryRepository()
        val vm = GalleryViewModel(repo)
        val id = vm.uiState.firstSuccess().items.first().id

        vm.onAction(GalleryAction.RequestDeleteSingle(id))
        assertEquals(GalleryConfirm.Single(id), vm.uiState.firstSuccess().confirm)

        vm.onAction(GalleryAction.ConfirmDelete)

        assertEquals(listOf(listOf(id)), repo.deleted)
        val state = vm.uiState.firstSuccess()
        assertNull(state.confirm)
        assertFalse(state.selecting)
        assertTrue(state.selection.isEmpty())
    }

    @Test
    fun `request selected with empty selection does nothing`() = runTest {
        val vm = GalleryViewModel(FakeHistoryRepository())
        vm.uiState.firstSuccess()

        vm.onAction(GalleryAction.EnterSelect)
        vm.onAction(GalleryAction.RequestDeleteSelected)

        assertNull(vm.uiState.firstSuccess().confirm)
    }

    private suspend fun StateFlow<GalleryUiState>.firstSuccess(): GalleryUiState.Success =
        first { it is GalleryUiState.Success } as GalleryUiState.Success
}
