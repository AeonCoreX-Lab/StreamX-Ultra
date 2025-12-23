package com.aeoncorex.streamx.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.aeoncorex.streamx.ui.account.AccountScreen
import com.aeoncorex.streamx.ui.auth.AuthScreen
import com.aeoncorex.streamx.ui.copyright.CopyrightScreen
import com.aeoncorex.streamx.ui.home.HomeScreen
import com.aeoncorex.streamx.ui.player.PlayerScreen
import com.aeoncorex.streamx.ui.settings.SettingsScreen
import com.aeoncorex.streamx.ui.splash.SplashScreen
import com.aeoncorex.streamx.ui.theme.ThemeScreen
import com.aeoncorex.streamx.ui.theme.ThemeViewModel
import com.aeoncorex.streamx.ui.about.AboutScreen
import com.aeoncorex.streamx.ui.privacy.PrivacyPolicyScreen

@Composable
fun AppNavigation(themeViewModel: ThemeViewModel) {
    val navController = rememberNavController()
    
    NavHost(navController = navController, startDestination = "splash") {
        
        // ১. স্প্ল্যাশ স্ক্রিন
        composable("splash") { 
            SplashScreen(navController) 
        }
        
        // ২. অথেন্টিকেশন
        composable("auth") { 
            AuthScreen(navController) 
        }
        
        // ৩. হোম স্ক্রিন (এখন এটিই প্রধান স্ক্রিন)
        composable("home") {
            HomeScreen(navController)
        }
        
        // ৪. প্লেয়ার স্ক্রিন
        composable(
            route = "player/{encodedUrl}",
            arguments = listOf(navArgument("encodedUrl") { type = NavType.StringType })
        ) { backStackEntry ->
            val encodedUrl = backStackEntry.arguments?.getString("encodedUrl") ?: ""
            PlayerScreen(encodedUrl = encodedUrl, onBack = { navController.popBackStack() })
        }
        
        // ৫. অন্যান্য স্ক্রিন (সেটিংস, অ্যাকাউন্ট, ইত্যাদি)
        composable("settings") { SettingsScreen(navController) }
        composable("account") { AccountScreen(navController) }
        composable("theme") { ThemeScreen(navController, themeViewModel = themeViewModel) }
        composable("copyright") { CopyrightScreen(navController) }
        composable("about") { AboutScreen(navController) }
        composable("privacy_policy") { PrivacyPolicyScreen(navController) }
    }
}