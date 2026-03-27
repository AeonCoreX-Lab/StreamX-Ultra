package com.aeoncorex.streamx.ui.movie

import android.app.Activity
import android.content.Context
import android.content.pm.ActivityInfo
import android.media.AudioManager
import android.net.Uri
import android.util.Log
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Toast
import androidx.annotation.Keep
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.navigation.NavController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

// ─────────────────────────────────────────────────────────────
@Keep
object StreamXCore {
    init {
        val libraries = listOf(
            "c++_shared", "avutil", "swresample", "swscale", "avcodec",
            "avformat", "avfilter", "avdevice", "mpv", "streamx-native"
        )
        for (lib in libraries) {
            try {
                System.loadLibrary(lib)
                Log.d("StreamX_Native", "✅ Loaded: $lib")
            } catch (e: Throwable) {
                Log.e("StreamX_Native", "❌ Failed: $lib — ${e.message}")
                throw RuntimeException("Failed to load '$lib': ${e.message}")
            }
        }
    }

    @JvmStatic external fun getTmdbKey(): String

    @JvmStatic external fun initMpvEngine()
    @JvmStatic external fun playMpvVideo(path: String)
    @JvmStatic external fun setMpvSurface(surface: Surface?)
    @JvmStatic external fun setMpvSurfaceSize(width: Int, height: Int)
    @JvmStatic external fun toggleVulkanFSR(enable: Boolean)
    @JvmStatic external fun seekMpvVideo(seconds: Double)
    @JvmStatic external fun pauseMpvVideo(pause: Boolean)
    @JvmStatic external fun getMpvTime(): Double
    @JvmStatic external fun getMpvDuration(): Double

    @JvmStatic external fun commandNative(cmd: Array<String>)
    @JvmStatic external fun setPropertyStringNative(name: String, value: String)
    @JvmStatic external fun getPropertyStringNative(name: String): String
    @JvmStatic external fun getPropertyIntNative(name: String): Long

    // Cache state
    @JvmStatic external fun getMpvCachePercent(): Int
    @JvmStatic external fun isMpvPausedForCache(): Boolean

    // ── Single-call track list (replaces loop-based getTrackList) ──
    // Returns "id|title|selected;id|title|selected;..." or ""
    @JvmStatic external fun getTrackListNative(type: String): String

    // ── Helpers ───────────────────────────────────────────────
    fun cycleSubtitles()                 = commandNative(arrayOf("cycle", "sub"))
    fun cycleAudio()                     = commandNative(arrayOf("cycle", "audio"))
    fun addExternalSubtitle(url: String) = commandNative(arrayOf("sub-add", url, "select"))
    fun setSubTrack(id: Int)             = setPropertyStringNative("sid", if (id < 0) "no" else id.toString())
    fun setAudioTrack(id: Int)           = setPropertyStringNative("aid", id.toString())
    fun setVideoFilter(vf: String)       = setPropertyStringNative("vf",  vf)

    /** Parse the pipe-delimited track list string returned by getTrackListNative(). */
    fun getTrackList(type: String): List<MpvTrack> {
        val raw = try { getTrackListNative(type) } catch (e: Exception) { return emptyList() }
        if (raw.isBlank()) return emptyList()
        return raw.split(";").mapNotNull { entry ->
            val parts = entry.split("|")
            if (parts.size < 3) null
            else MpvTrack(
                id       = parts[0].toIntOrNull() ?: return@mapNotNull null,
                title    = parts[1].ifBlank { "Track ${parts[0]}" },
                selected = parts[2] == "1"
            )
        }
    }
}

data class MpvTrack(val id: Int, val title: String, val selected: Boolean)

// ── GPU Quality presets ───────────────────────────────────────
data class QualityPreset(
    val label: String, val subtitle: String,
    val vf: String, val scale: String, val cscale: String, val deband: Boolean
)

val GPU_QUALITY_PRESETS = listOf(
    QualityPreset("Auto",       "Balanced (Recommended)", "",           "bilinear",         "bilinear",         false),
    QualityPreset("Cinematic",  "Best quality, High GPU", "",           "ewa_lanczossharp", "ewa_lanczossharp", true),
    QualityPreset("High",       "Sharp, Medium GPU",      "",           "spline36",         "spline36",         false),
    QualityPreset("Medium",     "Smooth, Low GPU",        "",           "bilinear",         "bilinear",         false),
    QualityPreset("720p Scale", "Force 720p render",      "scale=1280:720", "bilinear",     "bilinear",         false),
    QualityPreset("480p Scale", "Force 480p, Save battery","scale=854:480", "bilinear",     "bilinear",         false),
)

fun applyQualityPreset(preset: QualityPreset) {
    try {
        StreamXCore.setPropertyStringNative("scale",  preset.scale)
        StreamXCore.setPropertyStringNative("cscale", preset.cscale)
        StreamXCore.setPropertyStringNative("deband", if (preset.deband) "yes" else "no")
        StreamXCore.setVideoFilter(preset.vf)
    } catch (e: Exception) { Log.e("MPV", "applyQuality: ${e.message}") }
}

// ─────────────────────────────────────────────────────────────
@Composable
fun MoviePlayerScreen(navController: NavController, encodedUrl: String) {
    val context  = LocalContext.current
    val activity = context as? Activity
    val decodedUrl = remember {
        try { URLDecoder.decode(encodedUrl, "UTF-8") } catch (e: Exception) { encodedUrl }
    }
    val scope = rememberCoroutineScope()

    // Extract movie title from magnet dn= for subtitle search
    val movieTitle = remember {
        if (decodedUrl.startsWith("magnet:?")) {
            try {
                val dn = Uri.parse(decodedUrl).getQueryParameter("dn") ?: ""
                dn.replace(Regex("\\b(1080p|720p|480p|BluRay|WEB-DL|HDR|x264|x265|\\d{4}).*"), "").trim()
            } catch (e: Exception) { "" }
        } else ""
    }

    // ── Core state ────────────────────────────────────────────
    var videoPath       by remember { mutableStateOf<String?>(null) }
    var isPlaying       by remember { mutableStateOf(true) }
    var currentTime     by remember { mutableDoubleStateOf(0.0) }
    var totalDuration   by remember { mutableDoubleStateOf(0.0) }

    // ── Buffering ─────────────────────────────────────────────
    var isPreBuffering  by remember { mutableStateOf(true) }
    var isMidBuffering  by remember { mutableStateOf(false) }
    var cachePercent    by remember { mutableIntStateOf(100) }
    var statusMsg       by remember { mutableStateOf("Preparing...") }
    var downloadSpeed   by remember { mutableStateOf("0 KB/s") }
    var seeds           by remember { mutableIntStateOf(0) }
    var torrentProgress by remember { mutableIntStateOf(0) }

    // ── Surface ───────────────────────────────────────────────
    var isSurfaceReady  by remember { mutableStateOf(false) }
    // Keep a holder ref so we can re-attach surface after loadfile
    var surfaceHolder   by remember { mutableStateOf<SurfaceHolder?>(null) }
    var surfaceW        by remember { mutableIntStateOf(0) }
    var surfaceH        by remember { mutableIntStateOf(0) }

    // ── Controls ─────────────────────────────────────────────
    var isControlsVisible by remember { mutableStateOf(true) }
    var isLocked          by remember { mutableStateOf(false) }
    var showSettingsMenu  by remember { mutableStateOf(false) }
    var activeSettingPage by remember { mutableStateOf("Main") }
    var selectedQuality   by remember { mutableStateOf(GPU_QUALITY_PRESETS[0]) }

    // ── Subtitle ──────────────────────────────────────────────
    var subTracks       by remember { mutableStateOf<List<MpvTrack>>(emptyList()) }
    var isSearchingSub  by remember { mutableStateOf(false) }
    var subSearchMsg    by remember { mutableStateOf("") }

    // ── Gesture ───────────────────────────────────────────────
    var volumeLevel     by remember { mutableFloatStateOf(0.5f) }
    var brightnessLevel by remember { mutableFloatStateOf(0.5f) }
    var gestureIcon     by remember { mutableStateOf<ImageVector?>(null) }
    var gestureText     by remember { mutableStateOf("") }
    var showGestureOverlay by remember { mutableStateOf(false) }
    var forwardAnimAlpha by remember { mutableFloatStateOf(0f) }
    var rewindAnimAlpha  by remember { mutableFloatStateOf(0f) }

    val audioManager = remember { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    val maxVolume    = remember { audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC) }

    // ── Init MPV ─────────────────────────────────────────────
    LaunchedEffect(Unit) {
        try { StreamXCore.initMpvEngine() } catch (e: Exception) { Log.e("MPV", "Init", e) }
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        activity?.window?.let { WindowCompat.getInsetsController(it, it.decorView) }
            ?.hide(WindowInsetsCompat.Type.systemBars())
    }

    DisposableEffect(Unit) {
        onDispose {
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            try { StreamXCore.pauseMpvVideo(true) } catch (e: Exception) { }
            TorrentEngine.stop()
            TorrentEngine.clearCache(context)
        }
    }

    // ── Torrent / direct URL handling ─────────────────────────
    LaunchedEffect(decodedUrl) {
        var retryCount = 0
        val maxRetries = 3
        while (retryCount < maxRetries) {
            if (decodedUrl.startsWith("magnet:?")) {
                withContext(Dispatchers.IO) { TorrentEngine.clearCache(context) }
                var metadataTimeout = 0
                var completed = false

                TorrentEngine.start(context, decodedUrl).collect { state ->
                    when (state) {
                        is StreamState.Preparing -> {
                            statusMsg = state.message
                            if (++metadataTimeout > 240) {
                                statusMsg = if (retryCount < maxRetries - 1)
                                    "Timeout – retrying (${retryCount + 1}/$maxRetries)"
                                else "Timeout – last attempt"
                                TorrentEngine.stop(); completed = true
                            }
                        }
                        is StreamState.Buffering -> {
                            isPreBuffering  = true
                            torrentProgress = state.progress
                            // BUG FIX: removed "target 5%" text and "%/5%" display
                            statusMsg       = "Buffering ${state.progress}%"
                            downloadSpeed   = "${state.speed} KB/s"
                            seeds           = state.seeds
                            metadataTimeout = 0
                        }
                        is StreamState.Ready -> {
                            videoPath      = state.filePath
                            isPreBuffering = false
                            statusMsg      = ""
                            completed      = true
                        }
                        is StreamState.Error -> {
                            statusMsg      = "Error: ${state.message}"
                            isPreBuffering = false
                            completed      = true
                        }
                    }
                }
                if (completed) break
                retryCount++
                delay(2000)
            } else {
                videoPath      = decodedUrl
                isPreBuffering = false
                statusMsg      = ""
                break
            }
        }
        if (retryCount == maxRetries)
            statusMsg = "Failed after $maxRetries retries. Check connection."
    }

    // ── Start playback when BOTH path AND surface are ready ───
    LaunchedEffect(videoPath, isSurfaceReady) {
        val path = videoPath ?: return@LaunchedEffect
        if (!isSurfaceReady) return@LaunchedEffect

        Log.d("MPV", "Surface+Path ready → loadfile: $path")
        try { StreamXCore.playMpvVideo(path) } catch (e: Exception) {
            Log.e("MPV", "playMpvVideo failed: ${e.message}"); return@LaunchedEffect
        }

        // ── Surface re-attach after loadfile ─────────────────────────
        //
        //  BLACK SCREEN ROOT CAUSE:
        //   After loadfile, MPV internally tears down and re-creates its
        //   VO (Video Output).  During this reset, android-surface-size
        //   is cleared and the VO waits for a new size notification before
        //   rendering any frame.  If we never re-send the size, the VO
        //   stays in limbo: demuxer runs, audio plays, timer advances,
        //   but the screen stays black indefinitely.
        //
        //   The C++ event thread in mpv_handler.cpp handles this via
        //   MPV_EVENT_VIDEO_RECONFIG.  But we also do it here from Kotlin
        //   as a belt-and-suspenders fix: wait 300ms for MPV to finish its
        //   internal VO setup, then re-send the surface + size.
        delay(300)
        val holder = surfaceHolder
        if (holder != null && holder.surface.isValid) {
            try { StreamXCore.setMpvSurface(holder.surface) } catch (e: Exception) { }
        }
        if (surfaceW > 0 && surfaceH > 0) {
            try { StreamXCore.setMpvSurfaceSize(surfaceW, surfaceH) } catch (e: Exception) { }
        }

        // Duration confirmation loop
        repeat(20) {
            delay(1000)
            val dur = try { StreamXCore.getMpvDuration() } catch (e: Exception) { 0.0 }
            if (dur > 0) { Log.d("MPV", "Duration: $dur s"); return@LaunchedEffect }
        }
        // Still 0 after 20s → reload
        Log.w("MPV", "Duration 0 after 20s → reloading")
        try { StreamXCore.playMpvVideo(path) } catch (e: Exception) { }
    }

    // ── Time sync loop ────────────────────────────────────────
    LaunchedEffect(isPlaying) {
        while (isPlaying) {
            try {
                currentTime = StreamXCore.getMpvTime()
                val dur = StreamXCore.getMpvDuration()
                if (dur > 0) totalDuration = dur
            } catch (e: Exception) { }
            delay(1000)
        }
    }

    // ── Mid-play cache monitor ────────────────────────────────
    LaunchedEffect(videoPath) {
        if (videoPath == null) return@LaunchedEffect
        while (true) {
            try {
                isMidBuffering = StreamXCore.isMpvPausedForCache()
                cachePercent   = StreamXCore.getMpvCachePercent()
            } catch (e: Exception) { }
            delay(500)
        }
    }

    // ── Auto-hide controls ────────────────────────────────────
    LaunchedEffect(isControlsVisible, showSettingsMenu) {
        if (isControlsVisible && !showSettingsMenu) {
            delay(4000); isControlsVisible = false
        }
    }

    // ─────────────────────────────────────────────────────────
    Box(Modifier.fillMaxSize().background(Color.Black)) {

        // ── MPV SurfaceView ───────────────────────────────────
        AndroidView(
            factory = { ctx ->
                SurfaceView(ctx).apply {
                    holder.addCallback(object : SurfaceHolder.Callback {
                        override fun surfaceCreated(h: SurfaceHolder) {
                            surfaceHolder = h
                            try { StreamXCore.setMpvSurface(h.surface) } catch (e: Exception) { }
                            isSurfaceReady = true
                            Log.d("MPV", "surfaceCreated")
                        }
                        override fun surfaceChanged(h: SurfaceHolder, fmt: Int, w: Int, height: Int) {
                            surfaceHolder = h
                            surfaceW = w; surfaceH = height
                            try { StreamXCore.setMpvSurfaceSize(w, height) } catch (e: Exception) { }
                        }
                        override fun surfaceDestroyed(h: SurfaceHolder) {
                            isSurfaceReady = false
                            surfaceHolder  = null
                            try { StreamXCore.setMpvSurface(null) } catch (e: Exception) { }
                        }
                    })
                    layoutParams = ViewGroup.LayoutParams(-1, -1)
                }
            },
            modifier = Modifier.fillMaxSize()
        )

        var lastUpdateTime  by remember { mutableLongStateOf(0L) }
        var dragAccumulator by remember { mutableFloatStateOf(0f) }

        // Gesture layer
        Box(Modifier.fillMaxSize()
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = {
                        if (showSettingsMenu) showSettingsMenu = false
                        else isControlsVisible = !isControlsVisible
                    },
                    onDoubleTap = { offset ->
                        if (isLocked || showSettingsMenu) return@detectTapGestures
                        val fwd = offset.x > size.width / 2
                        val delta = if (fwd) 10.0 else -10.0
                        val target = (currentTime + delta).coerceIn(0.0, totalDuration)
                        try { StreamXCore.seekMpvVideo(target - currentTime) } catch (e: Exception) { }
                        if (fwd) { forwardAnimAlpha = 1f; scope.launch { delay(500); forwardAnimAlpha = 0f } }
                        else     { rewindAnimAlpha  = 1f; scope.launch { delay(500); rewindAnimAlpha  = 0f } }
                    }
                )
            }
            .pointerInput(Unit) {
                detectVerticalDragGestures(
                    onDragStart = { showGestureOverlay = true; dragAccumulator = 0f },
                    onDragEnd   = { scope.launch { delay(500); showGestureOverlay = false }; dragAccumulator = 0f }
                ) { change, dragAmount ->
                    if (isLocked || showSettingsMenu) return@detectVerticalDragGestures
                    dragAccumulator += dragAmount
                    val now = System.currentTimeMillis()
                    if (now - lastUpdateTime > 50 && abs(dragAccumulator) > 5f) {
                        lastUpdateTime = now
                        if (change.position.x > size.width / 2) {
                            val delta = (-(dragAccumulator / (size.height / 2)) * maxVolume).toInt()
                            if (delta != 0) {
                                val newV = (audioManager.getStreamVolume(AudioManager.STREAM_MUSIC) + delta).coerceIn(0, maxVolume)
                                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, newV, 0)
                                volumeLevel = newV / maxVolume.toFloat()
                                gestureIcon = Icons.Rounded.VolumeUp
                                gestureText = "${(volumeLevel * 100).toInt()}%"
                                dragAccumulator = 0f
                            }
                        } else {
                            brightnessLevel = (brightnessLevel - (dragAccumulator / (size.height / 2))).coerceIn(0.01f, 1f)
                            val lp = activity?.window?.attributes
                            lp?.screenBrightness = brightnessLevel
                            activity?.window?.attributes = lp
                            gestureIcon = Icons.Rounded.BrightnessMedium
                            gestureText = "${(brightnessLevel * 100).toInt()}%"
                            dragAccumulator = 0f
                        }
                    }
                }
            }
        )

        if (showGestureOverlay) {
            Box(Modifier.align(Alignment.Center).background(Color.Black.copy(0.7f), RoundedCornerShape(16.dp)).padding(24.dp)) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    gestureIcon?.let { Icon(it, null, tint = Color.Cyan, modifier = Modifier.size(48.dp)) }
                    Text(gestureText, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                }
            }
        }

        if (rewindAnimAlpha > 0)  { Box(Modifier.align(Alignment.CenterStart).padding(50.dp).alpha(rewindAnimAlpha).background(Color.Black.copy(0.5f), CircleShape).padding(16.dp)) { Icon(Icons.Rounded.FastRewind,  null, tint = Color.White, modifier = Modifier.size(40.dp)) } }
        if (forwardAnimAlpha > 0) { Box(Modifier.align(Alignment.CenterEnd).padding(50.dp).alpha(forwardAnimAlpha).background(Color.Black.copy(0.5f), CircleShape).padding(16.dp)) { Icon(Icons.Rounded.FastForward, null, tint = Color.White, modifier = Modifier.size(40.dp)) } }

        // ── Pre-buffer overlay ────────────────────────────────
        if (isPreBuffering && videoPath == null) {
            Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(32.dp)) {
                    CircularProgressIndicator(color = Color.Cyan, strokeWidth = 3.dp)
                    Spacer(Modifier.height(20.dp))
                    Text(statusMsg, color = Color.White, textAlign = TextAlign.Center, fontSize = 14.sp)
                    if (torrentProgress > 0) {
                        Spacer(Modifier.height(12.dp))
                        LinearProgressIndicator(
                            progress = { torrentProgress / 100f },
                            modifier = Modifier.fillMaxWidth(0.7f).height(4.dp),
                            color = Color.Cyan,
                            trackColor = Color.White.copy(0.2f)
                        )
                        Spacer(Modifier.height(8.dp))
                        // BUG FIX: removed "target 5%" and "%/5%" labels
                        Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                            Text("▼ $downloadSpeed", color = Color.Green, fontSize = 13.sp)
                            Text("S: $seeds", color = Color.LightGray, fontSize = 13.sp)
                        }
                    }
                }
                IconButton(onClick = { navController.popBackStack() }, modifier = Modifier.align(Alignment.TopStart).padding(16.dp)) {
                    Icon(Icons.Default.ArrowBack, "Back", tint = Color.White)
                }
            }
        }

        // ── Mid-play buffering overlay ────────────────────────
        if (isMidBuffering && videoPath != null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Box(Modifier.background(Color.Black.copy(0.75f), RoundedCornerShape(16.dp)).padding(horizontal = 32.dp, vertical = 20.dp)) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(color = Color.Cyan, modifier = Modifier.size(36.dp), strokeWidth = 3.dp)
                        Spacer(Modifier.height(10.dp))
                        Text("Buffering $cachePercent%", color = Color.White, fontSize = 14.sp)
                        Text("▼ $downloadSpeed  S: $seeds", color = Color.Green, fontSize = 12.sp)
                    }
                }
            }
        }

        // ── Player controls ───────────────────────────────────
        if (!isPreBuffering || videoPath != null) {
            AnimatedVisibility(visible = isControlsVisible && !showSettingsMenu, enter = fadeIn(), exit = fadeOut()) {
                Box(Modifier.fillMaxSize().background(Color.Black.copy(0.4f))) {

                    Row(Modifier.fillMaxWidth().padding(16.dp).align(Alignment.TopStart), horizontalArrangement = Arrangement.SpaceBetween) {
                        IconButton(onClick = { navController.popBackStack() }) { Icon(Icons.Default.ArrowBack, null, tint = Color.White) }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (decodedUrl.startsWith("magnet")) {
                                Column(horizontalAlignment = Alignment.End, modifier = Modifier.padding(end = 12.dp)) {
                                    Text("▼ $downloadSpeed", color = Color.Green, fontSize = 12.sp)
                                    Text("S: $seeds", color = Color.LightGray, fontSize = 10.sp)
                                }
                            }
                            IconButton(onClick = {
                                try { StreamXCore.cycleSubtitles() } catch (e: Exception) { }
                                Toast.makeText(context, "Subtitle changed", Toast.LENGTH_SHORT).show()
                            }) { Icon(Icons.Rounded.Subtitles, null, tint = Color.White, modifier = Modifier.size(28.dp)) }
                            IconButton(onClick = { showSettingsMenu = true; activeSettingPage = "Main" }) {
                                Icon(Icons.Rounded.Settings, null, tint = Color.White, modifier = Modifier.size(28.dp))
                            }
                        }
                    }

                    if (!isLocked) {
                        Row(Modifier.align(Alignment.Center), horizontalArrangement = Arrangement.spacedBy(40.dp)) {
                            IconButton(onClick = {
                                val t = max(0.0, currentTime - 10.0)
                                try { StreamXCore.seekMpvVideo(t - currentTime) } catch (e: Exception) { }
                            }) { Icon(Icons.Rounded.Replay10, null, tint = Color.White, modifier = Modifier.size(48.dp)) }
                            IconButton(onClick = { isPlaying = !isPlaying; try { StreamXCore.pauseMpvVideo(!isPlaying) } catch (e: Exception) { } }) {
                                Icon(if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, null, tint = Color.White, modifier = Modifier.size(64.dp))
                            }
                            IconButton(onClick = {
                                val t = min(totalDuration, currentTime + 10.0)
                                try { StreamXCore.seekMpvVideo(t - currentTime) } catch (e: Exception) { }
                            }) { Icon(Icons.Rounded.Forward10, null, tint = Color.White, modifier = Modifier.size(48.dp)) }
                        }
                    }

                    IconButton(onClick = { isLocked = !isLocked }, modifier = Modifier.align(Alignment.CenterEnd).padding(32.dp)) {
                        Icon(if (isLocked) Icons.Rounded.Lock else Icons.Rounded.LockOpen, null, tint = if (isLocked) Color.Red else Color.White)
                    }

                    if (!isLocked) {
                        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(16.dp)) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(formatTime(currentTime.toLong()),   color = Color.White, fontSize = 12.sp)
                                Text(formatTime(totalDuration.toLong()), color = Color.White, fontSize = 12.sp)
                            }
                            Slider(
                                value = currentTime.toFloat(),
                                onValueChange = { v ->
                                    val clamped = v.coerceIn(0f, totalDuration.toFloat())
                                    try { StreamXCore.seekMpvVideo(clamped.toDouble() - currentTime) } catch (e: Exception) { }
                                },
                                valueRange = 0f..max(1f, totalDuration.toFloat()),
                                colors = SliderDefaults.colors(thumbColor = Color.Cyan, activeTrackColor = Color.Cyan)
                            )
                        }
                    }
                }
            }

            // ── Settings panel ────────────────────────────────
            AnimatedVisibility(
                visible = showSettingsMenu,
                enter = slideInHorizontally(initialOffsetX = { it }) + fadeIn(),
                exit  = slideOutHorizontally(targetOffsetX  = { it }) + fadeOut(),
                modifier = Modifier.align(Alignment.CenterEnd)
            ) {
                Box(Modifier.fillMaxHeight().width(320.dp).background(Color(0xF01A1A2E)).padding(16.dp)) {
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 16.dp)) {
                            if (activeSettingPage != "Main") {
                                IconButton(onClick = { activeSettingPage = "Main" }, modifier = Modifier.size(24.dp)) {
                                    Icon(Icons.Default.ArrowBack, "Back", tint = Color.White)
                                }
                                Spacer(Modifier.width(12.dp))
                            }
                            Text(
                                when (activeSettingPage) {
                                    "Quality"   -> "Video Quality"
                                    "Subtitles" -> "Subtitles"
                                    "Audio"     -> "Audio Tracks"
                                    else        -> "Settings"
                                },
                                color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold
                            )
                        }

                        // Main settings
                        if (activeSettingPage == "Main") {
                            SettingsItem(Icons.Rounded.HighQuality,  "Video Quality", selectedQuality.label)    { activeSettingPage = "Quality" }
                            SettingsItem(Icons.Rounded.Subtitles,    "Subtitles",     "Tracks & Download")      {
                                subTracks = StreamXCore.getTrackList("sub")
                                activeSettingPage = "Subtitles"
                            }
                            // ── CRASH FIX ──────────────────────────────────────────────────
                            // Audio track click used to call getTrackList() which called
                            // getPropertyStringNative() in a loop from the UI click handler.
                            // Concurrent MPV calls from the time-sync coroutine caused a
                            // data race → Scudo "corrupted chunk header" crash.
                            //
                            // FIX: getTrackList() now calls getTrackListNative() — a single
                            // JNI call that does ALL MPV property reads under ONE C++ mutex.
                            // No loop, no race, no crash.
                            SettingsItem(Icons.Rounded.LibraryMusic, "Audio Track",   "Internal tracks")        {
                                activeSettingPage = "Audio"
                            }
                        }

                        // Quality page
                        else if (activeSettingPage == "Quality") {
                            Text("GPU Render Quality", color = Color.Gray, fontSize = 11.sp, letterSpacing = 1.sp, modifier = Modifier.padding(bottom = 8.dp))
                            LazyColumn {
                                items(GPU_QUALITY_PRESETS) { preset ->
                                    val sel = selectedQuality.label == preset.label
                                    Row(
                                        Modifier.fillMaxWidth()
                                            .background(if (sel) Color.Cyan.copy(0.12f) else Color.Transparent, RoundedCornerShape(10.dp))
                                            .clickable { selectedQuality = preset; applyQualityPreset(preset); showSettingsMenu = false }
                                            .padding(horizontal = 12.dp, vertical = 10.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(Modifier.weight(1f)) {
                                            Text(preset.label,    color = if (sel) Color.Cyan else Color.White,   fontSize = 15.sp, fontWeight = if (sel) FontWeight.Bold else FontWeight.Normal)
                                            Text(preset.subtitle, color = Color.Gray, fontSize = 11.sp)
                                        }
                                        if (sel) Icon(Icons.Rounded.Check, null, tint = Color.Cyan, modifier = Modifier.size(20.dp))
                                    }
                                }
                            }
                        }

                        // Subtitles page
                        else if (activeSettingPage == "Subtitles") {
                            Button(
                                onClick = {
                                    if (!isSearchingSub) {
                                        isSearchingSub = true; subSearchMsg = "Searching…"
                                        val title = movieTitle.ifBlank { "Movie" }
                                        fetchSubtitle(title, context) { msg ->
                                            isSearchingSub = false; subSearchMsg = msg
                                            subTracks = StreamXCore.getTrackList("sub")
                                        }
                                    }
                                },
                                modifier = Modifier.fillMaxWidth(),
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1E3A5F))
                            ) {
                                if (isSearchingSub) {
                                    CircularProgressIndicator(color = Color.Cyan, modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                                    Spacer(Modifier.width(8.dp))
                                    Text("Searching…", color = Color.White, fontSize = 12.sp)
                                } else {
                                    Icon(Icons.Rounded.Download, null, tint = Color.Cyan, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.width(8.dp))
                                    Text("Download Subtitle Online", color = Color.White)
                                }
                            }
                            if (subSearchMsg.isNotEmpty()) Text(subSearchMsg, color = Color.Cyan, fontSize = 11.sp, modifier = Modifier.padding(top = 6.dp))

                            Spacer(Modifier.height(14.dp))
                            HorizontalDivider(color = Color.White.copy(0.1f))
                            Spacer(Modifier.height(10.dp))
                            Text("SUBTITLE TRACKS", color = Color.Gray, fontSize = 10.sp, letterSpacing = 1.sp)
                            Spacer(Modifier.height(8.dp))
                            SubTrackRow("Disable", false) { StreamXCore.setSubTrack(-1); showSettingsMenu = false }
                            if (subTracks.isEmpty()) {
                                Text("No tracks found", color = Color.Gray, fontSize = 12.sp, modifier = Modifier.padding(vertical = 8.dp))
                            } else {
                                subTracks.forEach { t -> SubTrackRow(t.title, t.selected) { StreamXCore.setSubTrack(t.id); showSettingsMenu = false } }
                            }
                        }

                        // Audio tracks page
                        else if (activeSettingPage == "Audio") {
                            // Load track list here — single safe call under C++ mutex
                            val audioTracks = remember(activeSettingPage) {
                                StreamXCore.getTrackList("audio")
                            }
                            Text("AUDIO TRACKS", color = Color.Gray, fontSize = 10.sp, letterSpacing = 1.sp)
                            Spacer(Modifier.height(8.dp))
                            if (audioTracks.isEmpty()) {
                                Text("No audio tracks found", color = Color.Gray, fontSize = 12.sp)
                            } else {
                                audioTracks.forEach { t ->
                                    SubTrackRow(t.title, t.selected) { StreamXCore.setAudioTrack(t.id); showSettingsMenu = false }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────
@Composable
fun SettingsItem(icon: ImageVector, title: String, subtitle: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = Color.White, modifier = Modifier.size(24.dp))
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = Color.White, fontSize = 16.sp)
            Text(subtitle, color = Color.LightGray, fontSize = 12.sp)
        }
        Icon(Icons.Rounded.ChevronRight, null, tint = Color.Gray)
    }
}

@Composable
private fun SubTrackRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 10.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            if (selected) Icons.Rounded.RadioButtonChecked else Icons.Rounded.RadioButtonUnchecked,
            null, tint = if (selected) Color.Cyan else Color.Gray, modifier = Modifier.size(18.dp)
        )
        Spacer(Modifier.width(12.dp))
        Text(label, color = if (selected) Color.Cyan else Color.White, fontSize = 14.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
    }
}

// ─────────────────────────────────────────────────────────────
private fun fetchSubtitle(title: String, context: Context, onComplete: (String) -> Unit) {
    CoroutineScope(Dispatchers.IO).launch {
        try {
            val encoded = title.trim().replace(" ", "%20")
            val urlStr  = "https://rest.opensubtitles.org/search/query-$encoded/sublanguageid-eng"
            val conn    = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                setRequestProperty("User-Agent",   "TemporaryUserAgent")
                setRequestProperty("X-User-Agent", "TemporaryUserAgent")
                connectTimeout = 10_000; readTimeout = 10_000
            }
            if (conn.responseCode == 200) {
                val arr = JSONArray(conn.inputStream.bufferedReader().use { it.readText() })
                var bestUrl = ""; var bestCount = -1
                for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)
                    val lang = obj.optString("SubLanguageID", "")
                    val fmt  = obj.optString("SubFormat", "")
                    val dl   = obj.optInt("SubDownloadsCnt", 0)
                    val url  = obj.optString("SubDownloadLink", "")
                    if (lang == "eng" && fmt.equals("srt", ignoreCase = true) && dl > bestCount && url.isNotEmpty()) {
                        bestCount = dl; bestUrl = url
                    }
                }
                if (bestUrl.isEmpty() && arr.length() > 0) bestUrl = arr.getJSONObject(0).optString("SubDownloadLink", "")
                withContext(Dispatchers.Main) {
                    if (bestUrl.isNotEmpty()) { try { StreamXCore.addExternalSubtitle(bestUrl) } catch (e: Exception) { }; onComplete("✓ Subtitle loaded!") }
                    else onComplete("No subtitle found for \"$title\"")
                }
            } else withContext(Dispatchers.Main) { onComplete("Server error: ${conn.responseCode}") }
        } catch (e: Exception) {
            withContext(Dispatchers.Main) { onComplete("Error: ${e.message?.take(50)}") }
        }
    }
}

private fun formatTime(secs: Long): String {
    val s = secs % 60; val m = (secs / 60) % 60; val h = secs / 3600
    return if (h > 0) String.format("%d:%02d:%02d", h, m, s) else String.format("%02d:%02d", m, s)
}
