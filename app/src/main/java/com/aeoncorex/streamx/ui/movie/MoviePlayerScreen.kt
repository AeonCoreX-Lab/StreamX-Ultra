package com.aeoncorex.streamx.ui.movie

import android.app.Activity
import android.content.Context
import android.content.pm.ActivityInfo
import android.net.Uri
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.annotation.OptIn
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.rounded.*
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
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.navigation.NavController
import java.io.File
import java.net.URLDecoder

@OptIn(UnstableApi::class)
@Composable
fun MoviePlayerScreen(navController: NavController, encodedMagnetOrUrl: String) {
    val decodedInput = remember { URLDecoder.decode(encodedMagnetOrUrl, "UTF-8") }
    val context = LocalContext.current

    // States
    var streamUrl by remember { mutableStateOf<String?>(null) }
    var statusMessage by remember { mutableStateOf("Initializing Torrent Engine...") }
    
    // Stats
    var downloadSpeed by remember { mutableStateOf("0 KB/s") }
    var seedsCount by remember { mutableIntStateOf(0) }
    var peersCount by remember { mutableIntStateOf(0) }
    var progress by remember { mutableIntStateOf(0) }
    
    var isError by remember { mutableStateOf(false) }
    
    // Engine Logic
    LaunchedEffect(decodedInput) {
        if (decodedInput.startsWith("magnet:?")) {
            TorrentEngine.init(context)
            TorrentEngine.startStreaming(decodedInput).collect { state ->
                when(state) {
                    is StreamState.Preparing -> statusMessage = state.message
                    is StreamState.Buffering -> {
                        statusMessage = "Downloading Metadata... ${state.progress}%"
                        progress = state.progress
                        // FIXED: Now we access valid properties
                        downloadSpeed = "${state.speed} KB/s"
                        seedsCount = state.seeds
                        peersCount = state.peers
                    }
                    is StreamState.Ready -> {
                        streamUrl = state.filePath
                        statusMessage = ""
                    }
                    is StreamState.Error -> {
                        statusMessage = "Error: ${state.message}"
                        isError = true
                    }
                }
            }
        } else {
            // Direct URL (HTTP)
            streamUrl = decodedInput
        }
    }

    DisposableEffect(Unit) {
        onDispose { TorrentEngine.stop() }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (streamUrl != null) {
            AdvancedExoPlayer(navController, streamUrl!!)
            
            // Show Overlay Stats if Torrenting
            if (decodedInput.startsWith("magnet:?")) {
                Box(Modifier.align(Alignment.TopEnd).padding(top = 40.dp, end = 16.dp)) {
                    Column(horizontalAlignment = Alignment.End) {
                        Text("▼ $downloadSpeed", color = Color.Green, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        Text("S:$seedsCount P:$peersCount | $progress%", color = Color.Yellow, fontSize = 10.sp)
                    }
                }
            }
        } else {
            // Loading UI
            Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator(color = Color.Red)
                Spacer(Modifier.height(16.dp))
                Text(statusMessage, color = Color.White, fontSize = 14.sp)
                
                if (progress > 0) {
                    LinearProgressIndicator(
                        progress = progress / 100f, 
                        modifier = Modifier.width(200.dp).padding(top = 8.dp), 
                        color = Color.Red
                    )
                    Text("$progress%", color = Color.Gray, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
                }
                
                if (isError) {
                    Spacer(Modifier.height(16.dp))
                    Button(onClick = { navController.popBackStack() }, colors = ButtonDefaults.buttonColors(containerColor = Color.Red)) {
                        Text("Exit")
                    }
                }
            }
        }
    }
}

@OptIn(UnstableApi::class)
@Composable
fun AdvancedExoPlayer(navController: NavController, videoSource: String) {
    val context = LocalContext.current
    val activity = activityContext(context)

    // Player Setup
    val exoPlayer = remember {
        ExoPlayer.Builder(context).build().apply {
            val mediaItem = if (videoSource.startsWith("/")) MediaItem.fromUri(Uri.fromFile(File(videoSource))) else MediaItem.fromUri(Uri.parse(videoSource))
            setMediaItem(mediaItem)
            prepare()
            playWhenReady = true
        }
    }

    // UI States
    var isControlsVisible by remember { mutableStateOf(false) }
    var isLocked by remember { mutableStateOf(false) }
    var showTrackSelector by remember { mutableStateOf(false) }
    var trackSelectorType by remember { mutableStateOf(C.TRACK_TYPE_TEXT) }

    // Immersive Mode
    DisposableEffect(Unit) {
        val window = activity?.window
        window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.getInsetsController(window!!, window.decorView).hide(WindowInsetsCompat.Type.systemBars())
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
                    onTap = { isControlsVisible = !isControlsVisible },
                    onDoubleTap = { if (!isLocked) { if (it.x > size.width / 2) exoPlayer.seekForward() else exoPlayer.seekBack() } }
                )
            }
    ) {
        // 1. Player View
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    player = exoPlayer
                    useController = false
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                    layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                }
            },
            modifier = Modifier.fillMaxSize()
        )

        // 2. Custom Controls
        if (isControlsVisible) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(0.6f))) {
                // Top Bar
                Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.Default.ArrowBack, null, tint = Color.White)
                    }
                    Row {
                        IconButton(onClick = { 
                            trackSelectorType = C.TRACK_TYPE_AUDIO
                            showTrackSelector = true 
                        }) {
                            Icon(Icons.Default.Audiotrack, null, tint = Color.White)
                        }
                        IconButton(onClick = { 
                            trackSelectorType = C.TRACK_TYPE_TEXT
                            showTrackSelector = true 
                        }) {
                            Icon(Icons.Default.ClosedCaption, null, tint = Color.White)
                        }
                    }
                }

                // Center Controls
                if (!isLocked) {
                    Row(Modifier.align(Alignment.Center), horizontalArrangement = Arrangement.spacedBy(50.dp), verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { exoPlayer.seekBack() }) { Icon(Icons.Rounded.Replay10, null, tint = Color.White, modifier = Modifier.size(50.dp)) }
                        
                        IconButton(onClick = { if (exoPlayer.isPlaying) exoPlayer.pause() else exoPlayer.play() }, modifier = Modifier.size(80.dp).background(Color.White, RoundedCornerShape(50))) {
                            Icon(if (exoPlayer.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, null, tint = Color.Black, modifier = Modifier.size(50.dp))
                        }

                        IconButton(onClick = { exoPlayer.seekForward() }) { Icon(Icons.Rounded.Forward10, null, tint = Color.White, modifier = Modifier.size(50.dp)) }
                    }
                }

                // Bottom Bar
                IconButton(
                    onClick = { isLocked = !isLocked },
                    modifier = Modifier.align(Alignment.BottomStart).padding(16.dp)
                ) {
                    Icon(if (isLocked) Icons.Rounded.Lock else Icons.Rounded.LockOpen, null, tint = if(isLocked) Color.Red else Color.White)
                }
            }
        }

        // 3. Track Selector Dialog
        if (showTrackSelector) {
            TrackSelectionDialog(
                player = exoPlayer,
                trackType = trackSelectorType,
                onDismiss = { showTrackSelector = false }
            )
        }
    }
}

// --- TRACK SELECTION UI ---
@OptIn(UnstableApi::class)
@Composable
fun TrackSelectionDialog(player: ExoPlayer, trackType: Int, onDismiss: () -> Unit) {
    val tracks = remember { player.currentTracks }
    val trackList = remember {
        val list = mutableListOf<TrackInfo>()
        if (trackType == C.TRACK_TYPE_TEXT) list.add(TrackInfo("Off", null, -1))
        
        tracks.groups.forEachIndexed { _, group ->
            if (group.type == trackType) {
                for (i in 0 until group.length) {
                    val format = group.getTrackFormat(i)
                    val name = format.label ?: format.language ?: "Unknown Track ${i+1}"
                    val isSelected = group.isTrackSelected(i)
                    list.add(TrackInfo(name, group.mediaTrackGroup, i, isSelected))
                }
            }
        }
        list
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (trackType == C.TRACK_TYPE_AUDIO) "Select Audio" else "Select Subtitles", color = Color.White) },
        text = {
            LazyColumn {
                items(trackList) { track ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                if (track.group == null) {
                                    player.trackSelectionParameters = player.trackSelectionParameters
                                        .buildUpon()
                                        .setTrackTypeDisabled(trackType, true)
                                        .build()
                                } else {
                                    player.trackSelectionParameters = player.trackSelectionParameters
                                        .buildUpon()
                                        .setTrackTypeDisabled(trackType, false)
                                        .setOverrideForType(TrackSelectionOverride(track.group, track.index))
                                        .build()
                                }
                                onDismiss()
                            }
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(selected = track.isSelected, onClick = null, colors = RadioButtonDefaults.colors(selectedColor = Color.Red))
                        Spacer(Modifier.width(8.dp))
                        Text(track.name, color = Color.White)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close", color = Color.Red) } },
        containerColor = Color(0xFF1E1E1E)
    )
}

data class TrackInfo(
    val name: String,
    val group: androidx.media3.common.TrackGroup?,
    val index: Int,
    val isSelected: Boolean = false
)

fun activityContext(context: Context): Activity? {
    var c = context
    while (c is android.content.ContextWrapper) {
        if (c is Activity) return c
        c = c.baseContext
    }
    return null
}
