package com.aeoncorex.streamx.ui.music

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import coil.compose.AsyncImage

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MusicPlayerScreen(navController: NavController) {
    val currentSong by MusicManager.currentSong.collectAsState()
    val isPlaying by MusicManager.isPlaying.collectAsState()
    val position by MusicManager.currentPosition.collectAsState()
    val duration by MusicManager.duration.collectAsState()

    if (currentSong == null) {
        navController.popBackStack()
        return
    }

    currentSong?.let { track ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Brush.verticalGradient(colors = listOf(Color(0xFF4A148C).copy(0.4f), Color(0xFF121212))))
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp)
                    .systemBarsPadding()
            ) {
                // Top Bar
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.Rounded.KeyboardArrowDown, null, tint = Color.White, modifier = Modifier.size(32.dp))
                    }
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("NOW PLAYING", color = Color.White.copy(0.7f), fontSize = 10.sp, letterSpacing = 2.sp)
                        if(track.albumName.isNotEmpty()) {
                            Text(track.albumName, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                        }
                    }
                    IconButton(onClick = {}) { Icon(Icons.Rounded.MoreHoriz, null, tint = Color.White) }
                }

                Spacer(modifier = Modifier.weight(1f))

                // Album Art
                Card(
                    modifier = Modifier.fillMaxWidth().aspectRatio(1f).shadow(elevation = 24.dp, shape = RoundedCornerShape(24.dp)),
                    shape = RoundedCornerShape(24.dp)
                ) {
                    AsyncImage(model = track.coverUrl, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                }

                Spacer(modifier = Modifier.height(48.dp))

                // Track Info
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(track.title, color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(track.artist, color = Color.White.copy(0.7f), fontSize = 18.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    IconButton(onClick = {}) { Icon(Icons.Rounded.FavoriteBorder, null, tint = Color.White, modifier = Modifier.size(28.dp)) }
                }

                Spacer(modifier = Modifier.height(24.dp))

                // Slider
                Column {
                    Slider(
                        value = if (duration > 0) position.toFloat() / duration else 0f,
                        onValueChange = { MusicManager.seekTo((it * duration).toLong()) },
                        colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = Color.White, inactiveTrackColor = Color.White.copy(0.2f)),
                        thumb = { Box(modifier = Modifier.size(16.dp).background(Color.White, CircleShape)) }
                    )
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(formatTime(position), color = Color.White.copy(0.5f), fontSize = 12.sp)
                        Text(formatTime(duration), color = Color.White.copy(0.5f), fontSize = 12.sp)
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                // Controls
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Rounded.Shuffle, null, tint = Color.White, modifier = Modifier.size(28.dp))
                    IconButton(onClick = { MusicManager.playPrevious() }) { Icon(Icons.Rounded.SkipPrevious, null, tint = Color.White, modifier = Modifier.size(42.dp)) }
                    Box(
                        modifier = Modifier.size(72.dp).clip(CircleShape).background(Color.White).clickable { MusicManager.togglePlayPause() },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(imageVector = if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, contentDescription = null, tint = Color.Black, modifier = Modifier.size(36.dp))
                    }
                    IconButton(onClick = { MusicManager.playNext() }) { Icon(Icons.Rounded.SkipNext, null, tint = Color.White, modifier = Modifier.size(42.dp)) }
                    Icon(Icons.Rounded.Repeat, null, tint = Color.White, modifier = Modifier.size(28.dp))
                }
                Spacer(modifier = Modifier.weight(1f))
            }
        }
    }
}

private fun formatTime(ms: Long): String {
    val totalSecs = ms / 1000
    val min = totalSecs / 60
    val sec = totalSecs % 60
    return String.format("%02d:%02d", min, sec)
}
