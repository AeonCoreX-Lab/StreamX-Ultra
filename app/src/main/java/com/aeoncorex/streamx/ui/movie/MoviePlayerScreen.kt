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
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.ViewGroup
import android.view.WindowManager
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

// --- RUST BRIDGE & MPV NATIVE ---
object StreamXCore {
    init {
        try { System.loadLibrary("streamx_core") } catch (e: Throwable) { e.printStackTrace() }
        try { System.loadLibrary("streamx-native") } catch (e: Throwable) { e.printStackTrace() }
    }
    external fun getTmdbKey(): String 
    
    // MPV Native Functions
    external fun initMpvEngine()
    external fun playMpvVideo(path: String)
    external fun setMpvSurface(surface: Surface)
    external fun toggleVulkanFSR(enable: Boolean)
    external fun switchMpvAudio(lang: String)
    external fun seekMpvVideo(seconds: Double)
    external fun pauseMpvVideo(pause: Boolean)
    external fun getMpvTime(): Double
    external fun getMpvDuration(): Double
}

@Composable
fun MoviePlayerScreen(navController: NavController, encodedUrl: String) {
    val context = LocalContext.current
    val activity = context as? Activity
    val decodedUrl = remember { try { URLDecoder.decode(encodedUrl, "UTF-8") } catch (e: Exception) { encodedUrl } }
    val scope = rememberCoroutineScope()

    var videoPath by remember { mutableStateOf<String?>(null) }
    var isPlaying by remember { mutableStateOf(true) }
    var isBuffering by remember { mutableStateOf(true) }
    var currentTime by remember { mutableDoubleStateOf(0.0) }
    var totalDuration by remember { mutableDoubleStateOf(0.0) }
    
    var isControlsVisible by remember { mutableStateOf(true) }
    var isLocked by remember { mutableStateOf(false) }
    var isVulkanEnabled by remember { mutableStateOf(false) }
    var audioLang by remember { mutableStateOf("en") }

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

    // --- INIT MPV ---
    LaunchedEffect(Unit) {
        StreamXCore.initMpvEngine()
    }

    // --- Torrent Handling ---
    LaunchedEffect(decodedUrl) {
        if (decodedUrl.startsWith("magnet:?")) {
            withContext(Dispatchers.IO) { TorrentEngine.clearCache(context) }
            TorrentEngine.start(context, decodedUrl).collect { state ->
                when (state) {
                    is StreamState.Preparing -> statusMsg = state.message
                    is StreamState.Buffering -> {
                        isBuffering = true
                        statusMsg = "Buffering ${state.progress}%"
                        downloadSpeed = "${state.speed / 1024} KB/s"
                        seeds = state.seeds
                    }
                    is StreamState.Ready -> {
                        if (videoPath != state.filePath) {
                            videoPath = state.filePath
                            StreamXCore.playMpvVideo(state.filePath) // Play via MPV
                        }
                        statusMsg = ""
                        isBuffering = false
                    }
                    is StreamState.Error -> statusMsg = "Error: ${state.message}"
                }
            }
        } else {
            videoPath = decodedUrl
            StreamXCore.playMpvVideo(decodedUrl) // Play via MPV
            statusMsg = ""
            isBuffering = false
        }
    }

    // --- Time Sync Loop for MPV ---
    LaunchedEffect(isPlaying) {
        while (isPlaying) {
            currentTime = StreamXCore.getMpvTime()
            val dur = StreamXCore.getMpvDuration()
            if (dur > 0) totalDuration = dur
            delay(1000)
        }
    }

    // --- ML Kit & Speech Setup ---
    val translator = remember(targetLanguage) {
        Translation.getClient(TranslatorOptions.Builder()
            .setSourceLanguage(TranslateLanguage.ENGLISH)
            .setTargetLanguage(targetLanguage)
            .build())
    }

    val speechRecognizer = remember { SpeechRecognizer.createSpeechRecognizer(context) }
    val recognizerIntent = remember {
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-US")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        }
    }

    DisposableEffect(Unit) {
        translator.downloadModelIfNeeded(DownloadConditions.Builder().build())
        
        val listener = object : RecognitionListener {
            override fun onResults(results: Bundle?) {
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                if (!matches.isNullOrEmpty()) translator.translate(matches[0]).addOnSuccessListener { translatedSubtitle = it }
                if (isAiEnabled) speechRecognizer.startListening(recognizerIntent)
            }
            override fun onPartialResults(p: Bundle?) {
                val m = p?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                if (!m.isNullOrEmpty()) translator.translate(m[0]).addOnSuccessListener { translatedSubtitle = it }
            }
            override fun onError(e: Int) { if (isAiEnabled) scope.launch { delay(500); speechRecognizer.startListening(recognizerIntent) } }
            override fun onReadyForSpeech(p: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(r: Float) {}
            override fun onBufferReceived(b: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onEvent(e: Int, p: Bundle?) {}
        }
        speechRecognizer.setRecognitionListener(listener)

        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val insetsController = activity?.window?.let { WindowCompat.getInsetsController(it, it.decorView) }
        insetsController?.hide(WindowInsetsCompat.Type.systemBars())

        onDispose {
            speechRecognizer.destroy()
            translator.close()
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            insetsController?.show(WindowInsetsCompat.Type.systemBars())
            StreamXCore.pauseMpvVideo(true) // Pause MPV
            TorrentEngine.stop()
            TorrentEngine.clearCache(context)
        }
    }

    LaunchedEffect(isAiEnabled) {
        if (isAiEnabled) speechRecognizer.startListening(recognizerIntent)
        else { speechRecognizer.stopListening(); translatedSubtitle = "" }
    }

    // --- UI Rendering ---
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        
        // --- NATIVE MPV SURFACE ---
        AndroidView(
            factory = { ctx ->
                SurfaceView(ctx).apply {
                    holder.addCallback(object : SurfaceHolder.Callback {
                        override fun surfaceCreated(holder: SurfaceHolder) {
                            StreamXCore.setMpvSurface(holder.surface)
                        }
                        override fun surfaceChanged(h: SurfaceHolder, format: Int, w: Int, height: Int) {}
                        override fun surfaceDestroyed(h: SurfaceHolder) {}
                    })
                    layoutParams = ViewGroup.LayoutParams(-1, -1)
                }
            },
            modifier = Modifier.fillMaxSize()
        )

        var lastUpdateTime by remember { mutableLongStateOf(0L) }
        var dragAccumulator by remember { mutableFloatStateOf(0f) }

        // Gestures
        Box(
            modifier = Modifier.fillMaxSize()
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = { isControlsVisible = !isControlsVisible },
                        onDoubleTap = { offset ->
                            if (isLocked) return@detectTapGestures
                            val isForward = offset.x > size.width / 2
                            StreamXCore.seekMpvVideo(if(isForward) 10.0 else -10.0) // Seek MPV
                            if (isForward) { forwardAnimAlpha = 1f; scope.launch { delay(500); forwardAnimAlpha = 0f } } 
                            else { rewindAnimAlpha = 1f; scope.launch { delay(500); rewindAnimAlpha = 0f } }
                        }
                    )
                }
                .pointerInput(Unit) {
                    detectVerticalDragGestures(
                        onDragStart = { showGestureOverlay = true; dragAccumulator = 0f },
                        onDragEnd = { scope.launch { delay(500); showGestureOverlay = false }; dragAccumulator = 0f }
                    ) { change, dragAmount ->
                        if (isLocked) return@detectVerticalDragGestures
                        dragAccumulator += dragAmount
                        val currentMs = System.currentTimeMillis()
                        if (currentMs - lastUpdateTime > 50 && abs(dragAccumulator) > 5f) {
                            lastUpdateTime = currentMs
                            if (change.position.x > size.width / 2) { 
                                val v = (-(dragAccumulator / (size.height / 2)) * maxVolume).toInt()
                                if (v != 0) {
                                    val newV = (audioManager.getStreamVolume(AudioManager.STREAM_MUSIC) + v).coerceIn(0, maxVolume)
                                    audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, newV, 0)
                                    volumeLevel = newV / maxVolume.toFloat()
                                    gestureIcon = Icons.Rounded.VolumeUp
                                    gestureText = "${(volumeLevel * 100).toInt()}%"
                                    dragAccumulator = 0f 
                                }
                            } else { 
                                brightnessLevel = (brightnessLevel + (-(dragAccumulator / (size.height / 2)))).coerceIn(0.01f, 1f)
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

        // Subtitles Overlay
        if (isAiEnabled && translatedSubtitle.isNotBlank()) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = if (isControlsVisible) 100.dp else 40.dp, start = 32.dp, end = 32.dp)
                    .background(Color.Black.copy(0.6f), RoundedCornerShape(8.dp))
                    .padding(16.dp)
            ) {
                Text(text = translatedSubtitle, color = subtitleColor, fontSize = subtitleFontSize.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
            }
        }

        // Gesture Overlay (Volume/Brightness)
        if (showGestureOverlay) {
            Box(Modifier.align(Alignment.Center).background(Color.Black.copy(0.7f), RoundedCornerShape(16.dp)).padding(24.dp)) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    gestureIcon?.let { Icon(it, null, tint = Color.Cyan, modifier = Modifier.size(48.dp)) }
                    Text(gestureText, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                }
            }
        }

        // Double Tap Animations
        if (rewindAnimAlpha > 0) { Box(Modifier.align(Alignment.CenterStart).padding(50.dp).alpha(rewindAnimAlpha).background(Color.Black.copy(0.5f), CircleShape).padding(16.dp)) { Icon(Icons.Rounded.FastRewind, null, tint = Color.White, modifier = Modifier.size(40.dp)) } }
        if (forwardAnimAlpha > 0) { Box(Modifier.align(Alignment.CenterEnd).padding(50.dp).alpha(forwardAnimAlpha).background(Color.Black.copy(0.5f), CircleShape).padding(16.dp)) { Icon(Icons.Rounded.FastForward, null, tint = Color.White, modifier = Modifier.size(40.dp)) } }

        // Controls
        if (isBuffering && videoPath == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = Color.Cyan)
                    Spacer(Modifier.height(16.dp))
                    Text(statusMsg, color = Color.White)
                }
                IconButton(onClick = { navController.popBackStack() }, modifier = Modifier.align(Alignment.TopStart).padding(16.dp)) { Icon(Icons.Default.ArrowBack, "Back", tint = Color.White) }
            }
        } else {
            AnimatedVisibility(visible = isControlsVisible, enter = fadeIn(), exit = fadeOut()) {
                Box(Modifier.fillMaxSize().background(Color.Black.copy(0.4f))) {
                    
                    Row(Modifier.fillMaxWidth().padding(16.dp).align(Alignment.TopStart), horizontalArrangement = Arrangement.SpaceBetween) {
                        IconButton(onClick = { navController.popBackStack() }) { Icon(Icons.Default.ArrowBack, null, tint = Color.White) }
                        
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (decodedUrl.startsWith("magnet")) {
                                Column(horizontalAlignment = Alignment.End, modifier = Modifier.padding(end = 12.dp)) {
                                    Text("▼ $downloadSpeed", color = Color.Green, fontSize = 12.sp)
                                    Text("S: $seeds", color = Color.LightGray, fontSize = 10.sp)
                                }
                            }
                            
                            // Vulkan Toggle
                            Button(
                                onClick = { isVulkanEnabled = !isVulkanEnabled; StreamXCore.toggleVulkanFSR(isVulkanEnabled) },
                                colors = ButtonDefaults.buttonColors(containerColor = if(isVulkanEnabled) Color.Magenta else Color.DarkGray),
                                modifier = Modifier.height(35.dp).padding(end = 8.dp), contentPadding = PaddingValues(horizontal = 8.dp)
                            ) { Text("Vulkan FSR", color = Color.White, fontSize = 12.sp) }

                            // Audio Switcher
                            Button(
                                onClick = { audioLang = if(audioLang == "en") "hi" else "en"; StreamXCore.switchMpvAudio(audioLang) },
                                colors = ButtonDefaults.buttonColors(containerColor = Color.Blue),
                                modifier = Modifier.height(35.dp).padding(end = 8.dp), contentPadding = PaddingValues(horizontal = 8.dp)
                            ) { Text(if(audioLang=="hi") "Hindi" else "Eng", color = Color.White, fontSize = 12.sp) }

                            // AI Subtitles
                            Button(
                                onClick = { isAiEnabled = !isAiEnabled },
                                colors = ButtonDefaults.buttonColors(containerColor = if(isAiEnabled) Color.Green else Color.DarkGray),
                                modifier = Modifier.height(35.dp), contentPadding = PaddingValues(horizontal = 8.dp)
                            ) { Text("Auto Subs", color = Color.Black) }
                            
                            IconButton(onClick = { showSubtitleSettings = true }) { Icon(Icons.Rounded.Subtitles, null, tint = Color.White) }
                        }
                    }

                    if (!isLocked) {
                        Row(Modifier.align(Alignment.Center), horizontalArrangement = Arrangement.spacedBy(40.dp)) {
                            IconButton(onClick = { StreamXCore.seekMpvVideo(-10.0) }) { Icon(Icons.Rounded.Replay10, null, tint = Color.White, modifier = Modifier.size(48.dp)) }
                            IconButton(onClick = { isPlaying = !isPlaying; StreamXCore.pauseMpvVideo(!isPlaying) }) { Icon(if(isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, null, tint = Color.White, modifier = Modifier.size(64.dp)) }
                            IconButton(onClick = { StreamXCore.seekMpvVideo(10.0) }) { Icon(Icons.Rounded.Forward10, null, tint = Color.White, modifier = Modifier.size(48.dp)) }
                        }
                    }

                    IconButton(onClick = { isLocked = !isLocked }, modifier = Modifier.align(Alignment.CenterEnd).padding(32.dp)) { Icon(if(isLocked) Icons.Rounded.Lock else Icons.Rounded.LockOpen, null, tint = if(isLocked) Color.Red else Color.White) }

                    if (!isLocked) {
                        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(16.dp)) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(formatTime(currentTime.toLong()), color = Color.White, fontSize = 12.sp)
                                Text(formatTime(totalDuration.toLong()), color = Color.White, fontSize = 12.sp)
                            }
                            Slider(
                                value = currentTime.toFloat(),
                                onValueChange = { val jump = it - currentTime; StreamXCore.seekMpvVideo(jump.toDouble()) },
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
                        Text("Size: ${subtitleFontSize.toInt()}", color = Color.LightGray)
                        Slider(value = subtitleFontSize, onValueChange = { subtitleFontSize = it }, valueRange = 16f..40f, colors = SliderDefaults.colors(thumbColor = Color.Cyan, activeTrackColor = Color.Cyan))
                        Text("Color", color = Color.LightGray)
                        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            listOf(Color.White, Color.Yellow, Color.Cyan, Color.Green).forEach { col ->
                                Box(modifier = Modifier.size(30.dp).background(col, CircleShape).clickable { subtitleColor = col })
                            }
                        }
                    }
                },
                confirmButton = { TextButton(onClick = { showSubtitleSettings = false }) { Text("Done", color = Color.Cyan) } }
            )
        }
    }
}

private fun formatTime(timeSeconds: Long): String {
    val seconds = timeSeconds % 60
    val minutes = (timeSeconds / 60) % 60
    val hours = timeSeconds / 3600
    return if (hours > 0) String.format("%d:%02d:%02d", hours, minutes, seconds)
    else String.format("%02d:%02d", minutes, seconds)
}
