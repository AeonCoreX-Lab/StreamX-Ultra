package com.aeoncorex.streamx.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.aeoncorex.streamx.ui.account.AccountScreen
import com.aeoncorex.streamx.ui.addons.AddonScreen
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
import com.aeoncorex.streamx.ui.player.EventPlayerScreen
import com.aeoncorex.streamx.ui.copyright.CopyrightScreen
import com.aeoncorex.streamx.ui.premium.PremiumScreen
import com.aeoncorex.streamx.ui.movie.MovieScreen
import com.aeoncorex.streamx.ui.movie.MovieDetailsScreen
import com.aeoncorex.streamx.ui.movie.MovieSettingsScreen
import com.aeoncorex.streamx.ui.movie.MovieLinkSelectionScreen
import com.aeoncorex.streamx.ui.movie.MoviePlayerScreen
import com.aeoncorex.streamx.ui.movie.ExoMoviePlayerScreen
import com.aeoncorex.streamx.ui.movie.ExoSourceSelectionScreen
import com.aeoncorex.streamx.ui.notifications.NotificationsScreen
import com.aeoncorex.streamx.ui.movie.PersonDetailScreen
import java.net.URLEncoder

@Composable
fun AppNavigation(
    themeViewModel:    ThemeViewModel,
    pendingInstallUrl: String? = null   // from deeplink: streamx://install-addon?url=...
) {
    val navController = rememberNavController()

    // ── Navigate to addon install screen when deeplink is received ─────────────
    LaunchedEffect(pendingInstallUrl) {
        if (!pendingInstallUrl.isNullOrEmpty()) {
            val enc = URLEncoder.encode(pendingInstallUrl, "UTF-8")
            // Navigate to addons screen with pre-filled install URL
            navController.navigate("addons?installUrl=$enc") {
                // Don't add to back stack if we're on splash
                launchSingleTop = true
            }
        }
    }

    NavHost(navController = navController, startDestination = "splash") {
        composable("splash")      { SplashScreen(navController) }
        composable("onboarding")  { OnboardingScreen(navController) }
        composable("auth")        { AuthScreen(navController) }
        composable("home")        { MainScreen(navController) }

        // ── Addon Management ──────────────────────────────────────────────────
        // Accepts optional installUrl from deeplink
        composable(
            route = "addons?installUrl={installUrl}",
            arguments = listOf(
                navArgument("installUrl") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                }
            )
        ) { backStack ->
            val installUrl = backStack.arguments?.getString("installUrl")
                ?.let { java.net.URLDecoder.decode(it, "UTF-8") }
            AddonScreen(navController = navController, autoInstallUrl = installUrl)
        }

        // Simple addons route (no install URL)
        composable("addons") { AddonScreen(navController) }

        // ── Live TV ───────────────────────────────────────────────────────────
        composable(
            route     = "player/{encodedUrl}",
            arguments = listOf(navArgument("encodedUrl") { type = NavType.StringType })
        ) { back ->
            PlayerScreen(navController = navController,
                encodedUrl = back.arguments?.getString("encodedUrl") ?: "")
        }

        composable(
            route = "event_player/{eventId}/{streamIndex}/{encodedTitle}",
            arguments = listOf(
                navArgument("eventId")      { type = NavType.StringType },
                navArgument("streamIndex")  { type = NavType.IntType; defaultValue = 0 },
                navArgument("encodedTitle") { type = NavType.StringType; defaultValue = "" }
            )
        ) { back ->
            EventPlayerScreen(navController = navController,
                eventId      = back.arguments?.getString("eventId")     ?: "",
                streamIndex  = back.arguments?.getInt("streamIndex")    ?: 0,
                encodedTitle = back.arguments?.getString("encodedTitle") ?: "")
        }

        // ── Movie ─────────────────────────────────────────────────────────────
        composable("movie")          { MovieScreen(navController) }
        composable("movie_settings") { MovieSettingsScreen(navController) }

        composable(
            route     = "movie_detail/{movieId}/{type}",
            arguments = listOf(
                navArgument("movieId") { type = NavType.IntType },
                navArgument("type")    { type = NavType.StringType }
            )
        ) { back ->
            MovieDetailsScreen(navController = navController,
                movieId   = back.arguments?.getInt("movieId") ?: 0,
                movieType = back.arguments?.getString("type") ?: "MOVIE")
        }

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
        ) { back ->
            ExoSourceSelectionScreen(navController = navController,
                imdbId  = back.arguments?.getString("imdbId")  ?: "",
                tmdbId  = back.arguments?.getInt("tmdbId")     ?: 0,
                title   = back.arguments?.getString("title")   ?: "",
                type    = back.arguments?.getString("type")    ?: "MOVIE",
                season  = back.arguments?.getInt("season")     ?: 0,
                episode = back.arguments?.getInt("episode")    ?: 0)
        }

        composable(
            route     = "exo_player/{encodedUrl}/{title}/{quality}/{language}/{imdbId}/{type}/{season}/{episode}/{subtitlesJson}/{headersJson}",
            arguments = listOf(
                navArgument("encodedUrl")    { type = NavType.StringType },
                navArgument("title")         { type = NavType.StringType },
                navArgument("quality")       { type = NavType.StringType },
                navArgument("language")      { type = NavType.StringType },
                navArgument("imdbId")        { type = NavType.StringType },
                navArgument("type")          { type = NavType.StringType },
                navArgument("season")        { type = NavType.IntType },
                navArgument("episode")       { type = NavType.IntType },
                navArgument("subtitlesJson") { type = NavType.StringType; defaultValue = "%5B%5D" },
                navArgument("headersJson")   { type = NavType.StringType; defaultValue = "%7B%7D" }
            )
        ) { back ->
            ExoMoviePlayerScreen(navController = navController,
                streamUrl     = back.arguments?.getString("encodedUrl")    ?: "",
                title         = back.arguments?.getString("title")         ?: "",
                quality       = back.arguments?.getString("quality")       ?: "Auto",
                language      = back.arguments?.getString("language")      ?: "English",
                imdbId        = back.arguments?.getString("imdbId"),
                movieType     = back.arguments?.getString("type")          ?: "MOVIE",
                season        = back.arguments?.getInt("season")           ?: 0,
                subtitlesJson = back.arguments?.getString("subtitlesJson") ?: "[]",
                headersJson   = back.arguments?.getString("headersJson")   ?: "{}",
                episode       = back.arguments?.getInt("episode")          ?: 0)
        }

        // ── Torrent ───────────────────────────────────────────────────────────
        composable(
            route     = "torrent_selection/{imdbId}/{tmdbId}/{title}/{type}/{season}/{episode}",
            arguments = listOf(
                navArgument("imdbId")  { type = NavType.StringType },
                navArgument("tmdbId")  { type = NavType.IntType },
                navArgument("title")   { type = NavType.StringType },
                navArgument("type")    { type = NavType.StringType },
                navArgument("season")  { type = NavType.IntType },
                navArgument("episode") { type = NavType.IntType }
            )
        ) { back ->
            MovieLinkSelectionScreen(navController = navController,
                imdbId  = back.arguments?.getString("imdbId")  ?: "",
                tmdbId  = back.arguments?.getInt("tmdbId")     ?: 0,
                title   = back.arguments?.getString("title")   ?: "",
                type    = back.arguments?.getString("type")    ?: "MOVIE",
                season  = back.arguments?.getInt("season")     ?: 0,
                episode = back.arguments?.getInt("episode")    ?: 0)
        }

        composable(
            route     = "torrent_player/{encodedUrl}",
            arguments = listOf(navArgument("encodedUrl") { type = NavType.StringType })
        ) { back ->
            MoviePlayerScreen(navController = navController,
                encodedUrl = back.arguments?.getString("encodedUrl") ?: "")
        }

        // ── Music ─────────────────────────────────────────────────────────────
        composable("music")        { MusicScreen(navController) }
        composable("music_player") { MusicPlayerScreen(navController) }

        // ── Other ─────────────────────────────────────────────────────────────
        composable("settings")      { SettingsScreen(navController) }
        composable("account")       { AccountScreen(navController) }
        composable("theme")         { ThemeScreen(navController, themeViewModel) }
        composable("privacy")       { PrivacyPolicyScreen(navController) }
        composable("about")         { AboutScreen(navController) }
        composable("copyright")     { CopyrightScreen(navController) }
        composable("premium")       { PremiumScreen(navController) }
        composable("notifications") { NotificationsScreen() }
        composable("person_detail/{personId}") { back ->
            PersonDetailScreen(
                personId      = back.arguments?.getString("personId")?.toIntOrNull() ?: return@composable,
                navController = navController)
        }
    }
}
