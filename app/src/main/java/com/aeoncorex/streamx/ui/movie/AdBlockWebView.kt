package com.aeoncorex.streamx.ui.movie

import android.annotation.SuppressLint
import android.app.Activity
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.view.ViewGroup
import android.webkit.*
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.navigation.NavController

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun AdBlockWebView(url: String, navController: NavController) {
    val context = LocalContext.current
    var isLoading by remember { mutableStateOf(true) }
    var webView: WebView? = remember { null }

    // --- 1. AUTO-ROTATE SYSTEM ---
    // Automatically enable sensor rotation (Landscape/Portrait) when player opens
    // and revert to Portrait when player closes.
    DisposableEffect(Unit) {
        val activity = context as? Activity
        val originalOrientation = activity?.requestedOrientation ?: ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        
        // Force Auto-Rotate (Sensor)
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR

        onDispose {
            // Restore to Portrait (or previous state)
            activity?.requestedOrientation = originalOrientation
        }
    }

    // Handle Back Press (Go back in browser history or close player)
    BackHandler {
        if (webView?.canGoBack() == true) {
            webView?.goBack()
        } else {
            navController.popBackStack()
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            factory = { ctx ->
                WebView(ctx).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                    
                    settings.apply {
                        javaScriptEnabled = true
                        domStorageEnabled = true
                        databaseEnabled = true
                        useWideViewPort = true
                        loadWithOverviewMode = true
                        mediaPlaybackRequiresUserGesture = false // Allow Autoplay
                        mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                        // Spoof User Agent to look like a desktop PC (Often bypasses mobile ads)
                        userAgentString = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/110.0.0.0 Safari/537.36"
                        
                        // Prevent Multiple Windows (Popups)
                        setSupportMultipleWindows(false)
                        javaScriptCanOpenWindowsAutomatically = false
                    }

                    // --- 2. ULTIMATE AD BLOCKING CLIENT ---
                    webViewClient = object : WebViewClient() {
                        // Massive list of Ad/Tracker Domains
                        private val adHosts = setOf(
                            "doubleclick.net", "googlesyndication.com", "google-analytics.com",
                            "adnxs.com", "popads.net", "popcash.net", "adsterra.com",
                            "mc.yandex.ru", "bebi.com", "histats.com", "onclickmedium.com",
                            "propellerads.com", "ad-maven.com", "juicyads.com",
                            "facebook.com", "twitter.com", "linkedin.com", // Block Social Trackers
                            "bet365.com", "1xbet.com" // Block Gambling Ads
                        )

                        // Whitelist: ONLY allow these domains to load main content
                        private val allowedHosts = listOf(
                            "vidsrc", "multiembed", "superembed", "2embed", "youtube", "webtor", "google.com/recaptcha"
                        )

                        // A. Block Resources (Images/Scripts) from Ad Servers
                        override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                            val requestUrl = request?.url.toString().lowercase()
                            
                            // If URL contains any ad host -> BLOCK IT (Return empty response)
                            if (adHosts.any { requestUrl.contains(it) }) {
                                return WebResourceResponse("text/plain", "utf-8", null)
                            }
                            return super.shouldInterceptRequest(view, request)
                        }

                        // B. Strict Redirect Blocking (The "Firewall")
                        @Deprecated("Deprecated in Java")
                        override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                            if (url == null) return false

                            val cleanUrl = url.lowercase()
                            
                            // CHECK: Is this a valid video player URL?
                            val isAllowed = allowedHosts.any { cleanUrl.contains(it) }

                            return if (isAllowed) {
                                false // Allow loading (It's the movie/player)
                            } else {
                                true // BLOCK redirect (It's likely a popup/ad redirect)
                            }
                        }

                        // C. Inject CSS/JS to remove Ad Elements visually
                        override fun onPageFinished(view: WebView?, url: String?) {
                            isLoading = false
                            val js = """
                                javascript:(function() {
                                    // 1. Remove Ad Elements by Class/ID
                                    var style = document.createElement('style');
                                    style.innerHTML = 'div[class*="ad"], iframe[src*="ads"], .popup, .overlay, #ad, .ads, .banner-ads { display: none !important; }';
                                    document.head.appendChild(style);
                                    
                                    // 2. Remove "Click to Play" overlays often found in embed players
                                    var overlays = document.querySelectorAll('div[style*="z-index: 999"]');
                                    overlays.forEach(el => el.remove());
                                    
                                    // 3. Auto-Click Play button if found
                                    var video = document.querySelector('video');
                                    if(video) { video.play(); }
                                })()
                            """
                            view?.evaluateJavascript(js, null)
                        }
                    }

                    webChromeClient = object : WebChromeClient() {
                        override fun onProgressChanged(view: WebView?, newProgress: Int) {
                            if (newProgress > 80) isLoading = false
                        }
                    }

                    loadUrl(url)
                    webView = this
                }
            },
            modifier = Modifier.fillMaxSize()
        )

        // Loading Indicator
        if (isLoading) {
            CircularProgressIndicator(
                modifier = Modifier.align(Alignment.Center),
                color = Color.Cyan
            )
        }

        // Close Button (Top Right)
        IconButton(
            onClick = { navController.popBackStack() },
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(16.dp)
                .background(Color.Black.copy(0.6f), androidx.compose.foundation.shape.CircleShape)
        ) {
            Icon(Icons.Default.Close, null, tint = Color.White)
        }
    }
}
