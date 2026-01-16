package com.aeoncorex.streamx.ui.movie

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.media.AudioManager
import android.net.Uri
import android.provider.Settings
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.*
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.navigation.NavController
import kotlinx.coroutines.delay
import java.net.URLDecoder

@OptIn(UnstableApi::class)
@Composable
fun MoviePlayerScreen(navController: NavController, encodedUrl: String) {
    val context = LocalContext.current
    val url = remember { URLDecoder.decode(encodedUrl, "UTF-8") }
    
    // Check if it's a direct stream (m3u8/mp4) or an Embed (HTML)
    val isDirectStream = url.endsWith(".m3u8") || url.endsWith(".mp4")

    if (isDirectStream) {
        ExoPlayerContent(navController, url)
    } else {
        // Fallback to Ultimate Ad-Block WebView for Embeds (vidsrc)
        AdBlockWebViewContent(navController, url)
    }
}

// -----------------------------------------------------------
// 1. EXO PLAYER (THE ULTIMATE PLAYER)
// -----------------------------------------------------------
@OptIn(UnstableApi::class)
@Composable
fun ExoPlayerContent(navController: NavController, videoUrl: String) {
    val context = LocalContext.current
    val activity = context as? Activity
    
    // Player Setup
    val exoPlayer = remember {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(Uri.parse(videoUrl)))
            prepare()
            playWhenReady = true
        }
    }

    // Gesture States
    var volume by remember { mutableStateOf(0.5f) }
    var brightness by remember { mutableStateOf(0.5f) }
    var isControlsVisible by remember { mutableStateOf(false) }
    var playbackSpeed by remember { mutableStateOf(1.0f) }
    var gestureIcon by remember { mutableStateOf<androidx.compose.ui.graphics.vector.ImageVector?>(null) }
    
    // Fullscreen Logic
    DisposableEffect(Unit) {
        val window = activity?.window
        window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.getInsetsController(window!!, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        onDispose {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            WindowCompat.getInsetsController(window, window.decorView).show(WindowInsetsCompat.Type.systemBars())
            exoPlayer.release()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInput(Unit) {
                detectVerticalDragGestures(
                    onDragStart = { },
                    onDragEnd = { gestureIcon = null }
                ) { change, dragAmount ->
                    val isLeft = change.position.x < size.width / 2
                    if (isLeft) {
                        // Brightness
                        brightness = (brightness - dragAmount / 500f).coerceIn(0f, 1f)
                        val lp = activity?.window?.attributes
                        lp?.screenBrightness = brightness
                        activity?.window?.attributes = lp
                        gestureIcon = Icons.Default.Brightness6
                    } else {
                        // Volume
                        volume = (volume - dragAmount / 500f).coerceIn(0f, 1f)
                        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                        val maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, (volume * maxVol).toInt(), 0)
                        gestureIcon = Icons.Default.VolumeUp
                    }
                }
            }
            .pointerInput(Unit) {
                detectTapGestures(
                    onDoubleTap = { offset ->
                        if (offset.x > size.width / 2) {
                            exoPlayer.seekForward() // Skip 10s
                        } else {
                            exoPlayer.seekBack() // Rewind 10s
                        }
                    },
                    onTap = { isControlsVisible = !isControlsVisible }
                )
            }
    ) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    player = exoPlayer
                    useController = false // Custom UI Used
                    layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                }
            },
            modifier = Modifier.fillMaxSize()
        )

        // Center Gesture Icon
        if (gestureIcon != null) {
            Box(Modifier.align(Alignment.Center).background(Color.Black.copy(0.5f), RoundedCornerShape(16.dp)).padding(24.dp)) {
                Icon(gestureIcon!!, null, tint = Color.White, modifier = Modifier.size(48.dp))
            }
        }

        // Custom Overlay
        AnimatedVisibility(
            visible = isControlsVisible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.fillMaxSize().background(Color.Black.copy(0.4f))
        ) {
            Box(Modifier.fillMaxSize()) {
                // Top Bar
                Row(Modifier.align(Alignment.TopStart).padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { navController.popBackStack() }) { Icon(Icons.Default.ArrowBack, null, tint = Color.White) }
                    Text("Now Playing", color = Color.White, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.weight(1f))
                    // Speed Control
                    TextButton(onClick = {
                        playbackSpeed = if (playbackSpeed >= 2f) 0.5f else playbackSpeed + 0.25f
                        exoPlayer.playbackParameters = PlaybackParameters(playbackSpeed)
                    }) {
                        Text("${playbackSpeed}x", color = Color.White, fontWeight = FontWeight.Bold)
                    }
                }

                // Center Controls
                Row(Modifier.align(Alignment.Center), horizontalArrangement = Arrangement.spacedBy(32.dp)) {
                    IconButton(onClick = { exoPlayer.seekBack() }) { Icon(Icons.Default.Replay10, null, tint = Color.White, modifier = Modifier.size(48.dp)) }
                    IconButton(onClick = { if (exoPlayer.isPlaying) exoPlayer.pause() else exoPlayer.play() }) {
                        Icon(if (exoPlayer.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, null, tint = Color.White, modifier = Modifier.size(64.dp))
                    }
                    IconButton(onClick = { exoPlayer.seekForward() }) { Icon(Icons.Default.Forward10, null, tint = Color.White, modifier = Modifier.size(48.dp)) }
                }
            }
        }
    }
}

// -----------------------------------------------------------
// 2. ULTIMATE AD-BLOCK WEBVIEW (FOR EMBEDS)
// -----------------------------------------------------------
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun AdBlockWebViewContent(navController: NavController, url: String) {
    val context = LocalContext.current
    val activity = context as? Activity
    var isLoading by remember { mutableStateOf(true) }

    // Fullscreen Logic
    DisposableEffect(Unit) {
        val window = activity?.window
        window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.getInsetsController(window!!, window.decorView).hide(WindowInsetsCompat.Type.systemBars())
        onDispose {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            WindowCompat.getInsetsController(window, window.decorView).show(WindowInsetsCompat.Type.systemBars())
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            factory = { ctx ->
                WebView(ctx).apply {
                    layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                    settings.apply {
                        javaScriptEnabled = true
                        domStorageEnabled = true
                        mediaPlaybackRequiresUserGesture = false
                        setSupportMultipleWindows(false) // Blocks popups opening in new windows
                    }
                    
                    webViewClient = object : WebViewClient() {
                        override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                            isLoading = true
                        }
                        override fun onPageFinished(view: WebView?, url: String?) {
                            isLoading = false
                            // Inject JS to remove ads by class name (Generic example)
                            view?.evaluateJavascript(
                                "javascript:(function() { " +
                                "document.querySelectorAll('.ad, .ads, .popup').forEach(el => el.remove());" +
                                "})()", null
                            )
                        }

                        // THE ULTIMATE REDIRECT BLOCKER
                        override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                            val nextUrl = request?.url.toString()
                            // Allow only the video source domain and Google (for captcha/services)
                            if (nextUrl.contains("vidsrc") || nextUrl.contains("google") || nextUrl.contains("cloudflare")) {
                                return false // Load it
                            }
                            return true // Block everything else (Ads, Redirects)
                        }
                    }
                    loadUrl(url)
                }
            },
            modifier = Modifier.fillMaxSize()
        )
        
        // Custom Back Button Overlay
        IconButton(
            onClick = { navController.popBackStack() },
            modifier = Modifier.align(Alignment.TopStart).padding(16.dp).background(Color.Black.copy(0.5f), androidx.compose.foundation.shape.CircleShape)
        ) {
            Icon(Icons.Default.ArrowBack, null, tint = Color.White)
        }

        if (isLoading) {
            CircularProgressIndicator(Modifier.align(Alignment.Center), color = Color.Red)
        }
    }
}
