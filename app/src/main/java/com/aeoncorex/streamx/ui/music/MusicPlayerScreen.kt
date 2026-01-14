package com.aeoncorex.streamx.ui.music

import androidx.compose.animation.core.*
import androidx.compose.foundation.*
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
import androidx.compose.ui.graphics.*
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import coil.compose.AsyncImage

@Composable
fun MusicPlayerScreen(navController: NavController) {
    // Collect states
    val currentSong by MusicManager.currentSong.collectAsState()
    val isPlaying by MusicManager.isPlaying.collectAsState()
    val position by MusicManager.currentPosition.collectAsState()
    val duration by MusicManager.duration.collectAsState()
    val lyrics by MusicManager.lyrics.collectAsState()

    val scrollState = rememberScrollState()

    if (currentSong == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = Color.White)
        }
        return
    }

    Box(modifier = Modifier.fillMaxSize().background(Color(0xFF121212))) {
        // 1. Dynamic Blurred Background (Full Screen)
        AsyncImage(
            model = currentSong!!.coverUrl,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxSize()
                .blur(60.dp)
                .alpha(0.5f)
        )
        // Dark Overlay for better contrast
        Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f)))

        // 2. Scrollable Content (Player + Lyrics + Artist)
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .verticalScroll(scrollState)
        ) {
            // --- TOP HEADER ---
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = { navController.popBackStack() }) {
                    Icon(Icons.Rounded.KeyboardArrowDown, null, tint = Color.White, modifier = Modifier.size(32.dp))
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        "PLAYING FROM PLAYLIST",
                        color = Color.White.copy(0.7f),
                        fontSize = 10.sp,
                        letterSpacing = 1.sp
                    )
                    Text(
                        currentSong!!.source, // "Saavn" or "YouTube"
                        color = Color.White,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
                IconButton(onClick = { /* More Options */ }) {
                    Icon(Icons.Rounded.MoreVert, null, tint = Color.White)
                }
            }

            Spacer(Modifier.height(20.dp))

            // --- ALBUM ART ---
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 40.dp)
                    .aspectRatio(1f), // Keeps it square
                contentAlignment = Alignment.Center
            ) {
                Card(
                    shape = RoundedCornerShape(12.dp),
                    elevation = CardDefaults.cardElevation(10.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    AsyncImage(
                        model = currentSong!!.coverUrl,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }

            Spacer(Modifier.height(40.dp))

            // --- TRACK TITLE & ARTIST ---
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = currentSong!!.title,
                            color = Color.White,
                            fontSize = 22.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1
                        )
                        Text(
                            text = currentSong!!.artist,
                            color = Color.White.copy(0.7f),
                            fontSize = 16.sp,
                            maxLines = 1
                        )
                    }
                    IconButton(onClick = { /* Like Action */ }) {
                        Icon(Icons.Rounded.FavoriteBorder, null, tint = Color.White)
                    }
                }
            }

            Spacer(Modifier.height(20.dp))

            // --- SEEK BAR ---
            Column(Modifier.padding(horizontal = 12.dp)) {
                Slider(
                    value = if (duration > 0) position.toFloat() / duration else 0f,
                    onValueChange = { MusicManager.seekTo((it * duration).toLong()) },
                    colors = SliderDefaults.colors(
                        thumbColor = Color.White,
                        activeTrackColor = Color.White,
                        inactiveTrackColor = Color.White.copy(0.2f)
                    ),
                    modifier = Modifier.height(20.dp)
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(formatTime(position), color = Color.White.copy(0.6f), fontSize = 11.sp)
                    Text(formatTime(duration), color = Color.White.copy(0.6f), fontSize = 11.sp)
                }
            }

            Spacer(Modifier.height(10.dp))

            // --- PLAYBACK CONTROLS ---
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = { /* Shuffle */ }) {
                    Icon(Icons.Rounded.Shuffle, null, tint = Color.White.copy(0.7f))
                }
                IconButton(onClick = { MusicManager.playPrevious() }, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.Rounded.SkipPrevious, null, tint = Color.White, modifier = Modifier.size(36.dp))
                }
                
                // Play/Pause
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

                IconButton(onClick = { MusicManager.playNext() }, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.Rounded.SkipNext, null, tint = Color.White, modifier = Modifier.size(36.dp))
                }
                IconButton(onClick = { /* Repeat */ }) {
                    Icon(Icons.Rounded.Repeat, null, tint = Color.White.copy(0.7f))
                }
            }

            Spacer(Modifier.height(30.dp))

            // ==========================================
            // --- SCROLL DOWN SECTIONS (SPOTIFY STYLE) ---
            // ==========================================

            // 1. LYRICS CARD
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color(0xFFBB5645)) // Spotify-like lyrical color (dynamic preferred, fixed for now)
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.Top
                    ) {
                        Text(
                            "Lyrics",
                            color = Color.White,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(20.dp))
                                .background(Color.Black.copy(0.2f))
                                .padding(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            Text("SHOW MORE", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                    
                    Spacer(Modifier.height(16.dp))
                    
                    // Lyrics Text
                    Text(
                        text = lyrics,
                        color = Color.White,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.SemiBold,
                        lineHeight = 30.sp,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            Spacer(Modifier.height(24.dp))

            // 2. ARTIST INFO SECTION
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color(0xFF2A2A2A)) // Dark grey card background
                    .padding(16.dp)
            ) {
                Text(
                    "About the artist",
                    color = Color.White,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(bottom = 16.dp)
                )

                // Artist Image
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(200.dp)
                        .clip(RoundedCornerShape(12.dp))
                ) {
                    AsyncImage(
                        model = currentSong!!.coverUrl, // Using song art as placeholder for artist
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                    // Artist Name Overlay
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black))),
                        contentAlignment = Alignment.BottomStart
                    ) {
                        Text(
                            text = currentSong!!.artist,
                            color = Color.White,
                            fontSize = 24.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(16.dp)
                        )
                    }
                }

                Spacer(Modifier.height(16.dp))

                // Artist Stats / Bio
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "10,543,201", // Placeholder number
                        color = Color.White,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "monthly listeners",
                        color = Color.Gray,
                        fontSize = 14.sp
                    )
                }
                
                Spacer(Modifier.height(12.dp))
                
                Text(
                    "Listen to ${currentSong!!.artist}, one of the top trending artists. Verified artist on StreamX.",
                    color = Color.White.copy(0.8f),
                    fontSize = 14.sp,
                    lineHeight = 20.sp,
                    maxLines = 4
                )
            }

            Spacer(Modifier.height(40.dp)) // Bottom padding for scrolling
        }
    }
}

private fun formatTime(ms: Long): String {
    val totalSecs = ms / 1000
    val min = totalSecs / 60
    val sec = totalSecs % 60
    return String.format("%02d:%02d", min, sec)
}
