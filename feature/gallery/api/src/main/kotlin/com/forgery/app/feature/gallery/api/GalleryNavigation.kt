package com.forgery.app.feature.gallery.api

import androidx.navigation.NavController
import kotlinx.serialization.Serializable

@Serializable
data class GalleryRoute(val id: String? = null)

@Serializable
data class GalleryDetailRoute(val id: Long)

const val GALLERY_ROUTE = "gallery"

fun NavController.navigateToGallery(id: String? = null) {
    navigate(GalleryRoute(id))
}
