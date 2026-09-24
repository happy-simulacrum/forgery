package com.forgery.app.feature.modules.api

import androidx.navigation.NavController
import kotlinx.serialization.Serializable

@Serializable
data class ModulesRoute(val modelTitle: String = "")

const val MODULES_ROUTE = "modules"

fun NavController.navigateToModules(modelTitle: String = "") {
    navigate(ModulesRoute(modelTitle))
}
