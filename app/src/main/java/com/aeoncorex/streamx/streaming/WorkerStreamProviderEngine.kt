package com.aeoncorex.streamx.streaming

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

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

    private const val TAG        = "WorkerStreamEngine"
    private const val TIMEOUT_MS = 35_000L
    private const val HARD_CAP   = 20

    // Providers enabled on the Worker side. Kept here too (rather than
    // asking the Worker's /config every call) so a per-request provider
    // list doesn't add a network round trip to every single search —
    // /config is only consulted for the base URL. If the Worker's
    // ENABLED_PROVIDERS ever diverges from this list, the Worker itself
    // is still the source of truth: it rejects any provider name that
    // isn't actually enabled, so a stale entry here just wastes one
    // failed call rather than serving something wrong.
    private val PROVIDERS = listOf("autoEmbed", "animetsu", "flixhq", "multi")

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

            Log.d(TAG, "'${req.title}': resolving via ${PROVIDERS.size} Worker providers")

            val jobs = PROVIDERS.map { provider ->
                async(Dispatchers.IO) {
                    safe(provider) {
                        withTimeout(TIMEOUT_MS) {
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
