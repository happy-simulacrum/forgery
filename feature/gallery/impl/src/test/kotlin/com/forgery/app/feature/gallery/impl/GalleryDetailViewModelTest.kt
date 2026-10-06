package com.forgery.app.feature.gallery.impl

import androidx.compose.ui.text.input.TextFieldValue
import androidx.lifecycle.SavedStateHandle
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
        collections: FakeDetailCollectionRepository = FakeDetailCollectionRepository(),
        collectionId: Long? = null,
    ) = GalleryDetailViewModel(
        SavedStateHandle(
            buildMap<String, Any> {
                put("id", id)
                if (collectionId != null) put("collectionId", collectionId)
            },
        ),
        repo,
        collections,
    )

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

    @Test
    fun `collection scope uses collection ids instead of global`() = runTest {
        val collections = FakeDetailCollectionRepository(
            mapOf(1L to listOf(7L, 5L)),
        )
        val vm = viewModel(FakeDetailHistoryRepository(), id = 5L, collections = collections, collectionId = 1L)
        val state = vm.uiState.firstSuccess()
        assertEquals(listOf(7L, 5L), state.ids)
        assertEquals(1, state.page)
        assertEquals(5L, state.item?.id)
    }

    @Test
    fun `create collection from detail`() = runTest {
        val collections = FakeDetailCollectionRepository()
        val vm = viewModel(FakeDetailHistoryRepository(), collections = collections)
        vm.uiState.firstSuccess()

        vm.onAction(GalleryDetailAction.RequestCreateCollection)
        assertTrue(vm.uiState.firstSuccess().collectionDialog is GalleryCollectionDialog.Create)

        vm.onAction(GalleryDetailAction.CollectionNameChanged(TextFieldValue("Trip")))
        vm.onAction(GalleryDetailAction.ConfirmCreateCollection)

        assertEquals(listOf("Trip" to listOf(7L)), collections.created)
        assertNull(vm.uiState.firstSuccess().collectionDialog)
    }

    @Test
    fun `add to collection from detail`() = runTest {
        val collections = FakeDetailCollectionRepository(
            mapOf(1L to listOf(9L)),
        )
        val vm = viewModel(FakeDetailHistoryRepository(), collections = collections)
        vm.uiState.firstSuccess()

        vm.onAction(GalleryDetailAction.RequestAddToCollection)
        assertTrue(vm.uiState.firstSuccess().collectionDialog is GalleryCollectionDialog.AddTo)

        vm.onAction(GalleryDetailAction.ConfirmAddToCollection(1L))

        assertEquals(listOf(1L to listOf(7L)), collections.added)
        assertNull(vm.uiState.firstSuccess().collectionDialog)
    }

    @Test
    fun `remove from collection detaches image and lands on neighbor`() = runTest {
        val collections = FakeDetailCollectionRepository(
            mapOf(1L to listOf(7L, 5L)),
        )
        val vm = viewModel(FakeDetailHistoryRepository(), collections = collections, collectionId = 1L)
        vm.uiState.firstSuccess()

        vm.onAction(GalleryDetailAction.RemoveFromCollection)

        assertEquals(listOf(1L to listOf(7L)), collections.removed)
        val state = vm.uiState.first {
            it is GalleryDetailUiState.Success && it.ids == listOf(5L)
        } as GalleryDetailUiState.Success
        assertFalse(state.deleted)
        assertEquals(5L, state.item?.id)
    }

    @Test
    fun `remove last from collection marks deleted`() = runTest {
        val collections = FakeDetailCollectionRepository(
            mapOf(1L to listOf(7L)),
        )
        val vm = viewModel(FakeDetailHistoryRepository(), collections = collections, collectionId = 1L)
        vm.uiState.firstSuccess()

        vm.onAction(GalleryDetailAction.RemoveFromCollection)

        assertEquals(listOf(1L to listOf(7L)), collections.removed)
        val state = vm.uiState.first {
            it is GalleryDetailUiState.Success && it.deleted
        } as GalleryDetailUiState.Success
        assertTrue(state.ids.isEmpty())
        assertNull(state.item)
    }

    @Test
    fun `remove outside collection does nothing`() = runTest {
        val collections = FakeDetailCollectionRepository(
            mapOf(1L to listOf(7L, 5L)),
        )
        val vm = viewModel(FakeDetailHistoryRepository(), collections = collections)
        vm.uiState.firstSuccess()

        vm.onAction(GalleryDetailAction.RemoveFromCollection)

        assertTrue(collections.removed.isEmpty())
        assertEquals(listOf(9L, 7L, 5L), vm.uiState.firstSuccess().ids)
    }

    private suspend fun StateFlow<GalleryDetailUiState>.firstSuccess(): GalleryDetailUiState.Success =
        first { it is GalleryDetailUiState.Success } as GalleryDetailUiState.Success
}

private class FakeDetailCollectionRepository(
    initialIds: Map<Long, List<Long>> = emptyMap(),
) : CollectionRepository {
    private val data = MutableStateFlow(initialIds)
    val created = mutableListOf<Pair<String, List<Long>>>()
    val added = mutableListOf<Pair<Long, List<Long>>>()
    val removed = mutableListOf<Pair<Long, List<Long>>>()

    override fun observeCollections(): Flow<List<GalleryCollection>> =
        data.map { m -> m.map { (id, ids) -> GalleryCollection(id, "C$id", 0, ids.size, null) } }

    override fun observeCollection(collectionId: Long): Flow<GalleryCollection?> =
        data.map { m ->
            m[collectionId]?.let { GalleryCollection(collectionId, "C$collectionId", 0, it.size, null) }
        }

    override fun observeItems(collectionId: Long, limit: Int, offset: Int): Flow<List<HistoryItem>> =
        data.map { m ->
            (m[collectionId] ?: emptyList()).drop(offset).take(limit).map {
                HistoryItem(it, "/img$it.png", null, "{}", "today")
            }
        }

    override fun observeIds(collectionId: Long): Flow<List<Long>> =
        data.map { it[collectionId] ?: emptyList() }

    override fun countInCollection(collectionId: Long): Flow<Int> =
        data.map { (it[collectionId] ?: emptyList()).size }

    override fun observeUnsorted(limit: Int, offset: Int): Flow<List<HistoryItem>> =
        flowOf(emptyList())

    override fun observeUnsortedIds(): Flow<List<Long>> = flowOf(emptyList())

    override fun countUnsorted(): Flow<Int> = flowOf(0)

    override fun observeCollectionsForImage(historyId: Long): Flow<List<GalleryCollection>> =
        flowOf(emptyList())

    override suspend fun createCollection(name: String, initialIds: List<Long>): Long {
        created += name to initialIds
        return 100L
    }

    override suspend fun rename(collectionId: Long, name: String) = Unit

    override suspend fun deleteCollection(collectionId: Long) = Unit

    override suspend fun addToCollection(collectionId: Long, historyIds: List<Long>) {
        added += collectionId to historyIds
    }

    override suspend fun removeFromCollection(collectionId: Long, historyIds: List<Long>) {
        removed += collectionId to historyIds
        data.value = data.value + (
            collectionId to (data.value[collectionId] ?: emptyList()).filterNot { it in historyIds }
            )
    }

    override suspend fun reorder(collectionId: Long, orderedIds: List<Long>) = Unit
}
