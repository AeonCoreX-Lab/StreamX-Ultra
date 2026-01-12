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
import com.aeoncorex.streamx.ui.main.MainScreen
import com.aeoncorex.streamx.ui.movie.MovieDetailScreen
import com.aeoncorex.streamx.ui.player.PlayerScreen
import com.aeoncorex.streamx.ui.settings.SettingsScreen
import com.aeoncorex.streamx.ui.splash.SplashScreen
import com.aeoncorex.streamx.ui.theme.ThemeScreen
import com.aeoncorex.streamx.ui.theme.ThemeViewModel
import com.aeoncorex.streamx.ui.privacy.PrivacyPolicyScreen
import com.aeoncorex.streamx.ui.about.AboutScreen
import com.aeoncorex.streamx.ui.music.MusicScreen
import com.aeoncorex.streamx.ui.music.MusicPlayerScreen
import java.net.URLDecoder

@Composable
fun AppNavigation(themeViewModel: ThemeViewModel) {
    val navController = rememberNavController()
    NavHost(navController = navController, startDestination = "splash") {
        composable("splash") { SplashScreen(navController) }
        composable("auth") { AuthScreen(navController) }
        
        // হোম রুট (বটম নেভিগেশন সহ মেইন স্ক্রিন)
        composable("home") { MainScreen(navController) }
        
        // মিউজিক মেইন স্ক্রিন
        composable("music") { MusicScreen(navController) }

        // মুভি ডিটেইল স্ক্রিন রুট
        composable(
            route = "movie_detail/{movieId}",
            arguments = listOf(navArgument("movieId") { type = NavType.StringType })
        ) { backStackEntry ->
            val movieId = backStackEntry.arguments?.getString("movieId") ?: ""
            MovieDetailScreen(navController, movieId)
        }
        
        // ভিডিও প্লেয়ার রুট
        composable(
            route = "player/{encodedUrl}",
            arguments = listOf(navArgument("encodedUrl") { type = NavType.StringType })
        ) { backStackEntry ->
            val encodedUrl = backStackEntry.arguments?.getString("encodedUrl") ?: ""
            PlayerScreen(navController = navController, encodedUrl = encodedUrl)
        }

        // মিউজিক প্লেয়ার রুট (অ্যাডভান্সড আর্গুমেন্ট সহ)
        composable(
            route = "music_player/{title}/{artist}/{imageUrl}/{streamUrl}",
            arguments = listOf(
                navArgument("title") { type = NavType.StringType },
                navArgument("artist") { type = NavType.StringType },
                navArgument("imageUrl") { type = NavType.StringType },
                navArgument("streamUrl") { type = NavType.StringType }
            )
        ) { backStackEntry ->
            val title = backStackEntry.arguments?.getString("title") ?: ""
            val artist = backStackEntry.arguments?.getString("artist") ?: ""
            val imageUrl = URLDecoder.decode(backStackEntry.arguments?.getString("imageUrl") ?: "", "UTF-8")
            val streamUrl = URLDecoder.decode(backStackEntry.arguments?.getString("streamUrl") ?: "", "UTF-8")
            
            MusicPlayerScreen(
                title = title,
                artist = artist,
                imageUrl = imageUrl,
                streamUrl = streamUrl,
                onBack = { navController.popBackStack() }
            )
        }
        
        composable("settings") { SettingsScreen(navController) }
        composable("account") { AccountScreen(navController) }
        composable("theme") { ThemeScreen(navController, themeViewModel) }
        composable("privacy") { PrivacyPolicyScreen(navController) }
        composable("about") { AboutScreen(navController) }
        composable("copyright") { CopyrightScreen(navController) }
    }
}
