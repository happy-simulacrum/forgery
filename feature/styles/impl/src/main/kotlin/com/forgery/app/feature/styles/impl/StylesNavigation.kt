package com.forgery.app.feature.styles.impl

import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import com.forgery.app.feature.styles.api.StylesRoute

fun NavGraphBuilder.stylesScreen(
    onBackClick: () -> Unit,
) {
    composable<StylesRoute> {
        StylesRoute(
            onBackClick = onBackClick,
        )
    }
}
