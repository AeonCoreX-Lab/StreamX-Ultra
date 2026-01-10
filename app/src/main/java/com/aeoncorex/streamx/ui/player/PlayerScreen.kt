package com.aeoncorex.streamx.ui.player

import android.app.Activity
import android.app.PictureInPictureParams
import android.content.Context
import android.content.pm.ActivityInfo
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.TrafficStats
import android.os.Build
import android.util.Rational
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.material.icons.rounded.BrokenImage
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.SignalWifiOff
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
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
import java.util.concurrent.TimeUnit

// --- Themes Colors ---
val NeonBlue = Color(0xFF00FFFF)
val NeonPurple = Color(0xFFBC13FE)
val GlassBlack = Color(0xCC000000)

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
                true
            )
            .setHandleAudioBecomingNoisy(true)
            .build().apply {
                setMediaItem(MediaItem.fromUri(streamUrl))
                prepare()
                playWhenReady = true
            }
    }

    // --- New Feature States ---
    var isAudioOnlyMode by remember { mutableStateOf(false) } // Stealth Mode
    var sleepTimerMinutes by remember { mutableIntStateOf(0) } // Sleep Timer
    var sleepTimerRemainingSeconds by remember { mutableLongStateOf(0L) }
    var showSleepTimerDialog by remember { mutableStateOf(false) }

    // UI States
    var isControlsVisible by remember { mutableStateOf(true) }
    var isLocked by remember { mutableStateOf(false) }
    var resizeMode by remember { mutableIntStateOf(AspectRatioFrameLayout.RESIZE_MODE_FIT) }
    
    // Dialogs
    var showQualityDialog by remember { mutableStateOf(false) }
    var currentQualityLabel by remember { mutableStateOf("Auto") }

    // Volume & Brightness
    var brightness by remember { mutableFloatStateOf(activity?.window?.attributes?.screenBrightness ?: 0.5f) }
    val audioManager = remember { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    val maxVolume = remember { audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC) }
    var currentVolume by remember { mutableIntStateOf(audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)) }
    var showBrightnessSlider by remember { mutableStateOf(false) }
    var showVolumeSlider by remember { mutableStateOf(false) }

    // Player Status
    var isBuffering by remember { mutableStateOf(true) }
    var isPlaying by remember { mutableStateOf(true) }
    var hasError by remember { mutableStateOf(false) }
    var isInternetLost by remember { mutableStateOf(false) }

    // Stats
    var downloadSpeed by remember { mutableStateOf("0 KB/s") }
    var totalDataUsed by remember { mutableStateOf("0 MB") }
    var startRxBytes by remember { mutableLongStateOf(TrafficStats.getTotalRxBytes()) }

    // --- Logic: Sleep Timer ---
    LaunchedEffect(sleepTimerMinutes) {
        if (sleepTimerMinutes > 0) {
            sleepTimerRemainingSeconds = sleepTimerMinutes * 60L
            while (isActive && sleepTimerRemainingSeconds > 0) {
                delay(1000)
                sleepTimerRemainingSeconds--
            }
            if (sleepTimerRemainingSeconds <= 0) {
                activity?.finish() // Close App
            }
        } else {
            sleepTimerRemainingSeconds = 0
        }
    }

    // --- Logic: Player Listener ---
    DisposableEffect(exoPlayer) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                isBuffering = (state == Player.STATE_BUFFERING)
                isPlaying = (state == Player.STATE_READY && exoPlayer.playWhenReady)
                if (state == Player.STATE_ENDED) isPlaying = false
            }
            override fun onPlayerError(error: PlaybackException) { hasError = true }
            override fun onIsPlayingChanged(isPlayingState: Boolean) { isPlaying = isPlayingState }
        }
        exoPlayer.addListener(listener)
        onDispose {
            exoPlayer.removeListener(listener)
            exoPlayer.release()
        }
    }

    // --- Logic: Stats & Network ---
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

    // --- Logic: Auto Hide ---
    LaunchedEffect(isControlsVisible, isPlaying) {
        if (isControlsVisible && isPlaying) {
            delay(4000)
            isControlsVisible = false
        }
    }

    // --- Logic: Hide Sliders ---
    LaunchedEffect(showBrightnessSlider, showVolumeSlider) {
        if (showBrightnessSlider || showVolumeSlider) {
            delay(1500)
            showBrightnessSlider = false; showVolumeSlider = false
        }
    }

    KeepScreenOn()
    HideSystemUi(activity)
    
    BackHandler {
        if (isControlsVisible) isControlsVisible = false
        else if (!isLocked) navController.popBackStack()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black) // Ambilight Base
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
                    onDragEnd = { }
                ) { change, dragAmount ->
                    if (!isLocked) {
                        val isRightSide = change.position.x > size.width / 2
                        if (isRightSide) {
                            val newVolume = (currentVolume + (dragAmount / -30)).toInt().coerceIn(0, maxVolume)
                            if (newVolume != currentVolume) {
                                currentVolume = newVolume
                                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, currentVolume, 0)
                                showVolumeSlider = true
                            }
                        } else {
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
        // 1. Ambilight Glow (Simulated)
        if(isPlaying && !isAudioOnlyMode) {
            Box(Modifier.fillMaxSize().background(Brush.radialGradient(listOf(NeonPurple.copy(0.15f), Color.Transparent))))
        }

        // 2. Video Surface
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
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
            
            // Stealth Mode Overlay (Audio Only)
            if (isAudioOnlyMode) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Rounded.Headphones, null, tint = NeonBlue, modifier = Modifier.size(64.dp))
                        Spacer(Modifier.height(16.dp))
                        Text("STEALTH MODE ACTIVE", color = Color.White, letterSpacing = 2.sp, fontWeight = FontWeight.Bold)
                        Text("Double tap to unlock controls", color = Color.Gray, fontSize = 12.sp)
                    }
                }
            }
        }

        // 3. Buffering Indicator
        if (isBuffering && !hasError) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = NeonBlue, strokeWidth = 4.dp)
            }
        }

        // 4. Sliders (Futuristic Look)
        AnimatedVisibility(
            visible = showBrightnessSlider,
            enter = slideInHorizontally { -it },
            exit = slideOutHorizontally { -it },
            modifier = Modifier.align(Alignment.CenterStart).padding(start = 32.dp)
        ) {
            CyberSlider(icon = Icons.Default.BrightnessMedium, level = brightness, max = 1f, color = Color.Yellow)
        }

        AnimatedVisibility(
            visible = showVolumeSlider,
            enter = slideInHorizontally { it },
            exit = slideOutHorizontally { it },
            modifier = Modifier.align(Alignment.CenterEnd).padding(end = 32.dp)
        ) {
            CyberSlider(icon = Icons.Default.VolumeUp, level = currentVolume.toFloat(), max = maxVolume.toFloat(), color = NeonBlue)
        }

        // 5. Advanced Controls UI
        AnimatedVisibility(
            visible = isControlsVisible && !hasError,
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            AdvancedPlayerControls(
                title = "STREAMX LIVE",
                isPlaying = isPlaying,
                isLocked = isLocked,
                isAudioOnly = isAudioOnlyMode,
                qualityLabel = currentQualityLabel,
                stats = "$downloadSpeed • $totalDataUsed",
                sleepTimerSeconds = sleepTimerRemainingSeconds,
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
                onAudioModeClick = { isAudioOnlyMode = !isAudioOnlyMode },
                onSleepTimerClick = { showSleepTimerDialog = true },
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

        // 6. Dialogs
        if (showQualityDialog) {
            QualitySelectorDialog(trackSelector, { showQualityDialog = false }) { currentQualityLabel = it }
        }
        
        if (showSleepTimerDialog) {
            SleepTimerDialog(
                currentValue = sleepTimerMinutes,
                onDismiss = { showSleepTimerDialog = false },
                onTimeSelected = { 
                    sleepTimerMinutes = it 
                    showSleepTimerDialog = false 
                }
            )
        }

        // 7. Error Screen
        if (hasError || isInternetLost) {
            ErrorScreen(isInternetLost = isInternetLost) {
                if (isInternetLost && isInternetAvailable(context)) {
                    isInternetLost = false
                    exoPlayer.prepare()
                    exoPlayer.play()
                }
            }
        }
    }
}

// -----------------------------------------------------------------------------
// CYBERPUNK UI COMPONENTS
// -----------------------------------------------------------------------------

@Composable
fun AdvancedPlayerControls(
    title: String,
    isPlaying: Boolean,
    isLocked: Boolean,
    isAudioOnly: Boolean,
    qualityLabel: String,
    stats: String,
    sleepTimerSeconds: Long,
    onBack: () -> Unit,
    onPlayPause: () -> Unit,
    onLockToggle: () -> Unit,
    onRotateToggle: () -> Unit,
    onQualityClick: () -> Unit,
    onAudioModeClick: () -> Unit,
    onSleepTimerClick: () -> Unit,
    onPipClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(Color.Black.copy(0.9f), Color.Transparent, Color.Black.copy(0.9f))))
    ) {
        // --- TOP BAR ---
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp).align(Alignment.TopStart),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack, modifier = Modifier.background(GlassBlack, CircleShape)) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Color.White)
            }
            Spacer(Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(title, color = NeonBlue, fontWeight = FontWeight.Black, fontSize = 16.sp, letterSpacing = 2.sp)
                Text(stats, color = Color.Gray, fontSize = 10.sp, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
            }
            
            // Top Right Tools
            Row(modifier = Modifier.background(GlassBlack, RoundedCornerShape(50)).padding(4.dp)) {
                IconButton(onClick = onPipClick) { Icon(Icons.Default.PictureInPictureAlt, "PiP", tint = Color.White) }
                IconButton(onClick = onQualityClick) {
                    Box(modifier = Modifier.border(1.dp, NeonPurple, RoundedCornerShape(4.dp)).padding(horizontal = 4.dp, vertical = 2.dp)) {
                        Text(qualityLabel, color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        // --- CENTER CONTROLS ---
        Box(modifier = Modifier.align(Alignment.Center)) {
            if (isLocked) {
                IconButton(
                    onClick = onLockToggle,
                    modifier = Modifier.size(60.dp).background(Color.White.copy(0.1f), CircleShape).border(1.dp, Color.Red, CircleShape)
                ) { Icon(Icons.Default.Lock, null, tint = Color.Red, modifier = Modifier.size(30.dp)) }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(30.dp)) {
                     // 10s Rewind (Optional addition)
                    IconButton(onClick = {}, modifier = Modifier.alpha(0.6f)) {
                        Icon(Icons.Default.Replay10, null, tint = Color.White, modifier = Modifier.size(35.dp))
                    }

                    // Play/Pause Neural Button
                    IconButton(
                        onClick = onPlayPause,
                        modifier = Modifier
                            .size(80.dp)
                            .background(Brush.linearGradient(listOf(NeonBlue, NeonPurple)), CircleShape)
                            .border(2.dp, Color.White.copy(0.5f), CircleShape)
                    ) {
                        Icon(
                            if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                            null, tint = Color.Black, modifier = Modifier.size(45.dp)
                        )
                    }

                    // 10s Forward (Optional)
                    IconButton(onClick = {}, modifier = Modifier.alpha(0.6f)) {
                        Icon(Icons.Default.Forward10, null, tint = Color.White, modifier = Modifier.size(35.dp))
                    }
                }
            }
        }

        // --- BOTTOM BAR ---
        if (!isLocked) {
            Column(modifier = Modifier.align(Alignment.BottomCenter).padding(horizontal = 16.dp, vertical = 24.dp)) {
                
                // Feature Row
                Row(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    // Sleep Timer
                    Box(modifier = Modifier.clickable(onClick = onSleepTimerClick).background(GlassBlack, RoundedCornerShape(12.dp)).padding(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.Timer, null, tint = if(sleepTimerSeconds > 0) NeonBlue else Color.White, modifier = Modifier.size(20.dp))
                            if (sleepTimerSeconds > 0) {
                                Spacer(Modifier.width(4.dp))
                                Text(
                                    String.format("%02d:%02d", TimeUnit.SECONDS.toMinutes(sleepTimerSeconds), sleepTimerSeconds % 60),
                                    color = NeonBlue, fontSize = 12.sp, fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }

                    // Stealth Audio Mode
                    IconButton(onClick = onAudioModeClick, modifier = Modifier.background(if(isAudioOnly) NeonBlue else GlassBlack, CircleShape).size(40.dp)) {
                        Icon(Icons.Rounded.Headphones, null, tint = if(isAudioOnly) Color.Black else Color.White, modifier = Modifier.size(20.dp))
                    }

                    // Rotate
                    IconButton(onClick = onRotateToggle, modifier = Modifier.background(GlassBlack, CircleShape).size(40.dp)) {
                        Icon(Icons.Default.ScreenRotation, null, tint = Color.White, modifier = Modifier.size(20.dp))
                    }
                    
                    // Lock
                    IconButton(onClick = onLockToggle, modifier = Modifier.background(GlassBlack, CircleShape).size(40.dp)) {
                        Icon(Icons.Default.LockOpen, null, tint = Color.White, modifier = Modifier.size(20.dp))
                    }
                }

                // Live Indicator Line
                Box(modifier = Modifier.fillMaxWidth().height(2.dp).background(Color.White.copy(0.2f))) {
                    Box(modifier = Modifier.fillMaxWidth().height(2.dp).background(Brush.horizontalGradient(listOf(NeonBlue, NeonPurple))))
                }
                Row(modifier = Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("LIVE BROADCAST", color = NeonBlue, fontSize = 10.sp, letterSpacing = 2.sp)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(6.dp).background(Color.Red, CircleShape))
                        Spacer(Modifier.width(4.dp))
                        Text("ON AIR", color = Color.Red, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

@Composable
fun CyberSlider(icon: ImageVector, level: Float, max: Float, color: Color) {
    val percentage = (level / max).coerceIn(0f, 1f)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(GlassBlack)
            .border(1.dp, Color.White.copy(0.1f), RoundedCornerShape(12.dp))
            .padding(12.dp)
    ) {
        Box(
            modifier = Modifier.width(6.dp).height(120.dp).clip(RoundedCornerShape(3.dp)).background(Color.DarkGray),
            contentAlignment = Alignment.BottomCenter
        ) {
            Box(modifier = Modifier.fillMaxWidth().fillMaxHeight(percentage).background(Brush.verticalGradient(listOf(color, color.copy(0.5f)))))
        }
        Spacer(modifier = Modifier.width(12.dp))
        Icon(icon, null, tint = color)
    }
}

@Composable
fun SleepTimerDialog(currentValue: Int, onDismiss: () -> Unit, onTimeSelected: (Int) -> Unit) {
    val options = listOf(0, 15, 30, 45, 60)
    Dialog(onDismissRequest = onDismiss) {
        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF101010)),
            border = BorderStroke(1.dp, NeonPurple.copy(0.5f))
        ) {
            Column(modifier = Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("SLEEP TIMER", color = NeonBlue, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
                Spacer(Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    options.forEach { min ->
                        val isSelected = min == currentValue
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(if(isSelected) NeonPurple else Color.White.copy(0.1f))
                                .clickable { onTimeSelected(min) }
                                .padding(horizontal = 12.dp, vertical = 8.dp)
                        ) {
                            Text(if(min == 0) "OFF" else "$min m", color = Color.White, fontSize = 12.sp)
                        }
                    }
                }
                Spacer(Modifier.height(16.dp))
                TextButton(onClick = onDismiss) { Text("CANCEL", color = Color.Gray) }
            }
        }
    }
}

@OptIn(UnstableApi::class)
@Composable
fun QualitySelectorDialog(trackSelector: DefaultTrackSelector, onDismiss: () -> Unit, onQualitySelected: (String) -> Unit) {
    val tracks = remember { trackSelector.currentMappedTrackInfo }
    val rendererIndex = 0 
    val trackGroups = tracks?.getTrackGroups(rendererIndex)
    
    Dialog(onDismissRequest = onDismiss) {
        Card(shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = Color(0xFF101010)), border = BorderStroke(1.dp, NeonBlue.copy(0.3f))) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("STREAM QUALITY", color = NeonBlue, fontSize = 14.sp, fontWeight = FontWeight.Black, letterSpacing = 1.sp)
                Spacer(modifier = Modifier.height(12.dp))
                
                QualityItem("Auto (Adaptive)", true) {
                    trackSelector.parameters = trackSelector.buildUponParameters().clearOverrides().build()
                    onQualitySelected("Auto")
                    onDismiss()
                }

                if (trackGroups != null) {
                    LazyColumn(modifier = Modifier.heightIn(max = 200.dp)) {
                       for (i in 0 until trackGroups.length) {
                           val group = trackGroups.get(i)
                           for (j in 0 until group.length) {
                               val format = group.getFormat(j)
                               if (format.height > 0) {
                                   val label = "${format.height}p • ${String.format("%.1f", format.bitrate / 1000000f)} Mbps"
                                   item {
                                       QualityItem(label, false) {
                                           val override = TrackSelectionOverride(group, j)
                                           trackSelector.parameters = trackSelector.buildUponParameters().setOverrideForType(override).build()
                                           onQualitySelected("${format.height}p")
                                           onDismiss()
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
}

@Composable
fun QualityItem(text: String, isSelected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Default.Hd, null, tint = if(isSelected) NeonBlue else Color.Gray, modifier = Modifier.size(20.dp))
        Spacer(modifier = Modifier.width(12.dp))
        Text(text, color = if(isSelected) Color.White else Color.Gray, fontSize = 14.sp)
    }
}

@Composable
fun ErrorScreen(isInternetLost: Boolean, onRetry: () -> Unit) {
    Box(modifier = Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(if (isInternetLost) Icons.Rounded.SignalWifiOff else Icons.Rounded.BrokenImage, null, tint = Color.Red, modifier = Modifier.size(60.dp))
            Spacer(modifier = Modifier.height(16.dp))
            Text(if (isInternetLost) "NEURAL LINK LOST" else "STREAM DISCONNECTED", color = Color.White, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
            Spacer(modifier = Modifier.height(24.dp))
            Button(onClick = onRetry, colors = ButtonDefaults.buttonColors(containerColor = NeonBlue)) { Text("RECONNECT SYSTEM", color = Color.Black) }
        }
    }
}

// --- Utility Helpers ---
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
