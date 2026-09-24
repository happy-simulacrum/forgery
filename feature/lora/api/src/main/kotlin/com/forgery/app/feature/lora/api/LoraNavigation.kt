package com.forgery.app.feature.lora.api

import androidx.navigation.NavController
import kotlinx.serialization.Serializable

@Serializable
data object LoraRoute

const val LORA_ROUTE = "lora"

fun NavController.navigateToLora() {
    navigate(LoraRoute)
}
