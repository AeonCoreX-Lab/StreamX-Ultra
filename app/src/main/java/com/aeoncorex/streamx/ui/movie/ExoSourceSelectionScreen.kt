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
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import coil.compose.AsyncImage
import com.aeoncorex.streamx.ads.AdManager
import com.aeoncorex.streamx.streaming.ProviderRequest
import com.aeoncorex.streamx.streaming.StreamCache
import com.aeoncorex.streamx.streaming.StreamProviderEngine
import com.aeoncorex.streamx.streaming.StreamResult
import com.aeoncorex.streamx.streaming.StreamType
import com.aeoncorex.streamx.streaming.SubtitleAddonClient  // ← IMPORT
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLDecoder
import java.net.URLEncoder

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

private enum class FetchState { IDLE, LOADING, STREAMING, DONE, ERROR, NOT_SIGNED_IN }

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

    var selectedDub    by remember { mutableStateOf(DUB_OPTIONS[0]) }
    var fetchState     by remember { mutableStateOf(FetchState.IDLE) }
    var sources        by remember { mutableStateOf<List<StreamResult>>(emptyList()) }
    var errorMsg       by remember { mutableStateOf<String?>(null) }
    var analysingLabel by remember { mutableStateOf("") }
    var isStale        by remember { mutableStateOf(false) }
    var adLoading      by remember { mutableStateOf(false) }
    var backdropUrl    by remember { mutableStateOf("") }

    // ── Subtitle addon results ────────────────────────────────────────────────
    // Fetched in parallel with streams using SubtitleAddonClient
    var addonSubtitles by remember { mutableStateOf<List<SubtitleAddonClient.SubtitleTrack>>(emptyList()) }

    val inf = rememberInfiniteTransition(label = "spin")
    val spinDeg by inf.animateFloat(
        0f, 360f, infiniteRepeatable(tween(1000, easing = LinearEasing)), "d"
    )

    // Display labels for the spinner while streams are resolving. These are
    // the fixed providers the streamx-stream-resolver Worker resolves
    // through — no on-device addon installation needed anymore (see
    // WorkerStreamProviderEngine).
    val sourceSites = remember {
        listOf("autoEmbed", "animetsu", "flixhq", "multi")
    }

    LaunchedEffect(tmdbId) {
        MovieRepository.getFullDetails(tmdbId, movieType)?.let {
            backdropUrl = it.basic?.backdropUrl ?: ""
        }
    }

    fun buildReq() = ProviderRequest(
        tmdbId   = if (tmdbId > 0) tmdbId else null,
        imdbId   = imdbId.takeIf { it.isNotEmpty() && it != "null" },
        title    = decodedTitle,
        isSeries = movieType == MovieType.SERIES,
        season   = season,
        episode  = episode,
        language = selectedDub.key
    )

    // ── MAIN FETCH ────────────────────────────────────────────────────────────
    LaunchedEffect(selectedDub) {
        sources       = emptyList()
        errorMsg      = null
        isStale       = false
        addonSubtitles = emptyList()

        // The Worker requires a Firebase ID token on every /resolve call
        // (see StreamResolverClient / streamx-stream-resolver's auth.js) —
        // without it every provider silently returns empty, which would
        // otherwise show a confusing "No sources found" instead of
        // pointing at the actual fix.
        if (FirebaseAuth.getInstance().currentUser == null) {
            fetchState = FetchState.NOT_SIGNED_IN
            return@LaunchedEffect
        }

        fetchState = FetchState.LOADING
        val req = buildReq()
        val key = StreamCache.streamKey(req)

        val cached = StreamCache.getStreams(key)
        if (cached != null) { sources = cached; fetchState = FetchState.DONE }
        val stale = StreamCache.getStaleStreams(key)
        if (stale != null) { sources = stale; fetchState = FetchState.STREAMING; isStale = true }

        // ── Spinner label animation ───────────────────────────────────────────
        val animJob = scope.launch {
            var i = 0
            while (fetchState == FetchState.LOADING || fetchState == FetchState.STREAMING) {
                analysingLabel = sourceSites[i % sourceSites.size]; delay(600); i++
            }
        }

        // ── Fetch streams + subtitle addon results in PARALLEL ────────────────
        val subtitleJob = async {
            val cleanImdb = imdbId.takeIf { it.startsWith("tt") } ?: ""
            if (cleanImdb.isEmpty()) return@async
            try {
                val tracks = SubtitleAddonClient.fetchSubtitles(
                    imdbId   = cleanImdb,
                    language = selectedDub.key,
                    isSeries = movieType == MovieType.SERIES,
                    season   = season,
                    episode  = episode
                )
                addonSubtitles = tracks
            } catch (e: Exception) { /* subtitle failure is non-critical */ }
        }

        try {
            val channel  = StreamProviderEngine.fetchStreaming(req)
            var seenUrls = sources.map { it.url }.toMutableSet()

            for (batch in channel) {
                val fresh = batch.filter { it.url !in seenUrls }
                if (fresh.isNotEmpty()) {
                    seenUrls  += fresh.map { it.url }
                    sources    = (sources + fresh)
                        .distinctBy { it.url }
                        .sortedWith(compareByDescending { qualityScore(it.quality) })
                    fetchState = FetchState.STREAMING
                    isStale    = false
                }
            }

            fetchState = if (sources.isEmpty()) FetchState.ERROR else FetchState.DONE
            if (sources.isEmpty()) errorMsg = "No sources found for ${selectedDub.label}."

        } catch (e: Exception) {
            if (sources.isEmpty()) { fetchState = FetchState.ERROR; errorMsg = e.message }
            else fetchState = FetchState.DONE
        } finally {
            animJob.cancel()
            analysingLabel = ""
            subtitleJob.await() // wait for subtitle fetch to complete
        }
    }

    // ── Play ──────────────────────────────────────────────────────────────────
    fun playSource(source: StreamResult) {
        if (activity == null) return
        adLoading = true
        AdManager.showInterstitial(activity) {
            adLoading = false

            // Build combined subtitle list:
            //   1. Subtitles from stream result (provider-embedded)
            //   2. Subtitles from HTTP subtitle addons (SubtitleAddonClient)
            val allSubtitles = buildList {
                // Provider subtitles
                addAll(source.subtitles.map { sub ->
                    JSONObject().apply {
                        put("url",      sub.url)
                        put("title",    sub.title.ifEmpty { sub.language })
                        put("language", sub.language)
                        put("mimeType", sub.mimeType)
                    }
                })
                // Subtitle addon subtitles (OpenSubtitles addon, SubSource, etc.)
                addonSubtitles.take(5).forEach { track ->
                    JSONObject().apply {
                        put("url",      track.url)
                        put("title",    track.title.ifEmpty { track.language })
                        put("language", track.language)
                        put("mimeType", track.mimeType)
                    }.also { add(it) }
                }
            }

            val subsJson    = JSONArray(allSubtitles).toString()
            val headersJson = JSONObject(source.headers).toString()

            val encUrl     = URLEncoder.encode(source.url,       "UTF-8")
            val encTitle   = URLEncoder.encode(decodedTitle,     "UTF-8")
            val encLang    = URLEncoder.encode(source.language.ifEmpty { selectedDub.key }, "UTF-8")
            val encImdb    = URLEncoder.encode(imdbId.ifEmpty { "null" }, "UTF-8")
            val encSubs    = URLEncoder.encode(subsJson,         "UTF-8")
            val encHeaders = URLEncoder.encode(headersJson,      "UTF-8")

            navController.navigate(
                "exo_player/$encUrl/$encTitle/${source.quality}/$encLang/$encImdb/$type/$season/$episode/$encSubs/$encHeaders"
            )
        }
    }

    // ── UI ────────────────────────────────────────────────────────────────────
    Box(Modifier.fillMaxSize().background(Color(0xFF06060F))) {

        if (adLoading) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(0.88f)),
                contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Color.Cyan,
                    modifier = Modifier.size(48.dp), strokeWidth = 3.dp)
            }
            return@Box
        }

        Column(Modifier.fillMaxSize()) {

            // ── Header backdrop ───────────────────────────────────────────────
            Box(Modifier.fillMaxWidth().height(200.dp)) {
                if (backdropUrl.isNotEmpty()) {
                    AsyncImage(model = backdropUrl, contentDescription = null,
                        contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                }
                Box(Modifier.fillMaxSize().background(
                    Brush.verticalGradient(listOf(Color.Black.copy(0.4f), Color(0xFF06060F)))))
                IconButton(onClick = { navController.popBackStack() },
                    modifier = Modifier.padding(top = 36.dp, start = 8.dp)) {
                    Box(Modifier.size(36.dp).background(Color.Black.copy(0.6f), CircleShape),
                        contentAlignment = Alignment.Center) {
                        Icon(Icons.Default.ArrowBack, "Back", tint = Color.White,
                            modifier = Modifier.size(18.dp))
                    }
                }
                Column(Modifier.align(Alignment.BottomStart).padding(16.dp)) {
                    Text(decodedTitle, color = Color.White, fontSize = 18.sp,
                        fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (type != "MOVIE") Text("S${season}E${episode}", color = Color.Gray, fontSize = 12.sp)
                        StatusBadge(fetchState, sources.size, spinDeg)
                        // Show subtitle addon count if available
                        if (addonSubtitles.isNotEmpty()) {
                            SubtitleBadge(addonSubtitles.size)
                        }
                    }
                }
            }

            // ── Language chips ────────────────────────────────────────────────
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(DUB_OPTIONS) { dub ->
                    DubChip(dub, dub.key == selectedDub.key) { selectedDub = dub }
                }
            }

            // ── Content ───────────────────────────────────────────────────────
            when {
                fetchState == FetchState.NOT_SIGNED_IN ->
                    NotSignedInState { navController.navigate("auth") }
                fetchState == FetchState.LOADING && sources.isEmpty() ->
                    LoadingState(selectedDub, analysingLabel)
                fetchState == FetchState.ERROR && sources.isEmpty() ->
                    ErrorState(errorMsg ?: "No sources found.", DUB_OPTIONS) { selectedDub = it }
                sources.isNotEmpty() ->
                    SourceList(
                        sources        = sources,
                        selectedDub    = selectedDub,
                        decodedTitle   = decodedTitle,
                        isStreaming    = fetchState == FetchState.STREAMING || fetchState == FetchState.LOADING,
                        analysingLabel = analysingLabel,
                        subtitleCount  = addonSubtitles.size,
                        onPlay         = { playSource(it) }
                    )
            }
        }
    }
}

// ── SubtitleBadge ─────────────────────────────────────────────────────────────
@Composable
private fun SubtitleBadge(count: Int) {
    Row(
        Modifier.clip(RoundedCornerShape(12.dp))
            .background(Color(0xFF0D1B0D))
            .border(1.dp, Color(0xFF2E7D32).copy(.4f), RoundedCornerShape(12.dp))
            .padding(horizontal = 8.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Icon(Icons.Rounded.ClosedCaption, null, tint = Color(0xFF81C784),
            modifier = Modifier.size(10.dp))
        Text("$count subs", color = Color(0xFF81C784), fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold)
    }
}

// ── StatusBadge ───────────────────────────────────────────────────────────────
@Composable
private fun StatusBadge(state: FetchState, count: Int, spinDeg: Float) {
    val (text, color, spinning) = when (state) {
        FetchState.LOADING   -> Triple("Searching…",         Color(0xFFFF6F00), true)
        FetchState.STREAMING -> Triple("$count sources",      Color(0xFF1565C0), true)
        FetchState.DONE      -> Triple("$count sources found", Color(0xFF2E7D32), false)
        FetchState.ERROR     -> Triple("No sources",          Color(0xFFC62828), false)
        FetchState.NOT_SIGNED_IN -> Triple("Sign in required",   Color(0xFFFF6F00), false)
        else                 -> return
    }
    Row(
        Modifier.clip(RoundedCornerShape(12.dp)).background(color.copy(.15f))
            .border(1.dp, color.copy(.4f), RoundedCornerShape(12.dp))
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        if (spinning) Icon(Icons.Rounded.Refresh, null, tint = color,
            modifier = Modifier.size(10.dp).rotate(spinDeg))
        else Box(Modifier.size(6.dp).background(color, CircleShape))
        Text(text, color = color, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
    }
}

// ── DubChip ───────────────────────────────────────────────────────────────────
@Composable
private fun DubChip(dub: DubOption, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.clip(RoundedCornerShape(20.dp))
            .background(if (selected) dub.color.copy(.22f) else Color(0xFF141420))
            .border(1.dp, if (selected) dub.color.copy(.7f) else Color.White.copy(.08f), RoundedCornerShape(20.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        Text(dub.flag, fontSize = 13.sp)
        Text(dub.label, color = if (selected) dub.color else Color.Gray, fontSize = 12.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal)
    }
}

// ── NotSignedInState ──────────────────────────────────────────────────────────
@Composable
private fun NotSignedInState(onSignIn: () -> Unit) {
    Box(Modifier.fillMaxWidth().padding(32.dp), Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Rounded.ExtensionOff, null, tint = Color(0xFFFF6F00),
                modifier = Modifier.size(52.dp))
            Spacer(Modifier.height(16.dp))
            Text("Sign in required", color = Color.White, fontSize = 16.sp,
                fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Text("Streaming sources are fetched through your account.\nSign in to continue.",
                color = Color.Gray, fontSize = 13.sp, textAlign = TextAlign.Center)
            Spacer(Modifier.height(20.dp))
            Button(onClick = onSignIn,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1A3A1A))) {
                Icon(Icons.Rounded.Extension, null, tint = Color(0xFF81C784),
                    modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Sign In", color = Color(0xFF81C784))
            }
        }
    }
}

// ── LoadingState ──────────────────────────────────────────────────────────────
@Composable
private fun LoadingState(dub: DubOption, site: String) {
    Box(Modifier.fillMaxWidth().height(200.dp), Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(color = dub.color,
                modifier = Modifier.size(36.dp), strokeWidth = 2.5.dp)
            Spacer(Modifier.height(14.dp))
            Text("Searching ${dub.label} sources…", color = Color.Gray, fontSize = 13.sp)
            if (site.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                Text("Checking $site", color = Color(0xFF3A3A5A), fontSize = 11.sp)
            }
        }
    }
}

// ── ErrorState ────────────────────────────────────────────────────────────────
@Composable
private fun ErrorState(msg: String, opts: List<DubOption>, onDub: (DubOption) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(Icons.Rounded.SearchOff, null, tint = Color.Gray, modifier = Modifier.size(40.dp))
        Spacer(Modifier.height(10.dp))
        Text(msg, color = Color.Gray, fontSize = 13.sp, textAlign = TextAlign.Center)
        Spacer(Modifier.height(16.dp))
        Text("Try another language:", color = Color(0xFF4A4A5A), fontSize = 11.sp)
        Spacer(Modifier.height(8.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(opts.filter { it.key != "English" }.take(5)) { dub ->
                OutlinedButton(onClick = { onDub(dub) },
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = dub.color),
                    border = androidx.compose.foundation.BorderStroke(1.dp, dub.color.copy(0.5f))) {
                    Text("${dub.flag} ${dub.label}", fontSize = 12.sp)
                }
            }
        }
    }
}

// ── SourceList ────────────────────────────────────────────────────────────────
@Composable
private fun SourceList(
    sources:        List<StreamResult>,
    selectedDub:    DubOption,
    decodedTitle:   String,
    isStreaming:    Boolean,
    analysingLabel: String,
    subtitleCount:  Int,
    onPlay:         (StreamResult) -> Unit
) {
    val sorted = remember(sources, selectedDub) {
        sources.sortedWith(
            compareByDescending<StreamResult> { it.language.equals(selectedDub.key, true) }
                .thenByDescending { it.type == StreamType.HLS }
                .thenByDescending { qualityScore(it.quality) }
        )
    }

    LazyColumn(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {

        // Play best button
        item {
            val best = sorted.first()
            Button(onClick = { onPlay(best) },
                modifier = Modifier.fillMaxWidth().height(58.dp),
                colors   = ButtonDefaults.buttonColors(containerColor = selectedDub.color.copy(.18f)),
                shape    = RoundedCornerShape(12.dp),
                border   = androidx.compose.foundation.BorderStroke(1.5.dp, selectedDub.color.copy(.6f))) {
                Icon(Icons.Rounded.PlayCircle, null, tint = selectedDub.color,
                    modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(10.dp))
                Column(horizontalAlignment = Alignment.Start) {
                    Text(decodedTitle, color = selectedDub.color, fontSize = 14.sp,
                        fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("${best.quality} • ${best.type}" +
                         if (subtitleCount > 0) " • $subtitleCount subtitles" else "",
                        color = selectedDub.color.copy(.6f), fontSize = 10.sp)
                }
            }
        }

        if (sorted.size > 1) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(top = 6.dp, bottom = 2.dp)) {
                    Text("More sources", color = Color(0xFF4A4A5A), fontSize = 11.sp)
                    if (isStreaming && analysingLabel.isNotEmpty())
                        Text("• checking $analysingLabel…", color = Color(0xFF2E4A2E), fontSize = 11.sp)
                }
            }
            items(sorted.drop(1).take(9)) { source ->
                SourceCard(source, selectedDub.color) { onPlay(source) }
            }
        }

        if (isStreaming) {
            item {
                Row(Modifier.fillMaxWidth().padding(8.dp),
                    Arrangement.Center, Alignment.CenterVertically) {
                    CircularProgressIndicator(color = selectedDub.color,
                        modifier = Modifier.size(14.dp), strokeWidth = 1.5.dp)
                    Spacer(Modifier.width(8.dp))
                    Text("Finding more…", color = Color(0xFF3A3A5A), fontSize = 11.sp)
                }
            }
        }
        item { Spacer(Modifier.height(32.dp)) }
    }
}

// ── SourceCard ────────────────────────────────────────────────────────────────
@Composable
private fun SourceCard(source: StreamResult, accent: Color, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth()
        .background(Color(0xFF0E0E18), RoundedCornerShape(10.dp))
        .border(1.dp, Color.White.copy(.05f), RoundedCornerShape(10.dp))
        .clickable(onClick = onClick)
        .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Icon(
            when (source.type) {
                StreamType.HLS  -> Icons.Rounded.PlayCircle
                StreamType.DASH -> Icons.Rounded.Stream
                else            -> Icons.Rounded.VideoFile
            }, null,
            tint = when (source.type) {
                StreamType.HLS  -> accent
                StreamType.DASH -> Color(0xFF80DEEA)
                else            -> Color.Gray
            }, modifier = Modifier.size(22.dp)
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(source.label.ifEmpty { "${source.quality} • ${source.source}" },
                color = Color.White, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(source.type.name, color = Color.Gray, fontSize = 10.sp)
        }
        val (bg, fg) = when {
            source.quality.contains("4K",   true) -> Color(0xFF1A237E) to Color(0xFF82B1FF)
            source.quality.contains("1080", true) -> Color(0xFF0D2E1B) to accent
            source.quality.contains("720",  true) -> Color(0xFF1A2A1A) to Color(0xFFA5D6A7)
            else -> Color(0xFF1A1A1A) to Color.Gray
        }
        Box(Modifier.background(bg, RoundedCornerShape(6.dp)).padding(horizontal = 7.dp, vertical = 3.dp)) {
            Text(source.quality, color = fg, fontSize = 10.sp, fontWeight = FontWeight.Bold)
        }
    }
}

private fun qualityScore(q: String): Int = when {
    q.contains("4K", true) || q.contains("2160", true) -> 40
    q.contains("1080", true) -> 30; q.contains("720", true) -> 20
    q.contains("HD", true)   -> 15; q.contains("480", true) -> 10
    else -> 1
}
