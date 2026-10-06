package com.forgery.app.feature.gallery.impl

import androidx.compose.ui.text.input.TextFieldValue
import androidx.lifecycle.SavedStateHandle
import com.forgery.app.core.data.CollectionRepository
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

private class FakeCollectionGalleryRepository(
    initial: List<HistoryItem> = listOf(
        HistoryItem(1, "/img1.png", null, "{\"seed\":1}", "today"),
        HistoryItem(2, "/img2.png", null, "{\"seed\":2}", "today"),
        HistoryItem(3, "/img3.png", null, "{\"seed\":3}", "today"),
    ),
    private var collectionName: String = "Trip",
) : CollectionRepository {
    private val items = MutableStateFlow(initial)
    private val name = MutableStateFlow(collectionName)
    val removed = mutableListOf<Pair<Long, List<Long>>>()
    val reordered = mutableListOf<Pair<Long, List<Long>>>()
    val renamed = mutableListOf<Pair<Long, String>>()
    val deletedCollections = mutableListOf<Long>()
    val collectionId = 1L

    override fun observeCollections(): Flow<List<GalleryCollection>> =
        kotlinx.coroutines.flow.combine(items, name) { list, n ->
            listOf(GalleryCollection(collectionId, n, 0, list.size, list.firstOrNull()))
        }

    override fun observeCollection(collectionId: Long): Flow<GalleryCollection?> =
        kotlinx.coroutines.flow.combine(items, name) { list, n ->
            GalleryCollection(collectionId, n, 0, list.size, list.firstOrNull())
        }

    override fun observeItems(collectionId: Long, limit: Int, offset: Int): Flow<List<HistoryItem>> =
        items.map { it.drop(offset).take(limit) }

    override fun observeIds(collectionId: Long): Flow<List<Long>> =
        items.map { list -> list.map { it.id } }

    override fun countInCollection(collectionId: Long): Flow<Int> =
        items.map { it.size }

    override fun observeUnsorted(limit: Int, offset: Int): Flow<List<HistoryItem>> =
        flowOf(emptyList())

    override fun observeUnsortedIds(): Flow<List<Long>> = flowOf(emptyList())

    override fun countUnsorted(): Flow<Int> = flowOf(0)

    override fun observeCollectionsForImage(historyId: Long): Flow<List<GalleryCollection>> =
        flowOf(emptyList())

    override suspend fun createCollection(name: String, initialIds: List<Long>): Long = 99L

    override suspend fun rename(collectionId: Long, name: String) {
        renamed += collectionId to name
        collectionName = name
        this.name.value = name
    }

    override suspend fun deleteCollection(collectionId: Long) {
        deletedCollections += collectionId
    }

    override suspend fun addToCollection(collectionId: Long, historyIds: List<Long>) = Unit

    override suspend fun removeFromCollection(collectionId: Long, historyIds: List<Long>) {
        removed += collectionId to historyIds
        items.value = items.value.filterNot { it.id in historyIds }
    }

    override suspend fun reorder(collectionId: Long, orderedIds: List<Long>) {
        reordered += collectionId to orderedIds
        val byId = items.value.associateBy { it.id }
        items.value = orderedIds.mapNotNull { byId[it] }
    }
}

class CollectionGalleryViewModelTest {

    @get:Rule
    val dispatcherRule = TestDispatcherRule()

    private fun viewModel(
        repo: FakeCollectionGalleryRepository = FakeCollectionGalleryRepository(),
        collectionId: Long = 1L,
    ) = CollectionGalleryViewModel(
        SavedStateHandle(mapOf("collectionId" to collectionId)),
        repo,
    )

    @Test
    fun `loads header and items in sort order`() = runTest {
        val vm = viewModel()
        val state = vm.uiState.firstSuccess()
        assertEquals("Trip", state.collection?.name)
        assertEquals(3, state.total)
        assertEquals(listOf(1L, 2L, 3L), state.items.map { it.id })
    }

    @Test
    fun `commit reorder persists new order`() = runTest {
        val repo = FakeCollectionGalleryRepository()
        val vm = viewModel(repo)
        vm.uiState.firstSuccess()

        vm.onAction(CollectionGalleryAction.CommitReorder(listOf(3L, 2L, 1L)))

        assertEquals(listOf(1L to listOf(3L, 2L, 1L)), repo.reordered)
        val state = vm.uiState.firstSuccess()
        assertEquals(listOf(3L, 2L, 1L), state.items.map { it.id })
    }

    @Test
    fun `remove selected keeps images globally (grouping only)`() = runTest {
        val repo = FakeCollectionGalleryRepository()
        val vm = viewModel(repo)
        vm.uiState.firstSuccess()

        vm.onAction(CollectionGalleryAction.ItemLongClicked(1L))
        vm.onAction(CollectionGalleryAction.RequestRemoveSelected)

        assertEquals(listOf(1L to listOf(1L)), repo.removed)
        val state = vm.uiState.firstSuccess()
        assertEquals(listOf(2L, 3L), state.items.map { it.id })
        assertFalse(state.selecting)
    }

    @Test
    fun `rename flow updates name`() = runTest {
        val repo = FakeCollectionGalleryRepository()
        val vm = viewModel(repo)
        vm.uiState.firstSuccess()

        vm.onAction(CollectionGalleryAction.RenameOpened)
        assertEquals(TextFieldValue("Trip"), vm.uiState.firstSuccess().renameInput)

        vm.onAction(CollectionGalleryAction.RenameChanged(TextFieldValue("Holiday")))
        vm.onAction(CollectionGalleryAction.ConfirmRename)

        assertEquals(listOf(1L to "Holiday"), repo.renamed)
        val renamed = vm.uiState.first {
            it is CollectionGalleryUiState.Success &&
                it.renameInput == null && it.collection?.name == "Holiday"
        } as CollectionGalleryUiState.Success
        assertNull(renamed.renameInput)
        assertEquals("Holiday", renamed.collection?.name)
    }

    @Test
    fun `delete collection marks deleted for navigation`() = runTest {
        val repo = FakeCollectionGalleryRepository()
        val vm = viewModel(repo)
        vm.uiState.firstSuccess()

        vm.onAction(CollectionGalleryAction.RequestDeleteCollection)
        assertTrue(vm.uiState.firstSuccess().confirmDeleteCollection)

        vm.onAction(CollectionGalleryAction.ConfirmDeleteCollection)

        assertEquals(listOf(1L), repo.deletedCollections)
        assertTrue(vm.uiState.firstSuccess().deleted)
    }

    private suspend fun StateFlow<CollectionGalleryUiState>.firstSuccess(): CollectionGalleryUiState.Success =
        first { it is CollectionGalleryUiState.Success } as CollectionGalleryUiState.Success
}
