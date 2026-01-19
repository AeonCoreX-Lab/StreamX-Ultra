package com.aeoncorex.streamx.ui.movie

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.media.AudioManager
import android.net.Uri
import android.view.WindowManager
import androidx.annotation.OptIn
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
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
import androidx.media3.ui.PlayerView
import androidx.navigation.NavController
import kotlinx.coroutines.delay
import java.io.File
import java.net.URLDecoder

@OptIn(UnstableApi::class)
@Composable
fun MoviePlayerScreen(navController: NavController, encodedMagnetOrUrl: String) {
    val decodedInput = remember { URLDecoder.decode(encodedMagnetOrUrl, "UTF-8") }
    val context = LocalContext.current
    val activity = activityContext(context)

    // States
    var streamUrl by remember { mutableStateOf<String?>(null) }
    var statusMessage by remember { mutableStateOf("Initializing Engine...") }
    var isError by remember { mutableStateOf(false) }
    
    // Initialize Torrent Engine if it's a magnet link
    LaunchedEffect(decodedInput) {
        if (decodedInput.startsWith("magnet:?")) {
            TorrentEngine.init(context)
            TorrentEngine.startStreaming(decodedInput).collect { state ->
                when(state) {
                    is StreamState.Preparing -> statusMessage = state.message
                    is StreamState.Buffering -> statusMessage = "Buffering ${state.progress}% (P2P)"
                    is StreamState.Ready -> {
                        streamUrl = state.filePath // File path for ExoPlayer
                        statusMessage = ""
                    }
                    is StreamState.Error -> {
                        statusMessage = "Error: ${state.message}"
                        isError = true
                    }
                }
            }
        } else {
            // Direct URL (e.g. from other sources)
            streamUrl = decodedInput
        }
    }

    // Cleanup on exit
    DisposableEffect(Unit) {
        onDispose {
            TorrentEngine.stop()
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (streamUrl != null) {
            NativeExoPlayer(navController, streamUrl!!)
        } else {
            // Loading Screen
            Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator(color = Color.Cyan)
                Spacer(Modifier.height(16.dp))
                Text(statusMessage, color = Color.White, fontWeight = FontWeight.Bold)
                if (isError) {
                    Spacer(Modifier.height(16.dp))
                    Button(onClick = { navController.popBackStack() }) {
                        Text("Go Back")
                    }
                }
            }
        }
    }
}

// --- THE POWERFUL NATIVE PLAYER ---
@OptIn(UnstableApi::class)
@Composable
fun NativeExoPlayer(navController: NavController, videoSource: String) {
    val context = LocalContext.current
    val activity = activityContext(context)

    // Player Initialization
    val exoPlayer = remember {
        ExoPlayer.Builder(context).build().apply {
            // Handle local file or network URL
            val mediaItem = if (videoSource.startsWith("/")) {
                MediaItem.fromUri(Uri.fromFile(File(videoSource)))
            } else {
                MediaItem.fromUri(Uri.parse(videoSource))
            }
            setMediaItem(mediaItem)
            prepare()
            playWhenReady = true
        }
    }

    // UI States
    var isControlsVisible by remember { mutableStateOf(false) }
    var volume by remember { mutableFloatStateOf(0.5f) }
    var brightness by remember { mutableFloatStateOf(0.5f) }
    var gestureIcon by remember { mutableStateOf<ImageVector?>(null) }
    var isLocked by remember { mutableStateOf(false) }

    // Immersive Mode
    DisposableEffect(Unit) {
        val window = activity?.window
        window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.getInsetsController(window!!, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE

        onDispose {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            WindowCompat.getInsetsController(window, window.decorView).show(WindowInsetsCompat.Type.systemBars())
            activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            exoPlayer.release()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInput(Unit) {
                detectTapGestures(
                    onDoubleTap = { offset ->
                        if (!isLocked) {
                            val screenWidth = size.width
                            if (offset.x < screenWidth / 2) {
                                exoPlayer.seekBack()
                                gestureIcon = Icons.Rounded.Replay10
                            } else {
                                exoPlayer.seekForward()
                                gestureIcon = Icons.Rounded.Forward10
                            }
                        }
                    },
                    onTap = { isControlsVisible = !isControlsVisible }
                )
            }
            .pointerInput(Unit) {
                detectVerticalDragGestures(
                    onDragStart = {},
                    onDragEnd = { gestureIcon = null }
                ) { change, dragAmount ->
                    if (!isLocked) {
                        val isRight = change.position.x > size.width / 2
                        if (isRight) {
                            // Volume Control
                            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                            val maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                            val currentVol = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                            val newVol = (currentVol - (dragAmount / 30)).coerceIn(0f, maxVol.toFloat())
                            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, newVol.toInt(), 0)
                            volume = newVol / maxVol
                            gestureIcon = if (volume == 0f) Icons.Rounded.VolumeOff else Icons.Rounded.VolumeUp
                        } else {
                            // Brightness Control
                            val lp = activity?.window?.attributes
                            val currentBright = lp?.screenBrightness ?: 0.5f
                            brightness = (currentBright - dragAmount / 500f).coerceIn(0.01f, 1f)
                            lp?.screenBrightness = brightness
                            activity?.window?.attributes = lp
                            gestureIcon = Icons.Rounded.Brightness6
                        }
                    }
                }
            }
    ) {
        // 1. VIDEO SURFACE
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    player = exoPlayer
                    useController = false // We use custom UI
                    resizeMode = androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT
                }
            },
            modifier = Modifier.fillMaxSize()
        )

        // 2. GESTURE FEEDBACK ICON
        AnimatedVisibility(
            visible = gestureIcon != null,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.Center)
        ) {
            Box(Modifier.background(Color.Black.copy(0.6f), RoundedCornerShape(16.dp)).padding(24.dp)) {
                Icon(gestureIcon ?: Icons.Default.PlayArrow, null, tint = Color.White, modifier = Modifier.size(48.dp))
            }
        }

        // 3. CONTROLS OVERLAY
        AnimatedVisibility(
            visible = isControlsVisible,
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(0.5f))) {
                // Top Bar
                Row(
                    Modifier.fillMaxWidth().align(Alignment.TopCenter).padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.Default.ArrowBack, null, tint = Color.White)
                    }
                    Text("STREAMX PLAYER", color = Color.White, fontWeight = FontWeight.Bold)
                }

                // Center Controls
                if (!isLocked) {
                    Row(
                        Modifier.align(Alignment.Center),
                        horizontalArrangement = Arrangement.spacedBy(40.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(onClick = { exoPlayer.seekBack() }) {
                            Icon(Icons.Rounded.Replay10, null, tint = Color.White, modifier = Modifier.size(48.dp))
                        }
                        
                        val isPlaying = remember { mutableStateOf(true) }
                        DisposableEffect(Unit) {
                            val listener = object : Player.Listener {
                                override fun onIsPlayingChanged(playing: Boolean) {
                                    isPlaying.value = playing
                                }
                            }
                            exoPlayer.addListener(listener)
                            onDispose { exoPlayer.removeListener(listener) }
                        }

                        IconButton(
                            onClick = { if (exoPlayer.isPlaying) exoPlayer.pause() else exoPlayer.play() },
                            modifier = Modifier.size(72.dp).background(Color.White.copy(0.2f), RoundedCornerShape(50))
                        ) {
                            Icon(
                                if (isPlaying.value) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                                null, tint = Color.White, modifier = Modifier.size(48.dp)
                            )
                        }
                        IconButton(onClick = { exoPlayer.seekForward() }) {
                            Icon(Icons.Rounded.Forward10, null, tint = Color.White, modifier = Modifier.size(48.dp))
                        }
                    }
                }

                // Bottom Bar (Lock & Seek)
                Column(Modifier.align(Alignment.BottomCenter).padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { isLocked = !isLocked }) {
                            Icon(
                                if (isLocked) Icons.Rounded.Lock else Icons.Rounded.LockOpen,
                                null, tint = if (isLocked) Color.Red else Color.White
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        // Progress Bar (Simplified)
                        LinearProgressIndicator(
                            progress = 0.5f, // You would bind this to exoPlayer.currentPosition
                            modifier = Modifier.weight(1f).height(4.dp).clip(RoundedCornerShape(2.dp)),
                            color = Color.Red,
                            trackColor = Color.Gray
                        )
                    }
                }
            }
        }
    }
}

// Helper to get Activity from Context - FIXED
fun activityContext(context: Context): Activity? {
    var c = context
    while (c is ContextWrapper) {
        if (c is Activity) return c
        c = c.baseContext
    }
    return null
}
