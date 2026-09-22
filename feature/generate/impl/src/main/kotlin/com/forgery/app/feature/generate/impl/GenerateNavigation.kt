package com.forgery.app.feature.generate.impl

import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import com.forgery.app.core.model.GenerationMode
import com.forgery.app.feature.generate.api.GenerateRoute

fun NavGraphBuilder.generateScreen(
    onBackClick: () -> Unit,
    onNavigateToQueue: () -> Unit,
    onNavigateToLora: (GenerationMode) -> Unit,
    onNavigateToStyles: (GenerationMode) -> Unit,
    onNavigateToMagic: (GenerationMode) -> Unit,
    onNavigateToPower: () -> Unit,
) {
    composable<GenerateRoute> {
        GenerateRoute(
            onNavigateToQueue = onNavigateToQueue,
            onNavigateToLora = onNavigateToLora,
            onNavigateToStyles = onNavigateToStyles,
            onNavigateToMagic = onNavigateToMagic,
            onNavigateToPower = onNavigateToPower,
        )
    }
}
