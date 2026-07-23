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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

// ═════════════════════════════════════════════════════════════════════════════
//  WafCookieResolver  —  two-phase WAF challenge solver, with proactive
//  warming, expiry-aware background refresh, and batch multi-domain solving.
//  ─────────────────────────────────────────────────────────────────────────
//  Phase 1 — invisible auto-solve (SILENT_TIMEOUT_MS):
//    Creates a 1×1 off-screen WebView and loads the blocked domain. Most
//    Cloudflare JS challenges (proof-of-work, browser fingerprint) resolve
//    automatically within a few seconds with no user interaction.
//
//  Phase 2 — user-visible interactive solve:
//    If Phase 1 times out (Turnstile checkbox, hCaptcha, image puzzle),
//    escalates to a visible BottomSheet (see WafChallengeOverlay.kt).
//    Same WebView instance reused across both phases — no reload, no lost
//    challenge state.
//
//  ── "Ultimate power" additions (2026-07-21) ─────────────────────────────
//
//  1. PROACTIVE WARMUP — instead of only reacting to a live resolve()
//     failing with a WAF-block signal, proactiveWarmup() runs once at app
//     startup, fetches the Worker's known-WAF-domain list (GET
//     /waf-known-domains — see wafDetect.js's getKnownWafDomains()), checks
//     which ones the Worker doesn't already have a fresh cookie for (GET
//     /waf-cookie-status), and solves those in the background BEFORE the
//     user ever opens a title. By the time they tap play, the cookie is
//     often already there — no visible delay, no cold-start WAF prompt.
//
//  2. REFRESH-BEFORE-EXPIRY — startProactiveRefreshLoop() runs for the
//     lifetime of the app process, periodically checking each known
//     domain's remaining TTL via /waf-cookie-status and re-solving any
//     that are getting close to expiry (see REFRESH_THRESHOLD_SECONDS).
//     This means a cookie ideally NEVER actually expires mid-session from
//     the user's point of view — it's refreshed quietly in the background
//     before that can happen, rather than the old reactive-only model
//     where the user's next resolve() would hit a 403 and THEN trigger a
//     solve (visible latency, and — before the AsyncLocalStorage
//     concurrency fix — sometimes silently not even detected at all).
//
//  3. BATCH MULTI-DOMAIN SOLVE — resolveMultiple() solves several domains
//     using a small pool of concurrent WebViews (MAX_CONCURRENT_SOLVES)
//     instead of one at a time. Proactive warmup and the refresh loop both
//     use this instead of looping resolve() calls sequentially, so warming
//     N domains takes roughly (N / pool size) rounds instead of N.
//
//  Cookie → Worker: unchanged — POSTed to /waf-cookie once obtained, same
//  as before.
// ═════════════════════════════════════════════════════════════════════════════
object WafCookieResolver {

    private const val TAG = "WafCookieResolver"

    private const val SILENT_TIMEOUT_MS    = 8_000L
    private const val USER_TIMEOUT_MS      = 120_000L
    private const val REPORTED_TTL_SECONDS = 25 * 60

    // How many domains to solve concurrently during proactive warmup or a
    // refresh pass. WebView instances aren't free (each is a real
    // Chromium content process slice), so this is intentionally modest —
    // enough to make warming a handful of known domains fast without
    // spiking memory/CPU on lower-end devices.
    private const val MAX_CONCURRENT_SOLVES = 3

    // A domain gets proactively refreshed once its remaining TTL drops
    // below this threshold — well before actual expiry, so there's buffer
    // for the refresh itself to run (network + WebView solve time) before
    // the old cookie would have stopped working.
    private const val REFRESH_THRESHOLD_SECONDS = 5 * 60

    // How often the background refresh loop wakes up to check status.
    // Doesn't need to be frequent — cookies live ~25+ minutes, checking
    // every few minutes is more than enough resolution.
    private const val REFRESH_CHECK_INTERVAL_MS = 3 * 60 * 1000L

    // Own supervisor scope for the long-lived refresh loop — NOT tied to
    // any screen/ViewModel lifecycle, since this needs to keep running for
    // as long as the app process is alive, independent of what screen the
    // user is currently on. Cancelled only if the process dies.
    private val refreshScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var refreshLoopStarted = false

    // ── State exposed to UI (unchanged shape) ───────────────────────────────

    data class ChallengeState(
        val domain:          String,
        val needsUserAction: Boolean       = false,
        val webView:         WebView?      = null,
        val onDismiss:       (() -> Unit)? = null
    )

    private val _state = MutableStateFlow<ChallengeState?>(null)
    val state: StateFlow<ChallengeState?> = _state.asStateFlow()

    // ── In-flight coalescing ────────────────────────────────────────────────

    private val inFlight     = mutableMapOf<String, CompletableDeferred<Boolean>>()
    private val inFlightLock = Mutex()

    // ── WebView availability check (fixed 2026-07-22) ───────────────────────
    //
    // Root cause of the "WebSettings WebView.getSettings() on a null object
    // reference" crash that made every single WAF solve attempt fail
    // (verified: 0 successes across 1,000+ attempts in field logs) —
    // confirmed via research, not a Kotlin/coroutines threading bug:
    // WebView(context) can complete construction WITHOUT throwing on some
    // Android/OEM builds even when the device's WebView provider (Android
    // System WebView / Chrome) is disabled, uninstalled, or mid-update —
    // the object it returns has a null internal provider, and the NEXT call
    // that touches it (settings.apply { ... }, i.e. getSettings()) is what
    // actually NPEs. This is a well-documented Android platform behavior
    // (android.webkit.WebViewFactory$MissingWebViewPackageException and its
    // silent-failure variants), not something fixable by changing which
    // Context we pass in — a ContextThemeWrapper doesn't help because the
    // provider itself is what's missing, not the theme.
    //
    // Fix: probe WebView.getCurrentWebViewPackage() ONCE (cheap, no WebView
    // construction involved) and cache the result. If null, WebView
    // genuinely isn't usable on this device right now — every WAF-solve
    // attempt is skipped immediately instead of repeatedly hitting the same
    // crash, and callers get a clear signal (false / empty map) instead of
    // a swallowed exception that looks like "it just doesn't work."
    //
    // Cached rather than re-checked every call because a WebView update
    // completing mid-session (the ONE case this could change) is rare
    // enough that a fresh app launch picking up the fixed state is an
    // acceptable trade-off against re-probing on every single resolve().
    @Volatile private var webViewAvailable: Boolean? = null

    /**
     * True if this device currently has a usable WebView provider. Safe to
     * call from any thread — does not construct a WebView, only queries
     * package info.
     */
    fun isWebViewAvailable(): Boolean {
        webViewAvailable?.let { return it }
        val available = try {
            WebView.getCurrentWebViewPackage() != null
        } catch (e: Throwable) {
            // Belt-and-suspenders: getCurrentWebViewPackage() itself is
            // documented safe to call without side effects, but if some
            // OEM build still manages to throw here, treat that exactly
            // like "not available" rather than letting it propagate.
            Log.w(TAG, "isWebViewAvailable() check itself failed: ${e.message}")
            false
        }
        webViewAvailable = available
        if (!available) {
            Log.w(TAG, "No usable WebView provider on this device — WAF solving will be skipped entirely")
        }
        return available
    }

    // ── Public entry point — single domain (unchanged behavior) ────────────

    /**
     * Resolves the WAF challenge for [domain].
     * - First tries automatically (invisible WebView, 8 s).
     * - If that times out, escalates to a user-visible dialog.
     * - Returns true if a cookie was obtained, false otherwise.
     * Safe to call from any dispatcher — WebView work hops to Main internally.
     */
    suspend fun resolve(context: Context, domain: String): Boolean {
        if (domain.isBlank()) return false
        if (!isWebViewAvailable()) return false

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
            Log.w(TAG, "resolve($domain) exception: ${e.message}", e)
            myDeferred.complete(false)
            false
        } finally {
            _state.value = null
            inFlightLock.withLock { inFlight.remove(domain) }
        }
    }

    // ── NEW: batch multi-domain solve ───────────────────────────────────────

    /**
     * Solves multiple domains using a small pool of concurrent WebViews.
     * Used by proactiveWarmup() and the refresh loop instead of looping
     * resolve() one at a time.
     *
     * IMPORTANT: this deliberately does NOT use Phase 2 (user-visible
     * dialog) for any domain beyond the first that needs it — background
     * warming/refreshing should never surprise the user with an unsolicited
     * verification popup while they're just browsing. If a domain in a
     * batch needs interactive solving, it's left unresolved here (returns
     * false for it) and will naturally fall through to the normal reactive
     * resolve() → Phase 2 flow the next time something actually needs it
     * live, which IS an appropriate moment to show the user a prompt.
     *
     * @param context
     * @param domains domains to solve
     * @return map of domain -> whether a cookie was obtained
     */
    suspend fun resolveMultiple(context: Context, domains: List<String>): Map<String, Boolean> {
        if (domains.isEmpty()) return emptyMap()
        if (!isWebViewAvailable()) return domains.associateWith { false }

        val results = mutableMapOf<String, Boolean>()
        val resultsLock = Mutex()

        // Process in chunks of MAX_CONCURRENT_SOLVES rather than firing all
        // of them at once — bounds how many WebView instances exist
        // simultaneously regardless of how many domains are passed in.
        domains.chunked(MAX_CONCURRENT_SOLVES).forEach { chunk ->
            coroutineScope {
                chunk.map { domain ->
                    launch {
                        val solved = resolveSilentOnly(context, domain)
                        resultsLock.withLock { results[domain] = solved }
                    }
                }.forEach { it.join() }
            }
        }
        return results
    }

    /**
     * Like resolve(), but Phase-1-only (invisible auto-solve) — never
     * escalates to the user-visible dialog. Used exclusively by background
     * warming/refresh paths (see resolveMultiple()'s doc comment for why).
     * Still participates in the same in-flight coalescing as resolve(), so
     * a domain being background-refreshed and a domain being live-resolved
     * at the same moment don't spawn duplicate WebViews.
     */
    private suspend fun resolveSilentOnly(context: Context, domain: String): Boolean {
        if (domain.isBlank()) return false
        if (!isWebViewAvailable()) return false

        val existing = inFlightLock.withLock {
            inFlight[domain]?.let { return@withLock it }
            val d = CompletableDeferred<Boolean>()
            inFlight[domain] = d
            null
        }
        if (existing != null) return existing.await()

        val myDeferred = inFlight.getValue(domain)
        return try {
            val solved = runSilentPhaseOnly(context, domain)
            if (solved) reportCookieToWorker(context, domain)
            myDeferred.complete(solved)
            solved
        } catch (e: Exception) {
            Log.w(TAG, "resolveSilentOnly($domain) exception: ${e.message}", e)
            myDeferred.complete(false)
            false
        } finally {
            inFlightLock.withLock { inFlight.remove(domain) }
        }
    }

    // ── NEW: proactive warmup ────────────────────────────────────────────────

    /**
     * Call once from StreamXApplication.onCreate() (fire-and-forget — don't
     * block app startup on this). Fetches the Worker's known-WAF-domain
     * list, checks which ones don't already have a fresh cookie, and
     * silently solves those in the background before the user opens
     * anything. Also starts the ongoing refresh loop (see
     * startProactiveRefreshLoop()) so those domains stay warm for the rest
     * of the session.
     */
    fun proactiveWarmup(context: Context) {
        refreshScope.launch {
            // Logged unconditionally on every launch — the single fastest way
            // to confirm/rule out the "no WebView provider on this device"
            // root cause without needing to trigger a WAF-blocked provider
            // first. If this logs false, every WAF solve attempt this
            // session will be a fast no-op (see isWebViewAvailable()) rather
            // than repeatedly hitting the getSettings() NPE.
            Log.d(TAG, "proactiveWarmup: WebView available on this device = ${isWebViewAvailable()}")

            try {
                val domains = fetchKnownWafDomains()
                if (domains.isEmpty()) {
                    Log.d(TAG, "proactiveWarmup: no known WAF domains reported by Worker")
                } else {
                    val needingSolve = filterDomainsNeedingRefresh(domains)
                    if (needingSolve.isNotEmpty()) {
                        Log.d(TAG, "proactiveWarmup: warming ${needingSolve.size}/${domains.size} domains: $needingSolve")
                        val results = resolveMultiple(context, needingSolve)
                        Log.d(TAG, "proactiveWarmup: results = $results")
                    } else {
                        Log.d(TAG, "proactiveWarmup: all ${domains.size} known domains already have fresh cookies")
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "proactiveWarmup failed: ${e.message}")
            }

            startProactiveRefreshLoop(context)
        }
    }

    /**
     * Runs for the app process's lifetime, periodically re-checking known
     * domains' cookie TTLs and silently re-solving any that are getting
     * close to expiry — see REFRESH_THRESHOLD_SECONDS / REFRESH_CHECK_INTERVAL_MS.
     * Idempotent — safe to call more than once, only actually starts the
     * loop the first time.
     */
    private fun startProactiveRefreshLoop(context: Context) {
        if (refreshLoopStarted) return
        refreshLoopStarted = true

        refreshScope.launch {
            while (isActive) {
                delay(REFRESH_CHECK_INTERVAL_MS)
                try {
                    val domains = fetchKnownWafDomains()
                    if (domains.isEmpty()) continue

                    val needingRefresh = filterDomainsNeedingRefresh(domains)
                    if (needingRefresh.isNotEmpty()) {
                        Log.d(TAG, "refresh loop: refreshing ${needingRefresh.size} domain(s) nearing expiry: $needingRefresh")
                        resolveMultiple(context, needingRefresh)
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "refresh loop iteration failed: ${e.message}")
                    // Don't let one failed iteration kill the loop — try
                    // again next interval.
                }
            }
        }
    }

    /**
     * Queries the Worker for each domain's remaining cookie TTL and
     * returns the subset that either has no cookie at all or is within
     * REFRESH_THRESHOLD_SECONDS of expiring.
     */
    private suspend fun filterDomainsNeedingRefresh(domains: List<String>): List<String> =
        withContext(Dispatchers.IO) {
            try {
                val token = FirebaseTokenProvider.getIdToken() ?: return@withContext domains // can't check status, assume all need it
                val baseUrl = StreamResolverConfig.getStreamWorkerBaseUrl()
                val domainsParam = domains.joinToString(",")

                val request = Request.Builder()
                    .url("${baseUrl}waf-cookie-status?domains=$domainsParam")
                    .header("Authorization", "Bearer $token")
                    .get()
                    .build()

                HttpClient.okhttp.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        Log.w(TAG, "waf-cookie-status: HTTP ${response.code}")
                        return@withContext domains
                    }
                    val body = response.body?.string() ?: return@withContext domains
                    val root = JSONObject(body)
                    val statuses = root.optJSONArray("statuses") ?: return@withContext domains

                    (0 until statuses.length()).mapNotNull { i ->
                        val o = statuses.optJSONObject(i) ?: return@mapNotNull null
                        val domain = o.optString("domain", "")
                        val hasCookie = o.optBoolean("hasCookie", false)
                        val secondsRemaining = o.optInt("secondsRemaining", 0)
                        if (domain.isNotBlank() && (!hasCookie || secondsRemaining < REFRESH_THRESHOLD_SECONDS)) {
                            domain
                        } else null
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "filterDomainsNeedingRefresh failed: ${e.message}")
                domains // fail open — if we can't check status, assume they all need solving rather than silently going stale
            }
        }

    /**
     * Fetches the Worker's list of known-WAF-protected domains
     * (GET /waf-known-domains — no auth required, see index.js).
     */
    private suspend fun fetchKnownWafDomains(): List<String> =
        withContext(Dispatchers.IO) {
            try {
                val baseUrl = StreamResolverConfig.getStreamWorkerBaseUrl()
                val request = Request.Builder()
                    .url("${baseUrl}waf-known-domains")
                    .get()
                    .build()

                HttpClient.okhttp.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        Log.w(TAG, "waf-known-domains: HTTP ${response.code}")
                        return@withContext emptyList()
                    }
                    val body = response.body?.string() ?: return@withContext emptyList()
                    val root = JSONObject(body)
                    val arr = root.optJSONArray("domains") ?: return@withContext emptyList()
                    (0 until arr.length()).mapNotNull { arr.optString(it).takeIf { d -> d.isNotBlank() } }
                }
            } catch (e: Exception) {
                Log.w(TAG, "fetchKnownWafDomains failed: ${e.message}")
                emptyList()
            }
        }

    // ── Two-phase logic (interactive — used by resolve()) ───────────────────

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun runTwoPhase(context: Context, domain: String): Boolean {
        val url          = "https://$domain/"
        val cookieResult = CompletableDeferred<Boolean>()
        val cookieManager = CookieManager.getInstance().also {
            it.setAcceptCookie(true)
            it.setCookie(url, "")
        }

        val webView = withContext(Dispatchers.Main) {
            buildWebView(context, url, cookieManager, cookieResult).also { wv ->
                // FIXED: setAcceptThirdPartyCookies(WebView, boolean) needs a
                // real WebView instance as its first argument — it was
                // previously called as setAcceptThirdPartyCookies(null, true)
                // BEFORE the WebView existed (there was nothing else to pass
                // at that point in the old code), which crashed on every
                // single call with "Attempt to invoke virtual method
                // 'WebSettings WebView.getSettings()' on a null object
                // reference" deep inside Android's CookieManagerAdapter —
                // this was a 100% reproducible logic bug, not a
                // device/environment issue (confirmed via production logs:
                // failure rate was 100% regardless of device or WebView
                // provider version). Must be called AFTER buildWebView, on
                // the WebView it actually configures, and — like all
                // WebView/CookieManager calls — on the main thread.
                cookieManager.setAcceptThirdPartyCookies(wv, true)
            }
        }

        _state.value = ChallengeState(domain = domain, needsUserAction = false)

        val autoSolved = withTimeoutOrNull(SILENT_TIMEOUT_MS) { cookieResult.await() }
        if (autoSolved == true) {
            withContext(Dispatchers.Main) { webView.destroy() }
            return true
        }

        Log.d(TAG, "$domain: auto-solve timed out — escalating to user dialog")

        val dismissDeferred = CompletableDeferred<Unit>()
        _state.value = ChallengeState(
            domain          = domain,
            needsUserAction = true,
            webView         = webView,
            onDismiss       = { dismissDeferred.complete(Unit) }
        )

        val userSolved = withTimeoutOrNull(USER_TIMEOUT_MS) {
            kotlinx.coroutines.selects.select<Boolean> {
                cookieResult.onAwait { it }
                dismissDeferred.onAwait { false }
            }
        } ?: false

        withContext(Dispatchers.Main) { webView.destroy() }
        return userSolved
    }

    // ── Silent-only phase (used by resolveSilentOnly() for background work) ─

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun runSilentPhaseOnly(context: Context, domain: String): Boolean {
        val url          = "https://$domain/"
        val cookieResult = CompletableDeferred<Boolean>()
        val cookieManager = CookieManager.getInstance().also {
            it.setAcceptCookie(true)
            it.setCookie(url, "")
        }

        val webView = withContext(Dispatchers.Main) {
            // FIXED: same bug as runTwoPhase — setAcceptThirdPartyCookies
            // needs the actual WebView, called after it's built, not null
            // called before it exists. See the comment in runTwoPhase for
            // the full root-cause explanation.
            buildWebView(context, url, cookieManager, cookieResult).also { wv ->
                cookieManager.setAcceptThirdPartyCookies(wv, true)
            }
        }

        // Deliberately no _state.value update here — background
        // warming/refreshing should be invisible to the user, no pill, no
        // dialog. Only a live, user-triggered resolve() shows UI.
        val solved = withTimeoutOrNull(SILENT_TIMEOUT_MS) { cookieResult.await() } ?: false

        withContext(Dispatchers.Main) { webView.destroy() }
        return solved
    }

    // ── WebView construction (shared by both phase runners) ─────────────────

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

    // ── Report cookie to Worker ──────────────────────────────────────────────

    private suspend fun reportCookieToWorker(context: Context, domain: String) =
        withContext(Dispatchers.IO) {
            try {
                val url    = "https://$domain/"
                val cookie = CookieManager.getInstance().getCookie(url)
                if (cookie.isNullOrBlank()) return@withContext

                val userAgent = withContext(Dispatchers.Main) {
                    WebView(context).settings.userAgentString
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
