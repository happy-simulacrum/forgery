package com.forgery.app.feature.comfy.impl

import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import com.forgery.app.feature.comfy.api.ComfyRoute

fun NavGraphBuilder.comfyScreen(
    onBackClick: () -> Unit,
) {
    composable<ComfyRoute> {
        ComfyRoute(
            onBackClick = onBackClick,
        )
    }
}
