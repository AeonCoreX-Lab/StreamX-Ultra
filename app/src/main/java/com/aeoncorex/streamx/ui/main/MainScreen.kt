package com.aeoncorex.streamx.ui.main

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import coil.compose.AsyncImage
import com.aeoncorex.streamx.ui.home.LiveTVScreen
import com.aeoncorex.streamx.ui.music.MusicScreen
import com.aeoncorex.streamx.ui.music.MusicManager

@Composable
fun MainScreen(navController: NavController) {
    var selectedTab by remember { mutableIntStateOf(0) } // 0 is now Live TV
    val primaryColor = MaterialTheme.colorScheme.primary
    val currentSong by MusicManager.currentSong.collectAsState()
    val isPlaying by MusicManager.isPlaying.collectAsState()

    Scaffold(
        bottomBar = {
            Column {
                // MINI PLAYER (Spotify Style)
                AnimatedVisibility(
                    visible = currentSong != null,
                    enter = slideInVertically { it } + fadeIn(),
                    exit = slideOutVertically { it } + fadeOut()
                ) {
                    currentSong?.let { song ->
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(8.dp)
                                .height(64.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .clickable {"music_player"},
                            color = Color(0xFF1E1E1E),
                            tonalElevation = 8.dp
                        ) {
                            Row(
                                modifier = Modifier.fillMaxSize().padding(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                AsyncImage(
                                    model = song.coverUrl,
                                    contentDescription = null,
                                    modifier = Modifier.size(48.dp).clip(RoundedCornerShape(8.dp)),
                                    contentScale = ContentScale.Crop
                                )
                                Column(
                                    modifier = Modifier
                                        .weight(1f)
                                        .padding(horizontal = 12.dp)
                                ) {
                                    Text(
                                        text = song.title,
                                        color = Color.White,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 14.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = song.artist,
                                        color = Color.Gray,
                                        fontSize = 12.sp,
                                        maxLines = 1
                                    )
                                }
                                IconButton(onClick = { MusicManager.togglePlayPause() }) {
                                    Icon(
                                        imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                        contentDescription = "Play/Pause",
                                        tint = Color.White
                                    )
                                }
                            }
                            // Progress Indicator at bottom of mini player
                            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.BottomStart) {
                                LinearProgressIndicator(
                                    progress = { 0.5f }, // TODO: Link to real progress
                                    modifier = Modifier.fillMaxWidth().height(2.dp),
                                    color = primaryColor,
                                    trackColor = Color.Transparent,
                                )
                            }
                        }
                    }
                }

                // NAVIGATION BAR
                NavigationBar(
                    containerColor = Color(0xFF0A0A0A),
                    contentColor = primaryColor,
                    tonalElevation = 10.dp
                ) {
                    NavigationBarItem(
                        selected = selectedTab == 0,
                        onClick = { selectedTab = 0 },
                        icon = { Icon(Icons.Default.Home, null) },
                        label = { Text("LIVE TV") }, // Changed Label
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
                        icon = { Icon(Icons.Default.MusicNote, null) },
                        label = { Text("MUSIC") },
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
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(bottom = paddingValues.calculateBottomPadding())
        ) {
            when (selectedTab) {
                0 -> LiveTVScreen(navController)
                1 -> MusicScreen(navController)
            }
        }
    }
}
