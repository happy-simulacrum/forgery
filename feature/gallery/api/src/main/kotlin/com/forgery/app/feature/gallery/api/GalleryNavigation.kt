package com.forgery.app.feature.gallery.api

import androidx.navigation.NavController
import kotlinx.serialization.Serializable

@Serializable
data class GalleryRoute(val id: String? = null)

@Serializable
data class GalleryDetailRoute(val id: Long, val collectionId: Long? = null)

@Serializable
data class GalleryCollectionRoute(val collectionId: Long)

const val GALLERY_ROUTE = "gallery"

/** Virtual collection id for images that belong to no collection. */
const val UNSORTED_COLLECTION_ID = -1L

fun NavController.navigateToGallery(id: String? = null) {
    navigate(GalleryRoute(id))
}

fun NavController.navigateToCollection(collectionId: Long) {
    navigate(GalleryCollectionRoute(collectionId))
}
