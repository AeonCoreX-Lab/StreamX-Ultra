package com.aeoncorex.streamx.ui.movie

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
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
    tmdbId: Int,
    title: String,
    type: String,
    season: Int,
    episode: Int
) {
    val decodedTitle = remember { try { URLDecoder.decode(title, "UTF-8") } catch(e: Exception) { title } }
    
    // States
    var torrentLinks by remember { mutableStateOf<List<StreamLink>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    // --- WEB SERVER LINKS GENERATION ---
    val webServers = remember(imdbId, tmdbId, type, season, episode) {
        ServerLinkGenerator.generateLinks(
            imdbId = if (imdbId == "null" || imdbId.isEmpty()) null else imdbId,
            tmdbId = if (tmdbId == 0) null else tmdbId,
            isSeries = type.equals("SERIES", ignoreCase = true) || type.equals("TV", ignoreCase = true),
            season = season,
            episode = episode
        )
    }

    // Fetch Torrents
    LaunchedEffect(Unit) {
        try {
            val movieType = if (type == "MOVIE" || type == "movie") MovieType.MOVIE else MovieType.SERIES
            val isAnime = decodedTitle.contains("Naruto", true) || decodedTitle.contains("One Piece", true)
            val validImdb = if (imdbId != "null" && imdbId.isNotEmpty()) imdbId else null

            val links = TorrentRepository.getStreamLinks(
                type = movieType,
                title = decodedTitle,
                imdbId = validImdb,
                season = season,
                episode = episode,
                isAnime = isAnime
            )
            torrentLinks = links
        } catch (e: Exception) {
            errorMessage = "Torrent Error: ${e.localizedMessage}"
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
            LazyColumn(contentPadding = PaddingValues(16.dp)) {
                
                // --- SECTION 1: FAST WEB SERVERS ---
                item {
                    Text("⚡ FAST CLOUD SERVERS (NO DOWNLOAD)", color = Color.Green, fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 8.dp))
                }
                
                if (webServers.isEmpty()) {
                    item { Text("No server links available (Missing IDs)", color = Color.Gray, fontSize = 12.sp) }
                } else {
                    items(webServers) { server ->
                        ServerCard(server) {
                            val encodedUrl = URLEncoder.encode(server.url, "UTF-8")
                            navController.navigate("webview_player/$encodedUrl")
                        }
                    }
                }

                item { 
                    Spacer(Modifier.height(20.dp))
                    Divider(color = Color.DarkGray, thickness = 1.dp)
                    Spacer(Modifier.height(20.dp))
                }

                // --- SECTION 2: TORRENTS ---
                item {
                    Text("💎 4K/1080p TORRENTS (NATIVE PLAYER)", color = Color.Cyan, fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 12.dp))
                }

                if (isLoading) {
                    item {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), color = Color.Cyan, strokeWidth = 2.dp)
                            Spacer(Modifier.width(10.dp))
                            Text("Searching P2P Networks...", color = Color.Gray, fontSize = 12.sp)
                        }
                    }
                } else if (torrentLinks.isEmpty()) {
                    item { Text("No torrents found. Please use Server 1 or 2 above.", color = Color.Gray) }
                } else {
                    items(torrentLinks) { link ->
                        StreamLinkCard(link) {
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
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1A1A20)),
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clickable { onClick() }
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Seeds Icon
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(40.dp)) {
                Icon(Icons.Default.ArrowDownward, null, tint = Color.Green, modifier = Modifier.size(20.dp))
                Text("${link.seeds}", color = Color.Green, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            }
            
            Spacer(Modifier.width(12.dp))
            
            Column(modifier = Modifier.weight(1f)) {
                Text(link.title, color = Color.White, fontWeight = FontWeight.Medium, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(link.size, color = Color.Gray, fontSize = 11.sp)
                    Spacer(Modifier.width(8.dp))
                    Box(modifier = Modifier.background(Color.DarkGray, RoundedCornerShape(4.dp)).padding(horizontal = 4.dp, vertical = 2.dp)) {
                        Text(link.source, color = Color.Cyan, fontSize = 9.sp)
                    }
                    Spacer(Modifier.width(8.dp))
                    // Quality Badge
                    Box(modifier = Modifier.background(Color.DarkGray, RoundedCornerShape(4.dp)).padding(horizontal = 4.dp, vertical = 2.dp)) {
                        Text(link.quality, color = Color.Yellow, fontSize = 9.sp)
                    }
                }
            }
            
            Icon(Icons.Default.PlayCircle, null, tint = Color.Cyan, modifier = Modifier.size(32.dp))
        }
    }
}

object ServerLinkGenerator {
    fun generateLinks(
        imdbId: String?,
        tmdbId: Int?,
        isSeries: Boolean,
        season: Int,
        episode: Int
    ): List<ServerLink> {
        val servers = mutableListOf<ServerLink>()

        // ------------------------------------------------------------
        // 1. CLOUD STREAM (VidSrc.win) - NEW ULTIMATE SERVER
        // ------------------------------------------------------------
        // This is the "Next Level" player request
        if (tmdbId != null) {
             val url = if (isSeries) "https://vidsrc.win/tv.html?id=$tmdbId&s=$season&e=$episode"
                       else "https://vidsrc.win/movie.html?id=$tmdbId"
             // High priority label
             servers.add(ServerLink("Vidsrc Win (next level player)", url))
        } else if (imdbId != null) {
             val url = if (isSeries) "https://vidsrc.win/tv.html?id=$imdbId&s=$season&e=$episode"
                       else "https://vidsrc.win/movie.html?id=$imdbId"
             servers.add(ServerLink("Vidsrc Win (next level player)", url))
        }
        
        // 2. SuperEmbed (Prioritize TMDB)
        if (tmdbId != null) {
            val url = if (isSeries) "https://multiembed.mov/?video_id=$tmdbId&tmdb=1&s=$season&e=$episode"
                      else "https://multiembed.mov/?video_id=$tmdbId&tmdb=1"
            servers.add(ServerLink("SuperEmbed (TMDB - Fast)", url))
        } else if (imdbId != null) {
            val url = if (isSeries) "https://multiembed.mov/?video_id=$imdbId&s=$season&e=$episode"
                      else "https://multiembed.mov/?video_id=$imdbId"
            servers.add(ServerLink("SuperEmbed (IMDb - Fast)", url))
        }

        // 3. 2Embed
        if (tmdbId != null) {
            val url = if (isSeries) "https://www.2embed.stream/embed/tv/$tmdbId/$season/$episode"
                      else "https://www.2embed.stream/embed/movie/$tmdbId"
            servers.add(ServerLink("2Embed (TMDB - Clean)", url))
        } else if (imdbId != null) {
            val url = if (isSeries) "https://www.2embed.stream/embed/tv/$imdbId/$season/$episode"
                      else "https://www.2embed.stream/embed/movie/$imdbId"
            servers.add(ServerLink("2Embed (IMDb - Clean)", url))
        }

        // 4. VidSrc Pro
        if (imdbId != null) {
            val url = if (isSeries) "https://vidsrc.xyz/embed/tv?imdb=$imdbId&season=$season&episode=$episode"
                      else "https://vidsrc.xyz/embed/movie?imdb=$imdbId"
            servers.add(ServerLink("VidSrc Pro (IMDb - Multi)", url))
        } else if (tmdbId != null) {
             val url = if (isSeries) "https://vidsrc.xyz/embed/tv?tmdb=$tmdbId&season=$season&episode=$episode"
                      else "https://vidsrc.xyz/embed/movie?tmdb=$tmdbId"
            servers.add(ServerLink("VidSrc Pro (TMDB - Multi)", url))
        }
        
        return servers
    }
}

data class ServerLink(val name: String, val url: String)

@Composable
fun ServerCard(server: ServerLink, onClick: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Color(0xFF202025)),
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clickable { onClick() }
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Default.Public, null, tint = Color.Green, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(16.dp))
            Column {
                Text(server.name, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                Text(server.url.take(40) + "...", color = Color.Gray, fontSize = 10.sp)
            }
            Spacer(Modifier.weight(1f))
            Icon(Icons.Default.PlayCircleOutline, null, tint = Color.White)
        }
    }
}