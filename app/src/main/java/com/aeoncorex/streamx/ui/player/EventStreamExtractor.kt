package com.aeoncorex.streamx.ui.player

import android.annotation.SuppressLint
import android.content.Context
import android.net.http.SslError
import android.webkit.*
import kotlinx.coroutines.*

/**
 * EventStreamExtractor v4 — iframe-wrapper approach
 *
 * ══════════════════════════════════════════════════════════════════
 *  ROOT CAUSE  (why embed.st shows "Remove sandbox attributes…")
 * ══════════════════════════════════════════════════════════════════
 *
 *  embed.st / embedsports.top are NOT standalone pages.
 *  They are designed to be loaded ONLY inside an <iframe> embedded
 *  from streamed.pk (or streamed.su).
 *
 *  On every page-load, embed.st checks:
 *    • HTTP Referer header    → must contain "streamed.pk"
 *    • document.referrer (JS) → same check client-side
 *
 *  When any browser (Chrome, Via, Firefox) or a WebView navigates
 *  to the URL directly, Referer is empty / wrong → embed.st shows
 *  "Remove sandbox attributes on the iframe tag" and blocks playback.
 *
 * ══════════════════════════════════════════════════════════════════
 *  FIX — loadDataWithBaseURL iframe wrapper
 * ══════════════════════════════════════════════════════════════════
 *
 *  Instead of loading the embed URL directly (loadUrl), we:
 *
 *  1. Build a tiny HTML page that contains a single <iframe>
 *     pointing to the embed URL — NO sandbox attribute on the iframe.
 *
 *  2. Load it via:
 *       webView.loadDataWithBaseURL(
 *           "https://streamed.pk/",   ← base URL  ← THE KEY
 *           iframeHtml, "text/html", "UTF-8", null
 *       )
 *
 *  3. The base URL makes the WebView's "current origin" = streamed.pk.
 *     When the <iframe> navigates to embed.st, the HTTP request carries:
 *       Referer: https://streamed.pk/
 *       Origin:  https://streamed.pk
 *     embed.st sees a valid referrer → renders the player normally.
 *
 *  4. shouldInterceptRequest fires for ALL network requests from ALL
 *     frames (main + nested iframes).  The first .m3u8 / vipstreams
 *     URL is captured and passed to ExoPlayer.
 *
 *  This is the exact technique used by HDStreamz, Pikashow, and other
 *  professional live-streaming Android apps.
 *
 * ══════════════════════════════════════════════════════════════════
 *  FIX (playback failure after successful extraction)
 * ══════════════════════════════════════════════════════════════════
 *
 *  Capturing the .m3u8 URL above is only half the problem. Once
 *  WebView's shouldInterceptRequest() sees it, that request was made
 *  BY THE WEBVIEW — with the Referer/Origin (streamed.pk) and User-
 *  Agent that the CDN already checked and approved for that specific
 *  token-locked URL.
 *
 *  If the caller then hands the bare URL string to ExoPlayer via
 *  MediaItem.Builder().setUri(playUrl) with no custom headers,
 *  ExoPlayer opens a completely NEW connection with no Referer, no
 *  Origin, and its own default User-Agent. Many of these CDNs
 *  (vipstreams.in and others) hotlink-protect on exactly those
 *  headers — so the capture succeeds, extractionStatus shows
 *  "Stream found ✓", but playback then fails or hangs, because the
 *  second request looks nothing like the first one the CDN allowed.
 *
 *  Fix: the caller (EventPlayerScreen) must NOT use a plain
 *  MediaItem.Builder().setUri(...) for MODE B results. It must build
 *  an HttpMediaSource with a DefaultHttpDataSource.Factory that sets:
 *    - Referer:    EventStreamExtractor.resolveBaseUrl(embedUrl)
 *    - User-Agent: EventStreamExtractor.CAPTURE_USER_AGENT
 *  so ExoPlayer's request is indistinguishable from a continuation of
 *  the same WebView session that got the URL approved in the first
 *  place. See EventPlayerScreen.kt's buildMediaSource() for the
 *  actual wiring — MODE A (already-direct CDN URLs) does not need
 *  this, since those were never gated by a WebView-only Referer check
 *  to begin with.
 *
 * ══════════════════════════════════════════════════════════════════
 *  TWO MODES
 * ══════════════════════════════════════════════════════════════════
 *
 *  MODE A — DIRECT:
 *    URL is already a playable stream (.m3u8, known CDN, etc.)
 *    → returned immediately, no WebView launched.
 *
 *  MODE B — IFRAME-WRAPPER:
 *    URL is an embed/watch page (embed.st, dlhd.pk, etc.)
 *    → wrap in iframe HTML, load with correct base URL,
 *      intercept the m3u8 from sub-requests.
 */
@SuppressLint("SetJavaScriptEnabled")
object EventStreamExtractor {

    private const val TIMEOUT_MS = 30_000L
    private const val TAG        = "StreamExtractor"

    // ── Directly-playable CDN domains ───────────────────────────────────────
    // ExoPlayer can open these without any extraction.
    private val DIRECT_DOMAINS = setOf(
        "d34080pnh6e62j.cloudfront.net",          // Tapmad CloudFront
        "vodintlv2.in-maa1.linodeobjects.com",    // Tapmad Linode
        "vipstreams.in",                          // StreamedPK CDN
        "rr.vipstreams.in",
        "vod-gcp.fancode.com",                    // FanCode GCP
        "d2r1yp2w7bby2u.cloudfront.net",          // FanCode CloudFront
        "cdn.jwplayer.com",                       // JWPlayer
    )

    /**
     * Maps embed host (partial) → base URL to use when wrapping in iframe.
     *
     * The base URL is what the parent page's "origin" will be.
     * embed.st reads this as the Referer and decides whether to allow playback.
     *
     * Rule: use the site that normally embeds this player in production.
     */
    private val EMBED_BASE_URLS: Map<String, String> = mapOf(
        // ── StreamedPK / StreamedSU family ──────────────────────────────
        "embed.st"           to "https://streamed.pk/",   // primary embed host (NEW)
        "embedsports.top"    to "https://streamed.pk/",   // legacy (domain migrated)
        "streamed.pk"        to "https://streamed.pk/",
        "streamed.su"        to "https://streamed.su/",
        "streami.su"         to "https://streamed.pk/",
        "streamed.st"        to "https://streamed.pk/",
        "embedme.one"        to "https://streamed.pk/",
        "embedsito.com"      to "https://streamed.pk/",
        "embedme.top"        to "https://embedme.top/",
        "embedstream.me"     to "https://embedstream.me/",
        // ── DaddyLive / DLHD family ────────────────────────────────────
        // DaddyLive rotates its mirror domain frequently (.pk, .sx, .st,
        // .to, .mp, .eu, .fm seen so far) — all serve the same backend.
        // A missing entry here falls through to resolveBaseUrl()'s
        // fallback (the embed URL's OWN origin), which sends the site
        // itself as its own Referer — that's exactly what DLHD's "Direct
        // access blocked, place iframe embed code on your website" page
        // is rejecting, since it expects an EXTERNAL embedding site, not
        // itself. Keep this list current as new mirrors appear; an
        // unmapped domain here silently reproduces that exact block.
        "dlhd.pk"            to "https://dlhd.pk/",
        "dlhd.sx"            to "https://dlhd.sx/",
        "dlhd.st"            to "https://dlhd.st/",
        "daddylive.dad"      to "https://daddylive.dad/",
        "daddylive.mp"       to "https://daddylive.mp/",
        "daddylive.eu"       to "https://daddylive.eu/",
        "daddylive.fm"       to "https://daddylive.fm/",
        "daddylive.to"       to "https://daddylive.to/",
        "daddylive.sx"       to "https://daddylive.sx/",
        // ── VIPLeague embed providers ──────────────────────────────────
        "burkhakalekah.com"  to "https://vipleaguetv.net/",
        "dungatv.xyz"        to "https://vipleaguetv.net/",
        "lapserspos.qpon"    to "https://vipleaguetv.net/",
        // ── StreamEast ─────────────────────────────────────────────────
        "beststreameast.net" to "https://beststreameast.net/",
        "streameast.live"    to "https://streameast.live/",
        "streameast.xyz"     to "https://streameast.xyz/",
        "streameast.app"     to "https://streameast.app/",
    )

    // ── JS injected on every onPageFinished (main frame + iframes) ──────────
    private val JS_STRIP_SANDBOX = """
        (function() {
            // 1. Remove sandbox from any nested iframes already in the DOM
            document.querySelectorAll('iframe').forEach(function(f) {
                f.removeAttribute('sandbox');
                var allow = f.getAttribute('allow') || '';
                if (!allow.includes('autoplay')) {
                    f.setAttribute('allow',
                        'autoplay; encrypted-media; fullscreen; picture-in-picture');
                }
            });

            // 2. Trigger video elements to start loading
            document.querySelectorAll('video').forEach(function(v) {
                v.play().catch(function(){});
                if (v.src && v.src.includes('.m3u8')) {
                    window.location.href = v.src;
                }
            });

            // 3. Watch DOM — strip sandbox from iframes added dynamically
            new MutationObserver(function(mutations) {
                mutations.forEach(function(m) {
                    m.addedNodes.forEach(function(node) {
                        if (!node || node.nodeType !== 1) return;
                        if (node.tagName === 'IFRAME') {
                            node.removeAttribute('sandbox');
                            node.setAttribute('allow',
                                'autoplay; encrypted-media; fullscreen; picture-in-picture');
                        }
                        if (node.querySelectorAll) {
                            node.querySelectorAll('iframe').forEach(function(f) {
                                f.removeAttribute('sandbox');
                            });
                        }
                    });
                });
            }).observe(document.documentElement, { childList: true, subtree: true });
        })();
    """.trimIndent()

    // ════════════════════════════════════════════════════════════════════════
    //  PUBLIC API
    // ════════════════════════════════════════════════════════════════════════

    /**
     * Returns true if [url] is already a directly-playable stream URL.
     * Returns false if it's an embed/watch page that needs iframe wrapping.
     *
     * Called publicly so EventPlayerScreen can show the right loading state.
     */
    fun isDirectUrl(url: String): Boolean {
        if (url.isBlank()) return false
        val lower = url.lowercase()

        // Stream file extensions → always direct
        if (lower.contains(".m3u8")) return true
        if (lower.contains(".mpd"))  return true

        // Protocol-level streams → always direct
        if (lower.startsWith("rtmp://") ||
            lower.startsWith("rtsp://") ||
            lower.startsWith("acestream://")) return true

        val domain = try {
            android.net.Uri.parse(url).host?.lowercase() ?: ""
        } catch (_: Exception) { "" }

        // Known CDN domains serving raw HLS → direct
        if (DIRECT_DOMAINS.any { domain.endsWith(it) }) return true

        // Known embed hosts → NEVER direct (needs iframe wrapper)
        if (EMBED_BASE_URLS.keys.any { domain.contains(it) }) return false

        return false
    }

    /**
     * Resolve a stream URL to a playable .m3u8.
     *
     * MODE A — Direct: returned immediately.
     * MODE B — Iframe-wrapper: wrap in HTML, load with correct base URL,
     *          intercept the m3u8 from sub-requests.
     *
     * Must be called from a coroutine.
     */
    suspend fun extract(
        context  : Context,
        embedUrl : String,
        onStatus : (String) -> Unit = {}
    ): String? {

        // ── MODE A ───────────────────────────────────────────────────────────
        if (isDirectUrl(embedUrl)) {
            android.util.Log.d(TAG, "MODE A — direct: $embedUrl")
            onStatus("Direct stream detected ✓")
            return embedUrl
        }

        // ── MODE B ───────────────────────────────────────────────────────────
        return withContext(Dispatchers.Main) {
            onStatus("Connecting to stream server…")

            val deferred = CompletableDeferred<String?>()
            var webView: WebView? = null

            try {
                val baseUrl    = resolveBaseUrl(embedUrl)
                val iframeHtml = buildIframeHtml(embedUrl)

                android.util.Log.d(TAG, "MODE B — iframe wrapper")
                android.util.Log.d(TAG, "  embed : $embedUrl")
                android.util.Log.d(TAG, "  base  : $baseUrl")

                webView = WebView(context).apply {
                    visibility = android.view.View.GONE

                    settings.apply {
                        @Suppress("DEPRECATION")
                        javaScriptEnabled                = true
                        domStorageEnabled                = true
                        loadWithOverviewMode             = true
                        useWideViewPort                  = true
                        mediaPlaybackRequiresUserGesture = false
                        setSupportMultipleWindows(false)
                        builtInZoomControls              = false

                        // Standard Chrome Android UA — embed pages block non-browser UAs.
                        // Shared with CAPTURE_USER_AGENT so the caller can hand ExoPlayer
                        // the exact same UA the CDN already saw and approved.
                        userAgentString = CAPTURE_USER_AGENT

                        // Allow HTTP sub-resources inside HTTPS pages (some CDNs need this)
                        @Suppress("DEPRECATION")
                        mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                    }

                    webViewClient = object : WebViewClient() {

                        /**
                         * Called for EVERY request from EVERY frame (main + all iframes).
                         * This is where we capture the .m3u8 / CDN stream URL.
                         */
                        override fun shouldInterceptRequest(
                            view    : WebView,
                            request : WebResourceRequest
                        ): WebResourceResponse? {
                            val reqUrl = request.url.toString()
                            if (!deferred.isCompleted && isStreamUrl(reqUrl)) {
                                android.util.Log.d(TAG, "🎯 Intercepted: $reqUrl")
                                deferred.complete(reqUrl)
                            }
                            return null  // let WebView handle the request normally
                        }

                        override fun onPageFinished(view: WebView, url: String) {
                            onStatus("Waiting for stream signal…")
                            // Inject sandbox-stripping + video-trigger JS
                            view.evaluateJavascript(JS_STRIP_SANDBOX, null)
                        }

                        // ── Suppress non-critical errors ──────────────────
                        override fun onReceivedError(
                            view    : WebView,
                            request : WebResourceRequest,
                            error   : WebResourceError
                        ) { /* ignore sub-resource / ad / tracker errors */ }

                        override fun onReceivedHttpError(
                            view     : WebView,
                            request  : WebResourceRequest,
                            response : WebResourceResponse
                        ) { /* ignore */ }

                        @Suppress("DEPRECATION")
                        override fun onReceivedSslError(
                            view    : WebView,
                            handler : SslErrorHandler,
                            error   : SslError
                        ) {
                            // Some CDN edge nodes use self-signed / mismatched certs
                            handler.proceed()
                        }
                    }

                    // ────────────────────────────────────────────────────────
                    //  THE KEY FIX
                    //
                    //  loadDataWithBaseURL(baseUrl, html, …) sets the WebView's
                    //  "current origin" to baseUrl.
                    //
                    //  When our <iframe src="embed.st/…"> makes its first HTTP
                    //  request, WebView sets:
                    //    Referer: https://streamed.pk/
                    //    Origin:  https://streamed.pk
                    //
                    //  embed.st sees a valid Referer → renders the player.
                    //  No "Remove sandbox attributes" error.
                    // ────────────────────────────────────────────────────────
                    loadDataWithBaseURL(
                        baseUrl,      // "https://streamed.pk/"
                        iframeHtml,   // our wrapper HTML
                        "text/html",
                        "UTF-8",
                        null
                    )
                    onStatus("Loading stream page…")
                }

                val result = withTimeoutOrNull(TIMEOUT_MS) { deferred.await() }

                if (result == null) onStatus("Stream not found — try another server")
                else                onStatus("Stream found ✓")

                result

            } catch (e: Exception) {
                android.util.Log.e(TAG, "Extraction error: ${e.message}")
                onStatus("Error: ${e.message}")
                null
            } finally {
                webView?.apply {
                    stopLoading()
                    destroy()
                }
            }
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    //  PRIVATE HELPERS
    // ════════════════════════════════════════════════════════════════════════

    /**
     * Resolve which base URL (Referer/Origin) to use for a given embed URL —
     * both for the iframe wrapper here AND for whoever ends up actually
     * requesting the captured stream URL (see FIX note below).
     *
     * Public because EventPlayerScreen needs this SAME value to attach a
     * matching Referer header when ExoPlayer opens the captured .m3u8 —
     * see the "FIX (playback failure after successful extraction)" note
     * at the top of this file.
     */
    fun resolveBaseUrl(embedUrl: String): String {
        val domain = try {
            android.net.Uri.parse(embedUrl).host?.lowercase() ?: ""
        } catch (_: Exception) { "" }

        // Match by partial domain key
        val match = EMBED_BASE_URLS.entries.firstOrNull { (key, _) ->
            domain.contains(key)
        }
        if (match != null) return match.value

        // Fallback: use embed URL's own origin
        return try {
            val uri = android.net.Uri.parse(embedUrl)
            "${uri.scheme}://${uri.host}/"
        } catch (_: Exception) { "https://streamed.pk/" }
    }

    /**
     * The exact User-Agent string the WebView used to capture the stream
     * URL. Exposed so ExoPlayer's request looks like a continuation of the
     * same "browser session" the CDN already approved, not a different
     * client suddenly requesting the same token-locked URL — see FIX note.
     */
    const val CAPTURE_USER_AGENT =
        "Mozilla/5.0 (Linux; Android 14; Pixel 8) " +
        "AppleWebKit/537.36 (KHTML, like Gecko) " +
        "Chrome/124.0.0.0 Mobile Safari/537.36"

    /**
     * Build minimal HTML that iframes [embedUrl] — NO sandbox attribute.
     *
     * The <iframe> intentionally has:
     *   allowfullscreen, allow="autoplay; encrypted-media; fullscreen …"
     * and intentionally LACKS:
     *   sandbox="…"  ← this is what blocked embed.st before
     */
    private fun buildIframeHtml(embedUrl: String): String {
        // Escape only what's needed for the src attribute value
        val safeSrc = embedUrl
            .replace("&", "&amp;")
            .replace("\"", "&quot;")

        return """<!DOCTYPE html>
<html>
<head>
  <meta charset="UTF-8">
  <meta name="viewport" content="width=device-width,initial-scale=1,user-scalable=no">
  <style>
    * { margin: 0; padding: 0; box-sizing: border-box; }
    html, body { width: 100%; height: 100%; overflow: hidden; background: #000; }
    iframe { width: 100%; height: 100%; border: 0; display: block; }
  </style>
</head>
<body>
  <iframe
    src="$safeSrc"
    allowfullscreen="true"
    allow="autoplay; encrypted-media; fullscreen; picture-in-picture;
           accelerometer; gyroscope; clipboard-write"
    scrolling="no"
    frameborder="0">
  </iframe>
</body>
</html>"""
    }

    /**
     * Returns true if [url] looks like an HLS / DASH / live stream
     * that ExoPlayer can play — i.e., it's worth capturing.
     */
    private fun isStreamUrl(url: String): Boolean {
        val lower = url.lowercase()
        return when {
            // ── HLS manifests ──────────────────────────────────────
            lower.contains(".m3u8")                                     -> true
            // ── MPEG-DASH ──────────────────────────────────────────
            lower.contains(".mpd") && !lower.contains("manifest.mpd")  -> true
            // ── HLS live-stream segment paths ─────────────────────
            lower.contains("/live/") && lower.contains(".ts")           -> true
            lower.contains("/live/") && lower.contains("chunklist")     -> true
            lower.contains("/stream") && lower.contains(".m3u8")        -> true
            // ── StreamedPK CDN ─────────────────────────────────────
            lower.contains("vipstreams.in")                             -> true
            // ── CloudFront HLS ────────────────────────────────────
            lower.contains("cloudfront.net") && lower.contains("m3u8") -> true
            // ── Common HLS path patterns ──────────────────────────
            lower.contains("/hls/")     && lower.contains(".m3u8")      -> true
            lower.contains("playlist.m3u8")                             -> true
            lower.contains("index.m3u8")                                -> true
            lower.contains("master.m3u8")                               -> true
            lower.contains("live.m3u8")                                 -> true
            lower.contains("stream.m3u8")                               -> true
            // ── Token-authenticated CDN streams ────────────────────
            lower.contains(".m3u8") && lower.contains("token=")         -> true
            lower.contains(".m3u8") && lower.contains("?")              -> true
            else                                                        -> false
        }
    }
}
