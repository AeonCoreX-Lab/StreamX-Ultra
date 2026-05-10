package com.aeoncorex.streamx.ui.movie

import android.app.Activity
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.aeoncorex.streamx.ads.AdManager
import java.net.URLDecoder
import java.net.URLEncoder

// ── Language chips shown in the filter row ────────────────────────
private data class LangChip(
    val lang:  DubLanguage,
    val flag:  String,
    val label: String
)

private val LANG_CHIPS = listOf(
    LangChip(DubLanguage.English,   "🇺🇸", "English"),
    LangChip(DubLanguage.Hindi,     "🇮🇳", "Hindi"),
    LangChip(DubLanguage.Tamil,     "🇮🇳", "Tamil"),
    LangChip(DubLanguage.Telugu,    "🇮🇳", "Telugu"),
    LangChip(DubLanguage.Bengali,   "🇧🇩", "Bengali"),
    LangChip(DubLanguage.Kannada,   "🇮🇳", "Kannada"),
    LangChip(DubLanguage.Malayalam, "🇮🇳", "Malayalam"),
    LangChip(DubLanguage.Japanese,  "🇯🇵", "Japanese"),
    LangChip(DubLanguage.DualAudio, "🌐",  "Dual Audio"),
)

// ── TORRENT ONLY — web servers সরানো হয়েছে ──────────────────────
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MovieLinkSelectionScreen(
    navController: NavController,
    imdbId: String, tmdbId: Int, title: String,
    type: String, season: Int, episode: Int
) {
    val context      = LocalContext.current
    val activity     = context as? Activity
    val decodedTitle = remember(title) {
        try { URLDecoder.decode(title, "UTF-8") } catch (_: Exception) { title }
    }

    var selectedLang by remember { mutableStateOf<DubLanguage>(DubLanguage.English) }
    var torrentLinks by remember { mutableStateOf<List<StreamLink>>(emptyList()) }
    var isLoading    by remember { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var adLoading    by remember { mutableStateOf(false) }

    // Re-fetch whenever title/episode/language changes
    LaunchedEffect(decodedTitle, season, episode, selectedLang) {
        isLoading = true; errorMessage = null; torrentLinks = emptyList()
        try {
            val movieType = if (type.equals("MOVIE", true)) MovieType.MOVIE else MovieType.SERIES
            val isAnime   = listOf("Naruto","One Piece","Demon Slayer","Jujutsu","Attack on Titan","Dragon Ball")
                .any { decodedTitle.contains(it, ignoreCase = true) }
            val validImdb = if (imdbId != "null" && imdbId.isNotEmpty()) imdbId else null
            torrentLinks  = TorrentRepository.getStreamLinks(
                type = movieType, title = decodedTitle, imdbId = validImdb,
                season = season, episode = episode, isAnime = isAnime,
                dubLang = selectedLang
            )
        } catch (e: Exception) {
            errorMessage = "Search failed: ${e.localizedMessage}"
        } finally { isLoading = false }
    }

    fun playTorrent(magnet: String) {
        if (activity == null) return
        adLoading = true
        AdManager.showInterstitial(activity) {
            adLoading = false
            val enc = URLEncoder.encode(magnet, "UTF-8")
            navController.navigate("torrent_player/$enc")
        }
    }

    Box(Modifier.fillMaxSize()) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Column {
                            Text("4K / 1080P Torrents", color = Color.White,
                                fontSize = 15.sp, fontWeight = FontWeight.Bold)
                            Text(
                                if (season > 0) "$decodedTitle · S${season}E${episode}" else decodedTitle,
                                color = Color.Gray, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis
                            )
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = { navController.popBackStack() }) {
                            Icon(Icons.Default.ArrowBack, null, tint = Color.Cyan)
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFF0A0A14))
                )
            },
            containerColor = Color.Black
        ) { padding ->
            Box(
                Modifier.fillMaxSize().padding(padding)
                    .background(Brush.verticalGradient(listOf(Color(0xFF0A0A14), Color.Black)))
            ) {
                Column(Modifier.fillMaxSize()) {

                    // ── Language Filter Row ──────────────────────────
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(LANG_CHIPS) { chip ->
                            val isSelected = selectedLang::class == chip.lang::class
                            Box(
                                modifier = Modifier
                                    .background(
                                        if (isSelected) Color(0xFF003333) else Color(0xFF0F0F1A),
                                        RoundedCornerShape(20.dp)
                                    )
                                    .border(
                                        1.dp,
                                        if (isSelected) Color.Cyan else Color.White.copy(0.1f),
                                        RoundedCornerShape(20.dp)
                                    )
                                    .clickable { selectedLang = chip.lang }
                                    .padding(horizontal = 12.dp, vertical = 7.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                                ) {
                                    Text(chip.flag, fontSize = 14.sp)
                                    Text(
                                        chip.label,
                                        color = if (isSelected) Color.Cyan else Color.White.copy(0.75f),
                                        fontSize = 12.sp,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                                    )
                                }
                            }
                        }
                    }

                    // ── Results area ────────────────────────────────
                    Box(Modifier.fillMaxSize()) {
                        when {
                            isLoading -> Column(
                                Modifier.align(Alignment.Center),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                CircularProgressIndicator(color = Color.Cyan, modifier = Modifier.size(36.dp), strokeWidth = 3.dp)
                                Spacer(Modifier.height(12.dp))
                                val loadMsg = if (selectedLang == DubLanguage.English)
                                    "Scanning P2P Networks…"
                                else
                                    "Searching ${LANG_CHIPS.find { it.lang::class == selectedLang::class }?.label ?: ""} dubs…"
                                Text(loadMsg, color = Color.Gray, fontSize = 13.sp)
                                Text("YTS · RARBG · EZTV · 1337x", color = Color.Gray.copy(0.5f), fontSize = 11.sp)
                            }
                            errorMessage != null -> Column(
                                Modifier.align(Alignment.Center).padding(24.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Icon(Icons.Rounded.SearchOff, null, tint = Color.Gray, modifier = Modifier.size(44.dp))
                                Spacer(Modifier.height(12.dp))
                                Text(errorMessage!!, color = Color.Gray, textAlign = TextAlign.Center, fontSize = 13.sp)
                            }
                            torrentLinks.isEmpty() -> Column(
                                Modifier.align(Alignment.Center).padding(24.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Icon(Icons.Rounded.CloudOff, null, tint = Color.Gray, modifier = Modifier.size(44.dp))
                                Spacer(Modifier.height(12.dp))
                                val langLabel = LANG_CHIPS.find { it.lang::class == selectedLang::class }?.label ?: "selected language"
                                Text("No $langLabel torrents found", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                                Text(
                                    if (selectedLang == DubLanguage.English) "Try instant stream instead"
                                    else "Try a different language or instant stream",
                                    color = Color.Gray, fontSize = 13.sp, textAlign = TextAlign.Center
                                )
                                Spacer(Modifier.height(16.dp))
                                Button(
                                    onClick = { navController.popBackStack() },
                                    colors  = ButtonDefaults.buttonColors(containerColor = Color(0xFF0E2A1E))
                                ) { Text("← Use Instant Play", color = Color.Cyan) }
                            }
                            else -> LazyColumn(
                                contentPadding = PaddingValues(horizontal = 16.dp, bottom = 16.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                item {
                                    val langLabel = LANG_CHIPS.find { it.lang::class == selectedLang::class }
                                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 4.dp)) {
                                        Icon(Icons.Rounded.DownloadForOffline, null, tint = Color.Cyan, modifier = Modifier.size(16.dp))
                                        Spacer(Modifier.width(6.dp))
                                        Text(
                                            "${torrentLinks.size} ${langLabel?.flag ?: ""} ${langLabel?.label ?: ""} sources found",
                                            color = Color.Cyan, fontSize = 13.sp, fontWeight = FontWeight.Bold
                                        )
                                    }
                                }
                                items(torrentLinks) { link ->
                                    TorrentCard(link) { playTorrent(link.magnet) }
                                }
                                item { Spacer(Modifier.height(20.dp)) }
                            }
                        }
                    }
                }
            }
        }

        if (adLoading) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(0.88f)), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Color.Cyan, modifier = Modifier.size(40.dp))
            }
        }
    }
}

@Composable
fun TorrentCard(link: StreamLink, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF0F0F1A), RoundedCornerShape(10.dp))
            .border(1.dp, Color.White.copy(0.07f), RoundedCornerShape(10.dp))
            .clickable { onClick() }
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(44.dp)) {
            val seedColor = if (link.seeds > 20) Color.Green else if (link.seeds > 5) Color.Yellow else Color.Red
            Icon(Icons.Rounded.ArrowUpward, null, tint = seedColor, modifier = Modifier.size(18.dp))
            Text("${link.seeds}", color = seedColor, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            Text("seeds", color = Color.Gray, fontSize = 9.sp)
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(link.title, color = Color.White, fontSize = 13.sp,
                fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(5.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                val (qbg, qfg) = when (link.quality) {
                    "4K","2160P" -> Color(0xFF1A0A00) to Color(0xFFFF6D00)
                    "1080P"      -> Color(0xFF001A0A) to Color(0xFF00E676)
                    "720P"       -> Color(0xFF001A1A) to Color(0xFF00E5FF)
                    else         -> Color(0xFF1A1A1A) to Color.Gray
                }
                Box(Modifier.background(qbg, RoundedCornerShape(4.dp)).padding(horizontal = 6.dp, vertical = 2.dp)) {
                    Text(link.quality, color = qfg, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                }
                Box(
                    Modifier.background(Color(0xFF0A0A1A), RoundedCornerShape(4.dp))
                        .border(1.dp, Color.Cyan.copy(0.2f), RoundedCornerShape(4.dp))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) { Text(link.source.take(8), color = Color.Cyan.copy(0.8f), fontSize = 9.sp) }
                if (link.size.isNotEmpty()) {
                    Text(link.size, color = Color.Gray, fontSize = 10.sp,
                        modifier = Modifier.align(Alignment.CenterVertically))
                }
            }
        }
        Icon(Icons.Rounded.PlayCircle, null, tint = Color.Cyan, modifier = Modifier.size(30.dp))
    }
}
