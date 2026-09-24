package com.forgery.app.app

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.ImageSearch
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.forgery.app.feature.analyze.api.AnalyzeRoute
import com.forgery.app.feature.analyze.impl.analyzeScreen
import com.forgery.app.feature.gallery.api.GalleryDetailRoute
import com.forgery.app.feature.gallery.api.GalleryRoute
import com.forgery.app.feature.gallery.impl.galleryDetailScreen
import com.forgery.app.feature.gallery.impl.galleryScreen
import com.forgery.app.feature.generate.api.GenerateRoute
import com.forgery.app.feature.generate.impl.generateScreen
import com.forgery.app.feature.inpaint.api.InpaintRoute
import com.forgery.app.feature.inpaint.impl.inpaintScreen
import com.forgery.app.feature.lora.api.LoraRoute
import com.forgery.app.feature.lora.impl.loraScreen
import com.forgery.app.feature.modules.api.ModulesRoute
import com.forgery.app.feature.modules.impl.modulesScreen
import com.forgery.app.feature.queue.api.QueueRoute
import com.forgery.app.feature.queue.impl.queueScreen
import com.forgery.app.feature.settings.api.SettingsRoute
import com.forgery.app.feature.settings.impl.settingsScreen
import com.forgery.app.feature.styles.api.StylesRoute
import com.forgery.app.feature.styles.impl.stylesScreen

private data class TopDest(val label: String, val icon: ImageVector, val route: Any)

@Composable
fun ForgeryNavHost(
    queueBadgeViewModel: QueueBadgeViewModel = hiltViewModel(),
) {
    val nav = rememberNavController()
    val pendingCount by queueBadgeViewModel.pendingCount.collectAsStateWithLifecycle()
    val dests = listOf(
        TopDest("GEN", Icons.Filled.AutoAwesome, GenerateRoute()),
        TopDest("INP", Icons.Filled.Brush, InpaintRoute()),
        TopDest("QUE", Icons.Filled.Layers, QueueRoute()),
        TopDest("GAL", Icons.Filled.Image, GalleryRoute()),
        TopDest("ANA", Icons.Filled.ImageSearch, AnalyzeRoute()),
        TopDest("CFG", Icons.Filled.Settings, SettingsRoute()),
    )
    Scaffold(
        bottomBar = {
            NavigationBar {
                val current by nav.currentBackStackEntryAsState()
                val cur = current?.destination?.route
                dests.forEach { d ->
                    val selected = when (d.route) {
                        is GenerateRoute -> cur?.contains("Generate") == true
                        is InpaintRoute -> cur?.contains("Inpaint") == true
                        is QueueRoute -> cur?.contains("Queue") == true
                        is GalleryRoute -> cur?.contains("Gallery") == true
                        is AnalyzeRoute -> cur?.contains("Analyze") == true
                        is SettingsRoute -> cur?.contains("Settings") == true
                        else -> false
                    }
                    NavigationBarItem(
                        selected = selected,
                        onClick = {
                            nav.navigate(d.route) {
                                popUpTo(nav.graph.startDestinationId) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = {
                            if (d.route is QueueRoute && pendingCount > 0) {
                                BadgedBox(
                                    badge = {
                                        Badge(
                                            containerColor = Color(0xFFFFC107),
                                            contentColor = Color.Black,
                                        ) {
                                            Text(formatBadgeCount(pendingCount))
                                        }
                                    },
                                ) {
                                    Icon(
                                        d.icon,
                                        contentDescription = "Queue, $pendingCount pending",
                                    )
                                }
                            } else {
                                Icon(d.icon, d.label)
                            }
                        },
                        label = { Text(d.label) },
                    )
                }
            }
        },
    ) { pad ->
        NavHost(nav, startDestination = GenerateRoute(), Modifier.padding(pad)) {
            generateScreen(
                onBackClick = {},
                onNavigateToQueue = { nav.navigate(QueueRoute()) },
                onNavigateToLora = { nav.navigate(LoraRoute) },
                onNavigateToStyles = { nav.navigate(StylesRoute) },
                onNavigateToModules = { modelTitle -> nav.navigate(ModulesRoute(modelTitle)) },
            )
            inpaintScreen(
                onBackClick = {},
                onNavigateToModules = { modelTitle -> nav.navigate(ModulesRoute(modelTitle)) },
            )
            queueScreen(onBackClick = {})
            galleryScreen(
                onBackClick = {},
                onNavigateToDetail = { id -> nav.navigate(GalleryDetailRoute(id)) },
            )
            galleryDetailScreen(
                onBackClick = { nav.popBackStack() },
                onNavigateToAnalyze = { path -> nav.navigate(AnalyzeRoute(imagePath = path)) },
                onNavigateToInpaint = { path -> nav.navigate(InpaintRoute(path)) },
            )
            analyzeScreen(onNavigateToGenerate = {
                nav.navigate(GenerateRoute()) {
                    popUpTo(nav.graph.startDestinationId) { saveState = true }
                    launchSingleTop = true
                    restoreState = true
                }
            })
            settingsScreen(onBackClick = {})
            loraScreen(onBackClick = { nav.popBackStack() })
            modulesScreen(onBackClick = { nav.popBackStack() })
            stylesScreen(onBackClick = { nav.popBackStack() })
        }
    }
}
