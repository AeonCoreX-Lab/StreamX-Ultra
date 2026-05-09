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
object PrimeMirrorProvider {
    private const val TAG   = "PrimeMirrorProvider"
    private const val PROXY = "https://netmirror.8man.dev/api/net-proxy"

    suspend fun fetch(req: ProviderRequest): List<StreamResult> = withContext(Dispatchers.IO) {
        try {
            val base = ModflixConfig.get("nfMirror")
            val ts   = System.currentTimeMillis() / 1000
            val url  = "$PROXY?isPrime=true&url=${base}/pv/playlist.php?id=${req.imdbId}&t=$ts"
            val json = HttpClient.getJson(url) ?: return@withContext emptyList()
            val arr  = org.json.JSONArray(json)
            val data = arr.optJSONObject(0) ?: return@withContext emptyList()
            val srcs = data.optJSONArray("sources") ?: return@withContext emptyList()

            buildList {
                for (i in 0 until srcs.length()) {
                    val s    = srcs.getJSONObject(i)
                    val file = s.optString("file").takeIf { it.isNotEmpty() } ?: continue
                    val link = if (file.startsWith("http")) file else "$base$file"
                    add(StreamResult(
                        url     = link,
                        type    = StreamType.HLS,
                        quality = s.optString("label", "HD"),
                        source  = "PrimeMirror",
                        label   = "${s.optString("label", "HD")} — PrimeMirror",
                        headers = mapOf("Referer" to base, "Cookie" to "hd=on")
                    ))
                }
            }
        } catch (e: Exception) { Log.w(TAG, e.message ?: "error"); emptyList() }
    }
}
