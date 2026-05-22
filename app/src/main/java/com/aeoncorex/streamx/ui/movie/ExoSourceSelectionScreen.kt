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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
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
import com.aeoncorex.streamx.streaming.ProviderRequest
import com.aeoncorex.streamx.streaming.StreamCache
import com.aeoncorex.streamx.streaming.StreamProviderEngine
import com.aeoncorex.streamx.streaming.StreamResult
import com.aeoncorex.streamx.streaming.StreamType
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.net.URLDecoder
import java.net.URLEncoder

// ═════════════════════════════════════════════════════════════════════════════
//  ExoSourceSelectionScreen — v2  Cache-First + Live Streaming Results
//  ─────────────────────────────────────────────────────────────────────────
//  What's new vs v1:
//
//  • Cache-first: if PrefetchEngine already populated the cache while the
//    user was reading the details page, sources appear INSTANTLY — no spinner.
//
//  • Live streaming: uses StreamProviderEngine.fetchStreaming() channel so
//    the UI shows the first result the moment any provider responds, then
//    keeps appending as more come in.  No waiting for the slowest provider.
//
//  • "Instant play" fast-path: if cache has ≥ 1 result, the best source
//    is shown immediately with a highlighted "▶ Play Best" button.
//
//  • Stale indicator: shows a subtle "Refreshing…" badge when serving
//    stale-while-revalidate results.
// ═════════════════════════════════════════════════════════════════════════════

// ── Language definitions ──────────────────────────────────────────────────────
data class DubOption(val key: String, val label: String, val flag: String, val color: Color)

private val DUB_OPTIONS = listOf(
    DubOption("English",    "English",    "🇺🇸", Color(0xFF1565C0)),
    DubOption("Hindi",      "हिंदी",       "🇮🇳", Color(0xFFFF6F00)),
    DubOption("Tamil",      "Tamil",      "🇮🇳", Color(0xFF6A1B9A)),
    DubOption("Telugu",     "Telugu",     "🇮🇳", Color(0xFF00838F)),
    DubOption("Bengali",    "বাংলা",       "🇧🇩", Color(0xFF2E7D32)),
    DubOption("Korean",     "한국어",       "🇰🇷", Color(0xFFC62828)),
    DubOption("Japanese",   "日本語",       "🇯🇵", Color(0xFF283593)),
    DubOption("Dual Audio", "Dual",       "🎵",  Color(0xFF558B2F)),
)

// ── Fetch state ───────────────────────────────────────────────────────────────
private enum class FetchState { IDLE, LOADING, STREAMING, DONE, ERROR }

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
    val context      = LocalContext.current
    val activity     = context as? Activity
    val scope        = rememberCoroutineScope()
    val movieType    = if (type.equals("MOVIE", true)) MovieType.MOVIE else MovieType.SERIES

    val decodedTitle = remember(title) {
        try { URLDecoder.decode(title, "UTF-8") } catch (_: Exception) { title }
    }

    // ── UI state ──────────────────────────────────────────────────
    var selectedDub    by remember { mutableStateOf(DUB_OPTIONS[0]) }
    var fetchState     by remember { mutableStateOf(FetchState.IDLE) }
    var sources        by remember { mutableStateOf<List<StreamResult>>(emptyList()) }
    var errorMsg       by remember { mutableStateOf<String?>(null) }
    var analysingLabel by remember { mutableStateOf("") }
    var isStale        by remember { mutableStateOf(false) }
    var adLoading      by remember { mutableStateOf(false) }
    var backdropUrl    by remember { mutableStateOf("") }
    var movieDetails   by remember { mutableStateOf<FullMovieDetails?>(null) }

    // Infinite spinner animation
    val inf = rememberInfiniteTransition(label = "spin")
    val spinDeg by inf.animateFloat(
        0f, 360f,
        infiniteRepeatable(tween(1000, easing = LinearEasing)), "d"
    )

    val sourceSites = remember(selectedDub) {
        when (selectedDub.key) {
            "English"    -> listOf("AutoEmbed", "MovieBox", "Showbox", "FlixHQ", "MoviesAPI")
            "Hindi"      -> listOf("VegaMovies", "HdHub4u", "Filmyfly", "KatMovies", "TopMovies")
            "Tamil"      -> listOf("Filmyfly", "VegaMovies", "HdHub4u", "Movies4u", "SkyMoviesHD")
            "Telugu"     -> listOf("Filmyfly", "VegaMovies", "HdHub4u", "MoviezWap")
            "Bengali"    -> listOf("VegaMovies", "HdHub4u", "OgoMovies", "KmMovies", "Joya9tv")
            "Korean"     -> listOf("KissKh", "FlixHQ")
            "Japanese"   -> listOf("HiAnime", "Animetsu", "TokyoInsider")
            "Dual Audio" -> listOf("HdHub4u", "VegaMovies", "KatMovies")
            else         -> listOf("AutoEmbed", "MovieBox", "FlixHQ")
        }
    }

    // ── Load backdrop ─────────────────────────────────────────────
    LaunchedEffect(tmdbId) {
        movieDetails = MovieRepository.getFullDetails(tmdbId, movieType)
        backdropUrl  = movieDetails?.basic?.backdropUrl ?: ""
    }

    // ── Build ProviderRequest ─────────────────────────────────────
    fun buildReq() = ProviderRequest(
        tmdbId   = if (tmdbId > 0) tmdbId else null,
        imdbId   = imdbId.takeIf { it.isNotEmpty() && it != "null" },
        title    = decodedTitle,
        isSeries = movieType == MovieType.SERIES,
        season   = season,
        episode  = episode,
        language = selectedDub.key
    )

    // ── MAIN FETCH — cache-first, then streaming ──────────────────
    LaunchedEffect(selectedDub) {
        sources    = emptyList()
        errorMsg   = null
        isStale    = false
        fetchState = FetchState.LOADING

        val req = buildReq()
        val key = StreamCache.streamKey(req)

        // ── Fast path: check cache FIRST ─────────────────────────
        val cached = StreamCache.getStreams(key)
        if (cached != null) {
            sources    = cached
            fetchState = FetchState.DONE
            return@LaunchedEffect           // ← instant, zero network
        }

        // ── Stale path: show stale while refreshing ───────────────
        val stale = StreamCache.getStaleStreams(key)
        if (stale != null) {
            sources    = stale
            fetchState = FetchState.STREAMING
            isStale    = true
            // Fall through to refresh in background below
        }

        // ── Animate site labels while fetching ────────────────────
        val animJob = scope.launch {
            var i = 0
            while (fetchState == FetchState.LOADING || fetchState == FetchState.STREAMING) {
                analysingLabel = sourceSites[i % sourceSites.size]
                delay(600)
                i++
            }
        }

        // ── Streaming fetch via Channel ───────────────────────────
        try {
            val channel = StreamProviderEngine.fetchStreaming(req)
            var seenUrls = sources.map { it.url }.toMutableSet()

            for (batch in channel) {
                val fresh = batch.filter { it.url !in seenUrls }
                if (fresh.isNotEmpty()) {
                    seenUrls += fresh.map { it.url }
                    sources    = (sources + fresh)
                        .distinctBy { it.url }
                        .sortedWith(compareByDescending { qualityScore(it.quality) })
                    fetchState = FetchState.STREAMING
                    isStale    = false
                }
            }

            fetchState = if (sources.isEmpty()) FetchState.ERROR else FetchState.DONE
            if (sources.isEmpty()) errorMsg = "No sources found for ${selectedDub.label}. Try another language."
        } catch (e: Exception) {
            if (sources.isEmpty()) {
                fetchState = FetchState.ERROR
                errorMsg   = "Search failed: ${e.message}"
            } else {
                fetchState = FetchState.DONE
            }
        } finally {
            animJob.cancel()
            analysingLabel = ""
        }
    }

    // ── Play a source ─────────────────────────────────────────────
    fun playSource(source: StreamResult) {
        if (activity == null) return
        adLoading = true
        AdManager.showInterstitial(activity) {
            adLoading = false
            val encUrl     = URLEncoder.encode(source.url, "UTF-8")
            val encTitle   = URLEncoder.encode(decodedTitle, "UTF-8")
            val encLang    = URLEncoder.encode(source.language.ifEmpty { selectedDub.key }, "UTF-8")
            val encImdb    = URLEncoder.encode(imdbId.ifEmpty { "null" }, "UTF-8")
            val subsJson   = org.json.JSONArray().apply {
                source.subtitles.forEach { sub ->
                    put(org.json.JSONObject().apply {
                        put("url", sub.url); put("title", sub.title)
                        put("language", sub.language); put("mimeType", sub.mimeType)
                    })
                }
            }.toString()
            val encSubs    = URLEncoder.encode(subsJson, "UTF-8")
            val encHeaders = URLEncoder.encode(org.json.JSONObject(source.headers).toString(), "UTF-8")
            navController.navigate(
                "exo_player/$encUrl/$encTitle/${source.quality}/$encLang/$encImdb/$type/$season/$episode/$encSubs/$encHeaders"
            )
        }
    }

    // ─────────────────────────────────────────────────────────────
    //  UI
    // ─────────────────────────────────────────────────────────────
    Box(Modifier.fillMaxSize().background(Color(0xFF06060F))) {

        // Ad loading overlay
        if (adLoading) {
            Box(
                Modifier.fillMaxSize().background(Color.Black.copy(0.88f)),
                contentAlignment = Alignment.Center
            ) { CircularProgressIndicator(color = Color.Cyan, modifier = Modifier.size(48.dp), strokeWidth = 3.dp) }
            return@Box
        }

        Column(Modifier.fillMaxSize()) {

            // ── Header ────────────────────────────────────────────
            Box(Modifier.fillMaxWidth().height(200.dp)) {
                if (backdropUrl.isNotEmpty()) {
                    coil.compose.AsyncImage(
                        model = backdropUrl, contentDescription = null,
                        contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                }
                Box(Modifier.fillMaxSize().background(
                    Brush.verticalGradient(listOf(Color.Black.copy(0.4f), Color(0xFF06060F)))
                ))
                // Back button
                IconButton(
                    onClick  = { navController.popBackStack() },
                    modifier = Modifier.padding(top = 36.dp, start = 8.dp)
                ) {
                    Box(Modifier.size(36.dp).background(Color.Black.copy(0.6f), CircleShape),
                        contentAlignment = Alignment.Center) {
                        Icon(Icons.Default.ArrowBack, "Back", tint = Color.White, modifier = Modifier.size(18.dp))
                    }
                }
                // Title + status badge
                Column(Modifier.align(Alignment.BottomStart).padding(16.dp)) {
                    Text(decodedTitle, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (type != "MOVIE") Text("S${season}E${episode}", color = Color.Gray, fontSize = 12.sp)
                        // Fetch status badge
                        when (fetchState) {
                            FetchState.LOADING -> StatusBadge("Searching…", Color(0xFFFF6F00), spinning = true, spinDeg)
                            FetchState.STREAMING -> if (isStale)
                                StatusBadge("Refreshing…", Color(0xFF1565C0), spinning = true, spinDeg)
                            else
                                StatusBadge("${sources.size} sources", Color(0xFF2E7D32))
                            FetchState.DONE -> StatusBadge("${sources.size} sources found", Color(0xFF2E7D32))
                            FetchState.ERROR -> StatusBadge("No sources", Color(0xFFC62828))
                            else -> {}
                        }
                    }
                }
            }

            // ── Language chips ────────────────────────────────────
            LazyRow(
                contentPadding        = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(DUB_OPTIONS) { dub ->
                    DubLanguageChip(dub, dub.key == selectedDub.key) { selectedDub = dub }
                }
            }

            // ── Source area ───────────────────────────────────────
            when {
                fetchState == FetchState.LOADING && sources.isEmpty() ->
                    AnalysingPlaceholder(selectedDub, analysingLabel)

                fetchState == FetchState.ERROR && sources.isEmpty() ->
                    ErrorState(
                        msg         = errorMsg ?: "No sources found.",
                        onRetry     = { selectedDub = selectedDub.copy() },
                        dubOptions  = DUB_OPTIONS,
                        onDubChange = { selectedDub = it }
                    )

                sources.isNotEmpty() ->
                    SourceResultList(
                        sources      = sources,
                        selectedDub  = selectedDub,
                        decodedTitle = decodedTitle,
                        isStreaming  = fetchState == FetchState.STREAMING || fetchState == FetchState.LOADING,
                        analysingLabel = analysingLabel,
                        onPlay       = { playSource(it) }
                    )
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
//  StatusBadge
// ─────────────────────────────────────────────────────────────────────────────
@Composable
private fun StatusBadge(text: String, color: Color, spinning: Boolean = false, spinDeg: Float = 0f) {
    Row(
        Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(color.copy(0.15f))
            .border(1.dp, color.copy(0.4f), RoundedCornerShape(12.dp))
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        if (spinning) {
            Icon(Icons.Rounded.Refresh, null, tint = color,
                modifier = Modifier.size(10.dp).rotate(spinDeg))
        } else {
            Box(Modifier.size(6.dp).background(color, CircleShape))
        }
        Text(text, color = color, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
    }
}

// ─────────────────────────────────────────────────────────────────────────────
//  DubLanguageChip
// ─────────────────────────────────────────────────────────────────────────────
@Composable
private fun DubLanguageChip(dub: DubOption, selected: Boolean, onClick: () -> Unit) {
    val bg     = if (selected) dub.color.copy(0.22f) else Color(0xFF141420)
    val border = if (selected) dub.color.copy(0.7f)  else Color.White.copy(0.08f)
    val text   = if (selected) dub.color              else Color.Gray
    Row(
        Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(bg)
            .border(1.dp, border, RoundedCornerShape(20.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment     = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        Text(dub.flag, fontSize = 13.sp)
        Text(dub.label, color = text, fontSize = 12.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal)
    }
}

// ─────────────────────────────────────────────────────────────────────────────
//  AnalysingPlaceholder
// ─────────────────────────────────────────────────────────────────────────────
@Composable
private fun AnalysingPlaceholder(dub: DubOption, site: String) {
    Box(Modifier.fillMaxWidth().height(200.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(color = dub.color, modifier = Modifier.size(36.dp), strokeWidth = 2.5.dp)
            Spacer(Modifier.height(14.dp))
            Text("Searching ${dub.label} sources…", color = Color.Gray, fontSize = 13.sp)
            if (site.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                Text("Checking $site", color = Color(0xFF3A3A5A), fontSize = 11.sp)
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
//  ErrorState
// ─────────────────────────────────────────────────────────────────────────────
@Composable
private fun ErrorState(msg: String, onRetry: () -> Unit, dubOptions: List<DubOption>, onDubChange: (DubOption) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(Icons.Rounded.SearchOff, null, tint = Color.Gray, modifier = Modifier.size(40.dp))
        Spacer(Modifier.height(10.dp))
        Text(msg, color = Color.Gray, fontSize = 13.sp, textAlign = TextAlign.Center)
        Spacer(Modifier.height(16.dp))
        Text("Try another language:", color = Color(0xFF4A4A5A), fontSize = 11.sp)
        Spacer(Modifier.height(8.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(dubOptions.filter { it.key != "English" }.take(5)) { dub ->
                OutlinedButton(
                    onClick = { onDubChange(dub) },
                    colors  = ButtonDefaults.outlinedButtonColors(contentColor = dub.color),
                    border  = androidx.compose.foundation.BorderStroke(1.dp, dub.color.copy(0.5f))
                ) { Text("${dub.flag} ${dub.label}", fontSize = 12.sp) }
            }
        }
        Spacer(Modifier.height(12.dp))
        Button(onClick = onRetry, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1A1A2E))) {
            Icon(Icons.Rounded.Refresh, null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text("Retry")
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
//  SourceResultList — live-updating, shows best instantly
// ─────────────────────────────────────────────────────────────────────────────
@Composable
private fun SourceResultList(
    sources:        List<StreamResult>,
    selectedDub:    DubOption,
    decodedTitle:   String,
    isStreaming:    Boolean,
    analysingLabel: String,
    onPlay:         (StreamResult) -> Unit
) {
    val sorted = remember(sources, selectedDub) {
        sources.sortedWith(
            compareByDescending<StreamResult> {
                it.language.equals(selectedDub.key, true)
            }.thenByDescending {
                it.type == StreamType.HLS
            }.thenByDescending {
                qualityScore(it.quality)
            }
        )
    }

    LazyColumn(
        contentPadding      = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // ── Best source — big play button ─────────────────────────
        item {
            val best = sorted.first()
            Button(
                onClick  = { onPlay(best) },
                modifier = Modifier.fillMaxWidth().height(58.dp),
                colors   = ButtonDefaults.buttonColors(containerColor = selectedDub.color.copy(0.18f)),
                shape    = RoundedCornerShape(12.dp),
                border   = androidx.compose.foundation.BorderStroke(1.5.dp, selectedDub.color.copy(0.6f))
            ) {
                Icon(Icons.Rounded.PlayCircle, null, tint = selectedDub.color, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(10.dp))
                Column(horizontalAlignment = Alignment.Start) {
                    Text(
                        buildString {
                            append(decodedTitle)
                            if (selectedDub.key != "English") append(" [${selectedDub.label}]")
                        },
                        color = selectedDub.color, fontSize = 14.sp, fontWeight = FontWeight.Bold,
                        maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        "${best.quality} • ${best.type} • ${best.source}",
                        color = selectedDub.color.copy(0.6f), fontSize = 10.sp
                    )
                }
            }
        }

        // ── "More sources" header with live badge ─────────────────
        if (sorted.size > 1) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(top = 6.dp, bottom = 2.dp)) {
                    Text("More sources", color = Color(0xFF4A4A5A), fontSize = 11.sp)
                    if (isStreaming && analysingLabel.isNotEmpty()) {
                        Text("• checking $analysingLabel…", color = Color(0xFF2E4A2E), fontSize = 11.sp)
                    }
                }
            }
            items(sorted.drop(1).take(9)) { source ->
                AnimatedVisibility(visible = true, enter = slideInVertically { it } + fadeIn()) {
                    SourceResultCard(source, selectedDub.color) { onPlay(source) }
                }
            }
        }

        // Live loading indicator at bottom
        if (isStreaming) {
            item {
                Row(Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(color = selectedDub.color, modifier = Modifier.size(14.dp), strokeWidth = 1.5.dp)
                    Spacer(Modifier.width(8.dp))
                    Text("Finding more sources…", color = Color(0xFF3A3A5A), fontSize = 11.sp)
                }
            }
        }
        item { Spacer(Modifier.height(32.dp)) }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
//  SourceResultCard
// ─────────────────────────────────────────────────────────────────────────────
@Composable
private fun SourceResultCard(source: StreamResult, accentColor: Color, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(Color(0xFF0E0E18), RoundedCornerShape(10.dp))
            .border(1.dp, Color.White.copy(0.05f), RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val icon: ImageVector = when (source.type) {
            StreamType.HLS  -> Icons.Rounded.PlayCircle
            StreamType.DASH -> Icons.Rounded.Stream
            else            -> Icons.Rounded.VideoFile
        }
        val iconTint = when (source.type) {
            StreamType.HLS  -> accentColor
            StreamType.DASH -> Color(0xFF80DEEA)
            else            -> Color.Gray
        }
        Icon(icon, null, tint = iconTint, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                source.label.ifEmpty { "${source.quality} • ${source.source}" },
                color = Color.White, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis
            )
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(source.type.name, color = Color.Gray, fontSize = 10.sp)
                if (source.language.isNotEmpty() && source.language != "English") {
                    Text("•", color = Color(0xFF3A3A4A), fontSize = 10.sp)
                    Text(source.language, color = accentColor.copy(0.75f), fontSize = 10.sp)
                }
            }
        }
        QualityBadge(source.quality, accentColor)
    }
}

@Composable
private fun QualityBadge(quality: String, accentColor: Color) {
    val (bg, fg) = when {
        quality.contains("4K",   true) -> Color(0xFF1A237E) to Color(0xFF82B1FF)
        quality.contains("1080", true) -> Color(0xFF0D2E1B) to accentColor
        quality.contains("720",  true) -> Color(0xFF1A2A1A) to Color(0xFFA5D6A7)
        else                            -> Color(0xFF1A1A1A) to Color.Gray
    }
    Box(Modifier.background(bg, RoundedCornerShape(6.dp)).padding(horizontal = 7.dp, vertical = 3.dp)) {
        Text(quality, color = fg, fontSize = 10.sp, fontWeight = FontWeight.Bold)
    }
}

private fun qualityScore(q: String): Int = when {
    q.contains("4K",   true) || q.contains("2160", true) -> 40
    q.contains("1080", true) -> 30
    q.contains("720",  true) -> 20
    q.contains("HD",   true) -> 15
    q.contains("480",  true) -> 10
    else -> 1
}
