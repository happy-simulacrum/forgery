package com.forgery.app.feature.modules.api

import androidx.navigation.NavController
import kotlinx.serialization.Serializable

@Serializable
data class ModulesRoute(val mode: String? = null)

const val MODULES_ROUTE = "modules"

fun NavController.navigateToModules(mode: String? = null) {
    navigate(ModulesRoute(mode))
}
