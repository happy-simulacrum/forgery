package com.forgery.app.feature.queue.api

import androidx.navigation.NavController
import kotlinx.serialization.Serializable

@Serializable
data class QueueRoute(val id: String? = null)

const val QUEUE_ROUTE = "queue"

fun NavController.navigateToQueue(id: String? = null) {
    navigate(QueueRoute(id))
}
