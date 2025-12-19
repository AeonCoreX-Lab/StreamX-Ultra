package com.aeoncorex.streamx.ui.player

import android.app.Activity
import android.content.Context
import android.media.AudioManager
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.activity.compose.BackHandler
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.google.android.exoplayer2.ExoPlayer
import com.google.android.exoplayer2.MediaItem
import com.google.android.exoplayer2.Player
import com.google.android.exoplayer2.source.hls.HlsMediaSource
import com.google.android.exoplayer2.ui.AspectRatioFrameLayout
import com.google.android.exoplayer2.ui.StyledPlayerView
import com.google.android.exoplayer2.upstream.DefaultHttpDataSource
import kotlinx.coroutines.delay

@Composable
fun PlayerScreen(streamUrl: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val activity = context as? Activity
    
    // --- Global IPTV User-Agent Config ---
    val userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/119.0.0.0 Safari/537.36"
    
    val exoPlayer = remember {
        ExoPlayer.Builder(context).build().apply {
            val dataSourceFactory = DefaultHttpDataSource.Factory()
                .setUserAgent(userAgent)
                .setAllowCrossProtocolRedirects(true)
            
            val mediaSource = HlsMediaSource.Factory(dataSourceFactory)
                .createMediaSource(MediaItem.fromUri(streamUrl))
            
            setMediaSource(mediaSource)
            prepare()
            playWhenReady = true
        }
    }

    var isControlsVisible by remember { mutableStateOf(true) }
    var aspectRatioMode by remember { mutableStateOf(AspectRatioFrameLayout.RESIZE_MODE_FIT) }
    
    // Auto-hide controls
    LaunchedEffect(isControlsVisible) {
        if (isControlsVisible) {
            delay(5000)
            isControlsVisible = false
        }
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
    ) {
        AndroidView(
            factory = { ctx ->
                StyledPlayerView(ctx).apply {
                    player = exoPlayer
                    useController = false
                    resizeMode = aspectRatioMode
                    layoutParams = FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                }
            },
            update = { it.resizeMode = aspectRatioMode },
            modifier = Modifier.fillMaxSize()
        )

        // --- All Original Features & UI ---
        AnimatedVisibility(visible = isControlsVisible, enter = fadeIn(), exit = fadeOut()) {
            PlayerControls(
                exoPlayer = exoPlayer,
                onBack = {
                    exoPlayer.release()
                    onBack()
                },
                onAspectRatioChange = { mode -> aspectRatioMode = mode },
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
    onBack: () -> Unit,
    onAspectRatioChange: (Int) -> Unit,
    activity: Activity?
) {
    Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.4f))) {
        // Top Bar
        Row(
            modifier = Modifier.align(Alignment.TopStart).padding(16.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack, modifier = Modifier.background(Color.Black.copy(0.5f), CircleShape)) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Color.White)
            }
        }

        // Center Play/Pause
        var isPlaying by remember { mutableStateOf(exoPlayer.isPlaying) }
        IconButton(
            onClick = {
                if (exoPlayer.isPlaying) exoPlayer.pause() else exoPlayer.play()
                isPlaying = exoPlayer.isPlaying
            },
            modifier = Modifier.align(Alignment.Center).size(80.dp).background(Color.Black.copy(0.3f), CircleShape)
        ) {
            Icon(
                imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                contentDescription = "Play/Pause",
                modifier = Modifier.size(50.dp),
                tint = Color.White
            )
        }

        // Bottom Controls (Aspect Ratio & PiP)
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
            }) {
                Icon(Icons.Default.AspectRatio, "Aspect Ratio", tint = Color.White)
            }

            IconButton(onClick = { activity?.enterPictureInPictureMode() }) {
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
        onDispose {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
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
        onDispose {
            windowInsetsController?.show(WindowInsetsCompat.Type.systemBars())
        }
    }
}
