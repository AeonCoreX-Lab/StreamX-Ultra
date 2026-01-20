package com.aeoncorex.streamx.ui.movie

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SignalCellularAlt
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import java.net.URLDecoder
import java.net.URLEncoder

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MovieLinkSelectionScreen(
    navController: NavController,
    imdbId: String,
    title: String,
    type: String, // "MOVIE" or "SERIES"
    season: Int,
    episode: Int
) {
    // Decode title safely
    val decodedTitle = remember { 
        try { URLDecoder.decode(title, "UTF-8") } catch(e: Exception) { title }
    }
    
    var streamLinks by remember { mutableStateOf<List<StreamLink>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    // Fetch Links on Load
    LaunchedEffect(Unit) {
        try {
            val movieType = if (type == "MOVIE") MovieType.MOVIE else MovieType.SERIES
            
            // Simple Logic for Anime detection
            val isAnime = decodedTitle.contains("Naruto", true) || 
                          decodedTitle.contains("One Piece", true) || 
                          decodedTitle.contains("Boruto", true) ||
                          decodedTitle.contains("Bleach", true) ||
                          decodedTitle.contains("Attack on Titan", true)

            val links = TorrentRepository.getStreamLinks(
                type = movieType,
                title = decodedTitle,
                imdbId = if (imdbId == "null") null else imdbId,
                season = season,
                episode = episode,
                isAnime = isAnime
            )
            
            if (links.isEmpty()) {
                errorMessage = "No links found. Try a different server or use VPN."
            } else {
                streamLinks = links
            }
        } catch (e: Exception) {
            errorMessage = "Error: ${e.localizedMessage}"
        } finally {
            isLoading = false
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { 
                    Column {
                        Text("SELECT SOURCE", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                        Text(if(season > 0) "$decodedTitle (S$season E$episode)" else decodedTitle, color = Color.Gray, fontSize = 12.sp)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.Default.ArrowBack, "Back", tint = Color.Cyan)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFF0F0F15))
            )
        },
        containerColor = Color.Black
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(Brush.verticalGradient(listOf(Color(0xFF0F0F15), Color.Black)))
        ) {
            if (isLoading) {
                Column(
                    modifier = Modifier.align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    CircularProgressIndicator(color = Color.Cyan)
                    Spacer(Modifier.height(16.dp))
                    Text("Searching YTS, EZTV & Cloud...", color = Color.Gray)
                }
            } else if (errorMessage != null) {
                Column(
                    modifier = Modifier.align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(Icons.Default.CloudDownload, null, tint = Color.Red, modifier = Modifier.size(48.dp))
                    Spacer(Modifier.height(8.dp))
                    Text(errorMessage!!, color = Color.White)
                    Button(
                        onClick = { navController.popBackStack() },
                        colors = ButtonDefaults.buttonColors(containerColor = Color.Red)
                    ) { Text("Go Back") }
                }
            } else {
                LazyColumn(contentPadding = PaddingValues(16.dp)) {
                    item {
                        Text(
                            "${streamLinks.size} Links Found", 
                            color = Color.Cyan, 
                            fontSize = 12.sp, 
                            modifier = Modifier.padding(bottom = 12.dp)
                        )
                    }
                    items(streamLinks) { link ->
                        StreamLinkCard(link) {
                            // If it's a magnet, encode it. If it's http_stream, handle differently if needed.
                            val encodedUrl = URLEncoder.encode(link.magnet, "UTF-8")
                            navController.navigate("movie_player/$encodedUrl")
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun StreamLinkCard(link: StreamLink, onClick: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1A1A1A)),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
            .clickable { onClick() }
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Quality Badge
            Box(
                modifier = Modifier
                    .background(
                        when {
                            link.quality.contains("2160") || link.quality.contains("4k") -> Color(0xFF9C27B0)
                            link.quality.contains("1080") -> Color(0xFF00C853)
                            link.source == "Web" -> Color(0xFF2979FF) // Blue for Web/Consumet
                            else -> Color.DarkGray
                        }, 
                        RoundedCornerShape(6.dp)
                    )
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            ) {
                Text(link.quality, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 12.sp)
            }

            Spacer(Modifier.width(16.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(link.source, color = Color.Cyan, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                Text(
                    link.title.replace(".", " "), 
                    color = Color.White, 
                    fontSize = 14.sp, 
                    maxLines = 1, 
                    overflow = TextOverflow.Ellipsis
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.CloudDownload, null, tint = Color.Gray, modifier = Modifier.size(12.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(link.size, color = Color.Gray, fontSize = 12.sp)
                    
                    Spacer(Modifier.width(12.dp))
                    
                    Icon(Icons.Default.SignalCellularAlt, null, tint = if(link.seeds > 20) Color.Green else Color.Yellow, modifier = Modifier.size(12.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("${link.seeds} Seeds", color = if(link.seeds > 20) Color.Green else Color.Yellow, fontSize = 12.sp)
                }
            }

            Icon(Icons.Default.PlayArrow, null, tint = Color.White)
        }
    }
}
