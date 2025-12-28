package com.aeoncorex.streamx.ui.player

import android.app.Activity
import android.content.Context
import android.content.pm.ActivityInfo
import android.media.AudioManager
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.TransferListener
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.delay
import java.net.URLDecoder

enum class SeekDirection { NONE, FORWARD, BACKWARD }
enum class GestureMode { NONE, VOLUME, BRIGHTNESS }

@OptIn(UnstableApi::class)
@Composable
fun PlayerScreen(encodedUrl: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val activity = context as? Activity
    val window = activity?.window

    // --- State Management ---
    var isLoading by remember { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var showControls by remember { mutableStateOf(true) }
    var isLocked by remember { mutableStateOf(false) }
    var isPlaying by remember { mutableStateOf(true) }
    var isLandscape by remember { mutableStateOf(false) }
    
    // Resize Mode (Fit, Zoom/Fill, Stretch)
    var resizeMode by remember { mutableIntStateOf(AspectRatioFrameLayout.RESIZE_MODE_FIT) }
    var resizeMessage by remember { mutableStateOf<String?>(null) }
    
    // Data Usage
    var totalDataUsed by remember { mutableStateOf(0L) }
    var currentSpeed by remember { mutableStateOf("0 KB/s") }
    
    // Gestures UI State
    var showSeekUI by remember { mutableStateOf(SeekDirection.NONE) }
    var gestureMode by remember { mutableStateOf(GestureMode.NONE) }
    var gestureValue by remember { mutableStateOf(0f) } 
    var gestureIcon by remember { mutableStateOf(Icons.Default.VolumeUp) }

    // Player Progress
    var currentPosition by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(0L) }
    var bufferedPercentage by remember { mutableIntStateOf(0) }
    var isLiveStream by remember { mutableStateOf(false) }

    val streamUrl = remember { URLDecoder.decode(encodedUrl, "UTF-8") }
    val audioManager = remember { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    val maxVolume = remember { audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC) }

    // --- Player Initialization ---
    val exoPlayer = remember {
        val dataSourceListener = object : TransferListener {
            override fun onTransferInitializing(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) {}
            override fun onTransferStart(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) {}
            override fun onBytesTransferred(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean, bytesTransferred: Int) {
                if (isNetwork) totalDataUsed += bytesTransferred
            }
            override fun onTransferEnd(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) {}
        }
        
        val httpDataSourceFactory = DefaultHttpDataSource.Factory()
            .setUserAgent("StreamX-Player/1.0")
            .setAllowCrossProtocolRedirects(true)
            .setTransferListener(dataSourceListener)
        
        val mediaSourceFactory = DefaultMediaSourceFactory(context).setDataSourceFactory(httpDataSourceFactory)

        ExoPlayer.Builder(context).setMediaSourceFactory(mediaSourceFactory).build().apply {
            setMediaItem(MediaItem.fromUri(streamUrl))
            playWhenReady = true
            prepare()
            addListener(object : Player.Listener {
                override fun onIsPlayingChanged(playing: Boolean) { isPlaying = playing }
                override fun onPlaybackStateChanged(state: Int) {
                    isLoading = state == Player.STATE_BUFFERING
                    if (state == Player.STATE_READY) {
                        val realDuration = this@apply.duration
                        isLiveStream = this@apply.isCurrentMediaItemLive || realDuration <= 0
                        duration = if (realDuration < 0) 0 else realDuration
                    }
                }
                override fun onPlayerError(error: PlaybackException) {
                    isLoading = false
                    errorMessage = "Error: ${error.localizedMessage}"
                    prepare() 
                }
            })
        }
    }

    // --- Cleanup & System UI ---
    DisposableEffect(Unit) { onDispose { exoPlayer.release() } }
    KeepScreenOn()
    HideSystemUi(activity)
    
    BackHandler {
        if (isLandscape) {
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            isLandscape = false
        } else {
            exoPlayer.release()
            onBack()
        }
    }

    // --- Progress Update Loop ---
    LaunchedEffect(exoPlayer) {
        while (true) {
            currentPosition = exoPlayer.currentPosition.coerceAtLeast(0L)
            bufferedPercentage = exoPlayer.bufferedPercentage
            if (!isLiveStream) {
                duration = exoPlayer.duration.coerceAtLeast(0L)
            }
            delay(1000)
        }
    }

    // --- Network Speed Loop ---
    LaunchedEffect(Unit) {
        var lastDataUsed = 0L
        while (true) {
            delay(1000)
            val currentData = totalDataUsed
            val speedBytesPerSec = currentData - lastDataUsed
            currentSpeed = formatSpeed(speedBytesPerSec)
            lastDataUsed = currentData
        }
    }
    
    // --- Resize Message Timeout ---
    LaunchedEffect(resizeMessage) {
        if (resizeMessage != null) {
            delay(1500)
            resizeMessage = null
        }
    }

    // --- Main Layout ---
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            // 1. PINCH TO ZOOM GESTURE (YouTube Style)
            .pointerInput(Unit) {
                if (isLocked) return@pointerInput
                detectTransformGestures { _, _, zoom, _ ->
                    if (zoom > 1.1f) {
                        if (resizeMode != AspectRatioFrameLayout.RESIZE_MODE_ZOOM) {
                            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                            resizeMessage = "Zoomed to Fill"
                        }
                    } else if (zoom < 0.9f) {
                        if (resizeMode != AspectRatioFrameLayout.RESIZE_MODE_FIT) {
                            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                            resizeMessage = "Original Aspect Ratio"
                        }
                    }
                }
            }
            // 2. TAP & DRAG GESTURES
            .pointerInput(Unit) {
                if (isLocked) return@pointerInput
                
                detectTapGestures(
                    onTap = { showControls = !showControls },
                    onDoubleTap = { offset ->
                        // 10s Skip Logic
                        val screenWidth = size.width
                        if (offset.x > screenWidth / 2) {
                            // Forward
                            exoPlayer.seekTo(exoPlayer.currentPosition + 10000)
                            showSeekUI = SeekDirection.FORWARD
                        } else {
                            // Backward
                            exoPlayer.seekTo(exoPlayer.currentPosition - 10000)
                            showSeekUI = SeekDirection.BACKWARD
                        }
                    }
                )
            }
            .pointerInput(Unit) {
                if (isLocked) return@pointerInput

                var startX = 0f
                var isVolume = false
                var initialVolume = 0
                var initialBrightness = 0f

                detectVerticalDragGestures(
                    onDragStart = { offset ->
                        startX = offset.x
                        isVolume = startX > size.width / 2
                        
                        if (isVolume) {
                            initialVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                            gestureMode = GestureMode.VOLUME
                            gestureValue = initialVolume.toFloat()
                            gestureIcon = if (initialVolume == 0) Icons.Default.VolumeOff else Icons.Default.VolumeUp
                        } else {
                            initialBrightness = window?.attributes?.screenBrightness?.takeIf { it >= 0 } ?: 0.5f
                            gestureMode = GestureMode.BRIGHTNESS
                            gestureValue = initialBrightness
                            gestureIcon = Icons.Default.BrightnessMedium
                        }
                    },
                    onDragEnd = { gestureMode = GestureMode.NONE },
                    onDragCancel = { gestureMode = GestureMode.NONE },
                    onVerticalDrag = { _, dragAmount ->
                        val sensitivity = 2.0f / size.height 
                        val delta = -dragAmount * sensitivity 

                        if (isVolume) {
                            val maxVol = maxVolume.toFloat()
                            val newVol = (gestureValue + (delta * maxVol * 10)).coerceIn(0f, maxVol)
                            gestureValue = newVol
                            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, newVol.toInt(), 0)
                            gestureIcon = if (newVol <= 0) Icons.Default.VolumeOff else Icons.Default.VolumeUp
                        } else {
                            val newBright = (gestureValue + delta).coerceIn(0.01f, 1f)
                            gestureValue = newBright
                            val attr = window?.attributes
                            attr?.screenBrightness = newBright
                            window?.attributes = attr
                            gestureIcon = when {
                                newBright > 0.6 -> Icons.Default.BrightnessHigh
                                newBright > 0.3 -> Icons.Default.BrightnessMedium
                                else -> Icons.Default.BrightnessLow
                            }
                        }
                    }
                )
            }
    ) {
        // 1. Player View with Dynamic Resize Mode
        AndroidView(
            factory = { 
                PlayerView(it).apply { 
                    player = exoPlayer
                    useController = false
                    // Start with Fit
                    this.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                    setKeepContentOnPlayerReset(true)
                } 
            },
            update = { playerView ->
                // Update resize mode when state changes
                playerView.resizeMode = resizeMode
            },
            modifier = Modifier.fillMaxSize()
        )

        // 2. Gesture Overlays
        SeekIndicator(direction = showSeekUI)
        
        AnimatedVisibility(
            visible = gestureMode != GestureMode.NONE,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.Center)
        ) {
            GestureInfoDialog(
                icon = gestureIcon,
                value = gestureValue,
                max = if (gestureMode == GestureMode.VOLUME) maxVolume.toFloat() else 1f,
                isVolume = gestureMode == GestureMode.VOLUME
            )
        }

        // 3. Aspect Ratio Toast
        AnimatedVisibility(
            visible = resizeMessage != null,
            enter = fadeIn() + slideInVertically { -it },
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.TopCenter).padding(top = 80.dp)
        ) {
            Surface(
                color = Color.Black.copy(0.7f), 
                shape = RoundedCornerShape(20.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(0.3f))
            ) {
                Text(
                    text = resizeMessage ?: "",
                    color = Color.White,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.labelLarge
                )
            }
        }

        LaunchedEffect(showSeekUI) {
            if (showSeekUI != SeekDirection.NONE) {
                delay(600)
                showSeekUI = SeekDirection.NONE
            }
        }

        if (isLoading) {
            CircularProgressIndicator(modifier = Modifier.align(Alignment.Center), color = MaterialTheme.colorScheme.primary)
        }

        errorMessage?.let { msg ->
            Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(0.8f)), contentAlignment = Alignment.Center) {
                Text(text = msg, color = Color.White, textAlign = TextAlign.Center)
            }
        }

        // 4. Controls
        AnimatedVisibility(
            visible = showControls && errorMessage == null && !isLocked, 
            enter = fadeIn(), 
            exit = fadeOut()
        ) {
            PlayerControls(
                isPlaying = isPlaying, 
                isLandscape = isLandscape,
                currentPosition = currentPosition,
                duration = duration,
                bufferedPercentage = bufferedPercentage,
                isLive = isLiveStream,
                resizeMode = resizeMode,
                title = "Channel Name", // Can be passed as parameter
                onPlayPauseToggle = { if (exoPlayer.isPlaying) exoPlayer.pause() else exoPlayer.play() },
                onSeek = { pos -> exoPlayer.seekTo(pos) },
                onBack = { 
                    if (isLandscape) { 
                        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                        isLandscape = false 
                    } else { 
                        exoPlayer.release()
                        onBack() 
                    } 
                },
                onLockToggle = { isLocked = !isLocked },
                onRotateClick = {
                    isLandscape = !isLandscape
                    activity?.requestedOrientation = if (isLandscape) ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                },
                onResizeClick = {
                    // Cycle modes: Fit -> Zoom -> Fill -> Fit
                    resizeMode = when(resizeMode) {
                        AspectRatioFrameLayout.RESIZE_MODE_FIT -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                        AspectRatioFrameLayout.RESIZE_MODE_ZOOM -> AspectRatioFrameLayout.RESIZE_MODE_FILL
                        else -> AspectRatioFrameLayout.RESIZE_MODE_FIT
                    }
                    resizeMessage = when(resizeMode) {
                        AspectRatioFrameLayout.RESIZE_MODE_FIT -> "Fit to Screen"
                        AspectRatioFrameLayout.RESIZE_MODE_ZOOM -> "Zoom (Crop)"
                        AspectRatioFrameLayout.RESIZE_MODE_FILL -> "Stretch to Fill"
                        else -> "Original"
                    }
                }
            )
            DataUsageOverlay(totalData = formatBytes(totalDataUsed), speed = currentSpeed, modifier = Modifier.align(Alignment.TopEnd))
        }
        
        AnimatedVisibility(visible = isLocked, enter = fadeIn(), exit = fadeOut()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                IconButton(onClick = { isLocked = false }, modifier = Modifier.size(70.dp).background(Color.Black.copy(0.5f), CircleShape)) {
                    Icon(Icons.Default.LockOpen, "Unlock", tint = Color.White, modifier = Modifier.size(35.dp))
                }
            }
        }
    }
    
    LaunchedEffect(showControls, isLocked, isPlaying) {
        if (showControls && !isLocked && isPlaying) {
            delay(4000)
            showControls = false
        }
    }
}

// --- UI Components ---

@Composable
fun GestureInfoDialog(icon: ImageVector, value: Float, max: Float, isVolume: Boolean) {
    Box(
        modifier = Modifier
            .background(Color.Black.copy(0.6f), RoundedCornerShape(16.dp))
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(48.dp))
            Spacer(modifier = Modifier.height(12.dp))
            
            val percent = if (max > 0) (value / max * 100).toInt() else 0
            Text(text = "$percent%", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 20.sp)
            
            Spacer(modifier = Modifier.height(8.dp))
            LinearProgressIndicator(
                progress = { if(max>0) value/max else 0f },
                color = MaterialTheme.colorScheme.primary,
                trackColor = Color.White.copy(0.3f),
                modifier = Modifier.width(100.dp).height(6.dp).clip(RoundedCornerShape(3.dp)),
            )
        }
    }
}

@Composable
fun PlayerControls(
    isPlaying: Boolean, 
    isLandscape: Boolean,
    currentPosition: Long,
    duration: Long,
    bufferedPercentage: Int,
    isLive: Boolean,
    resizeMode: Int,
    title: String,
    onPlayPauseToggle: () -> Unit, 
    onSeek: (Long) -> Unit,
    onBack: () -> Unit, 
    onLockToggle: () -> Unit, 
    onRotateClick: () -> Unit,
    onResizeClick: () -> Unit
) {
    Column(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.3f))
    ) {
        // Top Bar
        Row(
            Modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(Color.Black.copy(0.8f), Color.Transparent))).padding(16.dp), 
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Color.White) }
                Spacer(Modifier.width(8.dp))
                if (isLive) {
                    Box(Modifier.background(Color.Red, RoundedCornerShape(4.dp)).padding(horizontal = 8.dp, vertical = 2.dp)) {
                        Text("LIVE", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    }
                }
            }
            Row {
                IconButton(onClick = onResizeClick) {
                    val icon = when(resizeMode) {
                        AspectRatioFrameLayout.RESIZE_MODE_FIT -> Icons.Default.AspectRatio
                        AspectRatioFrameLayout.RESIZE_MODE_ZOOM -> Icons.Default.ZoomOutMap
                        else -> Icons.Default.FitScreen
                    }
                    Icon(icon, "Resize", tint = Color.White)
                }
            }
        }

        // Center Area (Play/Pause)
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            IconButton(
                onClick = onPlayPauseToggle, 
                modifier = Modifier
                    .size(80.dp)
                    .background(Color.Black.copy(0.4f), CircleShape)
            ) {
                Icon(
                    if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, 
                    "Play/Pause", 
                    tint = Color.White, 
                    modifier = Modifier.size(50.dp)
                )
            }
        }

        // Bottom Bar
        Column(
            Modifier
                .fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(0.9f))))
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            // Progress Bar (Different for Live vs VOD)
            if (isLive) {
                LinearProgressIndicator(
                    progress = { 1f },
                    modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)),
                    color = Color.Red,
                    trackColor = Color.White.copy(0.2f)
                )
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                     Text("Live Broadcast", color = Color.White.copy(0.7f), fontSize = 12.sp)
                }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(formatDuration(currentPosition), color = Color.White, fontSize = 12.sp)
                    Slider(
                        value = currentPosition.toFloat(),
                        onValueChange = { onSeek(it.toLong()) },
                        valueRange = 0f..duration.toFloat().coerceAtLeast(1f),
                        colors = SliderDefaults.colors(
                            thumbColor = MaterialTheme.colorScheme.primary,
                            activeTrackColor = MaterialTheme.colorScheme.primary,
                            inactiveTrackColor = Color.White.copy(0.3f)
                        ),
                        modifier = Modifier.weight(1f).padding(horizontal = 8.dp)
                    )
                    Text(formatDuration(duration), color = Color.White, fontSize = 12.sp)
                }
            }

            // Bottom Buttons
            Row(
                Modifier.fillMaxWidth().padding(top = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onLockToggle) { 
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Default.Lock, "Lock", tint = Color.White)
                        Text("Lock", color = Color.White, fontSize = 10.sp)
                    }
                }
                
                // You can add more buttons here (Audio track, Subtitles etc)
                
                IconButton(onClick = onRotateClick) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(if (isLandscape) Icons.Default.FullscreenExit else Icons.Default.Fullscreen, "Rotate", tint = Color.White)
                        Text("Rotate", color = Color.White, fontSize = 10.sp)
                    }
                }
            }
        }
    }
}

@Composable
fun BoxScope.SeekIndicator(direction: SeekDirection) {
    Row(
        Modifier.fillMaxSize(),
        horizontalArrangement = Arrangement.SpaceAround,
        verticalAlignment = Alignment.CenterVertically
    ) {
        AnimatedVisibility(
            visible = direction == SeekDirection.BACKWARD,
            enter = fadeIn() + scaleIn(),
            exit = fadeOut() + scaleOut(),
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.background(Color.Black.copy(0.5f), CircleShape).padding(24.dp)
            ) {
                Icon(Icons.Default.FastRewind, "Rewind", tint = Color.White, modifier = Modifier.size(40.dp))
                Text("-10s", color = Color.White, fontWeight = FontWeight.Bold)
            }
        }
        AnimatedVisibility(
            visible = direction == SeekDirection.FORWARD,
            enter = fadeIn() + scaleIn(),
            exit = fadeOut() + scaleOut(),
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.background(Color.Black.copy(0.5f), CircleShape).padding(24.dp)
            ) {
                Icon(Icons.Default.FastForward, "Forward", tint = Color.White, modifier = Modifier.size(40.dp))
                Text("+10s", color = Color.White, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
fun DataUsageOverlay(totalData: String, speed: String, modifier: Modifier) {
    Column(
        modifier = modifier
            .padding(16.dp)
            .background(Color.Black.copy(0.5f), RoundedCornerShape(8.dp))
            .padding(8.dp)
    ) {
        Text("Data: $totalData", color = Color.Green, fontSize = 10.sp)
        Text("Speed: $speed", color = Color.Yellow, fontSize = 10.sp)
    }
}

// --- Utils ---

private fun formatDuration(millis: Long): String {
    val totalSeconds = millis / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    val hours = minutes / 60
    return if (hours > 0) {
        String.format("%02d:%02d:%02d", hours, minutes % 60, seconds)
    } else {
        String.format("%02d:%02d", minutes, seconds)
    }
}

private fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val kb = bytes / 1024
    if (kb < 1024) return "$kb KB"
    val mb = kb / 1024
    return String.format("%.1f MB", kb / 1024.0)
}

private fun formatSpeed(bytesPerSecond: Long): String {
    if (bytesPerSecond < 0) return "0 B/s"
    if (bytesPerSecond < 1024) return "$bytesPerSecond B/s"
    val kbPerSecond = bytesPerSecond / 1024.0
    if (kbPerSecond < 1024) return "${kbPerSecond.toInt()} KB/s"
    val mbPerSecond = kbPerSecond / 1024.0
    return String.format("%.1f MB/s", mbPerSecond)
}

@Composable
private fun KeepScreenOn() {
    val view = LocalView.current
    DisposableEffect(Unit) {
        val window = (view.context as? Activity)?.window
        window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }
}

@Composable
private fun HideSystemUi(activity: Activity?) {
    val window = activity?.window ?: return
    DisposableEffect(Unit) {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        onDispose {
            WindowCompat.setDecorFitsSystemWindows(window, true)
            controller.show(WindowInsetsCompat.Type.systemBars())
            activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }
}
