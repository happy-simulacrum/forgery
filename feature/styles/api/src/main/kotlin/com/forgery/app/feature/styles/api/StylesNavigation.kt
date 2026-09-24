package com.forgery.app.feature.styles.api

import androidx.navigation.NavController
import kotlinx.serialization.Serializable

@Serializable
data object StylesRoute

const val STYLES_ROUTE = "styles"

fun NavController.navigateToStyles() {
    navigate(StylesRoute)
}
