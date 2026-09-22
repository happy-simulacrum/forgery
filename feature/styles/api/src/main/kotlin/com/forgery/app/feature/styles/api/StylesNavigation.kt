package com.forgery.app.feature.styles.api

import androidx.navigation.NavController
import kotlinx.serialization.Serializable

@Serializable
data class StylesRoute(val mode: String? = null)

const val STYLES_ROUTE = "styles"

fun NavController.navigateToStyles(mode: String? = null) {
    navigate(StylesRoute(mode))
}
