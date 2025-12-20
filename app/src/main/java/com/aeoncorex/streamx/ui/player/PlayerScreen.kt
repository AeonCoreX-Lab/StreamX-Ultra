package com.aeoncorex.streamx.ui.player

import android.app.Activity
import android.content.Context
import android.media.AudioManager
import android.os.Build
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
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
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.delay
import java.net.URLDecoder
import kotlin.math.abs

@OptIn(UnstableApi::class)
@Composable
fun PlayerScreen(encodedUrl: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val activity = context as? Activity
    var isLoading by remember { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var showControls by remember { mutableStateOf(true) }
    var isLocked by remember { mutableStateOf(false) }

    val streamUrl = remember { URLDecoder.decode(encodedUrl, "UTF-8") }

    val exoPlayer = remember {
        val headers = mapOf(
            "User-Agent" to "HDStreamz/3.1.0 (Linux; Android 11; SM-G973F)",
            "Referer" to "https://www.google.com/"
        )
        val httpDataSourceFactory = DefaultHttpDataSource.Factory()
            .setUserAgent(headers["User-Agent"])
            .setDefaultRequestProperties(headers)
            .setConnectTimeoutMs(15000)
            .setReadTimeoutMs(15000)
            .setAllowCrossProtocolRedirects(true)
        val mediaSourceFactory = DefaultMediaSourceFactory(context).setDataSourceFactory(httpDataSourceFactory)

        ExoPlayer.Builder(context).setMediaSourceFactory(mediaSourceFactory).build().apply {
            addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(state: Int) {
                    isLoading = state == Player.STATE_BUFFERING
                    if (state == Player.STATE_READY) errorMessage = null
                }
                override fun onPlayerError(error: PlaybackException) {
                    isLoading = false
                    errorMessage = "Sorry, some errors occurred.\nPlease wait..."
                }
            })
        }
    }
    
    LaunchedEffect(errorMessage) {
        if (errorMessage != null) {
            delay(2500)
            onBack()
        }
    }

    LaunchedEffect(streamUrl) {
        val mediaItem = MediaItem.fromUri(streamUrl)
        exoPlayer.setMediaItem(mediaItem)
        exoPlayer.prepare()
        exoPlayer.play()
    }

    DisposableEffect(Unit) { onDispose { exoPlayer.release() } }
    BackHandler { exoPlayer.release(); onBack() }
    KeepScreenOn()
    HideSystemUi(activity)
    
    var brightness by remember { mutableStateOf(activity?.window?.attributes?.screenBrightness?.takeIf { it > 0 } ?: 0.5f) }
    val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
    var volume by remember { mutableStateOf(audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInput(Unit) {
                if (isLocked) return@pointerInput
                detectTapGestures(
                    onTap = { showControls = !showControls },
                    onDoubleTap = { offset ->
                        if (offset.x < size.width / 2) exoPlayer.seekBack() else exoPlayer.seekForward()
                        showControls = true
                    }
                )
            }
            .pointerInput(Unit) {
                if (isLocked) return@pointerInput
                var dragStartedOnLeft = false
                var verticalDragTotal = 0f
                detectVerticalDragGestures(
                    onDragStart = { offset ->
                        verticalDragTotal = 0f
                        dragStartedOnLeft = offset.x < size.width / 2
                    },
                    onVerticalDrag = { _, dragAmount ->
                        verticalDragTotal += dragAmount
                        if (abs(verticalDragTotal) > 15f) {
                            if (dragStartedOnLeft) {
                                val newBrightness = (brightness - (verticalDragTotal / (size.height * 0.7f))).coerceIn(0.05f, 1.0f)
                                activity?.window?.let {
                                    val attributes = it.attributes
                                    attributes.screenBrightness = newBrightness
                                    it.attributes = attributes
                                    brightness = newBrightness
                                }
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
                isPlaying = exoPlayer.isPlaying,
                onPlayPauseToggle = { if (exoPlayer.isPlaying) exoPlayer.pause() else exoPlayer.play() },
                onBack = { exoPlayer.release(); onBack() },
                onLockToggle = { isLocked = !isLocked },
                onPipClick = { if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) activity?.enterPictureInPictureMode() }
            )
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
            delay(4000)
            showControls = false
        }
    }
}

@Composable
fun PlayerControls(
    isPlaying: Boolean,
    onPlayPauseToggle: () -> Unit,
    onBack: () -> Unit,
    onLockToggle: () -> Unit,
    onPipClick: () -> Unit
) {
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.4f))) {
        Row(Modifier.fillMaxWidth().align(Alignment.TopCenter).padding(16.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Color.White) }
            Row {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    IconButton(onClick = onPipClick) { Icon(Icons.Default.PictureInPicture, "PiP", tint = Color.White) }
                }
                IconButton(onClick = onLockToggle) { Icon(Icons.Default.Lock, "Lock", tint = Color.White) }
            }
        }
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            IconButton(onClick = onPlayPauseToggle, modifier = Modifier.size(70.dp)) {
                Icon(if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, "Play/Pause", tint = Color.White, modifier = Modifier.size(50.dp))
            }
        }
    }
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
        }
    }
}