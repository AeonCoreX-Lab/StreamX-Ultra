package com.aeoncorex.streamx.ui.movie

import android.app.Activity
import android.content.Context
import android.content.pm.ActivityInfo
import android.media.AudioManager
import android.net.Uri
import android.provider.Settings
import android.view.ViewGroup
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.navigation.NavController
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.net.URLDecoder
import java.util.concurrent.TimeUnit
import kotlin.math.max
import kotlin.math.min

@OptIn(UnstableApi::class)
@Composable
fun MoviePlayerScreen(navController: NavController, encodedUrl: String) {
    val context = LocalContext.current
    val activity = context as? Activity
    val decodedUrl = remember { try { URLDecoder.decode(encodedUrl, "UTF-8") } catch (e: Exception) { encodedUrl } }

    // --- State Management ---
    var videoPath by remember { mutableStateOf<String?>(null) }
    var isPlaying by remember { mutableStateOf(true) }
    var currentTime by remember { mutableLongStateOf(0L) }
    var totalDuration by remember { mutableLongStateOf(0L) }
    var bufferedPercentage by remember { mutableIntStateOf(0) }
    var isControlsVisible by remember { mutableStateOf(true) }
    var resizeMode by remember { mutableIntStateOf(AspectRatioFrameLayout.RESIZE_MODE_FIT) }
    var isLocked by remember { mutableStateOf(false) }
    
    // Gesture Feedback States
    var volumeLevel by remember { mutableFloatStateOf(0.5f) }
    var brightnessLevel by remember { mutableFloatStateOf(0.5f) }
    var gestureIcon by remember { mutableStateOf<ImageVector?>(null) }
    var gestureText by remember { mutableStateOf("") }
    var showGestureOverlay by remember { mutableStateOf(false) }

    // Torrent States
    var statusMsg by remember { mutableStateOf("Initializing Core...") }
    var downloadSpeed by remember { mutableStateOf("0 KB/s") }
    var seeds by remember { mutableIntStateOf(0) }

    // Audio Manager Setup
    val audioManager = remember { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    val maxVolume = remember { audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC) }

    // Initialize Brightness & Volume
    LaunchedEffect(Unit) {
        val currentVol = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        volumeLevel = currentVol.toFloat() / maxVolume.toFloat()
        activity?.window?.attributes?.screenBrightness?.let {
            brightnessLevel = if (it < 0) 0.5f else it
        }
    }

    // --- System UI & Orientation Setup ---
    DisposableEffect(Unit) {
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val controller = activity?.let { WindowCompat.getInsetsController(it.window, it.window.decorView) }
        controller?.hide(WindowInsetsCompat.Type.systemBars())
        controller?.systemBarsBehavior = WindowInsetsCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        
        onDispose {
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            controller?.show(WindowInsetsCompat.Type.systemBars())
            TorrentEngine.stop()
        }
    }

    // --- Torrent Engine Logic ---
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

    // --- Auto Hide Controls ---
    LaunchedEffect(isControlsVisible, isPlaying) {
        if (isControlsVisible && isPlaying) {
            delay(4000)
            isControlsVisible = false
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { isControlsVisible = !isControlsVisible },
                    onDoubleTap = { offset ->
                        // Will be handled by ExoPlayer logic below, but kept here for structure
                    }
                )
            }
    ) {
        // 1. ExoPlayer
        videoPath?.let { path ->
            val exoPlayer = remember(context) {
                ExoPlayer.Builder(context).build().apply {
                    val uri = if (path.startsWith("http")) Uri.parse(path) else Uri.fromFile(File(path))
                    setMediaItem(MediaItem.fromUri(uri))
                    prepare()
                    playWhenReady = true
                }
            }

            // Sync Player State
            DisposableEffect(exoPlayer) {
                val listener = object : Player.Listener {
                    override fun onIsPlayingChanged(playing: Boolean) { isPlaying = playing }
                    override fun onEvents(player: Player, events: Player.Events) {
                        currentTime = player.currentPosition
                        totalDuration = player.duration
                        bufferedPercentage = player.bufferedPercentage
                    }
                }
                exoPlayer.addListener(listener)
                
                // Update progress loop
                val interval = 1000L
                val scope = CoroutineScope(Dispatchers.Main)
                val job = scope.launch {
                    while (true) {
                        currentTime = exoPlayer.currentPosition
                        delay(interval)
                    }
                }
                
                onDispose {
                    exoPlayer.removeListener(listener)
                    exoPlayer.release()
                    job.cancel()
                }
            }

            AndroidView(
                factory = { ctx ->
                    PlayerView(ctx).apply {
                        player = exoPlayer
                        useController = false // We use custom UI
                        resizeMode = resizeMode
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                        )
                    }
                },
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        // --- ADVANCED GESTURE LOGIC ---
                        detectVerticalDragGestures(
                            onDragStart = { showGestureOverlay = true },
                            onDragEnd = { 
                                showGestureOverlay = false 
                                gestureIcon = null
                            }
                        ) { change, dragAmount ->
                            if (isLocked) return@detectVerticalDragGestures
                            
                            val width = size.width
                            val xPos = change.position.x
                            val isLeft = xPos < width / 2
                            
                            if (isLeft) {
                                // Brightness Control
                                val delta = -dragAmount / 500f // Invert & Scale
                                brightnessLevel = (brightnessLevel + delta).coerceIn(0f, 1f)
                                activity?.window?.attributes = activity?.window?.attributes?.apply {
                                    screenBrightness = brightnessLevel
                                }
                                gestureIcon = Icons.Rounded.BrightnessMedium
                                gestureText = "${(brightnessLevel * 100).toInt()}%"
                            } else {
                                // Volume Control
                                val delta = -dragAmount / 50f
                                val currentVol = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                                val newVol = (currentVol + delta.toInt()).coerceIn(0, maxVolume)
                                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, newVol, 0)
                                volumeLevel = newVol.toFloat() / maxVolume
                                gestureIcon = if (volumeLevel == 0f) Icons.Rounded.VolumeOff else Icons.Rounded.VolumeUp
                                gestureText = "${(volumeLevel * 100).toInt()}%"
                            }
                        }
                    }
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onDoubleTap = { offset ->
                                if (isLocked) return@detectTapGestures
                                val width = size.width
                                val isForward = offset.x > width / 2
                                val seekTime = if (isForward) 10000L else -10000L
                                exoPlayer.seekTo(exoPlayer.currentPosition + seekTime)
                                
                                // Visual Feedback
                                showGestureOverlay = true
                                gestureIcon = if (isForward) Icons.Rounded.Forward10 else Icons.Rounded.Replay10
                                gestureText = if (isForward) "+10s" else "-10s"
                                
                                // Auto hide overlay
                                CoroutineScope(Dispatchers.Main).launch {
                                    delay(600)
                                    showGestureOverlay = false
                                }
                            },
                            onTap = { isControlsVisible = !isControlsVisible },
                            onLongPress = {
                                // Future: Implement 2x Speed
                            }
                        )
                    }
            )

            // 2. Custom Controls UI Overlay
            AnimatedVisibility(
                visible = isControlsVisible,
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.4f))
                ) {
                    // Top Bar
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp)
                            .align(Alignment.TopStart),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(onClick = { navController.popBackStack() }) {
                            Icon(Icons.Default.ArrowBack, "Back", tint = Color.White)
                        }
                        
                        Text(
                            text = File(decodedUrl).nameWithoutExtension.take(30),
                            color = Color.White,
                            fontWeight = FontWeight.Bold
                        )

                        Row {
                            // Download Speed Stats
                            if (decodedUrl.startsWith("magnet")) {
                                Column(horizontalAlignment = Alignment.End, modifier = Modifier.padding(end = 16.dp)) {
                                    Text("▼ $downloadSpeed", color = Color.Green, fontSize = 12.sp)
                                    Text("S: $seeds", color = Color.Gray, fontSize = 10.sp)
                                }
                            }
                            
                            // Aspect Ratio Button
                            IconButton(onClick = { 
                                resizeMode = when(resizeMode) {
                                    AspectRatioFrameLayout.RESIZE_MODE_FIT -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                                    AspectRatioFrameLayout.RESIZE_MODE_ZOOM -> AspectRatioFrameLayout.RESIZE_MODE_FILL
                                    else -> AspectRatioFrameLayout.RESIZE_MODE_FIT
                                }
                            }) {
                                Icon(Icons.Rounded.AspectRatio, "Resize", tint = Color.White)
                            }
                        }
                    }

                    // Center Controls (Play/Pause)
                    if (!isLocked) {
                        Row(
                            modifier = Modifier.align(Alignment.Center),
                            horizontalArrangement = Arrangement.spacedBy(40.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            IconButton(
                                onClick = { exoPlayer.seekTo(exoPlayer.currentPosition - 10000) },
                                modifier = Modifier.size(50.dp)
                            ) {
                                Icon(Icons.Rounded.Replay10, "Rewind", tint = Color.White, modifier = Modifier.fillMaxSize())
                            }

                            IconButton(
                                onClick = { if (isPlaying) exoPlayer.pause() else exoPlayer.play() },
                                modifier = Modifier
                                    .size(70.dp)
                                    .background(Color.White.copy(0.2f), CircleShape)
                            ) {
                                Icon(
                                    if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                                    "Play/Pause",
                                    tint = Color.White,
                                    modifier = Modifier.size(40.dp)
                                )
                            }

                            IconButton(
                                onClick = { exoPlayer.seekTo(exoPlayer.currentPosition + 10000) },
                                modifier = Modifier.size(50.dp)
                            ) {
                                Icon(Icons.Rounded.Forward10, "Forward", tint = Color.White, modifier = Modifier.fillMaxSize())
                            }
                        }
                    }

                    // Lock Button (Always visible logic)
                    IconButton(
                        onClick = { isLocked = !isLocked },
                        modifier = Modifier
                            .align(Alignment.CenterEnd)
                            .padding(end = 30.dp)
                    ) {
                        Icon(
                            if (isLocked) Icons.Rounded.Lock else Icons.Rounded.LockOpen,
                            contentDescription = "Lock",
                            tint = if (isLocked) Color.Red else Color.White
                        )
                    }

                    // Bottom Seekbar Section
                    if (!isLocked) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .align(Alignment.BottomCenter)
                                .background(
                                    Brush.verticalGradient(
                                        colors = listOf(Color.Transparent, Color.Black.copy(0.9f))
                                    )
                                )
                                .padding(16.dp)
                        ) {
                            Text(
                                text = "${formatTime(currentTime)} / ${formatTime(totalDuration)}",
                                color = Color.White,
                                fontSize = 12.sp,
                                modifier = Modifier.padding(start = 8.dp)
                            )
                            
                            Slider(
                                value = currentTime.toFloat(),
                                onValueChange = { exoPlayer.seekTo(it.toLong()) },
                                valueRange = 0f..max(1f, totalDuration.toFloat()),
                                colors = SliderDefaults.colors(
                                    thumbColor = Color.Cyan,
                                    activeTrackColor = Color.Cyan,
                                    inactiveTrackColor = Color.White.copy(0.3f)
                                ),
                                modifier = Modifier.height(20.dp)
                            )
                        }
                    }
                }
            }
        }

        // 3. Central Gesture Feedback Overlay (Volume/Brightness/Seek)
        if (showGestureOverlay) {
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .background(Color.Black.copy(0.7f), RoundedCornerShape(16.dp))
                    .padding(24.dp)
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    gestureIcon?.let {
                        Icon(it, null, tint = Color.Cyan, modifier = Modifier.size(48.dp))
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = gestureText,
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 20.sp
                    )
                }
            }
        }

        // 4. Loading Indicator (Initial)
        if (videoPath == null) {
            Column(
                modifier = Modifier.align(Alignment.Center),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                CircularProgressIndicator(color = Color.Cyan)
                Spacer(Modifier.height(16.dp))
                Text(statusMsg, color = Color.White)
            }
        }
    }
}

// Utility to format milliseconds to MM:SS or HH:MM:SS
fun formatTime(ms: Long): String {
    val totalSeconds = ms / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    
    return if (hours > 0) {
        String.format("%02d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format("%02d:%02d", minutes, seconds)
    }
}
