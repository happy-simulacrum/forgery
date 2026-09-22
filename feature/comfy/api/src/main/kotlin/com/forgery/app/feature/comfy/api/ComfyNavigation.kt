package com.forgery.app.feature.comfy.api

import androidx.navigation.NavController
import kotlinx.serialization.Serializable

@Serializable
data class ComfyRoute(val id: String? = null)

const val COMFY_ROUTE = "comfy"

fun NavController.navigateToComfy(id: String? = null) {
    navigate(ComfyRoute(id))
}
