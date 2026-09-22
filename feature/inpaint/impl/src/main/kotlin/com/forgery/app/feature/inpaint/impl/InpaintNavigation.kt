package com.forgery.app.feature.inpaint.impl

import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import com.forgery.app.feature.inpaint.api.InpaintRoute

fun NavGraphBuilder.inpaintScreen(
    onBackClick: () -> Unit,
) {
    composable<InpaintRoute> {
        InpaintRoute(
            onBackClick = onBackClick,
        )
    }
}
