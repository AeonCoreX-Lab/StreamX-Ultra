package com.aeoncorex.streamx.streaming

import android.util.Log
import com.aeoncorex.streamx.streaming.transport.HttpAddonTransport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

// ═════════════════════════════════════════════════════════════════════════════
//  JsStreamProviderEngine.kt  —  FIXED v2
//
//  FIX 1 — On-demand stream.js re-download
//    If AddonStorage.getModules(addon).stream is null/blank, it means the
//    addon was "installed" but its JS was never successfully fetched (e.g.,
//    because DefaultAddonManager used the wrong sourceUrl on first seed).
//    We now attempt a one-shot re-download from the canonical source URL
//    before giving up and returning empty — so the first real playback
//    attempt auto-heals without needing an explicit "Update" in the UI.
//
//  FIX 2 — buildVegaLink: add 'title' field
//    Some providers (e.g., katmovies, 4khdhub via scraper fallback) use
//    `payload.title` for a fallback Google/site search when tmdbId is absent.
//    Adding it costs nothing and improves scraper-style provider hit rate.
//
//  FIX 3 — HTTP addon: robust ID selection
//    If the addon's idPrefixes include "tt" AND we have an imdbId, always
//    prefer the IMDB ID even when a tmdb: ID is also available.
//    Some addons (Torrentio) only work with the tt prefix and silently
//    return [] for tmdb: queries.
//
//  FIX 4 — AddonStorage.getHttpAddonsForResource always-false Map<*,*> check
//    (tracked as compile warning, will error in Kotlin 2.4)
//    Moved the resource-name extraction to a top-level helper that handles
//    both String and JSONObject resource entries without the unsound cast.
// ═════════════════════════════════════════════════════════════════════════════
object JsStreamProviderEngine {

    private const val TAG        = "JsStreamEngine"
    private const val TIMEOUT_MS = 35_000L
    private const val HARD_CAP   = 20

    // ── Public API ────────────────────────────────────────────────────────────

    suspend fun fetch(req: ProviderRequest): List<StreamResult> {
        val key = StreamCache.streamKey(req)
        StreamCache.getStreams(key)?.let { return it }
        val stale = StreamCache.getStaleStreams(key)
        if (stale != null) { PrefetchEngine.prefetch(req); return stale }
        val results = fetchFromNetwork(req)
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
                val fresh = fetchFromNetwork(req)
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

    // ── Core fetch ────────────────────────────────────────────────────────────

    private suspend fun fetchFromNetwork(req: ProviderRequest): List<StreamResult> =
        coroutineScope {
            val type   = if (req.isSeries) "series" else "movie"
            val imdbId = HttpAddonTransport.buildVideoId(req)        // "tt.." or "tmdb:.."
            val tmdbId = req.tmdbId?.let {
                "tmdb:$it" + if (req.isSeries) ":${req.season}:${req.episode}" else ""
            }

            // ── Type A: HTTP addons (Stremio protocol) ────────────────────────
            // FIX 3: prefer tt-prefix id for addons that declare "tt" idPrefixes.
            val httpAddons = AddonStorage.getHttpAddons().filter { desc ->
                desc.manifest.supportsStream(type, imdbId) ||
                (tmdbId != null && desc.manifest.supportsStream(type, tmdbId))
            }

            // ── Type B: Bundle JS addons ──────────────────────────────────────
            val bundleAddons = AddonStorage.getInstalled().filter { !it.disabled }

            Log.d(TAG, "'${req.title}': ${httpAddons.size} HTTP + ${bundleAddons.size} bundle addons")
            if (httpAddons.isEmpty() && bundleAddons.isEmpty()) {
                Log.w(TAG, "No addons installed or enabled")
                return@coroutineScope emptyList()
            }

            // ── Launch all in parallel ────────────────────────────────────────
            val httpJobs = httpAddons.map { desc ->
                async(Dispatchers.IO) {
                    safe(desc.manifest.name) {
                        withTimeout(TIMEOUT_MS) {
                            // FIX 3: always use imdbId (tt prefix) when the addon
                            // declares it in idPrefixes — avoids Torrentio returning []
                            val id = when {
                                !req.imdbId.isNullOrEmpty() &&
                                desc.manifest.idPrefixes?.any { p -> imdbId.startsWith(p) } == true
                                    -> imdbId
                                tmdbId != null && desc.manifest.supportsStream(type, tmdbId)
                                    -> tmdbId
                                else -> imdbId
                            }
                            fetchFromHttpAddon(desc, req, id)
                        }
                    }
                }
            }

            val bundleJobs = bundleAddons.map { addon ->
                async(Dispatchers.IO) {
                    safe(addon.displayName) {
                        withTimeout(TIMEOUT_MS) { fetchFromBundleAddon(addon, req) }
                    }
                }
            }

            (httpJobs + bundleJobs)
                .flatMap  { it.await() }
                .distinctBy { it.url.split("?").first().trimEnd('/') }
                .filter   { it.url.startsWith("http") }
                .sortedWith(resultComparator())
                .take(HARD_CAP)
                .also { Log.d(TAG, "Total streams merged: ${it.size}") }
        }

    // ── Type A: GET /stream/{type}/{id}.json ──────────────────────────────────

    private suspend fun fetchFromHttpAddon(
        desc: AddonDescriptor,
        req:  ProviderRequest,
        id:   String = HttpAddonTransport.buildVideoId(req)
    ): List<StreamResult> = withContext(Dispatchers.IO) {
        val transport = HttpAddonTransport(desc.transportUrl)
        val type      = if (req.isSeries) "series" else "movie"
        Log.d(TAG, "${desc.manifest.name}: GET stream/$type/$id.json")
        transport.streams(type, id).mapNotNull { it.toStreamResult(desc.manifest.name) }
    }

    // ── Type B: execute stream.js via QuickJS ─────────────────────────────────
    //
    // FIX 1: if stream.js is not cached, attempt a one-shot on-demand
    // re-download from the canonical source URL before giving up.
    // This auto-heals addons that were seeded with the old wrong URL.

    private suspend fun fetchFromBundleAddon(
        addon: AddonInfo,
        req:   ProviderRequest
    ): List<StreamResult> = withContext(Dispatchers.IO) {

        var code = AddonStorage.getModules(addon.value, addon.sourceAuthor)?.stream

        // FIX 1: on-demand re-download if stream.js is missing
        if (code.isNullOrBlank()) {
            Log.d(TAG, "${addon.value}: stream.js not in cache — attempting on-demand download")
            val fixedAddon = addon.copy(
                sourceAuthor = DefaultAddonManager.DEFAULT_AUTHOR,
                sourceUrl    = DefaultAddonManager.DEFAULT_SOURCE_URL
            )
            runCatching {
                AddonManager.downloadModules(
                    fixedAddon.sourceUrl,
                    fixedAddon.sourceAuthor,
                    fixedAddon.value,
                    fixedAddon.version
                )
                // Update the installed record so next call uses correct author/url
                AddonStorage.install(fixedAddon.copy(installed = true))
            }.onSuccess {
                code = AddonStorage.getModules(
                    fixedAddon.value, fixedAddon.sourceAuthor
                )?.stream
                Log.d(TAG, "${addon.value}: on-demand download OK, stream.js=${code?.length ?: 0} chars")
            }.onFailure {
                Log.w(TAG, "${addon.value}: on-demand download FAILED: ${it.message}")
            }
        }

        if (code.isNullOrBlank()) {
            Log.d(TAG, "${addon.value}: no stream.js available — skipping")
            return@withContext emptyList()
        }

        val link = buildVegaLink(req)
        Log.d(TAG, "${addon.displayName}: executing stream.js, link=$link")
        StreamXNative.executeJsStream(code, link, req.isSeries, addon.displayName)
    }

    // ── Vega link builder ─────────────────────────────────────────────────────
    //
    // Returns a JSON string that Vega-style stream.js providers parse:
    //   const payload = JSON.parse(id)
    //   tmdbId = payload.tmdbId
    //   imdbId = payload.imdbId
    //   season/episode for series
    //
    // FIX 2: added 'title' field — used by scraper-style providers as a
    // fallback search term when no tmdbId is available.

    private fun buildVegaLink(req: ProviderRequest): String {
        val obj = org.json.JSONObject()
        req.tmdbId?.let { obj.put("tmdbId", it) }
        req.imdbId?.takeIf { it.isNotEmpty() }?.let { obj.put("imdbId", it) }
        req.year?.let { obj.put("year", it) }
        obj.put("title", req.title)        // FIX 2: scraper fallback
        if (req.isSeries && req.season > 0) {
            obj.put("season",  req.season)
            obj.put("episode", req.episode)
            obj.put("type", "series")
        } else {
            obj.put("type", "movie")
        }
        return obj.toString()
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
