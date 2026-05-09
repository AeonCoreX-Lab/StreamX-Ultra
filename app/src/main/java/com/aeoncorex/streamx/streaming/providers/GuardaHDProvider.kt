package com.aeoncorex.streamx.streaming.providers

import android.util.Log
import com.aeoncorex.streamx.streaming.HttpClient
import com.aeoncorex.streamx.streaming.ModflixConfig
import com.aeoncorex.streamx.streaming.ProviderRequest
import com.aeoncorex.streamx.streaming.StreamResult
import com.aeoncorex.streamx.streaming.StreamType
import com.aeoncorex.streamx.streaming.extractors.HubCloudExtractor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.jsoup.Jsoup

// ─────────────────────────────────────────────────────────────────────────────
object GuardaHDProvider {
    private const val TAG = "GuardaHDProvider"

    suspend fun fetch(req: ProviderRequest): List<StreamResult> = withContext(Dispatchers.IO) {
        try {
            val base    = ModflixConfig.get("guardahd")
            val imdbId  = req.imdbId?.takeIf { it.isNotEmpty() } ?: return@withContext emptyList()
            val type    = if (req.isSeries) "series" else "movie"

            // Get movie page URL from Cinemeta
            val metaUrl = "https://v3-cinemeta.strem.io/meta/$type/$imdbId.json"
            val meta    = HttpClient.getJson(metaUrl) ?: return@withContext emptyList()
            val slug    = JSONObject(meta).optJSONObject("meta")
                ?.optString("slug")?.takeIf { it.isNotEmpty() }
                ?: req.title.lowercase().replace(Regex("""[^a-z0-9]+"""), "-")

            val pageUrl = if (req.isSeries)
                "$base/set-serie-a/$slug/stagione-${req.season}/episodio-${req.episode}/"
            else
                "$base/set-movie-a/$slug/"

            Log.d(TAG, "GuardaHD: $pageUrl")
            val html = HttpClient.getHtml(pageUrl, mapOf("Referer" to base))
                ?: return@withContext emptyList()

            // Extract superVideo packed script → find stream URL
            val streamUrl = decodeGuardaHD(html)
            if (streamUrl.isNullOrEmpty()) return@withContext emptyList()

            listOf(StreamResult(
                url     = streamUrl,
                type    = if (streamUrl.contains(".m3u8")) StreamType.HLS else StreamType.MP4,
                source  = "GuardaHD",
                label   = "HD — GuardaHD [Italian]",
                language = "Italian",
                headers  = mapOf("Referer" to base)
            ))
        } catch (e: Exception) { Log.w(TAG, e.message ?: "error"); emptyList() }
    }

    // Decode base36 packed script → extract file/src URL
    private fun decodeGuardaHD(html: String): String? {
        // Look for packed eval: eval(function(p,a,c,k,e,d){...}('...',36,...))
        val match = Regex("""eval\(function\(.*?return p\}.*?'(.*?)',36""", RegexOption.DOT_MATCHES_ALL)
            .find(html) ?: return null
        val packed = match.groupValues[1]
        val kMatch = Regex("""'\|\|'(.*?)'\.split""").find(html)
        val keys   = kMatch?.groupValues?.get(1)?.split("|") ?: return null
        val cCount = keys.size

        // Simple base36 decode: replace number tokens with keys
        var result = packed
        var c = cCount - 1
        while (c >= 0) {
            val key = keys[c]
            if (key.isNotEmpty()) {
                result = result.replace(Regex("""\b${c.toString(36)}\b"""), key)
            }
            c--
        }

        // Extract file/src from decoded script
        return Regex("""(?:file|src)\s*:\s*["']([^"']+\.(?:m3u8|mp4)[^"']*)["']""")
            .find(result)?.groupValues?.get(1)
    }
}
