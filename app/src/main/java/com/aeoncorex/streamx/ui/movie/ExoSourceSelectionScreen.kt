package com.aeoncorex.streamx.ui.movie

import android.app.Activity
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.net.URLDecoder
import java.net.URLEncoder

// ═══════════════════════════════════════════════════════════════════
//  ExoSourceSelectionScreen
//  ─────────────────────────
//  Movie Box style:
//    1. Opens with "Analysing from [source]..." overlay on backdrop
//    2. Auto-fetches best stream from multiple sources in parallel
//    3. Language filter: English, Hindi, Tamil, Telugu, etc.
//    4. One-tap play — no complex server selection UI
//    5. If first source fails → next source tries automatically
//
//  Unlike old MovieLinkSelectionScreen:
//    ❌ No web server cards
//    ❌ No torrent section (moved to separate "Play with Torrent" button)
//    ✅ Just clean auto-analysing + play
// ═══════════════════════════════════════════════════════════════════
@Composable
fun ExoSourceSelectionScreen(
    navController: NavController,
    imdbId:  String,
    tmdbId:  Int,
    title:   String,
    type:    String,
    season:  Int,
    episode: Int
) {
    val context   = LocalContext.current
    val activity  = context as? Activity
    val scope     = rememberCoroutineScope()
    val movieType = if (type.equals("MOVIE", true)) MovieType.MOVIE else MovieType.SERIES

    val decodedTitle = remember(title) {
        try { URLDecoder.decode(title, "UTF-8") } catch (_: Exception) { title }
    }

    // ── State ──────────────────────────────────────────────────────
    var isUsingFallback  by remember { mutableStateOf(false) }
    var analysingSource  by remember { mutableStateOf("") }
    var isAnalysing      by remember { mutableStateOf(false) }
    var sources          by remember { mutableStateOf<List<StreamSourceRepository.StreamResult>>(emptyList()) }
    var errorMsg         by remember { mutableStateOf<String?>(null) }
    var selectedLang     by remember { mutableStateOf("English") }
    var adLoading        by remember { mutableStateOf(false) }
    var torrentResults   by remember { mutableStateOf<List<TorrentStreamRepository.TorrentResult>>(emptyList()) }
    var isTorrentLoading by remember { mutableStateOf(false) }
    var torrentBuffer    by remember { mutableIntStateOf(0) }
    var isStreamingTorrent by remember { mutableStateOf(false) }
    var backdropUrl      by remember { mutableStateOf("") }
    var movieDetails     by remember { mutableStateOf<FullMovieDetails?>(null) }

    // Source sites shown in "Analysing from [site]" UI — matches backend order
    val sourceSites = listOf(
        "vidlink.pro", "embed.su", "vidsrc.xyz",
        "autoembed.cc", "2embed.stream", "123freemovies.net", "fzmovie.net", "1337xx.to"
    )
    val languages = listOf("English", "Hindi", "Tamil", "Telugu", "Bengali")

    // Load movie details for backdrop
    LaunchedEffect(tmdbId) {
        movieDetails = MovieRepository.getFullDetails(tmdbId, movieType)
        backdropUrl  = movieDetails?.basic?.backdropUrl ?: ""
    }

    // Auto-start analysis
    LaunchedEffect(selectedLang) {
        isAnalysing = true
        sources     = emptyList()
        errorMsg    = null

        for (site in sourceSites) {
            analysingSource = site
            delay(600)  // visual effect like Movie Box
        }

        // Also search 1337x in parallel for dubbed/torrent sources
        isTorrentLoading = true
        kotlinx.coroutines.launch {
            val torrents = TorrentStreamRepository.search(
                title    = decodedTitle,
                type     = if (movieType == "TV" || movieType == "SERIES") MovieType.SERIES else MovieType.MOVIE,
                season   = season,
                episode  = episode,
                language = selectedLang,
            )
            torrentResults   = torrents
            isTorrentLoading = false
        }

        val results = StreamSourceRepository.getSources(
            tmdbId   = if (tmdbId > 0) tmdbId else null,
            imdbId   = if (imdbId.isNotEmpty() && imdbId != "null") imdbId else null,
            title    = decodedTitle,
            type     = movieType,
            season   = season,
            episode  = episode,
            language = selectedLang
        )

        isAnalysing     = false
        analysingSource = ""
        isUsingFallback = StreamSourceRepository.lastUsedFallback

        if (results.isEmpty()) errorMsg = "No sources found. Try a different language."
        else sources = results
    }

    fun playSource(source: StreamSourceRepository.StreamResult) {
        if (activity == null) return
        adLoading = true
        AdManager.showInterstitial(activity) {
            adLoading = false
            val encodedUrl   = URLEncoder.encode(source.url, "UTF-8")
            val encodedTitle = URLEncoder.encode(decodedTitle, "UTF-8")
            val encLang      = URLEncoder.encode(source.language, "UTF-8")
            val encImdb      = URLEncoder.encode(imdbId.ifEmpty { "null" }, "UTF-8")
            navController.navigate(
                "exo_player/$encodedUrl/$encodedTitle/${source.quality}/$encLang/$encImdb/$type/$season/$episode"
            )
        }
    }

    // ── UI ──────────────────────────────────────────────────────────
    Box(Modifier.fillMaxSize().background(Color.Black)) {

        // Backdrop image (Movie Box shows it behind)
        if (backdropUrl.isNotEmpty()) {
            coil.compose.AsyncImage(
                model = backdropUrl, contentDescription = null,
                contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                modifier     = Modifier.fillMaxWidth().height(260.dp)
            )
            Box(Modifier.fillMaxWidth().height(260.dp)
                .background(Brush.verticalGradient(listOf(Color.Black.copy(0.5f), Color.Black))))
        }

        // Ad loading overlay
        if (adLoading) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(0.88f)),
                contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Color.Cyan, modifier = Modifier.size(40.dp))
            }
            return@Box
        }

        Column(Modifier.fillMaxSize()) {

            // Top bar (no backdrop behind controls area)
            Row(
                Modifier.fillMaxWidth().padding(top = 40.dp, start = 4.dp, end = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = { navController.popBackStack() }) {
                    Icon(Icons.Default.ArrowBack, null, tint = Color.White)
                }
                Column(Modifier.weight(1f)) {
                    Text(decodedTitle, color = Color.White, fontSize = 16.sp,
                        fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (season > 0) {
                        Text("Season $season  Episode $episode", color = Color.Gray, fontSize = 12.sp)
                    }
                }
            }

            // Movie Box style "Analysing from [source]" overlay text
            AnimatedVisibility(
                visible = isAnalysing,
                enter   = fadeIn(), exit = fadeOut()
            ) {
                Box(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
                        .background(Color.Black.copy(0.7f), RoundedCornerShape(8.dp))
                        .padding(12.dp)
                ) {
                    Column {
                        if (analysingSource.isNotEmpty()) {
                            Text("Analysing from [$analysingSource]",
                                color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        }
                        Spacer(Modifier.height(6.dp))
                        LinearProgressIndicator(
                            modifier   = Modifier.fillMaxWidth().height(2.dp),
                            color      = Color.Cyan,
                            trackColor = Color.White.copy(0.15f)
                        )
                    }
                }
            }

            // Movie info row (like Movie Box)
            movieDetails?.let { m ->
                Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Text(m.basic.title, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.ExtraBold)
                    Spacer(Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Rounded.Star, null, tint = Color(0xFFFFC107), modifier = Modifier.size(14.dp))
                        Text(m.basic.rating, color = Color.LightGray, fontSize = 12.sp)
                        Text("•", color = Color.Gray, fontSize = 12.sp)
                        Text(m.basic.year, color = Color.Gray, fontSize = 12.sp)
                        Text("•", color = Color.Gray, fontSize = 12.sp)
                        Text(m.genres.take(2).joinToString(", "), color = Color.Gray, fontSize = 12.sp)
                    }
                }
            }

            // Language filter tabs (like Movie Box dub selector)
            LazyRow(
                Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(languages) { lang ->
                    FilterChip(
                        selected = selectedLang == lang,
                        onClick  = { selectedLang = lang },
                        label    = { Text(lang, fontSize = 12.sp) },
                        colors   = FilterChipDefaults.filterChipColors(
                            containerColor         = Color(0xFF1A1A2A),
                            labelColor             = Color.Gray,
                            selectedContainerColor = Color(0xFF1A3A2A),
                            selectedLabelColor     = Color.Cyan
                        )
                    )
                }
            }

            Spacer(Modifier.height(8.dp))
            HorizontalDivider(color = Color.White.copy(0.08f), modifier = Modifier.padding(horizontal = 16.dp))
            Spacer(Modifier.height(8.dp))

            // Resources label + optional Device Mode badge
            Row(
                Modifier.padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text("Resources",
                    color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                if (isUsingFallback) {
                    Surface(
                        color  = Color(0xFF1A2A3A),
                        shape  = RoundedCornerShape(6.dp),
                        border = androidx.compose.foundation.BorderStroke(
                            0.5.dp, Color.Cyan.copy(alpha = 0.4f)
                        )
                    ) {
                        Row(
                            Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Icon(
                                Icons.Rounded.PhoneAndroid, null,
                                tint     = Color.Cyan,
                                modifier = Modifier.size(10.dp)
                            )
                            Text(
                                "Device Mode",
                                color    = Color.Cyan,
                                fontSize = 9.sp,
                                fontWeight = FontWeight.SemiBold,
                                letterSpacing = 0.5.sp
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(8.dp))

            when {
                isAnalysing -> {
                    Box(Modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator(color = Color.Cyan,
                                modifier = Modifier.size(32.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.height(8.dp))
                            Text("Finding best sources…", color = Color.Gray, fontSize = 12.sp)
                        }
                    }
                }
                errorMsg != null -> {
                    Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(Icons.Rounded.SearchOff, null, tint = Color.Gray, modifier = Modifier.size(36.dp))
                            Spacer(Modifier.height(8.dp))
                            Text(errorMsg!!, color = Color.Gray, fontSize = 13.sp, textAlign = TextAlign.Center)
                            Spacer(Modifier.height(12.dp))
                            Button(onClick = { selectedLang = selectedLang }) {
                                Text("Retry")
                            }
                        }
                    }
                }
                sources.isNotEmpty() -> {
                    LazyColumn(
                        contentPadding      = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        val sorted = sources.sortedWith(
                            compareByDescending<StreamSourceRepository.StreamResult> {
                                when (it.quality) { "4K" -> 4; "1080P" -> 3; "720P" -> 2; "480P" -> 1; else -> 0 }
                            }.thenByDescending { it.type == "HLS" }
                        )

                        item {
                            val best = sorted.first()
                            Button(
                                onClick  = { playSource(best) },
                                modifier = Modifier.fillMaxWidth().height(50.dp),
                                colors   = ButtonDefaults.buttonColors(containerColor = Color(0xFF0E2A1E)),
                                shape    = RoundedCornerShape(8.dp),
                                border   = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF00C9A7).copy(0.4f))
                            ) {
                                Text(
                                    decodedTitle + if (selectedLang != "English") " [$selectedLang]" else "",
                                    color      = Color(0xFF00C9A7),
                                    fontSize   = 14.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }

                        if (sorted.size > 1) {
                            item {
                                Text("More options", color = Color.Gray, fontSize = 11.sp,
                                    modifier = Modifier.padding(top = 8.dp, bottom = 4.dp))
                            }
                            items(sorted.drop(1).take(6)) { source ->
                                SourceResultCard(source) { playSource(source) }
                            }
                        }

                        item { Spacer(Modifier.height(24.dp)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun SourceResultCard(source: StreamSourceRepository.StreamResult, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth()
            .background(Color(0xFF111118), RoundedCornerShape(8.dp))
            .border(1.dp, Color.White.copy(0.06f), RoundedCornerShape(8.dp))
            .clickable { onClick() }
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val typeIcon = if (source.type == "HLS") Icons.Rounded.PlayCircle else Icons.Rounded.VideoFile
        Icon(typeIcon, null, tint = Color.Cyan, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(source.label.ifEmpty { "${source.quality} • ${source.source}" },
                color = Color.White, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("${source.type} • ${source.language}",
                color = Color.Gray, fontSize = 11.sp)
        }
        Text(source.quality,
            color    = Color.Yellow,
            fontSize = 11.sp,
            modifier = Modifier.background(Color.Black.copy(0.5f), RoundedCornerShape(4.dp)).padding(4.dp, 2.dp))
    }
}
