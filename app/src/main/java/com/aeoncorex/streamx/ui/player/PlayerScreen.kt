package com.aeoncorex.streamx.ui.player

import android.app.Activity
import android.app.PictureInPictureParams
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.TrafficStats
import android.os.Build
import android.provider.Settings
import android.util.Rational
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
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
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
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
import androidx.media3.common.*
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.navigation.NavController
import com.aeoncorex.streamx.ui.music.MusicManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.URLDecoder
import java.util.*

// --- Themes Colors ---
val NeonBlue = Color(0xFF00FFFF)
val NeonPurple = Color(0xFFBC13FE)
val GlassBlack = Color(0xCC000000)
val LiveRed = Color(0xFFFF0044)

// --- EPG Data Structure (New) ---
data class EPGProgram(
    val title: String,
    val startTime: Calendar,
    val endTime: Calendar
)

fun isInternetAvailable(context: Context): Boolean {
    val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    val network = connectivityManager.activeNetwork ?: return false
    val activeNetwork = connectivityManager.getNetworkCapabilities(network) ?: return false
    return activeNetwork.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
}

@Composable
fun PlayerScreen(navController: NavController, encodedUrl: String) {
    val context = LocalContext.current
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    val streamUrl = remember(encodedUrl) { URLDecoder.decode(encodedUrl, "UTF-8") }
    val activity = context as? Activity
    
    // --- Stop Music when Video Starts ---
    LaunchedEffect(Unit) {
        MusicManager.pause()
    }

    // --- Player Core ---
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
            .build()
    }

    // --- Features States ---
    var isAudioOnlyMode by remember { mutableStateOf(false) }
    var playbackSpeed by remember { mutableFloatStateOf(1.0f) }
    var subtitlesEnabled by remember { mutableStateOf(false) }
    
    // --- UI States ---
    var isControlsVisible by remember { mutableStateOf(true) }
    var isLocked by remember { mutableStateOf(false) }
    var resizeMode by remember { mutableIntStateOf(AspectRatioFrameLayout.RESIZE_MODE_FIT) }
    var showSettingsSheet by remember { mutableStateOf(false) }

    // --- EPG States — REAL-TIME from EpgRepository ---
    var currentProgramTitle by remember { mutableStateOf("Loading...") }
    var nextProgramTitle    by remember { mutableStateOf("") }
    var epgProgress         by remember { mutableFloatStateOf(0f) }
    var epgSource           by remember { mutableStateOf("") }
    var currentProgramTime  by remember { mutableStateOf("") }
    var nextProgramTime     by remember { mutableStateOf("") }
    
    // --- Dialogs ---
    var showQualityDialog by remember { mutableStateOf(false) }
    var currentQualityLabel by remember { mutableStateOf("Auto") }
    var showSleepTimerDialog by remember { mutableStateOf(false) }
    var sleepTimerMinutes by remember { mutableIntStateOf(0) }
    var sleepTimerRemainingSeconds by remember { mutableLongStateOf(0L) }

    // --- Volume & Brightness ---
    var brightness by remember { mutableFloatStateOf(activity?.window?.attributes?.screenBrightness ?: 0.5f) }
    val audioManager = remember { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    val maxVolume = remember { audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC) }
    var currentVolume by remember { mutableIntStateOf(audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)) }
    var showBrightnessSlider by remember { mutableStateOf(false) }
    var showVolumeSlider by remember { mutableStateOf(false) }

    // --- Status ---
    var isBuffering by remember { mutableStateOf(true) }
    var isPlaying by remember { mutableStateOf(true) }
    var hasError by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf("") }
    
    // --- Stats ---
    var downloadSpeed by remember { mutableStateOf("0 KB/s") }
    var totalDataUsed by remember { mutableStateOf("0 MB") }
    var startRxBytes by remember { mutableLongStateOf(TrafficStats.getTotalRxBytes()) }

    // Initialize Player
    LaunchedEffect(streamUrl) {
        val mediaItem = MediaItem.Builder()
            .setUri(streamUrl)
            .setLiveConfiguration(
                MediaItem.LiveConfiguration.Builder()
                    .setMaxPlaybackSpeed(1.02f)
                    .build()
            )
            .build()
        exoPlayer.setMediaItem(mediaItem)
        exoPlayer.prepare()
        exoPlayer.playWhenReady = true
    }

    // Stats & Real-Time EPG Engine
    LaunchedEffect(exoPlayer, streamUrl) {
        while (isActive) {
            // 1. Network stats
            val currentRxBytes = TrafficStats.getTotalRxBytes()
            downloadSpeed = formatSpeed(currentRxBytes - startRxBytes)
            totalDataUsed = formatData(TrafficStats.getUidRxBytes(android.os.Process.myUid()))
            startRxBytes = currentRxBytes

            // 2. Real-time EPG — fetch from EpgRepository
            try {
                val epgState = EpgRepository.getEPG(
                    streamUrl   = streamUrl,
                    channelName = streamUrl.substringAfterLast("/")
                        .substringBeforeLast(".")
                        .replace(Regex("[_-]"), " ")
                )

                epgState.current?.let { prog ->
                    currentProgramTitle = prog.title
                    currentProgramTime  = buildString {
                        append(EpgRepository.formatTime(prog.startTime))
                        append(" - ")
                        append(EpgRepository.formatTime(prog.endTime))
                    }
                }
                epgState.next?.let { prog ->
                    nextProgramTitle = prog.title
                    nextProgramTime  = EpgRepository.formatTime(prog.startTime)
                }
                epgProgress = epgState.progress
                epgSource   = epgState.source
            } catch (e: Exception) {
                // Keep previous values on error
            }

            // Update every 30s (EPG changes slowly)
            delay(30_000)
        }
    }

    // Sleep Timer Logic
    LaunchedEffect(sleepTimerMinutes) {
        if (sleepTimerMinutes > 0) {
            sleepTimerRemainingSeconds = sleepTimerMinutes * 60L
            while (isActive && sleepTimerRemainingSeconds > 0) {
                delay(1000)
                sleepTimerRemainingSeconds--
            }
            if (sleepTimerRemainingSeconds <= 0) {
                activity?.finish()
            }
        } else {
            sleepTimerRemainingSeconds = 0
        }
    }

    // Player Listeners
    DisposableEffect(exoPlayer) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                isBuffering = (state == Player.STATE_BUFFERING)
                isPlaying = (state == Player.STATE_READY && exoPlayer.playWhenReady)
                if (state == Player.STATE_ENDED) isPlaying = false
            }
            override fun onPlayerError(error: PlaybackException) {
                hasError = true
                errorMessage = error.localizedMessage ?: "Unknown Stream Error"
                isBuffering = false
            }
            override fun onIsPlayingChanged(isPlayingState: Boolean) { isPlaying = isPlayingState }
        }
        exoPlayer.addListener(listener)
        onDispose {
            exoPlayer.removeListener(listener)
            exoPlayer.release()
        }
    }

    // Auto Hide Controls
    LaunchedEffect(isControlsVisible, isPlaying) {
        if (isControlsVisible && isPlaying) {
            delay(5000) // Increased to 5s to read EPG
            isControlsVisible = false
        }
    }
    
    // Hide Sliders
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
        else if (showSettingsSheet) showSettingsSheet = false
        else if (!isLocked) navController.popBackStack()
        else Toast.makeText(context, "Screen is Locked!", Toast.LENGTH_SHORT).show()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { 
                        isControlsVisible = !isControlsVisible 
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
        // 1. Ambilight (Disabled in Audio Mode)
        if(isPlaying && !isAudioOnlyMode) {
            Box(Modifier.fillMaxSize().background(Brush.radialGradient(listOf(NeonPurple.copy(0.15f), Color.Transparent))))
        }

        // 2. Video Player Surface
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            AndroidView(
                factory = {
                    PlayerView(it).apply {
                        player = exoPlayer
                        useController = false
                        this.resizeMode = resizeMode
                        setKeepContentOnPlayerReset(true)
                        setShutterBackgroundColor(android.graphics.Color.BLACK)
                    }
                },
                update = { 
                    it.resizeMode = resizeMode 
                    it.player = exoPlayer
                },
                modifier = Modifier.fillMaxSize()
            )
            
            // Audio Mode Overlay
            if (isAudioOnlyMode) {
                Box(
                    modifier = Modifier.fillMaxSize().background(Color.Black),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Rounded.Headphones, null, tint = NeonBlue, modifier = Modifier.size(80.dp))
                        Spacer(Modifier.height(16.dp))
                        Text("AUDIO ONLY MODE", color = Color.White, letterSpacing = 2.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(8.dp))
                        Text("Tap screen to wake controls", color = Color.Gray, fontSize = 12.sp)
                    }
                }
            }
        }

        // 3. Buffering
        if (isBuffering && !hasError) {
            CircularProgressIndicator(color = NeonBlue, strokeWidth = 4.dp, modifier = Modifier.align(Alignment.Center))
        }

        // 4. Sliders (Volume/Brightness)
        AnimatedVisibility(visible = showBrightnessSlider, enter = slideInHorizontally { -it }, exit = fadeOut(), modifier = Modifier.align(Alignment.CenterStart).padding(start = 32.dp)) {
            CyberSlider(icon = Icons.Default.BrightnessMedium, level = brightness, max = 1f, color = Color.Yellow)
        }
        AnimatedVisibility(visible = showVolumeSlider, enter = slideInHorizontally { it }, exit = fadeOut(), modifier = Modifier.align(Alignment.CenterEnd).padding(end = 32.dp)) {
            CyberSlider(icon = Icons.Default.VolumeUp, level = currentVolume.toFloat(), max = maxVolume.toFloat(), color = NeonBlue)
        }

        // 5. Controls (LIVE WITH REAL-TIME EPG)
        AnimatedVisibility(visible = isControlsVisible, enter = fadeIn(), exit = fadeOut()) {
            AdvancedPlayerControls(
                title              = streamUrl.substringAfterLast("/").substringBeforeLast(".").replace(Regex("[_-]"), " ").ifEmpty { "LIVE" },
                networkSpeed       = downloadSpeed,
                dataUsed           = totalDataUsed,
                isPlaying          = isPlaying,
                isLocked           = isLocked,
                qualityLabel       = currentQualityLabel,
                currentProgram     = currentProgramTitle,
                currentProgramTime = currentProgramTime,
                nextProgram        = nextProgramTitle,
                nextProgramTime    = nextProgramTime,
                epgProgress        = epgProgress,
                epgSource          = epgSource,
                onBack = { navController.popBackStack() },
                onPlayPause = { if (isPlaying) exoPlayer.pause() else exoPlayer.play() },
                onLockToggle = { isLocked = !isLocked },
                onRotateScreen = {
                    val currentOrientation = activity?.resources?.configuration?.orientation
                    if (currentOrientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE) {
                         activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                    } else {
                         activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                    }
                },
                onResizeToggle = {
                     resizeMode = when (resizeMode) {
                        AspectRatioFrameLayout.RESIZE_MODE_FIT -> AspectRatioFrameLayout.RESIZE_MODE_FILL
                        AspectRatioFrameLayout.RESIZE_MODE_FILL -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                        else -> AspectRatioFrameLayout.RESIZE_MODE_FIT
                    }
                    val modeName = when(resizeMode) {
                        AspectRatioFrameLayout.RESIZE_MODE_FIT -> "Fit Screen"
                        AspectRatioFrameLayout.RESIZE_MODE_FILL -> "Fill Screen"
                        else -> "Zoom"
                    }
                    Toast.makeText(context, "Mode: $modeName", Toast.LENGTH_SHORT).show()
                },
                onSettingsClick = { showSettingsSheet = true },
                onPipClick = {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        val params = PictureInPictureParams.Builder()
                            .setAspectRatio(Rational(16, 9))
                            .build()
                        activity?.enterPictureInPictureMode(params)
                    } else {
                        Toast.makeText(context, "PiP not supported on this device", Toast.LENGTH_SHORT).show()
                    }
                }
            )
        }

        // 6. Settings Sheet
        if (showSettingsSheet) {
            PlayerSettingsSheet(
                onDismiss = { showSettingsSheet = false },
                qualityLabel = currentQualityLabel,
                onQualityClick = { showSettingsSheet = false; showQualityDialog = true },
                isAudioOnly = isAudioOnlyMode,
                onAudioModeClick = { isAudioOnlyMode = !isAudioOnlyMode },
                sleepTimerSeconds = sleepTimerRemainingSeconds,
                onSleepTimerClick = { showSettingsSheet = false; showSleepTimerDialog = true },
                playbackSpeed = playbackSpeed,
                onSpeedClick = {
                    val newSpeed = if(playbackSpeed >= 2.0f) 0.5f else playbackSpeed + 0.25f
                    playbackSpeed = newSpeed
                    exoPlayer.setPlaybackSpeed(newSpeed)
                },
                subtitlesEnabled = subtitlesEnabled,
                onSubtitleToggle = {
                    subtitlesEnabled = !subtitlesEnabled
                    trackSelector.parameters = trackSelector.buildUponParameters()
                        .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, !subtitlesEnabled)
                        .build()
                },
                onCastClick = {
                    showSettingsSheet = false
                    try {
                        context.startActivity(Intent("android.settings.CAST_SETTINGS"))
                    } catch (e: Exception) {
                        Toast.makeText(context, "Please use Quick Settings to Cast", Toast.LENGTH_LONG).show()
                        try { context.startActivity(Intent(Settings.ACTION_DISPLAY_SETTINGS)) } catch (e2: Exception) { /* safe */ }
                    }
                }
            )
        }

        // 7. Dialogs
        if (showQualityDialog) {
            QualitySelectorDialog(trackSelector, { showQualityDialog = false }) { currentQualityLabel = it }
        }
        
        if (showSleepTimerDialog) {
            SleepTimerDialog(sleepTimerMinutes, { showSleepTimerDialog = false }) { sleepTimerMinutes = it; showSleepTimerDialog = false }
        }

        // 8. Error / Retry Overlay
        if (hasError) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(0.9f)), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Rounded.Warning, null, tint = Color.Red, modifier = Modifier.size(48.dp))
                    Spacer(Modifier.height(16.dp))
                    Text("STREAM FAILED", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                    Text(errorMessage, color = Color.Gray, fontSize = 12.sp, modifier = Modifier.padding(20.dp))
                    Spacer(Modifier.height(24.dp))
                    Button(
                        onClick = { 
                            hasError = false
                            isBuffering = true
                            exoPlayer.prepare()
                            exoPlayer.play()
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = NeonBlue)
                    ) {
                        Text("RETRY CONNECTION", color = Color.Black, fontWeight = FontWeight.Bold)
                    }
                    Spacer(Modifier.height(16.dp))
                    TextButton(onClick = { navController.popBackStack() }) {
                        Text("EXIT PLAYER", color = Color.Gray)
                    }
                }
            }
        }
    }
}

// -----------------------------------------------------------------------------
// IMPROVED UI COMPONENTS (LIVE FOCUSED + REAL-TIME EPG)
// -----------------------------------------------------------------------------

@Composable
fun AdvancedPlayerControls(
    title: String,
    networkSpeed: String,
    dataUsed: String,
    isPlaying: Boolean,
    isLocked: Boolean,
    qualityLabel: String,
    currentProgram: String,
    currentProgramTime: String = "",
    nextProgram: String,
    nextProgramTime: String = "",
    epgProgress: Float,
    epgSource: String = "",
    onBack: () -> Unit,
    onPlayPause: () -> Unit,
    onLockToggle: () -> Unit,
    onRotateScreen: () -> Unit,
    onResizeToggle: () -> Unit,
    onSettingsClick: () -> Unit,
    onPipClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(0.4f))
    ) {
        // --- LOCKED STATE ---
        if (isLocked) {
             Column(
                 modifier = Modifier.align(Alignment.CenterStart).padding(start = 24.dp),
                 horizontalAlignment = Alignment.CenterHorizontally
             ) {
                 IconButton(
                    onClick = onLockToggle,
                    modifier = Modifier
                        .background(Color.White.copy(0.1f), CircleShape)
                        .border(1.dp, NeonBlue, CircleShape)
                        .size(50.dp)
                ) { Icon(Icons.Default.Lock, null, tint = NeonBlue) }
                Spacer(Modifier.height(8.dp))
                Text("LOCKED", color = NeonBlue, fontSize = 10.sp, fontWeight = FontWeight.Bold)
             }
            return
        }

        // --- TOP HEADER ---
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp).align(Alignment.TopStart),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = Color.White) }
            Spacer(Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(title, color = LiveRed, fontWeight = FontWeight.Bold, fontSize = 14.sp, maxLines = 1)
                Text("$qualityLabel • $networkSpeed • $dataUsed", color = Color.LightGray, fontSize = 10.sp)
            }
            IconButton(onClick = onPipClick)      { Icon(Icons.Default.PictureInPictureAlt, null, tint = Color.White) }
            IconButton(onClick = onResizeToggle)   { Icon(Icons.Default.AspectRatio, null, tint = Color.White) }
            IconButton(onClick = onSettingsClick)  { Icon(Icons.Default.Settings, null, tint = Color.White) }
        }

        // --- CENTER PLAY/PAUSE ---
        Box(modifier = Modifier.align(Alignment.Center)) {
            Box(
                modifier = Modifier
                    .size(72.dp)
                    .clip(CircleShape)
                    .background(Brush.linearGradient(listOf(NeonBlue, NeonPurple)))
                    .clickable(onClick = onPlayPause),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                    null, tint = Color.White, modifier = Modifier.size(40.dp)
                )
            }
        }

        // --- BOTTOM BAR (REAL-TIME EPG) ---
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(0.95f))))
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            // Current program title + LIVE badge
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = currentProgram.ifEmpty { "Loading..." },
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp,
                        maxLines = 1
                    )
                    if (currentProgramTime.isNotEmpty()) {
                        Text(
                            text = currentProgramTime,
                            color = NeonBlue.copy(0.85f),
                            fontSize = 11.sp
                        )
                    }
                }
                Spacer(Modifier.width(8.dp))
                // LIVE badge
                Box(
                    Modifier
                        .background(LiveRed, RoundedCornerShape(4.dp))
                        .padding(horizontal = 8.dp, vertical = 3.dp)
                ) {
                    Text("● LIVE", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Black)
                }
            }

            Spacer(Modifier.height(8.dp))

            // EPG progress bar
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .background(Color.White.copy(0.15f), RoundedCornerShape(2.dp))
            ) {
                Box(
                    Modifier
                        .fillMaxWidth(epgProgress.coerceIn(0f, 1f))
                        .fillMaxHeight()
                        .background(
                            Brush.horizontalGradient(listOf(NeonBlue, NeonPurple)),
                            RoundedCornerShape(2.dp)
                        )
                )
            }

            Spacer(Modifier.height(8.dp))

            // Next program + controls
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    if (nextProgram.isNotEmpty()) {
                        Text(
                            text = "Next: $nextProgram",
                            color = Color.Gray,
                            fontSize = 12.sp,
                            maxLines = 1
                        )
                        if (nextProgramTime.isNotEmpty()) {
                            Text(
                                text = "At $nextProgramTime",
                                color = Color.Gray.copy(0.7f),
                                fontSize = 10.sp
                            )
                        }
                    }
                    if (epgSource.isNotEmpty()) {
                        Text(
                            text = "EPG: $epgSource",
                            color = Color.Gray.copy(0.4f),
                            fontSize = 9.sp
                        )
                    }
                }
                Row {
                    IconButton(onClick = onLockToggle) {
                        Icon(Icons.Default.LockOpen, null, tint = Color.White)
                    }
                    IconButton(onClick = onRotateScreen) {
                        Icon(Icons.Default.ScreenRotation, null, tint = Color.White)
                    }
                }
            }
        }
    }
}

@Composable
fun PlayerSettingsSheet(
    onDismiss: () -> Unit,
    qualityLabel: String,
    onQualityClick: () -> Unit,
    isAudioOnly: Boolean,
    onAudioModeClick: () -> Unit,
    sleepTimerSeconds: Long,
    onSleepTimerClick: () -> Unit,
    playbackSpeed: Float,
    onSpeedClick: () -> Unit,
    subtitlesEnabled: Boolean,
    onSubtitleToggle: () -> Unit,
    onCastClick: () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF1E1E1E),
        contentColor = Color.White
    ) {
        Column(modifier = Modifier.padding(16.dp).padding(bottom = 32.dp)) {
            Text("Control Center", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = NeonBlue)
            Spacer(Modifier.height(16.dp))

            SettingsItem(icon = Icons.Rounded.Cast, title = "Cast to Device", value = "TV / PC", onClick = onCastClick)
            HorizontalDivider(color = Color.White.copy(0.1f), modifier = Modifier.padding(vertical = 8.dp))

            SettingsItem(icon = Icons.Default.Hd, title = "Stream Quality", value = qualityLabel, onClick = onQualityClick)
            SettingsItem(icon = Icons.Default.Speed, title = "Playback Speed", value = "${playbackSpeed}x", onClick = onSpeedClick)
            SettingsItem(icon = Icons.Default.Subtitles, title = "Subtitles (CC)", value = if(subtitlesEnabled) "On" else "Off", onClick = onSubtitleToggle, isToggle = true, isToggled = subtitlesEnabled)
            
            HorizontalDivider(color = Color.White.copy(0.1f), modifier = Modifier.padding(vertical = 8.dp))
            
            val timerText = if(sleepTimerSeconds > 0) "${sleepTimerSeconds/60} min left" else "Off"
            SettingsItem(icon = Icons.Rounded.Timer, title = "Sleep Timer", value = timerText, onClick = onSleepTimerClick)
            SettingsItem(icon = Icons.Rounded.Headphones, title = "Audio Only Mode", value = if(isAudioOnly) "Active" else "Inactive", onClick = onAudioModeClick, isToggle = true, isToggled = isAudioOnly)
        }
    }
}

@Composable
fun SettingsItem(icon: ImageVector, title: String, value: String, onClick: () -> Unit, isToggle: Boolean = false, isToggled: Boolean = false) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, tint = Color.Gray, modifier = Modifier.size(24.dp))
        Spacer(Modifier.width(16.dp))
        Text(title, modifier = Modifier.weight(1f), fontSize = 16.sp)
        if (isToggle) {
             Switch(
                 checked = isToggled, 
                 onCheckedChange = { onClick() }, 
                 colors = SwitchDefaults.colors(checkedThumbColor = NeonBlue, checkedTrackColor = NeonBlue.copy(0.3f), uncheckedTrackColor = Color.DarkGray)
             )
        } else {
            Text(value, color = NeonBlue, fontSize = 14.sp, fontWeight = FontWeight.Bold)
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
    val options = listOf(0, 15, 30, 45, 60, 90)
    Dialog(onDismissRequest = onDismiss) {
        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF101010)),
            border = BorderStroke(1.dp, NeonPurple.copy(0.5f))
        ) {
            Column(modifier = Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("SLEEP TIMER", color = NeonBlue, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
                Spacer(Modifier.height(16.dp))
                LazyColumn(modifier = Modifier.heightIn(max = 250.dp)) {
                    items(options.size) { index ->
                        val min = options[index]
                        val isSelected = min == currentValue
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onTimeSelected(min) }
                                .padding(vertical = 12.dp),
                            horizontalArrangement = Arrangement.Center
                        ) {
                            Text(
                                if(min == 0) "Turn Off" else "$min Minutes", 
                                color = if(isSelected) NeonPurple else Color.White,
                                fontWeight = if(isSelected) FontWeight.Bold else FontWeight.Normal
                            )
                        }
                        if(index < options.size - 1) HorizontalDivider(color = Color.White.copy(0.1f))
                    }
                }
                Spacer(Modifier.height(16.dp))
                TextButton(onClick = onDismiss) { Text("CANCEL", color = Color.Gray) }
            }
        }
    }
}

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
