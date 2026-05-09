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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
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
//  ExoSourceSelectionScreen — Language-Aware Source Selector
//  ──────────────────────────────────────────────────────────────
//  UI Flow (Movie Box style):
//    1. "Analysing from [source]..." overlay on backdrop
//    2. Auto-fetches streams via StreamProviderEngine (30+ providers, no server needed)
//    3. Language selector: English / Hindi / Tamil / Telugu /
//       Bengali / Korean / Japanese / Dual Audio + any language
//    4. Best stream auto-highlighted, others listed below
//    5. On play → Ad interstitial → ExoPlayer
//
//  Language routing:
//    • Each language key routes to relevant providers in StreamProviderEngine
//    • All HTTP calls made directly from device — no backend server needed
// ═══════════════════════════════════════════════════════════════════

// ── Language definitions for UI ───────────────────────────────────

data class DubOption(
    val key:   String,   // language key for StreamProviderEngine routing
    val label: String,   // shown in UI
    val flag:  String,   // emoji flag
    val color: Color     // accent color for selected state
)

private val DUB_OPTIONS = listOf(
    DubOption("English",    "English",    "🇺🇸", Color(0xFF1565C0)),
    DubOption("Hindi",      "हिंदी",       "🇮🇳", Color(0xFFFF6F00)),
    DubOption("Tamil",      "Tamil",      "🇮🇳", Color(0xFF6A1B9A)),
    DubOption("Telugu",     "Telugu",     "🇮🇳", Color(0xFF00838F)),
    DubOption("Bengali",    "বাংলা",       "🇧🇩", Color(0xFF2E7D32)),
    DubOption("Korean",     "한국어",       "🇰🇷", Color(0xFFC62828)),
    DubOption("Japanese",   "日本語",       "🇯🇵", Color(0xFF283593)),
    DubOption("Dual Audio", "Dual",       "🎵", Color(0xFF558B2F)),
)

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
    var selectedDub      by remember { mutableStateOf(DUB_OPTIONS[0]) }    // English default
    var isAnalysing      by remember { mutableStateOf(false) }
    var analysingSource  by remember { mutableStateOf("") }
    var sources          by remember { mutableStateOf<List<StreamSourceRepository.StreamResult>>(emptyList()) }
    var errorMsg         by remember { mutableStateOf<String?>(null) }
    var adLoading        by remember { mutableStateOf(false) }
    var backdropUrl      by remember { mutableStateOf("") }
    var movieDetails     by remember { mutableStateOf<FullMovieDetails?>(null) }
    var torrentResults   by remember { mutableStateOf<List<TorrentStreamRepository.TorrentResult>>(emptyList()) }
    var isTorrentLoading by remember { mutableStateOf(false) }

    // Sites shown in "Analysing from [site]" — mirrors backend source order
    val sourceSites = remember(selectedDub) {
        when (selectedDub.key) {
            "English"    -> listOf("AutoEmbed", "MovieBox", "Showbox", "FlixHQ", "MoviesAPI", "Primewire")
            "Hindi"      -> listOf("VegaMovies", "HdHub4u", "Filmyfly", "KatMovies", "TopMovies", "MoviesMod")
            "Tamil"      -> listOf("Filmyfly", "VegaMovies", "HdHub4u", "Movies4u", "SkyMoviesHD")
            "Telugu"     -> listOf("Filmyfly", "VegaMovies", "HdHub4u", "MoviezWap")
            "Bengali"    -> listOf("VegaMovies", "HdHub4u", "OgoMovies", "KmMovies", "Joya9tv")
            "Korean"     -> listOf("KissKh", "FlixHQ")
            "Japanese"   -> listOf("HiAnime", "Animetsu", "TokyoInsider")
            "Dual Audio" -> listOf("HdHub4u", "VegaMovies", "KatMovies")
            else         -> listOf("AutoEmbed", "MovieBox", "FlixHQ")
        }
    }

    // ── Load movie details ─────────────────────────────────────────
    LaunchedEffect(tmdbId) {
        movieDetails = MovieRepository.getFullDetails(tmdbId, movieType)
        backdropUrl  = movieDetails?.basic?.backdropUrl ?: ""
    }

    // ── Auto-fetch on language change ──────────────────────────────
    LaunchedEffect(selectedDub) {
        isAnalysing = true
        sources     = emptyList()
        errorMsg    = null

        // Animate through source sites (Movie Box feel)
        for (site in sourceSites) {
            analysingSource = site
            delay(500)
        }

        // Parallel: torrent search
        isTorrentLoading = true
        scope.launch {
            torrentResults = TorrentStreamRepository.search(
                title    = decodedTitle,
                type     = movieType,
                season   = season,
                episode  = episode,
                language = selectedDub.key,
            )
            isTorrentLoading = false
        }

        // Main stream search
        val results = StreamSourceRepository.getSources(
            tmdbId   = if (tmdbId > 0) tmdbId else null,
            imdbId   = if (imdbId.isNotEmpty() && imdbId != "null") imdbId else null,
            title    = decodedTitle,
            type     = movieType,
            season   = season,
            episode  = episode,
            language = selectedDub.key
        )

        isAnalysing     = false
        analysingSource = ""
        if (results.isEmpty())
            errorMsg = "No sources found for ${selectedDub.label}. Try another language."
        else
            sources = results
    }

    fun playSource(source: StreamSourceRepository.StreamResult) {
        if (activity == null) return
        adLoading = true
        AdManager.showInterstitial(activity) {
            adLoading = false
            val encodedUrl   = URLEncoder.encode(source.url,   "UTF-8")
            val encodedTitle = URLEncoder.encode(decodedTitle,  "UTF-8")
            val encLang      = URLEncoder.encode(source.language.ifEmpty { selectedDub.key }, "UTF-8")
            val encImdb      = URLEncoder.encode(imdbId.ifEmpty { "null" }, "UTF-8")

            // Encode subtitles as JSON string in nav arg
            val subsJson = org.json.JSONArray().apply {
                source.subtitles.forEach { sub ->
                    put(org.json.JSONObject().apply {
                        put("url",      sub.url)
                        put("title",    sub.title)
                        put("language", sub.language)
                        put("mimeType", sub.mimeType)
                    })
                }
            }.toString()
            val encSubs = URLEncoder.encode(subsJson, "UTF-8")

            // Encode headers as JSON
            val headersJson = org.json.JSONObject(source.headers.ifEmpty {
                mapOf<String, String>()
            }).toString()
            val encHeaders = URLEncoder.encode(headersJson, "UTF-8")

            navController.navigate(
                "exo_player/$encodedUrl/$encodedTitle/${source.quality}/$encLang/$encImdb/$type/$season/$episode/$encSubs/$encHeaders"
            )
        }
    }

    // ── Root UI ────────────────────────────────────────────────────
    Box(Modifier.fillMaxSize().background(Color.Black)) {

        // Backdrop
        if (backdropUrl.isNotEmpty()) {
            coil.compose.AsyncImage(
                model            = backdropUrl,
                contentDescription = null,
                contentScale     = androidx.compose.ui.layout.ContentScale.Crop,
                modifier         = Modifier.fillMaxWidth().height(280.dp)
            )
            Box(
                Modifier.fillMaxWidth().height(280.dp)
                    .background(Brush.verticalGradient(listOf(Color.Black.copy(0.4f), Color.Black)))
            )
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

            // ── Top bar ────────────────────────────────────────────
            Row(
                Modifier.fillMaxWidth().padding(top = 40.dp, start = 4.dp, end = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = { navController.popBackStack() }) {
                    Icon(Icons.Default.ArrowBack, null, tint = Color.White)
                }
                Column(Modifier.weight(1f)) {
                    Text(
                        decodedTitle,
                        color      = Color.White,
                        fontSize   = 16.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines   = 1,
                        overflow   = TextOverflow.Ellipsis
                    )
                    if (season > 0) {
                        Text(
                            "Season $season  •  Episode $episode",
                            color    = Color.Gray,
                            fontSize = 12.sp
                        )
                    }
                }
            }

            // ── "Analysing from [source]..." bar ──────────────────
            AnimatedVisibility(visible = isAnalysing, enter = fadeIn(), exit = fadeOut()) {
                Box(
                    Modifier.fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 6.dp)
                        .background(Color(0xFF0D1B2A), RoundedCornerShape(10.dp))
                        .padding(12.dp)
                ) {
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            // Language flag
                            Text(selectedDub.flag, fontSize = 14.sp)
                            if (analysingSource.isNotEmpty()) {
                                Text(
                                    "Analysing from [$analysingSource]",
                                    color      = Color.White,
                                    fontSize   = 13.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                            } else {
                                Text(
                                    "Searching ${selectedDub.label} sources…",
                                    color    = Color.Gray,
                                    fontSize = 13.sp
                                )
                            }
                        }
                        Spacer(Modifier.height(6.dp))
                        LinearProgressIndicator(
                            modifier   = Modifier.fillMaxWidth().height(2.dp),
                            color      = selectedDub.color,
                            trackColor = Color.White.copy(0.12f)
                        )
                    }
                }
            }

            // ── Movie info row ─────────────────────────────────────
            movieDetails?.let { m ->
                Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
                    Text(
                        m.basic.title,
                        color      = Color.White,
                        fontSize   = 18.sp,
                        fontWeight = FontWeight.ExtraBold
                    )
                    Spacer(Modifier.height(4.dp))
                    Row(
                        verticalAlignment    = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(Icons.Rounded.Star, null, tint = Color(0xFFFFC107), modifier = Modifier.size(13.dp))
                        Text(m.basic.rating, color = Color.LightGray, fontSize = 12.sp)
                        Text("•", color = Color.Gray, fontSize = 12.sp)
                        Text(m.basic.year, color = Color.Gray, fontSize = 12.sp)
                        Text("•", color = Color.Gray, fontSize = 12.sp)
                        Text(m.genres.take(2).joinToString(", "), color = Color.Gray, fontSize = 12.sp)
                    }
                }
            }

            Spacer(Modifier.height(6.dp))

            // ── Language / Dub selector ────────────────────────────
            Column(Modifier.padding(horizontal = 16.dp)) {
                Row(
                    verticalAlignment    = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        Icons.Rounded.Language, null,
                        tint     = Color.Gray,
                        modifier = Modifier.size(14.dp)
                    )
                    Text(
                        "Language / Dub",
                        color      = Color.Gray,
                        fontSize   = 12.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
                Spacer(Modifier.height(8.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(DUB_OPTIONS) { dub ->
                        DubLanguageChip(
                            dub      = dub,
                            selected = selectedDub.key == dub.key,
                            onClick  = { if (selectedDub.key != dub.key) selectedDub = dub }
                        )
                    }
                }
            }

            Spacer(Modifier.height(10.dp))
            HorizontalDivider(
                color    = Color.White.copy(0.07f),
                modifier = Modifier.padding(horizontal = 16.dp)
            )
            Spacer(Modifier.height(8.dp))

            // ── Resources label + Device Mode badge ────────────────
            Row(
                Modifier.padding(horizontal = 16.dp),
                verticalAlignment    = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    Icons.Rounded.OndemandVideo, null,
                    tint     = Color.White,
                    modifier = Modifier.size(16.dp)
                )
                Text(
                    "Resources",
                    color      = Color.White,
                    fontSize   = 15.sp,
                    fontWeight = FontWeight.Bold
                )
                if (selectedDub.key != "English") {
                    Surface(
                        color  = selectedDub.color.copy(0.18f),
                        shape  = RoundedCornerShape(6.dp),
                        border = androidx.compose.foundation.BorderStroke(
                            0.5.dp, selectedDub.color.copy(0.5f)
                        )
                    ) {
                        Text(
                            "${selectedDub.flag} ${selectedDub.label} Dubbed",
                            color      = selectedDub.color,
                            fontSize   = 10.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier   = Modifier.padding(horizontal = 7.dp, vertical = 3.dp)
                        )
                    }
                }
                // Provider Mode chip — always shown (30+ providers, no server)
                Surface(
                    color  = Color(0xFF0A1F2F),
                    shape  = RoundedCornerShape(6.dp),
                    border = androidx.compose.foundation.BorderStroke(
                        0.5.dp, Color(0xFF00FFFF).copy(0.4f)
                    )
                ) {
                    Row(
                        Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
                        verticalAlignment     = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(
                            Icons.Rounded.Language, null,
                            tint     = Color(0xFF00FFFF),
                            modifier = Modifier.size(10.dp)
                        )
                        Text(
                            "30+ Providers",
                            color         = Color(0xFF00FFFF),
                            fontSize      = 9.sp,
                            fontWeight    = FontWeight.SemiBold,
                            letterSpacing = 0.5.sp
                        )
                    }
                }
            }

            Spacer(Modifier.height(8.dp))

            // ── Content area ───────────────────────────────────────
            when {
                isAnalysing -> AnalysingPlaceholder(selectedDub)

                errorMsg != null -> ErrorState(
                    msg       = errorMsg!!,
                    onRetry   = { selectedDub = selectedDub },   // trigger re-fetch
                    dubOptions = DUB_OPTIONS,
                    onDubChange = { selectedDub = it }
                )

                sources.isNotEmpty() -> SourceResultList(
                    sources      = sources,
                    selectedDub  = selectedDub,
                    decodedTitle = decodedTitle,
                    onPlay       = { playSource(it) }
                )
            }
        }
    }
}

// ════════════════════════════════════════════════════════════════════
//  DubLanguageChip — language selector pill
// ════════════════════════════════════════════════════════════════════

@Composable
private fun DubLanguageChip(dub: DubOption, selected: Boolean, onClick: () -> Unit) {
    val bgColor    = if (selected) dub.color.copy(0.22f) else Color(0xFF141420)
    val borderCol  = if (selected) dub.color.copy(0.7f) else Color.White.copy(0.08f)
    val textColor  = if (selected) dub.color else Color.Gray

    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(bgColor)
            .border(1.dp, borderCol, RoundedCornerShape(20.dp))
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment    = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        Text(dub.flag, fontSize = 13.sp)
        Text(
            dub.label,
            color      = textColor,
            fontSize   = 12.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
        )
    }
}

// ════════════════════════════════════════════════════════════════════
//  AnalysingPlaceholder
// ════════════════════════════════════════════════════════════════════

@Composable
private fun AnalysingPlaceholder(dub: DubOption) {
    Box(
        Modifier.fillMaxWidth().height(140.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(
                color       = dub.color,
                modifier    = Modifier.size(34.dp),
                strokeWidth = 2.5.dp
            )
            Spacer(Modifier.height(10.dp))
            Text(
                "Finding ${dub.label} sources…",
                color    = Color.Gray,
                fontSize = 13.sp
            )
            Spacer(Modifier.height(4.dp))
            Text(
                if (dub.key == "English") "Searching embed sources"
                else "Searching dubbed sources",
                color    = Color(0xFF4A4A5A),
                fontSize = 11.sp
            )
        }
    }
}

// ════════════════════════════════════════════════════════════════════
//  ErrorState
// ════════════════════════════════════════════════════════════════════

@Composable
private fun ErrorState(
    msg:         String,
    onRetry:     () -> Unit,
    dubOptions:  List<DubOption>,
    onDubChange: (DubOption) -> Unit
) {
    Column(
        Modifier.fillMaxWidth().padding(24.dp),
        horizontalAlignment   = Alignment.CenterHorizontally
    ) {
        Icon(
            Icons.Rounded.SearchOff, null,
            tint     = Color.Gray,
            modifier = Modifier.size(40.dp)
        )
        Spacer(Modifier.height(10.dp))
        Text(msg, color = Color.Gray, fontSize = 13.sp, textAlign = TextAlign.Center)
        Spacer(Modifier.height(16.dp))

        // Quick-switch language buttons
        Text("Try another language:", color = Color(0xFF4A4A5A), fontSize = 11.sp)
        Spacer(Modifier.height(8.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(dubOptions.filter { it.key != "English" }.take(5)) { dub ->
                OutlinedButton(
                    onClick = { onDubChange(dub) },
                    colors  = ButtonDefaults.outlinedButtonColors(contentColor = dub.color),
                    border  = androidx.compose.foundation.BorderStroke(1.dp, dub.color.copy(0.5f))
                ) {
                    Text("${dub.flag} ${dub.label}", fontSize = 12.sp)
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        Button(
            onClick = onRetry,
            colors  = ButtonDefaults.buttonColors(containerColor = Color(0xFF1A1A2E))
        ) {
            Icon(Icons.Rounded.Refresh, null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text("Retry")
        }
    }
}

// ════════════════════════════════════════════════════════════════════
//  SourceResultList
// ════════════════════════════════════════════════════════════════════

@Composable
private fun SourceResultList(
    sources:      List<StreamSourceRepository.StreamResult>,
    selectedDub:  DubOption,
    decodedTitle: String,
    onPlay:       (StreamSourceRepository.StreamResult) -> Unit
) {
    // Sort: language match → HLS → quality
    val sorted = remember(sources, selectedDub) {
        sources.sortedWith(
            compareByDescending<StreamSourceRepository.StreamResult> {
                it.language.equals(selectedDub.key, ignoreCase = true)
            }.thenByDescending {
                it.type == "HLS"
            }.thenByDescending {
                when (it.quality) { "4K" -> 4; "1080P" -> 3; "720P" -> 2; "480P" -> 1; else -> 0 }
            }
        )
    }

    LazyColumn(
        contentPadding      = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // ── Best source — prominent play button ──────────────────
        item {
            val best = sorted.first()
            Button(
                onClick  = { onPlay(best) },
                modifier = Modifier.fillMaxWidth().height(54.dp),
                colors   = ButtonDefaults.buttonColors(
                    containerColor = selectedDub.color.copy(0.18f)
                ),
                shape  = RoundedCornerShape(10.dp),
                border = androidx.compose.foundation.BorderStroke(
                    1.dp, selectedDub.color.copy(0.45f)
                )
            ) {
                Icon(
                    Icons.Rounded.PlayCircle, null,
                    tint     = selectedDub.color,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(Modifier.width(8.dp))
                Column(horizontalAlignment = Alignment.Start) {
                    Text(
                        buildString {
                            append(decodedTitle)
                            if (selectedDub.key != "English") append(" [${selectedDub.label}]")
                        },
                        color      = selectedDub.color,
                        fontSize   = 14.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines   = 1,
                        overflow   = TextOverflow.Ellipsis
                    )
                    Text(
                        "${best.quality} • ${best.type} • ${best.source}",
                        color    = selectedDub.color.copy(0.6f),
                        fontSize = 10.sp
                    )
                }
            }
        }

        // ── "More options" header ─────────────────────────────────
        if (sorted.size > 1) {
            item {
                Text(
                    "More sources",
                    color    = Color(0xFF4A4A5A),
                    fontSize = 11.sp,
                    modifier = Modifier.padding(top = 6.dp, bottom = 2.dp)
                )
            }

            items(sorted.drop(1).take(8)) { source ->
                SourceResultCard(source = source, accentColor = selectedDub.color) {
                    onPlay(source)
                }
            }
        }

        item { Spacer(Modifier.height(32.dp)) }
    }
}

// ════════════════════════════════════════════════════════════════════
//  SourceResultCard
// ════════════════════════════════════════════════════════════════════

@Composable
private fun SourceResultCard(
    source:      StreamSourceRepository.StreamResult,
    accentColor: Color = Color.Cyan,
    onClick:     () -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(Color(0xFF0E0E18), RoundedCornerShape(10.dp))
            .border(1.dp, Color.White.copy(0.05f), RoundedCornerShape(10.dp))
            .clickable { onClick() }
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Type icon
        val icon: ImageVector = when {
            source.type == "HLS"  -> Icons.Rounded.PlayCircle
            source.type == "DASH" -> Icons.Rounded.Stream
            else                  -> Icons.Rounded.VideoFile
        }
        val iconTint = when (source.type) {
            "HLS"  -> accentColor
            "DASH" -> Color(0xFF80DEEA)
            else   -> Color.Gray
        }
        Icon(icon, null, tint = iconTint, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(12.dp))

        // Info
        Column(Modifier.weight(1f)) {
            Text(
                source.label.ifEmpty { "${source.quality} • ${source.source}" },
                color    = Color.White,
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment     = Alignment.CenterVertically
            ) {
                Text(source.type, color = Color.Gray, fontSize = 10.sp)
                if (source.language.isNotEmpty() && source.language != "English") {
                    Text("•", color = Color(0xFF3A3A4A), fontSize = 10.sp)
                    Text(
                        source.language,
                        color    = accentColor.copy(0.75f),
                        fontSize = 10.sp
                    )
                }
            }
        }

        // Quality badge
        Box(
            Modifier
                .background(
                    when (source.quality) {
                        "4K"    -> Color(0xFF1A237E)
                        "1080P" -> Color(0xFF0D2E1B)
                        "720P"  -> Color(0xFF1A2A1A)
                        else    -> Color(0xFF1A1A1A)
                    },
                    RoundedCornerShape(6.dp)
                )
                .padding(horizontal = 7.dp, vertical = 3.dp)
        ) {
            Text(
                source.quality,
                color      = when (source.quality) {
                    "4K"    -> Color(0xFF82B1FF)
                    "1080P" -> accentColor
                    "720P"  -> Color(0xFFA5D6A7)
                    else    -> Color.Gray
                },
                fontSize   = 10.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}
