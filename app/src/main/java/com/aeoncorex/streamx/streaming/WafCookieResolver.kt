package com.aeoncorex.streamx.streaming

import android.annotation.SuppressLint
import android.content.Context
import android.util.Log
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import com.aeoncorex.streamx.network.FirebaseTokenProvider
import com.aeoncorex.streamx.network.StreamResolverConfig
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

// ═════════════════════════════════════════════════════════════════════════════
//  WafCookieResolver  —  two-phase WAF challenge solver
//  ─────────────────────────────────────────────────────────────────────────
//  Phase 1 — invisible auto-solve (SILENT_TIMEOUT_MS):
//    Creates a 1×1 off-screen WebView and loads the blocked domain. Most
//    Cloudflare JS challenges (proof-of-work, browser fingerprint) resolve
//    automatically within a few seconds with no user interaction. If the
//    cookie jar is populated before the silent timeout, we're done.
//
//  Phase 2 — user-visible interactive solve:
//    If Phase 1 times out (Turnstile checkbox, hCaptcha, image puzzle, or
//    any other challenge that genuinely needs a human), we escalate by
//    emitting ChallengeState(needsUserAction = true). WafChallengeOverlay
//    (see that file) catches this and presents a full-screen BottomSheet
//    containing the same WebView — now sized and visible so the user can
//    interact with the challenge. We wait indefinitely (USER_TIMEOUT_MS
//    is generous but bounded so a forgotten dialog doesn't block forever)
//    until the cookie appears or the user dismisses.
//
//  In both phases the same WebView instance is reused — we never recreate
//  it between phases, so the partially-loaded challenge page (JS already
//  running, challenge iframe already fetched) is still there when the UI
//  makes it visible. No redundant reload, no losing challenge state.
//
//  Cookie → Worker:
//    Once a cookie is obtained (either phase), it's POSTed to the Worker's
//    POST /waf-cookie endpoint (wafCookieStore.js) so the Worker's own
//    headless fetch() calls to that domain also succeed for the next ~25
//    minutes — see wafCookieStore.js / globalFetchPatch.js.
// ═════════════════════════════════════════════════════════════════════════════
object WafCookieResolver {

    private const val TAG = "WafCookieResolver"

    // Phase 1: how long we try invisibly before escalating to user-visible
    private const val SILENT_TIMEOUT_MS  = 8_000L
    // Phase 2: how long we wait for the user to interact before giving up
    private const val USER_TIMEOUT_MS    = 120_000L
    // TTL reported to the Worker (server-side clamped to 1h max)
    private const val REPORTED_TTL_SECONDS = 25 * 60

    // ── State exposed to UI ─────────────────────────────────────────────────

    /**
     * @param domain           the bare hostname being challenged
     * @param needsUserAction  false  → Phase 1, invisible pill only
     *                         true   → Phase 2, show WebView dialog to user
     * @param webView          non-null only in Phase 2 — the overlay mounts
     *                         this inside an AndroidView so the user sees it
     * @param onDismiss        called when the user taps "Cancel" in the dialog
     */
    data class ChallengeState(
        val domain:          String,
        val needsUserAction: Boolean              = false,
        val webView:         WebView?             = null,
        val onDismiss:       (() -> Unit)?        = null
    )

    private val _state = MutableStateFlow<ChallengeState?>(null)
    val state: StateFlow<ChallengeState?> = _state.asStateFlow()

    // ── In-flight coalescing ────────────────────────────────────────────────

    private val inFlight     = mutableMapOf<String, CompletableDeferred<Boolean>>()
    private val inFlightLock = Mutex()

    // ── Public entry point ──────────────────────────────────────────────────

    /**
     * Resolves the WAF challenge for [domain].
     * - First tries automatically (invisible WebView, 8 s).
     * - If that times out, escalates to a user-visible dialog.
     * - Returns true if a cookie was obtained, false otherwise.
     * Safe to call from any dispatcher — WebView work hops to Main internally.
     */
    suspend fun resolve(context: Context, domain: String): Boolean {
        if (domain.isBlank()) return false

        // Coalesce: if another coroutine is already solving this domain,
        // just await its result instead of spawning a second WebView.
        val existing = inFlightLock.withLock {
            inFlight[domain]?.let { return@withLock it }
            val d = CompletableDeferred<Boolean>()
            inFlight[domain] = d
            null
        }
        if (existing != null) return existing.await()

        val myDeferred = inFlight.getValue(domain)
        return try {
            val solved = runTwoPhase(context, domain)
            if (solved) reportCookieToWorker(context, domain)
            myDeferred.complete(solved)
            solved
        } catch (e: Exception) {
            Log.w(TAG, "resolve($domain) exception: ${e.message}")
            myDeferred.complete(false)
            false
        } finally {
            _state.value = null
            inFlightLock.withLock { inFlight.remove(domain) }
        }
    }

    // ── Two-phase logic ─────────────────────────────────────────────────────

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun runTwoPhase(context: Context, domain: String): Boolean {
        val url          = "https://$domain/"
        val cookieResult = CompletableDeferred<Boolean>()
        val cookieManager = CookieManager.getInstance().also {
            it.setAcceptCookie(true)
            it.setAcceptThirdPartyCookies(null, true)
            it.setCookie(url, "") // clear stale cookie first
        }

        // Build the WebView once — reused across both phases so the
        // challenge JS state is preserved when we make it visible.
        val webView = withContext(Dispatchers.Main) {
            buildWebView(context, url, cookieManager, cookieResult)
        }

        // ── Phase 1: invisible auto-solve ───────────────────────────────────
        _state.value = ChallengeState(domain = domain, needsUserAction = false)

        val autoSolved = withTimeoutOrNull(SILENT_TIMEOUT_MS) {
            cookieResult.await()
        }
        if (autoSolved == true) {
            withContext(Dispatchers.Main) { webView.destroy() }
            return true
        }

        // ── Phase 2: user-visible interactive solve ─────────────────────────
        Log.d(TAG, "$domain: auto-solve timed out — escalating to user dialog")

        val dismissDeferred = CompletableDeferred<Unit>()
        _state.value = ChallengeState(
            domain          = domain,
            needsUserAction = true,
            webView         = webView,
            onDismiss       = { dismissDeferred.complete(Unit) }
        )

        // Wait for either the cookie or the user dismissing
        val userSolved = withTimeoutOrNull(USER_TIMEOUT_MS) {
            // Race: cookie arrive vs user cancel
            kotlinx.coroutines.selects.select<Boolean> {
                cookieResult.onAwait { it }
                dismissDeferred.onAwait { false }
            }
        } ?: false

        withContext(Dispatchers.Main) { webView.destroy() }
        return userSolved
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun buildWebView(
        context:       Context,
        url:           String,
        cookieManager: CookieManager,
        result:        CompletableDeferred<Boolean>
    ): WebView = WebView(context).apply {
        layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
        settings.apply {
            javaScriptEnabled      = true
            domStorageEnabled      = true
            // Keep the real device UA — challenge fingerprinting often
            // ties the issued cookie to the UA it was solved under, so
            // we must replay the same UA on the Worker side too.
            // (userAgentString getter returns the default; no-op but explicit.)
            userAgentString        = userAgentString
            setSupportZoom(true)
            builtInZoomControls    = true
            displayZoomControls    = false
            loadWithOverviewMode   = true
            useWideViewPort        = true
        }

        webViewClient = object : WebViewClient() {
            private var settled = false

            override fun onPageFinished(view: WebView?, finishedUrl: String?) {
                super.onPageFinished(view, finishedUrl)
                if (settled) return
                val cookies = cookieManager.getCookie(url)
                if (!cookies.isNullOrBlank()) {
                    settled = true
                    cookieManager.flush()
                    result.complete(true)
                }
                // Not settling here on blank cookie — page may still be
                // mid-challenge (redirect chains, JS-injected navigations).
                // The timeouts in runTwoPhase handle the bounded wait.
            }

            override fun onReceivedError(
                view: WebView?, errorCode: Int,
                description: String?, failingUrl: String?
            ) {
                super.onReceivedError(view, errorCode, description, failingUrl)
                if (!settled) {
                    settled = true
                    Log.w(TAG, "$url WebView error: $description")
                    result.complete(false)
                }
            }

            // Allow navigation within the challenge domain (redirect chains,
            // cf challenge subpaths) but don't follow off-domain links that
            // might appear in challenge page iframes.
            override fun shouldOverrideUrlLoading(
                view: WebView?, request: WebResourceRequest?
            ): Boolean {
                val host = request?.url?.host ?: return false
                return !host.endsWith(view?.url?.let {
                    try { java.net.URI(it).host } catch (_: Exception) { "" }
                } ?: "")
            }
        }
        loadUrl(url)
    }

    // ── Report cookie to Worker ─────────────────────────────────────────────

    private suspend fun reportCookieToWorker(context: Context, domain: String) =
        withContext(Dispatchers.IO) {
            try {
                val url    = "https://$domain/"
                val cookie = CookieManager.getInstance().getCookie(url)
                if (cookie.isNullOrBlank()) return@withContext

                // Read UA from a throwaway WebView on Main — we need the
                // same UA string the challenge WebView used (default device UA).
                val userAgent = withContext(Dispatchers.Main) {
                    WebView(context).settings.userAgentString.also {
                        // don't loadUrl, just reading the default UA string
                    }
                }

                val token = FirebaseTokenProvider.getIdToken() ?: run {
                    Log.w(TAG, "no Firebase token — skipping cookie report for $domain")
                    return@withContext
                }
                val baseUrl = StreamResolverConfig.getStreamWorkerBaseUrl()

                val body = JSONObject().apply {
                    put("domain",     domain)
                    put("cookie",     cookie)
                    put("userAgent",  userAgent)
                    put("ttlSeconds", REPORTED_TTL_SECONDS)
                }.toString()

                val request = Request.Builder()
                    .url("${baseUrl}waf-cookie")
                    .header("Authorization", "Bearer $token")
                    .post(body.toRequestBody("application/json".toMediaType()))
                    .build()

                HttpClient.okhttp.newCall(request).execute().use { response ->
                    if (!response.isSuccessful)
                        Log.w(TAG, "waf-cookie POST $domain: HTTP ${response.code}")
                    else
                        Log.d(TAG, "reported WAF cookie for $domain (${cookie.length} chars)")
                }
            } catch (e: Exception) {
                Log.w(TAG, "reportCookieToWorker($domain) failed: ${e.message}")
            }
        }
}
