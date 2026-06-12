package com.aeoncorex.streamx.streaming

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

// ═════════════════════════════════════════════════════════════════════════════
//  StreamXNative.kt
//
//  Kotlin wrapper for Rust JNI functions that don't belong to TorrentEngine.
//    • nativeAddonFetchStreams  — HTTP (Stremio) addon transport
//    • nativeExecuteJsStream    — NEW: QuickJS bundle-addon engine
//                                  (replaces JsEngine.kt / Rhino entirely)
//
//  JNI functions live in:
//    app/src/main/rust/src/lib.rs       → Java_com_aeoncorex_streamx_streaming_StreamXNative_*
//    app/src/main/rust/src/jsengine/    → actual QuickJS execution
// ═════════════════════════════════════════════════════════════════════════════
object StreamXNative {

    private const val TAG = "StreamXNative"

    init {
        // streamx-native.so already loaded by TorrentEngine — safe to call again
        System.loadLibrary("streamx-native")
    }

    // ── Rust JNI declarations ─────────────────────────────────────────────────

    private external fun nativeAddonFetchStreams(
        transportUrl: String,
        type:         String,
        id:           String
    ): String   // JSON array string: [{"url":"...","name":"...","description":"..."}]

    /**
     * Executes a Vega-style CJS `stream.js` bundle inside a fresh QuickJS
     * context (Rust/rquickjs — see app/src/main/rust/src/jsengine/mod.rs).
     *
     * @param code     raw stream.js module source (CommonJS)
     * @param link     JSON payload string, e.g.
     *                 {"tmdbId":123,"imdbId":"tt1234567","season":1,"episode":2,"type":"series"}
     * @param isSeries true for series episodes (sets arg.type = "series")
     * @return JSON array string of stream objects:
     *         [{"link":"https://...","type":"mp4","quality":"1080p","server":"...","headers":{...}}, ...]
     *         "[]" on any failure — never throws.
     */
    private external fun nativeExecuteJsStream(
        code:     String,
        link:     String,
        isSeries: Boolean
    ): String

    // ── Public API ────────────────────────────────────────────────────────────

    suspend fun fetchAddonStreams(
        transportUrl: String,
        contentType:  String,
        id:           String
    ): List<AddonStreamResult> = withContext(Dispatchers.IO) {
        try {
            val json = nativeAddonFetchStreams(transportUrl, contentType, id)
            parseStreams(json)
        } catch (e: Exception) {
            Log.w(TAG, "fetchAddonStreams error: ${e.message}")
            emptyList()
        }
    }

    /**
     * Executes a bundle addon's stream.js and returns parsed [StreamResult]s.
     * This is the QuickJS replacement for the old Rhino-based
     * `JsEngine.executeAndCallStream()` + `parseBundleResults()` pair —
     * all parsing now happens Rust-side via `JSON.stringify`, so Kotlin only
     * deals with plain org.json, no NativeArray/NativeObject.
     */
    suspend fun executeJsStream(
        code:     String,
        link:     String,
        isSeries: Boolean,
        source:   String
    ): List<StreamResult> = withContext(Dispatchers.IO) {
        try {
            val json = nativeExecuteJsStream(code, link, isSeries)
            parseJsStreamResults(json, source)
        } catch (e: Exception) {
            Log.w(TAG, "executeJsStream($source) error: ${e.message}")
            emptyList()
        }
    }

    // ── Parsing ───────────────────────────────────────────────────────────────

    private fun parseStreams(json: String): List<AddonStreamResult> = try {
        val arr = JSONArray(json)
        (0 until arr.length()).mapNotNull { i ->
            val o   = arr.getJSONObject(i)
            val url = o.optString("url", "")
            if (url.isEmpty()) null
            else AddonStreamResult(
                url         = url,
                name        = o.optString("name",        ""),
                description = o.optString("description", "")
            )
        }
    } catch (e: Exception) {
        Log.w(TAG, "parse error: ${e.message}")
        emptyList()
    }

    /**
     * Parses the JSON array returned by [nativeExecuteJsStream].
     * Each element: { link/url, type, quality, server, language, headers:{...} }
     * Mirrors the field names used by Vega-style providers (link, server,
     * quality) plus Stremio-style fallbacks (url, name).
     */
    private fun parseJsStreamResults(json: String, source: String): List<StreamResult> = try {
        val arr = JSONArray(json)
        val results = mutableListOf<StreamResult>()

        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue

            val url = o.optString("link", "").ifEmpty { o.optString("url", "") }
            if (!url.startsWith("http")) continue

            val quality = o.optString("quality", "Unknown").ifEmpty { "Unknown" }
            val server  = o.optString("server", "").ifEmpty { o.optString("name", "") }.ifEmpty { source }
            val typeStr = o.optString("type", "mp4")
            val lang    = o.optString("language", "").ifEmpty { o.optString("lang", "") }.ifEmpty { "Unknown" }

            val streamType = when {
                typeStr.contains("m3u", ignoreCase = true) ||
                typeStr.contains("hls", ignoreCase = true)  -> StreamType.HLS
                typeStr.contains("dash", ignoreCase = true) -> StreamType.DASH
                typeStr.contains("mkv",  ignoreCase = true) -> StreamType.MKV
                else                                        -> StreamType.MP4
            }

            val headers = mutableMapOf<String, String>()
            o.optJSONObject("headers")?.let { h ->
                h.keys().forEach { k -> headers[k] = h.optString(k, "") }
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

        Log.d(TAG, "$source -> ${results.size} streams (native engine)")
        results
    } catch (e: Exception) {
        Log.w(TAG, "parseJsStreamResults($source) error: ${e.message}")
        emptyList()
    }

    data class AddonStreamResult(
        val url:         String,
        val name:        String,
        val description: String
    ) {
        // Convert to StreamResult for ExoSourceSelectionScreen
        fun toStreamResult(): StreamResult? {
            if (!url.startsWith("http")) return null
            val quality = when {
                name.contains("4K",   true) || name.contains("2160", true) -> "4K"
                name.contains("1080", true)                                 -> "1080p"
                name.contains("720",  true)                                 -> "720p"
                name.contains("480",  true)                                 -> "480p"
                else                                                         -> "HD"
            }
            val type = when {
                url.contains(".m3u8") || name.contains("hls", true)  -> StreamType.HLS
                url.contains(".mpd")  || name.contains("dash", true) -> StreamType.DASH
                url.contains(".mkv")                                  -> StreamType.MKV
                else                                                   -> StreamType.MP4
            }
            return StreamResult(
                url     = url,
                quality = quality,
                type    = type,
                source  = name.ifEmpty { "HTTP Addon" },
                label   = name.ifEmpty { description.ifEmpty { "$quality • HTTP" } }
            )
        }
    }
}
