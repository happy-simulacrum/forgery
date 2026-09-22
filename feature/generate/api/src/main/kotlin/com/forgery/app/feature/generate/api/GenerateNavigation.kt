package com.forgery.app.feature.generate.api

import androidx.navigation.NavController
import kotlinx.serialization.Serializable

@Serializable
data class GenerateRoute(val id: String? = null)

const val GENERATE_ROUTE = "generate"

fun NavController.navigateToGenerate(id: String? = null) {
    navigate(GenerateRoute(id))
}
