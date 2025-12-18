package com.aeoncorex.streamx.ui.player

import android.app.Activity
import android.content.Context
import android.media.AudioManager
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.navigation.NavController
import com.aeoncorex.streamx.MainActivity
import com.aeoncorex.streamx.model.ChatMessage
import com.google.firebase.Timestamp
import com.google.firebase.auth.ktx.auth
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.firestore.ktx.toObjects
import com.google.firebase.ktx.Firebase
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import android.util.Log

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
fun PlayerScreen(navController: NavController, channelId: String, streamUrl: String) {
    val context = LocalContext.current
    val activity = context as? MainActivity
    val window = (LocalView.current.context as Activity).window

    var isLoading by remember { mutableStateOf(true) }
    var isLocked by remember { mutableStateOf(false) }
    var controlsVisible by remember { mutableStateOf(true) }
    var isAudioOnly by remember { mutableStateOf(false) }
    var aspectRatio by remember { mutableStateOf(AspectRatioFrameLayout.RESIZE_MODE_FIT) }
    var showChat by remember { mutableStateOf(false) }

    val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    val exoPlayer = remember {
        androidx.media3.exoplayer.ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(streamUrl))
            playWhenReady = true
            prepare()
            addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    isLoading = playbackState == Player.STATE_BUFFERING
                }
            })
        }
    }
    
    LaunchedEffect(controlsVisible, isLocked) {
        if (controlsVisible && !isLocked) { delay(5000); controlsVisible = false }
    }

    HideSystemUi(activity)
    KeepScreenOn()

    DisposableEffect(Unit) { onDispose { exoPlayer.release() } }
    
    BackHandler {
        if (showChat) showChat = false
        else if (isLocked) { isLocked = false; controlsVisible = true } 
        else activity?.enterPiPMode()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInput(isLocked, showChat) {
                if (!isLocked && !showChat) {
                    detectTapGestures(onTap = { controlsVisible = !controlsVisible })
                } else if (isLocked) {
                    detectTapGestures(onTap = { controlsVisible = true })
                }
            }
    ) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { PlayerView(it).apply { player = exoPlayer; useController = false; resizeMode = aspectRatio } },
            update = { it.resizeMode = aspectRatio }
        )

        if (!showChat) {
            GestureControls(activity, window, audioManager, isLocked)
        }

        AnimatedVisibility(visible = controlsVisible && !isLocked, enter = fadeIn(), exit = fadeOut()) {
            Box(modifier = Modifier.fillMaxSize()) {
                TopControls(navController = navController, "Live Stream")
                CenterControls(player = exoPlayer)
                BottomControls(
                    activity = activity,
                    isAudioOnly = isAudioOnly,
                    onAudioOnlyToggle = {
                        isAudioOnly = !isAudioOnly
                        exoPlayer.trackSelectionParameters = exoPlayer.trackSelectionParameters
                            .buildUpon()
                            .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, isAudioOnly)
                            .build()
                    },
                    onAspectRatioChange = { aspectRatio = it },
                    onChatToggle = { showChat = !showChat }
                )
            }
        }
        
        AnimatedVisibility(visible = controlsVisible, enter = fadeIn(), exit = fadeOut()) {
            IconButton(onClick = { isLocked = !isLocked }, modifier = Modifier.align(Alignment.CenterStart).padding(16.dp).background(Color.Black.copy(alpha = 0.5f), CircleShape)) {
                Icon(imageVector = if (isLocked) Icons.Default.Lock else Icons.Default.LockOpen, contentDescription = "Lock Screen", tint = Color.White, modifier = Modifier.size(32.dp))
            }
        }

        if (isLoading) {
            CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
        }

        if (isAudioOnly) {
            Box(modifier = Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
                Icon(Icons.AutoMirrored.Filled.VolumeUp, "Audio Mode", tint = Color.White, modifier = Modifier.size(100.dp))
            }
        }

        LiveChatOverlay(
            isVisible = showChat,
            channelId = channelId,
            onClose = { showChat = false }
        )
    }
}

@Composable
fun LiveChatOverlay(isVisible: Boolean, channelId: String, onClose: () -> Unit) {
    val db = Firebase.firestore
    val auth = Firebase.auth
    val messages = remember { mutableStateListOf<ChatMessage>() }
    var userMessage by remember { mutableStateOf("") }
    val focusManager = LocalFocusManager.current
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    LaunchedEffect(channelId) {
        val messagesRef = db.collection("channels").document(channelId).collection("messages")
            .orderBy("timestamp", Query.Direction.ASCENDING).limitToLast(100)
        
        val registration = messagesRef.addSnapshotListener { snapshot, error ->
            if (error != null) { Log.w("ChatError", "Listen failed.", error); return@addSnapshotListener }
            if (snapshot != null) {
                val newMessages = snapshot.toObjects<ChatMessage>()
                messages.clear()
                messages.addAll(newMessages)
                if (messages.isNotEmpty()) {
                    scope.launch { listState.animateScrollToItem(messages.size - 1) }
                }
            }
        }
    }

    AnimatedVisibility(
        visible = isVisible,
        enter = slideInHorizontally(initialOffsetX = { it }),
        exit = slideOutHorizontally(targetOffsetX = { it })
    ) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.CenterEnd) {
            Box(modifier = Modifier.fillMaxHeight().fillMaxWidth(0.85f).background(Color.Black.copy(alpha = 0.85f))) {
                Column(Modifier.fillMaxSize()) {
                    Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("Live Chat", color = Color.White, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                        IconButton(onClick = onClose) { Icon(Icons.Default.Close, "Close Chat", tint = Color.White) }
                    }
                    
                    LazyColumn(state = listState, modifier = Modifier.weight(1f).padding(horizontal = 8.dp)) {
                        items(messages) { msg -> ChatMessageItem(msg) }
                    }

                    OutlinedTextField(
                        value = userMessage,
                        onValueChange = { userMessage = it },
                        modifier = Modifier.fillMaxWidth().padding(8.dp),
                        placeholder = { Text("Say something...", color = Color.Gray) },
                        trailingIcon = {
                            IconButton(onClick = {
                                if (userMessage.isNotBlank()) {
                                    val newMessage = ChatMessage(text = userMessage, senderName = auth.currentUser?.displayName ?: "Guest", timestamp = Timestamp.now())
                                    db.collection("channels").document(channelId).collection("messages").add(newMessage)
                                    userMessage = ""
                                    focusManager.clearFocus()
                                }
                            }) { Icon(Icons.AutoMirrored.Filled.Send, "Send", tint = MaterialTheme.colorScheme.primary) }
                        },
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(onSend = { /* Send logic is in trailingIcon */ })
                    )
                }
            }
        }
    }
}

@Composable
fun ChatMessageItem(msg: ChatMessage) {
    Row(modifier = Modifier.padding(vertical = 4.dp)) {
        Text("${msg.senderName}: ", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary, fontSize = 14.sp)
        Text(msg.text, color = Color.White, fontSize = 14.sp)
    }
}

@Composable
fun GestureControls(activity: Activity?, window: WindowManager.LayoutParams, audioManager: AudioManager, isLocked: Boolean) {
    val brightness = remember { mutableStateOf(window.screenBrightness) }
    
    Row(Modifier.fillMaxSize()) {
        Box(modifier = Modifier.fillMaxHeight().weight(1f).pointerInput(isLocked) {
            if (!isLocked) {
                detectVerticalDragGestures(
                    onVerticalDrag = { _, dragAmount ->
                        val newBrightness = (brightness.value - dragAmount / 1000f).coerceIn(0f, 1f)
                        window.screenBrightness = newBrightness
                        brightness.value = newBrightness
                    }
                )
            }
        })
        Box(modifier = Modifier.fillMaxHeight().weight(1f).pointerInput(isLocked) {
            if (!isLocked) {
                detectVerticalDragGestures(
                    onVerticalDrag = { _, dragAmount ->
                        val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                        val currentVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                        val delta = -(dragAmount / 100).toInt()
                        val newVolume = (currentVolume + delta).coerceIn(0, maxVolume)
                        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, newVolume, 0)
                    }
                )
            }
        })
    }
}

@Composable
fun BoxScope.TopControls(navController: NavController, title: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .align(Alignment.TopCenter)
            .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.7f), Color.Transparent)))
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = { navController.popBackStack() }) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color.White)
        }
        Text(text = title, color = Color.White, modifier = Modifier.weight(1f))
        IconButton(onClick = { /* TODO: Show Audio/Subtitle tracks */ }) {
            Icon(Icons.Default.Settings, contentDescription = "Settings", tint = Color.White)
        }
    }
}

@Composable
fun BoxScope.CenterControls(player: androidx.media3.exoplayer.ExoPlayer) {
    var isPlaying by remember { mutableStateOf(player.isPlaying) }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying = playing
            }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }

    Row(
        modifier = Modifier.fillMaxWidth().align(Alignment.Center),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = { player.seekBack() }) {
            Icon(Icons.Default.Replay10, contentDescription = "Rewind 10s", tint = Color.White, modifier = Modifier.size(48.dp))
        }
        IconButton(onClick = { if (isPlaying) player.pause() else player.play() }) {
            Icon(
                imageVector = if (isPlaying) Icons.Default.PauseCircleFilled else Icons.Default.PlayCircleFilled,
                contentDescription = "Play/Pause",
                tint = Color.White,
                modifier = Modifier.size(64.dp)
            )
        }
        IconButton(onClick = { player.seekForward() }) {
            Icon(Icons.Default.Forward10, contentDescription = "Forward 10s", tint = Color.White, modifier = Modifier.size(48.dp))
        }
    }
}

@Composable
fun BoxScope.BottomControls(activity: MainActivity?, isAudioOnly: Boolean, onAudioOnlyToggle: () -> Unit, onAspectRatioChange: (Int) -> Unit, onChatToggle: () -> Unit) {
    var currentAspectRatioIndex by remember { mutableIntStateOf(0) }
    val aspectRatios = listOf(
        AspectRatioFrameLayout.RESIZE_MODE_FIT,
        AspectRatioFrameLayout.RESIZE_MODE_FILL,
        AspectRatioFrameLayout.RESIZE_MODE_ZOOM,
    )
    val aspectRatioIcons = listOf(Icons.Default.FitScreen, Icons.Default.Fullscreen, Icons.Default.ZoomIn)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .align(Alignment.BottomCenter)
            .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.7f))))
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onAudioOnlyToggle) {
            Icon(if (isAudioOnly) Icons.AutoMirrored.Filled.VolumeOff else Icons.AutoMirrored.Filled.VolumeUp, "Audio Only", tint = Color.White)
        }
        IconButton(onClick = onChatToggle) {
            Icon(Icons.Default.Chat, "Live Chat", tint = Color.White)
        }
        Spacer(modifier = Modifier.weight(1f))
        IconButton(onClick = {
            currentAspectRatioIndex = (currentAspectRatioIndex + 1) % aspectRatios.size
            onAspectRatioChange(aspectRatios[currentAspectRatioIndex])
        }) {
            Icon(aspectRatioIcons[currentAspectRatioIndex], "Aspect Ratio", tint = Color.White)
        }
        IconButton(onClick = { activity?.enterPiPMode() }) {
            Icon(Icons.Default.PictureInPicture, "PiP Mode", tint = Color.White)
        }
    }
}

@Composable
private fun KeepScreenOn() {
    val view = LocalView.current
    DisposableEffect(Unit) {
        val window = (view.context as? Activity)?.window
        window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }
}

@Composable
private fun HideSystemUi(activity: Activity?) {
    val view = activity?.window?.decorView
    val windowInsetsController = view?.let { WindowCompat.getInsetsController(activity.window, it) }

    DisposableEffect(Unit) {
        windowInsetsController?.let {
            it.hide(WindowInsetsCompat.Type.systemBars())
            it.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        onDispose {
            windowInsetsController?.show(WindowInsetsCompat.Type.systemBars())
        }
    }
}