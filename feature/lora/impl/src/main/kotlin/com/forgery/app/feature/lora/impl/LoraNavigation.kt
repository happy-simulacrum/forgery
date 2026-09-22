package com.forgery.app.feature.lora.impl

import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import com.forgery.app.feature.lora.api.LoraRoute

fun NavGraphBuilder.loraScreen(
    onBackClick: () -> Unit,
) {
    composable<LoraRoute> {
        LoraRoute(
            onBackClick = onBackClick,
        )
    }
}
