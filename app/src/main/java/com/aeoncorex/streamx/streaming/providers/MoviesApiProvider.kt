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
object MoviesApiProvider {
    private const val TAG      = "MoviesApiProvider"
    private const val DECRYPT  = "https://ext.8man.me/api/decrypt?passphrase==JV[t}{trEV=Ilh5"

    suspend fun fetch(req: ProviderRequest): List<StreamResult> = withContext(Dispatchers.IO) {
        try {
            val base = ModflixConfig.get("moviesapi")
            val link = if (!req.isSeries)
                "$base/movie/${req.tmdbId}"
            else
                "$base/tv/${req.tmdbId}-${req.season}-${req.episode}"

            val pageHtml = HttpClient.getHtml(link,
                mapOf("Referer" to base, "User-Agent" to HttpClient.DESKTOP_UA)
            ) ?: return@withContext emptyList()

            val iframeUrl = Jsoup.parse(pageHtml, base)
                .selectFirst("iframe")?.attr("src")?.trim()
                ?: return@withContext emptyList()

            val embedHtml = HttpClient.getHtml(iframeUrl,
                mapOf(
                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:101.0) Gecko/20100101 Firefox/101.0",
                    "Accept"     to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
                    "Referer"    to base
                )
            ) ?: return@withContext emptyList()

            // Extract encrypted JSON: const Encrypted = '{"sources":[...]}'
            val encMatch = Regex("""const\s+Encrypted\s*=\s*['"](\{.*\})['"]""")
                .find(embedHtml)?.groupValues?.get(1) ?: return@withContext emptyList()

            // Decrypt via 8man.me API
            val decResp = HttpClient.postJson(DECRYPT, encMatch) ?: return@withContext emptyList()
            val decJson = JSONObject(decResp)

            // Parse subtitles
            val subs = mutableListOf<SubtitleTrack>()
            decJson.optJSONArray("subtitles")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val sub  = arr.getJSONObject(i)
                    val file = sub.optString("file", "")
                    if (file.isEmpty()) continue
                    subs.add(SubtitleTrack(
                        url      = file,
                        language = sub.optString("label", "en").take(2).lowercase(),
                        title    = sub.optString("label", "Unknown"),
                        mimeType = if (file.endsWith(".vtt")) "text/vtt" else "application/x-subrip"
                    ))
                }
            }

            // Parse sources
            buildList {
                decJson.optJSONArray("sources")?.let { arr ->
                    for (i in 0 until arr.length()) {
                        val s   = arr.getJSONObject(i)
                        val url = s.optString("file").takeIf { it.isNotEmpty() } ?: continue
                        add(StreamResult(
                            url       = url,
                            type      = if (url.contains(".m3u8")) StreamType.HLS else StreamType.MP4,
                            quality   = s.optString("label", "HD"),
                            source    = "MoviesAPI",
                            label     = "${s.optString("label", "HD")} — MoviesAPI",
                            subtitles = subs,
                            headers   = mapOf("Referer" to base)
                        ))
                    }
                }
            }
        } catch (e: Exception) { Log.w(TAG, e.message ?: "error"); emptyList() }
    }
}
