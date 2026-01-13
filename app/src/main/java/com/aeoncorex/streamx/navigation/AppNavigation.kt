package com.aeoncorex.streamx.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.aeoncorex.streamx.ui.account.AccountScreen
import com.aeoncorex.streamx.ui.auth.AuthScreen
import com.aeoncorex.streamx.ui.main.MainScreen
import com.aeoncorex.streamx.ui.settings.SettingsScreen
import com.aeoncorex.streamx.ui.splash.SplashScreen
import com.aeoncorex.streamx.ui.theme.ThemeScreen
import com.aeoncorex.streamx.ui.theme.ThemeViewModel
import com.aeoncorex.streamx.ui.privacy.PrivacyPolicyScreen
import com.aeoncorex.streamx.ui.about.AboutScreen
import com.aeoncorex.streamx.ui.music.MusicScreen
import com.aeoncorex.streamx.ui.music.MusicPlayerScreen

@Composable
fun AppNavigation(themeViewModel: ThemeViewModel) {
    val navController = rememberNavController()
    NavHost(navController = navController, startDestination = "splash") {
        composable("splash") { SplashScreen(navController) }
        composable("auth") { AuthScreen(navController) }
        composable("home") { MainScreen(navController) }
        
        // ভিডিও প্লেয়ার রুট
        composable(
            route = "player/{encodedUrl}",
            arguments = listOf(navArgument("encodedUrl") { type = NavType.StringType })
        ) { backStackEntry ->
            val encodedUrl = backStackEntry.arguments?.getString("encodedUrl") ?: ""
            PlayerScreen(navController = navController, encodedUrl = encodedUrl)
        }
        
        // --- MUSIC SECTION ROUTES ---
        composable("music") { 
            MusicScreen(navController) 
        }
        composable("music_player") { 
            MusicPlayerScreen(navController) 
        }
        
        // --- OTHER ROUTES ---
        composable("settings") { SettingsScreen(navController) }
        composable("account") { AccountScreen(navController) }
        composable("theme") { ThemeScreen(navController, themeViewModel) }
        composable("privacy") { PrivacyPolicyScreen(navController) }
        composable("about") { AboutScreen(navController) }
        composable("copyright") { CopyrightScreen(navController) }
    }
}
