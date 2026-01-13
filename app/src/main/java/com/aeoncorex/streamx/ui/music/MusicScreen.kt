package com.aeoncorex.streamx.ui.music

import android.util.Log
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.*
import androidx.navigation.NavController
import coil.compose.AsyncImage
import kotlinx.coroutines.launch
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.*

// --- Data Models (Saavn API Compatible) ---
data class SaavnResponse(val success: Boolean?, val data: SaavnData?)
data class SaavnData(val results: List<SaavnSong>?)

// Song Details Response
data class SongDetailResponse(val success: Boolean?, val data: List<DetailedSong>?)
data class DetailedSong(val id: String?, val downloadUrl: List<UrlLink>?)

data class SaavnSong(
    val id: String?,
    val title: String?,
    val primaryArtists: String?,
    val image: List<UrlLink>?, 
    val url: String?
)

data class UrlLink(val link: String?, val url: String?, val quality: String?)

data class MusicTrack(
    val id: String,
    val title: String,
    val artist: String,
    val coverUrl: String,
    val streamUrl: String
)

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
    var searchQuery by remember { mutableStateOf("Trending Bangla") }
    var musicList by remember { mutableStateOf<List<MusicTrack>>(emptyList()) }
    var isLoading by remember { mutableStateOf(false) }

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
                val tracks = response.data?.results?.mapNotNull { song ->
                    // ইমেজের জন্য link অথবা url চেক করা হচ্ছে
                    val cover = song.image?.lastOrNull()?.link ?: song.image?.lastOrNull()?.url
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
                Toast.makeText(context, "Search Failed", Toast.LENGTH_SHORT).show()
            }
            isLoading = false
        }
    }

    fun fetchAndPlay(track: MusicTrack) {
        Toast.makeText(context, "Loading: ${track.title}...", Toast.LENGTH_SHORT).show()
        scope.launch {
            try {
                val detail = api.getSongDetails(track.id)
                // ডাউনলোড ইউআরএল খোঁজার লজিক আপডেট করা হয়েছে
                val songData = detail.data?.firstOrNull()
                // সবথেকে ভালো কোয়ালিটি (last) নেওয়ার চেষ্টা, না পেলে প্রথমটা (first)
                val mp3 = songData?.downloadUrl?.lastOrNull()?.link 
                          ?: songData?.downloadUrl?.firstOrNull()?.link
                          ?: songData?.downloadUrl?.lastOrNull()?.url // কিছু ক্ষেত্রে url ফিল্ড থাকে

                if (!mp3.isNullOrBlank()) {
                    MusicManager.play(track.copy(streamUrl = mp3))
                    // নেভিগেশন নিশ্চিত করা হচ্ছে
                    navController.navigate("music_player") {
                        launchSingleTop = true
                    }
                } else {
                    Toast.makeText(context, "Stream link not found!", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Log.e("MusicAPI", "MP3 Fetch Error: ${e.message}")
                Toast.makeText(context, "Error playing song", Toast.LENGTH_SHORT).show()
            }
        }
    }

    LaunchedEffect(Unit) { searchMusic(searchQuery) }

    Box(modifier = Modifier.fillMaxSize().background(Color(0xFF0A0A0A))) {
        Column(modifier = Modifier.fillMaxSize()) {
            Text(
                "STREAMX MUSIC", 
                color = Color.White, 
                fontSize = 22.sp, 
                fontWeight = FontWeight.ExtraBold,
                modifier = Modifier.padding(top = 45.dp, start = 20.dp, bottom = 10.dp)
            )

            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                placeholder = { Text("Search songs...", color = Color.Gray) },
                shape = RoundedCornerShape(14.dp),
                trailingIcon = {
                    IconButton(onClick = { searchMusic(searchQuery) }) {
                        Icon(Icons.Rounded.Search, null, tint = primaryColor)
                    }
                },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White,
                    focusedBorderColor = primaryColor,
                    cursorColor = primaryColor
                )
            )

            if (isLoading) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(top = 10.dp), color = primaryColor)
            }

            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                contentPadding = PaddingValues(16.dp, bottom = 100.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                items(musicList) { track ->
                    MusicTrackCard(track, primaryColor) { fetchAndPlay(track) }
                }
            }
        }
    }
}

@Composable
fun MusicTrackCard(track: MusicTrack, primaryColor: Color, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().aspectRatio(0.85f).clickable { onClick() },
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF121212))
    ) {
        Box {
            AsyncImage(
                model = track.coverUrl,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(0.8f)))))
            
            Column(Modifier.align(Alignment.BottomStart).padding(10.dp)) {
                Text(track.title, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                Text(track.artist, color = primaryColor, fontSize = 10.sp, maxLines = 1)
            }
            
            Icon(
                Icons.Default.PlayArrow, 
                null, 
                tint = Color.Black, 
                modifier = Modifier.align(Alignment.TopEnd).padding(8.dp).background(primaryColor, CircleShape).size(24.dp).padding(4.dp)
            )
        }
    }
}
