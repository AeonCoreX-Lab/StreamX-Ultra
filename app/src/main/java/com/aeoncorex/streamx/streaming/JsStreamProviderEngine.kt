package com.aeoncorex.streamx.streaming

import android.util.Log
import com.aeoncorex.streamx.streaming.transport.HttpAddonTransport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.mozilla.javascript.NativeArray
import org.mozilla.javascript.NativeObject
import org.mozilla.javascript.ScriptableObject
import org.mozilla.javascript.UniqueTag

// ═════════════════════════════════════════════════════════════════════════════
//  JsStreamProviderEngine.kt  —  Unified HTTP + Bundle addon engine
//
//  Queries two addon types in parallel:
//
//    Type A — HTTP Addon (Stremio protocol)
//             • From AddonStorage.getHttpAddons()
//             • Calls GET {baseUrl}/stream/{type}/{id}.json
//             • Any Stremio community addon works here ✓
//
//    Type B — Bundle JS Addon (Vega-style CJS stream.js)
//             • From AddonStorage.getInstalled()
//             • Executes stream.js via Rhino (JsEngine)
//
//  Both produce List<StreamResult> → merged, deduped, sorted, capped at 20.
//
//  ── FULL FIX LOG ──────────────────────────────────────────────────────────
//
//  FIX 1 — No HTTP requests from bundle addons
//    Root cause: Promise undefined in Rhino → every getStream() crash before
//    any network call.
//    Solution: JsEngine now injects SyncPromise + atob/btoa + fetch + process
//    polyfills. See JsEngine.kt for details.
//
//  FIX 2 — Cross-context Rhino error (NativeObject from dead context)
//    Root cause: old code called execModule() in context A, then Context.exit(),
//    then called getStream() in a fresh context B. Objects from A are invalid in B.
//    Solution: JsEngine.executeAndCallStream() does module execution AND
//    getStream() call inside ONE Rhino context/scope lifetime.
//    fetchFromBundleAddon() now calls executeAndCallStream() directly.
//    callGetStream() is removed.
//
//  FIX 3 — Wrong link format for bundle providers
//    Root cause: old code passed Stremio-style "tt1234567" or "tmdb:123" as link.
//    Providers like autoEmbed expect a JSON payload:
//      {"tmdbId":123,"imdbId":"tt456","season":1,"episode":2,"type":"series"}
//    Others (4khdhub, multi, world4u) need a website URL from the getMeta step
//    (those providers will still return empty until getMeta flow is added).
//    Solution: buildVegaLink() builds the JSON payload. autoEmbed/MultiStream
//    parses it via JSON.parse(id) and extracts tmdbId/imdbId directly. ✓
//
//  FIX 4 — axios.get().data not parsed as JSON
//    Root cause: providers do response.data.streams.forEach() expecting object.
//    Solution: JsEngine's module wrapper wraps providerContext.axios with a
//    smart proxy that auto-parses JSON and exposes response.headers.get(name).
//
//  FIX 5 — Promise result discarded
//    Root cause: parseBundleResults received the Promise object, not its value.
//    Solution: JsEngine.executeAndCallStream() calls resolvePromise() which reads
//    ._state/_value from our SyncPromise before returning.
//
//  FIX 6 — HTTP addons: try tmdb: prefix addons when no imdbId
//    Root cause: supportsStream was too strict — if req.imdbId is null,
//    id = "tmdb:123" but addon idPrefixes = ["tt"] → filtered out (correct).
//    The REAL fix is populating imdbId via TMDB external-IDs API upstream.
//    Interim fix here: if no IMDB ID available, we also try HTTP addons that
//    accept "tmdb:" prefix (MediaFusion supports this).
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
            val type     = if (req.isSeries) "series" else "movie"
            val imdbId   = HttpAddonTransport.buildVideoId(req)       // "tt.." or "tmdb:.."
            val tmdbId   = req.tmdbId?.let { "tmdb:$it" + (if (req.isSeries) ":${req.season}:${req.episode}" else "") }

            // ── Type A: HTTP addons (Stremio protocol) ────────────────────────
            //
            // Pass 1: addons that match the imdb-style id (tt prefix)
            // Pass 2: if we have no IMDB ID, also try addons that accept "tmdb:" ids
            //
            val httpAddons = AddonStorage.getHttpAddons().filter { desc ->
                val supportsImdb = desc.manifest.supportsStream(type, imdbId)
                val supportsTmdb = tmdbId != null && desc.manifest.supportsStream(type, tmdbId)
                supportsImdb || supportsTmdb
            }

            // ── Type B: Bundle JS addons (Vega-style) ────────────────────────
            val bundleAddons = AddonStorage.getInstalled().filter { !it.disabled }

            val total = httpAddons.size + bundleAddons.size
            Log.d(TAG, "'${req.title}': ${httpAddons.size} HTTP + ${bundleAddons.size} bundle addons")

            if (total == 0) {
                Log.w(TAG, "No addons installed")
                return@coroutineScope emptyList()
            }

            // ── Launch all jobs in parallel ───────────────────────────────────
            val httpJobs = httpAddons.map { desc ->
                async(Dispatchers.IO) {
                    safe(desc.manifest.name) {
                        withTimeout(TIMEOUT_MS) {
                            // Choose the right ID format for this addon
                            val id = if (
                                !req.imdbId.isNullOrEmpty() &&
                                desc.manifest.supportsStream(type, imdbId)
                            ) imdbId else tmdbId ?: imdbId
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
                .also { Log.d(TAG, "Total streams: ${it.size}") }
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

    // ── Type B: execute stream.js via Rhino ───────────────────────────────────
    //
    // CRITICAL CHANGE: we now call JsEngine.executeAndCallStream() which keeps
    // module execution and getStream() call inside ONE Rhino Context lifetime.
    // The old pattern (execModule → Context.exit → callGetStream → Context.enter)
    // caused cross-context NativeObject failures.
    //
    // Link format: JSON payload so direct providers (autoEmbed/MultiStream) can
    // extract tmdbId + imdbId + season + episode. Scraped providers that need a
    // website URL (4khdhub, world4u, multi) will return empty until the getMeta
    // flow is implemented.

    private suspend fun fetchFromBundleAddon(
        addon: AddonInfo,
        req:   ProviderRequest
    ): List<StreamResult> = withContext(Dispatchers.IO) {
        val code = AddonStorage.getModules(addon.value, addon.sourceAuthor)?.stream
        if (code.isNullOrBlank()) {
            Log.d(TAG, "${addon.value}: no stream.js cached")
            return@withContext emptyList()
        }

        // Build Vega-compatible JSON payload
        // autoEmbed/stream.js: JSON.parse(id) → { tmdbId, imdbId, season, episode, type }
        val link = buildVegaLink(req)
        Log.d(TAG, "${addon.displayName}: link=$link")

        val ctx    = JsProviderContext(addon.value)
        val rawResult = JsEngine.executeAndCallStream(code, ctx, link, req.isSeries)
        parseBundleResults(rawResult, addon.displayName)
    }

    // ── Vega link builder ─────────────────────────────────────────────────────
    //
    // Returns a JSON string that Vega-style stream.js providers can parse.
    //
    // autoEmbed parses it like:
    //   const payload = JSON.parse(id)   // or { tmdbId: id } as fallback
    //   tmdbId = payload.tmdbId ?? ""
    //   imdbId = payload.imdbId ?? ""
    //   season = payload.season ?? ""
    //   episode= payload.episode?? ""

    private fun buildVegaLink(req: ProviderRequest): String {
        val obj = org.json.JSONObject()
        req.tmdbId?.let { obj.put("tmdbId", it) }
        req.imdbId?.takeIf { it.isNotEmpty() }?.let { obj.put("imdbId", it) }
        req.year?.let { obj.put("year", it) }
        if (req.isSeries && req.season > 0) {
            obj.put("season",  req.season)
            obj.put("episode", req.episode)
            obj.put("type", "series")
        } else {
            obj.put("type", "movie")
        }
        return obj.toString()
    }

    // ── parseBundleResults ────────────────────────────────────────────────────
    //
    // Iterates a NativeArray of JS objects returned by getStream().
    // Each element looks like:
    //   { link: "https://...", type: "mp4", quality: "1080p", server: "Name" }

    private fun parseBundleResults(raw: Any?, source: String): List<StreamResult> {
        if (raw == null) return emptyList()
        val results = mutableListOf<StreamResult>()

        fun prop(o: NativeObject, k: String): String? {
            val v = ScriptableObject.getProperty(o, k)
            return if (v == null || v === UniqueTag.NOT_FOUND || v.toString() == "undefined") null
            else v.toString().trim().takeIf { it.isNotEmpty() }
        }

        fun parseOne(obj: Any?) {
            val o = obj as? NativeObject ?: return
            // providers use "link" (Vega) or "url" (Stremio) for the stream URL
            val url = prop(o, "link") ?: prop(o, "url") ?: return
            if (!url.startsWith("http")) return

            val quality = prop(o, "quality") ?: "Unknown"
            val server  = prop(o, "server")  ?: source
            val typeStr = prop(o, "type")    ?: "mp4"
            val lang    = prop(o, "language") ?: prop(o, "lang") ?: "Unknown"

            val streamType = when {
                typeStr.contains("m3u", ignoreCase = true) ||
                typeStr.contains("hls", ignoreCase = true)  -> StreamType.HLS
                typeStr.contains("dash", ignoreCase = true) -> StreamType.DASH
                typeStr.contains("mkv",  ignoreCase = true) -> StreamType.MKV
                else                                        -> StreamType.MP4
            }

            // Parse headers if provider supplies them (for DRM / Referer)
            val headers = mutableMapOf<String, String>()
            (ScriptableObject.getProperty(o, "headers") as? NativeObject)?.let { h ->
                h.ids.forEach { id ->
                    headers[id.toString()] = h.get(id.toString(), h)?.toString() ?: ""
                }
            }

            results.add(StreamResult(
                url      = url,
                quality  = quality,
                type     = streamType,
                source   = server,
                language = lang,
                label    = buildString {
                    append(quality)
                    if (server.isNotEmpty()) append(" • $server")
                },
                headers  = headers
            ))
        }

        when (raw) {
            is NativeArray -> (0 until raw.length).forEach { parseOne(raw[it]) }
            is NativeObject -> parseOne(raw)
            else -> Log.w(TAG, "parseBundleResults: unexpected type ${raw.javaClass.simpleName}")
        }

        Log.d(TAG, "$source → ${results.size} streams parsed")
        return results
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
