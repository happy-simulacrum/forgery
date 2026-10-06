package com.forgery.app.feature.gallery.impl

import androidx.compose.ui.text.input.TextFieldValue
import com.forgery.app.core.data.CollectionRepository
import com.forgery.app.core.data.HistoryRepository
import com.forgery.app.core.model.GalleryCollection
import com.forgery.app.core.model.HistoryItem
import com.forgery.app.core.testing.TestDispatcherRule
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
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

    private fun viewModel(
        history: FakeHistoryRepository = FakeHistoryRepository(),
        collections: FakeCollectionRepository = FakeCollectionRepository(),
    ) = GalleryViewModel(history, collections)

    @Test
    fun `first page shows 50 of 120`() = runTest {
        val vm = viewModel()
        val state = vm.uiState.firstSuccess()
        assertEquals(50, state.items.size)
        assertEquals(120, state.total)
        assertEquals(3, state.totalPages)
        assertEquals(0, state.page)
    }

    @Test
    fun `paging clamps at bounds`() = runTest {
        val vm = viewModel()
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
        val vm = viewModel()
        val id = vm.uiState.firstSuccess().items.first().id

        vm.onAction(GalleryAction.ItemLongClicked(id))

        val state = vm.uiState.firstSuccess()
        assertTrue(state.selecting)
        assertEquals(setOf(id), state.selection)
    }

    @Test
    fun `tap in selecting mode toggles, tap outside mode is ignored`() = runTest {
        val vm = viewModel()
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
        val vm = viewModel()
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
        val vm = viewModel(history = repo)
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
        val vm = viewModel(history = repo)
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
        val vm = viewModel(history = repo)
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
        val vm = viewModel(history = repo)
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
        val vm = viewModel()
        vm.uiState.firstSuccess()

        vm.onAction(GalleryAction.EnterSelect)
        vm.onAction(GalleryAction.RequestDeleteSelected)

        assertNull(vm.uiState.firstSuccess().confirm)
    }

    @Test
    fun `H-6 external tail removal clamps page to last non-empty`() = runTest {
        val repo = FakeHistoryRepository()
        val vm = viewModel(history = repo)
        vm.uiState.firstSuccess()

        vm.onAction(GalleryAction.NextPage)
        vm.onAction(GalleryAction.NextPage)
        assertEquals(2, vm.uiState.firstSuccess().page)

        // External tail deletion (e.g. from another screen): 120 -> 50,
        // max page drops 2 -> 0. Without the source clamp the VM would sit
        // on an empty page 2 forever (Next/Prev blocked).
        repo.delete((51L..120L).toList())

        val state = vm.uiState.first { it is GalleryUiState.Success && it.page == 0 } as GalleryUiState.Success
        assertEquals(0, state.page)
        assertEquals(50, state.total)
        assertTrue(state.items.isNotEmpty())
    }

    @Test
    fun `defaults to solo mode with collections listed`() = runTest {
        val vm = viewModel()
        val state = vm.uiState.firstSuccess()
        assertEquals(GalleryViewMode.SOLO, state.viewMode)
        assertEquals(1, state.collections.size)
        assertEquals("Trip", state.collections.first().name)
        assertNull(state.collectionDialog)
    }

    @Test
    fun `switching to collections exits select mode`() = runTest {
        val vm = viewModel()
        val id = vm.uiState.firstSuccess().items.first().id
        vm.onAction(GalleryAction.ItemLongClicked(id))
        assertTrue(vm.uiState.firstSuccess().selecting)

        vm.onAction(GalleryAction.SetViewMode(GalleryViewMode.COLLECTIONS))
        val state = vm.uiState.firstSuccess()
        assertEquals(GalleryViewMode.COLLECTIONS, state.viewMode)
        assertFalse(state.selecting)
        assertTrue(state.selection.isEmpty())

        vm.onAction(GalleryAction.SetViewMode(GalleryViewMode.SOLO))
        assertEquals(GalleryViewMode.SOLO, vm.uiState.firstSuccess().viewMode)
    }

    @Test
    fun `create collection from selection resets selecting`() = runTest {
        val collections = FakeCollectionRepository()
        val vm = viewModel(collections = collections)
        val id = vm.uiState.firstSuccess().items.first().id

        vm.onAction(GalleryAction.ItemLongClicked(id))
        vm.onAction(GalleryAction.RequestCreateCollection(listOf(id)))
        assertTrue(vm.uiState.firstSuccess().collectionDialog is GalleryCollectionDialog.Create)

        vm.onAction(GalleryAction.CollectionNameChanged(TextFieldValue("  Trip  ")))
        vm.onAction(GalleryAction.ConfirmCreateCollection)

        assertEquals(listOf("Trip" to listOf(id)), collections.created)
        val state = vm.uiState.firstSuccess()
        assertNull(state.collectionDialog)
        assertFalse(state.selecting)
        assertTrue(state.selection.isEmpty())
    }

    @Test
    fun `blank collection name does not create`() = runTest {
        val collections = FakeCollectionRepository()
        val vm = viewModel(collections = collections)
        val id = vm.uiState.firstSuccess().items.first().id

        vm.onAction(GalleryAction.RequestCreateCollection(listOf(id)))
        vm.onAction(GalleryAction.CollectionNameChanged(TextFieldValue("   ")))
        vm.onAction(GalleryAction.ConfirmCreateCollection)

        assertTrue(collections.created.isEmpty())
        assertTrue(vm.uiState.firstSuccess().collectionDialog is GalleryCollectionDialog.Create)
    }

    @Test
    fun `add selection to existing collection`() = runTest {
        val collections = FakeCollectionRepository()
        val vm = viewModel(collections = collections)
        val id = vm.uiState.firstSuccess().items.first().id

        vm.onAction(GalleryAction.ItemLongClicked(id))
        vm.onAction(GalleryAction.RequestAddToCollection(listOf(id)))
        assertTrue(vm.uiState.firstSuccess().collectionDialog is GalleryCollectionDialog.AddTo)

        vm.onAction(GalleryAction.ConfirmAddToCollection(1L))

        assertEquals(listOf(1L to listOf(id)), collections.added)
        val state = vm.uiState.firstSuccess()
        assertNull(state.collectionDialog)
        assertFalse(state.selecting)
    }

    @Test
    fun `delete collection removes grouping only`() = runTest {
        val collections = FakeCollectionRepository()
        val history = FakeHistoryRepository()
        val vm = viewModel(history = history, collections = collections)
        vm.uiState.firstSuccess()

        vm.onAction(GalleryAction.RequestDeleteCollection(1L, "Trip"))
        assertTrue(vm.uiState.firstSuccess().collectionDialog is GalleryCollectionDialog.DeleteCollection)

        vm.onAction(GalleryAction.ConfirmDeleteCollection)

        assertEquals(listOf(1L), collections.deletedCollections)
        assertNull(vm.uiState.firstSuccess().collectionDialog)
        // Images are untouched: solo total unchanged.
        assertEquals(120, vm.uiState.firstSuccess().total)
    }

    private suspend fun StateFlow<GalleryUiState>.firstSuccess(): GalleryUiState.Success =
        first { it is GalleryUiState.Success } as GalleryUiState.Success
}

private class FakeCollectionRepository(
    initial: Map<Long, List<HistoryItem>> = mapOf(
        1L to listOf(
            HistoryItem(1, "/img1.png", null, "{\"seed\":1}", "today"),
            HistoryItem(2, "/img2.png", null, "{\"seed\":2}", "today"),
        ),
    ),
    private val names: MutableMap<Long, String> = mutableMapOf(1L to "Trip"),
) : CollectionRepository {
    private val data = MutableStateFlow(initial)
    val created = mutableListOf<Pair<String, List<Long>>>()
    val added = mutableListOf<Pair<Long, List<Long>>>()
    val deletedCollections = mutableListOf<Long>()
    val removed = mutableListOf<Pair<Long, List<Long>>>()
    val reordered = mutableListOf<Pair<Long, List<Long>>>()
    val renamed = mutableListOf<Pair<Long, String>>()
    private var nextId = 100L

    override fun observeCollections(): Flow<List<GalleryCollection>> =
        data.map { m ->
            m.map { (id, items) ->
                GalleryCollection(id, names[id] ?: "C$id", 0, items.size, items.firstOrNull())
            }
        }

    override fun observeCollection(collectionId: Long): Flow<GalleryCollection?> =
        data.map { m ->
            m[collectionId]?.let {
                GalleryCollection(collectionId, names[collectionId] ?: "C$collectionId", 0, it.size, it.firstOrNull())
            }
        }

    override fun observeItems(collectionId: Long, limit: Int, offset: Int): Flow<List<HistoryItem>> =
        data.map { (it[collectionId] ?: emptyList()).drop(offset).take(limit) }

    override fun observeIds(collectionId: Long): Flow<List<Long>> =
        data.map { (it[collectionId] ?: emptyList()).map { item -> item.id } }

    override fun countInCollection(collectionId: Long): Flow<Int> =
        data.map { (it[collectionId] ?: emptyList()).size }

    override fun observeUnsorted(limit: Int, offset: Int): Flow<List<HistoryItem>> =
        flowOf(emptyList())

    override fun observeUnsortedIds(): Flow<List<Long>> = flowOf(emptyList())

    override fun countUnsorted(): Flow<Int> = flowOf(0)

    override fun observeCollectionsForImage(historyId: Long): Flow<List<GalleryCollection>> =
        data.map { m ->
            m.filter { (_, v) -> v.any { it.id == historyId } }
                .map { (id, _) -> GalleryCollection(id, names[id] ?: "C$id") }
        }

    override suspend fun createCollection(name: String, initialIds: List<Long>): Long {
        val trimmed = name.trim()
        require(trimmed.isNotEmpty())
        created += trimmed to initialIds
        val id = nextId++
        names[id] = trimmed
        data.value = data.value + (id to initialIds.map { itemFor(it) })
        return id
    }

    override suspend fun rename(collectionId: Long, name: String) {
        renamed += collectionId to name
        names[collectionId] = name
    }

    override suspend fun deleteCollection(collectionId: Long) {
        deletedCollections += collectionId
        data.value = data.value - collectionId
    }

    override suspend fun addToCollection(collectionId: Long, historyIds: List<Long>) {
        added += collectionId to historyIds
        val merged = ((data.value[collectionId] ?: emptyList()) + historyIds.map { itemFor(it) })
            .distinctBy { it.id }
        data.value = data.value + (collectionId to merged)
    }

    override suspend fun removeFromCollection(collectionId: Long, historyIds: List<Long>) {
        removed += collectionId to historyIds
        data.value = data.value + (collectionId to (data.value[collectionId] ?: emptyList()).filterNot { it.id in historyIds })
    }

    override suspend fun reorder(collectionId: Long, orderedIds: List<Long>) {
        reordered += collectionId to orderedIds
        val byId = (data.value[collectionId] ?: emptyList()).associateBy { it.id }
        data.value = data.value + (collectionId to orderedIds.mapNotNull { byId[it] })
    }

    private fun itemFor(id: Long) = HistoryItem(id, "/img$id.png", null, "{\"seed\":$id}", "today")
}
