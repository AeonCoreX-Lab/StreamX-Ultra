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
    title: String,
    type: String, // "MOVIE" or "SERIES"
    season: Int,
    episode: Int
) {
    val decodedTitle = remember { try { URLDecoder.decode(title, "UTF-8") } catch(e: Exception) { title } }
    
    // States
    var torrentLinks by remember { mutableStateOf<List<StreamLink>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    // --- WEB SERVER LINKS (UPDATED) ---
    val webServers = remember(imdbId, type, season, episode) {
        val servers = mutableListOf<ServerLink>()
        
        if (imdbId != "null" && imdbId.isNotEmpty()) {
            val isSeries = type.equals("SERIES", ignoreCase = true) || type.equals("TV", ignoreCase = true)

            // 1. VidSrc Pro (Multi-Audio)
            // Docs: https://vidsrc.xyz/embed/movie?imdb=tt123 or https://vidsrc.xyz/embed/tv?imdb=tt123&season=1&episode=1
            val vidsrcProUrl = if (isSeries) {
                "https://vidsrc.xyz/embed/tv?imdb=$imdbId&season=$season&episode=$episode"
            } else {
                "https://vidsrc.xyz/embed/movie?imdb=$imdbId"
            }
            servers.add(ServerLink("VidSrc Pro (Multi-Audio)", vidsrcProUrl))

            // 2. SuperEmbed (VIP Player)
            // Docs: https://multiembed.mov/directstream.php?video_id=tt123 (&s=1&e=1)
            val superEmbedUrl = if (isSeries) {
                "https://multiembed.mov/directstream.php?video_id=$imdbId&s=$season&e=$episode"
            } else {
                "https://multiembed.mov/directstream.php?video_id=$imdbId"
            }
            servers.add(ServerLink("SuperEmbed (VIP Fast)", superEmbedUrl))

            // 3. VidSrc Me (Backup)
            // Docs: https://vidsrc.me/embed/movie?imdb=tt123
            val vidsrcMeUrl = if (isSeries) {
                "https://vidsrc.me/embed/tv?imdb=$imdbId&season=$season&episode=$episode"
            } else {
                "https://vidsrc.me/embed/movie?imdb=$imdbId"
            }
            servers.add(ServerLink("VidSrc Me (Backup)", vidsrcMeUrl))

            // 4. 2Embed (Clean)
            // Docs: https://www.2embed.stream/2embed.php?id=tt123 or tv-2embed.php?id=tt123&season=1&episode=1
            val twoEmbedUrl = if (isSeries) {
                "https://www.2embed.stream/tv-2embed.php?id=$imdbId&season=$season&episode=$episode"
            } else {
                "https://www.2embed.stream/2embed.php?id=$imdbId"
            }
            servers.add(ServerLink("2Embed (Clean)", twoEmbedUrl))
        }
        servers
    }

    // Fetch Torrents
    LaunchedEffect(Unit) {
        try {
            val movieType = if (type == "MOVIE") MovieType.MOVIE else MovieType.SERIES
            val isAnime = decodedTitle.contains("Naruto", true) || decodedTitle.contains("One Piece", true)

            val links = TorrentRepository.getStreamLinks(
                type = movieType,
                title = decodedTitle,
                imdbId = if (imdbId == "null") null else imdbId,
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
                    item { Text("No server links available (Missing IMDB ID)", color = Color.Gray, fontSize = 12.sp) }
                } else {
                    items(webServers) { server ->
                        ServerCard(server) {
                            // Open in AdBlock Web Player
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

// Data Model & UI for Web Servers
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
            Text(server.name, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            Spacer(Modifier.weight(1f))
            Icon(Icons.Default.PlayCircleOutline, null, tint = Color.White)
        }
    }
}

// Existing StreamLinkCard
@Composable
fun StreamLinkCard(link: StreamLink, onClick: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1A1A1A)),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp).clickable { onClick() }
    ) {
        Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier.background(
                    when {
                        link.quality.contains("2160") || link.quality.contains("4k") -> Color(0xFF9C27B0)
                        link.quality.contains("1080") -> Color(0xFF00C853)
                        else -> Color.DarkGray
                    }, RoundedCornerShape(6.dp)
                ).padding(horizontal = 8.dp, vertical = 4.dp)
            ) {
                Text(link.quality, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 12.sp)
            }
            Spacer(Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(link.source, color = Color.Cyan, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                Text(link.title.replace(".", " "), color = Color.White, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
