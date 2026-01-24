package com.aeoncorex.streamx.ui.movie

import android.annotation.SuppressLint
import android.app.Activity
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.net.Uri
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
import java.io.ByteArrayInputStream

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun AdBlockWebView(url: String, navController: NavController) {
    val context = LocalContext.current
    var isLoading by remember { mutableStateOf(true) }
    var webView: WebView? = remember { null }

    // Extract the main host dynamically (e.g., "vidsrc.win" from the full URL)
    // This makes it FUTURE PROOF. No need to hardcode server names anymore.
    val initialHost = remember(url) {
        try { Uri.parse(url).host ?: "" } catch (e: Exception) { "" }
    }

    // --- AUTO-ROTATE SYSTEM ---
    DisposableEffect(Unit) {
        val activity = context as? Activity
        val originalOrientation = activity?.requestedOrientation ?: ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        onDispose { activity?.requestedOrientation = originalOrientation }
    }

    BackHandler {
        if (webView?.canGoBack() == true) webView?.goBack() else navController.popBackStack()
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
                        mediaPlaybackRequiresUserGesture = false // AutoPlay ON
                        mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                        
                        // Desktop User Agent blocks many mobile-specific redirects
                        userAgentString = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/115.0.0.0 Safari/537.36"
                        
                        // --- CORE POPUP BLOCKER ---
                        setSupportMultipleWindows(false) // Block window.open()
                        javaScriptCanOpenWindowsAutomatically = false
                    }

                    webViewClient = object : WebViewClient() {
                        // --- 1. THE BLACKLIST (Universal Ad Domains) ---
                        // We block these specific patterns regardless of where they come from.
                        private val adPatterns = listOf(
                            "doubleclick", "googlesyndication", "google-analytics",
                            "adnxs", "popads", "popcash", "adsterra", "propellerads",
                            "ad-maven", "juicyads", "bet365", "1xbet", "mc.yandex",
                            "histats", "bebi", "onclick", "tracker", "pixel", "flyer",
                            "ad.js", "ads.js", "banner", "popup"
                        )

                        // --- 2. RESOURCE INTERCEPTOR (The Firewall) ---
                        // Blocks ad scripts/images BEFORE they load to save data and speed up player.
                        override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                            val requestUrl = request?.url.toString().lowercase()
                            
                            // Block known ad patterns
                            if (adPatterns.any { requestUrl.contains(it) }) {
                                // Return empty response (Blank 0kb file)
                                return WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream("".toByteArray()))
                            }
                            return super.shouldInterceptRequest(view, request)
                        }

                        // --- 3. SMART REDIRECT BLOCKER ---
                        override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                            val nextUrl = request?.url.toString().lowercase()
                            val nextHost = request?.url?.host ?: ""

                            // A. Block Deep Links (App Openers)
                            if (nextUrl.startsWith("intent://") || nextUrl.startsWith("market://") || 
                                nextUrl.startsWith("whatsapp://") || nextUrl.startsWith("telegram://") ||
                                nextUrl.startsWith("shopee://") || nextUrl.startsWith("lazada://")) {
                                return true // BLOCK
                            }

                            // B. Allow Essential Cloud Infrastructure
                            if (nextHost.contains("google.com/recaptcha") || 
                                nextHost.contains("cloudflare") || 
                                nextHost.contains("gstatic")) {
                                return false // ALLOW
                            }

                            // C. Dynamic Whitelist Logic (Future Proofing)
                            // If the new URL matches the Initial Server's Host (e.g., staying on vidsrc.win), ALLOW.
                            if (initialHost.isNotEmpty() && nextHost.contains(initialHost)) {
                                return false
                            }

                            // D. Block everything else (Likely a Popunder/Redirect)
                            // Most streaming servers play video inside an iframe, they don't navigate the main window.
                            // So, any main window navigation that isn't the host itself is usually an ad.
                            return true 
                        }

                        // --- 4. JS INJECTOR (The Cleaner) ---
                        override fun onPageFinished(view: WebView?, url: String?) {
                            isLoading = false
                            
                            // This JS runs inside the web page to surgically remove ads
                            val cleanerJs = """
                                javascript:(function() {
                                    // A. Function to kill elements
                                    function removeAds() {
                                        // 1. Generic Ad Classes/IDs
                                        var selectors = [
                                            'div[class*="ad"]', 'div[id*="ad"]', 
                                            'iframe[src*="ads"]', 'iframe[src*="pop"]',
                                            '.popup', '.overlay', '.banner', 
                                            'a[target="_blank"]', // Remove new tab links
                                            'div[style*="z-index: 2147483647"]', // Topmost overlays
                                            '#player_overlay'
                                        ];
                                        
                                        selectors.forEach(sel => {
                                            document.querySelectorAll(sel).forEach(el => el.remove());
                                        });

                                        // 2. Remove invisible click-jacking layers
                                        document.querySelectorAll('div').forEach(div => {
                                            var style = window.getComputedStyle(div);
                                            if (style.position === 'absolute' && style.width === '100%' && style.height === '100%' && style.opacity === '0') {
                                                div.remove();
                                            }
                                        });
                                    }

                                    // B. Run immediately
                                    removeAds();

                                    // C. Set up a MutationObserver to kill NEW ads that load later (Real-time protection)
                                    var observer = new MutationObserver(function(mutations) {
                                        removeAds();
                                        
                                        // Auto-Play Logic
                                        var playBtn = document.querySelector('.play-button') || document.querySelector('button[aria-label="Play"]');
                                        if(playBtn) playBtn.click();
                                        
                                        var video = document.querySelector('video');
                                        if(video && video.paused) { video.play(); }
                                    });
                                    
                                    observer.observe(document.body, { childList: true, subtree: true });
                                    
                                    // D. Neutalize Window Open (Popup Killer)
                                    window.open = function() { console.log('Popup blocked by StreamX'); return null; };
                                })()
                            """
                            view?.evaluateJavascript(cleanerJs, null)
                        }
                    }

                    loadUrl(url)
                    webView = this
                }
            },
            modifier = Modifier.fillMaxSize()
        )

        if (isLoading) {
            CircularProgressIndicator(modifier = Modifier.align(Alignment.Center), color = Color.Red)
        }

        // Close Button
        IconButton(
            onClick = { navController.popBackStack() },
            modifier = Modifier.align(Alignment.TopEnd).padding(16.dp).background(Color.Black.copy(0.6f), androidx.compose.foundation.shape.CircleShape)
        ) { Icon(Icons.Default.Close, null, tint = Color.White) }
    }
}
