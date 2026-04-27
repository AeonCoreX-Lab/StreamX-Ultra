package com.aeoncorex.streamx.ui.movie

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

// ═══════════════════════════════════════════════════════════════════
//  MovieSourceScraper — Movie Box Style Auto-Extraction
//  ──────────────────────────────────────────────────────
//  How Movie Box works (from screenshots):
//    "Analysing from [123moviesfree.net]"
//    "Analysing from [fzmovie.net]"
//    "Analysing from [eztvtorrent.co]"  ← torrent mirror
//    "Analysing from [netnaija.com]"
//
//  They scrape these sites, extract .m3u8 / .mp4 stream URLs,
//  then feed directly to ExoPlayer → instant play, no buffer.
//
//  Sources by content type:
//    Hollywood original   → 123moviesfree.net, vidsrc.win
//    Hindi dub            → fzmovie.net, hindimovies.to
//    Tamil/Telugu dub     → 1tamilmv.cfd (magnet) / fzmovie
//    Nollywood/African    → netnaija.com
//
//  ⚠️ Code is kept in-app (not separate repo) for simplicity.
//     These URLs scrape publicly available streaming sites.
// ═══════════════════════════════════════════════════════════════════
object MovieSourceScraper {

    private const val TAG = "MovieSourceScraper"

    private val client = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(18, TimeUnit.SECONDS)
        .followRedirects(true)
        .addInterceptor { chain ->
            chain.proceed(chain.request().newBuilder()
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 13; Pixel 7) " +
                    "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36")
                .header("Accept", "text/html,application/xhtml+xml,*/*;q=0.9")
                .header("Accept-Language", "en-US,en;q=0.9")
                .build()
            )
        }
        .build()

    // ── Result ────────────────────────────────────────────────────
    data class StreamSource(
        val url:       String,
        val type:      StreamType,
        val quality:   String  = "Auto",
        val language:  String  = "English",
        val sourceSite: String = "",
        val label:     String  = ""   // display name
    )

    enum class StreamType { HLS, MP4, DASH }

    // ── Main extractor ────────────────────────────────────────────
    suspend fun getSources(
        tmdbId:  Int?,
        imdbId:  String?,
        title:   String,
        type:    MovieType,
        season:  Int = 0,
        episode: Int = 0,
        language: String = "English"   // "English", "Hindi", "Tamil", "Telugu"
    ): List<StreamSource> = withContext(Dispatchers.IO) {

        val sources = mutableListOf<StreamSource>()
        val isSeries = type == MovieType.SERIES

        Log.d(TAG, "Analysing: title=$title lang=$language type=$type")

        // ── Source 1: vidsrc.win (best for English) ───────────────
        try {
            val url = if (isSeries)
                "https://vidsrc.win/tv.html?id=${tmdbId ?: imdbId}&s=$season&e=$episode"
            else
                "https://vidsrc.win/movie.html?id=${tmdbId ?: imdbId}"
            val streams = extractFromEmbed(url, "vidsrc.win")
            sources.addAll(streams)
            Log.d(TAG, "vidsrc.win: ${streams.size} streams")
        } catch (e: Exception) { Log.w(TAG, "vidsrc.win: ${e.message}") }

        // ── Source 2: multiembed (English fallback) ───────────────
        if (sources.size < 2) {
            try {
                val id = tmdbId?.toString() ?: imdbId ?: ""
                val url = if (isSeries)
                    "https://multiembed.mov/?video_id=$id&tmdb=1&s=$season&e=$episode"
                else
                    "https://multiembed.mov/?video_id=$id&tmdb=1"
                val streams = extractFromEmbed(url, "multiembed")
                sources.addAll(streams)
                Log.d(TAG, "multiembed: ${streams.size} streams")
            } catch (e: Exception) { Log.w(TAG, "multiembed: ${e.message}") }
        }

        // ── Source 3: fzmovie.net (Hindi/Nollywood dubbed) ────────
        if (language == "Hindi" || language == "English") {
            try {
                val streams = extractFromFzmovie(title, type, season, episode, language)
                sources.addAll(streams)
                Log.d(TAG, "fzmovie: ${streams.size} streams")
            } catch (e: Exception) { Log.w(TAG, "fzmovie: ${e.message}") }
        }

        // ── Source 4: 2embed (fallback) ───────────────────────────
        if (sources.isEmpty()) {
            try {
                val id = tmdbId?.toString() ?: imdbId ?: ""
                val url = if (isSeries)
                    "https://www.2embed.stream/embed/tv/$id/$season/$episode"
                else
                    "https://www.2embed.stream/embed/movie/$id"
                val streams = extractFromEmbed(url, "2embed")
                sources.addAll(streams)
                Log.d(TAG, "2embed: ${streams.size} streams")
            } catch (e: Exception) { Log.w(TAG, "2embed: ${e.message}") }
        }

        // ── Source 5: 123moviesfree (as last resort) ──────────────
        if (sources.isEmpty()) {
            try {
                val streams = extractFrom123Movies(title, type, season, episode)
                sources.addAll(streams)
                Log.d(TAG, "123moviesfree: ${streams.size} streams")
            } catch (e: Exception) { Log.w(TAG, "123moviesfree: ${e.message}") }
        }

        val distinct = sources.distinctBy { it.url }
        Log.d(TAG, "Total unique streams: ${distinct.size}")
        distinct
    }

    // ── Embed page extractor ──────────────────────────────────────
    private fun extractFromEmbed(pageUrl: String, site: String): List<StreamSource> {
        val html = fetchHtml(pageUrl, "https://vidsrc.win/") ?: return emptyList()
        return parseAllStreamUrls(html, site)
    }

    // ── fzmovie.net extractor ─────────────────────────────────────
    // fzmovie has Hindi + Hollywood content with direct MP4 links
    private fun extractFromFzmovie(
        title: String, type: MovieType,
        season: Int, episode: Int, language: String
    ): List<StreamSource> {
        val searchQuery = buildString {
            append(title.replace(" ", "+"))
            if (language == "Hindi") append("+hindi+dubbed")
            if (type == MovieType.SERIES && season > 0) append("+season+$season")
        }
        val searchUrl = "https://fzmovies.net/search.php?" +
            "searchname=$searchQuery&searchby=moviename&" +
            "category=${if (type == MovieType.MOVIE) "movies" else "series"}&beginsearch=Search"

        val searchHtml = fetchHtml(searchUrl, "https://fzmovies.net/") ?: return emptyList()

        // Extract first movie link from search results
        val linkPat = Pattern.compile("""href="(/movie-[^"]+?\.htm)"""")
        val matcher = linkPat.matcher(searchHtml)
        if (!matcher.find()) return emptyList()

        val movieUrl  = "https://fzmovies.net${matcher.group(1)}"
        val movieHtml = fetchHtml(movieUrl, "https://fzmovies.net/") ?: return emptyList()

        val streams = parseAllStreamUrls(movieHtml, "fzmovie.net")
        return streams.map { it.copy(language = if (language == "Hindi") "Hindi" else it.language) }
    }

    // ── 123moviesfree.net extractor ───────────────────────────────
    private fun extractFrom123Movies(
        title: String, type: MovieType, season: Int, episode: Int
    ): List<StreamSource> {
        val slug    = title.lowercase().replace(" ", "-").replace(Regex("[^a-z0-9-]"), "")
        val baseUrl = "https://123moviesfree.net"
        val pageUrl = if (type == MovieType.MOVIE) "$baseUrl/movie/$slug"
                      else "$baseUrl/series/$slug/season-$season/episode-$episode"

        val html = fetchHtml(pageUrl, "$baseUrl/") ?: return emptyList()
        return parseAllStreamUrls(html, "123moviesfree.net")
    }

    // ── Core URL parser ───────────────────────────────────────────
    private fun parseAllStreamUrls(html: String, site: String): List<StreamSource> {
        val found = mutableListOf<StreamSource>()

        // Pattern 1: .m3u8 HLS streams
        Pattern.compile("""["'](https?://[^"'\s]{10,}\.m3u8[^"'\s]*)["']""")
            .matcher(html).run {
                while (find()) group(1)?.takeIf { isValidUrl(it) }?.let {
                    found.add(StreamSource(it, StreamType.HLS, detectQuality(it), "English", site,
                        "${detectQuality(it)} (${site})"))
                }
            }

        // Pattern 2: JWPlayer / VideoJS file config
        Pattern.compile("""file\s*:\s*["'](https?://[^"'\s]+)["']""")
            .matcher(html).run {
                while (find()) group(1)?.takeIf { isValidUrl(it) }?.let { u ->
                    val t = if (u.contains(".m3u8")) StreamType.HLS else StreamType.MP4
                    if (found.none { it.url == u })
                        found.add(StreamSource(u, t, detectQuality(u), "English", site,
                            "${detectQuality(u)} (${site})"))
                }
            }

        // Pattern 3: source src attribute
        Pattern.compile("""<source[^>]+src=["'](https?://[^"'\s]+)["']""")
            .matcher(html).run {
                while (find()) group(1)?.takeIf { isValidUrl(it) }?.let { u ->
                    val t = if (u.contains(".m3u8")) StreamType.HLS else StreamType.MP4
                    if (found.none { it.url == u })
                        found.add(StreamSource(u, t, detectQuality(u), "English", site,
                            "${detectQuality(u)} (${site})"))
                }
            }

        // Pattern 4: direct MP4
        Pattern.compile("""["'](https?://[^"'\s]+\.mp4[^"'\s]*)["']""")
            .matcher(html).run {
                while (find()) group(1)?.takeIf { isValidUrl(it) }?.let { u ->
                    if (found.none { it.url == u })
                        found.add(StreamSource(u, StreamType.MP4, detectQuality(u), "English", site,
                            "${detectQuality(u)} (${site})"))
                }
            }

        return found
    }

    // ── HTTP helper ───────────────────────────────────────────────
    fun fetchHtml(url: String, referer: String = ""): String? = try {
        val req = Request.Builder().url(url)
            .apply { if (referer.isNotEmpty()) header("Referer", referer) }
            .build()
        val resp = client.newCall(req).execute()
        if (resp.isSuccessful) resp.body?.string() else null
    } catch (e: Exception) { Log.w(TAG, "fetchHtml $url: ${e.message}"); null }

    private fun isValidUrl(url: String): Boolean {
        if (url.length < 12) return false
        val blocked = listOf("ad.", "ads.", "analytics", "pixel", ".jpg", ".png", ".css")
        return blocked.none { url.contains(it, ignoreCase = true) }
    }

    fun detectQuality(url: String): String = when {
        url.contains("2160", true) || url.contains("4k", true)  -> "4K"
        url.contains("1080", true)  -> "1080P"
        url.contains("720", true)   -> "720P"
        url.contains("480", true)   -> "480P"
        url.contains("360", true)   -> "360P"
        else                        -> "Auto"
    }
}
