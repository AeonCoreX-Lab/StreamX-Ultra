package com.aeoncorex.streamx.ui.movie

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.media.AudioManager
import android.net.Uri
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.view.ViewGroup
import android.view.WindowManager
import androidx.annotation.OptIn
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.navigation.NavController
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.URLDecoder
import kotlin.math.abs
import kotlin.math.max

// --- RUST BRIDGE (Only TMDB left) ---
object StreamXCore {
    init {
        try { System.loadLibrary("streamx_core") } catch (e: Throwable) { e.printStackTrace() }
        try { System.loadLibrary("streamx-native") } catch (e: Throwable) { e.printStackTrace() }
    }
    external fun getTmdbKey(): String 
}

@OptIn(UnstableApi::class)
@Composable
fun MoviePlayerScreen(navController: NavController, encodedUrl: String) {
    val context = LocalContext.current
    val activity = context as? Activity
    val decodedUrl = remember { try { URLDecoder.decode(encodedUrl, "UTF-8") } catch (e: Exception) { encodedUrl } }
    val scope = rememberCoroutineScope()

    var videoPath by remember { mutableStateOf<String?>(null) }
    var exoPlayer by remember { mutableStateOf<ExoPlayer?>(null) }

    var isPlaying by remember { mutableStateOf(true) }
    var isBuffering by remember { mutableStateOf(true) }
    var currentTime by remember { mutableLongStateOf(0L) }
    var totalDuration by remember { mutableLongStateOf(0L) }
    var isControlsVisible by remember { mutableStateOf(true) }
    var resizeMode by remember { mutableIntStateOf(AspectRatioFrameLayout.RESIZE_MODE_FIT) }
    var isLocked by remember { mutableStateOf(false) }

    // --- Subtitle Next-Level States ---
    var isAiEnabled by remember { mutableStateOf(false) }
    var rawText by remember { mutableStateOf("") }
    var translatedSubtitle by remember { mutableStateOf("") }
    var subtitleFontSize by remember { mutableFloatStateOf(20f) }
    var subtitleColor by remember { mutableStateOf(Color.Yellow) }
    var targetLanguage by remember { mutableStateOf(TranslateLanguage.BENGALI) }
    var showSubtitleSettings by remember { mutableStateOf(false) }

    // Other UI States
    var volumeLevel by remember { mutableFloatStateOf(0.5f) }
    var brightnessLevel by remember { mutableFloatStateOf(0.5f) }
    var gestureIcon by remember { mutableStateOf<ImageVector?>(null) }
    var gestureText by remember { mutableStateOf("") }
    var showGestureOverlay by remember { mutableStateOf(false) }
    var forwardAnimAlpha by remember { mutableFloatStateOf(0f) }
    var rewindAnimAlpha by remember { mutableFloatStateOf(0f) }

    var statusMsg by remember { mutableStateOf("Preparing...") }
    var downloadSpeed by remember { mutableStateOf("0 KB/s") }
    var seeds by remember { mutableIntStateOf(0) }

    val audioManager = remember { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    val maxVolume = remember { audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC) }

    // --- ML Kit Translator Setup ---
    val translator = remember(targetLanguage) {
        val options = TranslatorOptions.Builder()
            .setSourceLanguage(TranslateLanguage.ENGLISH)
            .setTargetLanguage(targetLanguage)
            .build()
        Translation.getClient(options)
    }

    LaunchedEffect(targetLanguage) {
        val conditions = DownloadConditions.Builder().build()
        translator.downloadModelIfNeeded(conditions)
    }

    // --- Native Speech Recognizer Setup ---
    val speechRecognizer = remember { SpeechRecognizer.createSpeechRecognizer(context) }
    val recognizerIntent = remember {
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-US")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        }
    }

    DisposableEffect(Unit) {
        val listener = object : RecognitionListener {
            override fun onResults(results: Bundle?) {
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                if (!matches.isNullOrEmpty()) {
                    rawText = matches[0]
                    translator.translate(rawText).addOnSuccessListener { translatedSubtitle = it }
                }
                if (isAiEnabled) speechRecognizer.startListening(recognizerIntent)
            }
            override fun onPartialResults(partialResults: Bundle?) {
                val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                if (!matches.isNullOrEmpty()) {
                    rawText = matches[0]
                    translator.translate(rawText).addOnSuccessListener { translatedSubtitle = it }
                }
            }
            override fun onError(error: Int) {
                if (isAiEnabled) {
                    scope.launch { delay(500); speechRecognizer.startListening(recognizerIntent) }
                }
            }
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        }

        speechRecognizer.setRecognitionListener(listener)

        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val window = activity?.window
        val insetsController = if (window != null) WindowCompat.getInsetsController(window, window.decorView) else null

        insetsController?.hide(WindowInsetsCompat.Type.systemBars())
        insetsController?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE

        brightnessLevel = activity?.window?.attributes?.screenBrightness?.takeIf { it >= 0 } ?: 0.5f

        onDispose {
            speechRecognizer.destroy()
            translator.close()
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            insetsController?.show(WindowInsetsCompat.Type.systemBars())
            
            exoPlayer?.stop()             
            exoPlayer?.release()          
            TorrentEngine.stop()          
            TorrentEngine.clearCache(context)
        }
    }

    LaunchedEffect(isAiEnabled) {
        if (isAiEnabled) {
            speechRecognizer.startListening(recognizerIntent)
        } else {
            speechRecognizer.stopListening()
            translatedSubtitle = ""
        }
    }

    LaunchedEffect(decodedUrl) {
        if (decodedUrl.startsWith("magnet:?")) {
            withContext(Dispatchers.IO) { TorrentEngine.clearCache(context) }
            TorrentEngine.start(context, decodedUrl).collect { state ->
                when (state) {
                    is StreamState.Preparing -> statusMsg = state.message
                    is StreamState.Buffering -> {
                        if (!isPlaying) isBuffering = true
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
            isBuffering = false
        }
    }

    LaunchedEffect(isPlaying) {
        while (isPlaying) {
            exoPlayer?.let { player ->
                currentTime = player.currentPosition
                totalDuration = max(0L, player.duration)
            }
            delay(1000)
        }
    }

    DisposableEffect(videoPath) {
        if (videoPath == null) return@DisposableEffect onDispose {}
        
        // --- ExoPlayer is now much lighter without custom audio sink ---
        val player = ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(Uri.parse(videoPath)))
            prepare()
            playWhenReady = true
        }
        exoPlayer = player

        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying = playing
                if (playing) {
                    isBuffering = false
                    scope.launch { delay(3000); if(isPlaying) isControlsVisible = false }
                }
            }
            override fun onPlaybackStateChanged(state: Int) {
                isBuffering = state == Player.STATE_BUFFERING
                if (state == Player.STATE_ENDED) isControlsVisible = true
            }
        }
        player.addListener(listener)

        onDispose {
            player.removeListener(listener)
            exoPlayer = null
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        
        if (videoPath != null) {
            AndroidView(
                factory = { ctx ->
                    PlayerView(ctx).apply {
                        player = exoPlayer
                        useController = false
                        layoutParams = ViewGroup.LayoutParams(-1, -1)
                        keepScreenOn = true
                    }
                },
                update = { view ->
                    if (view.player != exoPlayer) view.player = exoPlayer
                    view.resizeMode = resizeMode
                },
                modifier = Modifier.fillMaxSize()
            )
        }

        var lastUpdateTime by remember { mutableLongStateOf(0L) }
        var dragAccumulator by remember { mutableFloatStateOf(0f) }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = { isControlsVisible = !isControlsVisible },
                        onDoubleTap = { offset ->
                            if (isLocked) return@detectTapGestures
                            val isForward = offset.x > size.width / 2
                            exoPlayer?.let { 
                                it.seekTo(it.currentPosition + if(isForward) 10000 else -10000)
                                if (isForward) {
                                    forwardAnimAlpha = 1f; scope.launch { delay(500); forwardAnimAlpha = 0f }
                                } else {
                                    rewindAnimAlpha = 1f; scope.launch { delay(500); rewindAnimAlpha = 0f }
                                }
                            }
                        }
                    )
                }
                .pointerInput(Unit) {
                    detectVerticalDragGestures(
                        onDragStart = { 
                            showGestureOverlay = true 
                            dragAccumulator = 0f
                        },
                        onDragEnd = { 
                            scope.launch { delay(500); showGestureOverlay = false } 
                            dragAccumulator = 0f
                        }
                    ) { change, dragAmount ->
                        if (isLocked) return@detectVerticalDragGestures
                        
                        dragAccumulator += dragAmount
                        val currentTimeMs = System.currentTimeMillis()
                        
                        if (currentTimeMs - lastUpdateTime > 50 && abs(dragAccumulator) > 5f) {
                            lastUpdateTime = currentTimeMs
                            val isRight = change.position.x > size.width / 2
                            val delta = -dragAccumulator / (size.height / 2) 

                            if (isRight) { 
                                val volDelta = (delta * maxVolume).toInt()
                                if (volDelta != 0) {
                                    val current = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                                    val newVol = (current + volDelta).coerceIn(0, maxVolume)
                                    audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, newVol, 0)
                                    volumeLevel = newVol / maxVolume.toFloat()
                                    gestureIcon = Icons.Rounded.VolumeUp
                                    gestureText = "${(volumeLevel * 100).toInt()}%"
                                    dragAccumulator = 0f 
                                }
                            } else { 
                                brightnessLevel = (brightnessLevel + delta).coerceIn(0.01f, 1f)
                                val lp = activity?.window?.attributes
                                lp?.screenBrightness = brightnessLevel
                                activity?.window?.attributes = lp
                                gestureIcon = Icons.Rounded.BrightnessMedium
                                gestureText = "${(brightnessLevel * 100).toInt()}%"
                                dragAccumulator = 0f 
                            }
                        }
                    }
                }
        )

        if (rewindAnimAlpha > 0) {
            Box(Modifier.align(Alignment.CenterStart).padding(50.dp).alpha(rewindAnimAlpha).background(Color.Black.copy(0.5f), CircleShape).padding(16.dp)) {
                Icon(Icons.Rounded.FastRewind, null, tint = Color.White, modifier = Modifier.size(40.dp))
            }
        }
        if (forwardAnimAlpha > 0) {
            Box(Modifier.align(Alignment.CenterEnd).padding(50.dp).alpha(forwardAnimAlpha).background(Color.Black.copy(0.5f), CircleShape).padding(16.dp)) {
                Icon(Icons.Rounded.FastForward, null, tint = Color.White, modifier = Modifier.size(40.dp))
            }
        }

        // --- THE NEW CUSTOM SUBTITLE UI ---
        if (isAiEnabled && translatedSubtitle.isNotBlank()) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = if (isControlsVisible) 100.dp else 40.dp)
                    .padding(horizontal = 32.dp)
                    .background(Color.Black.copy(0.6f), RoundedCornerShape(8.dp))
                    .padding(16.dp)
            ) {
                Text(
                    text = translatedSubtitle,
                    color = subtitleColor,
                    fontSize = subtitleFontSize.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center
                )
            }
        }

        if (showGestureOverlay) {
            Box(Modifier.align(Alignment.Center).background(Color.Black.copy(0.7f), RoundedCornerShape(16.dp)).padding(24.dp)) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    gestureIcon?.let { Icon(it, null, tint = Color.Cyan, modifier = Modifier.size(48.dp)) }
                    Text(gestureText, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                }
            }
        }

        if (isBuffering && videoPath == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = Color.Cyan)
                    Spacer(Modifier.height(16.dp))
                    Text(statusMsg, color = Color.White)
                }
                IconButton(onClick = { navController.popBackStack() }, modifier = Modifier.align(Alignment.TopStart).padding(16.dp)) {
                    Icon(Icons.Default.ArrowBack, "Back", tint = Color.White)
                }
            }
        } else {
            AnimatedVisibility(
                visible = isControlsVisible || isBuffering,
                enter = fadeIn(), exit = fadeOut(),
                modifier = Modifier.fillMaxSize()
            ) {
                Box(Modifier.fillMaxSize().background(Color.Black.copy(0.4f))) {
                    
                    if (isBuffering && !isPlaying) {
                        CircularProgressIndicator(Modifier.align(Alignment.Center), color = Color.White)
                    }

                    Row(Modifier.fillMaxWidth().padding(16.dp).align(Alignment.TopStart), horizontalArrangement = Arrangement.SpaceBetween) {
                        IconButton(onClick = { navController.popBackStack() }) { Icon(Icons.Default.ArrowBack, null, tint = Color.White) }
                        
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (decodedUrl.startsWith("magnet")) {
                                Column(horizontalAlignment = Alignment.End, modifier = Modifier.padding(end = 12.dp)) {
                                    Text("▼ $downloadSpeed", color = Color.Green, fontSize = 12.sp)
                                    Text("S: $seeds", color = Color.LightGray, fontSize = 10.sp)
                                }
                            }
                            Button(
                                onClick = { isAiEnabled = !isAiEnabled },
                                colors = ButtonDefaults.buttonColors(containerColor = if(isAiEnabled) Color.Green else Color.DarkGray),
                                modifier = Modifier.height(35.dp),
                                contentPadding = PaddingValues(horizontal = 8.dp)
                            ) { Text("Auto Subs", color = Color.Black) }
                            
                            IconButton(onClick = { showSubtitleSettings = true }) { Icon(Icons.Rounded.Subtitles, null, tint = Color.White) }
                        }
                    }

                    if (!isLocked && !isBuffering) {
                        Row(Modifier.align(Alignment.Center), horizontalArrangement = Arrangement.spacedBy(40.dp)) {
                            IconButton(onClick = { exoPlayer?.seekTo((exoPlayer?.currentPosition ?: 0) - 10000) }) {
                                Icon(Icons.Rounded.Replay10, null, tint = Color.White, modifier = Modifier.size(48.dp))
                            }
                            IconButton(onClick = { if (isPlaying) exoPlayer?.pause() else exoPlayer?.play() }) {
                                Icon(if(isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, null, tint = Color.White, modifier = Modifier.size(64.dp))
                            }
                            IconButton(onClick = { exoPlayer?.seekTo((exoPlayer?.currentPosition ?: 0) + 10000) }) {
                                Icon(Icons.Rounded.Forward10, null, tint = Color.White, modifier = Modifier.size(48.dp))
                            }
                        }
                    }

                    IconButton(onClick = { isLocked = !isLocked }, modifier = Modifier.align(Alignment.CenterEnd).padding(32.dp)) { 
                        Icon(if(isLocked) Icons.Rounded.Lock else Icons.Rounded.LockOpen, null, tint = if(isLocked) Color.Red else Color.White) 
                    }

                    if (!isLocked) {
                        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(16.dp)) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(formatTime(currentTime), color = Color.White, fontSize = 12.sp)
                                Text(formatTime(totalDuration), color = Color.White, fontSize = 12.sp)
                            }
                            Slider(
                                value = currentTime.toFloat(),
                                onValueChange = { exoPlayer?.seekTo(it.toLong()) },
                                valueRange = 0f..max(1f, totalDuration.toFloat()),
                                colors = SliderDefaults.colors(thumbColor = Color.Cyan, activeTrackColor = Color.Cyan)
                            )
                        }
                    }
                }
            }
        }

        // --- SUBTITLE SETTINGS DIALOG ---
        if (showSubtitleSettings) {
            AlertDialog(
                onDismissRequest = { showSubtitleSettings = false },
                containerColor = Color.DarkGray,
                title = { Text("Subtitle Settings", color = Color.White) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        
                        // Language Selection
                        Text("Language", color = Color.LightGray)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            val langs = mapOf("Bengali" to TranslateLanguage.BENGALI, "Hindi" to TranslateLanguage.HINDI, "English" to TranslateLanguage.ENGLISH)
                            langs.forEach { (name, code) ->
                                FilterChip(
                                    selected = targetLanguage == code,
                                    onClick = { targetLanguage = code },
                                    label = { Text(name, color = if(targetLanguage == code) Color.Black else Color.White) },
                                    colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Color.Cyan)
                                )
                            }
                        }

                        // Size Selection
                        Text("Size: ${subtitleFontSize.toInt()}", color = Color.LightGray)
                        Slider(
                            value = subtitleFontSize, 
                            onValueChange = { subtitleFontSize = it }, 
                            valueRange = 16f..40f,
                            colors = SliderDefaults.colors(thumbColor = Color.Cyan, activeTrackColor = Color.Cyan)
                        )

                        // Color Selection
                        Text("Color", color = Color.LightGray)
                        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            val colors = listOf(Color.White, Color.Yellow, Color.Cyan, Color.Green)
                            colors.forEach { col ->
                                Box(
                                    modifier = Modifier
                                        .size(30.dp)
                                        .background(col, CircleShape)
                                        .clickable { subtitleColor = col }
                                )
                            }
                        }
                    }
                },
                confirmButton = { 
                    TextButton(onClick = { showSubtitleSettings = false }) { Text("Done", color = Color.Cyan) } 
                }
            )
        }
    }
}

// --- Helper Function for Formatting Time ---
private fun formatTime(timeMs: Long): String {
    val totalSeconds = timeMs / 1000
    val seconds = totalSeconds % 60
    val minutes = (totalSeconds / 60) % 60
    val hours = totalSeconds / 3600
    return if (hours > 0) {
        String.format("%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format("%02d:%02d", minutes, seconds)
    }
}
