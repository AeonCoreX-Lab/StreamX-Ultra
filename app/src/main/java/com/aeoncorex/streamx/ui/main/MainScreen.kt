package com.aeoncorex.streamx.ui.main

import androidx.compose.animation.*
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.rounded.Movie
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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import coil.compose.AsyncImage
import com.aeoncorex.streamx.ui.music.MusicManager
import com.aeoncorex.streamx.ui.navigation.SetupNavGraph
import kotlin.math.roundToInt

@Composable
fun MainScreen() {
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route
    val currentSong by MusicManager.currentSong.collectAsState()

    Scaffold(
        bottomBar = {
            if (currentRoute != "music_player") {
                Column {
                    // --- GLOBAL MINI PLAYER (Spotify Style) ---
                    AnimatedVisibility(
                        visible = currentSong != null,
                        enter = slideInVertically { it } + fadeIn(),
                        exit = slideOutVertically { it } + fadeOut()
                    ) {
                        currentSong?.let { song ->
                            SwipeableMiniPlayer(
                                songTitle = song.title,
                                artist = song.artist,
                                coverUrl = song.coverUrl,
                                isPlaying = MusicManager.isPlaying.collectAsState().value,
                                onPlayPause = { MusicManager.togglePlayPause() },
                                onClick = { navController.navigate("music_player") },
                                onDismiss = { MusicManager.stopPlayer() }
                            )
                        }
                    }

                    // --- BOTTOM NAVIGATION BAR ---
                    StreamXBottomNavBar(navController = navController, currentRoute = currentRoute)
                }
            }
        },
        content = { padding ->
            Box(modifier = Modifier.padding(padding)) {
                SetupNavGraph(navController = navController)
            }
        }
    )
}

@Composable
fun SwipeableMiniPlayer(
    songTitle: String,
    artist: String,
    coverUrl: String,
    isPlaying: Boolean,
    onPlayPause: () -> Unit,
    onClick: () -> Unit,
    onDismiss: () -> Unit
) {
    // Swipe Logic
    var offsetX by remember { mutableFloatStateOf(0f) }
    val configuration = LocalConfiguration.current
    val screenWidth = configuration.screenWidthDp.dp
    val swipeThreshold = 150f

    val draggableState = rememberDraggableState { delta ->
        offsetX += delta
    }

    LaunchedEffect(offsetX) {
        // If swiped enough, dismiss
        if (androidx.compose.ui.unit.dp.times(offsetX).value > swipeThreshold || 
            androidx.compose.ui.unit.dp.times(offsetX).value < -swipeThreshold) {
            onDismiss()
        }
    }
    
    // Reset offset if released but not dismissed (snap back)
    LaunchedEffect(key1 = isPlaying) { // Triggers reset if state changes or could handle "onDragStopped" logic with higher level api
        if (offsetX != 0f && (offsetX < swipeThreshold && offsetX > -swipeThreshold)) {
            offsetX = 0f
        }
    }

    Box(
        modifier = Modifier
            .offset(x = offsetX.dp)
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp)
            .shadow(10.dp, RoundedCornerShape(12.dp))
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xFF1E1E1E)) // Spotify-like dark grey
            .clickable { onClick() }
            .draggable(
                orientation = Orientation.Horizontal,
                state = draggableState,
                onDragStopped = { 
                     if (kotlin.math.abs(offsetX) > swipeThreshold) {
                         onDismiss()
                     } else {
                         offsetX = 0f // Snap back
                     }
                }
            )
    ) {
        Row(
            modifier = Modifier
                .padding(8.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Album Art
            AsyncImage(
                model = coverUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color.DarkGray)
            )

            Spacer(modifier = Modifier.width(12.dp))

            // Text Info
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = songTitle,
                    color = Color.White,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = artist,
                    color = Color.Gray,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            // Controls
            IconButton(onClick = onPlayPause) {
                Icon(
                    imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = "Play/Pause",
                    tint = Color.White,
                    modifier = Modifier.size(32.dp)
                )
            }
        }
        
         Progress Indicator (Optional thin line at bottom)
         val progress by MusicManager.currentPosition.collectAsState()
         val duration by MusicManager.duration.collectAsState()
         if(duration > 0) {
            LinearProgressIndicator(
                progress = { (progress.toFloat() / duration.toFloat()) },
                modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(2.dp),
                color = MaterialTheme.colorScheme.primary,
               trackColor = Color.Transparent
            )
         }
    }
}

@Composable
fun StreamXBottomNavBar(navController: NavController, currentRoute: String?) {
    val items = listOf(
        Triple("home", Icons.Rounded.Tv, "Home"),
        Triple("movies", Icons.Rounded.Movie, "Movies"),
        Triple("music", Icons.Filled.MusicNote, "Music"),
        Triple("profile", Icons.Filled.Person, "Profile")
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.Black) // Pure black for immersive look
            .padding(vertical = 12.dp, horizontal = 24.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        items.forEach { (route, icon, label) ->
            val isSelected = currentRoute == route
            BottomNavItem(
                icon = icon,
                label = label,
                isSelected = isSelected,
                primaryColor = MaterialTheme.colorScheme.primary,
                onClick = {
                    if (currentRoute != route) {
                        navController.navigate(route) {
                            popUpTo(navController.graph.startDestinationId) { saveState = true }
                            launchSingleTop = true
                            restoreState = true
                        }
                    }
                }
            )
        }
    }
}

@Composable
fun BottomNavItem(
    icon: ImageVector,
    label: String,
    isSelected: Boolean,
    primaryColor: Color,
    onClick: () -> Unit
) {
    val scale by animateFloatAsState(targetValue = if (isSelected) 1.2f else 1.0f, label = "scale")
    
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.clickable(
            interactionSource = remember { MutableInteractionSource() },
            indication = null
        ) { onClick() }
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = if (isSelected) primaryColor else Color.Gray,
            modifier = Modifier.size(26.dp).scale(scale)
        )
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
