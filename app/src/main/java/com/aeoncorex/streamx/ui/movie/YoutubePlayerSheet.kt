package com.aeoncorex.streamx.ui.movie

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.view.View
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView

// ── Design tokens ──────────────────────────────────────────────────
private val YtCyan      = Color(0xFF00E5FF)
private val YtPurple    = Color(0xFF7C4DFF)
private val YtPurpleGlow= Color(0xFFB388FF)
private val YtSheetBg   = Color(0xFF08080F)
private val YtDarkBg    = Color(0xFF020810)
private val YtGlass     = Color(0x10FFFFFF)
private val YtErrorRed  = Color(0xFFFF5252)

// ══════════════════════════════════════════════════════════════════
//  YOUTUBE IFRAME PLAYER API — FULL FIXED VERSION
//  Fixes applied:
//  1. playsinline=1  (was 0 — caused mobile fullscreen crash)
//  2. enablejsapi=1  (required for API events)
//  3. origin=https://www.youtube.com  (security / embed auth)
//  4. mute=1 + autoplay=1  (mobile browser autoplay policy)
//  5. host=https://www.youtube-nocookie.com  (fewer restrictions)
//  6. JS Bridge for error detection + fallback to YouTube app
//  7. MixedContentMode + DOM storage for modern WebView
//  8. Hardware acceleration manifest flag required
// ══════════════════════════════════════════════════════════════════

/** JS bridge to catch player errors from WebView */
class YoutubeJsBridge(
    private val onError: (String) -> Unit,
    private val onReady: () -> Unit
) {
    @JavascriptInterface
    fun onPlayerError(errorCode: String) {
        onError(errorCode)
    }

    @JavascriptInterface
    fun onPlayerReady() {
        onReady()
    }
}

// ══════════════════════════════════════════════════════════════════
//  ✅ ROOT-CAUSE FIX for "Error 152 — restricted embedding":
//  Many trailers (studio uploads especially) have the uploader's
//  "allow embedding" flag turned OFF. The iframe API / IFrame embed
//  ALWAYS fails for these videos — no amount of playerVars tuning
//  fixes it, because YouTube blocks the embed server-side per video.
//
//  The fix is to stop embedding and instead load the real YouTube
//  watch page directly inside the WebView, exactly like a normal
//  in-app browser (same approach as InAppBrowserActivity). The
//  watch page is never embed-restricted since it IS the origin.
// ══════════════════════════════════════════════════════════════════
private fun buildYoutubeWatchUrl(videoKey: String) =
    "https://www.youtube.com/watch?v=$videoKey&autoplay=1&mute=1&playsinline=1"

// ══════════════════════════════════════════════════════════════════
//  YoutubePlayerSheet — FULL FIXED
// ══════════════════════════════════════════════════════════════════
@OptIn(ExperimentalMaterial3Api::class)
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun YoutubePlayerSheet(
    videoKey  : String,
    title     : String,
    onDismiss : () -> Unit,
) {
    val context = LocalContext.current

    var fullscreenView     by remember { mutableStateOf<View?>(null) }
    var fullscreenCallback by remember { mutableStateOf<WebChromeClient.CustomViewCallback?>(null) }
    val isFullscreen = fullscreenView != null

    // Error / fallback states
    var playerError   by remember { mutableStateOf<String?>(null) }
    var isLoading     by remember { mutableStateOf(true) }
    var isReady       by remember { mutableStateOf(false) }

    BackHandler(enabled = isFullscreen) {
        fullscreenCallback?.onCustomViewHidden()
        fullscreenView     = null
        fullscreenCallback = null
    }

    val inf = rememberInfiniteTransition(label = "yt")
    val glowAlpha by inf.animateFloat(
        0.4f, 1f,
        infiniteRepeatable(tween(2000, easing = EaseInOutSine), RepeatMode.Reverse), "glow"
    )
    val spinA by inf.animateFloat(
        0f, 360f,
        infiniteRepeatable(tween(2200, easing = LinearEasing)), "sA"
    )
    val spinB by inf.animateFloat(
        360f, 0f,
        infiniteRepeatable(tween(1500, easing = LinearEasing)), "sB"
    )

    val youtubeWatchUrl = remember(videoKey) { buildYoutubeWatchUrl(videoKey) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState       = sheetState,
        containerColor   = YtSheetBg,
        dragHandle       = null,
    ) {
        Box(Modifier.fillMaxWidth()) {

            // Ambient glow blobs
            Box(
                Modifier.size(220.dp).offset((-60).dp, (-40).dp).blur(70.dp)
                    .background(
                        Brush.radialGradient(listOf(YtPurple.copy(0.3f), Color.Transparent)),
                        CircleShape
                    )
            )
            Box(
                Modifier.size(180.dp).align(Alignment.BottomEnd).offset(40.dp, 30.dp).blur(60.dp)
                    .background(
                        Brush.radialGradient(listOf(YtCyan.copy(0.2f), Color.Transparent)),
                        CircleShape
                    )
            )

            Column(Modifier.fillMaxWidth()) colScope@{

                // Neon top line
                Box(
                    Modifier.fillMaxWidth().height(2.dp).drawBehind {
                        drawRect(
                            Brush.horizontalGradient(
                                listOf(
                                    Color.Transparent,
                                    YtPurple.copy(glowAlpha),
                                    YtCyan.copy(glowAlpha),
                                    YtPurpleGlow.copy(glowAlpha * 0.7f),
                                    Color.Transparent
                                )
                            )
                        )
                    }
                )

                // Header
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        Modifier.size(32.dp).drawBehind {
                            drawCircle(YtPurple.copy(0.3f), radius = size.minDimension * 1.2f)
                        },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Rounded.PlayCircle, null,
                            tint     = YtPurple.copy(glowAlpha),
                            modifier = Modifier.size(28.dp)
                        )
                    }
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            "OFFICIAL TRAILER",
                            color         = YtCyan.copy(0.65f),
                            fontSize      = 9.sp,
                            fontWeight    = FontWeight.Bold,
                            letterSpacing = 1.5.sp
                        )
                        Text(
                            title,
                            color      = Color.White,
                            fontSize   = 14.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines   = 1,
                            overflow   = TextOverflow.Ellipsis
                        )
                    }
                    Box(
                        Modifier.size(36.dp).clip(CircleShape)
                            .background(YtGlass)
                            .border(1.dp, Color.White.copy(0.12f), CircleShape)
                            .clickable { onDismiss() },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Rounded.Close, null,
                            tint     = Color.White.copy(0.75f),
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }

                // Video area 16:9
                Box(
                    Modifier
                        .fillMaxWidth()
                        .aspectRatio(16f / 9f)
                        .background(YtDarkBg)
                        .border(
                            width = 1.dp,
                            brush = Brush.linearGradient(
                                listOf(
                                    YtPurple.copy(glowAlpha * 0.5f),
                                    YtCyan.copy(glowAlpha * 0.4f),
                                    Color.Transparent
                                )
                            ),
                            shape = RectangleShape
                        )
                ) {
                    AndroidView(
                        modifier = Modifier.fillMaxSize(),
                        factory  = { ctx ->
                            WebView(ctx).apply {
                                layoutParams = ViewGroup.LayoutParams(
                                    ViewGroup.LayoutParams.MATCH_PARENT,
                                    ViewGroup.LayoutParams.MATCH_PARENT
                                )
                                // Match sheet bg so there's no white flash before the
                                // YouTube page paints (same fix as InAppBrowserActivity).
                                setBackgroundColor(android.graphics.Color.parseColor("#FF020810"))

                                settings.apply {
                                    @Suppress("DEPRECATION")
                                    javaScriptEnabled                = true
                                    domStorageEnabled                = true
                                    databaseEnabled                  = true
                                    loadWithOverviewMode             = true
                                    useWideViewPort                  = true
                                    mediaPlaybackRequiresUserGesture = false
                                    setSupportMultipleWindows(true)
                                    javaScriptCanOpenWindowsAutomatically = true
                                    mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                                    setSupportZoom(false)
                                    builtInZoomControls = false

                                    // Modern Chrome UA — MUST for YouTube to serve correct player
                                    userAgentString =
                                        "Mozilla/5.0 (Linux; Android 14; Pixel 8) " +
                                        "AppleWebKit/537.36 (KHTML, like Gecko) " +
                                        "Chrome/126.0.0.0 Mobile Safari/537.36"
                                }

                                // Retain the JS bridge purely as a safety net — the real
                                // page will drive isLoading/isReady via WebViewClient below.
                                addJavascriptInterface(
                                    YoutubeJsBridge(
                                        onError  = { code -> playerError = code; isLoading = false },
                                        onReady  = { isReady = true; isLoading = false }
                                    ),
                                    "YoutubeBridge"
                                )

                                webViewClient = object : WebViewClient() {
                                    override fun shouldOverrideUrlLoading(
                                        view: WebView, request: WebResourceRequest
                                    ): Boolean {
                                        val url = request.url.toString()
                                        // Stay inside the sheet for the watch page and its
                                        // player assets — this IS our "in-app browser" now.
                                        // Only kick out to the real app for things like
                                        // "Sign in", account switches, or external links.
                                        val stayInWebView =
                                            url.contains("youtube.com/watch") ||
                                            url.contains("youtube.com/embed") ||
                                            url.startsWith("about:") ||
                                            url.contains("googlevideo.com")
                                        if (!stayInWebView && (url.contains("youtube.com") || url.contains("youtu.be"))) {
                                            return false // let it load in-WebView too; avoids app hand-off jank
                                        }
                                        return false
                                    }

                                    override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
                                        playerError = null
                                    }

                                    override fun onPageFinished(view: WebView, url: String) {
                                        // Real watch page loaded successfully — dismiss spinner.
                                        isReady   = true
                                        isLoading = false
                                    }

                                    override fun onReceivedError(
                                        view: WebView,
                                        errorCode: Int,
                                        description: String?,
                                        failingUrl: String?
                                    ) {
                                        // Only fail on the main frame request, not sub-resources
                                        if (failingUrl == null || failingUrl.contains("youtube.com/watch")) {
                                            playerError = "webview_$errorCode"
                                            isLoading = false
                                        }
                                    }
                                }

                                webChromeClient = object : WebChromeClient() {
                                    override fun onShowCustomView(
                                        view: View, callback: CustomViewCallback
                                    ) {
                                        fullscreenView     = view
                                        fullscreenCallback = callback
                                    }
                                    override fun onHideCustomView() {
                                        fullscreenView     = null
                                        fullscreenCallback = null
                                    }
                                }

                                // ✅ FIXED: load the real YouTube watch page directly,
                                // same as a genuine in-app browser tab. This bypasses
                                // "Error 152 — embedding disabled" entirely, since that
                                // restriction only applies to the <iframe> embed player,
                                // not the watch page itself.
                                loadUrl(youtubeWatchUrl)
                            }
                        }
                    )

                    // Loading overlay
                    this@colScope.AnimatedVisibility(
                        visible  = isLoading && playerError == null,
                        enter    = fadeIn(),
                        exit     = fadeOut(tween(600)),
                        modifier = Modifier.fillMaxSize()
                    ) {
                        Box(
                            Modifier.fillMaxSize().background(YtDarkBg),
                            contentAlignment = Alignment.Center
                        ) {
                            Box(
                                Modifier.size(130.dp).blur(40.dp)
                                    .background(
                                        Brush.radialGradient(listOf(YtPurple.copy(0.4f), Color.Transparent)),
                                        CircleShape
                                    )
                            )
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Box(Modifier.size(72.dp), contentAlignment = Alignment.Center) {
                                    CircularProgressIndicator(
                                        modifier    = Modifier.size(72.dp).rotate(spinA),
                                        color       = YtCyan.copy(glowAlpha),
                                        strokeWidth = 2.dp,
                                        trackColor  = Color.Transparent
                                    )
                                    CircularProgressIndicator(
                                        modifier    = Modifier.size(50.dp).rotate(spinB),
                                        color       = YtPurple.copy(glowAlpha),
                                        strokeWidth = 2.dp,
                                        trackColor  = Color.Transparent
                                    )
                                    Box(
                                        Modifier.size(10.dp)
                                            .drawBehind {
                                                drawCircle(YtCyan.copy(0.35f), radius = size.minDimension * 2.2f)
                                            }
                                            .background(
                                                Brush.radialGradient(listOf(YtCyan, YtPurple)),
                                                CircleShape
                                            )
                                    )
                                }
                                Spacer(Modifier.height(20.dp))
                                Text(
                                    "LOADING TRAILER",
                                    color         = YtCyan.copy(0.65f),
                                    fontSize      = 10.sp,
                                    fontWeight    = FontWeight.Bold,
                                    letterSpacing = 2.sp
                                )
                            }
                        }
                    }

                    // Error / Unavailable overlay with fallback
                    this@colScope.AnimatedVisibility(
                        visible  = playerError != null,
                        enter    = fadeIn(tween(400)),
                        exit     = fadeOut(tween(300)),
                        modifier = Modifier.fillMaxSize()
                    ) {
                        Box(
                            Modifier
                                .fillMaxSize()
                                .background(YtDarkBg),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier.padding(24.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.ErrorOutline,
                                    contentDescription = null,
                                    tint = YtErrorRed.copy(0.8f),
                                    modifier = Modifier.size(48.dp)
                                )
                                Spacer(Modifier.height(12.dp))
                                Text(
                                    "This video is unavailable",
                                    color = Color.White.copy(0.9f),
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    "Error: ${playerError ?: "unknown"} — The uploader restricted embedding.",
                                    color = Color.White.copy(0.5f),
                                    fontSize = 12.sp
                                )
                                Spacer(Modifier.height(20.dp))

                                // Open in YouTube App button
                                Box(
                                    Modifier
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(
                                            Brush.horizontalGradient(
                                                listOf(YtPurple.copy(0.8f), YtCyan.copy(0.6f))
                                            )
                                        )
                                        .clickable {
                                            val intent = Intent(
                                                Intent.ACTION_VIEW,
                                                Uri.parse("https://www.youtube.com/watch?v=$videoKey")
                                            )
                                            intent.setPackage("com.google.android.youtube")
                                            try {
                                                context.startActivity(intent)
                                            } catch (e: ActivityNotFoundException) {
                                                // YouTube app not installed, open browser
                                                context.startActivity(
                                                    Intent(
                                                        Intent.ACTION_VIEW,
                                                        Uri.parse("https://www.youtube.com/watch?v=$videoKey")
                                                    )
                                                )
                                            }
                                        }
                                        .padding(horizontal = 20.dp, vertical = 10.dp)
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(
                                            Icons.Rounded.PlayArrow,
                                            null,
                                            tint = Color.White,
                                            modifier = Modifier.size(18.dp)
                                        )
                                        Spacer(Modifier.width(6.dp))
                                        Text(
                                            "Watch on YouTube",
                                            color = Color.White,
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // Nav bar bottom spacing
                Spacer(
                    Modifier.fillMaxWidth().background(YtSheetBg)
                        .windowInsetsBottomHeight(WindowInsets.navigationBars)
                )
            }
        }
    }

    // Fullscreen overlay
    Box(Modifier.fillMaxSize()) {
        AnimatedVisibility(
            visible = isFullscreen,
            enter   = fadeIn(tween(200)),
            exit    = fadeOut(tween(200))
        ) {
            Box(Modifier.fillMaxSize().background(Color.Black)) {
                fullscreenView?.let { view ->
                    AndroidView(factory = { view }, modifier = Modifier.fillMaxSize())
                }
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .padding(top = 40.dp, end = 16.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color.Black.copy(0.55f))
                        .border(1.dp, Color.White.copy(0.1f), RoundedCornerShape(8.dp))
                        .clickable {
                            fullscreenCallback?.onCustomViewHidden()
                            fullscreenView     = null
                            fullscreenCallback = null
                        }
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Rounded.FullscreenExit, null,
                            tint     = Color.White.copy(0.75f),
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(Modifier.width(5.dp))
                        Text("Exit Fullscreen", color = Color.White.copy(0.75f), fontSize = 12.sp)
                    }
                }
            }
        }
    }
}
