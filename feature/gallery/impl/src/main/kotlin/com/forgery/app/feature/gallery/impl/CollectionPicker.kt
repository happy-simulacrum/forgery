package com.forgery.app.feature.gallery.impl

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.forgery.app.core.designsystem.ForgeryTheme
import com.forgery.app.core.model.GalleryCollection
import com.forgery.app.core.model.HistoryItem
import com.forgery.app.core.ui.DraftTextField
import java.io.File

/**
 * Collection picker for the "add to collection" flow: grid of cover tiles
 * like the COLLECTIONS gallery view, with a name filter on top. Scales to
 * large collection counts (lazy grid + Coil), unlike a scrolling button list.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun CollectionPickerDialog(
    pendingCount: Int,
    collections: List<GalleryCollection>,
    onPick: (Long) -> Unit,
    onNewCollection: () -> Unit,
    onDismiss: () -> Unit,
) {
    var query by remember { mutableStateOf(TextFieldValue("")) }
    val visible = remember(collections, query) {
        val q = query.text.trim()
        if (q.isEmpty()) collections
        else collections.filter { it.name.contains(q, ignoreCase = true) }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add $pendingCount to collection") },
        text = {
            Column {
                DraftTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = "Search",
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                )
                if (visible.isEmpty() && collections.isNotEmpty()) {
                    Text(
                        "No collections match.",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(vertical = 8.dp),
                    )
                }
                LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    modifier = Modifier.heightIn(max = 420.dp).fillMaxWidth(),
                ) {
                    item(key = "new") {
                        Column(
                            Modifier
                                .padding(2.dp)
                                .combinedClickable(onClick = onNewCollection),
                        ) {
                            Box(
                                Modifier.aspectRatio(1f).fillMaxWidth(),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(Icons.Filled.Add, contentDescription = null)
                            }
                            Text(
                                "New",
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(horizontal = 2.dp),
                            )
                        }
                    }
                    items(visible, key = { it.id }) { collection ->
                        CollectionCoverTile(
                            name = collection.name,
                            count = collection.count,
                            cover = collection.cover,
                            onClick = { onPick(collection.id) },
                            onLongClick = {},
                        )
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

/** Cover tile shared with the COLLECTIONS gallery grid. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun CollectionCoverTile(
    name: String,
    count: Int,
    cover: HistoryItem?,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .padding(2.dp)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
    ) {
        Box(Modifier.aspectRatio(1f).fillMaxWidth()) {
            val model = cover?.let { File(it.thumbPath ?: it.imagePath) }
            if (model != null) {
                AsyncImage(
                    model = model,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Icon(
                    Icons.Filled.Folder,
                    contentDescription = null,
                    modifier = Modifier.align(Alignment.Center),
                )
            }
        }
        Text(
            "$name ($count)",
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(horizontal = 2.dp),
        )
    }
}

@Preview
@Composable
private fun CollectionPickerPreview() {
    ForgeryTheme {
        CollectionPickerDialog(
            pendingCount = 3,
            collections = listOf(
                GalleryCollection(1, "Trip", 0, 12, null),
                GalleryCollection(2, "Portraits", 0, 5, null),
                GalleryCollection(3, "Wallpapers", 0, 120, null),
            ),
            onPick = {},
            onNewCollection = {},
            onDismiss = {},
        )
    }
}
