package com.aeoncorex.streamx.ui.music

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.*
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import coil.compose.AsyncImage

// Theme Colors (MusicScreen এর সাথে মিল রেখে)
private val NeonPurple = Color(0xFFBC13FE)
private val NeonCyan = Color(0xFF04D9FF)
private val DeepDark = Color(0xFF05050A)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MusicPlayerScreen(navController: NavController) {
    // Collect states from MusicManager
    val currentSong by MusicManager.currentSong.collectAsState()
    val isPlaying by MusicManager.isPlaying.collectAsState()
    val position by MusicManager.currentPosition.collectAsState()
    val duration by MusicManager.duration.collectAsState()

    // গান না থাকলে ব্যাক করা
    if (currentSong == null) {
        LaunchedEffect(Unit) { navController.popBackStack() }
        return
    }

    currentSong?.let { track ->
        Box(modifier = Modifier.fillMaxSize().background(DeepDark)) {
            
            // 1. Dynamic Background Blur (অ্যালবাম আর্ট থেকে ব্লার ব্যাকগ্রাউন্ড)
            AsyncImage(
                model = track.coverUrl,
                contentDescription = null,
                modifier = Modifier
                    .fillMaxSize()
                    .blur(60.dp) // ব্যাকগ্রাউন্ডে ঝাপসা এফেক্ট
                    .alpha(0.35f),
                contentScale = ContentScale.Crop
            )

            // মেইন কন্টেন্ট লেআউট
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .systemBarsPadding()
                    .padding(horizontal = 24.dp)
            ) {
                // ২. টপ বার
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.Rounded.KeyboardArrowDown, null, tint = Color.White, modifier = Modifier.size(35.dp))
                    }
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            "NOW PLAYING",
                            color = NeonCyan,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 2.sp
                        )
                        Text(
                            track.albumName.ifEmpty { "Single" },
                            color = Color.White.copy(0.7f),
                            fontSize = 13.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    IconButton(onClick = { /* Options */ }) {
                        Icon(Icons.Rounded.MoreVert, null, tint = Color.White)
                    }
                }

                Spacer(modifier = Modifier.weight(0.5f))

                // ৩. রোটেটিং অ্যালবাম আর্ট (Futuristic Vinyl Style)
                val infiniteTransition = rememberInfiniteTransition(label = "rotation")
                val rotation by infiniteTransition.animateFloat(
                    initialValue = 0f,
                    targetValue = 360f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(12000, easing = LinearEasing),
                        repeatMode = RepeatMode.Restart
                    ),
                    label = "album_art_rotation"
                )

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(1f)
                        .padding(20.dp),
                    contentAlignment = Alignment.Center
                ) {
                    // নিয়ন গ্লো এফেক্ট (Outer Glow)
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(NeonPurple.copy(0.15f), CircleShape)
                            .blur(40.dp)
                    )
                    
                    // মেইন আর্ট সার্কেল
                    AsyncImage(
                        model = track.coverUrl,
                        contentDescription = null,
                        modifier = Modifier
                            .fillMaxSize()
                            .clip(CircleShape) // গোলাকার শেপ
                            .rotate(if (isPlaying) rotation else 0f) // গান চললে ঘুরবে
                            .border(4.dp, Brush.sweepGradient(listOf(NeonCyan, NeonPurple, NeonCyan)), CircleShape),
                        contentScale = ContentScale.Crop
                    )
                }

                Spacer(modifier = Modifier.weight(0.5f))

                // ৪. ট্র্যাক ইনফো (টাইটেল ও আর্টিস্ট)
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        track.title,
                        color = Color.White,
                        fontSize = 28.sp,
                        fontWeight = FontWeight.Black,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        track.artist,
                        color = NeonCyan,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1
                    )
                }

                Spacer(modifier = Modifier.height(32.dp))

                // ৫. প্রগ্রেস বার (Slider)
                Column {
                    Slider(
                        value = if (duration > 0) position.toFloat() / duration else 0f,
                        onValueChange = { MusicManager.seekTo((it * duration).toLong()) },
                        colors = SliderDefaults.colors(
                            thumbColor = Color.White,
                            activeTrackColor = NeonPurple,
                            inactiveTrackColor = Color.White.copy(0.15f)
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

                Spacer(modifier = Modifier.height(24.dp))

                // ৬. কন্ট্রোল বাটনসমূহ (Play, Pause, Next, Prev)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 32.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Rounded.Shuffle, null, tint = Color.Gray, modifier = Modifier.size(24.dp))
                    
                    IconButton(onClick = { MusicManager.playPrevious() }) {
                        Icon(Icons.Rounded.SkipPrevious, null, tint = Color.White, modifier = Modifier.size(45.dp))
                    }

                    // প্লে/পজ ফ্লোটিং বাটন
                    Surface(
                        modifier = Modifier
                            .size(75.dp)
                            .clickable { MusicManager.togglePlayPause() },
                        shape = CircleShape,
                        color = Color.White,
                        shadowElevation = 10.dp
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                                contentDescription = null,
                                tint = DeepDark,
                                modifier = Modifier.size(40.dp)
                            )
                        }
                    }

                    IconButton(onClick = { MusicManager.playNext() }) {
                        Icon(Icons.Rounded.SkipNext, null, tint = Color.White, modifier = Modifier.size(45.dp))
                    }

                    Icon(Icons.Rounded.Repeat, null, tint = Color.Gray, modifier = Modifier.size(24.dp))
                }
            }
        }
    }
}

// টাইম ফরম্যাটিং ফাংশন (00:00)
private fun formatTime(ms: Long): String {
    val totalSecs = ms / 1000
    val min = totalSecs / 60
    val sec = totalSecs % 60
    return String.format("%02d:%02d", min, sec)
}
