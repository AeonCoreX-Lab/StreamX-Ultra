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
private val Cyan          = Color(0xFF00E5FF)
private val Purple        = Color(0xFF7C4DFF)
private val PurpleGlow    = Color(0xFFB388FF)
private val SheetBg       = Color(0xFF08080F)
private val DarkBg        = Color(0xFF020810)
// FIX: renamed from GlassWhite → SheetGlass to avoid conflict with the
// package-level 'val GlassWhite' already defined in MovieScreen.kt
// (same package com.aeoncorex.streamx.ui.movie). Two top-level vals with
// the same name in the same package cause "Conflicting declarations".
private val SheetGlass    = Color(0x10FFFFFF)

/**
 * YoutubePlayerSheet
 *
 * Full-featured in-app YouTube trailer player.
 *  - ModalBottomSheet slides up with video ready to play
 *  - YouTube IFrame embed — no extra dependency, just WebView
 *  - Fullscreen support: YouTube's own fullscreen button works natively
 *  - Futuristic design: neon accent, dark glass, ambient glow
 *
 * Usage in MovieDetailsScreen:
 *   YoutubePlayerSheet(
 *       videoKey  = movie.trailerKey!!,
 *       title     = movie.basic.title,
 *       onDismiss = { showTrailerSheet = false }
 *   )
 */
@OptIn(ExperimentalMaterial3Api::class)
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun YoutubePlayerSheet(
    videoKey  : String,
    title     : String,
    onDismiss : () -> Unit,
) {
    // ── Fullscreen state (YouTube fullscreen button) ───────────────
    var fullscreenView     by remember { mutableStateOf<View?>(null) }
    var fullscreenCallback by remember { mutableStateOf<WebChromeClient.CustomViewCallback?>(null) }

    val isFullscreen = fullscreenView != null

    // Close fullscreen on back press
    BackHandler(enabled = isFullscreen) {
        fullscreenCallback?.onCustomViewHidden()
        fullscreenView     = null
        fullscreenCallback = null
    }

    // Infinite animations
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

    // Loading state — hide after page loads
    var isLoading by remember { mutableStateOf(true) }

    // YouTube embed URL — autoplay, no ads sidebar, minimal branding
    val embedUrl = remember(videoKey) {
        "https://www.youtube.com/embed/$videoKey" +
        "?autoplay=1" +
        "&rel=0" +
        "&modestbranding=1" +
        "&showinfo=0" +
        "&fs=1" +            // allow fullscreen button
        "&playsinline=0" +   // let YouTube handle fullscreen natively
        "&iv_load_policy=3"  // hide annotations
    }

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    // ── Bottom Sheet ──────────────────────────────────────────────
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState       = sheetState,
        containerColor   = SheetBg,
        dragHandle       = null,        // custom drag area below
        // FIX: removed 'windowInsets = WindowInsets(0)' — this parameter
        // does not exist in the Material3 ModalBottomSheet version used here.
        // Use windowInsetsBottomHeight() on a Spacer below instead (already done).
    ) {
        Box(Modifier.fillMaxWidth()) {

            // Ambient purple glow top-left
            Box(
                Modifier.size(220.dp).offset((-60).dp, (-40).dp).blur(70.dp)
                    .background(
                        Brush.radialGradient(listOf(Purple.copy(0.3f), Color.Transparent)),
                        CircleShape
                    )
            )
            // Ambient cyan glow bottom-right
            Box(
                Modifier.size(180.dp).align(Alignment.BottomEnd).offset(40.dp, 30.dp).blur(60.dp)
                    .background(
                        Brush.radialGradient(listOf(Cyan.copy(0.2f), Color.Transparent)),
                        CircleShape
                    )
            )

            Column(Modifier.fillMaxWidth()) {

                // ── Neon top accent line ──────────────────────────
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(2.dp)
                        .drawBehind {
                            drawRect(
                                brush = Brush.horizontalGradient(
                                    listOf(
                                        Color.Transparent,
                                        Purple.copy(glowAlpha),
                                        Cyan.copy(glowAlpha),
                                        PurpleGlow.copy(glowAlpha * 0.7f),
                                        Color.Transparent
                                    )
                                )
                            )
                        }
                )

                // ── Header row ────────────────────────────────────
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Play icon with glow
                    Box(
                        Modifier.size(32.dp)
                            .drawBehind {
                                drawCircle(Purple.copy(0.3f), radius = size.minDimension * 1.2f)
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Rounded.PlayCircle, null,
                            tint     = Purple.copy(glowAlpha),
                            modifier = Modifier.size(28.dp)
                        )
                    }
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            "OFFICIAL TRAILER",
                            color         = Cyan.copy(0.65f),
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
                    // Close button — FIX: use renamed SheetGlass instead of GlassWhite
                    Box(
                        Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(SheetGlass)
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

                // ── Video player area (16:9) ──────────────────────
                Box(
                    Modifier
                        .fillMaxWidth()
                        .aspectRatio(16f / 9f)
                        .background(DarkBg)
                        // Neon border frame around the video
                        .border(
                            width = 1.dp,
                            brush = Brush.linearGradient(
                                listOf(
                                    Purple.copy(glowAlpha * 0.5f),
                                    Cyan.copy(glowAlpha * 0.4f),
                                    Color.Transparent
                                )
                            ),
                            shape = RectangleShape
                        )
                ) {
                    // YouTube WebView
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
                                    mediaPlaybackRequiresUserGesture = false // autoplay
                                    setSupportMultipleWindows(true)
                                    // Chrome user agent so YouTube serves proper embed
                                    userAgentString =
                                        "Mozilla/5.0 (Linux; Android 14; Pixel 8) " +
                                        "AppleWebKit/537.36 (KHTML, like Gecko) " +
                                        "Chrome/124.0.0.0 Mobile Safari/537.36"
                                }
                                webViewClient = object : WebViewClient() {
                                    override fun shouldOverrideUrlLoading(
                                        view: WebView, request: WebResourceRequest
                                    ): Boolean = false // let WebView handle all YouTube redirects

                                    override fun onPageFinished(view: WebView, url: String) {
                                        isLoading = false
                                    }
                                }
                                webChromeClient = object : WebChromeClient() {
                                    // ✅ YouTube fullscreen button → show native fullscreen
                                    override fun onShowCustomView(
                                        view     : View,
                                        callback : CustomViewCallback
                                    ) {
                                        fullscreenView     = view
                                        fullscreenCallback = callback
                                    }
                                    override fun onHideCustomView() {
                                        fullscreenView     = null
                                        fullscreenCallback = null
                                    }
                                }
                                loadUrl(embedUrl)
                            }
                        }
                    )

                    // Loading overlay (dual spinning rings)
                    // FIX: AnimatedVisibility here is inside a Box scope — OK, no ColumnScope conflict
                    androidx.compose.animation.AnimatedVisibility(
                        visible  = isLoading,
                        enter    = fadeIn(),
                        exit     = fadeOut(tween(600)),
                        modifier = Modifier.fillMaxSize()
                    ) {
                        Box(
                            Modifier.fillMaxSize().background(DarkBg),
                            contentAlignment = Alignment.Center
                        ) {
                            // Purple ambient blob
                            Box(
                                Modifier.size(140.dp).blur(40.dp)
                                    .background(
                                        Brush.radialGradient(listOf(Purple.copy(0.4f), Color.Transparent)),
                                        CircleShape
                                    )
                            )
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Box(Modifier.size(72.dp), contentAlignment = Alignment.Center) {
                                    CircularProgressIndicator(
                                        modifier    = Modifier.size(72.dp).rotate(spinA),
                                        color       = Cyan.copy(glowAlpha),
                                        strokeWidth = 2.dp,
                                        trackColor  = Color.Transparent
                                    )
                                    CircularProgressIndicator(
                                        modifier    = Modifier.size(50.dp).rotate(spinB),
                                        color       = Purple.copy(glowAlpha),
                                        strokeWidth = 2.dp,
                                        trackColor  = Color.Transparent
                                    )
                                    // Center dot
                                    Box(
                                        Modifier.size(10.dp)
                                            .drawBehind {
                                                drawCircle(Cyan.copy(0.35f), radius = size.minDimension * 2.2f)
                                            }
                                            .background(
                                                Brush.radialGradient(listOf(Cyan, Purple)),
                                                CircleShape
                                            )
                                    )
                                }
                                Spacer(Modifier.height(20.dp))
                                Text(
                                    "LOADING TRAILER",
                                    color         = Cyan.copy(0.65f),
                                    fontSize      = 10.sp,
                                    fontWeight    = FontWeight.Bold,
                                    letterSpacing = 2.sp
                                )
                            }
                        }
                    }
                }

                // ── Bottom padding for gesture nav bar ────────────
                Spacer(
                    Modifier
                        .fillMaxWidth()
                        .background(SheetBg)
                        .windowInsetsBottomHeight(WindowInsets.navigationBars)
                )
            }
        }
    }

    // ════════════════════════════════════════════════════════════════
    //  FULLSCREEN OVERLAY
    //  Shown when user taps YouTube's fullscreen button
    // ════════════════════════════════════════════════════════════════
    // FIX: wrapped in Box so the composable is inside a BoxScope, not
    // the implicit ColumnScope that caused "cannot be called in this context
    // with an implicit receiver". Using fully-qualified name also resolves
    // the overload ambiguity between ColumnScope.AnimatedVisibility and
    // the standalone variant imported via 'import androidx.compose.animation.*'.
    Box(modifier = Modifier.fillMaxSize()) {
        androidx.compose.animation.AnimatedVisibility(
            visible = isFullscreen,
            enter   = fadeIn(tween(200)),
            exit    = fadeOut(tween(200))
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black)
            ) {
                fullscreenView?.let { view ->
                    AndroidView(
                        factory  = { view },
                        modifier = Modifier.fillMaxSize()
                    )
                }

                // Subtle exit hint
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
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Rounded.FullscreenExit, null,
                            tint     = Color.White.copy(0.75f),
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(Modifier.width(5.dp))
                        Text(
                            "Exit Fullscreen",
                            color    = Color.White.copy(0.75f),
                            fontSize = 12.sp
                        )
                    }
                }
            }
        }
    }
}
