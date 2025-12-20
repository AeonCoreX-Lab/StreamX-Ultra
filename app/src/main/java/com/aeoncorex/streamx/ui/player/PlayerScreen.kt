package com.aeoncorex.streamx.ui.player

import android.app.Activity
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.delay

@OptIn(UnstableApi::class)
@Composable
fun PlayerScreen(streamUrl: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val activity = context as? Activity
    var isLoading by remember { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var showControls by remember { mutableStateOf(true) }

    // HD Streamz-এর মতো উন্নত ExoPlayer ইঞ্জিন
    val exoPlayer = remember {
        // রিয়েল-টাইম সার্ভার কমিউনিকেশনের জন্য কাস্টম ডাটা সোর্স
        val httpDataSourceFactory = DefaultHttpDataSource.Factory()
            .setUserAgent("HDStreamz/3.1.0 (Linux; Android 11; SM-G973F)") // সার্ভারকে বিভ্রান্ত করার জন্য প্রো-ইউজার এজেন্ট
            .setConnectTimeoutMs(10000) // ১০ সেকেন্ডের মধ্যে কানেক্ট না হলে এরর দিবে
            .setReadTimeoutMs(10000)
            .setAllowCrossProtocolRedirects(true)

        ExoPlayer.Builder(context)
            .setMediaSourceFactory(DefaultMediaSourceFactory(context).setDataSourceFactory(httpDataSourceFactory))
            .build().apply {
                setMediaSource(MediaItem.fromUri(streamUrl))
                prepare()
                playWhenReady = true

                addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(state: Int) {
                        isLoading = (state == Player.STATE_BUFFERING)
                        if (state == Player.STATE_READY) {
                            errorMessage = null // ভিডিও শুরু হলে এরর মেসেজ মুছে যাবে
                        }
                    }

                    override fun onPlayerError(error: PlaybackException) {
                        isLoading = false
                        // রিয়েল-টাইম এরর মেসেজ লজিক (HD Streamz Style)
                        val cause = error.cause
                        errorMessage = when {
                            // সার্ভার যদি রেসপন্স না করে বা ইন্টারনেট না থাকে
                            cause is java.net.ConnectException || cause is java.net.UnknownHostException -> 
                                "Server communication failed! Please check your internet connection."
                            
                            // যদি লিঙ্কটি ব্রোকেন হয় (HTTP 404/403)
                            error.errorCode == PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS ||
                            error.errorCode == PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND ->
                                "Source Link Broken! This channel is currently offline from server (404)."

                            // যদি সার্ভার খুব স্লো হয় (Timeout)
                            error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT ->
                                "Request Timeout! Server is taking too long to respond."

                            else -> "Live stream error: ${error.localizedMessage}"
                        }
                    }
                })
            }
    }

    // ব্যাক হ্যান্ডলিং এবং রিসোর্স রিলিজ
    BackHandler {
        exoPlayer.release()
        onBack()
    }

    // ফুলস্ক্রিন এবং স্ক্রিন অন রাখার লজিক
    KeepScreenOn()
    HideSystemUi(activity)

    Box(modifier = Modifier
        .fillMaxSize()
        .background(Color.Black)
        .pointerInput(Unit) {
            detectTapGestures(onTap = { showControls = !showControls })
        }
    ) {
        // প্লেয়ার ভিউ
        AndroidView(
            factory = {
                PlayerView(it).apply {
                    player = exoPlayer
                    useController = false
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                    layoutParams = FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                }
            },
            modifier = Modifier.fillMaxSize()
        )

        // HD Streamz লোডিং ইন্ডিকেটর (সবুজ রঙের)
        if (isLoading) {
            CircularProgressIndicator(
                modifier = Modifier.align(Alignment.Center),
                color = Color.Green,
                strokeWidth = 4.dp
            )
        }

        // রিয়েল-টাইম এরর মেসেজ ডিসপ্লে
        errorMessage?.let { msg ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(0.85f)),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(24.dp)) {
                    Icon(Icons.Default.ErrorOutline, contentDescription = null, tint = Color.Red, size = 60.dp)
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "ERROR DETECTED",
                        color = Color.Red,
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = msg,
                        color = Color.White,
                        textAlign = TextAlign.Center,
                        fontSize = 14.sp
                    )
                    Spacer(modifier = Modifier.height(24.dp))
                    Button(
                        onClick = { 
                            errorMessage = null
                            isLoading = true
                            exoPlayer.prepare()
                            exoPlayer.play() 
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color.Green)
                    ) {
                        Text("RETRY CONNECTION", color = Color.Black, fontWeight = FontWeight.Black)
                    }
                }
            }
        }

        // কাস্টম কন্ট্রোলস (Auto-hide)
        if (showControls && errorMessage == null) {
            // টপ বার
            Box(modifier = Modifier.fillMaxSize().padding(16.dp)) {
                IconButton(
                    onClick = { 
                        exoPlayer.release()
                        onBack() 
                    },
                    modifier = Modifier.align(Alignment.TopStart).background(Color.Black.copy(0.5f), CircleShape)
                ) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Color.White)
                }

                // সেন্টার প্লে/পজ
                IconButton(
                    onClick = { if (exoPlayer.isPlaying) exoPlayer.pause() else exoPlayer.play() },
                    modifier = Modifier
                        .align(Alignment.Center)
                        .size(70.dp)
                        .background(Color.Black.copy(0.4f), CircleShape)
                ) {
                    Icon(
                        if (exoPlayer.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(50.dp)
                    )
                }
            }
        }
    }

    LaunchedEffect(showControls) {
        if (showControls) {
            delay(4000)
            showControls = false
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
