package com.aeoncorex.streamx.ui.movie

import android.app.Activity
import android.content.pm.ActivityInfo
import android.net.Uri
import android.util.Log
import android.view.ViewGroup
import android.view.WindowManager
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.navigation.NavController
import java.io.File
import java.net.URLDecoder

@OptIn(UnstableApi::class)
@Composable
fun MoviePlayerScreen(navController: NavController, encodedUrl: String) {
    val context = LocalContext.current
    val decodedUrl = remember { try { URLDecoder.decode(encodedUrl, "UTF-8") } catch(e: Exception) { encodedUrl } }
    
    // Engine States
    var videoPath by remember { mutableStateOf<String?>(null) }
    var statusMsg by remember { mutableStateOf("Starting Engine...") }
    var bufferProgress by remember { mutableIntStateOf(0) }
    var downloadSpeed by remember { mutableStateOf("0 KB/s") }
    var seeds by remember { mutableIntStateOf(0) }
    var showError by remember { mutableStateOf(false) }

    // ১. ফুলস্ক্রিন ও ওরিয়েন্টেশন লজিক
    DisposableEffect(Unit) {
        val activity = context as? Activity
        val window = activity?.window
        
        // Force Landscape & Hide Bars
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        if (window != null) {
            WindowCompat.getInsetsController(window, window.decorView).apply {
                hide(WindowInsetsCompat.Type.systemBars())
                systemBarsBehavior = androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }

        onDispose {
            // Restore Portrait and System Bars
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            if (window != null) {
                WindowCompat.getInsetsController(window, window.decorView).show(WindowInsetsCompat.Type.systemBars())
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
            // ইঞ্জিন বন্ধ করা (User যখন স্ক্রিন থেকে বের হবে)
            TorrentEngine.stop()
        }
    }

    // ২. ইঞ্জিন স্টার্ট করা
    LaunchedEffect(decodedUrl) {
        if (decodedUrl.startsWith("magnet:?")) {
            TorrentEngine.start(context, decodedUrl).collect { state ->
                when(state) {
                    is StreamState.Preparing -> statusMsg = state.message
                    is StreamState.Buffering -> {
                        bufferProgress = state.progress
                        downloadSpeed = "${state.speed} KB/s"
                        seeds = state.seeds
                        statusMsg = "Buffering... $bufferProgress%"
                    }
                    is StreamState.Ready -> {
                        // শুধুমাত্র পাথ চেঞ্জ হলেই আপডেট হবে
                        if (videoPath != state.filePath) {
                            Log.d("Player", "Ready to play: ${state.filePath}")
                            videoPath = state.filePath
                        }
                        statusMsg = "" 
                    }
                    is StreamState.Error -> {
                        statusMsg = state.message
                        showError = true
                    }
                }
            }
        } else {
            // ডাইরেক্ট লিঙ্ক (Web Server)
            videoPath = decodedUrl
            statusMsg = ""
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        // ৩. ভিডিও প্লেয়ার (ExoPlayer)
        if (videoPath != null) {
            val exoPlayer = remember(videoPath) {
                ExoPlayer.Builder(context).build().apply {
                    try {
                        val uri = if (videoPath!!.startsWith("http")) 
                            Uri.parse(videoPath)
                        else 
                            Uri.parse("file://${videoPath!!}") // Safe local file URI

                        val mediaItem = MediaItem.fromUri(uri)
                        setMediaItem(mediaItem)
                        prepare()
                        playWhenReady = true
                    } catch (e: Exception) {
                        Log.e("Player", "Error loading media: ${e.message}")
                        statusMsg = "Player Error: ${e.message}"
                        showError = true
                    }
                }
            }
            
            DisposableEffect(exoPlayer) {
                onDispose { exoPlayer.release() }
            }

            AndroidView(
                factory = { ctx ->
                    PlayerView(ctx).apply {
                        player = exoPlayer
                        useController = true
                        setShowNextButton(false)
                        setShowPreviousButton(false)
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT, 
                            ViewGroup.LayoutParams.MATCH_PARENT
                        )
                    }
                },
                modifier = Modifier.fillMaxSize()
            )
        }

        // ৪. লোডিং বা স্ট্যাটাস ওভারলে
        if (videoPath == null || (decodedUrl.startsWith("magnet") && bufferProgress < 100)) {
            // ব্লকিং লোডার (যদি ভিডিও এখনো রেডি না হয়)
            if (videoPath == null) {
                Column(
                    modifier = Modifier.align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    CircularProgressIndicator(color = Color.Cyan)
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(statusMsg, color = Color.White, fontWeight = FontWeight.Bold)
                    
                    if(showError) {
                        Spacer(modifier = Modifier.height(16.dp))
                        Button(
                            onClick = { navController.popBackStack() },
                            colors = ButtonDefaults.buttonColors(containerColor = Color.Red)
                        ) { Text("Go Back") }
                    }
                }
            } 
            // মিনিমাল ওভারলে (ভিডিও চলাকালীন ডাউনলোড ইনফো)
            else if (!showError) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(top = 24.dp, end = 24.dp) // Avoid overlap with close button
                        .background(Color.Black.copy(0.6f), RoundedCornerShape(8.dp))
                        .padding(8.dp)
                ) {
                    Column(horizontalAlignment = Alignment.End) {
                        Text("▼ $downloadSpeed", color = Color.Green, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        Text("S:$seeds | P:$bufferProgress%", color = Color.Yellow, fontSize = 10.sp)
                    }
                }
            }
        }
    }
}
