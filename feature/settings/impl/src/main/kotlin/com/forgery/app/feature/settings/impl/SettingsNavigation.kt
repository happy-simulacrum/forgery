package com.forgery.app.feature.settings.impl

import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import com.forgery.app.feature.settings.api.SettingsRoute

fun NavGraphBuilder.settingsScreen(
    onBackClick: () -> Unit,
) {
    composable<SettingsRoute> {
        SettingsRoute(
            onBackClick = onBackClick,
        )
    }
}
