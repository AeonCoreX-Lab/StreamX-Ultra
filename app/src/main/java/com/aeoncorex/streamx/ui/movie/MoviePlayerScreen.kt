package com.aeoncorex.streamx.ui.movie

import android.app.Activity
import android.content.Context
import android.content.pm.ActivityInfo
import android.media.AudioManager
import android.net.Uri
import android.util.Log
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
    val type: Int // C.TRACK_TYPE_AUDIO or C.TRACK_TYPE_TEXT
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
    var isPlaying by remember { mutableStateOf(true) }
    var currentTime by remember { mutableLongStateOf(0L) }
    var totalDuration by remember { mutableLongStateOf(0L) }
    var bufferedPercentage by remember { mutableIntStateOf(0) }
    var isControlsVisible by remember { mutableStateOf(true) }
    var resizeMode by remember { mutableIntStateOf(AspectRatioFrameLayout.RESIZE_MODE_FIT) }
    var isLocked by remember { mutableStateOf(false) }
    
    // --- Track Management States ---
    var showTrackSettings by remember { mutableStateOf(false) }
    var audioTracks by remember { mutableStateOf<List<TrackInfo>>(emptyList()) }
    var subtitleTracks by remember { mutableStateOf<List<TrackInfo>>(emptyList()) }
    // Track Selector Reference
    val trackSelector = remember { DefaultTrackSelector(context) }

    // Gesture States
    var volumeLevel by remember { mutableFloatStateOf(0.5f) }
    var brightnessLevel by remember { mutableFloatStateOf(0.5f) }
    var gestureIcon by remember { mutableStateOf<ImageVector?>(null) }
    var gestureText by remember { mutableStateOf("") }
    var showGestureOverlay by remember { mutableStateOf(false) }

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

    // --- SYSTEM UI & CACHE CLEANUP SETUP ---
    DisposableEffect(Unit) {
        // 1. Setup Landscape & Fullscreen
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val window = activity?.window
        val controller = if (window != null) WindowCompat.getInsetsController(window, window.decorView) else null
        controller?.hide(WindowInsetsCompat.Type.systemBars())
        controller?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        
        // 2. Cleanup when leaving screen
        onDispose {
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            controller?.show(WindowInsetsCompat.Type.systemBars())
            
            Log.d("StreamX", "Exiting Player: Stopping Engine & Cleaning Cache")
            
            // --- CRITICAL: Stop Engine First, Then Delete Files ---
            TorrentEngine.stop() 
            TorrentEngine.clearCache(context) // <--- এই লাইনটি ক্যাশ ডিলিট করবে
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
                    }
                    is StreamState.Ready -> {
                        if (videoPath != state.filePath) videoPath = state.filePath
                        statusMsg = ""
                    }
                    is StreamState.Error -> statusMsg = "Error: ${state.message}"
                }
            }
        } else {
            videoPath = decodedUrl
            statusMsg = ""
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
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { isControlsVisible = !isControlsVisible },
                    onDoubleTap = { /* Handled deeper */ }
                )
            }
    ) {
        // 1. ExoPlayer Layer
        videoPath?.let { path ->
            val exoPlayer = remember(context) {
                ExoPlayer.Builder(context)
                    .setTrackSelector(trackSelector) // Inject Track Selector
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
                    override fun onEvents(player: Player, events: Player.Events) {
                        currentTime = player.currentPosition
                        totalDuration = player.duration
                        bufferedPercentage = player.bufferedPercentage
                    }
                    // --- TRACK DETECTION LOGIC ---
                    override fun onTracksChanged(tracks: Tracks) {
                        val newAudioTracks = mutableListOf<TrackInfo>()
                        val newSubtitleTracks = mutableListOf<TrackInfo>()

                        for (group in tracks.groups) {
                            for (i in 0 until group.length) {
                                val format = group.getTrackFormat(i)
                                val isSelected = group.isTrackSelected(i)
                                val trackType = group.type
                                
                                val language = format.language ?: "Und"
                                val label = format.label ?: language
                                val name = "$label (${format.id ?: "Unknown"})".uppercase()

                                val info = TrackInfo(
                                    id = format.id ?: i.toString(),
                                    name = name,
                                    groupIndex = tracks.groups.indexOf(group),
                                    trackIndex = i,
                                    isSelected = isSelected,
                                    type = trackType
                                )

                                if (trackType == C.TRACK_TYPE_AUDIO) {
                                    newAudioTracks.add(info)
                                } else if (trackType == C.TRACK_TYPE_TEXT) {
                                    newSubtitleTracks.add(info)
                                }
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
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        detectVerticalDragGestures(
                            onDragStart = { showGestureOverlay = true },
                            onDragEnd = { 
                                showGestureOverlay = false 
                                gestureIcon = null
                            }
                        ) { change, dragAmount ->
                            if (isLocked) return@detectVerticalDragGestures
                            val width = size.width
                            val xPos = change.position.x
                            val isLeft = xPos < width / 2
                            
                            if (isLeft) {
                                val delta = -dragAmount / 500f
                                brightnessLevel = (brightnessLevel + delta).coerceIn(0f, 1f)
                                activity?.window?.attributes = activity?.window?.attributes?.apply {
                                    screenBrightness = brightnessLevel
                                }
                                gestureIcon = Icons.Rounded.BrightnessMedium
                                gestureText = "${(brightnessLevel * 100).toInt()}%"
                            } else {
                                val delta = -dragAmount / 50f
                                val currentVol = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                                val newVol = (currentVol + delta.toInt()).coerceIn(0, maxVolume)
                                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, newVol, 0)
                                volumeLevel = newVol.toFloat() / maxVolume
                                gestureIcon = if (volumeLevel == 0f) Icons.Rounded.VolumeOff else Icons.Rounded.VolumeUp
                                gestureText = "${(volumeLevel * 100).toInt()}%"
                            }
                        }
                    }
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onDoubleTap = { offset ->
                                if (isLocked) return@detectTapGestures
                                val width = size.width
                                val isForward = offset.x > width / 2
                                val seekTime = if (isForward) 10000L else -10000L
                                exoPlayer.seekTo(exoPlayer.currentPosition + seekTime)
                                showGestureOverlay = true
                                gestureIcon = if (isForward) Icons.Rounded.Forward10 else Icons.Rounded.Replay10
                                gestureText = if (isForward) "+10s" else "-10s"
                                scope.launch { delay(600); showGestureOverlay = false }
                            },
                            onTap = { isControlsVisible = !isControlsVisible }
                        )
                    }
            )

            // --- SETTINGS SHEET (Audio & Subtitle) ---
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
                                    .setOverrideForType(
                                        TrackSelectionOverride(group.mediaTrackGroup, track.trackIndex)
                                    )
                            )
                            showTrackSettings = false
                        },
                        onSubtitleSelect = { track ->
                            if (track == null) {
                                // Turn Off Subtitles
                                trackSelector.setParameters(
                                    trackSelector.buildUponParameters()
                                        .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                                )
                            } else {
                                // Enable Specific Subtitle
                                val tracks = exoPlayer.currentTracks
                                val group = tracks.groups[track.groupIndex]
                                trackSelector.setParameters(
                                    trackSelector.buildUponParameters()
                                        .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                                        .setOverrideForType(
                                            TrackSelectionOverride(group.mediaTrackGroup, track.trackIndex)
                                        )
                                )
                            }
                            showTrackSettings = false
                        }
                    )
                }
            }
        }

        // 2. Custom Controls Overlay
        AnimatedVisibility(
            visible = isControlsVisible,
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

                        // Settings/Track Button
                        IconButton(onClick = { showTrackSettings = true }) {
                            Icon(Icons.Rounded.Settings, "Settings", tint = Color.White)
                        }

                        // Resize Button
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
                        IconButton(onClick = { videoPath?.let { /* Rewind handled by gesture usually */ } }, modifier = Modifier.size(50.dp)) {
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
                            onValueChange = { /* Seek handled below to prevent UI jank */ },
                            onValueChangeFinished = { /* Logic to seek */ },
                            enabled = false, 
                            valueRange = 0f..max(1f, totalDuration.toFloat()),
                            colors = SliderDefaults.colors(thumbColor = Color.Cyan, activeTrackColor = Color.Cyan, inactiveTrackColor = Color.White.copy(0.3f)),
                            modifier = Modifier.height(20.dp)
                        )
                    }
                }
            }
        }

        // Gesture Overlay
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

        // Loading
        if (videoPath == null) {
            Column(modifier = Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator(color = Color.Cyan)
                Spacer(Modifier.height(16.dp))
                Text(statusMsg, color = Color.White)
            }
        }
    }
}

// --- SUB-COMPONENTS ---

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

// Formatter
fun formatTime(ms: Long): String {
    val totalSeconds = ms / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) String.format("%02d:%02d:%02d", hours, minutes, seconds)
    else String.format("%02d:%02d", minutes, seconds)
}
