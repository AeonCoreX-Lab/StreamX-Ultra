package com.aeoncorex.streamx.ui.movie

import android.app.Activity
import android.content.Context
import android.content.pm.ActivityInfo
import android.media.AudioManager
import android.net.Uri
import android.provider.Settings
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.annotation.OptIn
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
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
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.navigation.NavController
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.net.URLDecoder
import java.util.concurrent.TimeUnit
import kotlin.math.abs

@OptIn(UnstableApi::class)
@Composable
fun MoviePlayerScreen(navController: NavController, encodedMagnetOrUrl: String) {
    val decodedInput = remember { URLDecoder.decode(encodedMagnetOrUrl, "UTF-8") }
    val context = LocalContext.current

    // States for Torrent Engine
    var streamUrl by remember { mutableStateOf<String?>(null) }
    var statusMessage by remember { mutableStateOf("Initializing Engine...") }
    var downloadSpeed by remember { mutableStateOf("0 KB/s") }
    var seedsCount by remember { mutableIntStateOf(0) }
    var peersCount by remember { mutableIntStateOf(0) }
    var progress by remember { mutableIntStateOf(0) }
    var isError by remember { mutableStateOf(false) }

    // Torrent Logic
    LaunchedEffect(decodedInput) {
        if (decodedInput.startsWith("magnet:?")) {
            TorrentEngine.start(context, decodedInput).collect { state ->
                when(state) {
                    is StreamState.Preparing -> statusMessage = state.message
                    is StreamState.Buffering -> {
                        statusMessage = "Buffering... ${state.progress}%"
                        progress = state.progress
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
            streamUrl = decodedInput
        }
    }

    DisposableEffect(Unit) { onDispose { TorrentEngine.stop() } }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (streamUrl != null) {
            UltimateExoPlayer(navController, streamUrl!!)
            
            // Torrent Stats Overlay (Minimalist)
            if (decodedInput.startsWith("magnet:?")) {
                Box(Modifier.align(Alignment.TopEnd).padding(top = 50.dp, end = 20.dp)) {
                    Column(horizontalAlignment = Alignment.End) {
                        Text("▼ $downloadSpeed", color = Color.Green, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        Text("S:$seedsCount | $progress%", color = Color.Yellow, fontSize = 10.sp)
                    }
                }
            }
        } else {
            // Loading Screen
            Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator(color = Color.Red)
                Spacer(Modifier.height(16.dp))
                Text(statusMessage, color = Color.White)
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
fun UltimateExoPlayer(navController: NavController, videoSource: String) {
    val context = LocalContext.current
    val activity = activityContext(context)
    val audioManager = remember { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }

    // --- Player State ---
    val exoPlayer = remember {
        ExoPlayer.Builder(context).build().apply {
            val mediaItem = if (videoSource.startsWith("http")) MediaItem.fromUri(Uri.parse(videoSource)) 
                           else MediaItem.fromUri(Uri.fromFile(File(videoSource)))
            setMediaItem(mediaItem)
            prepare()
            playWhenReady = true
        }
    }

    // --- UI States ---
    var isControlsVisible by remember { mutableStateOf(false) }
    var isLocked by remember { mutableStateOf(false) }
    var resizeMode by remember { mutableIntStateOf(AspectRatioFrameLayout.RESIZE_MODE_FIT) }
    
    // Seek Bar
    var currentPosition by remember { mutableLongStateOf(0L) }
    var totalDuration by remember { mutableLongStateOf(0L) }
    var bufferedPercentage by remember { mutableIntStateOf(0) }
    
    // Gestures UI
    var volumeLevel by remember { mutableFloatStateOf(audioManager.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat()) }
    var maxVolume by remember { mutableFloatStateOf(audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).toFloat()) }
    var brightnessLevel by remember { mutableFloatStateOf(activity?.window?.attributes?.screenBrightness ?: 0.5f) }
    var gestureIcon by remember { mutableStateOf<androidx.compose.ui.graphics.vector.ImageVector?>(null) }
    var gestureText by remember { mutableStateOf("") }
    var showGestureOverlay by remember { mutableStateOf(false) }

    // Tracks
    var showTrackSelector by remember { mutableStateOf(false) }
    var trackSelectorType by remember { mutableIntStateOf(C.TRACK_TYPE_TEXT) }

    // Double Tap Animation State
    var showForwardAnim by remember { mutableStateOf(false) }
    var showBackwardAnim by remember { mutableStateOf(false) }

    // --- Full Screen & Immersive ---
    DisposableEffect(Unit) {
        val window = activity?.window
        if (window != null) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            WindowCompat.getInsetsController(window, window.decorView).hide(WindowInsetsCompat.Type.systemBars())
            activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        }
        onDispose {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            WindowCompat.getInsetsController(window!!, window.decorView).show(WindowInsetsCompat.Type.systemBars())
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            exoPlayer.release()
        }
    }

    // --- Progress Updater Loop ---
    LaunchedEffect(exoPlayer) {
        while (true) {
            currentPosition = exoPlayer.currentPosition
            totalDuration = exoPlayer.duration.coerceAtLeast(0L)
            bufferedPercentage = exoPlayer.bufferedPercentage
            delay(1000)
        }
    }

    // --- Hide Controls Auto ---
    LaunchedEffect(isControlsVisible) {
        if (isControlsVisible && !isLocked) {
            delay(4000)
            isControlsVisible = false
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { isControlsVisible = !isControlsVisible },
                    onDoubleTap = { offset ->
                        if (!isLocked) {
                            val screenWidth = size.width
                            if (offset.x < screenWidth / 2) {
                                exoPlayer.seekBack()
                                showBackwardAnim = true
                            } else {
                                exoPlayer.seekForward()
                                showForwardAnim = true
                            }
                        }
                    }
                )
            }
            .pointerInput(Unit) {
                // --- GESTURE CONTROLS (Volume & Brightness) ---
                detectVerticalDragGestures(
                    onDragStart = { showGestureOverlay = true },
                    onDragEnd = { showGestureOverlay = false },
                    onDragCancel = { showGestureOverlay = false }
                ) { change, dragAmount ->
                    if (isLocked) return@detectVerticalDragGestures
                    
                    val screenWidth = size.width
                    val isLeft = change.position.x < screenWidth / 2
                    
                    if (isLeft) {
                        // BRIGHTNESS
                        val delta = -dragAmount / 500f // Sensitivity
                        brightnessLevel = (brightnessLevel + delta).coerceIn(0f, 1f)
                        val lp = activity?.window?.attributes
                        lp?.screenBrightness = brightnessLevel
                        activity?.window?.attributes = lp
                        gestureIcon = Icons.Filled.Brightness6
                        gestureText = "${(brightnessLevel * 100).toInt()}%"
                    } else {
                        // VOLUME
                        val delta = -dragAmount / 30f 
                        volumeLevel = (volumeLevel + delta).coerceIn(0f, maxVolume)
                        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, volumeLevel.toInt(), 0)
                        gestureIcon = if (volumeLevel == 0f) Icons.Filled.VolumeOff else Icons.Filled.VolumeUp
                        gestureText = "${((volumeLevel / maxVolume) * 100).toInt()}%"
                    }
                }
            }
    ) {
        // 1. Video Surface
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    player = exoPlayer
                    useController = false
                    setResizeMode(resizeMode)
                    layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                }
            },
            modifier = Modifier.fillMaxSize(),
            update = { view -> 
                view.setResizeMode(resizeMode)
            }
        )

        // 2. Double Tap Animation Overlay
        DoubleTapAnimation(showForwardAnim, showBackwardAnim) {
            showForwardAnim = false
            showBackwardAnim = false
        }

        // 3. Gesture Indicator (Volume/Brightness Center Overlay)
        if (showGestureOverlay && !isLocked) {
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .background(Color.Black.copy(0.6f), RoundedCornerShape(16.dp))
                    .padding(24.dp)
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(gestureIcon ?: Icons.Filled.Info, null, tint = Color.White, modifier = Modifier.size(48.dp))
                    Spacer(Modifier.height(8.dp))
                    Text(gestureText, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                }
            }
        }

        // 4. MAIN CONTROLS OVERLAY
        AnimatedVisibility(
            visible = isControlsVisible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.fillMaxSize()
        ) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(0.4f))) {
                
                // --- TOP BAR ---
                Row(
                    modifier = Modifier.fillMaxWidth().padding(16.dp).align(Alignment.TopCenter),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.Default.ArrowBack, null, tint = Color.White)
                    }
                    
                    Row {
                         // Resize Button
                        IconButton(onClick = { 
                            resizeMode = when(resizeMode) {
                                AspectRatioFrameLayout.RESIZE_MODE_FIT -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                                AspectRatioFrameLayout.RESIZE_MODE_ZOOM -> AspectRatioFrameLayout.RESIZE_MODE_FILL
                                else -> AspectRatioFrameLayout.RESIZE_MODE_FIT
                            }
                        }) {
                            val icon = when(resizeMode) {
                                AspectRatioFrameLayout.RESIZE_MODE_FIT -> Icons.Default.AspectRatio
                                AspectRatioFrameLayout.RESIZE_MODE_ZOOM -> Icons.Default.Fullscreen
                                else -> Icons.Default.FitScreen
                                // Note: Using basic icons for simplicity
                            }
                            Icon(icon, "Resize", tint = Color.White)
                        }

                        // Audio
                        IconButton(onClick = { trackSelectorType = C.TRACK_TYPE_AUDIO; showTrackSelector = true }) {
                            Icon(Icons.Default.Audiotrack, null, tint = Color.White)
                        }
                        // Subtitle
                        IconButton(onClick = { trackSelectorType = C.TRACK_TYPE_TEXT; showTrackSelector = true }) {
                            Icon(Icons.Default.Subtitles, null, tint = Color.White)
                        }
                    }
                }

                // --- CENTER PLAYBACK CONTROLS ---
                if (!isLocked) {
                    Row(
                        modifier = Modifier.align(Alignment.Center),
                        horizontalArrangement = Arrangement.spacedBy(50.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(onClick = { exoPlayer.seekBack() }) {
                            Icon(Icons.Rounded.Replay10, null, tint = Color.White, modifier = Modifier.size(48.dp))
                        }

                        IconButton(
                            onClick = { if (exoPlayer.isPlaying) exoPlayer.pause() else exoPlayer.play() },
                            modifier = Modifier.size(72.dp).background(Color.White, CircleShape)
                        ) {
                            Icon(
                                if (exoPlayer.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                                null,
                                tint = Color.Black,
                                modifier = Modifier.size(40.dp)
                            )
                        }

                        IconButton(onClick = { exoPlayer.seekForward() }) {
                            Icon(Icons.Rounded.Forward10, null, tint = Color.White, modifier = Modifier.size(48.dp))
                        }
                    }
                }

                // --- BOTTOM BAR (Seeker + Time) ---
                if (!isLocked) {
                    Column(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .padding(16.dp)
                    ) {
                        Row(
                            horizontalArrangement = Arrangement.SpaceBetween,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp)
                        ) {
                            Text(formatTime(currentPosition), color = Color.White, fontSize = 12.sp)
                            Text(formatTime(totalDuration), color = Color.White, fontSize = 12.sp)
                        }
                        
                        Slider(
                            value = currentPosition.toFloat(),
                            onValueChange = { exoPlayer.seekTo(it.toLong()) },
                            valueRange = 0f..totalDuration.toFloat().coerceAtLeast(1f),
                            colors = SliderDefaults.colors(
                                thumbColor = Color.Red,
                                activeTrackColor = Color.Red,
                                inactiveTrackColor = Color.White.copy(0.3f)
                            )
                        )
                    }
                }

                // --- LOCK BUTTON ---
                IconButton(
                    onClick = { isLocked = !isLocked },
                    modifier = Modifier.align(Alignment.CenterStart).padding(start = 24.dp)
                ) {
                    Icon(
                        if (isLocked) Icons.Rounded.Lock else Icons.Rounded.LockOpen,
                        null,
                        tint = if (isLocked) Color.Red else Color.White,
                        modifier = Modifier.size(32.dp)
                    )
                }
            }
        }

        // 5. Track Selector Dialog
        if (showTrackSelector) {
            TrackSelectionDialog(exoPlayer, trackSelectorType) { showTrackSelector = false }
        }
    }
}

// --- HELPER COMPONENTS ---

@Composable
fun DoubleTapAnimation(showForward: Boolean, showBackward: Boolean, onFinished: () -> Unit) {
    if (showForward || showBackward) {
        LaunchedEffect(Unit) {
            delay(600)
            onFinished()
        }
        Box(
            modifier = Modifier.fillMaxSize().background(Color.Black.copy(0.2f)),
            contentAlignment = if (showForward) Alignment.CenterEnd else Alignment.CenterStart
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(50.dp)
            ) {
                Icon(
                    if (showForward) Icons.Default.FastForward else Icons.Default.FastRewind,
                    null, tint = Color.White, modifier = Modifier.size(40.dp)
                )
                Text("10s", color = Color.White, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@OptIn(UnstableApi::class)
@Composable
fun TrackSelectionDialog(player: ExoPlayer, trackType: Int, onDismiss: () -> Unit) {
    val tracks = remember { player.currentTracks }
    val trackList = remember {
        val list = mutableListOf<TrackInfo>()
        list.add(TrackInfo("Disabled", null, -1))
        
        tracks.groups.forEachIndexed { _, group ->
            if (group.type == trackType) {
                for (i in 0 until group.length) {
                    val format = group.getTrackFormat(i)
                    val name = format.label ?: format.language ?: "Track ${i+1}"
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
                                        .buildUpon().setTrackTypeDisabled(trackType, true).build()
                                } else {
                                    player.trackSelectionParameters = player.trackSelectionParameters
                                        .buildUpon().setTrackTypeDisabled(trackType, false)
                                        .setOverrideForType(TrackSelectionOverride(track.group, track.index)).build()
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

fun formatTime(ms: Long): String {
    val totalSeconds = ms / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return String.format("%02d:%02d", minutes, seconds)
}

fun activityContext(context: Context): Activity? {
    var c = context
    while (c is android.content.ContextWrapper) {
        if (c is Activity) return c
        c = c.baseContext
    }
    return null
}
