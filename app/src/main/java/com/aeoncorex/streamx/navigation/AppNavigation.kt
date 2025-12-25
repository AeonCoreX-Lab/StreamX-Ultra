package com.aeoncorex.streamx.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.aeoncorex.streamx.ui.account.AccountScreen
import com.aeoncorex.streamx.ui.auth.AuthScreen
import com.aeoncorex.streamx.ui.copyright.CopyrightScreen // নতুন ইম্পোর্ট
import com.aeoncorex.streamx.ui.home.HomeScreen
import com.aeoncorex.streamx.ui.player.PlayerScreen
import com.aeoncorex.streamx.ui.settings.SettingsScreen
import com.aeoncorex.streamx.ui.splash.SplashScreen
import com.aeoncorex.streamx.ui.theme.ThemeScreen
import com.aeoncorex.streamx.ui.theme.ThemeViewModel

@Composable
fun AppNavigation(themeViewModel: ThemeViewModel) {
    val navController = rememberNavController()
    NavHost(navController = navController, startDestination = "splash") {
        composable("splash") {
            SplashScreen(navController)
        }
        composable("auth") {
            AuthScreen(navController)
        }
        composable("home") {
            HomeScreen(navController)
        }
        
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
        
        composable("settings") {
            SettingsScreen(navController)
        }
        composable("account") {
            AccountScreen(navController)
        }
        composable("theme") {
            ThemeScreen(navController, themeViewModel = themeViewModel)
        }
        
        // --- নতুন Copyright রুটটি এখানে যোগ করা হয়েছে ---
        composable("copyright") {
            CopyrightScreen(navController)
        }
    }
}