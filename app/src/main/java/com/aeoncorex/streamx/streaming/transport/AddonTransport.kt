package com.aeoncorex.streamx.streaming.transport

// ── FIX: imports must be at the TOP of the file ──────────────────────────────
import android.util.Log
import com.aeoncorex.streamx.streaming.AddonDescriptor
import com.aeoncorex.streamx.streaming.AddonManifest
import com.aeoncorex.streamx.streaming.AddonStream
import com.aeoncorex.streamx.streaming.HttpClient
import com.aeoncorex.streamx.streaming.ProviderRequest
import com.aeoncorex.streamx.streaming.ResourcePath
import com.aeoncorex.streamx.streaming.StreamResult
import org.json.JSONObject

// ═════════════════════════════════════════════════════════════════════════════
//  AddonTransport.kt
//
//  Mirrors stremio-core's AddonTransport trait:
//    interface AddonTransport {
//        suspend fun manifest(): AddonManifest
//        suspend fun streams(type, id): List<AddonStream>
//        suspend fun catalog(type, id, extra): List<Any>
//    }
//
//  HttpAddonTransport calls the Stremio HTTP protocol:
//    GET {baseUrl}/manifest.json
//    GET {baseUrl}/stream/{type}/{id}.json
//    GET {baseUrl}/catalog/{type}/{id}.json
//
//  transportUrl MUST end with /manifest.json (same rule as stremio-core).
//
//  Any Stremio community addon works here — same protocol.
// ═════════════════════════════════════════════════════════════════════════════
interface AddonTransport {
    suspend fun manifest(): AddonManifest
    suspend fun streams(type: String, id: String): List<AddonStream>
    suspend fun catalog(type: String, id: String, extra: Map<String, String> = emptyMap()): List<Any>
}

// ─────────────────────────────────────────────────────────────────────────────
//  HttpAddonTransport
// ─────────────────────────────────────────────────────────────────────────────
class HttpAddonTransport(
    private val transportUrl: String
) : AddonTransport {

    private val TAG     = "HttpAddonTransport"
    private val baseUrl = transportUrl
        .removeSuffix("manifest.json")
        .trimEnd('/')

    override suspend fun manifest(): AddonManifest {
        val json = HttpClient.getJson("$baseUrl/manifest.json")
            ?: throw Exception("Cannot fetch manifest from $baseUrl/manifest.json")
        return AddonManifest.fromJson(JSONObject(json))
    }

    override suspend fun streams(type: String, id: String): List<AddonStream> {
        val path = ResourcePath("stream", type, id).toUrlPath()
        val url  = "$baseUrl$path"
        Log.d(TAG, "stream → $url")

        val json = HttpClient.getJson(url) ?: return emptyList()
        return try {
            val arr = JSONObject(json).optJSONArray("streams") ?: return emptyList()
            (0 until arr.length()).mapNotNull { i ->
                runCatching { AddonStream.fromJson(arr.getJSONObject(i)) }.getOrNull()
            }.also { Log.d(TAG, "${it.size} streams from $baseUrl") }
        } catch (e: Exception) {
            Log.w(TAG, "Parse error from $url: ${e.message}")
            emptyList()
        }
    }

    override suspend fun catalog(type: String, id: String, extra: Map<String, String>): List<Any> {
        val path = ResourcePath("catalog", type, id, extra).toUrlPath()
        val json = HttpClient.getJson("$baseUrl$path") ?: return emptyList()
        return try {
            val arr = JSONObject(json).optJSONArray("metas") ?: return emptyList()
            (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
        } catch (e: Exception) {
            Log.w(TAG, "Catalog parse error: ${e.message}")
            emptyList()
        }
    }

    companion object {
        /**
         * Build Stremio video ID from ProviderRequest.
         *   movie  → "tt1234567"
         *   series → "tt1234567:1:2"
         * Falls back to "tmdb:{id}" if no IMDB id.
         */
        fun buildVideoId(req: ProviderRequest): String {
            val base = when {
                !req.imdbId.isNullOrEmpty() -> req.imdbId
                req.tmdbId != null          -> "tmdb:${req.tmdbId}"
                else                        -> req.title
            }
            return if (req.isSeries && req.season > 0) "$base:${req.season}:${req.episode}"
            else base
        }
    }
}
