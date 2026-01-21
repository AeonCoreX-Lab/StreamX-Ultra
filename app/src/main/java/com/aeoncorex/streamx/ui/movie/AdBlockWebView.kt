package com.aeoncorex.streamx.ui.movie

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.view.View
import android.view.ViewGroup
import android.webkit.*
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.navigation.NavController

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun AdBlockWebView(url: String, navController: NavController) {
    var isLoading by remember { mutableStateOf(true) }
    var webView: WebView? = remember { null }
    
    // Back Press Handle inside WebView
    BackHandler {
        if (webView?.canGoBack() == true) {
            webView?.goBack()
        } else {
            navController.popBackStack()
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            factory = { context ->
                WebView(context).apply {
                    settings.apply {
                        javaScriptEnabled = true
                        domStorageEnabled = true
                        databaseEnabled = true
                        useWideViewPort = true
                        loadWithOverviewMode = true
                        mediaPlaybackRequiresUserGesture = false // AutoPlay Enable
                        mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                        userAgentString = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/110.0.0.0 Safari/537.36"
                    }

                    // **ULTIMATE AD BLOCK SYSTEM**
                    webViewClient = object : WebViewClient() {
                        // অ্যাড সার্ভার লিস্ট
                        private val adHosts = listOf(
                            "doubleclick.net", "googlesyndication.com", "google-analytics.com",
                            "adnxs.com", "popads.net", "popcash.net", "adsterra.com",
                            "mc.yandex.ru", "bebi.com", "histats.com", "onclickmedium.com"
                        )

                        // ১. অ্যাড রিসোর্স লোড বন্ধ করা
                        override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                            val requestUrl = request?.url.toString()
                            if (adHosts.any { requestUrl.contains(it) }) {
                                return WebResourceResponse("text/plain", "utf-8", null) // Empty response blocks the ad
                            }
                            return super.shouldInterceptRequest(view, request)
                        }

                        // ২. পেজ লোড হলে JS দিয়ে অ্যাড রিমুভ করা
                        override fun onPageFinished(view: WebView?, url: String?) {
                            isLoading = false
                            val js = """
                                javascript:(function() {
                                    // Remove common ad classes
                                    var styles = document.createElement('style');
                                    styles.innerHTML = 'div[class*="ad"], iframe[src*="ads"], .popup, .overlay, #ad, .ads { display: none !important; }';
                                    document.head.appendChild(styles);
                                    
                                    // Auto Click Play if needed
                                    var video = document.querySelector('video');
                                    if(video) { video.play(); }
                                })()
                            """
                            view?.evaluateJavascript(js, null)
                        }

                        // ৩. অটো রিডাইরেক্ট ব্লক (পপ-আপ উইন্ডো)
                        @Deprecated("Deprecated in Java")
                        override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                            return if (url != null && (url.contains("vidsrc") || url.contains("youtube") || url.contains("superembed") || url.contains("2embed") || url.contains("webtor"))) {
                                false // Allow Video Source
                            } else {
                                true // Block Everything Else (Ads/Redirects)
                            }
                        }
                    }

                    webChromeClient = object : WebChromeClient() {
                        override fun onProgressChanged(view: WebView?, newProgress: Int) {
                            if (newProgress > 80) isLoading = false
                        }
                        
                        // Fullscreen Video support needs activity config, simplified here
                    }

                    loadUrl(url)
                    webView = this
                }
            },
            modifier = Modifier.fillMaxSize()
        )

        // Loading Indicator
        if (isLoading) {
            CircularProgressIndicator(modifier = Modifier.align(Alignment.Center), color = Color.Red)
        }

        // Close Button Overlay
        IconButton(
            onClick = { navController.popBackStack() },
            modifier = Modifier.align(Alignment.TopEnd).padding(16.dp).background(Color.Black.copy(0.5f), androidx.compose.foundation.shape.CircleShape)
        ) {
            Icon(Icons.Default.Close, null, tint = Color.White)
        }
    }
}
