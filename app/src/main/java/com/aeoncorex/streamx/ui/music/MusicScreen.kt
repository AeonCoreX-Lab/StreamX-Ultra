package com.aeoncorex.streamx.ui.music

import android.widget.Toast
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import coil.compose.AsyncImage
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MusicScreen(navController: NavController) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    
    // UI State
    var searchQuery by remember { mutableStateOf("Arijit Singh") }
    var musicList by remember { mutableStateOf<List<MusicTrack>>(emptyList()) }
    var isLoading by remember { mutableStateOf(false) }
    
    // Player State
    val currentSong by MusicManager.currentSong.collectAsState()
    val isPlaying by MusicManager.isPlaying.collectAsState()

    // Initial Load
    LaunchedEffect(Unit) {
        isLoading = true
        musicList = MusicRepository.search(searchQuery)
        isLoading = false
    }

    fun playTrack(track: MusicTrack) {
        scope.launch {
            Toast.makeText(context, "Fetching: ${track.title}...", Toast.LENGTH_SHORT).show()
            val streamUrl = MusicRepository.getStreamUrl(track.id)
            if (streamUrl != null) {
                MusicManager.play(track.copy(streamUrl = streamUrl))
            } else {
                Toast.makeText(context, "Stream URL not found!", Toast.LENGTH_SHORT).show()
            }
        }
    }

    Scaffold(
        containerColor = Color(0xFF121212),
        bottomBar = {
            // --- SPOTIFY STYLE MINI PLAYER ---
            AnimatedVisibility(
                visible = currentSong != null,
                enter = slideInVertically { it },
                exit = slideOutVertically { it }
            ) {
                currentSong?.let { track ->
                    MiniPlayer(
                        track = track,
                        isPlaying = isPlaying,
                        onClick = { navController.navigate("music_player") },
                        onTogglePlay = { MusicManager.togglePlayPause() }
                    )
                }
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
        ) {
            // Header
            Text(
                "Find Your Vibe",
                color = Color.White,
                fontSize = 32.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(top = 40.dp, bottom = 20.dp)
            )

            // Search Field
            TextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp)),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.White,
                    unfocusedContainerColor = Color.White.copy(0.9f),
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    focusedTextColor = Color.Black,
                    unfocusedTextColor = Color.Black
                ),
                placeholder = { Text("Songs, Artists, Podcasts...", color = Color.Gray) },
                leadingIcon = { Icon(Icons.Rounded.Search, null, tint = Color.Black) },
                trailingIcon = {
                    IconButton(onClick = { 
                        scope.launch {
                            isLoading = true
                            musicList = MusicRepository.search(searchQuery)
                            isLoading = false
                        }
                    }) {
                        Icon(Icons.Rounded.ArrowForward, null, tint = Color.Black)
                    }
                },
                singleLine = true
            )

            Spacer(modifier = Modifier.height(20.dp))

            if (isLoading) {
                Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = Color(0xFF1DB954))
                }
            }

            // Song List
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(bottom = 100.dp)
            ) {
                items(musicList) { track ->
                    SongListItem(track) { playTrack(track) }
                }
            }
        }
    }
}

@Composable
fun SongListItem(track: MusicTrack, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Card(
            shape = RoundedCornerShape(4.dp),
            modifier = Modifier.size(56.dp)
        ) {
            AsyncImage(
                model = track.coverUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        }
        
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 16.dp)
        ) {
            Text(
                track.title,
                color = Color.White,
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                track.artist,
                color = Color.LightGray,
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        
        Icon(Icons.Rounded.MoreVert, null, tint = Color.Gray)
    }
}

@Composable
fun MiniPlayer(
    track: MusicTrack, 
    isPlaying: Boolean, 
    onClick: () -> Unit, 
    onTogglePlay: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Color(0xFF282828)), // Dark Grey
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(8.dp)
            .height(64.dp)
            .clickable { onClick() }
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier.fillMaxSize().padding(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                AsyncImage(
                    model = track.coverUrl,
                    contentDescription = null,
                    modifier = Modifier.size(48.dp).clip(RoundedCornerShape(4.dp)),
                    contentScale = ContentScale.Crop
                )
                
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 12.dp)
                ) {
                    Text(
                        track.title, 
                        color = Color.White, 
                        fontSize = 14.sp, 
                        fontWeight = FontWeight.Bold,
                        maxLines = 1
                    )
                    Text(
                        track.artist, 
                        color = Color(0xFFB3B3B3), 
                        fontSize = 12.sp,
                        maxLines = 1
                    )
                }

                IconButton(onClick = onTogglePlay) {
                    Icon(
                        imageVector = if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                        contentDescription = null,
                        tint = Color.White
                    )
                }
            }
            
            // Progress Bar at Bottom
            val position by MusicManager.currentPosition.collectAsState()
            val duration by MusicManager.duration.collectAsState()
            val progress = if (duration > 0) position.toFloat() / duration else 0f
            
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(2.dp)
                    .align(Alignment.BottomCenter),
                color = Color.White,
                trackColor = Color.Transparent,
            )
        }
    }
}
