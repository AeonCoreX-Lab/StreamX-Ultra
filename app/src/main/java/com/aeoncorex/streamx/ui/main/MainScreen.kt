package com.aeoncorex.streamx.ui.main

import androidx.compose.animation.*
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.rounded.Movie // Movie Icon
import androidx.compose.material.icons.rounded.Tv
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import coil.compose.AsyncImage
import com.aeoncorex.streamx.ui.account.AccountScreen
import com.aeoncorex.streamx.ui.home.LiveTVScreen
import com.aeoncorex.streamx.ui.movie.MovieScreen // New Import
import com.aeoncorex.streamx.ui.music.MusicManager
import com.aeoncorex.streamx.ui.music.MusicScreen

@Composable
fun MainScreen(navController: NavController) {
    var selectedTab by remember { mutableIntStateOf(0) }
    val primaryColor = MaterialTheme.colorScheme.primary
    val currentSong by MusicManager.currentSong.collectAsState()
    val isPlaying by MusicManager.isPlaying.collectAsState()

    Scaffold(
        containerColor = Color.Black,
        bottomBar = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color.Transparent)
            ) {
                // --- MINI PLAYER (Existing Logic) ---
                AnimatedVisibility(
                    visible = currentSong != null,
                    enter = slideInVertically { it } + fadeIn(),
                    exit = slideOutVertically { it } + fadeOut()
                ) {
                    currentSong?.let { song ->
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 8.dp)
                        ) {
                            Surface(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(60.dp)
                                    .clip(RoundedCornerShape(16.dp))
                                    .border(1.dp, primaryColor.copy(0.3f), RoundedCornerShape(16.dp))
                                    .clickable { navController.navigate("music_player") },
                                color = Color(0xFF1E1E1E).copy(0.9f),
                                tonalElevation = 8.dp
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxSize().padding(6.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    AsyncImage(
                                        model = song.coverUrl,
                                        contentDescription = null,
                                        modifier = Modifier.size(48.dp).clip(RoundedCornerShape(10.dp)),
                                        contentScale = ContentScale.Crop
                                    )
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = song.title,
                                            color = Color.White,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 13.sp,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            text = song.artist,
                                            color = primaryColor.copy(0.8f),
                                            fontSize = 11.sp,
                                            maxLines = 1
                                        )
                                    }
                                    IconButton(onClick = { MusicManager.togglePlayPause() }) {
                                        Icon(
                                            imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                            contentDescription = "Play/Pause",
                                            tint = primaryColor
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // --- NAVIGATION BAR (Updated with Movies) ---
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 24.dp, end = 24.dp, bottom = 24.dp, top = 8.dp)
                        .height(70.dp)
                        .shadow(20.dp, RoundedCornerShape(35.dp), spotColor = primaryColor.copy(0.5f))
                        .clip(RoundedCornerShape(35.dp))
                        .background(Color(0xFF0F0F0F).copy(alpha = 0.95f))
                        .border(
                            width = 1.dp,
                            brush = Brush.horizontalGradient(
                                listOf(primaryColor.copy(0.1f), primaryColor.copy(0.5f), primaryColor.copy(0.1f))
                            ),
                            shape = RoundedCornerShape(35.dp)
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Row(
                        modifier = Modifier.fillMaxSize(),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        FuturisticNavItem(Icons.Rounded.Tv, "LIVE TV", selectedTab == 0, primaryColor) { selectedTab = 0 }
                        
                        // NEW MOVIE TAB
                        FuturisticNavItem(Icons.Rounded.Movie, "MOVIES", selectedTab == 1, primaryColor) { selectedTab = 1 }
                        
                        FuturisticNavItem(Icons.Default.MusicNote, "MUSIC", selectedTab == 2, primaryColor) { selectedTab = 2 }
                        
                        FuturisticNavItem(Icons.Default.Person, "PROFILE", selectedTab == 3, primaryColor) { selectedTab = 3 }
                    }
                }
            }
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(bottom = paddingValues.calculateBottomPadding())
        ) {
            AnimatedContent(
                targetState = selectedTab,
                label = "TabAnimation"
            ) { targetIndex ->
                when (targetIndex) {
                    0 -> LiveTVScreen(navController)
                    1 -> MovieScreen(navController) // New Screen
                    2 -> MusicScreen(navController)
                    3 -> AccountScreen(navController)
                }
            }
        }
    }
}

@Composable
fun FuturisticNavItem(
    icon: ImageVector,
    label: String,
    isSelected: Boolean,
    primaryColor: Color,
    onClick: () -> Unit
) {
    val scale by animateFloatAsState(targetValue = if (isSelected) 1.2f else 1.0f, label = "scale")
    val glowAlpha by animateFloatAsState(targetValue = if (isSelected) 0.2f else 0f, label = "glow")

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = Modifier
            .clip(CircleShape)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) { onClick() }
            .padding(8.dp)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .background(
                        brush = Brush.radialGradient(
                            colors = listOf(primaryColor.copy(glowAlpha), Color.Transparent)
                        ),
                        shape = CircleShape
                    )
            )
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = if (isSelected) primaryColor else Color.Gray,
                modifier = Modifier.size(26.dp * scale)
            )
        }
        Spacer(modifier = Modifier.height(4.dp))
        if (isSelected) {
            Box(
                modifier = Modifier
                    .size(4.dp)
                    .background(primaryColor, CircleShape)
                    .shadow(4.dp, CircleShape, spotColor = primaryColor)
            )
        }
    }
}
