package com.aeoncorex.streamx.ui.player

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.*
import kotlinx.coroutines.*

/**
 * EventStreamExtractor v3
 *
 * Two-mode stream resolver:
 *
 * MODE A — DIRECT (fast path):
 *   If the URL is already a playable stream (.m3u8, known CDN, etc.)
 *   it is returned immediately without launching any WebView.
 *   This covers: Tapmad CloudFront URLs, DaddyLive m3u8, direct CDN links.
 *
 * MODE B — EXTRACT (WebView interception):
 *   Loads the embed/watch page in a hidden WebView, intercepts every
 *   outgoing network request, and returns the first HLS (.m3u8) URL.
 *
 *   Supported embed hosts (from page source analysis):
 *   • https://embedsports.top/embed/{source}/{id}/{streamNo}  ← StreamedPK
 *   • https://streamed.pk/watch/{id}/{source}                 ← watch page
 *   • DaddyLive embed pages, etc.
 *
 * Usage:
 *   val url = EventStreamExtractor.extract(context, streamUrl) { status -> ... }
 *   if (url != null) playWithExoPlayer(url) else showError()
 */
@SuppressLint("SetJavaScriptEnabled")
object EventStreamExtractor {

    private const val TIMEOUT_MS = 25_000L   // increased: embedsports needs more time
    private const val TAG        = "StreamExtractor"

    // ── Domains / patterns that indicate a directly-playable URL ────────────
    private val DIRECT_DOMAINS = listOf(
        "d34080pnh6e62j.cloudfront.net",  // Tapmad CloudFront
        "vodintlv2.in-maa1.linodeobjects.com",
        "vipstreams.in",
        "rr.vipstreams.in",
        "vod-gcp.fancode.com",
        "d2r1yp2w7bby2u.cloudfront.net",
        "cdn.jwplayer.com",
    )

    // ── Embed hosts that need WebView extraction (NOT direct) ───────────────
    // These are iframe/player pages — we must intercept their sub-requests
    private val EMBED_HOSTS = listOf(
        "embedsports.top",   // StreamedPK primary embed provider
        "streamed.pk",       // StreamedPK watch page
        "streami.su",        // Mirror 1
        "streamed.st",       // Mirror 2
        "streamed.su",       // Legacy domain
        "embedme.one",
        "embedsito.com",
        "embedme.top",
        "embedstream.me",
        // DaddyLive / DLHD player pages
        // Format: https://dlhd.pk/{stream|cast|watch|plus|casting|player}/stream-{id}.php
        "dlhd.pk",
        "dlhd.sx",
        "daddylive.dad",
        "daddylive.mp",
        "daddylive.eu",
    )

    /**
     * Returns true if [url] is already a directly-playable stream —
     * no WebView extraction needed.
     *
     * Called publicly so EventPlayerScreen can decide which play-mode to use.
     */
    fun isDirectUrl(url: String): Boolean {
        if (url.isBlank()) return false
        val lower = url.lowercase()

        // Direct stream file extensions
        if (lower.contains(".m3u8")) return true
        if (lower.contains(".mpd"))  return true

        // RTMP / RTSP / AceStream — always direct
        if (lower.startsWith("rtmp://") ||
            lower.startsWith("rtsp://") ||
            lower.startsWith("acestream://")) return true

        // Known CDN domains that serve direct streams
        val domain = try {
            android.net.Uri.parse(url).host?.lowercase() ?: ""
        } catch (_: Exception) { "" }
        if (DIRECT_DOMAINS.any { domain.endsWith(it) }) return true

        // Embed/watch pages — these NEED WebView extraction, NOT direct
        if (EMBED_HOSTS.any { domain.contains(it) }) return false

        return false
    }

    /**
     * Resolve a stream URL.
     *
     * - If [embedUrl] is already a direct stream → returned immediately (Mode A).
     * - Otherwise a hidden WebView intercepts sub-requests to find .m3u8 (Mode B).
     *
     * Must be called from a coroutine (suspend).
     */
    suspend fun extract(
        context  : Context,
        embedUrl : String,
        onStatus : (String) -> Unit = {}
    ): String? {

        // ── MODE A: Direct URL — return immediately ──────────────────────────
        if (isDirectUrl(embedUrl)) {
            android.util.Log.d(TAG, "Direct stream — skipping WebView: $embedUrl")
            onStatus("Direct stream detected ✓")
            return embedUrl
        }

        // ── MODE B: WebView extraction ───────────────────────────────────────
        return withContext(Dispatchers.Main) {
            onStatus("Connecting to stream server…")

            val deferred = CompletableDeferred<String?>()
            var webView: WebView? = null

            try {
                webView = WebView(context).apply {
                    visibility = android.view.View.GONE

                    settings.apply {
                        @Suppress("DEPRECATION")
                        javaScriptEnabled                = true
                        domStorageEnabled                = true
                        loadWithOverviewMode             = true
                        useWideViewPort                  = true
                        mediaPlaybackRequiresUserGesture = false
                        // Standard Chrome UA — embed pages block non-browser UAs
                        userAgentString =
                            "Mozilla/5.0 (Linux; Android 14; Pixel 8) " +
                            "AppleWebKit/537.36 (KHTML, like Gecko) " +
                            "Chrome/124.0.0.0 Mobile Safari/537.36"
                    }

                    // Allow mixed content (some embed pages load http sub-resources)
                    @Suppress("DEPRECATION")
                    settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW

                    webViewClient = object : WebViewClient() {

                        // Called for EVERY request the page (and its iframes) make
                        override fun shouldInterceptRequest(
                            view    : WebView,
                            request : WebResourceRequest
                        ): WebResourceResponse? {
                            val reqUrl = request.url.toString()
                            if (!deferred.isCompleted && isStreamUrl(reqUrl)) {
                                android.util.Log.d(TAG, "🎯 Stream intercepted: $reqUrl")
                                deferred.complete(reqUrl)
                            }
                            return null
                        }

                        override fun onPageFinished(view: WebView, url: String) {
                            onStatus("Waiting for stream signal…")
                            // Inject JS to find and trigger any lazy-loaded streams
                            view.evaluateJavascript("""
                                (function() {
                                    // Trigger video elements to load
                                    document.querySelectorAll('video').forEach(function(v) {
                                        v.play().catch(function(){});
                                    });
                                    // Look for HLS source in video elements
                                    document.querySelectorAll('video source').forEach(function(s) {
                                        if (s.src && s.src.includes('.m3u8')) {
                                            window.location.href = s.src;
                                        }
                                    });
                                })();
                            """.trimIndent(), null)
                        }

                        override fun onReceivedError(
                            view    : WebView,
                            request : WebResourceRequest,
                            error   : WebResourceError
                        ) {
                            // Ignore sub-resource errors (ads, trackers, etc.)
                        }

                        override fun onReceivedHttpError(
                            view     : WebView,
                            request  : WebResourceRequest,
                            response : WebResourceResponse
                        ) {
                            // Ignore HTTP errors on sub-resources
                        }
                    }

                    // Set proper Referer based on embed host
                    val referer = when {
                        embedUrl.contains("embedsports.top") -> "https://streamed.pk/"
                        embedUrl.contains("dlhd.pk") ||
                        embedUrl.contains("dlhd.sx") ||
                        embedUrl.contains("daddylive") -> "https://dlhd.pk/"
                        else -> embedUrl.substringBefore("/", "").let {
                            if (embedUrl.startsWith("http")) {
                                embedUrl.split("/").take(3).joinToString("/") + "/"
                            } else "https://streamed.pk/"
                        }
                    }
                    val extraHeaders = mapOf(
                        "Referer"          to referer,
                        "Origin"           to referer.trimEnd('/'),
                        "Accept-Language"  to "en-US,en;q=0.9",
                    )
                    loadUrl(embedUrl, extraHeaders)
                    onStatus("Loading stream page…")
                }

                val result = withTimeoutOrNull(TIMEOUT_MS) { deferred.await() }
                if (result == null) onStatus("Stream not found — try another server")
                else onStatus("Stream found ✓")
                result

            } catch (e: Exception) {
                android.util.Log.e(TAG, "Extraction error: ${e.message}")
                onStatus("Error: ${e.message}")
                null
            } finally {
                webView?.apply { stopLoading(); destroy() }
            }
        }
    }

    // ── Stream URL heuristics ────────────────────────────────────────────────
    private fun isStreamUrl(url: String): Boolean {
        val lower = url.lowercase()
        return when {
            // HLS streams
            lower.contains(".m3u8")                                    -> true
            // MPEG-DASH
            lower.contains(".mpd") && !lower.contains("manifest")     -> true
            // TS segments in live streams
            lower.contains("/live/") && lower.contains(".ts")          -> true
            lower.contains("/live/") && lower.contains("chunklist")    -> true
            lower.contains("/stream") && lower.contains(".m3u8")       -> true
            // StreamedPK CDN endpoints
            lower.contains("vipstreams.in")                            -> true
            lower.contains("cloudfront.net") && lower.contains("m3u8") -> true
            // Common live stream CDN patterns
            lower.contains("/hls/") && lower.contains(".m3u8")         -> true
            lower.contains("playlist.m3u8")                            -> true
            lower.contains("index.m3u8")                               -> true
            lower.contains("master.m3u8")                              -> true
            lower.contains("live.m3u8")                                -> true
            lower.contains("stream.m3u8")                              -> true
            // Token-authenticated streams (CDN with auth params)
            (lower.contains(".m3u8") && lower.contains("token="))      -> true
            (lower.contains(".m3u8") && lower.contains("?"))           -> true
            else                                                       -> false
        }
    }
}
