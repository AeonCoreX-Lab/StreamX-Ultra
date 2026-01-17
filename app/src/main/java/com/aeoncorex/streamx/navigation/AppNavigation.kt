package com.aeoncorex.streamx.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
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
import com.aeoncorex.streamx.ui.player.PlayerScreen 
import com.aeoncorex.streamx.ui.copyright.CopyrightScreen 

// --- MOVIE IMPORTS ---
import com.aeoncorex.streamx.ui.movie.MovieScreen
import com.aeoncorex.streamx.ui.movie.MoviePlayerScreen
import com.aeoncorex.streamx.ui.movie.MovieDetailsScreen
import com.aeoncorex.streamx.ui.movie.MovieSettingsScreen
import com.aeoncorex.streamx.ui.movie.MovieServerSelectionScreen // Ensure this is imported

@Composable
fun AppNavigation(themeViewModel: ThemeViewModel) {
    val navController = rememberNavController()
    
    NavHost(navController = navController, startDestination = "splash") {
        composable("splash") { SplashScreen(navController) }
        composable("auth") { AuthScreen(navController) }
        composable("home") { MainScreen(navController) }
        
        // Live TV Player Route
        composable(
            route = "player/{encodedUrl}",
            arguments = listOf(navArgument("encodedUrl") { type = NavType.StringType })
        ) { backStackEntry ->
            val encodedUrl = backStackEntry.arguments?.getString("encodedUrl") ?: ""
            PlayerScreen(navController = navController, encodedUrl = encodedUrl)
        }
        
        // --- MOVIE SECTION ROUTES ---
        composable("movie") {
            MovieScreen(navController)
        }
        
        composable("movie_settings") {
            MovieSettingsScreen(navController)
        }
        
        // MOVIE DETAIL ROUTE
        composable(
            route = "movie_detail/{movieId}/{type}",
            arguments = listOf(
                navArgument("movieId") { type = NavType.IntType },
                navArgument("type") { type = NavType.StringType }
            )
        ) { backStackEntry ->
            val movieId = backStackEntry.arguments?.getInt("movieId") ?: 0
            val type = backStackEntry.arguments?.getString("type") ?: "MOVIE"
            MovieDetailsScreen(navController, movieId, type)
        }

        // NEW: SERVER SELECTION ROUTE (Netflix Style)
        composable(
            route = "server_selection/{movieId}/{title}/{type}/{season}/{episode}",
            arguments = listOf(
                navArgument("movieId") { type = NavType.IntType },
                navArgument("title") { type = NavType.StringType },
                navArgument("type") { type = NavType.StringType }, // "MOVIE" or "SERIES"
                navArgument("season") { type = NavType.IntType },
                navArgument("episode") { type = NavType.IntType }
            )
        ) { backStackEntry ->
            val movieId = backStackEntry.arguments?.getInt("movieId") ?: 0
            val title = backStackEntry.arguments?.getString("title") ?: ""
            val type = backStackEntry.arguments?.getString("type") ?: "MOVIE"
            val season = backStackEntry.arguments?.getInt("season") ?: 0
            val episode = backStackEntry.arguments?.getInt("episode") ?: 0
            
            MovieServerSelectionScreen(navController, movieId, title, type, season, episode)
        }

        // MOVIE PLAYER (Internal)
        composable(
            route = "movie_player/{url}",
            arguments = listOf(navArgument("url") { type = NavType.StringType })
        ) { backStackEntry ->
            val url = backStackEntry.arguments?.getString("url") ?: ""
            MoviePlayerScreen(navController, url) 
        }
        
        // --- MUSIC SECTION ROUTES ---
        composable("music") { MusicScreen(navController) }
        composable("music_player") { MusicPlayerScreen(navController) }
        
        // --- OTHER ROUTES ---
        composable("settings") { SettingsScreen(navController) }
        composable("account") { AccountScreen(navController) }
        composable("theme") { ThemeScreen(navController, themeViewModel) }
        composable("privacy") { PrivacyPolicyScreen(navController) }
        composable("about") { AboutScreen(navController) }
        composable("copyright") { CopyrightScreen(navController) }
    }
}
