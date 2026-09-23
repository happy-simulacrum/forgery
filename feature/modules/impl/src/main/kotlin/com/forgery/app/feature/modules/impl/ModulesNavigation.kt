package com.forgery.app.feature.modules.impl

import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import com.forgery.app.feature.modules.api.ModulesRoute

fun NavGraphBuilder.modulesScreen(
    onBackClick: () -> Unit,
) {
    composable<ModulesRoute> {
        ModulesRoute(
            onBackClick = onBackClick,
        )
    }
}
