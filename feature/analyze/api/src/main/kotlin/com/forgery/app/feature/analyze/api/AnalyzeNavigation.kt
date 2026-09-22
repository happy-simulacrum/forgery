package com.forgery.app.feature.analyze.api

import androidx.navigation.NavController
import kotlinx.serialization.Serializable

@Serializable
data class AnalyzeRoute(val imagePath: String? = null)

const val ANALYZE_ROUTE = "analyze"

fun NavController.navigateToAnalyze(imagePath: String? = null) {
    navigate(AnalyzeRoute(imagePath))
}
