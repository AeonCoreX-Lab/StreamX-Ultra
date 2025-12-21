
package com.aeoncorex.streamx.ui.player

import android.app.Activity
import android.content.Context
import android.content.pm.ActivityInfo
import android.media.AudioManager
import android.os.Build
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.TransferListener
import androidx.compose.animation.core.tween
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.delay
import java.net.URLDecoder
import kotlin.math.abs

enum class SeekDirection { NONE, FORWARD, BACKWARD }

@OptIn(UnstableApi::class)
@Composable
fun PlayerScreen(encodedUrl: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val activity = context as? Activity

    // States
    var isLoading by remember { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var showControls by remember { mutableStateOf(true) }
    var isLocked by remember { mutableStateOf(false) }
    var isPlaying by remember { mutableStateOf(true) }
    
    // New high-level feature states
    var totalDataUsed by remember { mutableStateOf(0L) }
    var currentSpeed by remember { mutableStateOf("0 KB/s") }
    var showSeekUI by remember { mutableStateOf(SeekDirection.NONE) }
    var isLandscape by remember { mutableStateOf(false) }

    val streamUrl = remember { URLDecoder.decode(encodedUrl, "UTF-8") }

    val exoPlayer = remember {
        // --- এই অংশটি ফিক্স করা হয়েছে ---
        // DataSource.Listener-এর পরিবর্তে সঠিক TransferListener ইন্টারফেস ব্যবহার করা হচ্ছে
        val dataSourceListener = object : TransferListener {
            override fun onBytesTransferred(source: DataSource, dataSpec: com.media3.datasource.DataSpec, isNetwork: Boolean, bytesTransferred: Int) {
                if (isNetwork) {
                    totalDataUsed += bytesTransferred
                }
            }
        }

        val httpDataSourceFactory = DefaultHttpDataSource.Factory()
            .setUserAgent("HDStreamz/3.1.0 (Linux; Android 11; SM-G973F)")
            .setDefaultRequestProperties(mapOf("Referer" to "https://www.google.com/"))
            .setTransferListener(dataSourceListener) // এখন এটি সঠিকভাবে কাজ করবে
        
        val mediaSourceFactory = DefaultMediaSourceFactory(context).setDataSourceFactory(httpDataSourceFactory)

        ExoPlayer.Builder(context).setMediaSourceFactory(mediaSourceFactory).build().apply {
            setMediaItem(MediaItem.fromUri(streamUrl))
            playWhenReady = true
            prepare()
            addListener(object : Player.Listener {
                override fun onIsPlayingChanged(playing: Boolean) { isPlaying = playing }
                override fun onPlaybackStateChanged(state: Int) { isLoading = state == Player.STATE_BUFFERING }
                override fun onPlayerError(error: PlaybackException) {
                    isLoading = false
                    errorMessage = "Sorry, some errors occurred.\nPlease wait..."
                }
            })
        }
    }
    
    DisposableEffect(Unit) { onDispose { exoPlayer.release() } }
    BackHandler { exoPlayer.release(); onBack() }
    KeepScreenOn()
    HideSystemUi(activity)
    
    var brightness by remember { mutableStateOf(activity?.window?.attributes?.screenBrightness?.takeIf { it > 0 } ?: 0.5f) }
    val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
    var volume by remember { mutableStateOf(audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)) }

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

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInput(Unit) {
                if (isLocked) return@pointerInput
                detectTapGestures(
                    onTap = { showControls = !showControls },
                    onDoubleTap = { offset ->
                        showControls = true
                        if (offset.x > size.width / 2) {
                            exoPlayer.seekForward()
                            showSeekUI = SeekDirection.FORWARD
                        } else {
                            exoPlayer.seekBack()
                            showSeekUI = SeekDirection.BACKWARD
                        }
                    }
                )
            }
            .pointerInput(Unit) {
                if (isLocked) return@pointerInput
                var dragStartedOnLeft = false
                var verticalDragTotal = 0f
                detectVerticalDragGestures(
                    onDragStart = { offset -> verticalDragTotal = 0f; dragStartedOnLeft = offset.x < size.width / 2 },
                    onVerticalDrag = { _, dragAmount ->
                        verticalDragTotal += dragAmount
                        if (abs(verticalDragTotal) > 15f) {
                            if (dragStartedOnLeft) {
                                val newBrightness = (brightness - (verticalDragTotal / (size.height * 0.7f))).coerceIn(0.05f, 1.0f)
                                activity?.window?.let { val attributes = it.attributes; attributes.screenBrightness = newBrightness; it.attributes = attributes; brightness = newBrightness }
                            } else {
                                val delta = -(verticalDragTotal / (size.height * 0.7f)) * maxVolume
                                val newVolume = (volume + delta.toInt()).coerceIn(0, maxVolume)
                                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, newVolume, 0)
                                volume = newVolume
                            }
                            verticalDragTotal = 0f
                        }
                    }
                )
            }
    ) {
        AndroidView(
            factory = { PlayerView(it).apply { player = exoPlayer; useController = false; resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT } },
            modifier = Modifier.fillMaxSize()
        )

        SeekIndicator(direction = showSeekUI)

        LaunchedEffect(showSeekUI) {
            if (showSeekUI != SeekDirection.NONE) {
                delay(600)
                showSeekUI = SeekDirection.NONE
            }
        }

        if (isLoading) {
            CircularProgressIndicator(modifier = Modifier.align(Alignment.Center), color = Color.Cyan)
        }

        errorMessage?.let { msg ->
            Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(0.8f)), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(24.dp)) {
                    Text(text = msg, color = Color.White, fontSize = 16.sp, textAlign = TextAlign.Center, lineHeight = 24.sp)
                    Spacer(modifier = Modifier.height(8.dp))
                    LinearProgressIndicator(color = Color.White, modifier = Modifier.fillMaxWidth(0.6f))
                }
            }
        }

        AnimatedVisibility(visible = showControls && errorMessage == null && !isLocked, enter = fadeIn(), exit = fadeOut()) {
            PlayerControls(
                isPlaying = isPlaying,
                isLandscape = isLandscape,
                onPlayPauseToggle = { if (exoPlayer.isPlaying) exoPlayer.pause() else exoPlayer.play() },
                onBack = { exoPlayer.release(); onBack() },
                onLockToggle = { isLocked = !isLocked },
                onRotateClick = {
                    isLandscape = !isLandscape
                    activity?.requestedOrientation = if (isLandscape) ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
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
    
    LaunchedEffect(showControls, isLocked) {
        if (showControls && !isLocked) {
            delay(5000)
            showControls = false
        }
    }
}

@Composable
fun PlayerControls(
    isPlaying: Boolean, isLandscape: Boolean,
    onPlayPauseToggle: () -> Unit, onBack: () -> Unit, onLockToggle: () -> Unit, onRotateClick: () -> Unit
) {
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.4f))) {
        Row(Modifier.fillMaxWidth().align(Alignment.TopCenter).padding(16.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Color.White) }
        }
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            IconButton(onClick = onPlayPauseToggle, modifier = Modifier.size(70.dp)) {
                Icon(if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, "Play/Pause", tint = Color.White, modifier = Modifier.size(50.dp))
            }
        }
        Row(
            Modifier.fillMaxWidth().align(Alignment.BottomCenter).padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onLockToggle) { Icon(Icons.Default.Lock, "Lock", tint = Color.White) }
            IconButton(onClick = onRotateClick) {
                Icon(if (isLandscape) Icons.Default.FullscreenExit else Icons.Default.Fullscreen, "Rotate", tint = Color.White)
            }
        }
    }
}

@Composable
fun DataUsageOverlay(totalData: String, speed: String, modifier: Modifier = Modifier) {
    Row(modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text = speed, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.width(8.dp))
        Text(text = totalData, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
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
            enter = fadeIn(animationSpec = tween(200)) + scaleIn(),
            exit = fadeOut(animationSpec = tween(400)) + scaleOut(),
        ) {
            Icon(Icons.Default.FastRewind, "Rewind", tint = Color.White, modifier = Modifier.size(60.dp))
        }
        AnimatedVisibility(
            visible = direction == SeekDirection.FORWARD,
            enter = fadeIn(animationSpec = tween(200)) + scaleIn(),
            exit = fadeOut(animationSpec = tween(400)) + scaleOut(),
        ) {
            Icon(Icons.Default.FastForward, "Forward", tint = Color.White, modifier = Modifier.size(60.dp))
        }
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