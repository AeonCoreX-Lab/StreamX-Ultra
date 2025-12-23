package com.aeoncorex.streamx.ui.player

import android.app.Activity
import android.content.Context
import android.content.pm.ActivityInfo
import android.media.AudioManager
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.foundation.background
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
import java.util.concurrent.TimeUnit

@OptIn(UnstableApi::class)
@Composable
fun PlayerScreen(encodedUrl: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val activity = context as? Activity
    
    // States
    var isLoading by remember { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var showControls by remember { mutableStateOf(true) }
    var isLocked by remember { mutableStateOf(false) }
    var isPlaying by remember { mutableStateOf(true) }
    var currentResizeMode by remember { mutableIntStateOf(AspectRatioFrameLayout.RESIZE_MODE_FIT) }
    
    // Progress States
    var currentPosition by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(0L) }
    var bufferedPosition by remember { mutableLongStateOf(0L) }
    
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
            override fun onBytesTransferred(s: DataSource, d: DataSpec, isNet: Boolean, bytes: Int) {
                if (isNet) totalDataUsed += bytes
            }
            override fun onTransferInitializing(s: DataSource, d: DataSpec, isNet: Boolean) {}
            override fun onTransferStart(s: DataSource, d: DataSpec, isNet: Boolean) {}
            override fun onTransferEnd(s: DataSource, d: DataSpec, isNet: Boolean) {}
        }
        val httpDataSourceFactory = DefaultHttpDataSource.Factory()
            .setUserAgent("StreamX-Ultra/1.0")
            .setTransferListener(dataSourceListener)

        ExoPlayer.Builder(context).setTrackSelector(trackSelector)
            .setMediaSourceFactory(DefaultMediaSourceFactory(context).setDataSourceFactory(httpDataSourceFactory))
            .build().apply {
                setMediaItem(MediaItem.fromUri(streamUrl))
                prepare()
                playWhenReady = true
                addListener(object : Player.Listener {
                    override fun onIsPlayingChanged(p: Boolean) { isPlaying = p }
                    override fun onPlaybackStateChanged(state: Int) {
                        isLoading = state == Player.STATE_BUFFERING
                        duration = this@apply.duration.coerceAtLeast(0L)
                    }
                    override fun onVideoSizeChanged(size: VideoSize) { videoResolution = size }
                    override fun onPlayerError(e: PlaybackException) { errorMessage = "Stream Error: ${e.message}" }
                })
            }
    }
    
    // Updates Progress every 500ms
    LaunchedEffect(isPlaying) {
        while (isPlaying) {
            currentPosition = exoPlayer.currentPosition
            bufferedPosition = exoPlayer.bufferedPosition
            delay(500)
        }
    }

    DisposableEffect(Unit) { onDispose { exoPlayer.release() } }
    BackHandler { if (isLandscape) { activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT; isLandscape = false } else { exoPlayer.release(); onBack() } }
    KeepScreenOn(); HideSystemUi(activity)
    
    val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)
        .pointerInput(Unit) {
            if (isLocked) return@pointerInput
            detectTapGestures(onTap = { showControls = !showControls },
                onDoubleTap = { offset ->
                    showControls = true
                    if (offset.x > size.width / 2) { exoPlayer.seekForward(); showSeekUI = SeekDirection.FORWARD }
                    else { exoPlayer.seekBack(); showSeekUI = SeekDirection.BACKWARD }
                })
        }
        .pointerInput(Unit) {
            if (isLocked) return@pointerInput
            detectVerticalDragGestures(onDragStart = { showControls = true },
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
                        val currentVol = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                        val delta = -(dragAmount * maxVolume / size.height.toFloat())
                        val newVol = (currentVol + delta).toInt().coerceIn(0, maxVolume)
                        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, newVol, 0)
                        indicatorProgress = newVol.toFloat() / maxVolume.toFloat()
                    }
                })
        }
    ) {
        AndroidView(factory = { PlayerView(it).apply { 
            player = exoPlayer; useController = false
            this.resizeMode = currentResizeMode 
        } }, 
        update = { it.resizeMode = currentResizeMode },
        modifier = Modifier.fillMaxSize())
        
        SeekAnimationOverlay(direction = showSeekUI)
        VerticalIndicator(type = showIndicator, progress = indicatorProgress)
        
        if (isLoading) CircularProgressIndicator(modifier = Modifier.align(Alignment.Center), color = Color.Cyan)

        AnimatedVisibility(visible = showControls && !isLocked, enter = fadeIn(), exit = fadeOut()) {
            Box(Modifier.fillMaxSize()) {
                PlayerControls(
                    isPlaying = isPlaying, 
                    currentPos = currentPosition,
                    duration = duration,
                    bufferedPos = bufferedPosition,
                    isLandscape = isLandscape,
                    onPlayPause = { if (isPlaying) exoPlayer.pause() else exoPlayer.play() },
                    onSeek = { exoPlayer.seekTo(it) },
                    onBack = { if (isLandscape) { activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT; isLandscape = false } else { onBack() } },
                    onLock = { isLocked = true },
                    onResize = { 
                        currentResizeMode = when(currentResizeMode) {
                            AspectRatioFrameLayout.RESIZE_MODE_FIT -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                            AspectRatioFrameLayout.RESIZE_MODE_ZOOM -> AspectRatioFrameLayout.RESIZE_MODE_FILL
                            else -> AspectRatioFrameLayout.RESIZE_MODE_FIT
                        }
                    },
                    onPip = { activity?.enterPictureInPictureMode() },
                    onSettings = { showSettingsDialog = true }
                )
                DataUsageOverlay(speed = currentSpeed, buffer = formatTime(bufferedPosition - currentPosition), modifier = Modifier.align(Alignment.TopEnd))
            }
        }
        
        if (isLocked) { IconButton(onClick = { isLocked = false }, modifier = Modifier.align(Alignment.Center).size(60.dp).background(Color.Black.copy(0.5f), CircleShape)) { Icon(Icons.Default.LockOpen, null, tint = Color.White) } }
        if (showSettingsDialog) SettingsBottomSheet(exoPlayer = exoPlayer, onDismiss = { showSettingsDialog = false })
    }
}

@Composable
fun PlayerControls(
    isPlaying: Boolean, currentPos: Long, duration: Long, bufferedPos: Long, isLandscape: Boolean,
    onPlayPause: () -> Unit, onSeek: (Long) -> Unit, onBack: () -> Unit, onLock: () -> Unit,
    onResize: () -> Unit, onPip: () -> Unit, onSettings: () -> Unit
) {
    Box(Modifier.fillMaxSize().background(Color.Black.copy(0.4f))) {
        // Top Bar
        Row(Modifier.fillMaxWidth().align(Alignment.TopCenter).padding(8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = Color.White) }
            Row {
                IconButton(onClick = onPip) { Icon(Icons.Default.PictureInPicture, null, tint = Color.White) }
                IconButton(onClick = onResize) { Icon(Icons.Default.AspectRatio, null, tint = Color.White) }
                IconButton(onClick = onSettings) { Icon(Icons.Default.Settings, null, tint = Color.White) }
            }
        }

        // Middle Play/Pause
        IconButton(onClick = onPlayPause, modifier = Modifier.align(Alignment.Center).size(80.dp)) {
            Icon(if (isPlaying) Icons.Default.PauseCircleFilled else Icons.Default.PlayCircleFilled, null, tint = Color.White, modifier = Modifier.size(70.dp))
        }

        // Bottom Bar (SeekBar & Controls)
        Column(Modifier.fillMaxWidth().align(Alignment.BottomCenter).padding(16.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(formatTime(currentPos), color = Color.White, fontSize = 12.sp)
                Text(formatTime(duration), color = Color.White, fontSize = 12.sp)
            }
            Slider(
                value = currentPos.toFloat(),
                onValueChange = { onSeek(it.toLong()) },
                valueRange = 0f..(duration.toFloat().coerceAtLeast(1f)),
                colors = SliderDefaults.colors(thumbColor = Color.Cyan, activeTrackColor = Color.Cyan, inactiveTrackColor = Color.Gray.copy(0.5f))
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                IconButton(onClick = onLock) { Icon(Icons.Default.Lock, null, tint = Color.White) }
                Text("Buffer: ${formatTime(bufferedPos)}", color = Color.White.copy(0.7f), fontSize = 11.sp)
            }
        }
    }
}

@androidx.annotation.OptIn(UnstableApi::class)
@kotlin.OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsBottomSheet(exoPlayer: ExoPlayer, onDismiss: () -> Unit) {
    val sheetState = rememberModalBottomSheetState()
    var subtitleSize by remember { mutableFloatStateOf(18f) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        LazyColumn(Modifier.fillMaxWidth().padding(16.dp)) {
            item { Text("Player Settings", style = MaterialTheme.typography.headlineSmall) }
            
            // Subtitle Size Control
            item {
                SettingsGroup(title = "Subtitle Size: ${subtitleSize.toInt()}sp") {
                    Slider(value = subtitleSize, onValueChange = { 
                        subtitleSize = it
                        val style = CaptionStyleCompat(Color.White.toArgb(), Color.Transparent.toArgb(), Color.Transparent.toArgb(), CaptionStyleCompat.EDGE_TYPE_OUTLINE, Color.Black.toArgb(), null)
                        // Note: For full customization, a custom SubtitleView in AndroidView is needed
                    }, valueRange = 12f..30f)
                }
            }

            // Playback Speed
            item {
                SettingsGroup(title = "Playback Speed") {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                        listOf(0.5f, 1.0f, 1.5f, 2.0f).forEach { speed ->
                            FilterChip(selected = exoPlayer.playbackParameters.speed == speed, 
                                onClick = { exoPlayer.setPlaybackSpeed(speed) }, 
                                label = { Text("${speed}x") })
                        }
                    }
                }
            }
        }
    }
}

private fun formatTime(ms: Long): String {
    val hours = TimeUnit.MILLISECONDS.toHours(ms)
    val minutes = TimeUnit.MILLISECONDS.toMinutes(ms) % 60
    val seconds = TimeUnit.MILLISECONDS.toSeconds(ms) % 60
    return if (hours > 0) String.format("%02d:%02d:%02d", hours, minutes, seconds)
    else String.format("%02d:%02d", minutes, seconds)
}

// Keep older helper components like SeekAnimationOverlay, VerticalIndicator, etc.
