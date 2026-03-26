package com.aeoncorex.streamx.ui.movie

import android.app.Activity
import android.content.Context
import android.content.pm.ActivityInfo
import android.media.AudioManager
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

    fun cycleSubtitles()                = commandNative(arrayOf("cycle", "sub"))
    fun cycleAudio()                    = commandNative(arrayOf("cycle", "audio"))
    fun addExternalSubtitle(url: String) = commandNative(arrayOf("sub-add", url, "select"))
}

// ─────────────────────────────────────────────────────────────
@Composable
fun MoviePlayerScreen(navController: NavController, encodedUrl: String) {
    val context  = LocalContext.current
    val activity = context as? Activity
    val decodedUrl = remember { try { URLDecoder.decode(encodedUrl, "UTF-8") } catch (e: Exception) { encodedUrl } }
    val scope = rememberCoroutineScope()

    var videoPath       by remember { mutableStateOf<String?>(null) }
    var isPlaying       by remember { mutableStateOf(true) }
    var isBuffering     by remember { mutableStateOf(true) }
    var currentTime     by remember { mutableDoubleStateOf(0.0) }
    var totalDuration   by remember { mutableDoubleStateOf(0.0) }

    var isControlsVisible by remember { mutableStateOf(true) }
    var isLocked          by remember { mutableStateOf(false) }
    var isVulkanEnabled   by remember { mutableStateOf(false) }

    var showSettingsMenu  by remember { mutableStateOf(false) }
    var activeSettingPage by remember { mutableStateOf("Main") }
    var isSearchingSub    by remember { mutableStateOf(false) }
    var selectedQuality   by remember { mutableStateOf("Auto") }

    var volumeLevel       by remember { mutableFloatStateOf(0.5f) }
    var brightnessLevel   by remember { mutableFloatStateOf(0.5f) }
    var gestureIcon       by remember { mutableStateOf<ImageVector?>(null) }
    var gestureText       by remember { mutableStateOf("") }
    var showGestureOverlay by remember { mutableStateOf(false) }
    var forwardAnimAlpha  by remember { mutableFloatStateOf(0f) }
    var rewindAnimAlpha   by remember { mutableFloatStateOf(0f) }

    var statusMsg      by remember { mutableStateOf("Preparing...") }
    var downloadSpeed  by remember { mutableStateOf("0 KB/s") }
    var seeds          by remember { mutableIntStateOf(0) }

    // ── Surface ready tracking ────────────────────────────────
    // surfaceReady becomes true when SurfaceHolder.Callback.surfaceCreated
    // fires.  We must NOT call playMpvVideo() before this — if we do, MPV
    // has no window to render into and shows a black screen.
    var isSurfaceReady by remember { mutableStateOf(false) }

    val audioManager = remember { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    val maxVolume    = remember { audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC) }

    // ── Init MPV ─────────────────────────────────────────────
    LaunchedEffect(Unit) {
        try { StreamXCore.initMpvEngine() } catch (e: Exception) { Log.e("MPV", "Init error", e) }
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val ic = activity?.window?.let { WindowCompat.getInsetsController(it, it.decorView) }
        ic?.hide(WindowInsetsCompat.Type.systemBars())
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

    // ── Torrent / playback handling ───────────────────────────
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
                            if (++metadataTimeout > 180) {
                                statusMsg = if (retryCount < maxRetries - 1)
                                    "Timeout – retrying (${retryCount + 1}/$maxRetries)"
                                else "Timeout – last attempt"
                                TorrentEngine.stop()
                                completed = true
                            }
                        }
                        is StreamState.Buffering -> {
                            isBuffering   = true
                            statusMsg     = "Buffering ${state.progress}%"
                            downloadSpeed = "${state.speed} KB/s"
                            seeds         = state.seeds
                            metadataTimeout = 0
                        }
                        is StreamState.Ready -> {
                            videoPath   = state.filePath
                            statusMsg   = ""
                            isBuffering = false
                            completed   = true
                            // playMpvVideo() called below via LaunchedEffect(videoPath, isSurfaceReady)
                        }
                        is StreamState.Error -> {
                            statusMsg   = "Error: ${state.message}"
                            isBuffering = false
                            completed   = true
                        }
                    }
                }
                if (completed) break
                retryCount++
                delay(2000)
            } else {
                // Direct URL (HTTP stream, local file, YouTube…)
                videoPath   = decodedUrl
                statusMsg   = ""
                isBuffering = false
                break
            }
        }
        if (retryCount == maxRetries)
            statusMsg = "Failed after $maxRetries retries. Check connection."
    }

    // ── Start playback when BOTH file path AND surface are ready ──
    //
    //  ROOT CAUSE OF BLACK SCREEN:
    //  The old code called playMpvVideo() immediately inside collect{},
    //  but surfaceCreated may not have fired yet (surface creation is
    //  asynchronous with Compose rendering).  MPV received loadfile
    //  before wid was set → rendered to null surface → black screen.
    //
    //  FIX: watch both videoPath and isSurfaceReady.  Only call
    //  playMpvVideo() when both are non-null/true.
    LaunchedEffect(videoPath, isSurfaceReady) {
        val path = videoPath ?: return@LaunchedEffect
        if (!isSurfaceReady) return@LaunchedEffect

        Log.d("MPV", "Both surface and path ready → loadfile: $path")
        try { StreamXCore.playMpvVideo(path) } catch (e: Exception) {
            Log.e("MPV", "playMpvVideo failed: ${e.message}")
        }

        // ── Duration retry ────────────────────────────────────
        // After loadfile, poll for a valid duration for up to 15 s.
        // If duration stays 0, reload the file (happens when piece
        // download races ahead of demuxer on the first attempt).
        repeat(15) {
            delay(1000)
            val dur = try { StreamXCore.getMpvDuration() } catch (e: Exception) { 0.0 }
            if (dur > 0) {
                Log.d("MPV", "Duration confirmed: $dur s")
                return@LaunchedEffect
            }
        }
        // Still 0 after 15 s → reload
        Log.w("MPV", "Duration still 0 after 15s — reloading file")
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

    // ── Auto-hide controls ────────────────────────────────────
    LaunchedEffect(isControlsVisible, showSettingsMenu) {
        if (isControlsVisible && !showSettingsMenu) {
            delay(4000)
            isControlsVisible = false
        }
    }

    // ── UI ────────────────────────────────────────────────────
    Box(Modifier.fillMaxSize().background(Color.Black)) {

        // MPV SurfaceView
        AndroidView(
            factory = { ctx ->
                SurfaceView(ctx).apply {
                    holder.addCallback(object : SurfaceHolder.Callback {
                        override fun surfaceCreated(h: SurfaceHolder) {
                            try { StreamXCore.setMpvSurface(h.surface) } catch (e: Exception) { }
                            isSurfaceReady = true
                            Log.d("MPV", "surfaceCreated → isSurfaceReady=true")
                        }
                        override fun surfaceChanged(h: SurfaceHolder, fmt: Int, w: Int, height: Int) {
                            try { StreamXCore.setMpvSurfaceSize(w, height) } catch (e: Exception) { }
                        }
                        override fun surfaceDestroyed(h: SurfaceHolder) {
                            isSurfaceReady = false
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
        Box(modifier = Modifier.fillMaxSize()
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = {
                        if (showSettingsMenu) showSettingsMenu = false
                        else isControlsVisible = !isControlsVisible
                    },
                    onDoubleTap = { offset ->
                        if (isLocked || showSettingsMenu) return@detectTapGestures
                        val fwd = offset.x > size.width / 2
                        try { StreamXCore.seekMpvVideo(if (fwd) 10.0 else -10.0) } catch (e: Exception) { }
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
                            brightnessLevel = (brightnessLevel + (-(dragAccumulator / (size.height / 2)))).coerceIn(0.01f, 1f)
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

        if (rewindAnimAlpha > 0)  { Box(Modifier.align(Alignment.CenterStart).padding(50.dp).alpha(rewindAnimAlpha).background(Color.Black.copy(0.5f), CircleShape).padding(16.dp))  { Icon(Icons.Rounded.FastRewind,  null, tint = Color.White, modifier = Modifier.size(40.dp)) } }
        if (forwardAnimAlpha > 0) { Box(Modifier.align(Alignment.CenterEnd).padding(50.dp).alpha(forwardAnimAlpha).background(Color.Black.copy(0.5f), CircleShape).padding(16.dp)) { Icon(Icons.Rounded.FastForward, null, tint = Color.White, modifier = Modifier.size(40.dp)) } }

        // Loading overlay
        if (isBuffering && videoPath == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = Color.Cyan)
                    Spacer(Modifier.height(16.dp))
                    Text(statusMsg, color = Color.White, textAlign = TextAlign.Center)
                }
                IconButton(onClick = { navController.popBackStack() }, modifier = Modifier.align(Alignment.TopStart).padding(16.dp)) {
                    Icon(Icons.Default.ArrowBack, "Back", tint = Color.White)
                }
            }
        } else {
            // Controls overlay
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
                            IconButton(onClick = { try { StreamXCore.cycleSubtitles() } catch (e: Exception) { }; Toast.makeText(context, "Changing Subtitle…", Toast.LENGTH_SHORT).show() }) {
                                Icon(Icons.Rounded.Subtitles, null, tint = Color.White, modifier = Modifier.size(28.dp))
                            }
                            IconButton(onClick = { showSettingsMenu = true; activeSettingPage = "Main" }) {
                                Icon(Icons.Rounded.Settings, null, tint = Color.White, modifier = Modifier.size(28.dp))
                            }
                        }
                    }

                    if (!isLocked) {
                        Row(Modifier.align(Alignment.Center), horizontalArrangement = Arrangement.spacedBy(40.dp)) {
                            IconButton(onClick = { try { StreamXCore.seekMpvVideo(-10.0) } catch (e: Exception) { } }) { Icon(Icons.Rounded.Replay10,   null, tint = Color.White, modifier = Modifier.size(48.dp)) }
                            IconButton(onClick = { isPlaying = !isPlaying; try { StreamXCore.pauseMpvVideo(!isPlaying) } catch (e: Exception) { } }) { Icon(if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, null, tint = Color.White, modifier = Modifier.size(64.dp)) }
                            IconButton(onClick = { try { StreamXCore.seekMpvVideo(10.0)  } catch (e: Exception) { } }) { Icon(Icons.Rounded.Forward10, null, tint = Color.White, modifier = Modifier.size(48.dp)) }
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
                                onValueChange = { v -> val j = v - currentTime; try { StreamXCore.seekMpvVideo(j.toDouble()) } catch (e: Exception) { } },
                                valueRange = 0f..max(1f, totalDuration.toFloat()),
                                colors = SliderDefaults.colors(thumbColor = Color.Cyan, activeTrackColor = Color.Cyan)
                            )
                        }
                    }
                }
            }

            // Settings panel
            AnimatedVisibility(
                visible = showSettingsMenu,
                enter = slideInHorizontally(initialOffsetX = { it }) + fadeIn(),
                exit  = slideOutHorizontally(targetOffsetX  = { it }) + fadeOut(),
                modifier = Modifier.align(Alignment.CenterEnd)
            ) {
                Box(Modifier.fillMaxHeight().width(300.dp).background(Color(0xEE1E1E1E)).padding(16.dp)) {
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 16.dp)) {
                            if (activeSettingPage != "Main") {
                                IconButton(onClick = { activeSettingPage = "Main" }, modifier = Modifier.size(24.dp)) {
                                    Icon(Icons.Default.ArrowBack, "Back", tint = Color.White)
                                }
                                Spacer(Modifier.width(16.dp))
                            }
                            Text(if (activeSettingPage == "Main") "Settings" else activeSettingPage, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                        }

                        if (activeSettingPage == "Main") {
                            SettingsItem(Icons.Rounded.HighQuality,  "Quality",     selectedQuality)  { activeSettingPage = "Quality" }
                            SettingsItem(Icons.Rounded.Subtitles,    "Subtitles",   "Control Panel")  { activeSettingPage = "Subtitles" }
                            SettingsItem(Icons.Rounded.LibraryMusic, "Audio Track", "Change Audio")   { try { StreamXCore.cycleAudio() } catch (e: Exception) { }; showSettingsMenu = false }
                            Row(Modifier.fillMaxWidth().clickable {
                                isVulkanEnabled = !isVulkanEnabled
                                try { StreamXCore.toggleVulkanFSR(isVulkanEnabled) } catch (e: Exception) { }
                            }.padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Rounded.Memory, null, tint = Color.White, modifier = Modifier.size(24.dp))
                                Spacer(Modifier.width(16.dp))
                                Text("Vulkan FSR", color = Color.White, fontSize = 16.sp, modifier = Modifier.weight(1f))
                                Switch(checked = isVulkanEnabled, onCheckedChange = null, colors = SwitchDefaults.colors(checkedThumbColor = Color.Cyan))
                            }
                        } else if (activeSettingPage == "Quality") {
                            LazyColumn {
                                items(listOf("Auto", "1080p", "720p", "480p", "360p")) { q ->
                                    Row(Modifier.fillMaxWidth().clickable {
                                        selectedQuality = q
                                        val fmt = if (q == "Auto") "bestvideo+bestaudio/best" else "bestvideo[height<=?${q.replace("p", "")}]+bestaudio/best"
                                        try { StreamXCore.setPropertyStringNative("ytdl-format", fmt) } catch (e: Exception) { }
                                        showSettingsMenu = false
                                    }.padding(vertical = 12.dp)) {
                                        Text(q, color = if (selectedQuality == q) Color.Cyan else Color.White, fontSize = 16.sp)
                                    }
                                }
                            }
                        } else if (activeSettingPage == "Subtitles") {
                            Column {
                                Button(
                                    onClick = {
                                        if (!isSearchingSub) {
                                            isSearchingSub = true
                                            fetchAndApplySubtitle("Movie", context) { isSearchingSub = false; showSettingsMenu = false }
                                        }
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = ButtonDefaults.buttonColors(containerColor = Color.DarkGray)
                                ) {
                                    if (isSearchingSub) CircularProgressIndicator(color = Color.Cyan, modifier = Modifier.size(20.dp))
                                    else Text("Download Online Subtitle", color = Color.White)
                                }
                                Spacer(Modifier.height(16.dp))
                                Text("Internal Tracks:", color = Color.LightGray, fontSize = 14.sp)
                                LazyColumn(modifier = Modifier.padding(top = 8.dp)) {
                                    items(listOf("Disable", "Track 1", "Track 2", "Track 3")) { track ->
                                        Text(track, color = Color.White, fontSize = 16.sp,
                                            modifier = Modifier.fillMaxWidth().clickable {
                                                val sid = if (track == "Disable") "no" else track.last().toString()
                                                try { StreamXCore.setPropertyStringNative("sid", sid) } catch (e: Exception) { }
                                                showSettingsMenu = false
                                            }.padding(vertical = 12.dp))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun SettingsItem(icon: ImageVector, title: String, subtitle: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = Color.White, modifier = Modifier.size(24.dp))
        Spacer(Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title,    color = Color.White,     fontSize = 16.sp)
            Text(subtitle, color = Color.LightGray, fontSize = 12.sp)
        }
        Icon(Icons.Rounded.ChevronRight, null, tint = Color.Gray)
    }
}

private fun fetchAndApplySubtitle(movieTitle: String, context: Context, onComplete: () -> Unit) {
    CoroutineScope(Dispatchers.IO).launch {
        try {
            val url = URL("https://rest.opensubtitles.org/subs/movie_name-${movieTitle.replace(" ", "%20")}")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.setRequestProperty("User-Agent", "TemporaryUserAgent")
            if (conn.responseCode == 200) {
                val arr = JSONArray(conn.inputStream.bufferedReader().use { it.readText() })
                if (arr.length() > 0) {
                    val subUrl = arr.getJSONObject(0).getString("SubDownloadLink")
                    withContext(Dispatchers.Main) {
                        try { StreamXCore.addExternalSubtitle(subUrl) } catch (e: Exception) { }
                        Toast.makeText(context, "Subtitle Loaded!", Toast.LENGTH_SHORT).show()
                    }
                } else withContext(Dispatchers.Main) { Toast.makeText(context, "No Subtitle Found", Toast.LENGTH_SHORT).show() }
            }
        } catch (e: Exception) {
            withContext(Dispatchers.Main) { Toast.makeText(context, "Subtitle fetch failed", Toast.LENGTH_SHORT).show() }
        } finally {
            withContext(Dispatchers.Main) { onComplete() }
        }
    }
}

private fun formatTime(secs: Long): String {
    val s = secs % 60; val m = (secs / 60) % 60; val h = secs / 3600
    return if (h > 0) String.format("%d:%02d:%02d", h, m, s) else String.format("%02d:%02d", m, s)
}
