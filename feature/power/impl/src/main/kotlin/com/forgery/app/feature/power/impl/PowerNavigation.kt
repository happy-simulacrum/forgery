package com.forgery.app.feature.power.impl

import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import com.forgery.app.feature.power.api.PowerRoute

fun NavGraphBuilder.powerScreen(
    onBackClick: () -> Unit,
) {
    composable<PowerRoute> {
        PowerRoute(
            onBackClick = onBackClick,
        )
    }
}
