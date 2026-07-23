package com.aeoncorex.streamx.streaming

import android.app.Application
import android.content.Context
import android.util.Log
import android.view.ContextThemeWrapper
import com.aeoncorex.streamx.R
import com.aeoncorex.streamx.network.StreamResolverConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

// ═════════════════════════════════════════════════════════════════════════════
//  WorkerStreamProviderEngine.kt
//  ─────────────────────────────────────────────────────────────────────────
//  Replaces JsStreamProviderEngine as the backing implementation behind
//  StreamProviderEngine.fetch()/fetchStreaming(). Instead of downloading and
//  executing addon stream.js bundles on-device via QuickJS/JNI, every
//  provider is resolved server-side by the streamx-stream-resolver
//  Cloudflare Worker — see StreamResolverClient.kt for the HTTP layer.
//
//  WAF retry flow:
//  ───────────────────────────────────────
//  When the Worker returns wafBlockedDomain for a provider (meaning it got
//  a Cloudflare/WAF challenge on that domain and returned 0 streams), this
//  engine:
//    1. Calls WafCookieResolver.resolveForLiveRetry(domain) — runs an
//       on-device, invisible-only WebView against https://<domain>/, which
//       is a real Chromium browser and CAN solve the challenge. The
//       resolver also POSTs the resulting cookie to the Worker's POST
//       /waf-cookie endpoint (see wafCookieStore.js) so the Worker's next
//       fetch() to that domain injects the cookie automatically.
//    2. Retries the same /resolve call once. If the cookie is now valid on
//       the Worker side, this retry returns the actual streams.
//
//  IMPORTANT (fixed 2026-07-22): this deliberately calls
//  resolveForLiveRetry(), NOT WafCookieResolver's public two-phase
//  resolve(). resolve() can escalate to a 120-second user-visible dialog
//  (Phase 2, for interactive challenges the invisible WebView can't solve
//  alone) — appropriate when the user explicitly triggers a solve, but
//  wrong here: this WAF retry fires automatically mid-/resolve-call while
//  the user is just trying to load a movie source, so surfacing an
//  unrequested captcha popup would be confusing, and the two calls used to
//  SHARE in-flight coalescing (same domain, same underlying WebView
//  attempt) — meaning THIS function's short outer timeout could cancel a
//  DIFFERENT, already-escalated-to-Phase-2 solve that a background warmup
//  pass had started for the same domain. Confirmed in production logs
//  (2026-07-23): every WAF-retry attempt for domains under active
//  background warming died within ~10s of Phase 2 starting, well short of
//  Phase 2's real 120s budget. resolveForLiveRetry() is Phase-1-only
//  (invisible auto-solve, never escalates) — see its doc comment in
//  WafCookieResolver.kt for the full explanation.
//
//  WafCookieResolver needs an Android Context (WebView requires one). This
//  engine stores it via init(context) called from StreamXApplication.onCreate()
//  — wrapped in a ContextThemeWrapper (see init()'s doc comment for why a
//  raw Application Context isn't enough on its own), so it's always
//  available and never leaks an Activity reference.
//
//  One retry maximum per provider per fetchFromWorker() call. If the retry
//  also returns 0 (the site is genuinely down, or the challenge didn't
//  resolve cleanly), we move on rather than looping — the point is to fix
//  recoverable WAF blocks, not to hammer unreachable origins.
// ═════════════════════════════════════════════════════════════════════════════
object WorkerStreamProviderEngine {

    private const val TAG = "WorkerStreamEngine"

    private const val DEFAULT_PROVIDER_TIMEOUT_MS   = 12_000L
    // autoEmbed used to need a much longer budget when its Worker-side
    // getRiveStream fanned out to 11 parallel services (8s cap each) — that
    // was trimmed to 5 dub-priority services (hindicast, asiacloud, animez,
    // flowcast, ophim) on 2026-07-19 after Cloudflare's Free-plan 10ms CPU
    // limit was regularly killing /resolve mid-fan-out (processing 11
    // parallel responses' worth of JSON + the custom secret-key hash
    // function pushed it over budget — see streamx-stream-resolver's
    // autoEmbed.stream.txt). With only 5 services now, autoEmbed doesn't
    // need dramatically more room than any other single-call provider, so
    // this is close to DEFAULT rather than more than double it.
    private const val AUTOEMBED_PROVIDER_TIMEOUT_MS = 15_000L
    // Extra budget for the WAF solve + retry. resolveForLiveRetry() is
    // Phase-1-only (invisible auto-solve, WafCookieResolver.SILENT_TIMEOUT_MS
    // = 8s internally), so this only needs to cover that plus round-trip
    // overhead (WebView setup, the cookie-report POST, and the follow-up
    // /resolve call) — NOT WafCookieResolver's separate 120s interactive
    // Phase 2 budget, since that path is never reached from here anymore
    // (see the WAF retry flow comment above for why). Previously this was
    // 18_000L while the call site used the two-phase resolve() — too short
    // for Phase 2 if it ever got reached via in-flight coalescing, which is
    // exactly what production logs caught happening. 12s gives the 8s
    // silent-phase timeout a comfortable ~4s of headroom for the actual
    // network round-trips around it.
    private const val WAF_RETRY_EXTRA_MS            = 12_000L
    private const val HARD_CAP                      = 20

    @Volatile private var appContext: Context? = null

    /**
     * Exposes the themed context this engine wraps and stores in init() —
     * used by StreamXApplication so WafCookieResolver.proactiveWarmup()
     * (background WAF-domain warming at app startup, not tied to any live
     * resolve() call) can reuse the SAME properly-themed Context instead
     * of needing its own copy of the ContextThemeWrapper logic. Returns
     * null if init() hasn't been called yet.
     */
    fun getThemedContext(): Context? = appContext

    /**
     * Must be called once from StreamXApplication.onCreate() before any
     * fetch() call. Safe to call multiple times (idempotent after the first).
     *
     * IMPORTANT: WebView(context) requires a themed UI Context — a raw
     * Application Context is NOT sufficient and reliably crashes on
     * WebView construction (NPE inside WebView's internal
     * getSettings()/factory init on API 28+, since it can't resolve a
     * theme to inflate its internal chrome from). This was the actual
     * cause of every single WAF-retry attempt failing in production
     * (see the 2026-07-20 adb log audit — 100% of WafCookieResolver
     * calls threw the same
     * "WebSettings WebView.getSettings() on a null object reference").
     *
     * The fix: wrap the Application context in a ContextThemeWrapper
     * carrying the app's own theme before storing it. This gives WebView
     * everything it needs to inflate without requiring an Activity —
     * the WebView itself is still never attached to any Activity's view
     * hierarchy (it lives only in WafCookieResolver's off-screen/dialog
     * usage), so this doesn't risk an Activity leak the way holding an
     * actual Activity reference here would.
     */
    fun init(context: Context) {
        if (appContext == null) {
            appContext = ContextThemeWrapper(context.applicationContext, R.style.Theme_StreamXUltra)
        }
    }

    private fun timeoutFor(provider: String): Long =
        if (provider == "autoEmbed") AUTOEMBED_PROVIDER_TIMEOUT_MS
        else DEFAULT_PROVIDER_TIMEOUT_MS

    // ── Public API — mirrors JsStreamProviderEngine exactly ────────────────────

    suspend fun fetch(req: ProviderRequest): List<StreamResult> {
        val key = StreamCache.streamKey(req)
        StreamCache.getStreams(key)?.let { return it }
        val stale = StreamCache.getStaleStreams(key)
        if (stale != null) { PrefetchEngine.prefetch(req); return stale }
        val results = fetchFromWorker(req)
        if (results.isNotEmpty()) StreamCache.putStreams(key, results)
        return results
    }

    fun fetchStreaming(req: ProviderRequest): Channel<List<StreamResult>> {
        val channel = Channel<List<StreamResult>>(Channel.UNLIMITED)
        val key     = StreamCache.streamKey(req)

        GlobalScope.launch(Dispatchers.IO) {
            try {
                val cached = StreamCache.getStreams(key) ?: StreamCache.getStaleStreams(key)
                if (cached != null) {
                    channel.send(cached)
                    if (StreamCache.getStreams(key) != null) { channel.close(); return@launch }
                }
                val fresh = fetchFromWorker(req)
                if (fresh.isNotEmpty()) {
                    StreamCache.putStreams(key, fresh)
                    channel.send(fresh)
                }
            } catch (e: Exception) {
                Log.w(TAG, "fetchStreaming error: ${e.message}")
            } finally {
                channel.close()
            }
        }
        return channel
    }

    // ── Core fetch — one Worker call per provider, run in parallel ─────────────

    private suspend fun fetchFromWorker(req: ProviderRequest): List<StreamResult> =
        coroutineScope {
            val type      = if (req.isSeries) "series" else "movie"
            val providers = StreamResolverConfig.getEnabledProviders()

            Log.d(TAG, "'${req.title}': resolving via ${providers.size} Worker providers")

            val jobs = providers.map { provider ->
                async(Dispatchers.IO) {
                    resolveWithWafRetry(provider, req, type)
                }
            }

            jobs.awaitAll()
                .flatten()
                // Dedup by provider+server+quality+language, NOT by URL.
                // Every stream's `url` is a Worker-signed `/play?sig=<token>`
                // link (see index.js) — the path is identical for every
                // single stream from every provider, only the sig token
                // differs. A URL-based distinctBy (even stripping the query
                // string first) collapses ALL streams down to one, since
                // they all normalize to the same bare path. This was a real
                // bug (fixed 2026-07-21): autoEmbed would successfully
                // resolve 9 streams (different languages/qualities/servers,
                // confirmed via StreamResolverClient's "→ 9 streams" log)
                // and this dedup would silently drop 8 of them, which is
                // also why alternate dubs (Hindi etc.) appeared to be
                // missing — they were resolved, then deduped away here.
                .distinctBy { "${it.source}|${it.quality}|${it.language}" }
                .filter   { it.url.startsWith("http") }
                .sortedWith(resultComparator())
                .take(HARD_CAP)
                .also { Log.d(TAG, "Total streams merged: ${it.size}") }
        }

    /**
     * Resolves one provider. If the Worker signals a WAF block and we have
     * a Context to run a WebView solve, solve it and retry once.
     */
    private suspend fun resolveWithWafRetry(
        provider: String,
        req:      ProviderRequest,
        type:     String
    ): List<StreamResult> = coroutineScope {
        // First attempt — within normal per-provider timeout.
        //
        // IMPORTANT: this used to be `withTimeoutOrNull(ms) { resolve(...) }`,
        // which has a race at the exact deadline boundary: if resolve()
        // finishes and returns its value in the same instant the timeout
        // fires, withTimeoutOrNull can still cancel the coroutine and
        // discard the already-produced result, returning null instead of
        // the real (successful!) ResolveResult. That's not a hypothetical —
        // the 2026-07-21 adb log audit caught it happening repeatedly:
        // "autoEmbed → 9 streams via resolver" (StreamResolverClient
        // successfully parsed 9 streams) immediately followed by
        // "autoEmbed: timed out after 15000ms" / "autoEmbed → 0 streams"
        // in the SAME millisecond — the 9 streams were real and already
        // in hand, but got thrown away by the cancellation race.
        //
        // This got much more likely to trigger once Cloudflare's CPU
        // limit started being hit on nearly every provider call (see the
        // "error code: 1102" flood in that log — a resource-limit error
        // from Cloudflare itself, not from this Worker's own code): every
        // resolve() now runs close to its full timeout budget instead of
        // finishing early, so far more requests land right at the
        // deadline where this race can fire.
        //
        // Fix: run resolve() in its own async, and after the timeout
        // window, check whether it actually completed instead of letting
        // cancellation silently swallow a real result. isCompleted is
        // checked BEFORE calling await() to avoid triggering
        // cancellation on a deferred that finished microseconds after the
        // delay but before we got to it — either way, a completed result
        // is used if one exists, and only a genuinely-still-running call
        // gets cancelled.
        val deferred = async {
            StreamResolverClient.resolve(
                provider = provider,
                title    = req.title,
                tmdbId   = req.tmdbId,
                imdbId   = req.imdbId,
                type     = type,
                season   = if (req.isSeries) req.season else null,
                episode  = if (req.isSeries) req.episode else null
            )
        }

        val timeoutMs = timeoutFor(provider)
        val raced = withTimeoutOrNull(timeoutMs) { deferred.await() }

        val first = if (raced != null) {
            raced
        } else if (deferred.isCompleted) {
            // Genuinely completed right at the boundary — withTimeoutOrNull
            // returned null from the cancellation race, but the deferred
            // itself finished. Recover its real result instead of
            // discarding it. getCompleted() is safe here precisely because
            // isCompleted just confirmed it's done (no suspension risk).
            try {
                deferred.getCompleted()
            } catch (e: Exception) {
                Log.w(TAG, "$provider: completed with exception at boundary: ${e.message}")
                StreamResolverClient.ResolveResult(emptyList(), null)
            }
        } else {
            // Actually still running past its budget — a real timeout,
            // not a boundary race. Cancel it and move on.
            deferred.cancel()
            Log.w(TAG, "$provider: timed out after ${timeoutMs}ms")
            StreamResolverClient.ResolveResult(emptyList(), null)
        }

        // Got streams — no WAF issue, done
        if (first.streams.isNotEmpty()) return@coroutineScope first.streams

        // Got 0 streams but no WAF signal — normal empty result (site down,
        // no match, dead domain etc.) — nothing we can do, return empty
        val blockedDomain = first.wafBlockedDomain ?: return@coroutineScope emptyList()

        // WAF block detected. Need a Context for the WebView.
        val ctx = appContext ?: run {
            Log.w(TAG, "$provider: waf-blocked on $blockedDomain but no Context — call init() from Application.onCreate()")
            return@coroutineScope emptyList()
        }

        Log.d(TAG, "$provider: WAF block on $blockedDomain — attempting on-device solve + retry")

        // WAF solve runs within an extra time budget on top of what the
        // provider already used. withTimeoutOrNull here rather than inside
        // WafCookieResolver because resolveForLiveRetry() has its own
        // internal SILENT_TIMEOUT_MS cap — this outer cap guards against
        // the WebView + POST + Worker round-trip together. Uses
        // resolveForLiveRetry() specifically (not the public two-phase
        // resolve()) — see this file's WAF retry flow header comment and
        // that function's doc comment in WafCookieResolver.kt for why
        // calling the two-phase version here was a confirmed production bug.
        val retryStreams = withTimeoutOrNull(WAF_RETRY_EXTRA_MS) {
            val solved = WafCookieResolver.resolveForLiveRetry(ctx, blockedDomain)
            if (!solved) {
                Log.w(TAG, "$provider: WAF solve failed for $blockedDomain — giving up")
                return@withTimeoutOrNull emptyList<StreamResult>()
            }

            // Cookie is now in the Worker's KV for this domain.
            // Retry the same resolve — this time the Worker's globalFetchPatch
            // will inject the cookie into its outgoing fetch() calls.
            Log.d(TAG, "$provider: WAF solved, retrying resolve for $blockedDomain")
            safe(provider) {
                StreamResolverClient.resolve(
                    provider = provider,
                    title    = req.title,
                    tmdbId   = req.tmdbId,
                    imdbId   = req.imdbId,
                    type     = type,
                    season   = if (req.isSeries) req.season else null,
                    episode  = if (req.isSeries) req.episode else null
                )
            }?.streams ?: emptyList()
        } ?: emptyList<StreamResult>().also {
            Log.w(TAG, "$provider: WAF retry timed out for $blockedDomain")
        }

        Log.d(TAG, "$provider: WAF retry → ${retryStreams.size} streams")
        retryStreams
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun resultComparator() = compareByDescending<StreamResult> {
        when {
            it.quality.contains("4K",   ignoreCase = true) ||
            it.quality.contains("2160", ignoreCase = true) -> 40
            it.quality.contains("1080", ignoreCase = true) -> 30
            it.quality.contains("720",  ignoreCase = true) -> 20
            it.quality.contains("HD",   ignoreCase = true) -> 15
            it.quality.contains("480",  ignoreCase = true) -> 10
            else                                           ->  1
        }
    }

    // Returns null on exception (unlike the old version which returned
    // emptyList) so the WAF-retry path can distinguish "safe() swallowed
    // an exception" from "resolve returned a real ResolveResult with 0
    // streams + a wafBlockedDomain" — those need different responses.
    private suspend fun safe(
        name:  String,
        block: suspend () -> StreamResolverClient.ResolveResult
    ): StreamResolverClient.ResolveResult? = try {
        block().also { Log.d(TAG, "$name → ${it.streams.size} streams") }
    } catch (e: Exception) {
        Log.w(TAG, "$name failed: ${e.javaClass.simpleName}: ${e.message}")
        null
    }
}
