package com.aeoncorex.streamx.ui.movie

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

// ═══════════════════════════════════════════════════════════════════
//  SubtitleRepository — OpenSubtitles.org (Free API)
//  ──────────────────────────────────────────────────
//  Like Movie Box subtitle system (screenshots show OpenSubtitles):
//    • Multiple languages with ↓ download button
//    • English, हिन्दी, Français, Indonesia, Português, عربي, etc.
//    • Style settings: color, size, position, shadow, background
//    • Delay adjustment (-/+ buttons)
//
//  OpenSubtitles API v1: completely free, no key needed for basic use
//  Up to 200 requests/day per IP (enough for most users)
//
//  Downloaded subtitles cached to app storage as .srt files
// ═══════════════════════════════════════════════════════════════════
object SubtitleRepository {

    private const val TAG     = "SubtitleRepo"
    private const val API_URL = "https://opensubtitles-v3.strem.io/subtitles"

    // Languages shown in Movie Box (from screenshots)
    val SUPPORTED_LANGUAGES = listOf(
        SubtitleLanguage("en",   "English",     "English"),
        SubtitleLanguage("hi",   "हिन्दी",       "Hindi"),
        SubtitleLanguage("fr",   "Français",    "French"),
        SubtitleLanguage("id",   "Indonesia",   "Indonesian"),
        SubtitleLanguage("pt-br","Português",   "Portuguese"),
        SubtitleLanguage("ar",   "العربية",     "Arabic"),
        SubtitleLanguage("ha",   "Hausa",       "Hausa"),
        SubtitleLanguage("sw",   "Kiswahili",   "Swahili"),
        SubtitleLanguage("zh-cn","中文(简体)",    "Chinese"),
        SubtitleLanguage("bn",   "বাংলা",       "Bengali"),
        SubtitleLanguage("ko",   "한국어",       "Korean"),
        SubtitleLanguage("ja",   "日本語",       "Japanese"),
        SubtitleLanguage("es",   "Español",     "Spanish"),
        SubtitleLanguage("de",   "Deutsch",     "German"),
    )

    data class SubtitleLanguage(val code: String, val displayName: String, val englishName: String)

    data class SubtitleResult(
        val id:         String,
        val title:      String,
        val lang:       String,
        val langCode:   String,
        val url:        String,  // direct .srt URL
        val downloads:  Int = 0
    )

    // ── Search subtitles ─────────────────────────────────────────
    //  Uses opensubtitles-v3.strem.io which is free, no API key needed.
    //  When user selects Hindi dub → langCode="hi" → shows Hindi subtitles
    //  When user selects English   → langCode="en" → shows English subtitles
    suspend fun search(
        imdbId:   String?,
        title:    String,
        type:     MovieType,
        season:   Int = 0,
        episode:  Int = 0,
        langCode: String = "en"
    ): List<SubtitleResult> = withContext(Dispatchers.IO) {

        try {
            val id = (imdbId ?: "").trim()

            // Build URL — prefer IMDB ID, fallback to title search
            val url = when {
                type == MovieType.SERIES && id.startsWith("tt") ->
                    "$API_URL/$id:$season:$episode.json"
                type == MovieType.MOVIE && id.startsWith("tt") ->
                    "$API_URL/$id.json"
                else -> {
                    val encoded = java.net.URLEncoder.encode(title.trim(), "UTF-8")
                    "$API_URL/search=$encoded.json"
                }
            }

            Log.d(TAG, "Subtitle search ($langCode): $url")

            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 12_000
                readTimeout    = 18_000
                setRequestProperty("User-Agent", "StreamX-Ultra/2.0")
                setRequestProperty("Accept", "application/json")
                instanceFollowRedirects = true
            }

            val responseCode = runCatching { conn.responseCode }.getOrElse { -1 }
            if (responseCode != 200) {
                Log.w(TAG, "Subtitle API returned $responseCode for: $url")
                return@withContext emptyList()
            }

            val rawJson   = runCatching {
                conn.inputStream.bufferedReader().use { it.readText() }
            }.getOrElse { return@withContext emptyList() }

            val json      = runCatching { JSONObject(rawJson) }.getOrElse { return@withContext emptyList() }
            val subtitles = json.optJSONArray("subtitles") ?: return@withContext emptyList()

            val results = mutableListOf<SubtitleResult>()
            for (i in 0 until subtitles.length()) {
                val sub   = subtitles.getJSONObject(i)
                val lang  = sub.optString("lang", "en")
                val subUrl = sub.optString("url", "")
                val subTitle = sub.optString("id", title)

                if (subUrl.isEmpty()) continue
                // Filter by language if specified
                // Filter by language: "all" shows everything, else match lang prefix
                // This ensures Hindi dub selection shows Hindi subtitles etc.
                if (langCode != "all") {
                    val matches = lang.startsWith(langCode.take(2), ignoreCase = true) ||
                                  langCode.startsWith(lang.take(2), ignoreCase = true)
                    if (!matches) continue
                }

                results.add(SubtitleResult(
                    id       = sub.optString("id", i.toString()),
                    title    = subTitle.replace(Regex("\\.(srt|sub|ass|ssa)$"), "").take(60),
                    lang     = SUPPORTED_LANGUAGES.find { it.code.startsWith(lang, true) }?.displayName ?: lang,
                    langCode = lang,
                    url      = subUrl,
                    downloads = sub.optInt("downloads", 0)
                ))
            }

            // Sort: most downloads first
            results.sortByDescending { it.downloads }
            Log.d(TAG, "Found ${results.size} subtitles")
            results.take(20)  // return top 20

        } catch (e: Exception) {
            Log.e(TAG, "Subtitle search failed: ${e.message}")
            emptyList()
        }
    }

    // ── Download subtitle to cache ────────────────────────────────
    suspend fun download(
        context: android.content.Context,
        result:  SubtitleResult
    ): File? = withContext(Dispatchers.IO) {
        try {
            val cacheFile = File(context.cacheDir, "subtitle_${result.id}.srt")
            if (cacheFile.exists() && cacheFile.length() > 100) return@withContext cacheFile

            val conn = (URL(result.url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 10_000
                readTimeout    = 15_000
                instanceFollowRedirects = true
            }

            if (conn.responseCode in 200..299) {
                cacheFile.outputStream().use { out ->
                    conn.inputStream.use { it.copyTo(out) }
                }
                Log.d(TAG, "Subtitle downloaded: ${cacheFile.absolutePath}")
                cacheFile
            } else null

        } catch (e: Exception) {
            Log.e(TAG, "Subtitle download failed: ${e.message}")
            null
        }
    }
}
