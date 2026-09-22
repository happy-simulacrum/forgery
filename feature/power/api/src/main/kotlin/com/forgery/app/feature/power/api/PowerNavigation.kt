package com.forgery.app.feature.power.api

import androidx.navigation.NavController
import kotlinx.serialization.Serializable

@Serializable
data class PowerRoute(val id: String? = null)

const val POWER_ROUTE = "power"

fun NavController.navigateToPower(id: String? = null) {
    navigate(PowerRoute(id))
}
