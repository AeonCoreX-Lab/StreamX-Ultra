package com.aeoncorex.streamx.ui.music

import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.lazy.grid.* // FIX: Added missing import for Grid
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
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

// API Models
data class SaavnResponse(val data: SaavnData?)
data class SaavnData(val results: List<SaavnSong>)
data class SaavnSong(val id: String, val name: String, val primaryArtists: String, val image: List<UrlLink>, val downloadUrl: List<UrlLink>)
data class UrlLink(val link: String)
data class MusicTrack(val id: String, val title: String, val artist: String, val coverUrl: String, val streamUrl: String)

interface SaavnApi {
    @GET("search/songs")
    suspend fun search(@Query("query") q: String): SaavnResponse
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MusicScreen(navController: NavController) {
    val scope = rememberCoroutineScope()
    val primaryColor = MaterialTheme.colorScheme.primary
    var searchQuery by remember { mutableStateOf("") }
    var musicList by remember { mutableStateOf<List<MusicTrack>>(emptyList()) }
    var isLoading by remember { mutableStateOf(false) }
    var selectedCategory by remember { mutableStateOf("Trending") }

    val categories = listOf("Trending", "New Hits", "Arijit Singh", "Lofi", "Electronic")

    val api = remember {
        Retrofit.Builder().baseUrl("https://saavn.me/")
            .addConverterFactory(GsonConverterFactory.create()).build().create(SaavnApi::class.java)
    }

    fun searchMusic(q: String) {
        scope.launch {
            isLoading = true
            try {
                val res = api.search(q)
                musicList = res.data?.results?.map {
                    MusicTrack(it.id, it.name.replace("&quot;", "\""), it.primaryArtists, it.image.last().link, it.downloadUrl.last().link)
                } ?: emptyList()
            } catch (e: Exception) { e.printStackTrace() }
            isLoading = false
        }
    }

    LaunchedEffect(selectedCategory) { searchMusic(selectedCategory) }

    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        
        // --- HEADER ---
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("STREAMX", color = primaryColor, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                Text("MUSIC ARCHIVE", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold)
            }
            Box(modifier = Modifier.size(40.dp).border(1.dp, primaryColor, CircleShape), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.Search, null, tint = primaryColor)
            }
        }

        // --- SEARCH BAR (Live TV Style) ---
        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            placeholder = { Text("Search system database...", color = Color.Gray, fontSize = 14.sp) },
            shape = RoundedCornerShape(12.dp),
            singleLine = true,
            trailingIcon = {
                IconButton(onClick = { searchMusic(searchQuery) }) {
                    Icon(Icons.Rounded.Search, null, tint = primaryColor)
                }
            },
            colors = TextFieldDefaults.outlinedTextFieldColors(
                focusedBorderColor = primaryColor,
                unfocusedBorderColor = Color.Gray.copy(0.3f),
                cursorColor = primaryColor,
                containerColor = Color.Black.copy(0.2f)
            )
        )

        // --- CATEGORIES ---
        LazyRow(contentPadding = PaddingValues(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(categories) { cat ->
                val isSelected = selectedCategory == cat
                Surface(
                    onClick = { selectedCategory = cat },
                    shape = RoundedCornerShape(8.dp),
                    color = if (isSelected) primaryColor else Color.Transparent,
                    border = BorderStroke(1.dp, if (isSelected) primaryColor else Color.Gray.copy(0.3f)),
                    modifier = Modifier.animateContentSize()
                ) {
                    Text(
                        cat.uppercase(),
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        color = if (isSelected) Color.Black else Color.White,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        // --- MUSIC LIST (Channel Card Style) ---
        if (isLoading) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = primaryColor)
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                items(musicList) { track ->
                    MusicTrackCard(track, primaryColor) {
                        MusicManager.play(track)
                        navController.navigate("music_player")
                    }
                }
            }
        }
    }
}

@Composable
fun MusicTrackCard(track: MusicTrack, primaryColor: Color, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().aspectRatio(0.8f).clickable { onClick() },
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, Color.White.copy(0.1f)),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF121212))
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            AsyncImage(
                model = track.coverUrl,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
            // Overlay
            Box(modifier = Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black))))
            
            Column(modifier = Modifier.align(Alignment.BottomStart).padding(12.dp)) {
                Text(track.title, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                Text(track.artist, color = primaryColor, fontSize = 10.sp, maxLines = 1)
            }

            Box(modifier = Modifier.align(Alignment.TopEnd).padding(8.dp).size(24.dp).background(primaryColor, CircleShape), contentAlignment = Alignment.Center) {
                Icon(Icons.Default.PlayArrow, null, tint = Color.Black, modifier = Modifier.size(16.dp))
            }
        }
    }
}
