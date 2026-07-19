package com.aeoncorex.streamx.streaming

import android.app.Application
import android.content.Context
import android.util.Log
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
//  WAF retry flow (new in this version):
//  ───────────────────────────────────────
//  When the Worker returns wafBlockedDomain for a provider (meaning it got
//  a Cloudflare/WAF challenge on that domain and returned 0 streams), this
//  engine:
//    1. Calls WafCookieResolver.resolve(domain) — runs an on-device WebView
//       against https://<domain>/, which is a real Chromium browser and CAN
//       solve the challenge. The resolver also POSTs the resulting cookie to
//       the Worker's POST /waf-cookie endpoint (see wafCookieStore.js) so the
//       Worker's next fetch() to that domain injects the cookie automatically.
//    2. Retries the same /resolve call once. If the cookie is now valid on
//       the Worker side, this retry returns the actual streams.
//
//  WafCookieResolver needs an Android Context (WebView requires one). This
//  engine stores it via init(context) called from StreamXApplication.onCreate()
//  — the Application context, which is always available and never leaks.
//
//  One retry maximum per provider per fetchFromWorker() call. If the retry
//  also returns 0 (the site is genuinely down, or the challenge didn't
//  resolve cleanly), we move on rather than looping — the point is to fix
//  recoverable WAF blocks, not to hammer unreachable origins.
// ═════════════════════════════════════════════════════════════════════════════
object WorkerStreamProviderEngine {

    private const val TAG = "WorkerStreamEngine"

    private const val DEFAULT_PROVIDER_TIMEOUT_MS   = 12_000L
    private const val AUTOEMBED_PROVIDER_TIMEOUT_MS = 25_000L
    // Extra budget for the WAF solve + retry: the WebView gets up to 15s
    // (see WafCookieResolver.CHALLENGE_TIMEOUT_MS), plus one extra Worker
    // round-trip. This gives WAF-retry providers an overall cap of the
    // per-provider timeout + this constant — still bounded, still can't
    // hang the whole fan-out indefinitely.
    private const val WAF_RETRY_EXTRA_MS            = 18_000L
    private const val HARD_CAP                      = 20

    @Volatile private var appContext: Context? = null

    /**
     * Must be called once from StreamXApplication.onCreate() before any
     * fetch() call. Safe to call multiple times (idempotent after the first).
     */
    fun init(context: Context) {
        if (appContext == null) appContext = context.applicationContext
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
                .distinctBy { it.url.split("?").first().trimEnd('/') }
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
    ): List<StreamResult> {
        // First attempt — within normal per-provider timeout
        val first = safe(provider) {
            withTimeoutOrNull(timeoutFor(provider)) {
                StreamResolverClient.resolve(
                    provider = provider,
                    title    = req.title,
                    tmdbId   = req.tmdbId,
                    imdbId   = req.imdbId,
                    type     = type,
                    season   = if (req.isSeries) req.season else null,
                    episode  = if (req.isSeries) req.episode else null
                )
            } ?: StreamResolverClient.ResolveResult(emptyList(), null)
                .also { Log.w(TAG, "$provider: timed out after ${timeoutFor(provider)}ms") }
        } ?: return emptyList()

        // Got streams — no WAF issue, done
        if (first.streams.isNotEmpty()) return first.streams

        // Got 0 streams but no WAF signal — normal empty result (site down,
        // no match, dead domain etc.) — nothing we can do, return empty
        val blockedDomain = first.wafBlockedDomain ?: return emptyList()

        // WAF block detected. Need a Context for the WebView.
        val ctx = appContext ?: run {
            Log.w(TAG, "$provider: waf-blocked on $blockedDomain but no Context — call init() from Application.onCreate()")
            return emptyList()
        }

        Log.d(TAG, "$provider: WAF block on $blockedDomain — attempting on-device solve + retry")

        // WAF solve runs within an extra time budget on top of what the
        // provider already used. withTimeoutOrNull here rather than
        // inside WafCookieResolver because the resolver has its own
        // internal CHALLENGE_TIMEOUT_MS cap — this outer cap guards
        // against the WebView + POST + Worker round-trip together.
        val retryStreams = withTimeoutOrNull(WAF_RETRY_EXTRA_MS) {
            val solved = WafCookieResolver.resolve(ctx, blockedDomain)
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
        return retryStreams
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
