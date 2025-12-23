package com.aeoncorex.streamx.navigation

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.navigation.NavController
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.*
import androidx.navigation.navArgument
import com.aeoncorex.streamx.ui.account.AccountScreen
import com.aeoncorex.streamx.ui.auth.AuthScreen
import com.aeoncorex.streamx.ui.home.AppDrawer
import com.aeoncorex.streamx.ui.home.HomeScreen
import com.aeoncorex.streamx.ui.livetv.LiveTVScreen
import com.aeoncorex.streamx.ui.player.PlayerScreen
import com.aeoncorex.streamx.ui.settings.SettingsScreen
import com.aeoncorex.streamx.ui.splash.SplashScreen
import com.aeoncorex.streamx.ui.theme.ThemeScreen
import com.aeoncorex.streamx.ui.theme.ThemeViewModel
import kotlinx.coroutines.launch

// Screen Enum
sealed class Screen(val route: String, val label: String, val icon: androidx.compose.ui.graphics.vector.ImageVector) {
    object HomeHub : Screen("home_hub", "Home", Icons.Default.Home) // Events & Featured merged here
    object LiveTV : Screen("livetv", "Live TV", Icons.Default.LiveTv)
    object Favorites : Screen("favorites", "Favorites", Icons.Default.Favorite)
}

val bottomNavItems = listOf(
    Screen.HomeHub,
    Screen.LiveTV,
    Screen.Favorites
)

@Composable
fun AppNavigation(themeViewModel: ThemeViewModel) {
    val navController = rememberNavController()

    // Start destination is Splash. Splash will decide whether to go to 'auth' or 'main_screen'
    NavHost(navController = navController, startDestination = "splash") {
        composable("splash") { 
            // Splash Screen লজিক চেক করে এখানে ডিসাইড করবে:
            // যদি ইউজার লগইন থাকে -> navigate("main_screen")
            // না থাকলে -> navigate("auth")
            SplashScreen(navController) 
        }
        
        composable("auth") { 
            // AuthScreen সফল হলে: navController.navigate("main_screen") { popUpTo("auth") { inclusive = true } }
            AuthScreen(navController) 
        }

        // Main App Container (Bottom Nav & Drawer included)
        composable("main_screen") { 
            MainScreen(rootNavController = navController) 
        }

        // Player Screen (Global access)
        composable(
            route = "player/{encodedUrl}",
            arguments = listOf(navArgument("encodedUrl") { type = NavType.StringType })
        ) { backStackEntry ->
            val encodedUrl = backStackEntry.arguments?.getString("encodedUrl") ?: ""
            PlayerScreen(encodedUrl = encodedUrl, onBack = { navController.popBackStack() })
        }

        // Other Global Screens
        composable("settings") { SettingsScreen(navController) }
        composable("account") { AccountScreen(navController) }
        composable("theme") { ThemeScreen(navController, themeViewModel = themeViewModel) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(rootNavController: NavController) {
    val bottomNavController = rememberNavController()
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = { 
            AppDrawer(navController = rootNavController, onCloseDrawer = { scope.launch { drawerState.close() } }) 
        }
    ) {
        Scaffold(
            bottomBar = { AppBottomNavBar(navController = bottomNavController) }
        ) { padding ->
            // Bottom Nav Host
            NavHost(
                navController = bottomNavController, 
                startDestination = Screen.HomeHub.route, 
                Modifier.padding(padding)
            ) {
                composable(Screen.HomeHub.route) { 
                    // Pass drawer control to Home
                    HomeScreen(navController = rootNavController, openDrawer = { scope.launch { drawerState.open() } }) 
                }
                composable(Screen.LiveTV.route) { 
                    LiveTVScreen(navController = rootNavController) 
                }
                composable(Screen.Favorites.route) { 
                    // FavoritesScreen(rootNavController) // Implement properly
                    Text("Favorites Coming Soon", modifier = Modifier.padding(20.dp))
                }
            }
        }
    }
}

@Composable
fun AppBottomNavBar(navController: NavController) {
    NavigationBar(
        containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f),
        contentColor = MaterialTheme.colorScheme.primary
    ) {
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
                },
                colors = NavigationBarItemDefaults.colors(
                    indicatorColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f),
                    selectedIconColor = MaterialTheme.colorScheme.primary,
                    unselectedIconColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
            )
        }
    }
}
