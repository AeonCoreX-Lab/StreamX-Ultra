package com.aeoncorex.streamx.ui.music

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.*
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import androidx.navigation.NavController
import coil.compose.AsyncImage

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MusicPlayerScreen(navController: NavController) {
    val currentSong by MusicManager.currentSong.collectAsState()
    val isPlaying by MusicManager.isPlaying.collectAsState()
    val position by MusicManager.currentPosition.collectAsState()
    val duration by MusicManager.duration.collectAsState()

    LaunchedEffect(currentSong) {
        if (currentSong == null) navController.popBackStack()
    }

    currentSong?.let { track ->
        // Spotify-like Gradient Background
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color(0xFF4C4F69), // Muted dark color derived from art (static for now)
                            Color(0xFF121212),
                            Color(0xFF000000)
                        )
                    )
                )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 24.dp)
                    .systemBarsPadding(), // Ensures it respects status bar
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // --- TOP BAR ---
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.Default.KeyboardArrowDown, null, tint = Color.White, modifier = Modifier.size(32.dp))
                    }
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            "PLAYING FROM SEARCH",
                            color = Color.White.copy(0.7f),
                            fontSize = 10.sp,
                            letterSpacing = 1.sp
                        )
                    }
                    IconButton(onClick = { }) {
                        Icon(Icons.Default.MoreVert, null, tint = Color.White)
                    }
                }

                Spacer(modifier = Modifier.weight(0.1f))

                // --- ALBUM ART ---
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(1f)
                        .shadow(20.dp, RoundedCornerShape(12.dp)),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    AsyncImage(
                        model = track.coverUrl,
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                }

                Spacer(modifier = Modifier.height(40.dp))

                // --- TITLE & ARTIST ---
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            track.title,
                            color = Color.White,
                            fontSize = 22.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            track.artist,
                            color = Color.White.copy(0.7f),
                            fontSize = 16.sp,
                            maxLines = 1
                        )
                    }
                    IconButton(onClick = { /* Like Logic */ }) {
                        Icon(Icons.Rounded.FavoriteBorder, null, tint = Color.White, modifier = Modifier.size(28.dp))
                    }
                }

                Spacer(modifier = Modifier.height(20.dp))

                // --- PROGRESS BAR ---
                Column {
                    Slider(
                        value = if (duration > 0) position.toFloat() / duration else 0f,
                        onValueChange = { MusicManager.seekTo((it * duration).toLong()) },
                        colors = SliderDefaults.colors(
                            thumbColor = Color.White,
                            activeTrackColor = Color.White,
                            inactiveTrackColor = Color.White.copy(0.2f)
                        ),
                        thumb = {
                            Box(modifier = Modifier.size(12.dp).background(Color.White, CircleShape))
                        },
                        modifier = Modifier.height(10.dp)
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(formatTime(position), color = Color.White.copy(0.6f), fontSize = 12.sp)
                        Text(formatTime(duration), color = Color.White.copy(0.6f), fontSize = 12.sp)
                    }
                }

                Spacer(modifier = Modifier.height(20.dp))

                // --- CONTROLS ---
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = {}) {
                        Icon(Icons.Rounded.Shuffle, null, tint = Color(0xFF1DB954), modifier = Modifier.size(24.dp))
                    }
                    IconButton(onClick = {}) {
                        Icon(Icons.Rounded.SkipPrevious, null, tint = Color.White, modifier = Modifier.size(40.dp))
                    }

                    // Play/Pause Big Button
                    Box(
                        modifier = Modifier
                            .size(72.dp)
                            .clip(CircleShape)
                            .background(Color.White)
                            .clickable { MusicManager.togglePlayPause() },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                            contentDescription = null,
                            tint = Color.Black,
                            modifier = Modifier.size(36.dp)
                        )
                    }

                    IconButton(onClick = {}) {
                        Icon(Icons.Rounded.SkipNext, null, tint = Color.White, modifier = Modifier.size(40.dp))
                    }
                    IconButton(onClick = {}) {
                        Icon(Icons.Rounded.Repeat, null, tint = Color.White.copy(0.4f), modifier = Modifier.size(24.dp))
                    }
                }

                Spacer(modifier = Modifier.weight(0.2f))

                // --- CONNECT DEVICES (Icon only) ---
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                   IconButton(onClick = {}) {
                       Icon(Icons.Rounded.Devices, null, tint = Color.White.copy(0.7f))
                   }
                }
                Spacer(modifier = Modifier.height(20.dp))
            }
        }
    }
}

private fun formatTime(ms: Long): String {
    if (ms < 0) return "00:00"
    val totalSeconds = ms / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return String.format("%02d:%02d", minutes, seconds)
}
