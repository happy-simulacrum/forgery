package com.forgery.app.feature.analyze.impl

import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import com.forgery.app.feature.analyze.api.AnalyzeRoute

fun NavGraphBuilder.analyzeScreen(
    onNavigateToGenerate: () -> Unit,
) {
    composable<AnalyzeRoute> {
        AnalyzeRoute(
            onNavigateToGenerate = onNavigateToGenerate,
        )
    }
}
