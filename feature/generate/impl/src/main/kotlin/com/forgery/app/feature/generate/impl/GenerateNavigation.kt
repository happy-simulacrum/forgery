package com.forgery.app.feature.generate.impl

import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import com.forgery.app.feature.generate.api.GenerateRoute

fun NavGraphBuilder.generateScreen(
    onBackClick: () -> Unit,
    onNavigateToQueue: () -> Unit,
    onNavigateToLora: () -> Unit,
    onNavigateToStyles: () -> Unit,
    onNavigateToModules: (String) -> Unit,
) {
    composable<GenerateRoute> {
        GenerateRoute(
            onNavigateToQueue = onNavigateToQueue,
            onNavigateToLora = onNavigateToLora,
            onNavigateToStyles = onNavigateToStyles,
            onNavigateToModules = onNavigateToModules,
        )
    }
}
