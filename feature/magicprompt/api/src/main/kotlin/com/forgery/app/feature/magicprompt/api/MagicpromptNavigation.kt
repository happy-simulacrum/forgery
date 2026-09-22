package com.forgery.app.feature.magicprompt.api

import androidx.navigation.NavController
import kotlinx.serialization.Serializable

@Serializable
data class MagicpromptRoute(val mode: String? = null)

const val MAGICPROMPT_ROUTE = "magicprompt"

fun NavController.navigateToMagicprompt(mode: String? = null) {
    navigate(MagicpromptRoute(mode))
}
