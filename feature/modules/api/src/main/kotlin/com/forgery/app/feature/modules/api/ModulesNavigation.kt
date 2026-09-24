package com.forgery.app.feature.modules.api

import androidx.navigation.NavController
import kotlinx.serialization.Serializable

@Serializable
data object ModulesRoute

const val MODULES_ROUTE = "modules"

fun NavController.navigateToModules() {
    navigate(ModulesRoute)
}
