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
import com.aeoncorex.streamx.ui.onboarding.OnboardingScreen
import com.aeoncorex.streamx.ui.theme.ThemeScreen
import com.aeoncorex.streamx.ui.theme.ThemeViewModel
import com.aeoncorex.streamx.ui.privacy.PrivacyPolicyScreen
import com.aeoncorex.streamx.ui.about.AboutScreen
import com.aeoncorex.streamx.ui.music.MusicScreen
import com.aeoncorex.streamx.ui.music.MusicPlayerScreen
import com.aeoncorex.streamx.ui.player.PlayerScreen
import com.aeoncorex.streamx.ui.copyright.CopyrightScreen
import com.aeoncorex.streamx.ui.premium.PremiumScreen
import com.aeoncorex.streamx.ui.movie.MovieScreen
import com.aeoncorex.streamx.ui.movie.MovieDetailsScreen
import com.aeoncorex.streamx.ui.movie.MovieSettingsScreen
import com.aeoncorex.streamx.ui.movie.MovieLinkSelectionScreen
import com.aeoncorex.streamx.ui.movie.MoviePlayerScreen       // MPV torrent player (renamed internally)
import com.aeoncorex.streamx.ui.movie.ExoMoviePlayerScreen   // New instant ExoPlayer
import com.aeoncorex.streamx.ui.movie.ExoSourceSelectionScreen // NEW: web source selection
import com.aeoncorex.streamx.ui.notifications.NotificationsScreen
import com.aeoncorex.streamx.ui.movie.PersonDetailScreen

// ── REMOVED: AdBlockWebView (WebView player no longer needed) ─────
// ── REMOVED: webview_player route ────────────────────────────────

@Composable
fun AppNavigation(themeViewModel: ThemeViewModel) {
    val navController = rememberNavController()

    NavHost(navController = navController, startDestination = "splash") {
        composable("splash")      { SplashScreen(navController) }
        composable("onboarding")  { OnboardingScreen(navController) }
        composable("auth")        { AuthScreen(navController) }
        composable("home")        { MainScreen(navController) }

        // ── Live TV Player ────────────────────────────────────────
        composable(
            route     = "player/{encodedUrl}",
            arguments = listOf(navArgument("encodedUrl") { type = NavType.StringType })
        ) { backStack ->
            PlayerScreen(
                navController = navController,
                encodedUrl    = backStack.arguments?.getString("encodedUrl") ?: ""
            )
        }

        // ── Movie section ─────────────────────────────────────────
        composable("movie")          { MovieScreen(navController) }
        composable("movie_settings") { MovieSettingsScreen(navController) }

        composable(
            route     = "movie_detail/{movieId}/{type}",
            arguments = listOf(
                navArgument("movieId") { type = NavType.IntType },
                navArgument("type")    { type = NavType.StringType }
            )
        ) { backStack ->
            MovieDetailsScreen(
                navController = navController,
                movieId       = backStack.arguments?.getInt("movieId") ?: 0,
                movieType     = backStack.arguments?.getString("type") ?: "MOVIE"
            )
        }

        // ── NEW: Instant ExoPlayer source selection (web sources) ─
        // Triggered by "Play Now" button
        composable(
            route     = "exo_source/{imdbId}/{tmdbId}/{title}/{type}/{season}/{episode}",
            arguments = listOf(
                navArgument("imdbId")  { type = NavType.StringType },
                navArgument("tmdbId")  { type = NavType.IntType },
                navArgument("title")   { type = NavType.StringType },
                navArgument("type")    { type = NavType.StringType },
                navArgument("season")  { type = NavType.IntType },
                navArgument("episode") { type = NavType.IntType }
            )
        ) { backStack ->
            ExoSourceSelectionScreen(
                navController = navController,
                imdbId        = backStack.arguments?.getString("imdbId")  ?: "",
                tmdbId        = backStack.arguments?.getInt("tmdbId")     ?: 0,
                title         = backStack.arguments?.getString("title")   ?: "",
                type          = backStack.arguments?.getString("type")    ?: "MOVIE",
                season        = backStack.arguments?.getInt("season")     ?: 0,
                episode       = backStack.arguments?.getInt("episode")    ?: 0
            )
        }

        // ── NEW: Instant ExoPlayer (Movie Box style) ──────────────
        composable(
            route     = "exo_player/{encodedUrl}/{title}/{quality}/{language}/{imdbId}/{type}/{season}/{episode}",
            arguments = listOf(
                navArgument("encodedUrl") { type = NavType.StringType },
                navArgument("title")      { type = NavType.StringType },
                navArgument("quality")    { type = NavType.StringType },
                navArgument("language")   { type = NavType.StringType },
                navArgument("imdbId")     { type = NavType.StringType },
                navArgument("type")       { type = NavType.StringType },
                navArgument("season")     { type = NavType.IntType },
                navArgument("episode")    { type = NavType.IntType }
            )
        ) { backStack ->
            ExoMoviePlayerScreen(
                navController = navController,
                streamUrl     = backStack.arguments?.getString("encodedUrl") ?: "",
                title         = backStack.arguments?.getString("title")      ?: "",
                quality       = backStack.arguments?.getString("quality")    ?: "Auto",
                language      = backStack.arguments?.getString("language")   ?: "English",
                imdbId        = backStack.arguments?.getString("imdbId"),
                movieType     = backStack.arguments?.getString("type")       ?: "MOVIE",
                season        = backStack.arguments?.getInt("season")        ?: 0,
                episode       = backStack.arguments?.getInt("episode")       ?: 0
            )
        }

        // ── Torrent link selection (only torrents, no web servers) ─
        composable(
            route     = "torrent_selection/{imdbId}/{tmdbId}/{title}/{type}/{season}/{episode}",
            arguments = listOf(
                navArgument("imdbId")   { type = NavType.StringType },
                navArgument("tmdbId")   { type = NavType.IntType },
                navArgument("title")    { type = NavType.StringType },
                navArgument("type")     { type = NavType.StringType },
                navArgument("season")   { type = NavType.IntType },
                navArgument("episode")  { type = NavType.IntType }
            )
        ) { backStack ->
            MovieLinkSelectionScreen(
                navController = navController,
                imdbId   = backStack.arguments?.getString("imdbId")  ?: "",
                tmdbId   = backStack.arguments?.getInt("tmdbId")     ?: 0,
                title    = backStack.arguments?.getString("title")   ?: "",
                type     = backStack.arguments?.getString("type")    ?: "MOVIE",
                season   = backStack.arguments?.getInt("season")     ?: 0,
                episode  = backStack.arguments?.getInt("episode")    ?: 0
            )
        }

        // ── MPV Torrent Player (kept, renamed route) ──────────────
        composable(
            route     = "torrent_player/{encodedUrl}",
            arguments = listOf(navArgument("encodedUrl") { type = NavType.StringType })
        ) { backStack ->
            MoviePlayerScreen(
                navController = navController,
                encodedUrl    = backStack.arguments?.getString("encodedUrl") ?: ""
            )
        }

        // ── Music ─────────────────────────────────────────────────
        composable("music")        { MusicScreen(navController) }
        composable("music_player") { MusicPlayerScreen(navController) }

        // ── Other screens ─────────────────────────────────────────
        composable("settings") { SettingsScreen(navController) }
        composable("account")  { AccountScreen(navController) }
        composable("theme")    { ThemeScreen(navController, themeViewModel) }
        composable("privacy")  { PrivacyPolicyScreen(navController) }
        composable("about")    { AboutScreen(navController) }
        composable("copyright"){ CopyrightScreen(navController) }
        composable("premium")  { PremiumScreen(navController) }
        composable("notifications") { NotificationsScreen() }
        composable("person_detail/{personId}") { backStackEntry ->
            val personId = backStackEntry.arguments?.getString("personId")?.toIntOrNull() ?: return@composable
            PersonDetailScreen(personId = personId, navController = navController)
        }
    }
}
