package com.forgery.app.feature.gallery.impl

import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import com.forgery.app.feature.gallery.api.GalleryCollectionRoute
import com.forgery.app.feature.gallery.api.GalleryDetailRoute
import com.forgery.app.feature.gallery.api.GalleryRoute

fun NavGraphBuilder.galleryScreen(
    onBackClick: () -> Unit,
    onNavigateToDetail: (Long) -> Unit,
    onNavigateToCollection: (Long) -> Unit,
) {
    composable<GalleryRoute> {
        GalleryRoute(
            onBackClick = onBackClick,
            onNavigateToDetail = onNavigateToDetail,
            onNavigateToCollection = onNavigateToCollection,
        )
    }
}

fun NavGraphBuilder.collectionScreen(
    onBackClick: () -> Unit,
    onNavigateToDetail: (Long, Long) -> Unit,
    onCollectionDeleted: () -> Unit,
) {
    composable<GalleryCollectionRoute> {
        CollectionGalleryRoute(
            onBackClick = onBackClick,
            onNavigateToDetail = onNavigateToDetail,
            onCollectionDeleted = onCollectionDeleted,
        )
    }
}

fun NavGraphBuilder.galleryDetailScreen(
    onBackClick: () -> Unit,
    onNavigateToAnalyze: (String) -> Unit,
    onNavigateToInpaint: (String) -> Unit,
) {
    composable<GalleryDetailRoute> {
        GalleryDetailRoute(
            onBackClick = onBackClick,
            onNavigateToAnalyze = onNavigateToAnalyze,
            onNavigateToInpaint = onNavigateToInpaint,
        )
    }
}

fun NavController.navigateToGalleryDetail(id: Long) {
    navigate(GalleryDetailRoute(id))
}
