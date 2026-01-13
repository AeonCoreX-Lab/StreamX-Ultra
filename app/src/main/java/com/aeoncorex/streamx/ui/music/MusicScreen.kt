package com.aeoncorex.streamx.ui.music

import android.util.Log
import androidx.compose.animation.*
import androidx.compose.foundation.*
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.*
import androidx.navigation.NavController
import coil.compose.AsyncImage
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.*
import kotlinx.coroutines.launch

// --- ১. আপনার নতুন এন্ডপয়েন্ট অনুযায়ী ডাটা মডেল ---
data class SaavnResponse(val success: Boolean?, val data: SaavnData?)
data class SaavnData(val results: List<SaavnSong>?)

// আইডি দিয়ে আসল MP3 লিঙ্ক পাওয়ার জন্য মডেল
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

// --- ২. Retrofit ইন্টারফেস ---
interface SaavnApi {
    // আপনি যে এন্ডপয়েন্টটি ভার্সেলে পেয়েছেন
    @GET("api/search/songs")
    suspend fun searchSongs(@Query("query") q: String): SaavnResponse

    // আসল MP3 লিঙ্ক পাওয়ার জন্য এন্ডপয়েন্ট
    @GET("api/songs")
    suspend fun getSongDetails(@Query("id") id: String): SongDetailResponse
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MusicScreen(navController: NavController) {
    val scope = rememberCoroutineScope()
    val primaryColor = MaterialTheme.colorScheme.primary
    var searchQuery by remember { mutableStateOf("New Bangla Song") }
    var musicList by remember { mutableStateOf<List<MusicTrack>>(emptyList()) }
    var isLoading by remember { mutableStateOf(false) }

    val api = remember {
        Retrofit.Builder()
            .baseUrl("https://jiosaavn-api-kappa-seven.vercel.app/")
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(SaavnApi::class.java)
    }

    // গান সার্চ করার ফাংশন
    fun searchMusic(q: String) {
        if (q.isBlank()) return
        scope.launch {
            isLoading = true
            try {
                // সরাসরি songs এন্ডপয়েন্ট ব্যবহার
                val response = api.searchSongs(q)
                val tracks = response.data?.results?.mapNotNull { song ->
                    val cover = song.image?.lastOrNull()?.url ?: song.image?.lastOrNull()?.link
                    if (cover != null && song.id != null) {
                        MusicTrack(
                            id = song.id,
                            title = song.title?.replace("&amp;", "&") ?: "Unknown",
                            artist = song.primaryArtists ?: "Various Artists",
                            coverUrl = cover,
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

    // গান প্লে করার সময় MP3 লিঙ্ক নিয়ে আসা
    fun fetchAndPlay(track: MusicTrack) {
        scope.launch {
            try {
                val detail = api.getSongDetails(track.id)
                val mp3 = detail.data?.firstOrNull()?.downloadUrl?.lastOrNull()?.link
                if (mp3 != null) {
                    MusicManager.play(track.copy(streamUrl = mp3))
                    navController.navigate("music_player")
                }
            } catch (e: Exception) {
                Log.e("MusicAPI", "MP3 Fetch Error: ${e.message}")
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

            // Search Bar
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
                    focusedBorderColor = primaryColor
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
