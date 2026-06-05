package com.aeoncorex.streamx.streaming

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.json.JSONObject

// ═════════════════════════════════════════════════════════════════════════════
//  SubtitleAddonClient.kt
//
//  Stremio uses an OpenSubtitles addon that implements the `subtitles`
//  resource:  GET /subtitles/{type}/{id}.json
//  Returns:   { subtitles: [{ url, lang, id }] }
//
//  StreamX does the same — queries subtitle HTTP addons (Stremio protocol)
//  AND a built-in OpenSubtitles v3 API client as fallback.
//
//  How MPV receives subtitles:
//    1. We fetch subtitle URLs
//    2. Pass them as SubtitleTrack list to ExoMoviePlayerScreen
//    3. MPV loads them via: mpv.command("sub-add", url, "select", title, lang)
//
//  Usage:
//    val subs = SubtitleAddonClient.fetchSubtitles(
//        imdbId   = "tt0068646",
//        language = "en",
//        isSeries = false,
//        season   = 0,
//        episode  = 0
//    )
// ═════════════════════════════════════════════════════════════════════════════
object SubtitleAddonClient {

    private const val TAG = "SubtitleAddon"

    // ── OpenSubtitles v3 REST API (free, no key needed for basic use) ─────────
    private const val OS_API   = "https://rest.opensubtitles.org"
    private const val OS_API_V3 = "https://api.opensubtitles.com/api/v1"
    private const val OS_UA    = "StreamX v1.0"

    data class SubtitleTrack(
        val url:      String,
        val language: String,
        val title:    String,
        val mimeType: String = "application/x-subrip"
    )

    // ── Main fetch — queries HTTP subtitle addons + OpenSubtitles fallback ────

    suspend fun fetchSubtitles(
        imdbId:   String,
        language: String = "en",
        isSeries: Boolean = false,
        season:   Int = 0,
        episode:  Int = 0
    ): List<SubtitleTrack> = withContext(Dispatchers.IO) {
        if (imdbId.isEmpty()) return@withContext emptyList()

        coroutineScope {
            val stremioId = if (isSeries && season > 0) "$imdbId:$season:$episode" else imdbId

            // ── Source 1: installed HTTP subtitle addons (Stremio protocol)
            val httpJob = async {
                fetchFromHttpSubtitleAddons(
                    type      = if (isSeries) "series" else "movie",
                    stremioId = stremioId
                )
            }

            // ── Source 2: OpenSubtitles v3 REST API (free tier)
            val osJob = async {
                fetchFromOpenSubtitles(
                    imdbId   = imdbId,
                    language = language,
                    isSeries = isSeries,
                    season   = season,
                    episode  = episode
                )
            }

            val all = (httpJob.await() + osJob.await())
                .distinctBy { it.url }
                .sortedWith(
                    compareByDescending<SubtitleTrack> { it.language == language }
                        .thenBy { it.language }
                )

            Log.d(TAG, "Found ${all.size} subtitles for $imdbId")
            all
        }
    }

    // ── Source 1: HTTP addon subtitles (Stremio protocol) ─────────────────────

    private suspend fun fetchFromHttpSubtitleAddons(
        type:      String,
        stremioId: String
    ): List<SubtitleTrack> {
        val addons = AddonStorage.getHttpAddons().filter { desc ->
            desc.manifest.resources.any { r ->
                val name = if (r is String) r else (r as? Map<*, *>)?.get("name")?.toString() ?: ""
                name == "subtitles"
            }
        }
        if (addons.isEmpty()) return emptyList()

        val results = mutableListOf<SubtitleTrack>()
        for (desc in addons) {
            val base = desc.transportUrl.removeSuffix("manifest.json").trimEnd('/')
            val url  = "$base/subtitles/$type/${encode(stremioId)}.json"

            runCatching {
                val json = HttpClient.getJson(url) ?: return@runCatching
                val obj  = JSONObject(json)
                val arr  = obj.optJSONArray("subtitles") ?: return@runCatching
                for (i in 0 until arr.length()) {
                    val s = arr.getJSONObject(i)
                    val u = s.optString("url", "")
                    if (u.isEmpty()) continue
                    val lang = s.optString("lang", s.optString("language", "en"))
                    val id   = s.optString("id", "")
                    val mime = when {
                        u.endsWith(".vtt") -> "text/vtt"
                        u.endsWith(".ass") -> "text/x-ssa"
                        else               -> "application/x-subrip"
                    }
                    results.add(SubtitleTrack(
                        url      = u,
                        language = lang,
                        title    = id.ifEmpty { "${desc.manifest.name} [$lang]" },
                        mimeType = mime
                    ))
                }
            }.onFailure { Log.w(TAG, "Subtitle addon error ${desc.manifest.name}: ${it.message}") }
        }
        return results
    }

    // ── Source 2: OpenSubtitles v3 REST API ───────────────────────────────────

    private suspend fun fetchFromOpenSubtitles(
        imdbId:   String,
        language: String,
        isSeries: Boolean,
        season:   Int,
        episode:  Int
    ): List<SubtitleTrack> = runCatching {
        // Build search URL
        val bare = imdbId.removePrefix("tt")
        val path = buildString {
            append("$OS_API/search/imdbid-$bare")
            if (language.isNotEmpty()) append("/sublanguageid-$language")
            if (isSeries && season > 0) {
                append("/season-$season")
                if (episode > 0) append("/episode-$episode")
            }
        }

        val json = HttpClient.getHtml(path,
            headers = mapOf("User-Agent" to OS_UA, "X-User-Agent" to OS_UA)
        ) ?: return emptyList()

        // OpenSubtitles v1 REST returns a JSON array
        val arr = org.json.JSONArray(json)
        (0 until minOf(arr.length(), 10)).mapNotNull { i ->
            val o    = arr.getJSONObject(i)
            val url  = o.optString("SubDownloadLink", "")
            if (url.isEmpty()) return@mapNotNull null
            val lang = o.optString("SubLanguageID", "en")
            val name = o.optString("SubFileName",   "Subtitle")
            SubtitleTrack(
                url      = url,
                language = lang,
                title    = name,
                mimeType = "application/x-subrip"
            )
        }
    }.getOrElse { emptyList() }

    // ── Subtitle addon descriptor for Stremio-compatible addons ──────────────
    // User can install any Stremio subtitle addon (e.g., OpenSubtitles addon)
    // by pasting its manifest URL in the Addons screen → HTTP tab.
    //
    // Known Stremio subtitle addons:
    //   https://opensubtitles-v3.strem.io/manifest.json
    //   https://subsource.strem.io/manifest.json

    private fun encode(s: String) = java.net.URLEncoder.encode(s, "UTF-8")
}
