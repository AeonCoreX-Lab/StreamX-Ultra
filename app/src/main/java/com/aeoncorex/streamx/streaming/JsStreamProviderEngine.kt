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
//             • Executes stream.js via QuickJS (Rust/rquickjs, native engine)
//
//  Both produce List<StreamResult> → merged, deduped, sorted, capped at 20.
//
//  ── FULL FIX LOG ──────────────────────────────────────────────────────────
//
//  FIX 1 — No HTTP requests from bundle addons (ROOT CAUSE FIX)
//    Root cause: Rhino's `function*` generator implementation doesn't fully
//    support the `__async` resolve/reject/step pattern these esbuild bundles
//    use — every getStream() either threw deep inside Rhino's interpreter or
//    silently produced a wrong value, before any HTTP request left the device.
//    Polyfilling Promise/fetch/atob did NOT fix this — the engine itself was
//    the wrong tool.
//
//    Solution: Rhino is REMOVED entirely. Bundle addons now execute inside
//    QuickJS (Rust/rquickjs) via StreamXNative.executeJsStream(), which has
//    full native Promise/async/await/generator support — no polyfill hacks.
//    See app/src/main/rust/src/jsengine/mod.rs.
//
//  FIX 2 — Cross-context errors / two-context execution
//    No longer applicable — QuickJS runs module-eval + getStream() call +
//    promise-resolution inside ONE Context, in ONE Rust function
//    (run_provider_stream), called once via JNI per bundle addon.
//
//  FIX 3 — Wrong link format for bundle providers
//    Root cause: old code passed Stremio-style "tt1234567" or "tmdb:123" as link.
//    Providers like autoEmbed expect a JSON payload:
//      {"tmdbId":123,"imdbId":"tt456","season":1,"episode":2,"type":"series"}
//    Others (4khdhub, multi, world4u) need a website URL from the getMeta step
//    (those providers will still return empty until getMeta flow is added).
//    Solution: buildVegaLink() builds the JSON payload (unchanged from before).
//    autoEmbed/MultiStream parse it via JSON.parse(id) and extract tmdbId/imdbId. ✓
//
//  FIX 4 — axios.get().data not parsed as JSON
//    Root cause: providers do response.data.streams.forEach() expecting object.
//    Solution: the QuickJS POLYFILLS' axios wrapper auto-parses JSON bodies and
//    exposes response.headers.get(name) / response.request.responseURL,
//    mirroring the old Rhino-side contract exactly.
//
//  FIX 5 — Promise result discarded
//    Root cause: old code (Rhino) received the Promise object itself, not its
//    resolved value.
//    Solution: resolve_promise() in jsengine/mod.rs drains QuickJS's real job
//    queue via execute_pending_job() until the Promise settles, THEN
//    JSON.stringify()s the resolved array. Kotlin receives plain JSON.
//
//  FIX 6 — HTTP addons: try tmdb: prefix addons when no imdbId
//    Root cause: supportsStream was too strict — if req.imdbId is null,
//    id = "tmdb:123" but addon idPrefixes = ["tt"] → filtered out (correct).
//    The REAL fix is populating imdbId via TMDB external-IDs API upstream.
//    Interim fix here: if no IMDB ID available, we also try HTTP addons that
//    accept "tmdb:" prefix (MediaFusion supports this).
//
//  FIX 7 — cheerio support for scraper-style providers (4khdhub/multi/world4u)
//    Root cause: Rhino's JsCheerio (Jsoup-backed) worked but those providers
//    are still blocked on FIX 3's getMeta/website-URL gap, not on cheerio itself.
//    Solution: QuickJS POLYFILLS now back cheerio.load() with __native_cheerio,
//    implemented via Rust's `scraper` crate (real CSS selectors, html5ever) —
//    a strict upgrade over Jsoup-via-Rhino, ready once FIX 3's getMeta flow lands.
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

    // ── Type B: execute stream.js via QuickJS (native, Rust/rquickjs) ──────────
    //
    // Replaces the entire Rhino pipeline. StreamXNative.executeJsStream():
    //   1. JNI → run_provider_stream(code, link, isSeries) in jsengine/mod.rs
    //   2. QuickJS evals POLYFILLS + the bundle, calls getStream(arg)
    //   3. Drains the real Promise job queue, JSON.stringify()s the result
    //   4. Returns a JSON array string, parsed here into StreamResult directly
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

        StreamXNative.executeJsStream(code, link, req.isSeries, addon.displayName)
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
