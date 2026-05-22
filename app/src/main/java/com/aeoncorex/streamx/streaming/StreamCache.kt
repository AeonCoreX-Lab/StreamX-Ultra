package com.aeoncorex.streamx.streaming

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

// ═════════════════════════════════════════════════════════════════════════════
//  StreamCache.kt
//  ─────────────────────────────────────────────────────────────────────────
//  SharedPreferences-based persistence cache for the provider engine.
//  Mirrors Vega-app's MMKV + stale-while-revalidate pattern.
//
//  TTLs (matching vega-app/src/lib/hooks/useStream.ts):
//    Streams   → 5 min   (fast-changing download links)
//    Metadata  → 30 min  (post-page data)
//    Episodes  → 15 min  (series episode lists)
//    Base URLs → 60 min  (handled separately in ModflixConfig)
//
//  Usage:
//    val cached = StreamCache.getStreams(cacheKey)
//    if (cached != null) { /* instant display */ }
//    StreamCache.putStreams(cacheKey, freshResults)
// ═════════════════════════════════════════════════════════════════════════════
object StreamCache {

    private const val TAG = "StreamCache"

    // TTL constants (ms)
    private const val TTL_STREAMS_MS  = 5   * 60 * 1_000L   //  5 minutes
    private const val TTL_META_MS     = 30  * 60 * 1_000L   // 30 minutes
    private const val TTL_EPISODES_MS = 15  * 60 * 1_000L   // 15 minutes

    // Prefs names — separate files so one never blocks the other
    private const val PREFS_STREAMS  = "sx_stream_cache"
    private const val PREFS_META     = "sx_meta_cache"

    @Volatile private var streamsPrefs: SharedPreferences? = null
    @Volatile private var metaPrefs:    SharedPreferences? = null

    // ── Init — call once from Application.onCreate() ─────────────────────────
    fun init(context: Context) {
        val app = context.applicationContext
        streamsPrefs = app.getSharedPreferences(PREFS_STREAMS, Context.MODE_PRIVATE)
        metaPrefs    = app.getSharedPreferences(PREFS_META,    Context.MODE_PRIVATE)
        Log.d(TAG, "StreamCache initialised")
    }

    // ═══════════════════════════════════════════════════════════════
    //  STREAM RESULTS
    // ═══════════════════════════════════════════════════════════════

    /**
     * Build a stable cache key from the request.
     * Example: "streamresult|tt1234567|hindi|s1e3"
     */
    fun streamKey(req: ProviderRequest): String = buildString {
        append("streamresult|")
        append(req.imdbId ?: req.tmdbId?.toString() ?: req.title.take(30))
        append("|${req.language.lowercase()}")
        if (req.isSeries) append("|s${req.season}e${req.episode}")
    }

    /** Returns cached streams if still fresh, null otherwise. */
    fun getStreams(key: String): List<StreamResult>? {
        val prefs = streamsPrefs ?: return null
        val ts    = prefs.getLong("${key}_ts", 0L)
        if (System.currentTimeMillis() - ts > TTL_STREAMS_MS) return null
        val json  = prefs.getString(key, null) ?: return null
        return try { deserializeResults(json) } catch (e: Exception) { null }
    }

    /** Persists stream results to disk. Thread-safe (apply is async). */
    fun putStreams(key: String, results: List<StreamResult>) {
        val prefs = streamsPrefs ?: return
        try {
            prefs.edit()
                .putString(key, serializeResults(results))
                .putLong("${key}_ts", System.currentTimeMillis())
                .apply()
        } catch (e: Exception) {
            Log.w(TAG, "putStreams failed: ${e.message}")
        }
    }

    /** Returns stale (expired) cache — useful for stale-while-revalidate. */
    fun getStaleStreams(key: String): List<StreamResult>? {
        val prefs = streamsPrefs ?: return null
        val json  = prefs.getString(key, null) ?: return null
        return try { deserializeResults(json) } catch (e: Exception) { null }
    }

    // ═══════════════════════════════════════════════════════════════
    //  IN-FLIGHT DEDUPLICATION
    //  Prevents multiple coroutines from fetching the same key simultaneously.
    //  (Mirrors vega-app's React Query dedupe behaviour)
    // ═══════════════════════════════════════════════════════════════

    private val inFlight = mutableSetOf<String>()
    private val lock      = Any()

    /** Returns true if fetch should proceed; false if another coroutine is already fetching. */
    fun tryAcquireFetch(key: String): Boolean = synchronized(lock) {
        if (inFlight.contains(key)) return false
        inFlight.add(key)
        true
    }

    /** Must be called in finally block after fetch completes or fails. */
    fun releaseFetch(key: String) = synchronized(lock) { inFlight.remove(key) }

    // ═══════════════════════════════════════════════════════════════
    //  PREFETCH QUEUE
    //  Details screen open → prefetch queued for each likely language
    // ═══════════════════════════════════════════════════════════════

    private val prefetchQueue = ArrayDeque<ProviderRequest>(8)

    fun enqueuePrefetch(req: ProviderRequest) = synchronized(lock) {
        if (prefetchQueue.none { it.streamKey() == req.streamKey() })
            prefetchQueue.addLast(req)
    }

    fun dequeuePrefetch(): ProviderRequest? = synchronized(lock) {
        prefetchQueue.removeFirstOrNull()
    }

    private fun ProviderRequest.streamKey() = streamKey(this)

    // ═══════════════════════════════════════════════════════════════
    //  CACHE MANAGEMENT
    // ═══════════════════════════════════════════════════════════════

    /** Evicts entries older than their TTL. Call once per app session (e.g. App.onCreate). */
    fun evictExpired() {
        evictOldEntries(streamsPrefs, TTL_STREAMS_MS)
        evictOldEntries(metaPrefs,    TTL_META_MS)
    }

    private fun evictOldEntries(prefs: SharedPreferences?, ttl: Long) {
        prefs ?: return
        val now = System.currentTimeMillis()
        val editor = prefs.edit()
        var evicted = 0
        prefs.all.keys
            .filter { it.endsWith("_ts") }
            .forEach { tsKey ->
                val ts = prefs.getLong(tsKey, 0L)
                if (now - ts > ttl * 2) {   // 2× TTL = safe to evict
                    val dataKey = tsKey.removeSuffix("_ts")
                    editor.remove(tsKey).remove(dataKey)
                    evicted++
                }
            }
        editor.apply()
        if (evicted > 0) Log.d(TAG, "Evicted $evicted stale cache entries")
    }

    /** Wipes all cached streams (for debugging / settings reset). */
    fun clearAll() {
        streamsPrefs?.edit()?.clear()?.apply()
        metaPrefs?.edit()?.clear()?.apply()
        Log.d(TAG, "StreamCache cleared")
    }

    // ═══════════════════════════════════════════════════════════════
    //  SERIALIZATION
    // ═══════════════════════════════════════════════════════════════

    private fun serializeResults(results: List<StreamResult>): String {
        val arr = JSONArray()
        results.forEach { r ->
            val obj = JSONObject().apply {
                put("url",      r.url)
                put("quality",  r.quality)
                put("type",     r.type.name)
                put("source",   r.source)
                put("language", r.language)
                put("label",    r.label)
                // subtitles
                val subs = JSONArray()
                r.subtitles.forEach { s ->
                    subs.put(JSONObject().apply {
                        put("url",      s.url)
                        put("language", s.language)
                        put("title",    s.title)
                        put("mimeType", s.mimeType)
                    })
                }
                put("subtitles", subs)
                // headers
                val hdrs = JSONObject()
                r.headers.forEach { (k, v) -> hdrs.put(k, v) }
                put("headers", hdrs)
            }
            arr.put(obj)
        }
        return arr.toString()
    }

    private fun deserializeResults(json: String): List<StreamResult> {
        val arr = JSONArray(json)
        return buildList {
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val subs = buildList {
                    val subsArr = obj.optJSONArray("subtitles") ?: JSONArray()
                    for (j in 0 until subsArr.length()) {
                        val s = subsArr.getJSONObject(j)
                        add(SubtitleTrack(
                            url      = s.optString("url"),
                            language = s.optString("language"),
                            title    = s.optString("title"),
                            mimeType = s.optString("mimeType", "text/vtt")
                        ))
                    }
                }
                val hdrs = buildMap {
                    val hObj = obj.optJSONObject("headers") ?: JSONObject()
                    hObj.keys().forEach { k -> put(k, hObj.getString(k)) }
                }
                add(StreamResult(
                    url       = obj.getString("url"),
                    quality   = obj.optString("quality", "Unknown"),
                    type      = runCatching { StreamType.valueOf(obj.optString("type", "HLS")) }
                                    .getOrDefault(StreamType.HLS),
                    source    = obj.optString("source", ""),
                    language  = obj.optString("language", "English"),
                    label     = obj.optString("label", ""),
                    subtitles = subs,
                    headers   = hdrs
                ))
            }
        }
    }
}
