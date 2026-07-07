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

    // ── Download subtitle to local cache ──────────────────────────
    //  FIXED: previously this function existed but was never called —
    //  the player called StreamXCore.addExternalSubtitle(url) directly,
    //  handing mpv a raw remote URL. That has two real failure modes
    //  that this download() path avoids:
    //    1. mpv's own HTTP fetch for "sub-add" has no success/failure
    //       callback reaching Kotlin — a 404, timeout, or an HTML error
    //       page returned instead of an .srt file all fail *silently*.
    //       The UI showed "✓ Subtitle loaded!" even when nothing loaded.
    //    2. No local cache — every replay re-hit the network.
    //  Now: download the file ourselves, verify it's plausible SRT/VTT
    //  content (not an HTML error page or empty body), and only then
    //  hand mpv a local file:// path — which mpv can load synchronously
    //  and reliably, since no network round-trip is needed at that point.
    sealed class DownloadOutcome {
        data class Success(val file: File) : DownloadOutcome()
        data class Failure(val reason: String) : DownloadOutcome()
    }

    suspend fun download(
        context: android.content.Context,
        result:  SubtitleResult
    ): DownloadOutcome = withContext(Dispatchers.IO) {
        val safeId = result.id.replace(Regex("[^A-Za-z0-9_-]"), "_")
        val cacheFile = File(context.cacheDir, "subtitle_$safeId.srt")

        // Reuse a previously-verified cache hit — but only if it still
        // looks like real subtitle content, not a stale empty/corrupt file
        // from an earlier failed attempt that slipped through.
        if (cacheFile.exists() && looksLikeSubtitleFile(cacheFile)) {
            return@withContext DownloadOutcome.Success(cacheFile)
        }

        try {
            val conn = (URL(result.url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 10_000
                readTimeout    = 15_000
                setRequestProperty("User-Agent", "StreamX-Ultra/2.0")
                instanceFollowRedirects = true
            }

            val code = runCatching { conn.responseCode }.getOrElse { -1 }
            if (code !in 200..299) {
                Log.w(TAG, "Subtitle download HTTP $code for ${result.url}")
                return@withContext DownloadOutcome.Failure("Server returned $code")
            }

            val tmpFile = File(context.cacheDir, "subtitle_$safeId.tmp")
            tmpFile.outputStream().use { out -> conn.inputStream.use { it.copyTo(out) } }

            if (!looksLikeSubtitleFile(tmpFile)) {
                tmpFile.delete()
                Log.w(TAG, "Downloaded file for ${result.url} isn't valid subtitle content")
                return@withContext DownloadOutcome.Failure("File wasn't a valid subtitle")
            }

            // Only replace the real cache file once content is verified —
            // avoids ever leaving a half-written/invalid file at the path
            // the player will try to load.
            tmpFile.copyTo(cacheFile, overwrite = true)
            tmpFile.delete()
            Log.d(TAG, "Subtitle downloaded: ${cacheFile.absolutePath} (${cacheFile.length()} bytes)")
            DownloadOutcome.Success(cacheFile)

        } catch (e: Exception) {
            Log.e(TAG, "Subtitle download failed: ${e.message}")
            DownloadOutcome.Failure(e.message ?: "Unknown network error")
        }
    }

    // Cheap sanity check: real .srt/.vtt files are >100 bytes and contain
    // either a "-->" timing arrow (SRT/VTT) or start with "WEBVTT". This
    // catches the two most common silent-failure cases seen in practice:
    // an HTML error/redirect page saved as if it were a subtitle, or a
    // truncated/empty response from a flaky connection.
    private fun looksLikeSubtitleFile(file: File): Boolean {
        if (!file.exists() || file.length() < 100) return false
        return try {
            val buf = CharArray(2000)
            val head = file.bufferedReader().use { reader ->
                val n = reader.read(buf, 0, buf.size)
                if (n <= 0) "" else String(buf, 0, n)
            }
            head.contains("-->") || head.trimStart().startsWith("WEBVTT", ignoreCase = true)
        } catch (e: Exception) {
            false
        }
    }
}
