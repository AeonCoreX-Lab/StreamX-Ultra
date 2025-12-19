package com.aeoncorex.streamx.ui.player

import android.app.Activity
import android.content.Context
import android.media.AudioManager
import android.net.Uri
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.delay

@OptIn(UnstableApi::class)
@Composable
fun PlayerScreen(streamUrl: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val activity = context as? Activity
    
    val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    var currentVolume by remember { mutableStateOf(audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)) }
    val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
    
    var currentBrightness by remember { mutableStateOf(activity?.window?.attributes?.screenBrightness ?: 0.5f) }
    var gestureInfo by remember { mutableStateOf<String?>(null) }
    var isGestureVisible by remember { mutableStateOf(false) }

    val userAgent = "Mozilla/5.0 (Linux; Android 11; SM-G998B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/119.0.0.0 Mobile Safari/537.36"
    var isPlaying by remember { mutableStateOf(true) }
    var isBuffering by remember { mutableStateOf(true) }
    var isControlsVisible by remember { mutableStateOf(true) }
    var aspectRatioMode by remember { mutableStateOf(AspectRatioFrameLayout.RESIZE_MODE_FIT) }

    val exoPlayer = remember {
        ExoPlayer.Builder(context).build().apply {
            val dataSourceFactory = DefaultHttpDataSource.Factory()
                .setUserAgent(userAgent)
                .setAllowCrossProtocolRedirects(true)
            
            setMediaSource(androidx.media3.exoplayer.source.DefaultMediaSourceFactory(dataSourceFactory)
                .createMediaSource(MediaItem.fromUri(Uri.parse(streamUrl))))
            prepare()
            playWhenReady = true
        }
    }

    DisposableEffect(exoPlayer) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                isBuffering = state == Player.STATE_BUFFERING
            }
            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying = playing
            }
        }
        exoPlayer.addListener(listener)
        onDispose { exoPlayer.removeListener(listener) }
    }

    KeepScreenOn()
    HideSystemUi(activity)
    
    BackHandler {
        exoPlayer.release()
        onBack()
    }

    Box(modifier = Modifier
        .fillMaxSize()
        .background(Color.Black)
        .pointerInput(Unit) {
            detectTapGestures(onTap = { isControlsVisible = !isControlsVisible })
        }
        .pointerInput(Unit) {
            detectDragGestures(
                onDragStart = { isGestureVisible = true },
                onDragEnd = { isGestureVisible = false },
                onDrag = { change, dragAmount ->
                    val isLeftScreen = change.position.x < (size.width / 2)
                    if (isLeftScreen) {
                        val delta = -dragAmount.y / size.height
                        currentBrightness = (currentBrightness + delta).coerceIn(0.01f, 1f)
                        val layoutParams = activity?.window?.attributes
                        layoutParams?.screenBrightness = currentBrightness
                        activity?.window?.attributes = layoutParams
                        gestureInfo = "Brightness: ${(currentBrightness * 100).toInt()}%"
                    } else {
                        val delta = if (dragAmount.y > 0) -1 else 1
                        currentVolume = (currentVolume + delta).coerceIn(0, maxVolume)
                        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, currentVolume, 0)
                        gestureInfo = "Volume: $currentVolume"
                    }
                }
            )
        }
    ) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    player = exoPlayer
                    useController = false
                    resizeMode = aspectRatioMode
                }
            },
            update = { it.resizeMode = aspectRatioMode },
            modifier = Modifier.fillMaxSize()
        )

        if (isGestureVisible) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 80.dp)
                    .background(Color.Black.copy(0.6f), RoundedCornerShape(16.dp))
                    .padding(horizontal = 20.dp, vertical = 10.dp)
            ) {
                Text(gestureInfo ?: "", color = Color.Cyan, fontSize = 16.sp)
            }
        }

        if (isBuffering) {
            CircularProgressIndicator(modifier = Modifier.align(Alignment.Center), color = Color.Cyan)
        }

        AnimatedVisibility(visible = isControlsVisible, enter = fadeIn(), exit = fadeOut()) {
            PlayerControls(
                exoPlayer = exoPlayer,
                isPlaying = isPlaying,
                onBack = { exoPlayer.release(); onBack() },
                onAspectRatioChange = { aspectRatioMode = it },
                activity = activity
            )
        }
    }

    DisposableEffect(Unit) {
        onDispose { exoPlayer.release() }
    }
}

@Composable
fun PlayerControls(
    exoPlayer: ExoPlayer,
    isPlaying: Boolean,
    onBack: () -> Unit,
    onAspectRatioChange: (Int) -> Unit,
    activity: Activity?
) {
    Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.4f))) {
        Row(
            modifier = Modifier.align(Alignment.TopStart).padding(16.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack, modifier = Modifier.background(Color.Black.copy(0.4f), CircleShape)) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Color.White)
            }
        }

        IconButton(
            onClick = { if (isPlaying) exoPlayer.pause() else exoPlayer.play() },
            modifier = Modifier.align(Alignment.Center).size(80.dp).background(Color.Black.copy(0.2f), CircleShape)
        ) {
            Icon(
                imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                contentDescription = null,
                modifier = Modifier.size(55.dp),
                tint = Color.White
            )
        }

        Row(
            modifier = Modifier.align(Alignment.BottomCenter).padding(24.dp).fillMaxWidth(),
            horizontalArrangement = Arrangement.End
        ) {
            val modes = listOf(
                AspectRatioFrameLayout.RESIZE_MODE_FIT,
                AspectRatioFrameLayout.RESIZE_MODE_FILL,
                AspectRatioFrameLayout.RESIZE_MODE_ZOOM
            )
            var currentModeIndex by remember { mutableStateOf(0) }

            IconButton(onClick = {
                currentModeIndex = (currentModeIndex + 1) % modes.size
                onAspectRatioChange(modes[currentModeIndex])
            }, modifier = Modifier.background(Color.Black.copy(0.4f), CircleShape)) {
                Icon(Icons.Default.AspectRatio, "Screen", tint = Color.White)
            }

            Spacer(Modifier.width(12.dp))

            IconButton(onClick = { activity?.enterPictureInPictureMode() }, modifier = Modifier.background(Color.Black.copy(0.4f), CircleShape)) {
                Icon(Icons.Default.PictureInPicture, "PiP", tint = Color.White)
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
    val view = activity?.window?.decorView
    val windowInsetsController = view?.let { WindowCompat.getInsetsController(activity.window, it) }

    DisposableEffect(Unit) {
        windowInsetsController?.let {
            it.hide(WindowInsetsCompat.Type.systemBars())
            it.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        onDispose { windowInsetsController?.show(WindowInsetsCompat.Type.systemBars()) }
    }
}
