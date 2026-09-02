package com.auraguard.app.ui.navigation

import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.EventNote
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.Widgets
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.auraguard.app.core.AuraViewModel
import com.auraguard.app.ui.screens.events.EventsScreen
import com.auraguard.app.ui.screens.live.LiveScreen
import com.auraguard.app.ui.screens.settings.SettingsScreen
import com.auraguard.app.ui.screens.zones.ZonesScreen
import com.auraguard.app.ui.theme.OpsAccent
import com.auraguard.app.ui.theme.OpsBackground
import com.auraguard.app.ui.theme.OpsSurface
import com.auraguard.app.ui.theme.OpsTextSecondary

sealed class AuraDestination(val route: String, val label: String, val icon: androidx.compose.ui.graphics.vector.ImageVector) {
    data object Live : AuraDestination("live", "LIVE", Icons.Filled.Videocam)
    data object Zones : AuraDestination("zones", "ZONES", Icons.Filled.Widgets)
    data object Events : AuraDestination("events", "EVENTS", Icons.Filled.EventNote)
    data object Settings : AuraDestination("settings", "SETTINGS", Icons.Filled.Settings)
}

private val destinations = listOf(AuraDestination.Live, AuraDestination.Zones, AuraDestination.Events, AuraDestination.Settings)

@Composable
fun AuraNavHost(viewModel: AuraViewModel, onRequestScreenCapture: () -> Unit) {
    val navController = rememberNavController()

    Scaffold(
        containerColor = OpsBackground,
        bottomBar = {
            NavigationBar(containerColor = OpsSurface, tonalElevation = 0.dp) {
                val backStackEntry by navController.currentBackStackEntryAsState()
                val currentDestination = backStackEntry?.destination
                destinations.forEach { dest ->
                    val selected = currentDestination?.hierarchy?.any { it.route == dest.route } == true
                    NavigationBarItem(
                        selected = selected,
                        onClick = {
                            navController.navigate(dest.route) {
                                popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = { Icon(dest.icon, contentDescription = dest.label) },
                        label = { Text(dest.label, fontWeight = FontWeight.SemiBold) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = OpsBackground,
                            selectedTextColor = OpsAccent,
                            indicatorColor = OpsAccent,
                            unselectedIconColor = OpsTextSecondary,
                            unselectedTextColor = OpsTextSecondary
                        )
                    )
                }
            }
        }
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = AuraDestination.Live.route,
            modifier = Modifier.padding(padding),
            enterTransition = { fadeIn(tween(150)) },
            exitTransition = { fadeOut(tween(150)) }
        ) {
            composable(AuraDestination.Live.route) { LiveScreen(viewModel, onRequestScreenCapture) }
            composable(AuraDestination.Zones.route) {
                ZonesScreen(viewModel, onNavigateToLive = {
                    navController.navigate(AuraDestination.Live.route) {
                        popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                        launchSingleTop = true
                        restoreState = true
                    }
                })
            }
            composable(AuraDestination.Events.route) { EventsScreen(viewModel) }
            composable(AuraDestination.Settings.route) { SettingsScreen(viewModel) }
        }
    }
}
