
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
import androidx.compose.ui.graphics.drawscope.Stroke
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

    // যদি কোনো গান সিলেক্ট করা না থাকে, তবে স্ক্রিনটি বন্ধ হয়ে যাবে
    LaunchedEffect(currentSong) {
        if (currentSong == null) {
            navController.popBackStack()
        }
    }

    currentSong?.let { song ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black) // Live TV এর মতো ডার্ক ব্যাকগ্রাউন্ড
        ) {
            // Futuristic Glow Background
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.radialGradient(
                            colors = listOf(primaryColor.copy(0.12f), Color.Transparent),
                            radius = 1500f
                        )
                    )
            )

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp)
                    .systemBarsPadding(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // --- TOP ACTION BAR ---
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(
                            Icons.Rounded.KeyboardArrowDown,
                            contentDescription = "Minimize",
                            tint = Color.White,
                            modifier = Modifier.size(32.dp)
                        )
                    }
                    
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            "SYSTEM PLAYER",
                            color = primaryColor,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                            letterSpacing = 2.sp
                        )
                        Text(
                            "DECODING AUDIO...",
                            color = Color.White.copy(0.4f),
                            fontSize = 8.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }

                    IconButton(onClick = { }) {
                        Icon(Icons.Default.MoreVert, "Options", tint = Color.White)
                    }
                }

                Spacer(modifier = Modifier.weight(0.15f))

                // --- CIRCULAR NEON ALBUM ART ---
                Box(contentAlignment = Alignment.Center, modifier = Modifier.size(300.dp)) {
                    // Outer Neon Rings
                    Canvas(modifier = Modifier.size(300.dp)) {
                        drawCircle(
                            color = primaryColor,
                            style = Stroke(width = 1.dp),
                            alpha = 0.2f
                        )
                    }
                    Canvas(modifier = Modifier.size(280.dp)) {
                        drawCircle(
                            color = primaryColor,
                            style = Stroke(
                                width = 4.dp, 
                                pathEffect = PathEffect.dashPathEffect(floatArrayOf(15f, 15f))
                            ),
                            alpha = 0.1f
                        )
                    }

                    // Main Artwork
                    Box(
                        modifier = Modifier
                            .size(240.dp)
                            .clip(CircleShape)
                            .border(2.dp, primaryColor.copy(0.5f), CircleShape)
                    ) {
                        AsyncImage(
                            model = song.coverUrl,
                            contentDescription = null,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop
                        )
                    }
                }

                Spacer(modifier = Modifier.weight(0.15f))

                // --- SONG INFO (Futuristic Style) ---
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.Start
                ) {
                    Text(
                        text = song.title.uppercase(),
                        fontSize = 24.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        fontFamily = FontFamily.Monospace
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = song.artist.uppercase(),
                        fontSize = 14.sp,
                        color = primaryColor,
                        letterSpacing = 1.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        fontWeight = FontWeight.Bold
                    )
                }

                Spacer(modifier = Modifier.height(32.dp))

                // --- NEON PROGRESS SLIDER ---
                Slider(
                    value = if (duration > 0) position.toFloat() / duration.toFloat() else 0f,
                    onValueChange = { progress ->
                        MusicManager.seekTo((progress * duration).toLong())
                    },
                    colors = SliderDefaults.colors(
                        thumbColor = Color.White,
                        activeTrackColor = primaryColor,
                        inactiveTrackColor = Color.White.copy(0.1f)
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
                
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        formatTime(position),
                        color = Color.White.copy(0.5f),
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace
                    )
                    Text(
                        formatTime(duration),
                        color = Color.White.copy(0.5f),
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }

                Spacer(modifier = Modifier.height(32.dp))

                // --- CYBER CONTROLS ---
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = {}) { 
                        Icon(Icons.Rounded.Shuffle, null, tint = Color.White.copy(0.4f)) 
                    }
                    
                    IconButton(onClick = {}, modifier = Modifier.size(56.dp)) { 
                        Icon(Icons.Rounded.SkipPrevious, null, tint = Color.White, modifier = Modifier.size(40.dp)) 
                    }
                    
                    // Main Play/Pause Button with Neon Border
                    Box(
                        modifier = Modifier
                            .size(80.dp)
                            .border(2.dp, primaryColor, CircleShape)
                            .background(primaryColor.copy(0.1f), CircleShape)
                            .clip(CircleShape)
                            .clickable { MusicManager.togglePlayPause() },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                            contentDescription = null,
                            tint = primaryColor,
                            modifier = Modifier.size(42.dp)
                        )
                    }

                    IconButton(onClick = {}, modifier = Modifier.size(56.dp)) { 
                        Icon(Icons.Rounded.SkipNext, null, tint = Color.White, modifier = Modifier.size(40.dp)) 
                    }

                    IconButton(onClick = {}) { 
                        Icon(Icons.Rounded.Repeat, null, tint = Color.White.copy(0.4f)) 
                    }
                }

                Spacer(modifier = Modifier.weight(0.1f))
                
                // --- STATUS FOOTER (Live TV style) ---
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .border(1.dp, primaryColor.copy(0.2f), RoundedCornerShape(4.dp))
                        .padding(horizontal = 12.dp, vertical = 4.dp)
                ) {
                    Icon(
                        Icons.Rounded.SpeakerGroup, 
                        null, 
                        tint = primaryColor, 
                        modifier = Modifier.size(14.dp)
                    )
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

// মিলিসেকেন্ড থেকে মিনিট:সেকেন্ড বানানোর জন্য হেল্পার ফাংশন
private fun formatTime(ms: Long): String {
    if (ms < 0) return "00:00"
    val totalSeconds = ms / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return String.format("%02d:%02d", minutes, seconds)
}
