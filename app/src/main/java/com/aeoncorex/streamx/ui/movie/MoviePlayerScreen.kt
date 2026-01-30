package com.aeoncorex.streamx.ui.movie

import android.app.Activity
import android.content.Context
import android.content.pm.ActivityInfo
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.navigation.NavController
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.net.URLDecoder
import kotlin.math.max

// --- Track Info Data Class ---
data class TrackInfo(
    val id: String,
    val name: String,
    val groupIndex: Int,
    val trackIndex: Int,
    val isSelected: Boolean,
    val type: Int
)

@OptIn(UnstableApi::class)
@Composable
fun MoviePlayerScreen(navController: NavController, encodedUrl: String) {
    val context = LocalContext.current
    val activity = context as? Activity
    val decodedUrl = remember { try { URLDecoder.decode(encodedUrl, "UTF-8") } catch (e: Exception) { encodedUrl } }
    
    val scope = rememberCoroutineScope()

    // --- State Management ---
    var videoPath by remember { mutableStateOf<String?>(null) }
    // FIX: Initially false so controls don't show during buffering
    var isControlsVisible by remember { mutableStateOf(false) } 
    var isVideoReady by remember { mutableStateOf(false) } // Track if video is ready to play

    var isPlaying by remember { mutableStateOf(true) }
    var currentTime by remember { mutableLongStateOf(0L) }
    var totalDuration by remember { mutableLongStateOf(0L) }
    var bufferedPercentage by remember { mutableIntStateOf(0) }
    var resizeMode by remember { mutableIntStateOf(AspectRatioFrameLayout.RESIZE_MODE_FIT) }
    var isLocked by remember { mutableStateOf(false) }
    
    // --- Track Management States ---
    var showTrackSettings by remember { mutableStateOf(false) }
    var audioTracks by remember { mutableStateOf<List<TrackInfo>>(emptyList()) }
    var subtitleTracks by remember { mutableStateOf<List<TrackInfo>>(emptyList()) }
    val trackSelector = remember { DefaultTrackSelector(context) }

    // Gesture States
    var volumeLevel by remember { mutableFloatStateOf(0.5f) }
    var brightnessLevel by remember { mutableFloatStateOf(0.5f) }
    var gestureIcon by remember { mutableStateOf<ImageVector?>(null) }
    var gestureText by remember { mutableStateOf("") }
    var showGestureOverlay by remember { mutableStateOf(false) }
    // FIX: Job to handle debouncing of overlay hide
    var overlayHideJob by remember { mutableStateOf<Job?>(null) }

    // Torrent States
    var statusMsg by remember { mutableStateOf("Initializing Core...") }
    var downloadSpeed by remember { mutableStateOf("0 KB/s") }
    var seeds by remember { mutableIntStateOf(0) }

    val audioManager = remember { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    val maxVolume = remember { audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC) }

    // Initialize Brightness & Volume
    LaunchedEffect(Unit) {
        val currentVol = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        volumeLevel = currentVol.toFloat() / maxVolume.toFloat()
        activity?.window?.attributes?.screenBrightness?.let {
            brightnessLevel = if (it < 0) 0.5f else it
        }
    }

    // --- SYSTEM UI & CACHE CLEANUP SETUP (FIXED) ---
    DisposableEffect(Unit) {
        // 1. Setup Landscape & Fullscreen
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        
        val window = activity?.window
        val controller = if (window != null) WindowCompat.getInsetsController(window, window.decorView) else null
        
        // Hide System Bars
        controller?.hide(WindowInsetsCompat.Type.systemBars())
        controller?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        
        onDispose {
            Log.d("StreamX", "Exiting Player: Cleaning UI and Engine")
            
            // --- FIX: SYSTEM UI GLITCH ---
            // 1. Show bars BEFORE changing orientation to avoid "floating bar" bug
            controller?.show(WindowInsetsCompat.Type.systemBars())
            controller?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_DEFAULT
            
            // 2. Clear flags
            activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)

            // 3. Reset Orientation
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            
            // 4. Force UI Visibility Reset (Deprecated but needed for some devices)
            @Suppress("DEPRECATION")
            activity?.window?.decorView?.systemUiVisibility = View.SYSTEM_UI_FLAG_VISIBLE

            // --- CRITICAL: Stop Engine ---
            TorrentEngine.stop() 
            TorrentEngine.clearCache(context)
        }
    }

    // Torrent Logic
    LaunchedEffect(decodedUrl) {
        if (decodedUrl.startsWith("magnet:?")) {
            TorrentEngine.start(context, decodedUrl).collect { state ->
                when (state) {
                    is StreamState.Preparing -> statusMsg = state.message
                    is StreamState.Buffering -> {
                        statusMsg = "Buffering ${state.progress}%"
                        downloadSpeed = "${state.speed / 1024} KB/s"
                        seeds = state.seeds
                        // FIX: Hide controls while buffering if video hasn't started
                        if (!isVideoReady) isControlsVisible = false
                    }
                    is StreamState.Ready -> {
                        if (videoPath != state.filePath) {
                            videoPath = state.filePath
                            // FIX: Only show controls when video file is actually ready
                            isVideoReady = true
                            isControlsVisible = true
                        }
                        statusMsg = ""
                    }
                    is StreamState.Error -> statusMsg = "Error: ${state.message}"
                }
            }
        } else {
            videoPath = decodedUrl
            statusMsg = ""
            isVideoReady = true
            isControlsVisible = true
        }
    }

    // Auto Hide Controls
    LaunchedEffect(isControlsVisible, isPlaying) {
        if (isControlsVisible && isPlaying && !showTrackSettings) {
            delay(4000)
            isControlsVisible = false
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        // 1. ExoPlayer Layer
        videoPath?.let { path ->
            val exoPlayer = remember(context) {
                ExoPlayer.Builder(context)
                    .setTrackSelector(trackSelector)
                    .build().apply {
                        val uri = if (path.startsWith("http")) Uri.parse(path) else Uri.fromFile(File(path))
                        setMediaItem(MediaItem.fromUri(uri))
                        prepare()
                        playWhenReady = true
                    }
            }

            DisposableEffect(exoPlayer) {
                val listener = object : Player.Listener {
                    override fun onIsPlayingChanged(playing: Boolean) { isPlaying = playing }
                    
                    override fun onPlaybackStateChanged(playbackState: Int) {
                        // FIX: Ensure controls hide if buffering happens mid-stream
                        if (playbackState == Player.STATE_BUFFERING) {
                            // Optionally hide controls or show loading
                        } else if (playbackState == Player.STATE_READY) {
                            if (!isVideoReady) {
                                isVideoReady = true
                                isControlsVisible = true
                            }
                        }
                    }

                    override fun onEvents(player: Player, events: Player.Events) {
                        currentTime = player.currentPosition
                        totalDuration = player.duration
                        bufferedPercentage = player.bufferedPercentage
                    }

                    override fun onTracksChanged(tracks: Tracks) {
                        // (Same Track Logic as before)
                        val newAudioTracks = mutableListOf<TrackInfo>()
                        val newSubtitleTracks = mutableListOf<TrackInfo>()
                        for (group in tracks.groups) {
                            for (i in 0 until group.length) {
                                val format = group.getTrackFormat(i)
                                val isSelected = group.isTrackSelected(i)
                                val trackType = group.type
                                val label = format.label ?: format.language ?: "Und"
                                val name = "$label".uppercase()
                                val info = TrackInfo(format.id ?: i.toString(), name, tracks.groups.indexOf(group), i, isSelected, trackType)
                                if (trackType == C.TRACK_TYPE_AUDIO) newAudioTracks.add(info)
                                else if (trackType == C.TRACK_TYPE_TEXT) newSubtitleTracks.add(info)
                            }
                        }
                        audioTracks = newAudioTracks
                        subtitleTracks = newSubtitleTracks
                    }
                }
                exoPlayer.addListener(listener)
                onDispose {
                    exoPlayer.removeListener(listener)
                    exoPlayer.release()
                }
            }

            LaunchedEffect(exoPlayer) {
                while (isActive) {
                    currentTime = exoPlayer.currentPosition
                    delay(1000)
                }
            }

            AndroidView(
                factory = { ctx ->
                    PlayerView(ctx).apply {
                        player = exoPlayer
                        useController = false
                        this.resizeMode = resizeMode
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                        )
                    }
                },
                modifier = Modifier.fillMaxSize()
            )
            
            // --- FIX: GESTURE LAYER (Separate Box to prevent UI Jank) ---
            // This box sits ON TOP of the player to intercept touches efficiently
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        // Detect Taps (Double & Single)
                        detectTapGestures(
                            onDoubleTap = { offset ->
                                if (isLocked) return@detectTapGestures
                                val width = size.width
                                val isForward = offset.x > width / 2
                                val seekTime = if (isForward) 10000L else -10000L
                                exoPlayer.seekTo(exoPlayer.currentPosition + seekTime)
                                
                                // Smooth Overlay Handling
                                overlayHideJob?.cancel()
                                showGestureOverlay = true
                                gestureIcon = if (isForward) Icons.Rounded.Forward10 else Icons.Rounded.Replay10
                                gestureText = if (isForward) "+10s" else "-10s"
                                
                                overlayHideJob = scope.launch {
                                    delay(600)
                                    showGestureOverlay = false
                                }
                            },
                            onTap = { 
                                // Only toggle if video is ready
                                if (isVideoReady) isControlsVisible = !isControlsVisible 
                            }
                        )
                    }
                    .pointerInput(Unit) {
                        // Detect Drag (Volume/Brightness)
                        detectVerticalDragGestures(
                            onDragStart = { 
                                if (!isLocked) {
                                    overlayHideJob?.cancel()
                                    showGestureOverlay = true 
                                }
                            },
                            onDragEnd = { 
                                overlayHideJob = scope.launch {
                                    delay(600)
                                    showGestureOverlay = false
                                }
                                gestureIcon = null
                            }
                        ) { change, dragAmount ->
                            if (isLocked) return@detectVerticalDragGestures
                            
                            val width = size.width
                            val xPos = change.position.x
                            val isLeft = xPos < width / 2
                            val delta = -dragAmount / size.height // Normalize drag

                            if (isLeft) {
                                // Brightness
                                val newBrightness = (brightnessLevel + delta * 2.5f).coerceIn(0f, 1f)
                                brightnessLevel = newBrightness
                                
                                // Optimize window call (don't call if value is same)
                                val lp = activity?.window?.attributes
                                if (lp != null && lp.screenBrightness != newBrightness) {
                                    lp.screenBrightness = newBrightness
                                    activity.window?.attributes = lp
                                }
                                
                                gestureIcon = Icons.Rounded.BrightnessMedium
                                gestureText = "${(brightnessLevel * 100).toInt()}%"
                            } else {
                                // Volume
                                val currentVol = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                                val maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                                val changeVol = (delta * maxVol * 1.5f).toInt() // Sensitivity
                                
                                val newVol = (currentVol + changeVol).coerceIn(0, maxVol)
                                if (newVol != currentVol) {
                                    audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, newVol, 0)
                                    volumeLevel = newVol.toFloat() / maxVol.toFloat()
                                }
                                
                                gestureIcon = if (volumeLevel == 0f) Icons.Rounded.VolumeOff else Icons.Rounded.VolumeUp
                                gestureText = "${(volumeLevel * 100).toInt()}%"
                            }
                        }
                    }
            )

            // --- SETTINGS SHEET ---
            if (showTrackSettings) {
                ModalBottomSheet(
                    onDismissRequest = { showTrackSettings = false },
                    containerColor = Color(0xFF1E1E1E),
                    contentColor = Color.White
                ) {
                    TrackSelectionSheet(
                        audioTracks = audioTracks,
                        subtitleTracks = subtitleTracks,
                        onAudioSelect = { track ->
                            val tracks = exoPlayer.currentTracks
                            val group = tracks.groups[track.groupIndex]
                            trackSelector.setParameters(
                                trackSelector.buildUponParameters()
                                    .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, track.trackIndex))
                            )
                            showTrackSettings = false
                        },
                        onSubtitleSelect = { track ->
                            if (track == null) {
                                trackSelector.setParameters(trackSelector.buildUponParameters().setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true))
                            } else {
                                val tracks = exoPlayer.currentTracks
                                val group = tracks.groups[track.groupIndex]
                                trackSelector.setParameters(
                                    trackSelector.buildUponParameters()
                                        .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                                        .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, track.trackIndex))
                                )
                            }
                            showTrackSettings = false
                        }
                    )
                }
            }
        }

        // 2. Custom Controls Overlay (Corrected Visibility)
        AnimatedVisibility(
            visible = isControlsVisible, // Controlled by ready state
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(0.4f))) {
                // Top Bar
                Row(
                    modifier = Modifier.fillMaxWidth().padding(16.dp).align(Alignment.TopStart),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.Default.ArrowBack, "Back", tint = Color.White)
                    }
                    
                    Text(
                        text = File(decodedUrl).nameWithoutExtension.take(20),
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1
                    )

                    Row {
                         // Download Stats
                        if (decodedUrl.startsWith("magnet")) {
                            Column(horizontalAlignment = Alignment.End, modifier = Modifier.padding(end = 12.dp)) {
                                Text("▼ $downloadSpeed", color = Color.Green, fontSize = 12.sp)
                                Text("S: $seeds", color = Color.Gray, fontSize = 10.sp)
                            }
                        }

                        IconButton(onClick = { showTrackSettings = true }) {
                            Icon(Icons.Rounded.Settings, "Settings", tint = Color.White)
                        }

                        IconButton(onClick = { 
                            resizeMode = when(resizeMode) {
                                AspectRatioFrameLayout.RESIZE_MODE_FIT -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                                AspectRatioFrameLayout.RESIZE_MODE_ZOOM -> AspectRatioFrameLayout.RESIZE_MODE_FILL
                                else -> AspectRatioFrameLayout.RESIZE_MODE_FIT
                            }
                        }) {
                            Icon(Icons.Rounded.AspectRatio, "Resize", tint = Color.White)
                        }
                    }
                }

                // Center Controls
                if (!isLocked) {
                    Row(
                        modifier = Modifier.align(Alignment.Center),
                        horizontalArrangement = Arrangement.spacedBy(40.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(onClick = { 
                            /* Seek handled by gesture mostly, but can add Logic here */ 
                        }, modifier = Modifier.size(50.dp)) {
                             Icon(Icons.Rounded.Replay10, "Rewind", tint = Color.White, modifier = Modifier.fillMaxSize())
                        }
                        IconButton(
                            onClick = { isPlaying = !isPlaying }, 
                            modifier = Modifier.size(70.dp).background(Color.White.copy(0.2f), CircleShape)
                        ) {
                            Icon(
                                if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                                "Play", tint = Color.White, modifier = Modifier.size(40.dp)
                            )
                        }
                        IconButton(onClick = { /* Forward */ }, modifier = Modifier.size(50.dp)) {
                             Icon(Icons.Rounded.Forward10, "Forward", tint = Color.White, modifier = Modifier.fillMaxSize())
                        }
                    }
                }

                // Lock Button
                IconButton(
                    onClick = { isLocked = !isLocked },
                    modifier = Modifier.align(Alignment.CenterEnd).padding(end = 30.dp)
                ) {
                    Icon(
                        if (isLocked) Icons.Rounded.Lock else Icons.Rounded.LockOpen,
                        "Lock", tint = if(isLocked) Color.Red else Color.White
                    )
                }

                // Bottom Seekbar
                if (!isLocked) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .align(Alignment.BottomCenter)
                            .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(0.9f))))
                            .padding(16.dp)
                    ) {
                        Text(
                            text = "${formatTime(currentTime)} / ${formatTime(totalDuration)}",
                            color = Color.White,
                            fontSize = 12.sp,
                            modifier = Modifier.padding(start = 8.dp)
                        )
                        Slider(
                            value = currentTime.toFloat(),
                            onValueChange = { /* Prevent direct seek stutter, handle in finish */ },
                            onValueChangeFinished = { /* Seek Logic */ },
                            enabled = false, // Use gesture for seek mostly, or implement proper slider seek
                            valueRange = 0f..max(1f, totalDuration.toFloat()),
                            colors = SliderDefaults.colors(thumbColor = Color.Cyan, activeTrackColor = Color.Cyan, inactiveTrackColor = Color.White.copy(0.3f)),
                            modifier = Modifier.height(20.dp)
                        )
                    }
                }
            }
        }

        // Gesture Overlay (Only shows when dragging or double tapping)
        if (showGestureOverlay) {
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .background(Color.Black.copy(0.7f), RoundedCornerShape(16.dp))
                    .padding(24.dp)
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    gestureIcon?.let { Icon(it, null, tint = Color.Cyan, modifier = Modifier.size(48.dp)) }
                    Spacer(Modifier.height(8.dp))
                    Text(gestureText, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 20.sp)
                }
            }
        }

        // Loading / Buffering Indicator (Shows when Controls are Hidden)
        if (!isVideoReady || videoPath == null) {
            Column(
                modifier = Modifier
                    .align(Alignment.Center)
                    .background(Color.Black.copy(0.5f), RoundedCornerShape(16.dp))
                    .padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                CircularProgressIndicator(color = Color.Cyan)
                Spacer(Modifier.height(16.dp))
                Text(statusMsg, color = Color.White, fontWeight = FontWeight.Bold)
            }
        }
    }
}

// --- SUB-COMPONENTS & HELPERS --- (No Changes here)

@Composable
fun TrackSelectionSheet(
    audioTracks: List<TrackInfo>,
    subtitleTracks: List<TrackInfo>,
    onAudioSelect: (TrackInfo) -> Unit,
    onSubtitleSelect: (TrackInfo?) -> Unit
) {
    var selectedTab by remember { mutableIntStateOf(0) } // 0 = Audio, 1 = Subtitles

    Column(modifier = Modifier.padding(16.dp).fillMaxWidth().heightIn(max = 400.dp)) {
        Text("Audio & Subtitles", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color.White)
        Spacer(Modifier.height(16.dp))
        
        // Tabs
        Row(modifier = Modifier.fillMaxWidth().background(Color.DarkGray, RoundedCornerShape(8.dp)).padding(4.dp)) {
            TabButton("Audio (${audioTracks.size})", selectedTab == 0, Modifier.weight(1f)) { selectedTab = 0 }
            TabButton("Subtitles (${subtitleTracks.size})", selectedTab == 1, Modifier.weight(1f)) { selectedTab = 1 }
        }
        
        Spacer(Modifier.height(16.dp))

        // List
        LazyColumn {
            if (selectedTab == 0) {
                items(audioTracks) { track ->
                    TrackItem(track.name, track.isSelected) { onAudioSelect(track) }
                }
            } else {
                item {
                    TrackItem("Off", subtitleTracks.none { it.isSelected }) { onSubtitleSelect(null) }
                }
                items(subtitleTracks) { track ->
                    TrackItem(track.name, track.isSelected) { onSubtitleSelect(track) }
                }
            }
        }
    }
}

@Composable
fun TabButton(text: String, isSelected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Box(
        modifier = modifier
            .background(if (isSelected) Color.Cyan else Color.Transparent, RoundedCornerShape(6.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(text, color = if (isSelected) Color.Black else Color.White, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun TrackItem(name: String, isSelected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp, horizontal = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(name, color = if (isSelected) Color.Cyan else Color.White, fontSize = 16.sp)
        if (isSelected) {
            Icon(Icons.Rounded.Check, "Selected", tint = Color.Cyan)
        }
    }
}

fun formatTime(ms: Long): String {
    val totalSeconds = ms / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) String.format("%02d:%02d:%02d", hours, minutes, seconds)
    else String.format("%02d:%02d", minutes, seconds)
}
