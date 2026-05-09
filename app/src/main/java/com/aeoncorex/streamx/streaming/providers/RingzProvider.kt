package com.aeoncorex.streamx.streaming.providers

import android.util.Log
import com.aeoncorex.streamx.streaming.HttpClient
import com.aeoncorex.streamx.streaming.ProviderRequest
import com.aeoncorex.streamx.streaming.StreamResult
import com.aeoncorex.streamx.streaming.StreamType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup

// ─────────────────────────────────────────────────────────────────────────────
object RingzProvider {
    private const val TAG      = "RingzProvider"
    private const val BASE_URL = "https://privatereporz.pages.dev"

    // Cloudflare Access credentials (from posts.js)
    private val CF_HEADERS = mapOf(
        "cf-access-client-id"     to "833049b087acf6e787cedfd85d1ccdb8.access",
        "cf-access-client-secret" to "02db296a961d7513c3102d7785df4113eff036b2d57d060ffcc2ba3ba820c6aa",
        "User-Agent"              to HttpClient.DESKTOP_UA
    )

    // Catalog endpoints
    private const val MOVIES_URL = "$BASE_URL/test.json"
    private const val SHOWS_URL  = "$BASE_URL/srs.json"
    private const val ANIME_URL  = "$BASE_URL/anime.json"

    suspend fun fetch(req: ProviderRequest): List<StreamResult> = coroutineScope {
        try {
            val titleL   = req.title.lowercase()
            val isAnime  = req.language.lowercase() == "japanese"
            val isSeries = req.isSeries

            // Fetch relevant catalogs in parallel
            val catalogs = buildList {
                if (!isAnime) {
                    if (!isSeries) add(async(Dispatchers.IO) { fetchCatalog(MOVIES_URL, "AllMovieDataList") })
                    else           add(async(Dispatchers.IO) { fetchCatalog(SHOWS_URL, "webSeriesDataList") })
                } else {
                    add(async(Dispatchers.IO) { fetchCatalog(ANIME_URL, "webSeriesDataList") })
                }
            }.awaitAll().flatten()

            if (catalogs.isEmpty()) return@coroutineScope emptyList()

            // Search catalog for best match
            val matches = catalogs.filter { item ->
                val mn = item.optString("mn", "").lowercase()
                val kn = item.optString("kn", "").lowercase()
                mn.contains(titleL.take(5)) || kn.contains(titleL.take(5))
            }.take(3)

            if (matches.isEmpty()) {
                Log.d(TAG, "Ringz: no match for '${req.title}'")
                return@coroutineScope emptyList()
            }

            // Parse stream from matched item (mirrors stream.js logic)
            matches.flatMap { item -> parseStreamItem(item) }
        } catch (e: Exception) {
            Log.w(TAG, e.message ?: "error")
            emptyList()
        }
    }

    private fun fetchCatalog(url: String, arrayKey: String): List<JSONObject> {
        return try {
            val json = HttpClient.getJson(url, CF_HEADERS) ?: return emptyList()
            val arr  = JSONObject(json).optJSONArray(arrayKey) ?: return emptyList()
            (0 until arr.length()).map { arr.getJSONObject(it) }
        } catch (e: Exception) { emptyList() }
    }

    // Mirrors stream.js: parse {url, server} from element JSON
    private fun parseStreamItem(item: JSONObject): List<StreamResult> {
        val results = mutableListOf<StreamResult>()

        // Direct url field
        val url = item.optString("url", "").takeIf { it.isNotEmpty() }
        if (url != null) {
            val server = item.optString("server", "Ringz")
            val ext    = url.substringAfterLast(".").lowercase().substringBefore("?")
            val type   = when (ext) {
                "m3u8"       -> StreamType.HLS
                "mkv", "avi" -> StreamType.MKV
                else         -> StreamType.MP4
            }
            results.add(StreamResult(
                url    = url,
                type   = type,
                source = "Ringz ($server)",
                label  = "HD — Ringz [$server]"
            ))
        }

        // Some items have multiple quality links: Q1080, Q720, Q480
        listOf("Q1080", "Q720", "Q480", "Q4K").forEach { key ->
            val qUrl = item.optString(key, "").takeIf { it.isNotEmpty() } ?: return@forEach
            val qual = key.replace("Q", "") + "p"
            results.add(StreamResult(
                url     = qUrl,
                quality = qual,
                type    = StreamType.MKV,
                source  = "Ringz",
                label   = "$qual — Ringz"
            ))
        }

        return results
    }
}
