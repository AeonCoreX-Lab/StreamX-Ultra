package com.aeoncorex.streamx.ui.music

import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MusicPlayerScreen(navController: NavController) {
    // Collect states
    val currentSong by MusicManager.currentSong.collectAsState()
    val isPlaying by MusicManager.isPlaying.collectAsState()
    val position by MusicManager.currentPosition.collectAsState()
    val duration by MusicManager.duration.collectAsState()
    val lyrics by MusicManager.lyrics.collectAsState()

    // Pager for Tabs (Lyrics | Info)
    val pagerState = rememberPagerState(pageCount = { 2 })
    val coroutineScope = rememberCoroutineScope()
    val titles = listOf("LYRICS", "ARTIST INFO")

    if (currentSong == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = Color.Red)
        }
        return
    }

    Box(modifier = Modifier.fillMaxSize()) {
        // 1. Dynamic Blurred Background
        AsyncImage(
            model = currentSong!!.coverUrl,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxSize()
                .blur(80.dp)
                .alpha(0.6f)
        )
        // Dark Overlay for readability
        Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.5f)))

        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
        ) {
            // --- Top Bar ---
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
                Text("NOW PLAYING", color = Color.White.copy(0.7f), fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
                IconButton(onClick = { /* More Options */ }) {
                    Icon(Icons.Rounded.MoreVert, null, tint = Color.White)
                }
            }

            // --- Album Art (Rotating if playing) ---
            val infiniteTransition = rememberInfiniteTransition()
            val angle by infiniteTransition.animateFloat(
                initialValue = 0f,
                targetValue = 360f,
                animationSpec = infiniteRepeatable(
                    animation = tween(20000, easing = LinearEasing)
                )
            )
            
            Box(
                modifier = Modifier
                    .weight(1f) // Takes available upper space
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                Card(
                    modifier = Modifier
                        .size(300.dp)
                        .graphicsLayer { if(isPlaying) rotationZ = angle },
                    shape = CircleShape,
                    elevation = CardDefaults.cardElevation(20.dp)
                ) {
                    AsyncImage(
                        model = currentSong!!.coverUrl,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                }
                // Central hole for Vinyl look
                Box(Modifier.size(30.dp).background(Color(0xFF121212), CircleShape))
            }

            // --- Track Info ---
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = currentSong!!.title,
                    color = Color.White,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = currentSong!!.artist,
                    color = Color.Gray,
                    fontSize = 16.sp,
                    maxLines = 1
                )
            }

            Spacer(Modifier.height(24.dp))

            // --- Seek Bar ---
            Column(Modifier.padding(horizontal = 24.dp)) {
                Slider(
                    value = if (duration > 0) position.toFloat() / duration else 0f,
                    onValueChange = { MusicManager.seekTo((it * duration).toLong()) },
                    colors = SliderDefaults.colors(
                        thumbColor = Color.White,
                        activeTrackColor = Color.Red,
                        inactiveTrackColor = Color.White.copy(0.3f)
                    )
                )
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(formatTime(position), color = Color.Gray, fontSize = 12.sp)
                    Text(formatTime(duration), color = Color.Gray, fontSize = 12.sp)
                }
            }

            // --- Playback Controls ---
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 16.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = { MusicManager.playPrevious() }) {
                    Icon(Icons.Rounded.SkipPrevious, null, tint = Color.White, modifier = Modifier.size(40.dp))
                }
                
                // Play Button
                Box(
                    modifier = Modifier
                        .size(70.dp)
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

                IconButton(onClick = { MusicManager.playNext() }) {
                    Icon(Icons.Rounded.SkipNext, null, tint = Color.White, modifier = Modifier.size(40.dp))
                }
            }

            Spacer(Modifier.height(10.dp))

            // --- NEW: Professional Lyrics & Info Section ---
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(0.8f) // Takes remaining space at bottom
                    .background(Color.Black.copy(0.4f), RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
            ) {
                // Tab Header
                TabRow(
                    selectedTabIndex = pagerState.currentPage,
                    containerColor = Color.Transparent,
                    contentColor = Color.White,
                    indicator = { tabPositions ->
                        TabRowDefaults.SecondaryIndicator(
                            Modifier.tabIndicatorOffset(tabPositions[pagerState.currentPage]),
                            color = Color.Red
                        )
                    }
                ) {
                    titles.forEachIndexed { index, title ->
                        Tab(
                            selected = pagerState.currentPage == index,
                            onClick = { /* Switch via pager state if needed, usually auto with Pager */ },
                            text = { Text(title, fontSize = 12.sp, fontWeight = FontWeight.Bold) }
                        )
                    }
                }

                // Tab Content
                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier.fillMaxSize()
                ) { page ->
                    if (page == 0) {
                        // --- LYRICS SECTION ---
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(20.dp)
                                .verticalScroll(rememberScrollState())
                        ) {
                            Text(
                                text = lyrics,
                                color = Color.White.copy(0.9f),
                                fontSize = 18.sp,
                                lineHeight = 28.sp,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    } else {
                        // --- ARTIST INFO SECTION ---
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(20.dp)
                                .verticalScroll(rememberScrollState()),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            // Artist Image (Placeholder using song art or placeholder)
                            Card(shape = CircleShape, modifier = Modifier.size(100.dp)) {
                                AsyncImage(
                                    model = currentSong!!.coverUrl, // Using song art as artist placeholder
                                    contentDescription = null,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize()
                                )
                            }
                            Spacer(Modifier.height(16.dp))
                            Text(currentSong!!.artist, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.height(8.dp))
                            Text("Artist verified", color = Color.Green, fontSize = 12.sp)
                            
                            Spacer(Modifier.height(16.dp))
                            Text(
                                "About the Artist",
                                color = Color.Gray,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.align(Alignment.Start)
                            )
                            Spacer(Modifier.height(8.dp))
                            Text(
                                "Listen to ${currentSong!!.artist}, one of the top trending artists on StreamX. Enjoy the best quality audio and latest tracks right here.",
                                color = Color.White.copy(0.7f),
                                fontSize = 14.sp,
                                lineHeight = 20.sp,
                                textAlign = TextAlign.Justify
                            )
                        }
                    }
                }
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
