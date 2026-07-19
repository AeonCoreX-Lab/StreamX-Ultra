package com.aeoncorex.streamx.streaming

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
//  executing addon stream.js bundles on-device via QuickJS/JNI
//  (StreamXNative.executeJsStream), every provider is resolved server-side
//  by the streamx-stream-resolver Cloudflare Worker — see
//  StreamResolverClient.kt and the Worker repo's README for the full
//  request/response contract.
//
//  Kept deliberately separate from JsStreamProviderEngine (not edited in
//  place) so the old on-device path stays available to compare against
//  while this is verified in the field; once confirmed working, the old
//  engine + AddonManager/AddonStorage/StreamXNative JS-execution plumbing
//  can be deleted and this becomes the only implementation.
//
//  Public contract is IDENTICAL to JsStreamProviderEngine — same
//  fetch()/fetchStreaming() signatures, same caching behavior via
//  StreamCache — so StreamProviderEngine only needs its two delegating
//  calls repointed, and no caller (ExoSourceSelectionScreen, PrefetchEngine)
//  needs to change.
// ═════════════════════════════════════════════════════════════════════════════
object WorkerStreamProviderEngine {

    private const val TAG = "WorkerStreamEngine"

    // Per-provider timeout — NOT the timeout for the whole fetch. Providers
    // run in parallel (see fetchFromWorker), so this bounds how long ANY
    // single slow provider can hold up the merge; it does not add up across
    // providers.
    //
    // autoEmbed gets a longer budget than everything else: its Worker-side
    // getRiveStream fans out to 11 services in parallel with an 8s cap each
    // (see streamx-stream-resolver's autoEmbed.stream.txt) — under real
    // network conditions (not all 11 responding instantly, Worker's own
    // outbound connection setup) that can genuinely take longer than a
    // single-request provider. Giving it more room lets its full result set
    // (including hindicast/asiacloud dub servers, which are LAST in that
    // service list) actually come back instead of being cut off mid-fan-out.
    // Every other provider is a single Worker call (posts→meta→stream, all
    // server-side) and shouldn't normally need anywhere near this long — if
    // one does, timing it out and moving on is correct so it doesn't hold up
    // the 33 providers that already finished.
    private const val DEFAULT_PROVIDER_TIMEOUT_MS   = 12_000L
    private const val AUTOEMBED_PROVIDER_TIMEOUT_MS = 25_000L
    private const val HARD_CAP                      = 20

    private fun timeoutFor(provider: String): Long =
        if (provider == "autoEmbed") AUTOEMBED_PROVIDER_TIMEOUT_MS else DEFAULT_PROVIDER_TIMEOUT_MS

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
            val type = if (req.isSeries) "series" else "movie"

            // Pulled from the Worker's own /config (cached after first call —
            // see StreamResolverConfig) instead of a hardcoded list here.
            // The Worker's enabled-provider set has grown well past a fixed
            // handful and can change independently of an app release (a
            // provider can be disabled Worker-side without an app update),
            // so hardcoding it here just guarantees drift — this is exactly
            // what caused animetsu (removed Worker-side) to still be called
            // from here, and 33 newer providers to never be called at all.
            val providers = StreamResolverConfig.getEnabledProviders()

            Log.d(TAG, "'${req.title}': resolving via ${providers.size} Worker providers")

            val jobs = providers.map { provider ->
                async(Dispatchers.IO) {
                    safe(provider) {
                        // withTimeoutOrNull, not withTimeout: a single slow
                        // provider should degrade to "no results from this
                        // one" and let the rest of the parallel batch keep
                        // going, not throw a TimeoutCancellationException
                        // that awaitAll() would otherwise have to deal with.
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
                        } ?: emptyList<StreamResult>().also {
                            Log.w(TAG, "$provider: timed out after ${timeoutFor(provider)}ms")
                        }
                    }
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

    private suspend fun safe(
        name:  String,
        block: suspend () -> List<StreamResult>
    ): List<StreamResult> = try {
        block().also { Log.d(TAG, "$name → ${it.size} streams") }
    } catch (e: Exception) {
        Log.w(TAG, "$name failed: ${e.javaClass.simpleName}: ${e.message}")
        emptyList()
    }
}