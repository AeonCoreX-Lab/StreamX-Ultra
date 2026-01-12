package com.aeoncorex.streamx.ui.music

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import coil.compose.AsyncImage
import com.aeoncorex.streamx.ui.home.CyberMeshBackground
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.URL

// ডাটা মডেল
data class Track(
    val id: String,
    val title: String,
    val artist: String,
    val image: String,
    val url: String
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MusicScreen(navController: NavController) {
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val primaryColor = MaterialTheme.colorScheme.primary
    
    // স্টেট ম্যানেজমেন্ট
    var trendingSongs by remember { mutableStateOf<List<Track>>(emptyList()) }
    var topAlbums by remember { mutableStateOf<List<Track>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }

    // সরাসরি API থেকে ডাটা ফেচ করা (JioSaavn API)
    LaunchedEffect(Unit) {
        scope.launch {
            try {
                val response = withContext(Dispatchers.IO) {
                    URL("https://saavn.me/modules?language=hindi,english,bengali").readText()
                }
                val jsonObject = JSONObject(response)
                val data = jsonObject.getJSONObject("data")
                
                // ট্রেন্ডিং গান লোড করা
                val trendingArray = data.getJSONObject("trending").getJSONArray("songs")
                val trendingList = mutableListOf<Track>()
                for (i in 0 until trendingArray.length()) {
                    val obj = trendingArray.getJSONObject(i)
                    trendingList.add(Track(
                        id = obj.getString("id"),
                        title = obj.getString("name"),
                        artist = obj.getString("primaryArtists"),
                        image = obj.getJSONArray("image").getJSONObject(2).getString("link"),
                        url = obj.getJSONArray("downloadUrl").getJSONObject(4).getString("link")
                    ))
                }
                trendingSongs = trendingList

                // অ্যালবাম লোড করা
                val albumArray = data.getJSONArray("albums")
                val albumList = mutableListOf<Track>()
                for (i in 0 until albumArray.length()) {
                    val obj = albumArray.getJSONObject(i)
                    albumList.add(Track(
                        id = obj.getString("id"),
                        title = obj.getString("name"),
                        artist = obj.getString("artists"),
                        image = obj.getJSONArray("image").getJSONObject(2).getString("link"),
                        url = "" 
                    ))
                }
                topAlbums = albumList
                isLoading = false
            } catch (e: Exception) {
                isLoading = false
            }
        }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            // আপনার লাইভ টিভি স্ক্রিনের ড্রয়ার এখানে কল হবে
        }
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            CyberMeshBackground()

            Scaffold(
                containerColor = Color.Transparent,
                topBar = {
                    CenterAlignedTopAppBar(
                        title = {
                            Text("STREAMX MUSIC", fontWeight = FontWeight.Black, color = primaryColor, letterSpacing = 2.sp)
                        },
                        navigationIcon = {
                            IconButton(onClick = { scope.launch { drawerState.open() } }) {
                                Icon(Icons.Default.Menu, null, tint = Color.White)
                            }
                        },
                        actions = {
                            IconButton(onClick = { /* Search */ }) {
                                Icon(Icons.Default.Search, null, tint = Color.White)
                            }
                        },
                        colors = TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor = Color.Transparent)
                    )
                }
            ) { padding ->
                if (isLoading) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = primaryColor)
                    }
                } else {
                    LazyColumn(modifier = Modifier.padding(padding)) {
                        
                        // ১. বড় ব্যানার (Trending Horizontal)
                        item {
                            MusicHeader("TRENDING HITS")
                            LazyRow(contentPadding = PaddingValues(horizontal = 16.dp)) {
                                items(trendingSongs) { track ->
                                    MusicCard(track) {
                                        navController.navigate("player/${java.net.URLEncoder.encode(track.url, "UTF-8")}")
                                    }
                                }
                            }
                        }

                        // ২. নিউ রিলিজ (Albums)
                        item {
                            MusicHeader("TOP ALBUMS")
                            LazyRow(contentPadding = PaddingValues(horizontal = 16.dp)) {
                                items(topAlbums) { album ->
                                    AlbumCard(album)
                                }
                            }
                        }

                        // ৩. গানের লিস্ট (Vertical)
                        item { MusicHeader("RECOMMENDED FOR YOU") }
                        items(trendingSongs) { track ->
                            MusicListTile(track) {
                                navController.navigate("player/${java.net.URLEncoder.encode(track.url, "UTF-8")}")
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun MusicHeader(title: String) {
    Text(
        text = title,
        fontSize = 13.sp,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(16.dp).background(Color.White.copy(0.05f), RoundedCornerShape(4.dp)).padding(horizontal = 8.dp, vertical = 2.dp)
    )
}

@Composable
fun MusicCard(track: Track, onClick: () -> Unit) {
    Column(modifier = Modifier.width(160.dp).padding(end = 12.dp).clickable { onClick() }) {
        AsyncImage(
            model = track.image,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(160.dp).clip(RoundedCornerShape(12.dp)).border(1.dp, Color.White.copy(0.1f), RoundedCornerShape(12.dp))
        )
        Text(track.title, color = Color.White, fontWeight = FontWeight.Bold, maxLines = 1, modifier = Modifier.padding(top = 8.dp))
        Text(track.artist, color = Color.Gray, fontSize = 11.sp, maxLines = 1)
    }
}

@Composable
fun AlbumCard(track: Track) {
    Column(modifier = Modifier.width(120.dp).padding(end = 12.dp)) {
        AsyncImage(
            model = track.image,
            contentDescription = null,
            modifier = Modifier.size(120.dp).clip(CircleShape).border(1.dp, Color.Cyan.copy(0.3f), CircleShape)
        )
        Text(track.title, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Medium, maxLines = 1, modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 4.dp))
    }
}

@Composable
fun MusicListTile(track: Track, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)
            .background(Color.White.copy(0.03f), RoundedCornerShape(12.dp))
            .clickable { onClick() }.padding(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AsyncImage(model = track.image, contentDescription = null, modifier = Modifier.size(55.dp).clip(RoundedCornerShape(8.dp)))
        Column(modifier = Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(track.title, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
            Text(track.artist, color = Color.Gray, fontSize = 12.sp, maxLines = 1)
        }
        Icon(Icons.Default.PlayCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(32.dp))
    }
}
