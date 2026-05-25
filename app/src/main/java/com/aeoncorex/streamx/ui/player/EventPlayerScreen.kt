package com.aeoncorex.streamx.ui.player

import android.app.Activity
import android.app.PictureInPictureParams
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.media.AudioManager
import android.os.Build
import android.provider.Settings
import android.util.Rational
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.*
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.navigation.NavController
import com.aeoncorex.streamx.data.EventRepository
import com.aeoncorex.streamx.model.EventStream
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.net.URLDecoder
import java.net.URLEncoder

// ── Palette ────────────────────────────────────────────────────────
private val EP_CYAN   = Color(0xFF00FFFF)
private val EP_PURPLE = Color(0xFFBC13FE)
private val EP_RED    = Color(0xFFFF0044)

/**
 * EventPlayerScreen v2 — Multi-stream live event player
 *
 * Route: "event_player/{eventId}/{streamIndex}/{encodedTitle}"
 *
 * Flow:
 *  1. Load event from EventRepository by [eventId]
 *  2. Pick stream at [streamIndex]
 *  3a. Direct URL  → ExoPlayer immediately (no WebView)
 *  3b. Watch/embed → EventStreamExtractor → ExoPlayer
 *  4. If fail      → "Try next server" button switches to streamIndex+1
 *                    OR WebView fallback for manual watching
 */
@Composable
fun EventPlayerScreen(
    navController : NavController,
    eventId       : String,
    streamIndex   : Int   = 0,
    encodedTitle  : String = ""
) {
    val context  = LocalContext.current
    val activity = context as? Activity
    val scope    = rememberCoroutineScope()

    // ── Load event streams from repository ────────────────────────
    var allStreams  by remember { mutableStateOf<List<EventStream>>(emptyList()) }
    var eventTitle  by remember { mutableStateOf(
        try { URLDecoder.decode(encodedTitle, "UTF-8") } catch (_: Exception) { encodedTitle }
    )}
    var repoLoaded  by remember { mutableStateOf(false) }

    LaunchedEffect(eventId) {
        try {
            val events = EventRepository.getActiveEvents()
            val event  = events.find { it.eventId == eventId }
            if (event != null) {
                allStreams = event.streams
                if (eventTitle.isEmpty()) eventTitle = event.title
            }
        } catch (_: Exception) { }
        repoLoaded = true
    }

    // Current stream derived from index
    val currentStream  = allStreams.getOrNull(streamIndex)
    val embedUrl       = currentStream?.url ?: ""
    val streamName     = currentStream?.name ?: "Server ${streamIndex + 1}"
    val totalStreams    = allStreams.size
    val hasNextStream   = streamIndex < allStreams.lastIndex
    val hasPrevStream   = streamIndex > 0

    // Is this a directly-playable URL? (no WebView needed)
    val isDirect = remember(embedUrl) {
        embedUrl.isNotEmpty() && EventStreamExtractor.isDirectUrl(embedUrl)
    }

    // ── Extraction state ──────────────────────────────────────────
    var extractedUrl     by remember { mutableStateOf<String?>(null) }
    var extractionStatus by remember { mutableStateOf("Initialising…") }
    var extractionFailed by remember { mutableStateOf(false) }
    var retryKey         by remember { mutableIntStateOf(0) }

    // ── ExoPlayer ─────────────────────────────────────────────────
    val trackSelector = remember { DefaultTrackSelector(context) }
    val exoPlayer = remember {
        ExoPlayer.Builder(context)
            .setTrackSelector(trackSelector)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                    .build(),
                true
            )
            .setHandleAudioBecomingNoisy(true)
            .build()
    }

    var isBuffering       by remember { mutableStateOf(false) }
    var isPlaying         by remember { mutableStateOf(false) }
    var hasError          by remember { mutableStateOf(false) }
    var errorMessage      by remember { mutableStateOf("") }
    var isControlsVisible by remember { mutableStateOf(true) }
    var isLocked          by remember { mutableStateOf(false) }
    var resizeMode        by remember { mutableIntStateOf(AspectRatioFrameLayout.RESIZE_MODE_FIT) }
    var showSettingsSheet by remember { mutableStateOf(false) }
    var isAudioOnlyMode   by remember { mutableStateOf(false) }
    var playbackSpeed     by remember { mutableFloatStateOf(1.0f) }
    var subtitlesEnabled  by remember { mutableStateOf(false) }

    val audioManager  = remember { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    val maxVolume     = remember { audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC) }
    var currentVolume by remember { mutableIntStateOf(audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)) }
    var brightness    by remember { mutableFloatStateOf(activity?.window?.attributes?.screenBrightness ?: 0.5f) }
    var showBrightnessSlider by remember { mutableStateOf(false) }
    var showVolumeSlider     by remember { mutableStateOf(false) }

    // ── Navigation helpers ────────────────────────────────────────
    fun navigateToStream(index: Int) {
        val encTitle = try { URLEncoder.encode(eventTitle, "UTF-8") } catch (_: Exception) { "" }
        navController.navigate("event_player/$eventId/$index/$encTitle") {
            popUpTo("event_player/$eventId/$streamIndex/$encTitle") { inclusive = true }
        }
    }

    // ── System UI ─────────────────────────────────────────────────
    DisposableEffect(Unit) {
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        val win = activity?.window
        if (win != null) {
            WindowCompat.setDecorFitsSystemWindows(win, false)
            WindowCompat.getInsetsController(win, win.decorView).apply {
                hide(WindowInsetsCompat.Type.systemBars())
                systemBarsBehavior =
                    WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        }
        onDispose {
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            if (win != null) {
                WindowCompat.getInsetsController(win, win.decorView)
                    .show(WindowInsetsCompat.Type.systemBars())
                WindowCompat.setDecorFitsSystemWindows(win, true)
            }
            exoPlayer.release()
        }
    }

    // ── PHASE 1/2: Start play when embedUrl is ready ──────────────
    LaunchedEffect(retryKey, embedUrl) {
        if (embedUrl.isEmpty()) return@LaunchedEffect

        extractedUrl     = null
        extractionFailed = false
        hasError         = false
        extractionStatus = if (isDirect) "Direct stream detected…" else "Connecting to server…"

        // Resolve the playable URL
        val playUrl: String? = if (isDirect) {
            // MODE A: direct URL — skip WebView entirely
            extractionStatus = "Direct stream ✓"
            embedUrl
        } else {
            // MODE B: WebView extraction
            EventStreamExtractor.extract(context, embedUrl) { status ->
                extractionStatus = status
            }
        }

        if (playUrl != null) {
            extractedUrl = playUrl
            exoPlayer.setMediaItem(
                MediaItem.Builder()
                    .setUri(playUrl)
                    .setLiveConfiguration(
                        MediaItem.LiveConfiguration.Builder()
                            .setMaxPlaybackSpeed(1.02f)
                            .build()
                    )
                    .build()
            )
            exoPlayer.prepare()
            exoPlayer.playWhenReady = true
        } else {
            extractionFailed = true
        }
    }

    // ── ExoPlayer listener ────────────────────────────────────────
    DisposableEffect(exoPlayer) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                isBuffering = state == Player.STATE_BUFFERING
                isPlaying   = state == Player.STATE_READY && exoPlayer.playWhenReady
            }
            override fun onPlayerError(error: PlaybackException) {
                hasError     = true
                errorMessage = error.localizedMessage ?: "Stream error"
                isBuffering  = false
            }
            override fun onIsPlayingChanged(playing: Boolean) { isPlaying = playing }
        }
        exoPlayer.addListener(listener)
        onDispose { exoPlayer.removeListener(listener) }
    }

    // Auto-hide controls
    LaunchedEffect(isControlsVisible, isPlaying) {
        if (isControlsVisible && isPlaying) {
            delay(5_000)
            isControlsVisible = false
        }
    }
    LaunchedEffect(showBrightnessSlider, showVolumeSlider) {
        if (showBrightnessSlider || showVolumeSlider) {
            delay(1_500)
            showBrightnessSlider = false
            showVolumeSlider     = false
        }
    }

    KeepScreenOn()
    HideSystemUi(activity)

    BackHandler {
        when {
            isLocked          -> Toast.makeText(context, "Screen is Locked!", Toast.LENGTH_SHORT).show()
            showSettingsSheet -> showSettingsSheet = false
            isControlsVisible -> isControlsVisible = false
            else              -> navController.popBackStack()
        }
    }

    // ══════════════════════════════════════════════════════════════
    //  ROOT BOX
    // ══════════════════════════════════════════════════════════════
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInput(Unit) {
                detectTapGestures(onTap = { isControlsVisible = !isControlsVisible })
            }
            .pointerInput(Unit) {
                detectVerticalDragGestures(
                    onDragStart = {}, onDragEnd = {}
                ) { change, drag ->
                    if (!isLocked && extractedUrl != null) {
                        if (change.position.x > size.width / 2) {
                            val v = (currentVolume + (drag / -30)).toInt().coerceIn(0, maxVolume)
                            if (v != currentVolume) {
                                currentVolume = v
                                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, v, 0)
                                showVolumeSlider = true
                            }
                        } else {
                            brightness = (brightness + drag / -1000f).coerceIn(0f, 1f)
                            activity?.window?.attributes?.let { lp ->
                                lp.screenBrightness = brightness
                                activity.window.attributes = lp
                            }
                            showBrightnessSlider = true
                        }
                    }
                }
            }
    ) {
        // ── Ambilight ─────────────────────────────────────────────
        if (isPlaying && !isAudioOnlyMode) {
            Box(Modifier.fillMaxSize().background(
                Brush.radialGradient(listOf(EP_PURPLE.copy(.12f), Color.Transparent))
            ))
        }

        // ── ExoPlayer surface ─────────────────────────────────────
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory  = { ctx ->
                PlayerView(ctx).apply {
                    player          = exoPlayer
                    useController   = false
                    this.resizeMode = resizeMode
                    setKeepContentOnPlayerReset(true)
                    setShutterBackgroundColor(android.graphics.Color.BLACK)
                }
            },
            update = { pv ->
                pv.resizeMode = resizeMode
                pv.player     = exoPlayer
            }
        )

        // Audio-only overlay
        if (isAudioOnlyMode && extractedUrl != null) {
            Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Rounded.Headphones, null, tint = EP_CYAN, modifier = Modifier.size(80.dp))
                    Spacer(Modifier.height(16.dp))
                    Text("AUDIO ONLY MODE", color = Color.White, letterSpacing = 2.sp, fontWeight = FontWeight.Bold)
                }
            }
        }

        // ── Buffering ─────────────────────────────────────────────
        if (isBuffering && !hasError && extractedUrl != null) {
            CircularProgressIndicator(
                color       = EP_CYAN,
                strokeWidth = 4.dp,
                modifier    = Modifier.align(Alignment.Center)
            )
        }

        // Volume / Brightness sliders
        AnimatedVisibility(
            visible  = showBrightnessSlider,
            enter    = slideInHorizontally { -it },
            exit     = fadeOut(),
            modifier = Modifier.align(Alignment.CenterStart).padding(start = 32.dp)
        ) {
            CyberSlider(
                icon  = Icons.Rounded.BrightnessMedium,
                level = brightness,
                max   = 1f,
                color = Color.Yellow
            )
        }
        AnimatedVisibility(
            visible  = showVolumeSlider,
            enter    = slideInHorizontally { it },
            exit     = fadeOut(),
            modifier = Modifier.align(Alignment.CenterEnd).padding(end = 32.dp)
        ) {
            CyberSlider(
                icon  = Icons.Rounded.VolumeUp,
                level = currentVolume.toFloat(),
                max   = maxVolume.toFloat(),
                color = EP_CYAN
            )
        }

        // ── PHASE 2 controls ──────────────────────────────────────
        if (extractedUrl != null) {
            AnimatedVisibility(visible = isControlsVisible, enter = fadeIn(), exit = fadeOut()) {
                AdvancedPlayerControls(
                    title              = eventTitle.ifEmpty { "LIVE EVENT" },
                    networkSpeed       = "",
                    dataUsed           = "",
                    isPlaying          = isPlaying,
                    isLocked           = isLocked,
                    qualityLabel       = if (isDirect) "Direct" else "Auto",
                    currentProgram     = streamName,
                    currentProgramTime = if (totalStreams > 1) "${streamIndex + 1}/$totalStreams" else "",
                    nextProgram        = "",
                    nextProgramTime    = "",
                    epgProgress        = 0f,
                    epgSource          = "",
                    onBack             = { navController.popBackStack() },
                    onPlayPause        = { if (isPlaying) exoPlayer.pause() else exoPlayer.play() },
                    onLockToggle       = { isLocked = !isLocked },
                    onRotateScreen     = {
                        val ori = activity?.resources?.configuration?.orientation
                        activity?.requestedOrientation = if (
                            ori == android.content.res.Configuration.ORIENTATION_LANDSCAPE
                        ) ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                        else ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                    },
                    onResizeToggle     = {
                        resizeMode = when (resizeMode) {
                            AspectRatioFrameLayout.RESIZE_MODE_FIT  -> AspectRatioFrameLayout.RESIZE_MODE_FILL
                            AspectRatioFrameLayout.RESIZE_MODE_FILL -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                            else                                     -> AspectRatioFrameLayout.RESIZE_MODE_FIT
                        }
                    },
                    onSettingsClick    = { showSettingsSheet = true },
                    onPipClick         = {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            try {
                                activity?.enterPictureInPictureMode(
                                    PictureInPictureParams.Builder()
                                        .setAspectRatio(Rational(16, 9))
                                        .build()
                                )
                            } catch (_: Exception) {}
                        }
                    }
                )
            }

            // ── Stream switcher strip (top-right when controls visible) ────
            if (isControlsVisible && !isLocked && totalStreams > 1) {
                Row(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(top = 56.dp, end = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (hasPrevStream) {
                        FilledTonalButton(
                            onClick = { navigateToStream(streamIndex - 1) },
                            colors  = ButtonDefaults.filledTonalButtonColors(
                                containerColor = Color.White.copy(.15f)
                            ),
                            shape   = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                        ) {
                            Icon(Icons.Rounded.SkipPrevious, null,
                                tint = Color.White, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("PREV", color = Color.White, fontSize = 10.sp,
                                fontWeight = FontWeight.Bold, letterSpacing = .5.sp)
                        }
                    }
                    // Server badge
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(EP_CYAN.copy(.2f))
                            .border(1.dp, EP_CYAN.copy(.5f), RoundedCornerShape(8.dp))
                            .padding(horizontal = 10.dp, vertical = 5.dp)
                    ) {
                        Text(
                            "SERVER ${streamIndex + 1}/$totalStreams",
                            color         = EP_CYAN,
                            fontSize      = 9.sp,
                            fontWeight    = FontWeight.Bold,
                            letterSpacing = .8.sp
                        )
                    }
                    if (hasNextStream) {
                        FilledTonalButton(
                            onClick = { navigateToStream(streamIndex + 1) },
                            colors  = ButtonDefaults.filledTonalButtonColors(
                                containerColor = EP_CYAN.copy(.2f)
                            ),
                            shape   = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                        ) {
                            Text("NEXT", color = EP_CYAN, fontSize = 10.sp,
                                fontWeight = FontWeight.Bold, letterSpacing = .5.sp)
                            Spacer(Modifier.width(4.dp))
                            Icon(Icons.Rounded.SkipNext, null,
                                tint = EP_CYAN, modifier = Modifier.size(16.dp))
                        }
                    }
                }
            }

            if (showSettingsSheet) {
                PlayerSettingsSheet(
                    onDismiss          = { showSettingsSheet = false },
                    qualityLabel       = if (isDirect) "Direct" else "Auto",
                    onQualityClick     = { showSettingsSheet = false },
                    isAudioOnly        = isAudioOnlyMode,
                    onAudioModeClick   = { isAudioOnlyMode = !isAudioOnlyMode },
                    sleepTimerSeconds  = 0L,
                    onSleepTimerClick  = { showSettingsSheet = false },
                    playbackSpeed      = playbackSpeed,
                    onSpeedClick       = {
                        val s = if (playbackSpeed >= 2f) 0.5f else playbackSpeed + 0.25f
                        playbackSpeed = s
                        exoPlayer.setPlaybackSpeed(s)
                    },
                    subtitlesEnabled   = subtitlesEnabled,
                    onSubtitleToggle   = {
                        subtitlesEnabled = !subtitlesEnabled
                        trackSelector.parameters = trackSelector.buildUponParameters()
                            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, !subtitlesEnabled)
                            .build()
                    },
                    onCastClick = {
                        showSettingsSheet = false
                        try { context.startActivity(Intent("android.settings.CAST_SETTINGS")) }
                        catch (_: Exception) {
                            Toast.makeText(context, "Use Quick Settings to Cast", Toast.LENGTH_SHORT).show()
                        }
                    }
                )
            }
        }

        // ── ExoPlayer error overlay ───────────────────────────────
        if (hasError && extractedUrl != null) {
            Box(
                Modifier.fillMaxSize().background(Color.Black.copy(.9f)),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(horizontal = 32.dp)) {
                    Icon(Icons.Rounded.Warning, null, tint = Color.Red,
                        modifier = Modifier.size(48.dp))
                    Spacer(Modifier.height(12.dp))
                    Text("STREAM FAILED", color = Color.White,
                        fontWeight = FontWeight.Bold, fontSize = 18.sp)
                    Text(errorMessage, color = Color.Gray, fontSize = 12.sp,
                        modifier = Modifier.padding(20.dp), textAlign = TextAlign.Center)
                    Spacer(Modifier.height(16.dp))
                    // Retry current
                    Button(
                        onClick = { hasError = false; exoPlayer.prepare(); exoPlayer.play() },
                        colors  = ButtonDefaults.buttonColors(containerColor = EP_CYAN)
                    ) { Text("RETRY", color = Color.Black, fontWeight = FontWeight.Bold) }
                    // Try next server
                    if (hasNextStream) {
                        Spacer(Modifier.height(8.dp))
                        Button(
                            onClick = { navigateToStream(streamIndex + 1) },
                            colors  = ButtonDefaults.buttonColors(
                                containerColor = EP_PURPLE.copy(.8f)
                            )
                        ) {
                            Icon(Icons.Rounded.SkipNext, null,
                                tint = Color.White, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(
                                "TRY SERVER ${streamIndex + 2}/$totalStreams",
                                color = Color.White, fontWeight = FontWeight.Bold
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    TextButton(onClick = { retryKey++ }) {
                        Text("RE-EXTRACT STREAM", color = EP_PURPLE)
                    }
                    TextButton(onClick = { navController.popBackStack() }) {
                        Text("EXIT", color = Color.Gray)
                    }
                }
            }
        }

        // ── PHASE 1 OVERLAY — extraction in progress ──────────────
        AnimatedVisibility(
            visible  = extractedUrl == null && !extractionFailed,
            enter    = fadeIn(),
            exit     = fadeOut(tween(400)),
            modifier = Modifier.fillMaxSize()
        ) {
            ExtractionLoadingScreen(
                title      = eventTitle,
                status     = extractionStatus,
                isDirect   = isDirect,
                streamName = streamName,
                streamNo   = streamIndex + 1,
                total      = totalStreams
            )
        }

        // ── PHASE 3 OVERLAY — extraction failed ──────────────────
        AnimatedVisibility(
            visible  = extractionFailed,
            enter    = fadeIn(),
            exit     = fadeOut(),
            modifier = Modifier.fillMaxSize()
        ) {
            ExtractionFailedScreen(
                streamIndex  = streamIndex,
                totalStreams = totalStreams,
                hasNext      = hasNextStream,
                onRetry      = { retryKey++ },
                onNextServer = { navigateToStream(streamIndex + 1) },
                onWatchInBrowser = {
                    val enc      = try { URLEncoder.encode(embedUrl, "UTF-8") } catch (_: Exception) { "" }
                    val encTitle = try { URLEncoder.encode(eventTitle, "UTF-8") } catch (_: Exception) { "" }
                    navController.navigate("event_webview/$enc/$encTitle") {
                        popUpTo("event_player/$eventId/$streamIndex/$encTitle") { inclusive = true }
                    }
                },
                onBack = { navController.popBackStack() }
            )
        }
    }
}

// ══════════════════════════════════════════════════════════════════
//  Phase 1 UI — extraction loading
// ══════════════════════════════════════════════════════════════════
@Composable
private fun ExtractionLoadingScreen(
    title      : String,
    status     : String,
    isDirect   : Boolean = false,
    streamName : String  = "",
    streamNo   : Int     = 1,
    total      : Int     = 1
) {
    val inf     = rememberInfiniteTransition(label = "ext")
    val pulse   by inf.animateFloat(.35f, 1f,
        infiniteRepeatable(tween(1800, easing = EaseInOutSine), RepeatMode.Reverse), "p")
    val spinCw  by inf.animateFloat(0f, 360f,
        infiniteRepeatable(tween(1600, easing = LinearEasing)), "cw")
    val spinCcw by inf.animateFloat(360f, 0f,
        infiniteRepeatable(tween(1100, easing = LinearEasing)), "ccw")

    Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
        Box(Modifier.size(260.dp)
            .background(Brush.radialGradient(listOf(EP_PURPLE.copy(.2f), Color.Transparent)),
                CircleShape))

        Column(horizontalAlignment = Alignment.CenterHorizontally) {

            Box(Modifier.size(90.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(
                    modifier    = Modifier.size(90.dp).rotate(spinCw),
                    color       = EP_CYAN.copy(pulse),
                    strokeWidth = 1.8.dp,
                    trackColor  = Color.Transparent
                )
                CircularProgressIndicator(
                    modifier    = Modifier.size(62.dp).rotate(spinCcw),
                    color       = EP_PURPLE.copy(pulse),
                    strokeWidth = 1.8.dp,
                    trackColor  = Color.Transparent
                )
                Box(
                    Modifier.size(12.dp)
                        .drawBehind { drawCircle(EP_CYAN.copy(.3f), size.minDimension * 2.5f) }
                        .background(Brush.radialGradient(listOf(EP_CYAN, EP_PURPLE)), CircleShape)
                )
            }

            Spacer(Modifier.height(32.dp))

            Text(
                if (isDirect) "LOADING STREAM" else "EXTRACTING STREAM",
                color         = EP_CYAN.copy(.7f),
                fontSize      = 12.sp,
                fontWeight    = FontWeight.Bold,
                letterSpacing = 2.5.sp
            )

            if (total > 1) {
                Spacer(Modifier.height(4.dp))
                Text(
                    "SERVER $streamNo / $total",
                    color         = EP_PURPLE.copy(.8f),
                    fontSize      = 10.sp,
                    fontWeight    = FontWeight.SemiBold,
                    letterSpacing = 1.sp
                )
            }

            Spacer(Modifier.height(10.dp))

            AnimatedContent(
                targetState  = status,
                transitionSpec = {
                    (slideInVertically { it } + fadeIn()) togetherWith
                    (slideOutVertically { -it } + fadeOut())
                },
                label = "status"
            ) { s ->
                Text(s, color = Color.White.copy(.35f), fontSize = 12.sp)
            }

            if (streamName.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text(streamName, color = Color.White.copy(.2f), fontSize = 10.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 280.dp))
            }

            Spacer(Modifier.height(20.dp))

            if (title.isNotEmpty()) {
                Text(title, color = Color.White.copy(.18f), fontSize = 11.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 300.dp),
                    textAlign = TextAlign.Center)
            }

            Spacer(Modifier.height(40.dp))

            val lineOffset by inf.animateFloat(-200f, 500f,
                infiniteRepeatable(tween(2000, easing = LinearEasing)), "line")
            Box(
                Modifier.width(280.dp).height(1.dp)
                    .background(Color.White.copy(.07f))
                    .drawBehind {
                        drawRect(
                            Brush.linearGradient(
                                listOf(Color.Transparent, EP_CYAN.copy(.8f), Color.Transparent),
                                start = Offset(lineOffset, 0f),
                                end   = Offset(lineOffset + 120f, 0f)
                            )
                        )
                    }
            )
        }
    }
}

// ══════════════════════════════════════════════════════════════════
//  Phase 3 UI — extraction failed
// ══════════════════════════════════════════════════════════════════
@Composable
private fun ExtractionFailedScreen(
    streamIndex      : Int,
    totalStreams      : Int,
    hasNext          : Boolean,
    onRetry          : () -> Unit,
    onNextServer     : () -> Unit,
    onWatchInBrowser : () -> Unit,
    onBack           : () -> Unit
) {
    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(.95f)),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(horizontal = 32.dp)
        ) {
            Icon(Icons.Rounded.SignalWifiOff, null, tint = EP_RED,
                modifier = Modifier.size(52.dp))
            Spacer(Modifier.height(16.dp))
            Text("STREAM NOT FOUND", color = Color.White,
                fontWeight = FontWeight.Bold, fontSize = 18.sp, letterSpacing = 1.sp)
            Spacer(Modifier.height(8.dp))
            Text(
                "Server ${streamIndex + 1} of $totalStreams could not be extracted.",
                color = Color.Gray, fontSize = 13.sp,
                textAlign = TextAlign.Center, lineHeight = 20.sp
            )
            Spacer(Modifier.height(28.dp))

            Button(
                onClick  = onRetry,
                modifier = Modifier.fillMaxWidth().height(48.dp),
                colors   = ButtonDefaults.buttonColors(containerColor = EP_CYAN),
                shape    = RoundedCornerShape(12.dp)
            ) {
                Icon(Icons.Rounded.Refresh, null, tint = Color.Black,
                    modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("RETRY THIS SERVER", color = Color.Black, fontWeight = FontWeight.Bold)
            }

            if (hasNext) {
                Spacer(Modifier.height(10.dp))
                Button(
                    onClick  = onNextServer,
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    colors   = ButtonDefaults.buttonColors(containerColor = EP_PURPLE.copy(.85f)),
                    shape    = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Rounded.SkipNext, null, tint = Color.White,
                        modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "TRY SERVER ${streamIndex + 2} / $totalStreams",
                        color = Color.White, fontWeight = FontWeight.Bold
                    )
                }
            }

            Spacer(Modifier.height(10.dp))
            OutlinedButton(
                onClick  = onWatchInBrowser,
                modifier = Modifier.fillMaxWidth().height(48.dp),
                border   = BorderStroke(1.dp, EP_PURPLE.copy(.6f)),
                shape    = RoundedCornerShape(12.dp),
                colors   = ButtonDefaults.outlinedButtonColors(contentColor = EP_PURPLE)
            ) {
                Icon(Icons.Rounded.OpenInBrowser, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("WATCH IN BROWSER", fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.height(10.dp))
            TextButton(onClick = onBack) {
                Text("← BACK TO EVENTS", color = Color.Gray, fontSize = 13.sp)
            }
        }
    }
}
