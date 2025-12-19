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
        // FIXED: Route now accepts only streamUrl as PlayerScreen doesn't use channelId
        composable(
            route = "player/{streamUrl}",
            arguments = listOf(
                navArgument("streamUrl") { type = NavType.StringType }
            )
        ) { backStackEntry ->
            val encodedUrl = backStackEntry.arguments?.getString("streamUrl") ?: ""
            val decodedUrl = URLDecoder.decode(encodedUrl, StandardCharsets.UTF_8.toString())
            
            // FIXED: Removed incorrect arguments. PlayerScreen only takes streamUrl and onBack.
            PlayerScreen(
                streamUrl = decodedUrl,
                onBack = { navController.popBackStack() }
            )
        }
    }
}

fun encodeUrl(url: String): String {
    return URLEncoder.encode(url, StandardCharsets.UTF_8.toString())
}
