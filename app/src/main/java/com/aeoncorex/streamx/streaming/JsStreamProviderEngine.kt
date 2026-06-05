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
//    Type B — Bundle JS Addon (Vega-style)
//             • From AddonStorage.getInstalled()
//             • Executes stream.js via Rhino (JsEngine)
//
//  Both produce List<StreamResult> → merged, deduped, sorted, capped at 20.
//
//  FIXES from previous version:
//    • Removed `it.kind == AddonKind.BUNDLE_REPO` — AddonInfo has no kind field.
//      getInstalled() returns ONLY bundle addons; HTTP addons are separate.
//    • AddonStorage.getHttpAddons() now exists (added to AddonStorage.kt).
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
            // Type A: HTTP addons (Stremio protocol)
            // getHttpAddons() returns List<AddonDescriptor> — HTTP endpoint addons
            val httpAddons = AddonStorage.getHttpAddons().filter { desc ->
                desc.manifest.supportsStream(
                    if (req.isSeries) "series" else "movie",
                    HttpAddonTransport.buildVideoId(req)
                )
            }

            // Type B: Bundle JS addons (Vega-style)
            // getInstalled() returns List<AddonInfo> — these are ONLY bundle addons
            val bundleAddons = AddonStorage.getInstalled().filter { !it.disabled }

            val total = httpAddons.size + bundleAddons.size
            Log.d(TAG, "'${req.title}': ${httpAddons.size} HTTP + ${bundleAddons.size} bundle addons")

            if (total == 0) {
                Log.w(TAG, "No addons installed — returning empty")
                return@coroutineScope emptyList()
            }

            // ── Type A jobs ───────────────────────────────────────────────────
            val httpJobs = httpAddons.map { desc ->
                async(Dispatchers.IO) {
                    safe(desc.manifest.name) {
                        withTimeout(TIMEOUT_MS) { fetchFromHttpAddon(desc, req) }
                    }
                }
            }

            // ── Type B jobs ───────────────────────────────────────────────────
            val bundleJobs = bundleAddons.map { addon ->
                async(Dispatchers.IO) {
                    safe(addon.displayName) {
                        withTimeout(TIMEOUT_MS) { fetchFromBundleAddon(addon, req) }
                    }
                }
            }

            (httpJobs + bundleJobs)
                .flatMap { it.await() }
                .distinctBy { it.url.split("?").first().trimEnd('/') }
                .filter    { it.url.startsWith("http") }
                .sortedWith(resultComparator())
                .take(HARD_CAP)
                .also { Log.d(TAG, "Total: ${it.size} streams") }
        }

    // ── Type A: GET /stream/{type}/{id}.json ──────────────────────────────────

    private suspend fun fetchFromHttpAddon(
        desc: AddonDescriptor,
        req:  ProviderRequest
    ): List<StreamResult> = withContext(Dispatchers.IO) {
        val transport = HttpAddonTransport(desc.transportUrl)
        val type      = if (req.isSeries) "series" else "movie"
        val id        = HttpAddonTransport.buildVideoId(req)
        transport.streams(type, id).mapNotNull { it.toStreamResult(desc.manifest.name) }
    }

    // ── Type B: execute stream.js via Rhino ───────────────────────────────────

    private suspend fun fetchFromBundleAddon(
        addon: AddonInfo,
        req:   ProviderRequest
    ): List<StreamResult> = withContext(Dispatchers.IO) {
        val code = AddonStorage.getModules(addon.value, addon.sourceAuthor)?.stream
        if (code.isNullOrBlank()) {
            Log.d(TAG, "${addon.value}: no stream.js cached — install addon first")
            return@withContext emptyList()
        }

        val link = when {
            !req.imdbId.isNullOrEmpty() ->
                if (req.isSeries) "${req.imdbId}:${req.season}:${req.episode}"
                else req.imdbId
            req.tmdbId != null -> "tmdb:${req.tmdbId}"
            else               -> req.title
        }
        val ctx     = JsProviderContext(addon.value)
        val exports = JsEngine.execModule(code, ctx)
        callGetStream(exports, link, req, ctx, addon.value)
    }

    private fun callGetStream(
        exports:     org.mozilla.javascript.ScriptableObject,
        link:        String,
        req:         ProviderRequest,
        ctx:         JsProviderContext,
        providerKey: String
    ): List<StreamResult> {
        val rhino = org.mozilla.javascript.Context.enter()
        return try {
            val scope     = rhino.initStandardObjects()
            val getStream = exports.get("getStream", exports)
                as? org.mozilla.javascript.Function
                ?: return emptyList()

            val sig = rhino.newObject(scope).also { s ->
                org.mozilla.javascript.ScriptableObject.putProperty(s, "aborted", false)
            }
            val arg = rhino.newObject(scope).also { a ->
                org.mozilla.javascript.ScriptableObject.putProperty(a, "link",   link)
                org.mozilla.javascript.ScriptableObject.putProperty(a, "type",   if (req.isSeries) "series" else "movie")
                org.mozilla.javascript.ScriptableObject.putProperty(a, "signal", sig)
                org.mozilla.javascript.ScriptableObject.putProperty(a, "providerContext",
                    org.mozilla.javascript.Context.javaToJS(ctx, scope))
            }
            parseBundleResults(getStream.call(rhino, scope, scope, arrayOf(arg)), providerKey)
        } finally {
            org.mozilla.javascript.Context.exit()
        }
    }

    private fun parseBundleResults(raw: Any?, source: String): List<StreamResult> {
        if (raw == null) return emptyList()
        val results = mutableListOf<StreamResult>()

        fun getP(o: org.mozilla.javascript.NativeObject, k: String) =
            org.mozilla.javascript.ScriptableObject.getProperty(o, k)
                .takeIf { it != org.mozilla.javascript.UniqueTag.NOT_FOUND }

        fun parseOne(obj: Any?) {
            val o   = obj as? org.mozilla.javascript.NativeObject ?: return
            val url = getP(o, "link")?.toString() ?: getP(o, "url")?.toString() ?: return
            if (!url.startsWith("http")) return
            val quality = getP(o, "quality")?.toString() ?: "Unknown"
            val server  = getP(o, "server")?.toString()  ?: source
            val typeStr = getP(o, "type")?.toString()    ?: "mp4"
            val type    = when {
                typeStr.contains("m3u", true) || typeStr.contains("hls",  true) -> StreamType.HLS
                typeStr.contains("dash", true)                                   -> StreamType.DASH
                typeStr.contains("mkv",  true)                                   -> StreamType.MKV
                else                                                              -> StreamType.MP4
            }
            results.add(StreamResult(url = url, quality = quality, type = type,
                source = server, label = "$quality • $server"))
        }

        when (raw) {
            is org.mozilla.javascript.NativeArray ->
                (0 until raw.length).forEach { parseOne(raw[it]) }
            is org.mozilla.javascript.NativeObject -> parseOne(raw)
        }
        return results
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun resultComparator() = compareByDescending<StreamResult> {
        when {
            it.quality.contains("4K",   true) ||
            it.quality.contains("2160", true) -> 40
            it.quality.contains("1080", true) -> 30
            it.quality.contains("720",  true) -> 20
            it.quality.contains("HD",   true) -> 15
            else                              ->  1
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
