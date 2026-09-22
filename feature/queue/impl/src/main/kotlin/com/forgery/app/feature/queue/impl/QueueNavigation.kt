package com.forgery.app.feature.queue.impl

import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import com.forgery.app.feature.queue.api.QueueRoute

fun NavGraphBuilder.queueScreen(
    onBackClick: () -> Unit,
) {
    composable<QueueRoute> {
        QueueRoute(
            onBackClick = onBackClick,
        )
    }
}
