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
//  Currently: addon HTTP transport (nativeAddonFetchStreams).
//
//  JNI function lives in:
//    app/src/main/rust/src/lib.rs → Java_com_aeoncorex_streamx_streaming_StreamXNative_*
// ═════════════════════════════════════════════════════════════════════════════
object StreamXNative {

    private const val TAG = "StreamXNative"

    init {
        // streamx-native.so already loaded by TorrentEngine — safe to call again
        System.loadLibrary("streamx-native")
    }

    // ── Rust JNI declaration ──────────────────────────────────────────────────
    private external fun nativeAddonFetchStreams(
        transportUrl: String,
        type:         String,
        id:           String
    ): String   // JSON array string: [{"url":"...","name":"...","description":"..."}]

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
