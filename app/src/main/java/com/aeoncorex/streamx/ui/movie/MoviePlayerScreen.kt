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
    val activity = context as? Activity
    val decodedUrl = remember { try { URLDecoder.decode(encodedUrl, "UTF-8") } catch(e: Exception) { encodedUrl } }
    
    // --- Engine States ---
    var videoPath by remember { mutableStateOf<String?>(null) }
    var statusMsg by remember { mutableStateOf("Initializing Engine...") }
    var bufferProgress by remember { mutableIntStateOf(0) }
    var downloadSpeed by remember { mutableStateOf("0 KB/s") }
    var seeds by remember { mutableIntStateOf(0) }
    var showError by remember { mutableStateOf(false) }

    // ১. ওরিয়েন্টেশন এবং ফুলস্ক্রিন সেটআপ
    DisposableEffect(Unit) {
        activity?.apply {
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            WindowCompat.getInsetsController(window, window.decorView).apply {
                hide(WindowInsetsCompat.Type.statusBars() or WindowInsetsCompat.Type.navigationBars())
            }
        }
        onDispose {
            activity?.apply {
                requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                WindowCompat.getInsetsController(window, window.decorView).apply {
                    show(WindowInsetsCompat.Type.statusBars() or WindowInsetsCompat.Type.navigationBars())
                }
            }
            TorrentEngine.stop() // প্লেয়ার বন্ধ হলে ইঞ্জিনও বন্ধ
        }
    }

    // ২. ইঞ্জিন স্টার্ট এবং লোকাল পাথ লিসেনিং
    LaunchedEffect(decodedUrl) {
        if (decodedUrl.startsWith("magnet:?")) {
            TorrentEngine.start(context, decodedUrl).collect { state ->
                when(state) {
                    is StreamState.Preparing -> {
                        statusMsg = state.message
                    }
                    is StreamState.Buffering -> {
                        bufferProgress = state.progress
                        downloadSpeed = "${state.speed / 1024} KB/s" // Bytes to KB
                        seeds = state.seeds
                        statusMsg = "Buffering Pieces: $bufferProgress%"
                    }
                    is StreamState.Ready -> {
                        // ইঞ্জিন যখন কনফার্ম করবে ফাইলটি প্লে করার জন্য যথেষ্ট ডাউনলোড হয়েছে
                        if (videoPath != state.filePath) {
                            Log.d("StreamX_Player", "Setting Path: ${state.filePath}")
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
            // যদি এটি সরাসরি কোনো ওয়েব লিঙ্ক হয় (Direct Link)
            videoPath = decodedUrl
            statusMsg = ""
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        
        // ৩. ExoPlayer ইমপ্লিমেন্টেশন
        videoPath?.let { path ->
            val exoPlayer = remember(path) {
                ExoPlayer.Builder(context).build().apply {
                    val uri = if (path.startsWith("http")) {
                        Uri.parse(path)
                    } else {
                        // লোকাল ফাইলের জন্য সঠিক URI ফরম্যাট
                        Uri.fromFile(File(path))
                    }
                    
                    setMediaItem(MediaItem.fromUri(uri))
                    prepare()
                    playWhenReady = true
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
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT, 
                            ViewGroup.LayoutParams.MATCH_PARENT
                        )
                    }
                },
                modifier = Modifier.fillMaxSize()
            )
        }

        // ৪. ইউজার ইন্টারফেস ওভারলে (Loading & Stats)
        
        // ক) যখন মুভি এখনো শুরু হয়নি (Loading Screen)
        if (videoPath == null) {
            Column(
                modifier = Modifier.align(Alignment.Center),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                CircularProgressIndicator(color = Color.Cyan, strokeWidth = 4.dp)
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = statusMsg, 
                    color = Color.White, 
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp
                )
                
                if (showError) {
                    Spacer(modifier = Modifier.height(16.dp))
                    Button(
                        onClick = { navController.popBackStack() },
                        colors = ButtonDefaults.buttonColors(containerColor = Color.Red)
                    ) {
                        Text("Go Back")
                    }
                }
            }
        } 
        
        // খ) মুভি চলাকালীন ছোট স্ট্যাটাস ওভারলে (ডাউনলোড স্পিড দেখার জন্য)
        else if (decodedUrl.startsWith("magnet") && !showError) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(16.dp)
                    .background(Color.Black.copy(0.6f), RoundedCornerShape(8.dp))
                    .padding(8.dp)
            ) {
                Column(horizontalAlignment = Alignment.End) {
                    Text("▼ $downloadSpeed", color = Color.Green, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    Text("Seeds: $seeds | Progress: $bufferProgress%", color = Color.Yellow, fontSize = 10.sp)
                }
            }
        }
        
        // গ) ব্যাক বাটন (ফ্লোটিং)
        IconButton(
            onClick = { navController.popBackStack() },
            modifier = Modifier.padding(16.dp).align(Alignment.TopStart)
        ) {
            Icon(
                imageVector = androidx.compose.material.icons.Icons.Default.Close,
                contentDescription = "Close",
                tint = Color.White.copy(0.7f)
            )
        }
    }
}
