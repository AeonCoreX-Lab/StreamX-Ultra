package com.aeoncorex.streamx.ui.player

import android.app.Activity
import android.app.PictureInPictureParams
import android.content.Context
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Rect
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.util.Rational
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.util.Consumer
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.*
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

enum class SeekDirection { NONE, FORWARD, BACKWARD }
enum class IndicatorType { NONE, BRIGHTNESS, VOLUME }

@OptIn(UnstableApi::class, ExperimentalMaterial3Api::class)
@Composable
fun PlayerScreen(encodedUrl: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val activity = context as? ComponentActivity
    
    // Core States
    var isLoading by remember { mutableStateOf(true) }
    var hasError by remember { mutableStateOf(false) }
    var showControls by remember { mutableStateOf(true) }
    var isLocked by remember { mutableStateOf(false) }
    var isPlaying by remember { mutableStateOf(true) }
    var isInPipMode by remember { mutableStateOf(false) } // PiP স্টেট
    
    // Feature States
    var totalDataUsed by remember { mutableStateOf(0L) }
    var currentSpeedStr by remember { mutableStateOf("0 KB/s") }
    var showSeekUI by remember { mutableStateOf(SeekDirection.NONE) }
    var isLandscape by remember { mutableStateOf(false) }
    var resizeMode by remember { mutableStateOf(AspectRatioFrameLayout.RESIZE_MODE_FIT) }
    var showSettingsDialog by remember { mutableStateOf(false) }

    // Gesture States
    var showIndicator by remember { mutableStateOf(IndicatorType.NONE) }
    var indicatorProgress by remember { mutableStateOf(0f) }

    val streamUrl = remember { URLDecoder.decode(encodedUrl, "UTF-8") }

    // PiP Listener: PiP মোডে ঢুকলে বা বের হলে এটি ডিটেক্ট করবে
    DisposableEffect(activity) {
        val listener = Consumer<androidx.core.app.PictureInPictureModeChangedInfo> { info ->
            isInPipMode = info.isInPictureInPictureMode
            // PiP মোডে ঢুকলে কন্ট্রোল লুকিয়ে ফেলা হবে
            if (isInPipMode) {
                showControls = false
            }
        }
        activity?.addOnPictureInPictureModeChangedListener(listener)
        onDispose { activity?.removeOnPictureInPictureModeChangedListener(listener) }
    }

    val exoPlayer = remember {
        val trackSelector = DefaultTrackSelector(context)
        val dataSourceListener = object : TransferListener {
            override fun onTransferInitializing(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) {}
            override fun onTransferStart(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) {}
            override fun onBytesTransferred(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean, bytesTransferred: Int) {
                if (isNetwork) totalDataUsed += bytesTransferred
            }
            override fun onTransferEnd(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) {}
        }
        val httpDataSourceFactory = DefaultHttpDataSource.Factory()
            .setUserAgent("StreamX-Ultra/1.2")
            .setTransferListener(dataSourceListener)
            
        val mediaSourceFactory = DefaultMediaSourceFactory(context).setDataSourceFactory(httpDataSourceFactory)

        ExoPlayer.Builder(context)
            .setTrackSelector(trackSelector)
            .setMediaSourceFactory(mediaSourceFactory)
            .build().apply {
                setMediaItem(MediaItem.fromUri(Uri.parse(streamUrl)))
                playWhenReady = true
                prepare()
                addListener(object : Player.Listener {
                    override fun onIsPlayingChanged(playing: Boolean) { isPlaying = playing }
                    override fun onPlaybackStateChanged(state: Int) { 
                        isLoading = state == Player.STATE_BUFFERING 
                        if (state == Player.STATE_READY) hasError = false
                    }
                    override fun onPlayerError(error: PlaybackException) { 
                        isLoading = false
                        hasError = true 
                    }
                })
            }
    }
    
    DisposableEffect(Unit) { onDispose { exoPlayer.release() } }
    
    BackHandler {
        if (isLandscape) {
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            isLandscape = false
        } else {
            exoPlayer.release()
            onBack()
        }
    }
    
    KeepScreenOn()
    if (!isInPipMode) HideSystemUi(activity) // PiP মোডে সিস্টেম UI কন্ট্রোল করার দরকার নেই
    
    val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)

    // Data Usage Calculator
    LaunchedEffect(Unit) {
        var lastDataUsed = 0L
        while (true) {
            delay(1000)
            val currentData = totalDataUsed
            val diff = currentData - lastDataUsed
            currentSpeedStr = formatSpeed(diff)
            lastDataUsed = currentData
        }
    }
    
    // Auto-hide controls
    LaunchedEffect(showControls, isLocked) {
        if (showControls && !isLocked && !hasError) {
            delay(4000)
            showControls = false
        }
    }
    
    // Indicator hide
    LaunchedEffect(showIndicator) {
        if (showIndicator != IndicatorType.NONE) { delay(1500); showIndicator = IndicatorType.NONE }
    }
    
    LaunchedEffect(showSeekUI) {
        if (showSeekUI != SeekDirection.NONE) { delay(600); showSeekUI = SeekDirection.NONE }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInput(Unit) {
                if (isLocked || isInPipMode) return@pointerInput // PiP মোডে জেসচার কাজ করবে না
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
                if (isLocked || isInPipMode) return@pointerInput
                detectVerticalDragGestures(
                    onDragStart = { showControls = true },
                    onVerticalDrag = { change, dragAmount ->
                        val isLeft = change.position.x < size.width / 2
                        if (isLeft) {
                            showIndicator = IndicatorType.BRIGHTNESS
                            activity?.window?.let { window ->
                                val current = window.attributes.screenBrightness.takeIf { it > 0 } ?: 0.5f
                                val new = (current - dragAmount / size.height).coerceIn(0.01f, 1.0f)
                                window.attributes = window.attributes.apply { screenBrightness = new }
                                indicatorProgress = new
                            }
                        } else {
                            showIndicator = IndicatorType.VOLUME
                            val current = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                            val delta = -(dragAmount * maxVolume / size.height.toFloat())
                            val new = (current + delta).toInt().coerceIn(0, maxVolume)
                            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, new, 0)
                            indicatorProgress = new.toFloat() / maxVolume.toFloat()
                        }
                    }
                )
            }
    ) {
        // --- Video Player ---
        AndroidView(
            factory = { 
                PlayerView(it).apply { 
                    player = exoPlayer
                    useController = false
                    layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                } 
            },
            update = { 
                it.resizeMode = resizeMode 
            },
            modifier = Modifier.fillMaxSize()
        )

        // PiP মোডে থাকলে আমরা কোনো ওভারলে দেখাবো না
        if (!isInPipMode) {
            // --- Seek Animation ---
            SeekAnimationOverlay(showSeekUI)
            
            // --- Volume/Brightness Indicator ---
            VerticalIndicator(showIndicator, indicatorProgress)

            // --- Loading ---
            if (isLoading) {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.Center), color = Color.Cyan)
            }

            // --- Error UI ---
            if (hasError) {
                Box(
                    modifier = Modifier.fillMaxSize().background(Color.Black.copy(0.85f)),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Default.ErrorOutline, null, tint = Color.Red, modifier = Modifier.size(50.dp))
                        Text("Some errors occured", color = Color.White, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp))
                        Button(
                            onClick = { 
                                hasError = false; isLoading = true
                                exoPlayer.prepare(); exoPlayer.play() 
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color.Red)
                        ) { Text("Retry") }
                    }
                }
            }

            // --- Controls Overlay ---
            AnimatedVisibility(
                visible = showControls && !hasError && !isLocked,
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                PlayerControlsOverlay(
                    title = "",
                    isPlaying = isPlaying,
                    isLandscape = isLandscape,
                    resizeMode = resizeMode,
                    totalData = formatBytes(totalDataUsed),
                    speed = currentSpeedStr,
                    onBack = { 
                        if (isLandscape) {
                            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                            isLandscape = false
                        } else {
                            exoPlayer.release()
                            onBack()
                        }
                    },
                    onPlayPause = { if (exoPlayer.isPlaying) exoPlayer.pause() else exoPlayer.play() },
                    onLock = { isLocked = true },
                    onRotate = {
                        isLandscape = !isLandscape
                        activity?.requestedOrientation = if (isLandscape) 
                            ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE 
                        else 
                            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                    },
                    onResize = {
                        resizeMode = when (resizeMode) {
                            AspectRatioFrameLayout.RESIZE_MODE_FIT -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                            AspectRatioFrameLayout.RESIZE_MODE_ZOOM -> AspectRatioFrameLayout.RESIZE_MODE_FILL
                            else -> AspectRatioFrameLayout.RESIZE_MODE_FIT
                        }
                    },
                    onSettings = { showSettingsDialog = true },
                    onPip = {
                        // --- PiP ট্রিগার করার লজিক ---
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            val params = PictureInPictureParams.Builder()
                                .setAspectRatio(Rational(16, 9))
                                .build()
                            activity?.enterPictureInPictureMode(params)
                        }
                    }
                )
            }

            // --- Locked UI ---
            if (isLocked) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    IconButton(
                        onClick = { isLocked = false },
                        modifier = Modifier.size(60.dp).background(Color.White.copy(0.2f), CircleShape).border(1.dp, Color.White, CircleShape)
                    ) {
                        Icon(Icons.Default.LockOpen, null, tint = Color.White)
                    }
                }
            }

            // --- Settings Sheet ---
            if (showSettingsDialog) {
                SettingsBottomSheet(exoPlayer) { showSettingsDialog = false }
            }
        }
    }
}

// --- Sub-Composables ---

@Composable
fun PlayerControlsOverlay(
    title: String,
    isPlaying: Boolean,
    isLandscape: Boolean,
    resizeMode: Int,
    totalData: String,
    speed: String,
    onBack: () -> Unit,
    onPlayPause: () -> Unit,
    onLock: () -> Unit,
    onRotate: () -> Unit,
    onResize: () -> Unit,
    onSettings: () -> Unit,
    onPip: () -> Unit // নতুন প্যারামিটার
) {
    Column(
        modifier = Modifier.fillMaxSize().background(
            Brush.verticalGradient(
                colors = listOf(Color.Black.copy(0.7f), Color.Transparent, Color.Black.copy(0.7f))
            )
        )
    ) {
        // Top Bar
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = Color.White) }
                // PiP বাটন (টপ বারে যোগ করা হয়েছে)
                IconButton(onClick = onPip) { 
                    Icon(Icons.Default.PictureInPictureAlt, "PiP", tint = Color.White) 
                }
            }
            
            // Data Stats
            Column(horizontalAlignment = Alignment.End) {
                Text(speed, color = Color.Green, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                Text(totalData, color = Color.White.copy(0.7f), fontSize = 10.sp)
            }
        }

        Spacer(modifier = Modifier.weight(1f))

        // Center Play/Pause
        Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            IconButton(onClick = onPlayPause, modifier = Modifier.size(72.dp).background(Color.Black.copy(0.4f), CircleShape)) {
                Icon(
                    if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                    null, tint = Color.White, modifier = Modifier.size(48.dp)
                )
            }
        }

        Spacer(modifier = Modifier.weight(1f))

        // Bottom Bar
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            IconButton(onClick = onLock) { 
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.Lock, null, tint = Color.White)
                    Text("Lock", fontSize = 10.sp, color = Color.White)
                }
            }
            IconButton(onClick = onSettings) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.Tune, null, tint = Color.White)
                    Text("Audio/Sub", fontSize = 10.sp, color = Color.White)
                }
            }
            IconButton(onClick = onResize) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    val icon = when (resizeMode) {
                        AspectRatioFrameLayout.RESIZE_MODE_ZOOM -> Icons.Default.ZoomOutMap
                        AspectRatioFrameLayout.RESIZE_MODE_FILL -> Icons.Default.AspectRatio
                        else -> Icons.Default.FitScreen
                    }
                    Icon(icon, null, tint = Color.White)
                    Text(if(resizeMode == AspectRatioFrameLayout.RESIZE_MODE_ZOOM) "Zoom" else if(resizeMode == AspectRatioFrameLayout.RESIZE_MODE_FILL) "Fill" else "Fit", fontSize = 10.sp, color = Color.White)
                }
            }
            IconButton(onClick = onRotate) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(if (isLandscape) Icons.Default.ScreenLockPortrait else Icons.Default.ScreenRotation, null, tint = Color.White)
                    Text("Rotate", fontSize = 10.sp, color = Color.White)
                }
            }
        }
    }
}

// ... (SeekAnimationOverlay, VerticalIndicator, SettingsBottomSheet, Helper Functions আগের মতোই থাকবে)
// নিশ্চিত করুন নিচের কোডগুলো ফাইলের শেষে আছে

@Composable
fun SeekAnimationOverlay(direction: SeekDirection) {
    if (direction == SeekDirection.NONE) return
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (direction == SeekDirection.BACKWARD) {
                Icon(Icons.Default.FastRewind, null, tint = Color.White.copy(0.8f), modifier = Modifier.size(80.dp))
                Text("-10s", color = Color.White, fontWeight = FontWeight.Bold)
            } else {
                Text("+10s", color = Color.White, fontWeight = FontWeight.Bold)
                Icon(Icons.Default.FastForward, null, tint = Color.White.copy(0.8f), modifier = Modifier.size(80.dp))
            }
        }
    }
}

@Composable
fun BoxScope.VerticalIndicator(type: IndicatorType, progress: Float) {
    if (type == IndicatorType.NONE) return
    val isBrightness = type == IndicatorType.BRIGHTNESS
    
    Row(
        modifier = Modifier
            .align(if (isBrightness) Alignment.CenterStart else Alignment.CenterEnd)
            .padding(20.dp)
            .width(50.dp)
            .height(150.dp)
            .background(Color.Black.copy(0.6f), RoundedCornerShape(16.dp)),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                if (isBrightness) Icons.Default.Brightness6 else Icons.Default.VolumeUp,
                null, tint = Color.White
            )
            Spacer(Modifier.height(8.dp))
            Box(
                modifier = Modifier
                    .width(6.dp)
                    .weight(1f)
                    .clip(RoundedCornerShape(3.dp))
                    .background(Color.Gray.copy(0.3f)),
                contentAlignment = Alignment.BottomCenter
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .fillMaxHeight(progress)
                        .background(if (isBrightness) Color.Yellow else Color.Cyan)
                )
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsBottomSheet(exoPlayer: ExoPlayer, onDismiss: () -> Unit) {
    val sheetState = rememberModalBottomSheetState()
    var tracks by remember { mutableStateOf(exoPlayer.currentTracks) }
    
    LaunchedEffect(Unit) {
        val listener = object : Player.Listener {
            override fun onTracksChanged(t: Tracks) { tracks = t }
        }
        exoPlayer.addListener(listener)
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Color(0xFF1E1E1E),
        contentColor = Color.White
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Playback Settings", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(16.dp))
            
            // Audio Tracks
            Text("Audio", style = MaterialTheme.typography.titleMedium, color = Color.Cyan)
            val audioGroups = tracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }
            if (audioGroups.isEmpty()) {
                Text("Default Audio", color = Color.Gray, fontSize = 14.sp)
            } else {
                audioGroups.forEach { group ->
                    for (i in 0 until group.length) {
                        val format = group.getTrackFormat(i)
                        val isSelected = group.isSelected
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    exoPlayer.trackSelectionParameters = exoPlayer.trackSelectionParameters
                                        .buildUpon()
                                        .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, i))
                                        .build()
                                    onDismiss()
                                }
                                .padding(vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(selected = isSelected, onClick = null, colors = RadioButtonDefaults.colors(selectedColor = Color.Cyan))
                            Text(format.language ?: "Unknown Language", modifier = Modifier.padding(start = 8.dp))
                        }
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
            Text("Playback Speed", style = MaterialTheme.typography.titleMedium, color = Color.Cyan)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                listOf(0.5f, 1.0f, 1.5f, 2.0f).forEach { speed ->
                     FilterChip(
                        selected = exoPlayer.playbackParameters.speed == speed,
                        onClick = { exoPlayer.setPlaybackSpeed(speed) },
                        label = { Text("${speed}x") }
                    )
                }
            }
            Spacer(Modifier.height(32.dp))
        }
    }
}

private fun formatBytes(bytes: Long): String {
    val kb = bytes / 1024
    val mb = kb / 1024
    return if (mb > 0) "$mb MB" else "$kb KB"
}

private fun formatSpeed(bytesPerSec: Long): String {
    val kb = bytesPerSec / 1024
    val mb = kb / 1024
    return if (mb > 0) "$mb MB/s" else "$kb KB/s"
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