package com.aeoncorex.streamx.ui.movie

import android.app.Activity
import android.content.Context
import android.content.pm.ActivityInfo
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.view.ViewGroup
import android.view.WindowManager
import androidx.annotation.OptIn
import androidx.compose.animation.*
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
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

// --- NATIVE METHODS ---
private external fun initAINative(modelPath: String): Boolean
private external fun pushAudioNative(data: FloatArray, size: Int)
private external fun getSubtitleNative(): String
private external fun stopAINative()

private object NativeLoader {
    init {
        try {
            System.loadLibrary("streamx-native")
        } catch (e: UnsatisfiedLinkError) {
            e.printStackTrace()
        }
    }
    fun load() {}
}

@OptIn(UnstableApi::class)
@Composable
fun MoviePlayerScreen(navController: NavController, encodedUrl: String) {
    NativeLoader.load()

    val context = LocalContext.current
    val activity = context as? Activity
    val decodedUrl = remember { try { URLDecoder.decode(encodedUrl, "UTF-8") } catch (e: Exception) { encodedUrl } }
    val scope = rememberCoroutineScope()

    // --- State Management ---
    var videoPath by remember { mutableStateOf<String?>(null) }
    var exoPlayer by remember { mutableStateOf<ExoPlayer?>(null) }
    
    // Player States
    var isPlaying by remember { mutableStateOf(true) }
    var isBuffering by remember { mutableStateOf(true) } // New State for Buffering
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
    var subtitleFontSize by remember { mutableFloatStateOf(20f) }
    var showSubtitleSettings by remember { mutableStateOf(false) }

    // Gesture States
    var volumeLevel by remember { mutableFloatStateOf(0.5f) }
    var brightnessLevel by remember { mutableFloatStateOf(0.5f) }
    var gestureIcon by remember { mutableStateOf<ImageVector?>(null) }
    var gestureText by remember { mutableStateOf("") }
    var showGestureOverlay by remember { mutableStateOf(false) }
    
    // Animation States (Using Alpha for performance)
    var forwardAnimAlpha by remember { mutableFloatStateOf(0f) }
    var rewindAnimAlpha by remember { mutableFloatStateOf(0f) }

    // Torrent Info
    var statusMsg by remember { mutableStateOf("Initializing Core...") }
    var downloadSpeed by remember { mutableStateOf("0 KB/s") }
    var seeds by remember { mutableIntStateOf(0) }

    val audioManager = remember { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    val maxVolume = remember { audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC) }

    // --- SYSTEM UI & ORIENTATION SETUP ---
    DisposableEffect(Unit) {
        // Force Landscape
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        
        // Hide System Bars (Immersive Mode)
        val window = activity?.window
        val controller = if (window != null) WindowCompat.getInsetsController(window, window.decorView) else null
        
        if (window != null) {
            WindowCompat.setDecorFitsSystemWindows(window, false)
        }
        controller?.hide(WindowInsetsCompat.Type.systemBars())
        controller?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        
        onDispose {
            // Restore System Bars & Orientation
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            if (window != null) {
                WindowCompat.setDecorFitsSystemWindows(window, true)
            }
            controller?.show(WindowInsetsCompat.Type.systemBars())
            
            // Cleanup Logic
            TorrentEngine.stop()
            TorrentEngine.clearCache(context) // FIX: Cache clear on exit
            stopAINative()
        }
    }

    // --- AI Engine Initialization (Fixed) ---
    LaunchedEffect(isAiEnabled) {
        if (isAiEnabled) {
            withContext(Dispatchers.IO) {
                val modelFile = File(context.filesDir, "ggml-tiny.bin")
                
                // Copy ONLY if missing or empty
                if (!modelFile.exists() || modelFile.length() == 0L) {
                    try {
                        context.assets.open("ggml-tiny.bin").use { input ->
                            modelFile.outputStream().use { output -> input.copyTo(output) }
                        }
                    } catch (e: Exception) {
                        isAiModelLoaded = false
                    }
                }
                
                if (modelFile.exists() && modelFile.length() > 0) {
                    isAiModelLoaded = initAINative(modelFile.absolutePath)
                } else {
                    isAiModelLoaded = false
                }
            }
            if (!isAiModelLoaded) aiSubtitleText = "AI Init Failed (File Missing?)"
        } else {
            stopAINative()
            aiSubtitleText = ""
        }
    }

    // AI Polling
    LaunchedEffect(isAiEnabled, isAiModelLoaded) {
        while (isAiEnabled && isAiModelLoaded) {
            val sub = getSubtitleNative()
            if (sub.isNotEmpty()) aiSubtitleText = sub
            delay(200)
        }
    }

    // --- Torrent Logic ---
    LaunchedEffect(decodedUrl) {
        if (decodedUrl.startsWith("magnet:?")) {
            TorrentEngine.start(context, decodedUrl).collect { state ->
                when (state) {
                    is StreamState.Preparing -> statusMsg = state.message
                    is StreamState.Buffering -> {
                        statusMsg = "Buffering ${state.progress}%"
                        downloadSpeed = "${state.speed / 1024} KB/s"
                        seeds = state.seeds
                    }
                    is StreamState.Ready -> {
                        if (videoPath != state.filePath) videoPath = state.filePath
                        statusMsg = ""
                    }
                    is StreamState.Error -> statusMsg = "Error: ${state.message}"
                }
            }
        } else {
            videoPath = decodedUrl
            statusMsg = ""
        }
    }

    // --- Player Logic ---
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInput(Unit) {
                detectVerticalDragGestures(
                    onDragStart = { showGestureOverlay = true },
                    onDragEnd = { scope.launch { delay(500); showGestureOverlay = false } }
                ) { change, dragAmount ->
                    if (isLocked) return@detectVerticalDragGestures
                    val isLeft = change.position.x < size.width / 2
                    val delta = -dragAmount / (size.height / 2) // Inverted drag
                    
                    if (isLeft) {
                        brightnessLevel = (brightnessLevel + delta).coerceIn(0f, 1f)
                        val lp = activity?.window?.attributes
                        lp?.screenBrightness = brightnessLevel
                        activity?.window?.attributes = lp
                        gestureIcon = Icons.Rounded.BrightnessMedium
                        gestureText = "${(brightnessLevel * 100).toInt()}%"
                    } else {
                        val volDelta = (delta * maxVolume).toInt()
                        val currentVol = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                        val newVol = (currentVol + volDelta).coerceIn(0, maxVolume)
                        if (newVol != currentVol) {
                            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, newVol, 0)
                            volumeLevel = newVol.toFloat() / maxVolume
                        }
                        gestureIcon = Icons.Rounded.VolumeUp
                        gestureText = "${(volumeLevel * 100).toInt()}%"
                    }
                }
            }
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { isControlsVisible = !isControlsVisible },
                    onDoubleTap = { offset ->
                        if (isLocked) return@detectTapGestures
                        val isForward = offset.x > size.width / 2
                        exoPlayer?.let { player ->
                            player.seekTo(player.currentPosition + if (isForward) 10000L else -10000L)
                            if (isForward) {
                                forwardAnimAlpha = 1f
                                scope.launch { delay(600); forwardAnimAlpha = 0f }
                            } else {
                                rewindAnimAlpha = 1f
                                scope.launch { delay(600); rewindAnimAlpha = 0f }
                            }
                        }
                    }
                )
            }
    ) {
        videoPath?.let { path ->
            // Audio Sink setup for AI
            val renderersFactory = remember {
                object : DefaultRenderersFactory(context) {
                    override fun buildAudioSink(c: Context, enableFloat: Boolean, enableParams: Boolean): AudioSink? {
                        val defaultSink = DefaultAudioSink.Builder(c).build()
                        return object : ForwardingAudioSink(defaultSink) {
                            override fun handleBuffer(buffer: ByteBuffer, timeUs: Long, count: Int): Boolean {
                                if (isAiEnabled && isAiModelLoaded) {
                                    val bufferCopy = buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)
                                    val floats = FloatArray(bufferCopy.remaining() / 2)
                                    for (i in floats.indices) floats[i] = bufferCopy.short / 32768f
                                    pushAudioNative(floats, floats.size)
                                }
                                return super.handleBuffer(buffer, timeUs, count)
                            }
                        }
                    }
                }
            }

            // Player Setup
            DisposableEffect(Unit) {
                val player = ExoPlayer.Builder(context, renderersFactory).build().apply {
                    val uri = if (path.startsWith("http")) Uri.parse(path) else Uri.fromFile(File(path))
                    setMediaItem(MediaItem.fromUri(uri))
                    prepare()
                    playWhenReady = true
                }
                exoPlayer = player
                
                val listener = object : Player.Listener {
                    override fun onIsPlayingChanged(playing: Boolean) { 
                        isPlaying = playing 
                        // Hide controls automatically when playing starts
                        if(playing) {
                            scope.launch { 
                                delay(3000)
                                if(isPlaying) isControlsVisible = false 
                            }
                        }
                    }
                    override fun onPlaybackStateChanged(state: Int) {
                        isBuffering = state == Player.STATE_BUFFERING
                        if (state == Player.STATE_ENDED) isControlsVisible = true
                        if (state == Player.STATE_READY) isBuffering = false
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

            // Loop for progress update
            LaunchedEffect(Unit) {
                while(true) {
                    exoPlayer?.let { if(it.isPlaying) currentTime = it.currentPosition }
                    delay(1000)
                }
            }

            // --- VIDEO SURFACE ---
            AndroidView(
                factory = { ctx ->
                    PlayerView(ctx).apply {
                        player = exoPlayer
                        useController = false
                        this.resizeMode = resizeMode
                        layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                        subtitleView?.visibility = if (isAiEnabled) android.view.View.GONE else android.view.View.VISIBLE
                    }
                },
                update = { view ->
                    view.resizeMode = resizeMode
                    view.subtitleView?.visibility = if (isAiEnabled) android.view.View.GONE else android.view.View.VISIBLE
                    if (!isAiEnabled) {
                        view.subtitleView?.apply {
                             val style = CaptionStyleCompat(
                                subtitleColor.toArgb(), Color.Transparent.toArgb(), Color.Transparent.toArgb(),
                                CaptionStyleCompat.EDGE_TYPE_DROP_SHADOW, Color.Black.toArgb(), null
                            )
                            setStyle(style)
                            setFixedTextSize(2, subtitleFontSize)
                        }
                    }
                },
                modifier = Modifier.fillMaxSize()
            )
        }

        // --- DOUBLE TAP ANIMATION (Optimized) ---
        // Forward
        Box(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(end = 50.dp)
                .alpha(forwardAnimAlpha)
                .background(Color.Black.copy(0.5f), CircleShape)
                .padding(16.dp)
        ) {
             Column(horizontalAlignment = Alignment.CenterHorizontally) {
                 Icon(Icons.Rounded.FastForward, null, tint = Color.White, modifier = Modifier.size(40.dp))
                 Text("+10s", color = Color.White, fontWeight = FontWeight.Bold)
             }
        }
        // Rewind
        Box(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .padding(start = 50.dp)
                .alpha(rewindAnimAlpha)
                .background(Color.Black.copy(0.5f), CircleShape)
                .padding(16.dp)
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Rounded.FastRewind, null, tint = Color.White, modifier = Modifier.size(40.dp))
                Text("-10s", color = Color.White, fontWeight = FontWeight.Bold)
            }
        }

        // --- AI SUBTITLES ---
        if (isAiEnabled && aiSubtitleText.isNotEmpty()) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = if(isControlsVisible) 100.dp else 40.dp) // Adjust based on controls
                    .padding(horizontal = 32.dp)
                    .background(Color.Black.copy(0.6f), RoundedCornerShape(8.dp))
                    .padding(horizontal = 16.dp, vertical = 8.dp)
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

        // --- GESTURE FEEDBACK ---
        if (showGestureOverlay) {
            Box(
                modifier = Modifier.align(Alignment.Center)
                    .background(Color.Black.copy(0.7f), RoundedCornerShape(16.dp))
                    .padding(24.dp)
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    gestureIcon?.let { Icon(it, null, tint = Color.Cyan, modifier = Modifier.size(48.dp)) }
                    Text(gestureText, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 20.sp)
                }
            }
        }

        // --- CONTROLS OVERLAY ---
        // Buffering Indicator (Always visible if buffering, disables other controls)
        if (isBuffering || videoPath == null) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(0.3f)), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = Color.Green, strokeWidth = 4.dp)
                    Spacer(Modifier.height(16.dp))
                    Text(statusMsg, color = Color.White, fontWeight = FontWeight.Bold)
                    if(decodedUrl.startsWith("magnet")) {
                        Text("$downloadSpeed | Seeds: $seeds", color = Color.Gray, fontSize = 12.sp)
                    }
                }
                // Allow Back button during buffering
                IconButton(
                    onClick = { navController.popBackStack() },
                    modifier = Modifier.align(Alignment.TopStart).padding(16.dp)
                ) { Icon(Icons.Default.ArrowBack, "Back", tint = Color.White) }
            }
        } 
        else {
            // Main Controls (Only if NOT buffering)
            AnimatedVisibility(
                visible = isControlsVisible,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.fillMaxSize()
            ) {
                Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.4f))) {
                    // Top Bar
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(16.dp).align(Alignment.TopStart),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(onClick = { navController.popBackStack() }) { 
                            Icon(Icons.Default.ArrowBack, "Back", tint = Color.White) 
                        }
                        
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            // Torrent Stats
                            if (decodedUrl.startsWith("magnet")) {
                                Column(horizontalAlignment = Alignment.End, modifier = Modifier.padding(end = 12.dp)) {
                                    Text("▼ $downloadSpeed", color = Color.Green, fontSize = 12.sp)
                                    Text("S: $seeds", color = Color.LightGray, fontSize = 10.sp)
                                }
                            }
                            // AI Toggle
                            Button(
                                onClick = { isAiEnabled = !isAiEnabled },
                                colors = ButtonDefaults.buttonColors(containerColor = if(isAiEnabled) Color.Green else Color.DarkGray),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                                modifier = Modifier.height(35.dp)
                            ) {
                                Text("AI Subs", color = Color.Black, fontSize = 12.sp)
                            }
                            Spacer(Modifier.width(8.dp))
                            // Settings
                            IconButton(onClick = { showSubtitleSettings = true }) {
                                Icon(Icons.Rounded.Palette, "Style", tint = Color.White)
                            }
                            IconButton(onClick = {
                                resizeMode = if (resizeMode == AspectRatioFrameLayout.RESIZE_MODE_FIT) AspectRatioFrameLayout.RESIZE_MODE_ZOOM else AspectRatioFrameLayout.RESIZE_MODE_FIT
                            }) { Icon(Icons.Rounded.AspectRatio, "Resize", tint = Color.White) }
                        }
                    }

                    // Center Play/Pause (Hidden if Locked)
                    if (!isLocked) {
                        Row(modifier = Modifier.align(Alignment.Center), horizontalArrangement = Arrangement.spacedBy(50.dp)) {
                            IconButton(onClick = { exoPlayer?.seekTo((exoPlayer?.currentPosition ?: 0) - 10000) }) {
                                Icon(Icons.Rounded.Replay10, "Rewind", tint = Color.White, modifier = Modifier.size(48.dp))
                            }
                            IconButton(onClick = { if (isPlaying) exoPlayer?.pause() else exoPlayer?.play() }) {
                                Icon(if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, "Play", tint = Color.White, modifier = Modifier.size(64.dp))
                            }
                            IconButton(onClick = { exoPlayer?.seekTo((exoPlayer?.currentPosition ?: 0) + 10000) }) {
                                Icon(Icons.Rounded.Forward10, "Forward", tint = Color.White, modifier = Modifier.size(48.dp))
                            }
                        }
                    }

                    // Lock Button
                    IconButton(
                        onClick = { isLocked = !isLocked },
                        modifier = Modifier.align(Alignment.CenterEnd).padding(32.dp)
                    ) { 
                        Icon(
                            if (isLocked) Icons.Rounded.Lock else Icons.Rounded.LockOpen, 
                            "Lock", 
                            tint = if (isLocked) Color.Red else Color.White
                        ) 
                    }

                    // Bottom Seekbar (Hidden if Locked)
                    if (!isLocked) {
                        Column(modifier = Modifier.fillMaxWidth().align(Alignment.BottomCenter).padding(16.dp)) {
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
        
        // --- DIALOGS ---
        if (showSubtitleSettings) {
            AlertDialog(
                onDismissRequest = { showSubtitleSettings = false },
                title = { Text("Subtitle Settings") },
                text = {
                    Column {
                        Text("Color:")
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                            listOf(Color.White, Color.Yellow, Color.Cyan, Color.Green).forEach { c ->
                                Box(
                                    Modifier.size(30.dp)
                                        .background(c, CircleShape)
                                        .clickable { subtitleColor = c }
                                )
                            }
                        }
                        Spacer(Modifier.height(16.dp))
                        Text("Size: ${subtitleFontSize.toInt()}")
                        Slider(value = subtitleFontSize, onValueChange = { subtitleFontSize = it }, valueRange = 12f..36f)
                    }
                },
                confirmButton = { TextButton(onClick = { showSubtitleSettings = false }) { Text("Close") } }
            )
        }
    }
}

fun formatTime(ms: Long): String {
    val totalSeconds = max(0, ms) / 1000
    val m = (totalSeconds / 60) % 60
    val s = totalSeconds % 60
    val h = totalSeconds / 3600
    return if (h > 0) String.format("%02d:%02d:%02d", h, m, s) else String.format("%02d:%02d", m, s)
}
