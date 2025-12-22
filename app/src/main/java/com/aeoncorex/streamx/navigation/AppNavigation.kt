package com.aeoncorex.streamx.navigation

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavController
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.*
import com.aeoncorex.streamx.ui.account.AccountScreen
import com.aeoncorex.streamx.ui.auth.AuthScreen
import com.aeoncorex.streamx.ui.copyright.CopyrightScreen
import com.aeoncorex.streamx.ui.events.EventsScreen
import com.aeoncorex.streamx.ui.home.AppDrawer
import com.aeoncorex.streamx.ui.home.HomeScreen
import com.aeoncorex.streamx.ui.livetv.LiveTVScreen
import com.aeoncorex.streamx.ui.player.PlayerScreen
import com.aeoncorex.streamx.ui.settings.SettingsScreen
import com.aeoncorex.streamx.ui.splash.SplashScreen
import com.aeoncorex.streamx.ui.theme.ThemeScreen
import com.aeoncorex.streamx.ui.theme.ThemeViewModel
import kotlinx.coroutines.launch

// --- Bottom Navigation-এর জন্য স্ক্রিনের সংজ্ঞা ---
sealed class Screen(val route: String, val label: String, val icon: ImageVector) {
    object Events : Screen("events", "Events", Icons.Default.Event)
    object LiveTV : Screen("livetv", "Live TV", Icons.Default.LiveTv)
    object Favorites : Screen("favorites", "Favorites", Icons.Default.Favorite)
}

val bottomNavItems = listOf(
    Screen.Events,
    Screen.LiveTV,
    Screen.Favorites
)

@Composable
fun AppNavigation(themeViewModel: ThemeViewModel) {
    val navController = rememberNavController()
    
    NavHost(navController = navController, startDestination = "splash") {
        
        // --- Bottom Bar ছাড়া স্ক্রিনগুলো ---
        composable("splash") { SplashScreen(navController) }
        composable("auth") { AuthScreen(navController) }
        composable(
            route = "player/{encodedUrl}",
            arguments = listOf(navArgument("encodedUrl") { type = NavType.StringType })
        ) { backStackEntry ->
            val encodedUrl = backStackEntry.arguments?.getString("encodedUrl") ?: ""
            PlayerScreen(encodedUrl = encodedUrl, onBack = { navController.popBackStack() })
        }
        composable("settings") { SettingsScreen(navController) }
        composable("account") { AccountScreen(navController) }
        composable("theme") { ThemeScreen(navController, themeViewModel = themeViewModel) }
        composable("copyright") { CopyrightScreen(navController) }
        
        // --- HomeScreen (Hub) এর জন্য নতুন রুট ---
        composable("home_hub") {
            HomeScreen(navController)
        }
        
        // --- Bottom Bar সহ প্রধান স্ক্রিনগুলোর কন্টেইনার ---
        // লগইন করার পর ব্যবহারকারী এই রুটে আসবে
        composable("main_screen") {
            MainScreen(mainNavController = navController)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(mainNavController: NavController) {
    val bottomNavController = rememberNavController() // Bottom Bar-এর জন্য
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            AppDrawer(
                navController = mainNavController, // প্রধান NavController পাস করা হচ্ছে
                onCloseDrawer = { scope.launch { drawerState.close() } }
            )
        }
    ) {
        Scaffold(
            topBar = {
                CenterAlignedTopAppBar(
                    title = { Text("STREAMX ULTRA") }, // এখানে বর্তমান ট্যাবের নামও দেখানো যেতে পারে
                    navigationIcon = {
                        IconButton(onClick = { scope.launch { drawerState.open() } }) {
                            Icon(Icons.Default.Menu, contentDescription = "Menu")
                        }
                    },
                    actions = {
                        IconButton(onClick = { /* TODO: Search */ }) {
                            Icon(Icons.Default.Search, contentDescription = "Search")
                        }
                    }
                )
            },
            bottomBar = { AppBottomNavBar(navController = bottomNavController) }
        ) { padding ->
            NavHost(bottomNavController, startDestination = Screen.Events.route, Modifier.padding(padding)) {
                composable(Screen.Events.route) { EventsScreen(mainNavController) }
                composable(Screen.LiveTV.route) { LiveTVScreen(mainNavController) }
                // composable(Screen.Favorites.route) { FavoritesScreen(mainNavController) } // TODO
            }
        }
    }
}

@Composable
fun AppBottomNavBar(navController: NavController) {
    NavigationBar {
        val navBackStackEntry by navController.currentBackStackEntryAsState()
        val currentDestination = navBackStackEntry?.destination

        bottomNavItems.forEach { screen ->
            NavigationBarItem(
                icon = { Icon(screen.icon, contentDescription = screen.label) },
                label = { Text(screen.label) },
                selected = currentDestination?.hierarchy?.any { it.route == screen.route } == true,
                onClick = {
                    navController.navigate(screen.route) {
                        popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                        launchSingleTop = true
                        restoreState = true
                    }
                }
            )
        }
    }
}