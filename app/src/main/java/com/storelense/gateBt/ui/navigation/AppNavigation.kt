package com.storelense.gateBt.ui.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.storelense.gateBt.ui.gate.DashboardScreen
import com.storelense.gateBt.ui.gate.GateScanScreen
import com.storelense.gateBt.ui.login.LoginScreen
import com.storelense.gateBt.ui.settings.BtSettingsScreen

private const val ROUTE_LOGIN     = "login"
private const val ROUTE_GATE      = "gate"
private const val ROUTE_DASHBOARD = "dashboard"
private const val ROUTE_SETTINGS  = "settings"

@Composable
fun AppNavigation(startLoggedIn: Boolean) {
    val navController = rememberNavController()
    val startRoute    = if (startLoggedIn) ROUTE_GATE else ROUTE_LOGIN

    NavHost(navController = navController, startDestination = startRoute) {

        composable(ROUTE_LOGIN) {
            LoginScreen(
                onLoginSuccess = {
                    navController.navigate(ROUTE_GATE) {
                        popUpTo(ROUTE_LOGIN) { inclusive = true }
                    }
                }
            )
        }

        composable(ROUTE_GATE) {
            GateScanScreen(
                onLogout = {
                    navController.navigate(ROUTE_LOGIN) {
                        popUpTo(ROUTE_GATE) { inclusive = true }
                    }
                },
                onDashboard = { navController.navigate(ROUTE_DASHBOARD) },
                onSettings  = { navController.navigate(ROUTE_SETTINGS) }
            )
        }

        composable(ROUTE_DASHBOARD) {
            DashboardScreen(onBack = { navController.popBackStack() })
        }

        composable(ROUTE_SETTINGS) {
            BtSettingsScreen(onBack = { navController.popBackStack() })
        }
    }
}
