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
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

@Composable
fun AppNavigation() {
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
        // FIXED: Route now accepts channelId AND streamUrl
        composable(
            route = "player/{channelId}/{streamUrl}",
            arguments = listOf(
                navArgument("channelId") { type = NavType.StringType },
                navArgument("streamUrl") { type = NavType.StringType }
            )
        ) { backStackEntry ->
            val channelId = backStackEntry.arguments?.getString("channelId") ?: ""
            val encodedUrl = backStackEntry.arguments?.getString("streamUrl") ?: ""
            val decodedUrl = URLDecoder.decode(encodedUrl, StandardCharsets.UTF_8.toString())
            
            // FIXED: Passed both required parameters to PlayerScreen
            PlayerScreen(navController, channelId, decodedUrl)
        }
    }
}

// URL এনকোড করার জন্য একটি Helper ফাংশন
fun encodeUrl(url: String): String {
    return URLEncoder.encode(url, StandardCharsets.UTF_8.toString())
}
