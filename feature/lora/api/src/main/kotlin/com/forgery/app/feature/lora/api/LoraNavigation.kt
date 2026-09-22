package com.forgery.app.feature.lora.api

import androidx.navigation.NavController
import kotlinx.serialization.Serializable

@Serializable
data class LoraRoute(val mode: String? = null)

const val LORA_ROUTE = "lora"

fun NavController.navigateToLora(mode: String? = null) {
    navigate(LoraRoute(mode))
}
