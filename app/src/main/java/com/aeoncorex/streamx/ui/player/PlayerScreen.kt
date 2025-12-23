package com.aeoncorex.streamx.ui.player

import android.app.Activity
import android.content.Context
import android.content.pm.ActivityInfo
import android.media.AudioManager
import android.os.Build
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import androidx.media3.common.*
import androidx.media3.common.C.TRACK_TYPE_AUDIO
import androidx.media3.common.C.TRACK_TYPE_TEXT
import androidx.media3.common.C.TRACK_TYPE_VIDEO
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.TransferListener
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.delay
import java.net.URLDecoder
import kotlin.math.abs

// Enums for State Management
enum class SeekDirection { NONE, FORWARD, BACKWARD }
enum class IndicatorType { NONE, BRIGHTNESS, VOLUME }

@OptIn(UnstableApi::class)
@Composable
fun PlayerScreen(encodedUrl: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val activity = context as? Activity
    
    var isLoading by remember { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var showControls by remember { mutableStateOf(true) }
    var isLocked by remember { mutableStateOf(false) }
    var isPlaying by remember { mutableStateOf(true) }
    
    var totalDataUsed by remember { mutableStateOf(0L) }
    var currentSpeed by remember { mutableStateOf("0 KB/s") }
    var showSeekUI by remember { mutableStateOf(SeekDirection.NONE) }
    var isLandscape by remember { mutableStateOf(false) }
    var videoResolution by remember { mutableStateOf<VideoSize?>(null) }
    var showSettingsDialog by remember { mutableStateOf(false) }

    var showIndicator by remember { mutableStateOf(IndicatorType.NONE) }
    var indicatorProgress by remember { mutableStateOf(0f) }

    val streamUrl = remember { URLDecoder.decode(encodedUrl, "UTF-8") }

    val exoPlayer = remember {
        val trackSelector = DefaultTrackSelector(context).apply {
            setParameters(buildUponParameters().setAllowMultipleAdaptiveSelections(true))
        }
        val dataSourceListener = object : TransferListener {
            override fun onTransferInitializing(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) {}
            override fun onTransferStart(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) {}
            override fun onBytesTransferred(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean, bytesTransferred: Int) { if (isNetwork) totalDataUsed += bytesTransferred }
            override fun onTransferEnd(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) {}
        }
        val httpDataSourceFactory = DefaultHttpDataSource.Factory().setUserAgent("HDStreamz/3.1.0 (Linux; Android 11; SM-G973F)").setDefaultRequestProperties(mapOf("Referer" to "https://www.google.com/")).setTransferListener(dataSourceListener)
        val mediaSourceFactory = DefaultMediaSourceFactory(context).setDataSourceFactory(httpDataSourceFactory)

        ExoPlayer.Builder(context).setTrackSelector(trackSelector).setMediaSourceFactory(mediaSourceFactory).build().apply {
            setMediaItem(MediaItem.fromUri(streamUrl)); playWhenReady = true; prepare()
            addListener(object : Player.Listener {
                override fun onIsPlayingChanged(playing: Boolean) { isPlaying = playing }
                override fun onPlaybackStateChanged(state: Int) { isLoading = state == Player.STATE_BUFFERING }
                override fun onVideoSizeChanged(size: VideoSize) { videoResolution = size }
                override fun onPlayerError(error: PlaybackException) { isLoading = false; errorMessage = "Sorry, an error occurred. Please try another link." }
            })
        }
    }
    
    DisposableEffect(Unit) { onDispose { exoPlayer.release() } }
    BackHandler { if (isLandscape) { activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT; isLandscape = false } else { exoPlayer.release(); onBack() } }
    KeepScreenOn(); HideSystemUi(activity)
    
    val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)

    LaunchedEffect(Unit) { var lastDataUsed = 0L; while (true) { delay(1000); val currentData = totalDataUsed; val speedBytesPerSec = currentData - lastDataUsed; currentSpeed = formatSpeed(speedBytesPerSec); lastDataUsed = currentData } }
    LaunchedEffect(showControls, isLocked) { if (showControls && !isLocked) { delay(5000); showControls = false } }
    LaunchedEffect(showIndicator) { if (showIndicator != IndicatorType.NONE) { delay(1200); showIndicator = IndicatorType.NONE } }
    LaunchedEffect(showSeekUI) { if (showSeekUI != SeekDirection.NONE) { delay(800); showSeekUI = SeekDirection.NONE } }
    LaunchedEffect(errorMessage) { if (errorMessage != null) { delay(3000); onBack() } }

    Box(
        modifier = Modifier.fillMaxSize().background(Color.Black)
            .pointerInput(Unit) {
                if (isLocked) return@pointerInput
                detectTapGestures(
                    onTap = { showControls = !showControls },
                    onDoubleTap = { offset ->
                        showControls = true
                        if (offset.x > size.width / 2) {
                            exoPlayer.seekForward()
                            showSeekUI = SeekDirection.FORWARD
                        } else {
                            exoPlayer.seekBack()
                            showSeekUI = SeekDirection.BACKWARD
                        }
                    }
                )
            }
            .pointerInput(Unit) {
                if (isLocked) return@pointerInput
                detectVerticalDragGestures(
                    onDragStart = { showControls = true },
                    onVerticalDrag = { change, dragAmount ->
                        val isLeft = change.position.x < size.width / 2
                        if (isLeft) {
                            showIndicator = IndicatorType.BRIGHTNESS
                            activity?.window?.let { window ->
                                val currentBrightness = window.attributes.screenBrightness.takeIf { it > 0 } ?: 0.5f
                                val newBrightness = (currentBrightness - dragAmount / size.height).coerceIn(0.05f, 1.0f)
                                window.attributes = window.attributes.apply { screenBrightness = newBrightness }
                                indicatorProgress = newBrightness
                            }
                        } else {
                            showIndicator = IndicatorType.VOLUME
                            val currentVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                            val delta = -(dragAmount * maxVolume / size.height.toFloat())
                            val newVolume = (currentVolume + delta).toInt().coerceIn(0, maxVolume)
                            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, newVolume, 0)
                            indicatorProgress = newVolume.toFloat() / maxVolume.toFloat()
                        }
                    }
                )
            }
    ) {
        AndroidView(factory = { PlayerView(it).apply { player = exoPlayer; useController = false; resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT } }, modifier = Modifier.fillMaxSize())
        
        SeekAnimationOverlay(direction = showSeekUI)
        VerticalIndicator(type = showIndicator, progress = indicatorProgress)
        
        if (isLoading) CircularProgressIndicator(modifier = Modifier.align(Alignment.Center), color = Color.Cyan)
        errorMessage?.let { msg -> Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(0.8f)), contentAlignment = Alignment.Center) { Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(24.dp)) { Text(text = msg, color = Color.White, textAlign = TextAlign.Center); Spacer(Modifier.height(8.dp)); LinearProgressIndicator(color = Color.White) } } }

        AnimatedVisibility(visible = showControls && errorMessage == null && !isLocked, enter = fadeIn(), exit = fadeOut()) {
            PlayerControls(isPlaying = isPlaying, isLandscape = isLandscape, videoResolution = videoResolution, onPlayPauseToggle = { if (exoPlayer.isPlaying) exoPlayer.pause() else exoPlayer.play() }, onBack = { if (isLandscape) { activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT; isLandscape = false } else { exoPlayer.release(); onBack() } }, onLockToggle = { isLocked = !isLocked }, onRotateClick = { isLandscape = !isLandscape; activity?.requestedOrientation = if (isLandscape) ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }, onSettingsClick = { showSettingsDialog = true })
            DataUsageOverlay(totalData = formatBytes(totalDataUsed), speed = currentSpeed, modifier = Modifier.align(Alignment.TopEnd))
        }
        
        if (isLocked) { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { IconButton(onClick = { isLocked = false }, modifier = Modifier.size(70.dp).background(Color.Black.copy(0.5f), CircleShape)) { Icon(Icons.Default.LockOpen, "Unlock", tint = Color.White, modifier = Modifier.size(35.dp)) } } }
        if (showSettingsDialog) { SettingsBottomSheet(exoPlayer = exoPlayer, onDismiss = { showSettingsDialog = false }) }
    }
}

@Composable
fun PlayerControls(isPlaying: Boolean, isLandscape: Boolean, videoResolution: VideoSize?, onPlayPauseToggle: () -> Unit, onBack: () -> Unit, onLockToggle: () -> Unit, onRotateClick: () -> Unit, onSettingsClick: () -> Unit) {
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.5f))) {
        Row(Modifier.fillMaxWidth().align(Alignment.TopCenter).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Color.White) }
            videoResolution?.let { Text("${it.height}p", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold) }
            IconButton(onClick = onSettingsClick) { Icon(Icons.Default.Tune, "Settings", tint = Color.White) }
        }
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { IconButton(onClick = onPlayPauseToggle, modifier = Modifier.size(70.dp)) { Icon(if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, "Play/Pause", tint = Color.White, modifier = Modifier.size(50.dp)) } }
        Row(Modifier.fillMaxWidth().align(Alignment.BottomCenter).padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.End) {
            IconButton(onClick = onLockToggle) { Icon(Icons.Default.Lock, "Lock", tint = Color.White) }; IconButton(onClick = onRotateClick) { Icon(if (isLandscape) Icons.Default.FullscreenExit else Icons.Default.Fullscreen, "Rotate", tint = Color.White) }
        }
    }
}

@Composable
fun BoxScope.SeekAnimationOverlay(direction: SeekDirection) {
    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
        AnimatedVisibility(visible = direction == SeekDirection.BACKWARD, enter = fadeIn() + slideInHorizontally(), exit = fadeOut() + slideOutHorizontally(), modifier = Modifier.weight(1f)) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Icon(Icons.Default.FastRewind, "Rewind", tint = Color.White.copy(alpha = 0.8f), modifier = Modifier.size(60.dp)) }
        }
        Spacer(modifier = Modifier.weight(1f))
        AnimatedVisibility(visible = direction == SeekDirection.FORWARD, enter = fadeIn() + slideInHorizontally { it }, exit = fadeOut() + slideOutHorizontally { it }, modifier = Modifier.weight(1f)) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Icon(Icons.Default.FastForward, "Forward", tint = Color.White.copy(alpha = 0.8f), modifier = Modifier.size(60.dp)) }
        }
    }
}

@Composable
fun BoxScope.VerticalIndicator(type: IndicatorType, progress: Float) {
    AnimatedVisibility(
        visible = type != IndicatorType.NONE,
        modifier = Modifier.align(if (type == IndicatorType.BRIGHTNESS) Alignment.CenterStart else Alignment.CenterEnd).padding(horizontal = 24.dp, vertical = 60.dp).width(40.dp).clip(RoundedCornerShape(20.dp)).background(Color.Black.copy(alpha = 0.5f)),
        enter = fadeIn(), exit = fadeOut()
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center, modifier = Modifier.padding(vertical = 16.dp)) {
            Icon(if (type == IndicatorType.BRIGHTNESS) Icons.Default.BrightnessMedium else Icons.Default.VolumeUp, null, tint = Color.White)
            Spacer(modifier = Modifier.height(8.dp))
            LinearProgressIndicator(progress = { progress }, modifier = Modifier.height(120.dp).width(4.dp).clip(RoundedCornerShape(2.dp)), color = Color.White, trackColor = Color.White.copy(alpha = 0.3f))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsBottomSheet(exoPlayer: ExoPlayer, onDismiss: () -> Unit) {
    val sheetState = rememberModalBottomSheetState()
    var tracks by remember { mutableStateOf(exoPlayer.currentTracks) }
    var playbackSpeed by remember { mutableStateOf(exoPlayer.playbackParameters.speed) }
    
    DisposableEffect(exoPlayer) {
        val listener = object : Player.Listener { override fun onTracksChanged(t: Tracks) { tracks = t } }
        exoPlayer.addListener(listener)
        onDispose { exoPlayer.removeListener(listener) }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        LazyColumn(contentPadding = PaddingValues(bottom = 32.dp)) {
            item { Text("Playback Settings", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(16.dp)) }

            item { SettingsGroup(title = "Speed") { val speeds = listOf(0.5f, 1.0f, 1.5f, 2.0f); Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) { speeds.forEach { speed -> FilterChip(selected = playbackSpeed == speed, onClick = { exoPlayer.setPlaybackSpeed(speed); playbackSpeed = speed }, label = { Text("${speed}x") }) } } } }
            
            val videoTracks = tracks.groups.firstOrNull { it.type == TRACK_TYPE_VIDEO && it.isSupported }
            videoTracks?.let { item { SettingsGroup(title = "Quality") { TrackSelectionMenu(it, exoPlayer) { fmt -> "${fmt.height}p" } } } }
            
            val audioTracks = tracks.groups.firstOrNull { it.type == TRACK_TYPE_AUDIO && it.isSupported }
            audioTracks?.let { item { SettingsGroup(title = "Audio") { TrackSelectionMenu(it, exoPlayer) { fmt -> fmt.label ?: fmt.language ?: "Track" } } } }
            
            val textTracks = tracks.groups.firstOrNull { it.type == TRACK_TYPE_TEXT && it.isSupported }
            textTracks?.let { item { SettingsGroup(title = "Subtitles") { TrackSelectionMenu(it, exoPlayer, showOffOption = true) { fmt -> fmt.label ?: fmt.language ?: "Track" } } } }
        }
    }
}

@Composable
fun SettingsGroup(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold); Spacer(modifier = Modifier.height(8.dp)); content()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrackSelectionMenu(trackGroup: Tracks.Group, player: Player, showOffOption: Boolean = false, labelBuilder: (Format) -> String) {
    var expanded by remember { mutableStateOf(false) }
    val selectedTrack = trackGroup.tracks.find { it.isSelected }
    val currentLabel = if (selectedTrack != null) labelBuilder(selectedTrack.format) else "Off"

    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = !expanded }) {
        OutlinedTextField(
            value = currentLabel,
            onValueChange = {},
            readOnly = true,
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.menuAnchor().fillMaxWidth()
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            if (showOffOption && selectedTrack != null) {
                DropdownMenuItem(text = { Text("Off") }, onClick = {
                    player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                        .setTrackSelectionOverrides(player.trackSelectionParameters.overrides.buildUpon().remove(trackGroup.mediaTrackGroup).build())
                        .build()
                    expanded = false
                })
            }
            trackGroup.tracks.forEachIndexed { index, track ->
                if (track.isSupported) {
                    val format = track.format
                    DropdownMenuItem(
                        text = { Text(labelBuilder(format)) },
                        onClick = {
                            player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                                .setTrackSelectionOverrides(player.trackSelectionParameters.overrides.buildUpon().add(TrackSelectionOverride(trackGroup.mediaTrackGroup, index)).build())
                                .build()
                            expanded = false
                        }
                    )
                }
            }
        }
    }
}

@Composable
fun DataUsageOverlay(totalData: String, speed: String, modifier: Modifier = Modifier) {
    Row(modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text = speed, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold); Spacer(modifier = Modifier.width(8.dp)); Text(text = totalData, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
    }
}

private fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val kb = bytes / 1024
    if (kb < 1024) return "$kb KB"
    val mb = kb / 1024
    return String.format("%.1f MB", kb / 1024.0)
}

private fun formatSpeed(bytesPerSecond: Long): String {
    if (bytesPerSecond < 0) return "0 B/s"
    if (bytesPerSecond < 1024) return "$bytesPerSecond B/s"
    val kbPerSecond = bytesPerSecond / 1024.0
    if (kbPerSecond < 1024) return "${kbPerSecond.toInt()} KB/s"
    val mbPerSecond = kbPerSecond / 1024.0
    return String.format("%.1f MB/s", mbPerSecond)
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
    val window = activity?.window ?: return
    DisposableEffect(Unit) {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        onDispose {
            WindowCompat.setDecorFitsSystemWindows(window, true)
            controller.show(WindowInsetsCompat.Type.systemBars())
            activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }
}