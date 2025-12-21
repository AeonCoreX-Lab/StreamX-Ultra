package com.aeoncorex.streamx.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.aeoncorex.streamx.ui.auth.AuthScreen
import com.aeoncorex.streamx.ui.home.HomeScreen
import com.aeoncorex.streamx.ui.player.PlayerScreen
import com.aeoncorex.streamx.ui.splash.SplashScreen
import com.aeoncorex.streamx.ui.settings.SettingsScreen // নতুন
import com.aeoncorex.streamx.ui.account.AccountScreen   // নতুন
import com.aeoncorex.streamx.ui.theme.ThemeScreen       // নতুন

@Composable
fun AppNavigation() {
    val navController = rememberNavController()
    NavHost(navController = navController, startDestination = "splash") {
        composable("splash") { SplashScreen(navController) }
        composable("auth") { AuthScreen(navController) }
        composable("home") { HomeScreen(navController) }
        
        composable(
            route = "player/{encodedUrl}",
            arguments = listOf(navArgument("encodedUrl") { type = NavType.StringType })
        ) { backStackEntry ->
            val encodedUrl = backStackEntry.arguments?.getString("encodedUrl") ?: ""
            PlayerScreen(
                encodedUrl = encodedUrl,
                onBack = { navController.popBackStack() }
            )
        }
        
        // নতুন রুট
        composable("settings") { SettingsScreen(navController) }
        composable("account") { AccountScreen(navController) }
        composable("theme") { ThemeScreen(navController) }
    }
}