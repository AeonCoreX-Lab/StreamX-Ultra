package com.aeoncorex.streamx.streaming.providers

import android.util.Base64
import android.util.Log
import com.aeoncorex.streamx.streaming.HttpClient
import com.aeoncorex.streamx.streaming.ModflixConfig
import com.aeoncorex.streamx.streaming.ProviderRequest
import com.aeoncorex.streamx.streaming.StreamResult
import com.aeoncorex.streamx.streaming.StreamType
import com.aeoncorex.streamx.streaming.SubtitleTrack
import com.aeoncorex.streamx.streaming.extractors.HubCloudExtractor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.jsoup.Jsoup

// ─────────────────────────────────────────────────────────────────────────────
object PrimewireProvider {
    private const val TAG = "PrimewireProvider"

    suspend fun fetch(req: ProviderRequest): List<StreamResult> = withContext(Dispatchers.IO) {
        try {
            val base  = ModflixConfig.get("primewire")
            val query = req.title.replace(" ", "+") + if (req.year != null) "+${req.year}" else ""
            val html  = HttpClient.getHtml("$base/search?q=$query") ?: return@withContext emptyList()
            val doc   = Jsoup.parse(html, base)
            val titleL = req.title.lowercase()

            // Find best match
            val postUrl = doc.select(".film_list-wrap .flw-item .film-poster-ahref, .film-list a")
                .firstOrNull { it.attr("title").lowercase().contains(titleL.take(5)) }
                ?.attr("href")?.let { if (it.startsWith("http")) it else "$base$it" }
                ?: return@withContext emptyList()

            val postHtml = HttpClient.getHtml(postUrl) ?: return@withContext emptyList()
            val postDoc  = Jsoup.parse(postHtml, base)

            // Get MixDrop links: tr with class containing 'mixdrop'
            val mixLinks = mutableListOf<String>()
            postDoc.select("tr").forEach { row ->
                if (row.text().lowercase().contains("mixdrop")) {
                    val id = row.selectFirst(".wp-menu-btn")?.attr("data-wp-menu") ?: return@forEach
                    mixLinks.add("$base/links/go/$id")
                }
            }

            buildList {
                for (mixUrl in mixLinks.take(3)) {
                    try {
                        // Follow redirect to get /e/ URL
                        val redirectHtml = HttpClient.getHtml(mixUrl) ?: continue
                        val eUrl = Regex("""(?:location|href)\s*=\s*['"]([^'"]+/e/[^'"]+)['"]""")
                            .find(redirectHtml)?.groupValues?.get(1) ?: continue

                        val embedHtml = HttpClient.getHtml(eUrl, mapOf("Referer" to mixUrl)) ?: continue

                        // Decode MDCore packed script
                        val mdUrl = decodeMixdrop(embedHtml)
                        if (!mdUrl.isNullOrEmpty()) {
                            add(StreamResult(
                                url    = if (mdUrl.startsWith("//")) "https:$mdUrl" else mdUrl,
                                type   = if (mdUrl.contains(".m3u8")) StreamType.HLS else StreamType.MP4,
                                source = "Primewire (MixDrop)",
                                label  = "HD — Primewire [MixDrop]",
                                headers = mapOf("Referer" to eUrl)
                            ))
                        }
                    } catch (_: Exception) {}
                }
            }
        } catch (e: Exception) { Log.w(TAG, e.message ?: "error"); emptyList() }
    }

    private fun decodeMixdrop(html: String): String? {
        // Match: eval(function(p,a,c,k,e,d){...}('...',N,N,'...|MDCore|...'.split('|'),...))
        val match = Regex("""eval\(function\(.*?return p\}.*?'(.*?)'\.split\('\|'\)""", RegexOption.DOT_MATCHES_ALL)
            .find(html) ?: return null

        // Simple lookup for wurl= pattern after decode
        return Regex("""(?:wurl|MDCore\.wurl)\s*=\s*["']([^"']+)["']""").find(html)
            ?.groupValues?.get(1)
    }
}
