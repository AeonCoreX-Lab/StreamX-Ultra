package com.aeoncorex.streamx.ui.movie

import android.annotation.SuppressLint
import android.app.Activity
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
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
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.navigation.NavController

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun AdBlockWebView(url: String, navController: NavController) {
    val context = LocalContext.current
    var isLoading by remember { mutableStateOf(true) }
    var webView: WebView? = remember { null }
    
    // Custom View for HTML5 Fullscreen (Video Player's maximize button)
    var customView by remember { mutableStateOf<View?>(null) }
    var customViewCallback by remember { mutableStateOf<WebChromeClient.CustomViewCallback?>(null) }

    // --- FULLSCREEN & ORIENTATION LOGIC ---
    DisposableEffect(Unit) {
        val activity = context as? Activity
        val window = activity?.window
        val originalOrientation = activity?.requestedOrientation ?: ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        
        // 1. Force Landscape
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE

        // 2. Hide System Bars (Immersive Mode)
        if (window != null) {
            val insetsController = WindowCompat.getInsetsController(window, window.decorView)
            insetsController.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            insetsController.hide(WindowInsetsCompat.Type.systemBars())
        }

        onDispose {
            // Restore Orientation
            activity?.requestedOrientation = originalOrientation
            
            // Restore System Bars
            if (window != null) {
                val insetsController = WindowCompat.getInsetsController(window, window.decorView)
                insetsController.show(WindowInsetsCompat.Type.systemBars())
            }
        }
    }

    // Handle Back Press
    BackHandler {
        if (customView != null) {
            // Exit HTML5 Fullscreen
            customViewCallback?.onCustomViewHidden()
            customView = null
        } else if (webView?.canGoBack() == true) {
            webView?.goBack()
        } else {
            navController.popBackStack()
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        // If HTML5 Fullscreen is active, show that instead of WebView
        if (customView != null) {
            AndroidView(
                factory = { 
                    android.widget.FrameLayout(it).apply { 
                        addView(customView, ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT, 
                            ViewGroup.LayoutParams.MATCH_PARENT
                        )) 
                    } 
                },
                modifier = Modifier.fillMaxSize()
            )
        } else {
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
                            mediaPlaybackRequiresUserGesture = false
                            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                            userAgentString = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
                            setSupportMultipleWindows(true) // Required for some redirects to be caught
                            javaScriptCanOpenWindowsAutomatically = false
                        }

                        // --- 2. ULTIMATE AD BLOCKING CLIENT ---
                        webViewClient = object : WebViewClient() {
                            // Enhanced Blocklist (Includes Gambling/Betting)
                            private val adHosts = setOf(
                                "doubleclick.net", "googlesyndication.com", "google-analytics.com",
                                "adnxs.com", "popads.net", "popcash.net", "adsterra.com",
                                "mc.yandex.ru", "bebi.com", "histats.com", "onclickmedium.com",
                                "propellerads.com", "ad-maven.com", "juicyads.com",
                                "1win", "bet365", "1xbet", "gambling", "casino", "sportybet", // Specific for your screenshot
                                "facebook.com", "twitter.com"
                            )

                            private val allowedHosts = listOf(
                                "vidsrc", "multiembed", "superembed", "2embed", "youtube", "webtor", "recaptcha"
                            )

                            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                                val requestUrl = request?.url.toString().lowercase()
                                if (adHosts.any { requestUrl.contains(it) }) {
                                    return WebResourceResponse("text/plain", "utf-8", null)
                                }
                                return super.shouldInterceptRequest(view, request)
                            }

                            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                                val url = request?.url.toString().lowercase()
                                val isAllowed = allowedHosts.any { url.contains(it) }
                                return !isAllowed // Block if not allowed
                            }

                            override fun onPageFinished(view: WebView?, url: String?) {
                                isLoading = false
                                // --- AGGRESSIVE AD REMOVAL SCRIPT ---
                                // Runs repeatedly to kill ads that load AFTER the page starts
                                val js = """
                                    javascript:(function() {
                                        function killAds() {
                                            // 1. Remove common ad overlays by ID/Class
                                            var selectors = [
                                                'div[class*="ad"]', 'iframe[src*="ads"]', '.popup', '.overlay', 
                                                '#ad', '.ads', '.banner-ads', 'div[id*="script"]',
                                                'div[style*="z-index: 999"]', 'div[style*="z-index: 2147483647"]'
                                            ];
                                            
                                            // 2. Specific logic for "1win" / "Skip" ads (as seen in screenshot)
                                            var allDivs = document.querySelectorAll('div, span, a');
                                            allDivs.forEach(el => {
                                                if (el.innerText && (
                                                    el.innerText.includes('Skip after') || 
                                                    el.innerText.includes('Skip Ad') || 
                                                    el.innerText.toLowerCase().includes('1win')
                                                )) {
                                                    el.remove(); // Delete the element
                                                    // Also try to close closest container
                                                    if(el.closest('div[style*="position: absolute"]')) {
                                                        el.closest('div[style*="position: absolute"]').remove();
                                                    }
                                                }
                                            });

                                            // 3. Remove overlay IFrames (often used for video ads)
                                            var iframes = document.querySelectorAll('iframe');
                                            iframes.forEach(iframe => {
                                                try {
                                                    var src = iframe.src.toLowerCase();
                                                    if (src.includes('bet') || src.includes('win') || src.includes('casino')) {
                                                        iframe.remove();
                                                    }
                                                } catch(e) {}
                                            });

                                            // 4. Force Video Play
                                            var video = document.querySelector('video');
                                            if(video && video.paused) { video.play(); }
                                        }

                                        // Run immediately and then every 500ms for 10 seconds
                                        killAds();
                                        var count = 0;
                                        var interval = setInterval(function() {
                                            killAds();
                                            count++;
                                            if(count > 20) clearInterval(interval); 
                                        }, 500);
                                    })()
                                """
                                view?.evaluateJavascript(js, null)
                            }
                        }

                        // --- 3. HANDLE FULLSCREEN VIDEO PLAYERS ---
                        webChromeClient = object : WebChromeClient() {
                            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                                if (newProgress > 80) isLoading = false
                            }

                            // Triggered when video player hits "Fullscreen" button
                            override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
                                super.onShowCustomView(view, callback)
                                customView = view
                                customViewCallback = callback
                            }

                            override fun onHideCustomView() {
                                super.onHideCustomView()
                                customView = null
                                customViewCallback = null
                            }
                        }

                        loadUrl(url)
                        webView = this
                    }
                },
                modifier = Modifier.fillMaxSize()
            )
        }

        // Loading Indicator
        if (isLoading && customView == null) {
            CircularProgressIndicator(
                modifier = Modifier.align(Alignment.Center),
                color = Color.Cyan
            )
        }

        // Close Button (Only show if not in native HTML fullscreen)
        if (customView == null) {
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
}
