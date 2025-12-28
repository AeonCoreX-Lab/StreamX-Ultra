package com.aeoncorex.streamx.ui.player

import android.app.Activity
import android.app.PictureInPictureParams
import android.content.Context
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.TrafficStats
import android.os.Build
import android.util.Rational
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.annotation.RequiresApi
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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.rounded.BrokenImage
import androidx.compose.material.icons.rounded.SignalWifiOff
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
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.navigation.NavController
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import java.net.URLDecoder

// --- Utility: Check Internet ---
fun isInternetAvailable(context: Context): Boolean {
    val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    val network = connectivityManager.activeNetwork ?: return false
    val activeNetwork = connectivityManager.getNetworkCapabilities(network) ?: return false
    return activeNetwork.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
}

@OptIn(UnstableApi::class)
@Composable
fun PlayerScreen(navController: NavController, encodedUrl: String) {
    val context = LocalContext.current
    val streamUrl = remember(encodedUrl) { URLDecoder.decode(encodedUrl, "UTF-8") }
    val activity = context as? Activity

    // --- Core Player States ---
    val trackSelector = remember { DefaultTrackSelector(context) }
    val exoPlayer = remember {
        ExoPlayer.Builder(context)
            .setTrackSelector(trackSelector)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                    .build(),
                true // Handle Audio Focus automatically
            )
            .build().apply {
                setMediaItem(MediaItem.fromUri(streamUrl))
                prepare()
                playWhenReady = true
            }
    }

    // UI States
    var isControlsVisible by remember { mutableStateOf(true) }
    var isLocked by remember { mutableStateOf(false) }
    var resizeMode by remember { mutableIntStateOf(AspectRatioFrameLayout.RESIZE_MODE_FIT) }
    
    // Quality Selection State
    var showQualityDialog by remember { mutableStateOf(false) }
    var currentQualityLabel by remember { mutableStateOf("Auto") }

    // Volume & Brightness Logic
    var brightness by remember { mutableFloatStateOf(activity?.window?.attributes?.screenBrightness ?: 0.5f) }
    val audioManager = remember { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    val maxVolume = remember { audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC) }
    var currentVolume by remember { mutableIntStateOf(audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)) }
    
    // Visual Feedback States
    var showBrightnessSlider by remember { mutableStateOf(false) }
    var showVolumeSlider by remember { mutableStateOf(false) }

    // Player Status
    var isBuffering by remember { mutableStateOf(true) }
    var isPlaying by remember { mutableStateOf(true) }
    var hasError by remember { mutableStateOf(false) }
    var isInternetLost by remember { mutableStateOf(false) }

    // Network Stats
    var downloadSpeed by remember { mutableStateOf("0 KB/s") }
    var totalDataUsed by remember { mutableStateOf("0 MB") }
    var startRxBytes by remember { mutableLongStateOf(TrafficStats.getTotalRxBytes()) }

    // --- Lifecycle & Listeners ---
    DisposableEffect(exoPlayer) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                isBuffering = (state == Player.STATE_BUFFERING)
                isPlaying = (state == Player.STATE_READY && exoPlayer.playWhenReady)
                if (state == Player.STATE_ENDED) isPlaying = false
            }
            override fun onPlayerError(error: PlaybackException) {
                hasError = true
            }
            override fun onIsPlayingChanged(isPlayingState: Boolean) {
                isPlaying = isPlayingState
            }
        }
        exoPlayer.addListener(listener)
        onDispose {
            exoPlayer.removeListener(listener)
            exoPlayer.release()
        }
    }

    // --- Real-time Stats ---
    LaunchedEffect(Unit) {
        while (isActive) {
            if (!isInternetAvailable(context)) isInternetLost = true
            val currentRxBytes = TrafficStats.getTotalRxBytes()
            downloadSpeed = formatSpeed(currentRxBytes - startRxBytes)
            totalDataUsed = formatData(TrafficStats.getUidRxBytes(android.os.Process.myUid()))
            startRxBytes = currentRxBytes
            delay(1000)
        }
    }

    // --- Auto Hide Controls ---
    LaunchedEffect(isControlsVisible, isPlaying) {
        if (isControlsVisible && isPlaying) {
            delay(4000)
            isControlsVisible = false
        }
    }

    // --- Hide Sliders after delay ---
    LaunchedEffect(showBrightnessSlider, showVolumeSlider) {
        if (showBrightnessSlider || showVolumeSlider) {
            delay(1500)
            showBrightnessSlider = false
            showVolumeSlider = false
        }
    }

    KeepScreenOn()
    HideSystemUi(activity)
    
    // PiP Handling (Simple trigger on Home button usually requires Activity config, 
    // but here we add a button or rely on system behavior if configured in Manifest)

    BackHandler {
        if (isControlsVisible) isControlsVisible = false
        else if (!isLocked) navController.popBackStack()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { if (!isLocked) isControlsVisible = !isControlsVisible },
                    onDoubleTap = {
                        if (!isLocked) {
                            resizeMode = when (resizeMode) {
                                AspectRatioFrameLayout.RESIZE_MODE_FIT -> AspectRatioFrameLayout.RESIZE_MODE_FILL
                                AspectRatioFrameLayout.RESIZE_MODE_FILL -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                                else -> AspectRatioFrameLayout.RESIZE_MODE_FIT
                            }
                        }
                    }
                )
            }
            .pointerInput(Unit) {
                detectVerticalDragGestures(
                    onDragStart = { },
                    onDragEnd = { 
                        // Sliders auto-hide via LaunchedEffect
                    }
                ) { change, dragAmount ->
                    if (!isLocked) {
                        val isRightSide = change.position.x > size.width / 2
                        if (isRightSide) {
                            // Volume Control
                            val newVolume = (currentVolume + (dragAmount / -30)).toInt().coerceIn(0, maxVolume)
                            if (newVolume != currentVolume) {
                                currentVolume = newVolume
                                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, currentVolume, 0)
                                showVolumeSlider = true
                            }
                        } else {
                            // Brightness Control
                            brightness = (brightness + (dragAmount / -1000f)).coerceIn(0f, 1f)
                            val lp = activity?.window?.attributes
                            lp?.screenBrightness = brightness
                            activity?.window?.attributes = lp
                            showBrightnessSlider = true
                        }
                    }
                }
            }
    ) {
        // 1. Video Surface
        AndroidView(
            factory = {
                PlayerView(it).apply {
                    player = exoPlayer
                    useController = false
                    this.resizeMode = resizeMode
                    setKeepContentOnPlayerReset(true)
                }
            },
            update = { it.resizeMode = resizeMode },
            modifier = Modifier.fillMaxSize()
        )

        // 2. Buffering / Error Overlay
        if (isBuffering && !hasError) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = MaterialTheme.colorScheme.primary, strokeWidth = 4.dp)
            }
        }

        // 3. Visual Sliders (Left: Brightness, Right: Volume)
        // Brightness
        AnimatedVisibility(
            visible = showBrightnessSlider,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.CenterStart).padding(start = 32.dp)
        ) {
            VerticalSliderIndicator(icon = Icons.Default.BrightnessMedium, level = brightness, max = 1f, color = Color.Yellow)
        }

        // Volume
        AnimatedVisibility(
            visible = showVolumeSlider,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.CenterEnd).padding(end = 32.dp)
        ) {
            VerticalSliderIndicator(icon = Icons.Default.VolumeUp, level = currentVolume.toFloat(), max = maxVolume.toFloat(), color = Color.Cyan)
        }

        // 4. Controls UI
        AnimatedVisibility(
            visible = isControlsVisible && !hasError,
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            AdvancedPlayerControls(
                title = "Live Stream",
                isPlaying = isPlaying,
                isLocked = isLocked,
                qualityLabel = currentQualityLabel,
                stats = "$downloadSpeed | $totalDataUsed",
                onBack = { navController.popBackStack() },
                onPlayPause = { if (isPlaying) exoPlayer.pause() else exoPlayer.play() },
                onLockToggle = { isLocked = !isLocked },
                onRotateToggle = {
                    if (activity?.requestedOrientation == ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE) {
                        activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                    } else {
                        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                    }
                },
                onQualityClick = { showQualityDialog = true },
                onPipClick = {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        val params = PictureInPictureParams.Builder()
                            .setAspectRatio(Rational(16, 9))
                            .build()
                        activity?.enterPictureInPictureMode(params)
                    }
                }
            )
        }

        // 5. Quality Selection Dialog
        if (showQualityDialog) {
            QualitySelectorDialog(
                trackSelector = trackSelector,
                onDismiss = { showQualityDialog = false },
                onQualitySelected = { label -> currentQualityLabel = label }
            )
        }
        
        // 6. Error Screen
        if (hasError || isInternetLost) {
            ErrorScreen(isInternetLost = isInternetLost) {
                // Retry logic can be added here
                if (isInternetLost && isInternetAvailable(context)) {
                    isInternetLost = false
                    exoPlayer.prepare()
                    exoPlayer.play()
                }
            }
        }
    }
}

// --- Components ---

@Composable
fun AdvancedPlayerControls(
    title: String,
    isPlaying: Boolean,
    isLocked: Boolean,
    qualityLabel: String,
    stats: String,
    onBack: () -> Unit,
    onPlayPause: () -> Unit,
    onLockToggle: () -> Unit,
    onRotateToggle: () -> Unit,
    onQualityClick: () -> Unit,
    onPipClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(
                Color.Black.copy(0.8f),
                Color.Transparent,
                Color.Transparent,
                Color.Black.copy(0.8f)
            )))
    ) {
        // Top Bar
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp).align(Alignment.TopStart),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Color.White)
            }
            Column(modifier = Modifier.weight(1f).padding(start = 8.dp)) {
                Text(title, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                Text(stats, color = Color.Green, fontSize = 10.sp)
            }
            
            // Settings / PiP Buttons
            IconButton(onClick = onPipClick) {
                Icon(Icons.Default.PictureInPictureAlt, "PiP", tint = Color.White)
            }
            IconButton(onClick = onQualityClick) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Settings, "Quality", tint = Color.White, modifier = Modifier.size(20.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(qualityLabel, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }
        }

        // Center Controls
        Box(modifier = Modifier.align(Alignment.Center)) {
            if (isLocked) {
                IconButton(
                    onClick = onLockToggle,
                    modifier = Modifier.size(50.dp).background(Color.White.copy(0.2f), CircleShape)
                ) { Icon(Icons.Default.Lock, null, tint = Color.White) }
            } else {
                IconButton(
                    onClick = onPlayPause,
                    modifier = Modifier.size(70.dp).background(MaterialTheme.colorScheme.primary, CircleShape)
                ) {
                    Icon(
                        if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        null, tint = Color.White, modifier = Modifier.size(40.dp)
                    )
                }
            }
        }

        // Bottom Bar
        if (!isLocked) {
            Column(modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    // Lock
                    IconButton(onClick = onLockToggle) {
                        Icon(Icons.Default.LockOpen, "Lock", tint = Color.White)
                    }
                    
                    // Live Indicator
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(modifier = Modifier.size(8.dp).background(Color.Red, CircleShape))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("LIVE", color = Color.White, fontWeight = FontWeight.Bold)
                    }

                    // Rotate
                    IconButton(onClick = onRotateToggle) {
                        Icon(Icons.Default.ScreenRotation, "Rotate", tint = Color.White)
                    }
                }
            }
        }
    }
}

@Composable
fun VerticalSliderIndicator(icon: androidx.compose.ui.graphics.vector.ImageVector, level: Float, max: Float, color: Color) {
    val percentage = (level / max).coerceIn(0f, 1f)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .background(Color.Black.copy(0.6f))
            .padding(8.dp)
    ) {
        Box(
            modifier = Modifier
                .width(8.dp)
                .height(100.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(Color.White.copy(0.3f)),
            contentAlignment = Alignment.BottomCenter
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(percentage)
                    .background(color)
            )
        }
        Spacer(modifier = Modifier.width(8.dp))
        Icon(icon, null, tint = Color.White)
    }
}

@OptIn(UnstableApi::class)
@Composable
fun QualitySelectorDialog(
    trackSelector: DefaultTrackSelector,
    onDismiss: () -> Unit,
    onQualitySelected: (String) -> Unit
) {
    val tracks = remember { trackSelector.currentMappedTrackInfo }
    
    // Simple logic to fetch video tracks from renderer index 0 (usually video)
    // Needs robust checking in prod, but works for most streams
    val rendererIndex = 0 
    val trackGroups = tracks?.getTrackGroups(rendererIndex)
    
    Dialog(onDismissRequest = onDismiss) {
        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E))
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Select Quality", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(12.dp))
                
                // Auto Option
                QualityItem("Auto", onClick = {
                    trackSelector.parameters = trackSelector.buildUponParameters()
                        .clearOverrides()
                        .build()
                    onQualitySelected("Auto")
                    onDismiss()
                })

                // List tracks (Simplified)
                if (trackGroups != null) {
                    LazyColumn(modifier = Modifier.heightIn(max = 200.dp)) {
                       for (i in 0 until trackGroups.length) {
                           val group = trackGroups.get(i)
                           for (j in 0 until group.length) {
                               val format = group.getFormat(j)
                               if (format.height > 0) { // Only video tracks
                                   val label = "${format.height}p"
                                   item {
                                       QualityItem(label, onClick = {
                                           val override = TrackSelectionOverride(group, j)
                                           trackSelector.parameters = trackSelector.buildUponParameters()
                                               .setOverrideForType(override)
                                               .build()
                                           onQualitySelected(label)
                                           onDismiss()
                                       })
                                   }
                               }
                           }
                       }
                    }
                }
            }
        }
    }
}

@Composable
fun QualityItem(text: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Default.CheckCircleOutline, null, tint = Color.Gray, modifier = Modifier.size(20.dp))
        Spacer(modifier = Modifier.width(12.dp))
        Text(text, color = Color.White, fontSize = 16.sp)
    }
}

@Composable
fun ErrorScreen(isInternetLost: Boolean, onRetry: () -> Unit) {
    Box(
        modifier = Modifier.fillMaxSize().background(Color.Black.copy(0.9f)),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                if (isInternetLost) Icons.Rounded.SignalWifiOff else Icons.Rounded.BrokenImage,
                null, tint = Color.Red, modifier = Modifier.size(50.dp)
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(if (isInternetLost) "No Internet" else "Stream Error", color = Color.White, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(16.dp))
            Button(onClick = onRetry) { Text("Retry") }
        }
    }
}

// --- Helpers ---
private fun formatData(bytes: Long): String = String.format("%.1f MB", bytes / 1024.0 / 1024.0)
private fun formatSpeed(bytes: Long): String = if (bytes > 1024 * 1024) String.format("%.1f MB/s", bytes / 1024.0 / 1024.0) else String.format("%.0f KB/s", bytes / 1024.0)

@Composable
private fun KeepScreenOn() {
    val context = LocalContext.current
    DisposableEffect(Unit) {
        val window = (context as? Activity)?.window
        window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }
}

@Composable
private fun HideSystemUi(activity: Activity?) {
    val window = activity?.window ?: return
    DisposableEffect(Unit) {
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        onDispose { controller.show(WindowInsetsCompat.Type.systemBars()) }
    }
}
