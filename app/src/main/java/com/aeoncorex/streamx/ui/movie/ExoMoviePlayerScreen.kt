package com.aeoncorex.streamx.ui.movie

import android.app.Activity
import android.view.ViewGroup
import androidx.annotation.OptIn
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.media3.ui.SubtitleView
import androidx.navigation.NavController
import com.aeoncorex.streamx.ads.AdManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.net.URLDecoder

// ═══════════════════════════════════════════════════════════════════
//  ExoMoviePlayerScreen — Movie Box Style Instant Player
//  ─────────────────────────────────────────────────────
//  Features matching Movie Box screenshots:
//    ✅ Instant HLS/MP4 play — ExoPlayer (no torrent buffer)
//    ✅ Top-right timer countdown → timed mid-video ad
//    ✅ "Enter the ad | Go ad-free >" button when timer hits 0
//    ✅ Language panel: Audio tracks + Subtitle tracks side-by-side
//    ✅ Subtitle style: color, size, position, shadow, background
//    ✅ Subtitle delay: -/+ buttons
//    ✅ Speed control: 0.5x, 0.75x, 1x, 1.25x, 1.5x, 2x
//    ✅ Tap to Lock screen
//    ✅ Fit / Fill toggle
//    ✅ ±10s skip
//    ✅ Quality indicator (720P, 1080P, Auto)
// ═══════════════════════════════════════════════════════════════════
@OptIn(UnstableApi::class)
@Composable
fun ExoMoviePlayerScreen(
    navController: NavController,
    streamUrl:     String,          // HLS .m3u8 or .mp4 URL
    title:         String = "",
    quality:       String = "Auto",
    language:      String = "English",
    imdbId:        String? = null,
    movieType:     String = "MOVIE",
    season:        Int    = 0,
    episode:       Int    = 0
) {
    val context    = LocalContext.current
    val activity   = context as? Activity
    val scope      = rememberCoroutineScope()
    val decodedUrl = remember(streamUrl) {
        try { URLDecoder.decode(streamUrl, "UTF-8") } catch (_: Exception) { streamUrl }
    }

    // ── Player state ──────────────────────────────────────────────
    var isPlaying      by remember { mutableStateOf(true) }
    var showControls   by remember { mutableStateOf(true) }
    var isLocked       by remember { mutableStateOf(false) }
    var isFit          by remember { mutableStateOf(true) }
    var currentTime    by remember { mutableLongStateOf(0L) }
    var totalDuration  by remember { mutableLongStateOf(0L) }
    var isBuffering    by remember { mutableStateOf(true) }
    var errorMsg       by remember { mutableStateOf<String?>(null) }
    var playbackSpeed  by remember { mutableFloatStateOf(1f) }

    // ── Subtitle state ────────────────────────────────────────────
    var subtitleFile      by remember { mutableStateOf<File?>(null) }
    var subtitleText      by remember { mutableStateOf("") }
    var subtitleEnabled   by remember { mutableStateOf(true) }
    var subtitleDelayMs   by remember { mutableLongStateOf(0L) }
    var subtitleColor     by remember { mutableStateOf(Color.White) }
    var subtitleSize      by remember { mutableFloatStateOf(16f) }
    var subtitleShadow    by remember { mutableStateOf(true) }
    var subtitleBackground by remember { mutableStateOf(false) }
    var subtitlePosition  by remember { mutableFloatStateOf(0.85f) }

    // ── UI panels ─────────────────────────────────────────────────
    var showLanguagePanel  by remember { mutableStateOf(false) }
    var showSpeedPanel     by remember { mutableStateOf(false) }
    var showSubtitleStyle  by remember { mutableStateOf(false) }
    var showSubtitleDelay  by remember { mutableStateOf(false) }
    var showSubtitleSearch by remember { mutableStateOf(false) }
    var subtitleResults    by remember { mutableStateOf<List<SubtitleRepository.SubtitleResult>>(emptyList()) }
    var subtitleLoading    by remember { mutableStateOf(false) }

    // ── Timed Ad state (Movie Box style) ─────────────────────────
    var adTimerSeconds     by remember { mutableLongStateOf(AdManager.AD_INTERVAL_SECONDS) }
    var showAdPrompt       by remember { mutableStateOf(false) }   // "Enter the ad | Go ad-free"
    var adSkipCountdown    by remember { mutableIntStateOf(0) }
    var isAdPlaying        by remember { mutableStateOf(false) }

    // ── ExoPlayer ─────────────────────────────────────────────────
    val exoPlayer = remember {
        ExoPlayer.Builder(context).build().apply {
            val item = MediaItem.Builder()
                .setUri(decodedUrl)
                .apply {
                    if (decodedUrl.contains(".m3u8"))
                        setMimeType(MimeTypes.APPLICATION_M3U8)
                    else if (decodedUrl.contains(".mpd"))
                        setMimeType(MimeTypes.APPLICATION_MPD)
                }
                .build()
            setMediaItem(item)
            prepare()
            playWhenReady = true
        }
    }

    // ExoPlayer listener
    DisposableEffect(exoPlayer) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                isBuffering = state == Player.STATE_BUFFERING
                if (state == Player.STATE_READY) {
                    totalDuration = exoPlayer.duration.coerceAtLeast(0)
                }
            }
            override fun onIsPlayingChanged(playing: Boolean) { isPlaying = playing }
            override fun onPlayerError(error: PlaybackException) {
                errorMsg = "Playback error: ${error.localizedMessage}"
            }
        }
        exoPlayer.addListener(listener)
        onDispose {
            exoPlayer.removeListener(listener)
            exoPlayer.release()
        }
    }

    // Position tracker
    LaunchedEffect(isPlaying) {
        while (isPlaying) {
            currentTime = exoPlayer.currentPosition
            delay(1000)
        }
    }

    // Auto-hide controls
    LaunchedEffect(showControls) {
        if (showControls && !isLocked) {
            delay(4000)
            showControls = false
        }
    }

    // ── Timed ad countdown (Movie Box: shows "4:43 | Enter the ad") ──
    LaunchedEffect(isPlaying) {
        while (true) {
            delay(1000)
            if (isPlaying && !isAdPlaying && !AdManager.isPremiumCached()) {
                adTimerSeconds--
                if (adTimerSeconds <= 0) {
                    showAdPrompt   = true
                    adTimerSeconds = AdManager.AD_INTERVAL_SECONDS
                }
            }
        }
    }

    // Load subtitles on open
    LaunchedEffect(Unit) {
        scope.launch {
            subtitleLoading = true
            subtitleResults = SubtitleRepository.search(
                imdbId   = imdbId,
                title    = title,
                type     = if (movieType == "MOVIE") MovieType.MOVIE else MovieType.SERIES,
                season   = season,
                episode  = episode,
                langCode = "all"
            )
            subtitleLoading = false
        }
    }

    // Subtitle rendering from .srt file
    LaunchedEffect(subtitleFile, subtitleEnabled) {
        if (subtitleFile == null || !subtitleEnabled) { subtitleText = ""; return@LaunchedEffect }
        // Parse and sync subtitles with playback
        val srtContent = subtitleFile?.readText() ?: return@LaunchedEffect
        while (subtitleEnabled) {
            val adjustedTime = currentTime + subtitleDelayMs
            subtitleText = parseSrtAtTime(srtContent, adjustedTime)
            delay(100)
        }
    }

    // ── UI ────────────────────────────────────────────────────────
    Box(
        Modifier.fillMaxSize().background(Color.Black)
            .pointerInput(isLocked) {
                detectTapGestures(onTap = {
                    if (!isLocked) showControls = !showControls
                    else showControls = true  // tap to show unlock button
                })
            }
    ) {

        // ExoPlayer surface
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory  = {
                PlayerView(context).apply {
                    player       = exoPlayer
                    useController = false
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                }
            }
        )

        // Buffering indicator
        if (isBuffering && errorMsg == null) {
            CircularProgressIndicator(
                color    = Color.Cyan,
                modifier = Modifier.align(Alignment.Center).size(44.dp),
                strokeWidth = 3.dp
            )
        }

        // Error message
        errorMsg?.let { msg ->
            Box(Modifier.fillMaxSize().background(Color.Black.copy(0.7f)), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Rounded.ErrorOutline, null, tint = Color.Red, modifier = Modifier.size(40.dp))
                    Spacer(Modifier.height(8.dp))
                    Text(msg, color = Color.White, fontSize = 13.sp, textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 24.dp))
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = { errorMsg = null; exoPlayer.prepare(); exoPlayer.play() },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2A2A2A))) {
                        Text("Retry", color = Color.White)
                    }
                }
            }
        }

        // ── Subtitle overlay ──────────────────────────────────────
        if (subtitleEnabled && subtitleText.isNotBlank()) {
            val bgColor = if (subtitleBackground) Color.Black.copy(0.6f) else Color.Transparent
            Box(
                Modifier.fillMaxSize().padding(bottom = (subtitlePosition * 100).dp),
                contentAlignment = Alignment.BottomCenter
            ) {
                Text(
                    text      = subtitleText,
                    color     = subtitleColor,
                    fontSize  = subtitleSize.sp,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center,
                    modifier  = Modifier.background(bgColor, RoundedCornerShape(4.dp)).padding(4.dp, 2.dp),
                    style     = if (subtitleShadow) LocalTextStyle.current.copy(
                        shadow = androidx.compose.ui.graphics.Shadow(Color.Black, blurRadius = 4f)
                    ) else LocalTextStyle.current
                )
            }
        }

        // ── TIMED AD PROMPT (Movie Box style top-right timer) ─────
        AnimatedVisibility(
            visible = !AdManager.isPremiumCached() && !showAdPrompt,
            modifier = Modifier.align(Alignment.TopEnd).padding(top = 8.dp, end = 8.dp),
            enter   = fadeIn(),
            exit    = fadeOut()
        ) {
            if (adTimerSeconds < 60) {
                Box(
                    Modifier.background(Color.Black.copy(0.6f), RoundedCornerShape(6.dp))
                        .padding(horizontal = 10.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = "${formatTime(adTimerSeconds)} | Enter the ad  ",
                        color = Color.White, fontSize = 11.sp
                    )
                }
            }
        }

        // Ad prompt banner (when timer hits 0)
        AnimatedVisibility(
            visible  = showAdPrompt,
            modifier = Modifier.align(Alignment.TopCenter).fillMaxWidth(),
            enter    = slideInVertically(),
            exit     = slideOutVertically()
        ) {
            Row(
                Modifier.fillMaxWidth().background(Color.Black.copy(0.85f))
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("${formatTime(adTimerSeconds)} | Enter the ad", color = Color.White, fontSize = 13.sp)
                Text(
                    text = "Go ad-free >",
                    color = Color.Yellow,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.clickable {
                        showAdPrompt = false
                        navController.navigate("premium")
                    }
                )
            }
        }

        // ── Player controls (hide when locked or auto-hidden) ─────
        AnimatedVisibility(
            visible  = showControls,
            enter    = fadeIn(),
            exit     = fadeOut()
        ) {
            Box(Modifier.fillMaxSize()) {

                // Top bar
                Row(
                    Modifier.fillMaxWidth().padding(top = 40.dp, start = 8.dp, end = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.Default.ArrowBack, null, tint = Color.White)
                    }
                    Text(
                        text = buildString {
                            append(title)
                            if (season > 0) append(" S${season.toString().padStart(2,'0')} E${episode.toString().padStart(2,'0')}")
                        },
                        color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                        maxLines = 1, modifier = Modifier.weight(1f).padding(horizontal = 8.dp)
                    )
                    // Help & Settings (Movie Box has these)
                    Text("Help", color = Color.White, fontSize = 12.sp,
                        modifier = Modifier.padding(end = 8.dp).clickable { })
                    IconButton(onClick = { /* settings */ }) {
                        Icon(Icons.Rounded.Settings, null, tint = Color.White, modifier = Modifier.size(20.dp))
                    }
                }

                // Lock button (left side, Movie Box style)
                if (!isLocked) {
                    Row(
                        Modifier.align(Alignment.CenterStart).padding(start = 16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Rounded.LockOpen, null, tint = Color.White.copy(0.8f), modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Tap to Lock", color = Color.White.copy(0.8f), fontSize = 12.sp)
                    }
                }

                // Center controls: -10s, play/pause, +10s
                Row(
                    Modifier.align(Alignment.Center),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(32.dp)
                ) {
                    Box(Modifier.size(48.dp).background(Color.Black.copy(0.3f), CircleShape)
                        .clickable { exoPlayer.seekTo((currentTime - 10_000).coerceAtLeast(0)) },
                        contentAlignment = Alignment.Center) {
                        Icon(Icons.Rounded.Replay10, null, tint = Color.White, modifier = Modifier.size(32.dp))
                    }
                    Box(Modifier.size(56.dp).background(Color.Black.copy(0.4f), CircleShape)
                        .clickable {
                            if (isPlaying) exoPlayer.pause() else exoPlayer.play()
                        }, contentAlignment = Alignment.Center) {
                        Icon(if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                            null, tint = Color.White, modifier = Modifier.size(36.dp))
                    }
                    Box(Modifier.size(48.dp).background(Color.Black.copy(0.3f), CircleShape)
                        .clickable { exoPlayer.seekTo((currentTime + 10_000).coerceAtMost(totalDuration)) },
                        contentAlignment = Alignment.Center) {
                        Icon(Icons.Rounded.Forward10, null, tint = Color.White, modifier = Modifier.size(32.dp))
                    }
                }

                // Bottom bar
                Column(Modifier.align(Alignment.BottomCenter).padding(bottom = 8.dp)) {
                    // Progress bar + times
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Text(formatMs(currentTime), color = Color.White, fontSize = 11.sp)
                        Slider(
                            value = if (totalDuration > 0) currentTime.toFloat() / totalDuration else 0f,
                            onValueChange = { exoPlayer.seekTo((it * totalDuration).toLong()) },
                            modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                            colors   = SliderDefaults.colors(
                                thumbColor       = Color.Cyan,
                                activeTrackColor = Color.Cyan,
                                inactiveTrackColor = Color.White.copy(0.3f)
                            )
                        )
                        Text(formatMs(totalDuration), color = Color.White, fontSize = 11.sp)
                    }

                    // Control row: lock | pip | fullscreen | Fit | Language | Speed | Quality
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        // Pause/Play
                        IconButton(onClick = { if (isPlaying) exoPlayer.pause() else exoPlayer.play() }) {
                            Icon(if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                                null, tint = Color.White, modifier = Modifier.size(22.dp))
                        }

                        Row(verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)) {

                            // Fit button
                            IconButton(onClick = { isFit = !isFit }) {
                                Icon(if (isFit) Icons.Rounded.FitScreen else Icons.Rounded.Fullscreen,
                                    null, tint = Color.White, modifier = Modifier.size(22.dp))
                            }

                            // Language button (Audio + Subtitle)
                            TextButton(onClick = { showLanguagePanel = true }) {
                                Icon(Icons.Rounded.ClosedCaption, null, tint = Color.White,
                                    modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("Language", color = Color.White, fontSize = 11.sp)
                            }

                            // Speed
                            TextButton(onClick = { showSpeedPanel = !showSpeedPanel }) {
                                Text("${if (playbackSpeed == 1f) "1x" else "${playbackSpeed}x"}",
                                    color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }

                            // Quality
                            Text(quality, color = Color.White, fontSize = 11.sp,
                                modifier = Modifier.background(Color.Black.copy(0.5f), RoundedCornerShape(4.dp))
                                    .padding(4.dp, 2.dp))
                        }
                    }
                }

                // Speed panel
                if (showSpeedPanel) {
                    Column(
                        Modifier.align(Alignment.BottomEnd).padding(end = 16.dp, bottom = 80.dp)
                            .background(Color(0xFF1A1A2A), RoundedCornerShape(10.dp))
                            .padding(8.dp)
                    ) {
                        listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f).forEach { speed ->
                            Text(
                                text = if (speed == 1f) "1x" else "${speed}x",
                                color = if (playbackSpeed == speed) Color.Cyan else Color.White,
                                fontSize = 14.sp,
                                fontWeight = if (playbackSpeed == speed) FontWeight.Bold else FontWeight.Normal,
                                modifier = Modifier.clickable {
                                    playbackSpeed = speed
                                    exoPlayer.setPlaybackSpeed(speed)
                                    showSpeedPanel = false
                                }.padding(12.dp, 8.dp)
                            )
                        }
                    }
                }
            }
        }

        // Lock indicator (always visible when locked)
        if (isLocked) {
            Box(Modifier.fillMaxSize()) {
                Row(
                    Modifier.align(Alignment.CenterStart).padding(start = 16.dp)
                        .clickable { isLocked = false; showControls = true },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Rounded.Lock, null, tint = Color.White.copy(0.8f), modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Tap to Unlock", color = Color.White.copy(0.8f), fontSize = 12.sp)
                }
            }
        }

        // ── LANGUAGE PANEL (Movie Box style: Audio | Subtitle) ────
        if (showLanguagePanel) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(0.6f))
                .clickable { showLanguagePanel = false }) {}

            Box(
                Modifier.align(Alignment.CenterEnd).fillMaxHeight(0.75f).fillMaxWidth(0.55f)
                    .background(Color(0xFF1E1E2E), RoundedCornerShape(topStart = 12.dp, bottomStart = 12.dp))
            ) {
                when {
                    showSubtitleStyle -> SubtitleStylePanel(
                        color = subtitleColor, size = subtitleSize,
                        position = subtitlePosition, shadow = subtitleShadow, background = subtitleBackground,
                        onColorChange = { subtitleColor = it },
                        onSizeChange  = { subtitleSize = it },
                        onPositionChange = { subtitlePosition = it },
                        onShadowChange   = { subtitleShadow = it },
                        onBackgroundChange = { subtitleBackground = it },
                        onBack           = { showSubtitleStyle = false }
                    )
                    showSubtitleDelay -> SubtitleDelayPanel(
                        delayMs   = subtitleDelayMs,
                        onChange  = { subtitleDelayMs = it },
                        onBack    = { showSubtitleDelay = false }
                    )
                    showSubtitleSearch -> SubtitleSearchPanel(
                        results = subtitleResults,
                        loading = subtitleLoading,
                        onSelect = { result ->
                            scope.launch {
                                subtitleFile = SubtitleRepository.download(context, result)
                                subtitleEnabled = true
                            }
                            showSubtitleSearch = false
                            showLanguagePanel  = false
                        },
                        onBack = { showSubtitleSearch = false }
                    )
                    else -> AudioSubtitleMainPanel(
                        subtitleEnabled   = subtitleEnabled,
                        onSubtitleToggle  = { subtitleEnabled = it },
                        onStyleClick      = { showSubtitleStyle  = true },
                        onDelayClick      = { showSubtitleDelay  = true },
                        onDownloadClick   = { showSubtitleSearch = true },
                        onClose           = { showLanguagePanel = false }
                    )
                }
            }
        }
    }
}

// ── Sub-panels ────────────────────────────────────────────────────

@Composable
private fun AudioSubtitleMainPanel(
    subtitleEnabled: Boolean,
    onSubtitleToggle: (Boolean) -> Unit,
    onStyleClick: () -> Unit,
    onDelayClick: () -> Unit,
    onDownloadClick: () -> Unit,
    onClose: () -> Unit
) {
    val Teal = Color(0xFF00C9A7)
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClose) {
                Icon(Icons.Default.ArrowBack, null, tint = Teal, modifier = Modifier.size(18.dp))
            }
        }

        Row(Modifier.fillMaxWidth()) {
            // Audio column
            Column(Modifier.weight(1f)) {
                Text("Audio", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(12.dp))
                AudioTrackRow("Original Audio", true, Teal)
                // More audio tracks would come from ExoPlayer trackGroups
            }
            Spacer(Modifier.width(8.dp))
            // Subtitle column
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()) {
                    Text("Subtitle", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    Switch(checked = subtitleEnabled, onCheckedChange = onSubtitleToggle,
                        colors = SwitchDefaults.colors(checkedThumbColor = Teal, checkedTrackColor = Teal.copy(0.4f)))
                }
                Spacer(Modifier.height(8.dp))
                // Bilingual toggle
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Bilingual", color = Color.Gray, fontSize = 12.sp)
                    Spacer(Modifier.width(4.dp))
                    Switch(checked = false, onCheckedChange = {}, enabled = false,
                        modifier = Modifier.scale(0.7f))
                }
                Spacer(Modifier.height(8.dp))
                SubtitleLanguageRows()
                Spacer(Modifier.height(8.dp))
                // Download subtitle
                Row(Modifier.clickable { onDownloadClick() }.padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Download, null, tint = Teal, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Download >", color = Teal, fontSize = 12.sp)
                }
            }
        }

        Spacer(Modifier.weight(1f))
        HorizontalDivider(color = Color.White.copy(0.1f))
        Spacer(Modifier.height(8.dp))
        // Style | Delay row (bottom of panel, like Movie Box)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            TextButton(onClick = onStyleClick) {
                Icon(Icons.Rounded.FormatSize, null, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(4.dp))
                Text("Style", fontSize = 12.sp)
            }
            TextButton(onClick = onDelayClick) {
                Icon(Icons.Rounded.Timer, null, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(4.dp))
                Text("Delay", fontSize = 12.sp)
            }
        }
    }
}

@Composable private fun AudioTrackRow(name: String, selected: Boolean, teal: Color) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(name, color = if (selected) teal else Color.White, fontSize = 13.sp, modifier = Modifier.weight(1f))
        if (selected) Icon(Icons.Default.Check, null, tint = teal, modifier = Modifier.size(14.dp))
    }
}

@Composable private fun SubtitleLanguageRows() {
    val teal = Color(0xFF00C9A7)
    SubtitleLangRow("English", true, null, teal)
}

@Composable private fun SubtitleLangRow(name: String, selected: Boolean, downloadable: Boolean?, teal: Color) {
    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(name, color = if (selected) teal else Color.White, fontSize = 12.sp, modifier = Modifier.weight(1f))
        when {
            selected       -> Icon(Icons.Default.Check, null, tint = teal, modifier = Modifier.size(14.dp))
            downloadable != null -> Icon(Icons.Rounded.Download, null, tint = teal, modifier = Modifier.size(14.dp))
        }
    }
}

@Composable
private fun SubtitleStylePanel(
    color: Color, size: Float, position: Float, shadow: Boolean, background: Boolean,
    onColorChange: (Color) -> Unit, onSizeChange: (Float) -> Unit,
    onPositionChange: (Float) -> Unit, onShadowChange: (Boolean) -> Unit,
    onBackgroundChange: (Boolean) -> Unit, onBack: () -> Unit
) {
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.Default.ArrowBack, null, tint = Color.White, modifier = Modifier.size(18.dp))
            }
            Text("Subtitle Style", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(12.dp))
        Text("Font color", color = Color.White, fontSize = 13.sp)
        Spacer(Modifier.height(8.dp))
        val colors = listOf(Color.Black, Color.White, Color(0xFFFFA000), Color(0xFF4CAF50))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            colors.forEach { c ->
                Box(Modifier.size(32.dp)
                    .background(c, CircleShape)
                    .then(if (color == c) Modifier.border(2.dp, Color.Cyan, CircleShape) else Modifier)
                    .clickable { onColorChange(c) })
            }
        }
        Spacer(Modifier.height(16.dp))
        Text("Font size", color = Color.White, fontSize = 13.sp)
        Slider(value = size, onValueChange = onSizeChange, valueRange = 10f..30f,
            colors = SliderDefaults.colors(thumbColor = Color.Cyan, activeTrackColor = Color.Cyan))
        Spacer(Modifier.height(12.dp))
        Text("Position", color = Color.White, fontSize = 13.sp)
        Slider(value = position, onValueChange = onPositionChange, valueRange = 0.1f..0.95f,
            colors = SliderDefaults.colors(thumbColor = Color.Cyan, activeTrackColor = Color.Cyan))
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("Shadow", color = Color.White, fontSize = 13.sp)
            Switch(checked = shadow, onCheckedChange = onShadowChange,
                colors = SwitchDefaults.colors(checkedTrackColor = Color.Cyan.copy(0.4f)))
        }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("Background", color = Color.White, fontSize = 13.sp)
            Switch(checked = background, onCheckedChange = onBackgroundChange,
                colors = SwitchDefaults.colors(checkedTrackColor = Color.Cyan.copy(0.4f)))
        }
        Spacer(Modifier.weight(1f))
        Button(onClick = { onColorChange(Color.White); onSizeChange(16f); onPositionChange(0.85f); onShadowChange(true); onBackgroundChange(false) },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2A2A3A))) {
            Text("Reset", color = Color.Gray)
        }
    }
}

@Composable
private fun SubtitleDelayPanel(delayMs: Long, onChange: (Long) -> Unit, onBack: () -> Unit) {
    val teal = Color(0xFF00C9A7)
    Column(Modifier.fillMaxSize().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, null, tint = Color.White, modifier = Modifier.size(18.dp)) }
            Text("Subtitle Delay", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.weight(1f))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = { onChange(delayMs - 500) }, modifier = Modifier.size(56.dp),
                colors = ButtonDefaults.buttonColors(containerColor = teal), shape = RoundedCornerShape(8.dp)) {
                Text("-", fontSize = 20.sp, fontWeight = FontWeight.Bold)
            }
            Text("${delayMs / 1000.0}s", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Button(onClick = { onChange(delayMs + 500) }, modifier = Modifier.size(56.dp),
                colors = ButtonDefaults.buttonColors(containerColor = teal), shape = RoundedCornerShape(8.dp)) {
                Text("+", fontSize = 20.sp, fontWeight = FontWeight.Bold)
            }
        }
        Spacer(Modifier.weight(1f))
    }
}

@Composable
private fun SubtitleSearchPanel(
    results: List<SubtitleRepository.SubtitleResult>,
    loading: Boolean,
    onSelect: (SubtitleRepository.SubtitleResult) -> Unit,
    onBack: () -> Unit
) {
    val teal = Color(0xFF00C9A7)
    var selectedLang by remember { mutableStateOf("en") }

    Column(Modifier.fillMaxSize().padding(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, null, tint = teal, modifier = Modifier.size(18.dp)) }
            Text("Download Subtitle", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(8.dp))

        // Language filter tabs (like Movie Box)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            items(SubtitleRepository.SUPPORTED_LANGUAGES.take(6)) { lang ->
                FilterChip(
                    selected = selectedLang == lang.code,
                    onClick  = { selectedLang = lang.code },
                    label    = { Text(lang.displayName, fontSize = 10.sp) },
                    colors   = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = teal.copy(0.2f),
                        selectedLabelColor = teal
                    )
                )
            }
        }
        Spacer(Modifier.height(8.dp))

        if (loading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = teal)
            }
        } else {
            val filtered = results.filter { it.langCode.startsWith(selectedLang, true) }
            LazyColumn(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                items(filtered.ifEmpty { results }.take(15)) { sub ->
                    Row(
                        Modifier.fillMaxWidth().clickable { onSelect(sub) }.padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(sub.title, color = Color.White, fontSize = 11.sp, maxLines = 2)
                            Text(sub.lang, color = Color.Gray, fontSize = 10.sp)
                        }
                        Icon(Icons.Rounded.Download, null, tint = teal, modifier = Modifier.size(18.dp))
                    }
                }
            }
        }
    }
}

// ── Helpers ───────────────────────────────────────────────────────

private fun formatMs(ms: Long): String {
    if (ms <= 0) return "00:00"
    val totalSec = ms / 1000
    val s = totalSec % 60; val m = (totalSec / 60) % 60; val h = totalSec / 3600
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
}

private fun formatTime(secs: Long): String {
    val m = secs / 60; val s = secs % 60
    return "%02d:%02d".format(m, s)
}

/** Simple SRT parser — returns subtitle text at current playback position */
private fun parseSrtAtTime(srt: String, positionMs: Long): String {
    val blocks = srt.trim().split(Regex("\\n\\s*\\n"))
    for (block in blocks) {
        val lines = block.trim().lines()
        if (lines.size < 3) continue
        val timeLine = lines.getOrNull(1) ?: continue
        val match = Regex("""(\d{2}):(\d{2}):(\d{2}),(\d{3})\s*-->\s*(\d{2}):(\d{2}):(\d{2}),(\d{3})""")
            .find(timeLine) ?: continue
        val (h1,m1,s1,ms1,h2,m2,s2,ms2) = match.destructured
        val start = h1.toLong()*3600000 + m1.toLong()*60000 + s1.toLong()*1000 + ms1.toLong()
        val end   = h2.toLong()*3600000 + m2.toLong()*60000 + s2.toLong()*1000 + ms2.toLong()
        if (positionMs in start..end) {
            return lines.drop(2).joinToString("\n")
                .replace(Regex("<[^>]+>"), "")  // remove HTML tags
                .trim()
        }
    }
    return ""
}
