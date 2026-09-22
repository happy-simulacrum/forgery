package com.forgery.app.feature.settings.api

import androidx.navigation.NavController
import kotlinx.serialization.Serializable

@Serializable
data class SettingsRoute(val id: String? = null)

const val SETTINGS_ROUTE = "settings"

fun NavController.navigateToSettings(id: String? = null) {
    navigate(SettingsRoute(id))
}
