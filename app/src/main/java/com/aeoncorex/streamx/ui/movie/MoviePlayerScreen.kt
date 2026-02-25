package com.aeoncorex.streamx.ui.movie

import android.app.Activity
import android.content.Context
import android.content.pm.ActivityInfo
import android.media.AudioManager
import android.os.Bundle
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Toast
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

// --- RUST BRIDGE & MPV NATIVE ---
object StreamXCore {
    init {
        try { System.loadLibrary("streamx_core") } catch (e: Throwable) { e.printStackTrace() }
        try { System.loadLibrary("streamx-native") } catch (e: Throwable) { e.printStackTrace() }
    }
    external fun getTmdbKey(): String 
    
    // MPV Native Functions
    external fun initMpvEngine()
    external fun playMpvVideo(path: String)
    external fun setMpvSurface(surface: Surface)
    external fun toggleVulkanFSR(enable: Boolean)
    external fun switchMpvAudio(lang: String)
    external fun seekMpvVideo(seconds: Double)
    external fun pauseMpvVideo(pause: Boolean)
    external fun getMpvTime(): Double
    external fun getMpvDuration(): Double
    external fun setSubtitleNative(url: String)
    external fun setMpvPropertyString(name: String, value: String) // For Real-time Quality Change
}

@Composable
fun MoviePlayerScreen(navController: NavController, encodedUrl: String) {
    val context = LocalContext.current
    val activity = context as? Activity
    val decodedUrl = remember { try { URLDecoder.decode(encodedUrl, "UTF-8") } catch (e: Exception) { encodedUrl } }
    val scope = rememberCoroutineScope()

    var videoPath by remember { mutableStateOf<String?>(null) }
    var isPlaying by remember { mutableStateOf(true) }
    var isBuffering by remember { mutableStateOf(true) }
    var currentTime by remember { mutableDoubleStateOf(0.0) }
    var totalDuration by remember { mutableDoubleStateOf(0.0) }
    
    var isControlsVisible by remember { mutableStateOf(true) }
    var isLocked by remember { mutableStateOf(false) }
    var isVulkanEnabled by remember { mutableStateOf(false) }

    // Settings Menu States (YouTube Style)
    var showSettingsMenu by remember { mutableStateOf(false) }
    var activeSettingPage by remember { mutableStateOf("Main") } // "Main", "Quality", "Subtitles"
    var isSearchingSub by remember { mutableStateOf(false) }
    var selectedQuality by remember { mutableStateOf("Auto") }

    // Gestures States
    var volumeLevel by remember { mutableFloatStateOf(0.5f) }
    var brightnessLevel by remember { mutableFloatStateOf(0.5f) }
    var gestureIcon by remember { mutableStateOf<ImageVector?>(null) }
    var gestureText by remember { mutableStateOf("") }
    var showGestureOverlay by remember { mutableStateOf(false) }
    var forwardAnimAlpha by remember { mutableFloatStateOf(0f) }
    var rewindAnimAlpha by remember { mutableFloatStateOf(0f) }

    // Torrent States
    var statusMsg by remember { mutableStateOf("Preparing...") }
    var downloadSpeed by remember { mutableStateOf("0 KB/s") }
    var seeds by remember { mutableIntStateOf(0) }

    val audioManager = remember { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    val maxVolume = remember { audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC) }

    // --- INIT MPV ---
    LaunchedEffect(Unit) {
        StreamXCore.initMpvEngine()
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val insetsController = activity?.window?.let { WindowCompat.getInsetsController(it, it.decorView) }
        insetsController?.hide(WindowInsetsCompat.Type.systemBars())
    }

    DisposableEffect(Unit) {
        onDispose {
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            StreamXCore.pauseMpvVideo(true)
            TorrentEngine.stop()
            TorrentEngine.clearCache(context)
        }
    }

    // --- Torrent / Playback Handling ---
    LaunchedEffect(decodedUrl) {
        if (decodedUrl.startsWith("magnet:?")) {
            withContext(Dispatchers.IO) { TorrentEngine.clearCache(context) }
            TorrentEngine.start(context, decodedUrl).collect { state ->
                when (state) {
                    is StreamState.Preparing -> statusMsg = state.message
                    is StreamState.Buffering -> {
                        isBuffering = true
                        statusMsg = "Buffering ${state.progress}%"
                        downloadSpeed = "${state.speed / 1024} KB/s"
                        seeds = state.seeds
                    }
                    is StreamState.Ready -> {
                        if (videoPath != state.filePath) {
                            videoPath = state.filePath
                            StreamXCore.playMpvVideo(state.filePath)
                        }
                        statusMsg = ""
                        isBuffering = false
                    }
                    is StreamState.Error -> statusMsg = "Error: ${state.message}"
                }
            }
        } else {
            videoPath = decodedUrl
            StreamXCore.playMpvVideo(decodedUrl)
            statusMsg = ""
            isBuffering = false
        }
    }

    // --- Time Sync Loop for MPV ---
    LaunchedEffect(isPlaying) {
        while (isPlaying) {
            currentTime = StreamXCore.getMpvTime()
            val dur = StreamXCore.getMpvDuration()
            if (dur > 0) totalDuration = dur
            delay(1000)
        }
    }

    // --- Auto Hide Controls ---
    LaunchedEffect(isControlsVisible, showSettingsMenu) {
        if (isControlsVisible && !showSettingsMenu) {
            delay(4000)
            isControlsVisible = false
        }
    }

    // --- UI Rendering ---
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        
        // --- NATIVE MPV SURFACE ---
        AndroidView(
            factory = { ctx ->
                SurfaceView(ctx).apply {
                    holder.addCallback(object : SurfaceHolder.Callback {
                        override fun surfaceCreated(holder: SurfaceHolder) {
                            StreamXCore.setMpvSurface(holder.surface)
                        }
                        override fun surfaceChanged(h: SurfaceHolder, format: Int, w: Int, height: Int) {}
                        override fun surfaceDestroyed(h: SurfaceHolder) {}
                    })
                    layoutParams = ViewGroup.LayoutParams(-1, -1)
                }
            },
            modifier = Modifier.fillMaxSize()
        )

        var lastUpdateTime by remember { mutableLongStateOf(0L) }
        var dragAccumulator by remember { mutableFloatStateOf(0f) }

        // --- Gestures Box ---
        Box(
            modifier = Modifier.fillMaxSize()
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = { 
                            if(showSettingsMenu) showSettingsMenu = false 
                            else isControlsVisible = !isControlsVisible 
                        },
                        onDoubleTap = { offset ->
                            if (isLocked || showSettingsMenu) return@detectTapGestures
                            val isForward = offset.x > size.width / 2
                            StreamXCore.seekMpvVideo(if(isForward) 10.0 else -10.0)
                            if (isForward) { forwardAnimAlpha = 1f; scope.launch { delay(500); forwardAnimAlpha = 0f } } 
                            else { rewindAnimAlpha = 1f; scope.launch { delay(500); rewindAnimAlpha = 0f } }
                        }
                    )
                }
                .pointerInput(Unit) {
                    detectVerticalDragGestures(
                        onDragStart = { showGestureOverlay = true; dragAccumulator = 0f },
                        onDragEnd = { scope.launch { delay(500); showGestureOverlay = false }; dragAccumulator = 0f }
                    ) { change, dragAmount ->
                        if (isLocked || showSettingsMenu) return@detectVerticalDragGestures
                        dragAccumulator += dragAmount
                        val currentMs = System.currentTimeMillis()
                        if (currentMs - lastUpdateTime > 50 && abs(dragAccumulator) > 5f) {
                            lastUpdateTime = currentMs
                            if (change.position.x > size.width / 2) { 
                                val v = (-(dragAccumulator / (size.height / 2)) * maxVolume).toInt()
                                if (v != 0) {
                                    val newV = (audioManager.getStreamVolume(AudioManager.STREAM_MUSIC) + v).coerceIn(0, maxVolume)
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

        // Gesture Overlay
        if (showGestureOverlay) {
            Box(Modifier.align(Alignment.Center).background(Color.Black.copy(0.7f), RoundedCornerShape(16.dp)).padding(24.dp)) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    gestureIcon?.let { Icon(it, null, tint = Color.Cyan, modifier = Modifier.size(48.dp)) }
                    Text(gestureText, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                }
            }
        }

        // Double Tap Animations
        if (rewindAnimAlpha > 0) { Box(Modifier.align(Alignment.CenterStart).padding(50.dp).alpha(rewindAnimAlpha).background(Color.Black.copy(0.5f), CircleShape).padding(16.dp)) { Icon(Icons.Rounded.FastRewind, null, tint = Color.White, modifier = Modifier.size(40.dp)) } }
        if (forwardAnimAlpha > 0) { Box(Modifier.align(Alignment.CenterEnd).padding(50.dp).alpha(forwardAnimAlpha).background(Color.Black.copy(0.5f), CircleShape).padding(16.dp)) { Icon(Icons.Rounded.FastForward, null, tint = Color.White, modifier = Modifier.size(40.dp)) } }

        // --- CONTROLS OVERLAY ---
        if (isBuffering && videoPath == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = Color.Cyan)
                    Spacer(Modifier.height(16.dp))
                    Text(statusMsg, color = Color.White)
                }
                IconButton(onClick = { navController.popBackStack() }, modifier = Modifier.align(Alignment.TopStart).padding(16.dp)) { Icon(Icons.Default.ArrowBack, "Back", tint = Color.White) }
            }
        } else {
            AnimatedVisibility(visible = isControlsVisible && !showSettingsMenu, enter = fadeIn(), exit = fadeOut()) {
                Box(Modifier.fillMaxSize().background(Color.Black.copy(0.4f))) {
                    
                    // Top Bar
                    Row(Modifier.fillMaxWidth().padding(16.dp).align(Alignment.TopStart), horizontalArrangement = Arrangement.SpaceBetween) {
                        IconButton(onClick = { navController.popBackStack() }) { Icon(Icons.Default.ArrowBack, null, tint = Color.White) }
                        
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (decodedUrl.startsWith("magnet")) {
                                Column(horizontalAlignment = Alignment.End, modifier = Modifier.padding(end = 12.dp)) {
                                    Text("▼ $downloadSpeed", color = Color.Green, fontSize = 12.sp)
                                    Text("S: $seeds", color = Color.LightGray, fontSize = 10.sp)
                                }
                            }
                            // YouTube Style Settings Button
                            IconButton(onClick = { 
                                showSettingsMenu = true 
                                activeSettingPage = "Main"
                            }) { 
                                Icon(Icons.Rounded.Settings, "Settings", tint = Color.White, modifier = Modifier.size(28.dp)) 
                            }
                        }
                    }

                    // Center Play/Pause
                    if (!isLocked) {
                        Row(Modifier.align(Alignment.Center), horizontalArrangement = Arrangement.spacedBy(40.dp)) {
                            IconButton(onClick = { StreamXCore.seekMpvVideo(-10.0) }) { Icon(Icons.Rounded.Replay10, null, tint = Color.White, modifier = Modifier.size(48.dp)) }
                            IconButton(onClick = { isPlaying = !isPlaying; StreamXCore.pauseMpvVideo(!isPlaying) }) { Icon(if(isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, null, tint = Color.White, modifier = Modifier.size(64.dp)) }
                            IconButton(onClick = { StreamXCore.seekMpvVideo(10.0) }) { Icon(Icons.Rounded.Forward10, null, tint = Color.White, modifier = Modifier.size(48.dp)) }
                        }
                    }

                    // Lock Button
                    IconButton(onClick = { isLocked = !isLocked }, modifier = Modifier.align(Alignment.CenterEnd).padding(32.dp)) { Icon(if(isLocked) Icons.Rounded.Lock else Icons.Rounded.LockOpen, null, tint = if(isLocked) Color.Red else Color.White) }

                    // Bottom Bar (Seekbar)
                    if (!isLocked) {
                        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(16.dp)) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(formatTime(currentTime.toLong()), color = Color.White, fontSize = 12.sp)
                                Text(formatTime(totalDuration.toLong()), color = Color.White, fontSize = 12.sp)
                            }
                            Slider(
                                value = currentTime.toFloat(),
                                onValueChange = { val jump = it - currentTime; StreamXCore.seekMpvVideo(jump.toDouble()) },
                                valueRange = 0f..max(1f, totalDuration.toFloat()),
                                colors = SliderDefaults.colors(thumbColor = Color.Cyan, activeTrackColor = Color.Cyan)
                            )
                        }
                    }
                }
            }
        }

        // --- YOUTUBE STYLE SETTINGS MENU ---
        AnimatedVisibility(
            visible = showSettingsMenu,
            enter = slideInHorizontally(initialOffsetX = { it }) + fadeIn(),
            exit = slideOutHorizontally(targetOffsetX = { it }) + fadeOut(),
            modifier = Modifier.align(Alignment.CenterEnd)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .width(300.dp)
                    .background(Color(0xEE1E1E1E))
                    .padding(16.dp)
            ) {
                Column {
                    // Header
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 16.dp)) {
                        if (activeSettingPage != "Main") {
                            IconButton(onClick = { activeSettingPage = "Main" }, modifier = Modifier.size(24.dp)) {
                                Icon(Icons.Default.ArrowBack, "Back", tint = Color.White)
                            }
                            Spacer(Modifier.width(16.dp))
                        }
                        Text(
                            text = if (activeSettingPage == "Main") "Settings" else activeSettingPage, 
                            color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold
                        )
                    }

                    // Main Menu
                    if (activeSettingPage == "Main") {
                        SettingsItem(icon = Icons.Rounded.HighQuality, title = "Quality", subtitle = selectedQuality) { activeSettingPage = "Quality" }
                        SettingsItem(icon = Icons.Rounded.Subtitles, title = "Subtitles", subtitle = "OpenSubtitles") { activeSettingPage = "Subtitles" }
                        SettingsItem(icon = Icons.Rounded.Speed, title = "Playback Speed", subtitle = "Normal") { /* Optional Future Update */ }
                        
                        // Vulkan Toggle inside settings
                        Row(Modifier.fillMaxWidth().clickable { 
                            isVulkanEnabled = !isVulkanEnabled; StreamXCore.toggleVulkanFSR(isVulkanEnabled) 
                        }.padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.Memory, null, tint = Color.White, modifier = Modifier.size(24.dp))
                            Spacer(Modifier.width(16.dp))
                            Text("Vulkan Hardware FSR", color = Color.White, fontSize = 16.sp, modifier = Modifier.weight(1f))
                            Switch(checked = isVulkanEnabled, onCheckedChange = null, colors = SwitchDefaults.colors(checkedThumbColor = Color.Cyan))
                        }
                    }
                    
                    // Quality Menu
                    else if (activeSettingPage == "Quality") {
                        val qualities = listOf("Auto", "1080p", "720p", "480p", "360p")
                        LazyColumn {
                            items(qualities) { quality ->
                                Row(
                                    modifier = Modifier.fillMaxWidth().clickable {
                                        selectedQuality = quality
                                        // Send Command to MPV based on selection
                                        val ytdlFormat = if (quality == "Auto") "bestvideo+bestaudio/best" else "bestvideo[height<=?${quality.replace("p","")}]+bestaudio/best"
                                        StreamXCore.setMpvPropertyString("ytdl-format", ytdlFormat)
                                        showSettingsMenu = false
                                    }.padding(vertical = 12.dp)
                                ) {
                                    Text(quality, color = if(selectedQuality == quality) Color.Cyan else Color.White, fontSize = 16.sp)
                                }
                            }
                        }
                    }

                    // Subtitles Menu
                    else if (activeSettingPage == "Subtitles") {
                        Column {
                            Button(
                                onClick = { 
                                    if(!isSearchingSub) {
                                        isSearchingSub = true
                                        // "movieTitle" should be passed as argument ideally, here we extract from URL or use placeholder
                                        val title = "Movie" 
                                        fetchAndApplySubtitle(title, context) {
                                            isSearchingSub = false
                                            showSettingsMenu = false
                                        }
                                    }
                                },
                                modifier = Modifier.fillMaxWidth(),
                                colors = ButtonDefaults.buttonColors(containerColor = Color.DarkGray)
                            ) {
                                if(isSearchingSub) CircularProgressIndicator(color = Color.Cyan, modifier = Modifier.size(20.dp))
                                else Text("Search Auto (OpenSubtitles)", color = Color.White)
                            }
                            
                            Spacer(Modifier.height(16.dp))
                            Text("Or change Subtitle Track:", color = Color.LightGray, fontSize = 14.sp)
                            LazyColumn(modifier = Modifier.padding(top = 8.dp)) {
                                items(listOf("Disable", "Track 1", "Track 2")) { track ->
                                    Text(
                                        text = track,
                                        color = Color.White,
                                        fontSize = 16.sp,
                                        modifier = Modifier.fillMaxWidth().clickable {
                                            val sid = if (track == "Disable") "no" else track.last().toString()
                                            StreamXCore.setMpvPropertyString("sid", sid)
                                            showSettingsMenu = false
                                        }.padding(vertical = 12.dp)
                                    )
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
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, tint = Color.White, modifier = Modifier.size(24.dp))
        Spacer(Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, color = Color.White, fontSize = 16.sp)
            Text(subtitle, color = Color.LightGray, fontSize = 12.sp)
        }
        Icon(Icons.Rounded.ChevronRight, null, tint = Color.Gray)
    }
}

private fun fetchAndApplySubtitle(movieTitle: String, context: Context, onComplete: () -> Unit) {
    CoroutineScope(Dispatchers.IO).launch {
        try {
            val cleanTitle = movieTitle.replace(" ", "%20")
            val url = URL("https://rest.opensubtitles.org/subs/movie_name-$cleanTitle")
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.setRequestProperty("User-Agent", "TemporaryUserAgent") 

            if (connection.responseCode == 200) {
                val response = connection.inputStream.bufferedReader().use { it.readText() }
                val jsonArray = JSONArray(response)

                if (jsonArray.length() > 0) {
                    val subUrl = jsonArray.getJSONObject(0).getString("SubDownloadLink")
                    withContext(Dispatchers.Main) {
                        StreamXCore.setSubtitleNative(subUrl)
                        Toast.makeText(context, "Subtitle Loaded Successfully!", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    withContext(Dispatchers.Main) { Toast.makeText(context, "No Subtitle Found", Toast.LENGTH_SHORT).show() }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            withContext(Dispatchers.Main) { Toast.makeText(context, "Error fetching subtitle", Toast.LENGTH_SHORT).show() }
        } finally {
            withContext(Dispatchers.Main) { onComplete() }
        }
    }
}

private fun formatTime(timeSeconds: Long): String {
    val seconds = timeSeconds % 60
    val minutes = (timeSeconds / 60) % 60
    val hours = timeSeconds / 3600
    return if (hours > 0) String.format("%d:%02d:%02d", hours, minutes, seconds)
    else String.format("%02d:%02d", minutes, seconds)
}
