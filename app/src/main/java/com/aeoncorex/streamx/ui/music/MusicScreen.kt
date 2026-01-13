package com.aeoncorex.streamx.ui.music

import android.util.Log
import android.widget.Toast
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.*
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import androidx.navigation.NavController
import coil.compose.AsyncImage
import kotlinx.coroutines.launch
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.*

// --- Data Models ---
data class SaavnResponse(val success: Boolean?, val data: SaavnData?)
data class SaavnData(val songs: SaavnContent?, val results: List<SaavnSong>?)
data class SaavnContent(val results: List<SaavnSong>?)
data class SongDetailResponse(val success: Boolean?, val data: List<DetailedSong>?)
data class DetailedSong(val id: String?, val downloadUrl: List<UrlLink>?)
data class SaavnSong(val id: String?, val title: String?, val primaryArtists: String?, val image: List<UrlLink>?, val url: String?)
data class UrlLink(val link: String?, val url: String?, val quality: String?)
data class MusicTrack(val id: String, val title: String, val artist: String, val coverUrl: String, val streamUrl: String)

interface SaavnApi {
    @GET("api/search/songs")
    suspend fun searchSongs(@Query("query") q: String): SaavnResponse
    @GET("api/songs")
    suspend fun getSongDetails(@Query("id") id: String): SongDetailResponse
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MusicScreen(navController: NavController) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val primaryColor = MaterialTheme.colorScheme.primary
    
    // State
    var searchQuery by remember { mutableStateOf("Trending Bangla") }
    var musicList by remember { mutableStateOf<List<MusicTrack>>(emptyList()) }
    var isLoading by remember { mutableStateOf(false) }
    
    // Global Music State for MiniPlayer
    val currentSong by MusicManager.currentSong.collectAsState()
    val isPlaying by MusicManager.isPlaying.collectAsState()

    val api = remember {
        Retrofit.Builder()
            .baseUrl("https://jiosaavn-api-kappa-seven.vercel.app/")
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(SaavnApi::class.java)
    }

    fun searchMusic(q: String) {
        if (q.isBlank()) return
        scope.launch {
            isLoading = true
            try {
                val response = api.searchSongs(q)
                val rawList = response.data?.songs?.results ?: response.data?.results
                val tracks = rawList?.mapNotNull { song ->
                    val cover = song.image?.lastOrNull()?.url ?: song.image?.lastOrNull()?.link
                    if (song.id != null) {
                        MusicTrack(
                            id = song.id,
                            title = song.title?.replace("&amp;", "&") ?: "Unknown",
                            artist = song.primaryArtists ?: "Artist",
                            coverUrl = cover ?: "",
                            streamUrl = "" 
                        )
                    } else null
                } ?: emptyList()
                musicList = tracks
            } catch (e: Exception) {
                Log.e("MusicAPI", "Search Error: ${e.message}")
            }
            isLoading = false
        }
    }

    fun fetchAndPlay(track: MusicTrack) {
        Toast.makeText(context, "Playing: ${track.title}", Toast.LENGTH_SHORT).show()
        scope.launch {
            try {
                val detailResponse = api.getSongDetails(track.id)
                val songData = detailResponse.data?.firstOrNull()
                val mp3 = songData?.downloadUrl?.lastOrNull()?.url 
                          ?: songData?.downloadUrl?.lastOrNull()?.link
                          ?: songData?.downloadUrl?.firstOrNull()?.url

                if (!mp3.isNullOrBlank()) {
                    MusicManager.play(track.copy(streamUrl = mp3))
                    // Note: We don't navigate immediately here, we let the MiniPlayer appear
                } else {
                    Toast.makeText(context, "Premium content - Link not found", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Log.e("MusicAPI", "Error: ${e.message}")
            }
        }
    }

    LaunchedEffect(Unit) { searchMusic(searchQuery) }

    Box(modifier = Modifier.fillMaxSize().background(Color(0xFF121212))) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Header
            Text(
                "Search", 
                color = Color.White, 
                fontSize = 28.sp, 
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(top = 50.dp, start = 20.dp, bottom = 15.dp)
            )

            // Search Bar
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .background(Color.White, RoundedCornerShape(8.dp)),
                placeholder = { Text("What do you want to listen to?", color = Color.Black.copy(0.7f)) },
                shape = RoundedCornerShape(8.dp),
                leadingIcon = { Icon(Icons.Rounded.Search, null, tint = Color.Black) },
                trailingIcon = {
                    IconButton(onClick = { searchMusic(searchQuery) }) {
                        Icon(Icons.Rounded.Search, null, tint = Color.Black)
                    }
                },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = Color.Black,
                    unfocusedTextColor = Color.Black,
                    focusedBorderColor = Color.Transparent,
                    unfocusedBorderColor = Color.Transparent,
                    cursorColor = Color.Black
                ),
                singleLine = true
            )

            if (isLoading) {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth().padding(top = 10.dp), 
                    color = Color(0xFF1DB954) // Spotify Green
                )
            }

            // List
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                contentPadding = PaddingValues(16.dp, bottom = 120.dp), // Bottom padding for MiniPlayer
                verticalArrangement = Arrangement.spacedBy(16.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                items(musicList) { track ->
                    MusicTrackCard(track) { fetchAndPlay(track) }
                }
            }
        }

        // --- MINI PLAYER (Spotify Style) ---
        AnimatedVisibility(
            visible = currentSong != null,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 16.dp, start = 8.dp, end = 8.dp),
            enter = slideInVertically { it },
            exit = slideOutVertically { it }
        ) {
            currentSong?.let { track ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(64.dp)
                        .clickable { navController.navigate("music_player") },
                    shape = RoundedCornerShape(8.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF282828)) // Spotify Dark Grey
                ) {
                    Row(
                        modifier = Modifier.fillMaxSize().padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Thumbnail
                        AsyncImage(
                            model = track.coverUrl,
                            contentDescription = null,
                            modifier = Modifier.size(48.dp).clip(RoundedCornerShape(4.dp)),
                            contentScale = ContentScale.Crop
                        )
                        
                        Spacer(modifier = Modifier.width(12.dp))
                        
                        // Info
                        Column(modifier = Modifier.weight(1f)) {
                            Text(track.title, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                            Text(track.artist, color = Color.LightGray, fontSize = 12.sp, maxLines = 1)
                        }

                        // Play/Pause Button
                        IconButton(onClick = { MusicManager.togglePlayPause() }) {
                            Icon(
                                imageVector = if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                                contentDescription = null,
                                tint = Color.White
                            )
                        }
                    }
                    // Progress Indicator at bottom of MiniPlayer
                    val position by MusicManager.currentPosition.collectAsState()
                    val duration by MusicManager.duration.collectAsState()
                    val progress = if (duration > 0) position.toFloat() / duration else 0f
                    
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier.fillMaxWidth().height(2.dp).align(Alignment.BottomCenter),
                        color = Color.White,
                        trackColor = Color.Transparent,
                    )
                }
            }
        }
    }
}

@Composable
fun MusicTrackCard(track: MusicTrack, onClick: () -> Unit) {
    Column(
        modifier = Modifier.width(160.dp).clickable { onClick() }
    ) {
        Card(
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier.fillMaxWidth().aspectRatio(1f)
        ) {
            AsyncImage(
                model = track.coverUrl,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            track.title,
            color = Color.White,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            track.artist,
            color = Color.Gray,
            fontSize = 12.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}
