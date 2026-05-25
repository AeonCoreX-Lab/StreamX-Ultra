package com.aeoncorex.streamx.ui.movie

import android.annotation.SuppressLint
import android.view.View
import android.view.ViewGroup
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

// ══════════════════════════════════════════════════════════════════
//  IFrame Player API HTML
//  KEY FIX: loadDataWithBaseURL("https://www.youtube.com", ...)
//  YouTube allows embed only when the base URL is its own domain.
//  Directly calling loadUrl("youtube.com/embed/...") in a WebView
//  triggers Error 153 because YouTube detects the WebView UA and
//  refuses the "Video player configuration".
// ══════════════════════════════════════════════════════════════════
private fun buildYoutubeHtml(videoKey: String) = """
<!DOCTYPE html>
<html>
<head>
<meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0">
<style>
  * { margin: 0; padding: 0; box-sizing: border-box; }
  html, body {
    width: 100%; height: 100%;
    background: #000;
    overflow: hidden;
  }
  #player {
    position: absolute;
    top: 0; left: 0;
    width: 100%; height: 100%;
  }
  iframe {
    width: 100% !important;
    height: 100% !important;
    border: none;
  }
</style>
</head>
<body>
<div id="player"></div>
<script src="https://www.youtube.com/iframe_api"></script>
<script>
  var player;
  function onYouTubeIframeAPIReady() {
    player = new YT.Player('player', {
      videoId: '$videoKey',
      playerVars: {
        autoplay    : 1,
        rel         : 0,
        modestbranding: 1,
        showinfo    : 0,
        fs          : 1,
        playsinline : 0,
        iv_load_policy: 3,
        controls    : 1,
        cc_load_policy: 0,
        disablekb   : 0
      },
      events: {
        onReady: function(e) { e.target.playVideo(); }
      }
    });
  }
</script>
</body>
</html>
""".trimIndent()

// ══════════════════════════════════════════════════════════════════
//  YoutubePlayerSheet
// ══════════════════════════════════════════════════════════════════
@OptIn(ExperimentalMaterial3Api::class)
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun YoutubePlayerSheet(
    videoKey  : String,
    title     : String,
    onDismiss : () -> Unit,
) {
    var fullscreenView     by remember { mutableStateOf<View?>(null) }
    var fullscreenCallback by remember { mutableStateOf<WebChromeClient.CustomViewCallback?>(null) }
    val isFullscreen = fullscreenView != null

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

    var isLoading by remember { mutableStateOf(true) }

    // Pre-build HTML so it doesn't rebuild on recomposition
    val youtubeHtml = remember(videoKey) { buildYoutubeHtml(videoKey) }

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

            Column(Modifier.fillMaxWidth()) {

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
                                settings.apply {
                                    @Suppress("DEPRECATION")
                                    javaScriptEnabled                = true
                                    domStorageEnabled                = true
                                    loadWithOverviewMode             = true
                                    useWideViewPort                  = true
                                    mediaPlaybackRequiresUserGesture = false
                                    setSupportMultipleWindows(true)
                                    // Chrome UA — keeps YouTube from
                                    // serving a degraded or blocked player
                                    userAgentString =
                                        "Mozilla/5.0 (Linux; Android 14; Pixel 8) " +
                                        "AppleWebKit/537.36 (KHTML, like Gecko) " +
                                        "Chrome/124.0.0.0 Mobile Safari/537.36"
                                }
                                webViewClient = object : WebViewClient() {
                                    override fun shouldOverrideUrlLoading(
                                        view: WebView, request: WebResourceRequest
                                    ): Boolean = false

                                    override fun onPageFinished(view: WebView, url: String) {
                                        // Small delay so the IFrame API finishes
                                        // initialising before we hide the loader
                                        view.postDelayed({ isLoading = false }, 1200)
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
                                // ✅ THE FIX — load HTML with youtube.com as base URL
                                // YouTube's IFrame API checks document.domain, so the
                                // base URL must be https://www.youtube.com, otherwise
                                // the player throws "Video player configuration error".
                                loadDataWithBaseURL(
                                    "https://www.youtube.com",
                                    youtubeHtml,
                                    "text/html",
                                    "UTF-8",
                                    null
                                )
                            }
                        }
                    )

                    // Loading overlay
                    androidx.compose.animation.AnimatedVisibility(
                        visible  = isLoading,
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
        androidx.compose.animation.AnimatedVisibility(
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
