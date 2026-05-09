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
object ProtonMoviesProvider {
    private const val TAG = "ProtonMoviesProvider"

    suspend fun fetch(req: ProviderRequest): List<StreamResult> = withContext(Dispatchers.IO) {
        try {
            val base  = ModflixConfig.get("protonMovies")
            val query = req.title.replace(" ", "+")
            val html  = HttpClient.getHtml("$base/?s=$query") ?: return@withContext emptyList()
            val doc   = Jsoup.parse(html, base)
            val titleL = req.title.lowercase()

            val postUrl = doc.select("article a[href]")
                .firstOrNull { (it.attr("title") + it.text()).lowercase().contains(titleL.take(5)) }
                ?.attr("href") ?: doc.selectFirst("article a[href]")?.attr("href")
                ?: return@withContext emptyList()

            val postHtml = HttpClient.getHtml(postUrl) ?: return@withContext emptyList()

            // Find GoFile links: href containing gofile.io/d/
            val gofileLinks = Regex("""gofile\.io/d/([a-zA-Z0-9]+)""")
                .findAll(postHtml).map { it.groupValues[1] }.toList()

            if (gofileLinks.isEmpty()) return@withContext emptyList()

            val results = mutableListOf<StreamResult>()
            for (id in gofileLinks.take(2)) {
                val link = extractGoFile(id)
                if (!link.isNullOrEmpty()) {
                    results.add(StreamResult(
                        url    = link,
                        type   = if (link.contains(".m3u8")) StreamType.HLS else StreamType.MKV,
                        source = "ProtonMovies (GoFile)",
                        label  = "HD — ProtonMovies [GoFile]"
                    ))
                }
            }
            results
        } catch (e: Exception) { Log.w(TAG, e.message ?: "error"); emptyList() }
    }

    private fun extractGoFile(id: String): String? {
        return try {
            // Step 1: Get account token
            val tokenResp = HttpClient.postJson("https://api.gofile.io/accounts", "{}") ?: return null
            val token     = JSONObject(tokenResp).optJSONObject("data")?.optString("token") ?: return null

            // Step 2: Get wt from global.js
            val globalJs  = HttpClient.getHtml("https://gofile.io/dist/js/global.js") ?: return null
            val wt        = Regex("""appdata\.wt\s*=\s*["']([^"']+)["']""").find(globalJs)
                ?.groupValues?.get(1) ?: return null

            // Step 3: Get file contents
            val contentsJson = HttpClient.getJson(
                "https://api.gofile.io/contents/$id?wt=$wt",
                mapOf("Authorization" to "Bearer $token")
            ) ?: return null
            val contents = JSONObject(contentsJson)
            val children = contents.optJSONObject("data")?.optJSONObject("children") ?: return null
            val firstKey = children.keys().asSequence().firstOrNull() ?: return null
            children.optJSONObject(firstKey)?.optString("link")
        } catch (e: Exception) { null }
    }
}
