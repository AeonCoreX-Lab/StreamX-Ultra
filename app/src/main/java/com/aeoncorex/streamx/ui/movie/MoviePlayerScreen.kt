package com.aeoncorex.streamx.ui.movie

import android.app.Activity
import android.content.Context
import android.content.pm.ActivityInfo
import android.media.AudioManager
import android.net.Uri
import android.view.ViewGroup
import android.view.WindowManager
import androidx.annotation.OptIn
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.layout.*
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
import androidx.compose.ui.graphics.toArgb
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
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioSink
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.PlayerView
import androidx.navigation.NavController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URLDecoder
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max

// --- RUST BRIDGE (NEW) ---
object StreamXCore {
    init {
        // লোড অর্ডার ইম্পরট্যান্ট: Rust আগে, তারপর C++
        try { System.loadLibrary("streamx_core") } catch (e: Throwable) { e.printStackTrace() }
        try { System.loadLibrary("streamx-native") } catch (e: Throwable) { e.printStackTrace() }
    }

    // Rust Functions (যেগুলো Rust ফাইলে ডিফাইন করা হয়েছে)
    external fun initAI(modelPath: String): Boolean
    external fun pushAudio(data: FloatArray)
    external fun getSubtitle(): String
    external fun stopAI()
}

@OptIn(UnstableApi::class)
@Composable
fun MoviePlayerScreen(navController: NavController, encodedUrl: String) {
    val context = LocalContext.current
    val activity = context as? Activity
    val decodedUrl = remember { try { URLDecoder.decode(encodedUrl, "UTF-8") } catch (e: Exception) { encodedUrl } }
    val scope = rememberCoroutineScope()

    // --- State Management ---
    var videoPath by remember { mutableStateOf<String?>(null) }
    var exoPlayer by remember { mutableStateOf<ExoPlayer?>(null) }

    // UI States
    var isPlaying by remember { mutableStateOf(true) }
    var isBuffering by remember { mutableStateOf(true) }
    var currentTime by remember { mutableLongStateOf(0L) }
    var totalDuration by remember { mutableLongStateOf(0L) }
    var isControlsVisible by remember { mutableStateOf(true) }
    var resizeMode by remember { mutableIntStateOf(AspectRatioFrameLayout.RESIZE_MODE_FIT) }
    var isLocked by remember { mutableStateOf(false) }

    // AI States
    var isAiEnabled by remember { mutableStateOf(false) }
    var aiSubtitleText by remember { mutableStateOf("") }
    var isAiModelLoaded by remember { mutableStateOf(false) }
    var subtitleColor by remember { mutableStateOf(Color.Yellow) }
    var subtitleFontSize by remember { mutableFloatStateOf(24f) }
    var showSubtitleSettings by remember { mutableStateOf(false) }

    // Gesture States
    var volumeLevel by remember { mutableFloatStateOf(0.5f) }
    var brightnessLevel by remember { mutableFloatStateOf(0.5f) }
    var gestureIcon by remember { mutableStateOf<ImageVector?>(null) }
    var gestureText by remember { mutableStateOf("") }
    var showGestureOverlay by remember { mutableStateOf(false) }
    var forwardAnimAlpha by remember { mutableFloatStateOf(0f) }
    var rewindAnimAlpha by remember { mutableFloatStateOf(0f) }

    // Torrent Info
    var statusMsg by remember { mutableStateOf("Initializing Core...") }
    var downloadSpeed by remember { mutableStateOf("0 KB/s") }
    var seeds by remember { mutableIntStateOf(0) }

    val audioManager = remember { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    val maxVolume = remember { audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC) }

    // --- SYSTEM UI SETUP ---
    DisposableEffect(Unit) {
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val window = activity?.window
        val insetsController = if (window != null) WindowCompat.getInsetsController(window, window.decorView) else null

        insetsController?.hide(WindowInsetsCompat.Type.systemBars())
        insetsController?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE

        onDispose {
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            insetsController?.show(WindowInsetsCompat.Type.systemBars())
            
            // Clean up Engines
            TorrentEngine.stop()
            StreamXCore.stopAI()
        }
    }

    // --- ROBUST AI INITIALIZATION (FIXED) ---
    LaunchedEffect(isAiEnabled) {
        if (isAiEnabled) {
            val success = withContext(Dispatchers.IO) {
                val modelFile = File(context.filesDir, "ggml-tiny.bin")
                
                // Integrity Check: ফাইল আছে কিনা এবং সাইজ ১MB এর বেশি কিনা
                if (!modelFile.exists() || modelFile.length() < 1024 * 1024) {
                    try {
                        context.assets.open("ggml-tiny.bin").use { input ->
                            val tempFile = File(context.filesDir, "ggml-tiny.tmp")
                            tempFile.outputStream().use { output -> input.copyTo(output) }
                            // Atomic Rename (ফাইল করাপশন আটকায়)
                            tempFile.renameTo(modelFile)
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                        return@withContext false
                    }
                }
                // Call Rust Bridge
                StreamXCore.initAI(modelFile.absolutePath)
            }
            isAiModelLoaded = success
            if (!success) {
                aiSubtitleText = "AI Init Failed: Check Model"
                delay(2000)
                isAiEnabled = false
            }
        } else {
            StreamXCore.stopAI()
            aiSubtitleText = ""
            isAiModelLoaded = false
        }
    }

    // --- HIGH SPEED SUBTITLE POLLING (RUST) ---
    LaunchedEffect(isAiEnabled, isAiModelLoaded) {
        if (isAiEnabled && isAiModelLoaded) {
            while (true) {
                val sub = withContext(Dispatchers.Default) { StreamXCore.getSubtitle() }
                if (sub.isNotEmpty()) aiSubtitleText = sub
                delay(100) // 10Hz poll rate
            }
        }
    }

    // --- TORRENT ENGINE HANDLER ---
    LaunchedEffect(decodedUrl) {
        if (decodedUrl.startsWith("magnet:?")) {
            TorrentEngine.start(context, decodedUrl).collect { state ->
                when (state) {
                    is StreamState.Preparing -> statusMsg = state.message
                    is StreamState.Buffering -> {
                        if (!isPlaying) isBuffering = true
                        statusMsg = "Buffering ${state.progress}%"
                        downloadSpeed = "${state.speed / 1024} KB/s"
                        seeds = state.seeds
                    }
                    is StreamState.Ready -> {
                        if (videoPath != state.filePath) {
                            videoPath = state.filePath
                            // ভিডিও পাথ পাওয়ার পর অটো প্লে শুরু হবে Player ব্লকে
                        }
                        statusMsg = ""
                    }
                    is StreamState.Error -> statusMsg = "Error: ${state.message}"
                }
            }
        } else {
            videoPath = decodedUrl
            statusMsg = ""
            isBuffering = false
        }
    }

    // --- AUDIO SINK INTERCEPTOR ---
    val renderersFactory = remember {
        object : DefaultRenderersFactory(context) {
            override fun buildAudioSink(c: Context, enableFloat: Boolean, enableParams: Boolean): AudioSink {
                return object : ForwardingAudioSink(DefaultAudioSink.Builder(c).build()) {
                    override fun handleBuffer(buffer: ByteBuffer, timeUs: Long, count: Int): Boolean {
                        if (isAiEnabled && isAiModelLoaded) {
                            try {
                                val bufferCopy = buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)
                                val size = bufferCopy.remaining() / 2
                                if (size > 0) {
                                    val floats = FloatArray(size)
                                    for (i in 0 until size) {
                                        floats[i] = bufferCopy.short / 32768f
                                    }
                                    // Send to Rust (Fast & Safe)
                                    StreamXCore.pushAudio(floats)
                                }
                            } catch (e: Exception) { /* Ignore dropped frames */ }
                        }
                        return super.handleBuffer(buffer, timeUs, count)
                    }
                }
            }
        }
    }

    // --- EXO PLAYER ---
    DisposableEffect(videoPath) {
        if (videoPath == null) return@DisposableEffect onDispose {}

        val player = ExoPlayer.Builder(context, renderersFactory).build().apply {
            setMediaItem(MediaItem.fromUri(Uri.parse(videoPath)))
            prepare()
            playWhenReady = true
        }
        exoPlayer = player

        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying = playing
                if (playing) {
                    isBuffering = false
                    // ৩ সেকেন্ড পর কন্ট্রোল হাইড
                    scope.launch { delay(3000); if(isPlaying) isControlsVisible = false }
                }
            }
            override fun onPlaybackStateChanged(state: Int) {
                isBuffering = state == Player.STATE_BUFFERING
                if (state == Player.STATE_ENDED) isControlsVisible = true
            }
            override fun onEvents(p: Player, e: Player.Events) {
                currentTime = p.currentPosition
                totalDuration = p.duration
            }
        }
        player.addListener(listener)

        onDispose {
            player.removeListener(listener)
            player.release()
            exoPlayer = null
        }
    }

    // --- UI COMPOSITION ---
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        
        // 1. Video Layer
        if (videoPath != null) {
            AndroidView(
                factory = { ctx ->
                    PlayerView(ctx).apply {
                        player = exoPlayer
                        useController = false
                        layoutParams = ViewGroup.LayoutParams(-1, -1)
                        keepScreenOn = true
                    }
                },
                update = { view ->
                    if (view.player != exoPlayer) view.player = exoPlayer
                    view.resizeMode = resizeMode
                    // AI চালু থাকলে বিল্ট-ইন সাবটাইটেল বন্ধ রাখব
                    view.subtitleView?.visibility = if (isAiEnabled) android.view.View.GONE else android.view.View.VISIBLE
                },
                modifier = Modifier.fillMaxSize()
            )
        }

        // 2. Gesture Detector Layer
        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = { isControlsVisible = !isControlsVisible },
                        onDoubleTap = { offset ->
                            if (isLocked) return@detectTapGestures
                            val isForward = offset.x > size.width / 2
                            exoPlayer?.let { 
                                it.seekTo(it.currentPosition + if(isForward) 10000 else -10000)
                                if (isForward) {
                                    forwardAnimAlpha = 1f; scope.launch { delay(500); forwardAnimAlpha = 0f }
                                } else {
                                    rewindAnimAlpha = 1f; scope.launch { delay(500); rewindAnimAlpha = 0f }
                                }
                            }
                        }
                    )
                }
                .pointerInput(Unit) {
                    detectVerticalDragGestures(
                        onDragStart = { showGestureOverlay = true },
                        onDragEnd = { scope.launch { delay(500); showGestureOverlay = false } }
                    ) { change, dragAmount ->
                        if (isLocked) return@detectVerticalDragGestures
                        val isRight = change.position.x > size.width / 2
                        val delta = -dragAmount / (size.height / 2) // Invert drag

                        if (isRight) { // Volume
                            val volDelta = (delta * maxVolume).toInt()
                            val current = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                            val newVol = (current + volDelta).coerceIn(0, maxVolume)
                            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, newVol, 0)
                            volumeLevel = newVol / maxVolume.toFloat()
                            gestureIcon = Icons.Rounded.VolumeUp
                            gestureText = "${(volumeLevel * 100).toInt()}%"
                        } else { // Brightness
                            brightnessLevel = (brightnessLevel + delta).coerceIn(0f, 1f)
                            val lp = activity?.window?.attributes
                            lp?.screenBrightness = brightnessLevel
                            activity?.window?.attributes = lp
                            gestureIcon = Icons.Rounded.BrightnessMedium
                            gestureText = "${(brightnessLevel * 100).toInt()}%"
                        }
                    }
                }
        )

        // 3. Animation Overlays
        if (rewindAnimAlpha > 0) {
            Box(Modifier.align(Alignment.CenterStart).padding(50.dp).alpha(rewindAnimAlpha).background(Color.Black.copy(0.5f), CircleShape).padding(16.dp)) {
                Icon(Icons.Rounded.FastRewind, null, tint = Color.White, modifier = Modifier.size(40.dp))
            }
        }
        if (forwardAnimAlpha > 0) {
            Box(Modifier.align(Alignment.CenterEnd).padding(50.dp).alpha(forwardAnimAlpha).background(Color.Black.copy(0.5f), CircleShape).padding(16.dp)) {
                Icon(Icons.Rounded.FastForward, null, tint = Color.White, modifier = Modifier.size(40.dp))
            }
        }

        // 4. AI Subtitles (Custom Render)
        if (isAiEnabled && aiSubtitleText.isNotBlank()) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = if (isControlsVisible) 100.dp else 40.dp)
                    .padding(horizontal = 32.dp)
                    .background(Color.Black.copy(0.6f), RoundedCornerShape(8.dp))
                    .padding(16.dp)
            ) {
                Text(
                    text = aiSubtitleText,
                    color = subtitleColor,
                    fontSize = subtitleFontSize.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center
                )
            }
        }

        // 5. Gesture Info Overlay
        if (showGestureOverlay) {
            Box(Modifier.align(Alignment.Center).background(Color.Black.copy(0.7f), RoundedCornerShape(16.dp)).padding(24.dp)) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    gestureIcon?.let { Icon(it, null, tint = Color.Cyan, modifier = Modifier.size(48.dp)) }
                    Text(gestureText, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                }
            }
        }

        // 6. Loading / Buffering UI
        if (isBuffering && videoPath == null) {
            // Initial Load
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = Color.Cyan)
                    Spacer(Modifier.height(16.dp))
                    Text(statusMsg, color = Color.White)
                }
                IconButton(onClick = { navController.popBackStack() }, modifier = Modifier.align(Alignment.TopStart).padding(16.dp)) {
                    Icon(Icons.Default.ArrowBack, "Back", tint = Color.White)
                }
            }
        } else {
            // Player Controls
            AnimatedVisibility(
                visible = isControlsVisible || isBuffering,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.fillMaxSize()
            ) {
                Box(Modifier.fillMaxSize().background(Color.Black.copy(0.4f))) {
                    
                    if (isBuffering && !isPlaying) {
                        CircularProgressIndicator(Modifier.align(Alignment.Center), color = Color.White)
                    }

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
                            Button(
                                onClick = { isAiEnabled = !isAiEnabled },
                                colors = ButtonDefaults.buttonColors(containerColor = if(isAiEnabled) Color.Green else Color.DarkGray),
                                modifier = Modifier.height(35.dp),
                                contentPadding = PaddingValues(horizontal = 8.dp)
                            ) { Text("AI", color = Color.Black) }
                            
                            IconButton(onClick = { showSubtitleSettings = true }) { Icon(Icons.Rounded.Palette, null, tint = Color.White) }
                        }
                    }

                    // Center Controls
                    if (!isLocked && !isBuffering) {
                        Row(Modifier.align(Alignment.Center), horizontalArrangement = Arrangement.spacedBy(40.dp)) {
                            IconButton(onClick = { exoPlayer?.seekTo((exoPlayer?.currentPosition ?: 0) - 10000) }) {
                                Icon(Icons.Rounded.Replay10, null, tint = Color.White, modifier = Modifier.size(48.dp))
                            }
                            IconButton(onClick = { if (isPlaying) exoPlayer?.pause() else exoPlayer?.play() }) {
                                Icon(if(isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, null, tint = Color.White, modifier = Modifier.size(64.dp))
                            }
                            IconButton(onClick = { exoPlayer?.seekTo((exoPlayer?.currentPosition ?: 0) + 10000) }) {
                                Icon(Icons.Rounded.Forward10, null, tint = Color.White, modifier = Modifier.size(48.dp))
                            }
                        }
                    }

                    // Lock Button
                    IconButton(
                        onClick = { isLocked = !isLocked },
                        modifier = Modifier.align(Alignment.CenterEnd).padding(32.dp)
                    ) { 
                        Icon(if(isLocked) Icons.Rounded.Lock else Icons.Rounded.LockOpen, null, tint = if(isLocked) Color.Red else Color.White) 
                    }

                    // Bottom Seekbar
                    if (!isLocked) {
                        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(16.dp)) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(formatTime(currentTime), color = Color.White, fontSize = 12.sp)
                                Text(formatTime(totalDuration), color = Color.White, fontSize = 12.sp)
                            }
                            Slider(
                                value = currentTime.toFloat(),
                                onValueChange = { exoPlayer?.seekTo(it.toLong()) },
                                valueRange = 0f..max(1f, totalDuration.toFloat()),
                                colors = SliderDefaults.colors(thumbColor = Color.Cyan, activeTrackColor = Color.Cyan)
                            )
                        }
                    }
                }
            }
        }

        // Settings Dialog
        if (showSubtitleSettings) {
            AlertDialog(
                onDismissRequest = { showSubtitleSettings = false },
                title = { Text("Subtitle Settings") },
                text = {
                    Column {
                        Text("Size: ${subtitleFontSize.toInt()}")
                        Slider(value = subtitleFontSize, onValueChange = { subtitleFontSize = it }, valueRange = 16f..40f)
                    }
                },
                confirmButton = { TextButton(onClick = { showSubtitleSettings = false }) { Text("Done") } }
            )
        }
    }
}

fun formatTime(ms: Long): String {
    val totalSeconds = ms / 1000
    val m = (totalSeconds / 60) % 60
    val s = totalSeconds % 60
    val h = totalSeconds / 3600
    return if (h > 0) String.format("%02d:%02d:%02d", h, m, s) else String.format("%02d:%02d", m, s)
}
