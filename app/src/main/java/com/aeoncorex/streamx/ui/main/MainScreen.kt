package com.aeoncorex.streamx.ui.main

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.aeoncorex.streamx.ui.home.LiveTVScreen
import com.aeoncorex.streamx.ui.movie.MovieHomeScreen

@Composable
fun MainScreen(navController: NavController) {
    var selectedTab by remember { mutableIntStateOf(0) }
    val primaryColor = MaterialTheme.colorScheme.primary

    Scaffold(
        bottomBar = {
            // Futuristic Bottom Nav
            NavigationBar(
                containerColor = Color(0xFF0A0A0A), // Very dark background
                contentColor = primaryColor,
                tonalElevation = 10.dp
            ) {
                NavigationBarItem(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    icon = { Icon(Icons.Default.Home, null) },
                    label = { Text("MOVIES") },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = primaryColor,
                        selectedTextColor = primaryColor,
                        unselectedIconColor = Color.Gray,
                        unselectedTextColor = Color.Gray,
                        indicatorColor = primaryColor.copy(0.15f)
                    )
                )
                
                NavigationBarItem(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    icon = { Icon(Icons.Default.LiveTv, null) },
                    label = { Text("LIVE TV") },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = primaryColor,
                        selectedTextColor = primaryColor,
                        unselectedIconColor = Color.Gray,
                        unselectedTextColor = Color.Gray,
                        indicatorColor = primaryColor.copy(0.15f)
                    )
                )
            }
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(bottom = paddingValues.calculateBottomPadding()) // Only pad bottom
        ) {
            when (selectedTab) {
                0 -> MovieHomeScreen(navController)
                1 -> LiveTVScreen(navController) // This is your existing Live TV screen
            }
        }
    }
}