package com.forgery.app.feature.inpaint.api

import androidx.navigation.NavController
import kotlinx.serialization.Serializable

@Serializable
data class InpaintRoute(val id: String? = null)

const val INPAINT_ROUTE = "inpaint"

fun NavController.navigateToInpaint(id: String? = null) {
    navigate(InpaintRoute(id))
}
