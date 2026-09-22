package com.forgery.app.feature.magicprompt.impl

import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import com.forgery.app.feature.magicprompt.api.MagicpromptRoute

fun NavGraphBuilder.magicpromptScreen(
    onBackClick: () -> Unit,
) {
    composable<MagicpromptRoute> {
        MagicpromptRoute(
            onBackClick = onBackClick,
        )
    }
}
