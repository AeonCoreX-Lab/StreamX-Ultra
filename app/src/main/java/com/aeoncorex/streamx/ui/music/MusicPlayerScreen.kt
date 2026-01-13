package com.aeoncorex.streamx.ui.music

import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.*
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import androidx.navigation.NavController
import coil.compose.AsyncImage

@Composable
fun MusicPlayerScreen(navController: NavController) {
    val primaryColor = MaterialTheme.colorScheme.primary
    val currentSong by MusicManager.currentSong.collectAsState()
    val isPlaying by MusicManager.isPlaying.collectAsState()
    val position by MusicManager.currentPosition.collectAsState()
    val duration by MusicManager.duration.collectAsState()

    // যদি কোনো গান না থাকে তবে ব্যাকে চলে যাবে
    LaunchedEffect(currentSong) {
        if (currentSong == null) {
            navController.popBackStack()
        }
    }

    currentSong?.let { track ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Brush.verticalGradient(listOf(Color(0xFF1A1A2E), Color(0xFF16213E), Color(0xFF0F3460))))
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // --- TOP BAR ---
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.Rounded.KeyboardArrowDown, null, tint = Color.White, modifier = Modifier.size(32.dp))
                    }
                    Text(
                        "NOW PLAYING",
                        color = Color.White.copy(0.7f),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 2.sp
                    )
                    IconButton(onClick = { /* More options */ }) {
                        Icon(Icons.Default.MoreVert, null, tint = Color.White)
                    }
                }

                Spacer(modifier = Modifier.height(40.dp))

                // --- ALBUM ART ---
                Card(
                    modifier = Modifier
                        .fillMaxWidth(0.9f)
                        .aspectRatio(1f)
                        .shadow(30.dp, RoundedCornerShape(20.dp), ambientColor = primaryColor, spotColor = primaryColor),
                    shape = RoundedCornerShape(20.dp),
                    border = BorderStroke(1.dp, Color.White.copy(0.1f))
                ) {
                    AsyncImage(
                        model = track.coverUrl,
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                }

                Spacer(modifier = Modifier.height(40.dp))

                // --- INFO ---
                Column(horizontalAlignment = Alignment.Start, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        track.title,
                        color = Color.White,
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        track.artist,
                        color = primaryColor,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Medium
                    )
                }

                Spacer(modifier = Modifier.height(30.dp))

                // --- SEEK BAR ---
                Column {
                    Slider(
                        value = if (duration > 0) position.toFloat() / duration else 0f,
                        onValueChange = { MusicManager.seekTo((it * duration).toLong()) },
                        colors = SliderDefaults.colors(
                            thumbColor = primaryColor,
                            activeTrackColor = primaryColor,
                            inactiveTrackColor = Color.White.copy(0.2f)
                        )
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(formatTime(position), color = Color.Gray, fontSize = 12.sp)
                        Text(formatTime(duration), color = Color.Gray, fontSize = 12.sp)
                    }
                }

                Spacer(modifier = Modifier.height(30.dp))

                // --- CONTROLS ---
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = {}) {
                        Icon(Icons.Rounded.Shuffle, null, tint = Color.White.copy(0.4f))
                    }
                    IconButton(onClick = {}) {
                        Icon(Icons.Rounded.SkipPrevious, null, tint = Color.White, modifier = Modifier.size(40.dp))
                    }
                    
                    // Play/Pause Button
                    Box(
                        modifier = Modifier
                            .size(70.dp)
                            .clip(CircleShape)
                            .background(primaryColor)
                            .clickable { MusicManager.togglePlayPause() },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                            contentDescription = null,
                            tint = Color.Black,
                            modifier = Modifier.size(35.dp)
                        )
                    }

                    IconButton(onClick = {}) {
                        Icon(Icons.Rounded.SkipNext, null, tint = Color.White, modifier = Modifier.size(40.dp))
                    }
                    IconButton(onClick = {}) {
                        Icon(Icons.Rounded.Repeat, null, tint = Color.White.copy(0.4f))
                    }
                }

                Spacer(modifier = Modifier.weight(1f))

                // --- FOOTER ---
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .border(1.dp, primaryColor.copy(0.2f), RoundedCornerShape(4.dp))
                        .padding(horizontal = 12.dp, vertical = 4.dp)
                ) {
                    Icon(Icons.Rounded.SpeakerGroup, null, tint = primaryColor, modifier = Modifier.size(14.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        "STREAMX AUDIO ENGINE V1.2.1 - ACTIVE",
                        color = primaryColor,
                        fontSize = 9.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
        }
    }
}

// টাইম ফরম্যাট করার জন্য ফাংশন
private fun formatTime(ms: Long): String {
    if (ms < 0) return "00:00"
    val totalSeconds = ms / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return String.format("%02d:%02d", minutes, seconds)
}
