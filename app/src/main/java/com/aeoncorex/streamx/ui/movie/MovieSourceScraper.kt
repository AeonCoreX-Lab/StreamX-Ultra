package com.aeoncorex.streamx.ui.movie

import android.util.Base64
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

// ═══════════════════════════════════════════════════════════════════
//  MovieSourceScraper — Language-Aware Multi-Dub Stream Scraper
//  ──────────────────────────────────────────────────────────────
//  Architecture:
//    1. DubLanguage — sealed class, language-aware smart query builder
//    2. Per-language source routing (English vs all dub languages)
//    3. Multi-source parallel scraping with early exit
//    4. Iframe chain following (up to 4 levels deep)
//    5. JS deobfuscation (atob / Base64 / hex / unicode)
//    6. Quality-ranked result sorting
//
//  Supported Languages:
//    English  → vidsrc.me, vidsrc.to, vidsrc.xyz, vidlink.pro,
//               moviesapi.club, embed.su, autoembed.cc,
//               smashystream, 2embed.stream, 123moviesfree.net
//    Hindi    → fzmovies.net (DHollywood), downloads-anymovies.co,
//               vegamovies.app, hindimovies.to, 123moviesfree.net
//    Tamil    → fzmovies.net (Tamil), vegamovies.app, 123moviesfree.net
//    Telugu   → fzmovies.net (Telugu), vegamovies.app
//    Bengali  → fzmovies.net, downloads-anymovies.co
//    Korean   → 123moviesfree.net (kdrama search)
//    Japanese → 123moviesfree.net (anime/dual audio)
//    DualAudio→ downloads-anymovies.co, 123moviesfree.net
//    + Any language via Custom(lang) — generic keyword injection
//
//  Maintained: 2025-2026
// ═══════════════════════════════════════════════════════════════════

object MovieSourceScraper {

    private const val TAG = "MovieSourceScraper"
    private const val MAX_IFRAME_DEPTH = 4

    private val client = OkHttpClient.Builder()
        .connectTimeout(14, TimeUnit.SECONDS)
        .readTimeout(22, TimeUnit.SECONDS)
        .followRedirects(true)
        .addInterceptor { chain ->
            chain.proceed(
                chain.request().newBuilder()
                    .header("User-Agent",      "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36")
                    .header("Accept",          "text/html,application/xhtml+xml,*/*;q=0.9")
                    .header("Accept-Language", "en-US,en;q=0.9")
                    .header("Sec-Fetch-Dest",  "iframe")
                    .header("Sec-Fetch-Mode",  "navigate")
                    .build()
            )
        }
        .build()

    // ────────────────────────────────────────────────────────────────
    //  Result types
    // ────────────────────────────────────────────────────────────────

    data class StreamSource(
        val url:        String,
        val type:       StreamType,
        val quality:    String = "Auto",
        val language:   String = "English",
        val sourceSite: String = "",
        val label:      String = ""
    )

    enum class StreamType { HLS, MP4, DASH }

    // ════════════════════════════════════════════════════════════════
    //  DubLanguage — Language-Aware Smart Query Builder
    //  ────────────────────────────────────────────────────────────
    //  Each language carries:
    //    label           → display name & API param
    //    searchKeywords  → injected into search URLs (e.g. "Hindi Dubbed")
    //    fzCategory      → fzmovies.net category for this dub
    //    torrentTerms    → 1337x query suffixes, ranked best→fallback
    //    isNativeLang    → English = true (no "dubbed" suffix needed)
    //
    //  Usage:
    //    val lang = DubLanguage.from("tamil")
    //    val queries = lang.buildSearchQueries("Avengers Endgame")
    //    // → ["Avengers Endgame Tamil Dubbed", "Avengers Endgame Tamil Dub"]
    // ════════════════════════════════════════════════════════════════

    sealed class DubLanguage(
        val label:          String,
        val searchKeywords: List<String>,
        val fzCategory:     String,
        val torrentTerms:   List<String>,
        val isNativeLang:   Boolean = false
    ) {
        object English : DubLanguage(
            label          = "English",
            searchKeywords = listOf(""),
            fzCategory     = "All",
            torrentTerms   = listOf("1080p BluRay", "WEB-DL 1080p", "720p WEB-DL"),
            isNativeLang   = true
        )
        object Hindi : DubLanguage(
            label          = "Hindi",
            searchKeywords = listOf("Hindi Dubbed", "Hindi Dub", "HindiDub"),
            fzCategory     = "DHollywood",
            torrentTerms   = listOf("Hindi Dubbed 1080p", "Hindi Dub 1080p", "Hindi Dubbed 720p", "Hindi Dubbed")
        )
        object Tamil : DubLanguage(
            label          = "Tamil",
            searchKeywords = listOf("Tamil Dubbed", "Tamil Dub"),
            fzCategory     = "Tamil",
            torrentTerms   = listOf("Tamil Dubbed 1080p", "Tamil Dub 1080p", "Tamil Dubbed 720p", "Tamil Dubbed")
        )
        object Telugu : DubLanguage(
            label          = "Telugu",
            searchKeywords = listOf("Telugu Dubbed", "Telugu Dub"),
            fzCategory     = "Telugu",
            torrentTerms   = listOf("Telugu Dubbed 1080p", "Telugu Dub 1080p", "Telugu Dubbed 720p")
        )
        object Bengali : DubLanguage(
            label          = "Bengali",
            searchKeywords = listOf("Bengali Dubbed", "Bangla Dubbed", "Bengali Dub"),
            fzCategory     = "All",
            torrentTerms   = listOf("Bengali Dubbed", "Bangla Dubbed")
        )
        object Korean : DubLanguage(
            label          = "Korean",
            searchKeywords = listOf("Korean Dubbed", "Korean Dub"),
            fzCategory     = "All",
            torrentTerms   = listOf("Korean Dubbed", "KDrama English Sub")
        )
        object Japanese : DubLanguage(
            label          = "Japanese",
            searchKeywords = listOf("Dual Audio", "English Dubbed", "Japanese Dub"),
            fzCategory     = "All",
            torrentTerms   = listOf("Dual Audio 1080p", "English Dubbed 1080p", "Dual Audio 720p")
        )
        object DualAudio : DubLanguage(
            label          = "Dual Audio",
            searchKeywords = listOf("Dual Audio", "Hindi English"),
            fzCategory     = "All",
            torrentTerms   = listOf("Dual Audio 1080p", "Dual Audio 720p", "Dual Audio BluRay")
        )
        class Custom(lang: String) : DubLanguage(
            label          = lang,
            searchKeywords = listOf("$lang Dubbed", "$lang Dub"),
            fzCategory     = "All",
            torrentTerms   = listOf("$lang Dubbed 1080p", "$lang Dub 1080p", "$lang Dubbed")
        )

        companion object {
            fun from(language: String): DubLanguage = when (language.trim().lowercase()) {
                "english", "original", "en"  -> English
                "hindi", "hi"                -> Hindi
                "tamil", "ta"                -> Tamil
                "telugu", "te"               -> Telugu
                "bengali", "bangla", "bn"    -> Bengali
                "korean", "kr", "kdrama"     -> Korean
                "japanese", "anime", "jp"    -> Japanese
                "dual audio", "dual"         -> DualAudio
                else                         -> Custom(language)
            }
        }

        /** Returns search queries to try in order (best → fallback) */
        fun buildSearchQueries(title: String): List<String> = if (isNativeLang) {
            listOf(title)
        } else {
            searchKeywords.map { kw -> "$title $kw".trim() }
        }

        /** Returns best torrent query string for 1337x / eztvtorrent.co */
        fun bestTorrentQuery(title: String, isSeries: Boolean, season: Int, episode: Int): String {
            val base = if (isSeries && season > 0) {
                val s = "S${season.toString().padStart(2, '0')}"
                val e = if (episode > 0) "E${episode.toString().padStart(2, '0')}" else ""
                "$title $s$e"
            } else title

            return if (torrentTerms.isNotEmpty()) "$base ${torrentTerms.first()}" else base
        }
    }

    // ────────────────────────────────────────────────────────────────
    //  Internal source descriptor
    // ────────────────────────────────────────────────────────────────

    private data class ScrapeSource(
        val site:    String,
        val url:     String,
        val referer: String,
        val lang:    String  = "English",
        val dubbed:  Boolean = false
    )

    // ────────────────────────────────────────────────────────────────
    //  Main entry point — parallel scraping
    // ────────────────────────────────────────────────────────────────

    suspend fun getSources(
        tmdbId:   Int?,
        imdbId:   String?,
        title:    String,
        type:     MovieType,
        season:   Int    = 0,
        episode:  Int    = 0,
        language: String = "English"
    ): List<StreamSource> = withContext(Dispatchers.IO) {

        val dubLang  = DubLanguage.from(language)
        val isSeries = type == MovieType.SERIES
        val results  = mutableListOf<StreamSource>()

        Log.d(TAG, "Scraping: title=$title lang=${dubLang.label} dubbed=${!dubLang.isNativeLang}")

        coroutineScope {
            val jobs = buildSources(tmdbId, imdbId, title, type, season, episode, dubLang)
                .map { src ->
                    async {
                        try {
                            val streams = extractFromSource(src, title, isSeries, season, episode, dubLang)
                            Log.d(TAG, "${src.site}: ${streams.size} streams [${dubLang.label}]")
                            streams
                        } catch (e: Exception) {
                            Log.w(TAG, "${src.site}: ${e.message}")
                            emptyList()
                        }
                    }
                }

            for (job in jobs) {
                val streams = job.await()
                for (s in streams) {
                    if (!results.any { it.url == s.url }) results.add(s)
                }
                if (results.size >= 6) break
            }
        }

        // Sort: language-match first → HLS → quality score
        results.sortedWith(
            compareByDescending<StreamSource> { it.language.equals(dubLang.label, ignoreCase = true) }
                .thenByDescending { it.type == StreamType.HLS }
                .thenByDescending { qualityScore(it.quality) }
        ).also { Log.d(TAG, "Total unique streams: ${it.size}") }
    }

    // ────────────────────────────────────────────────────────────────
    //  Source list builder — language-aware routing
    // ────────────────────────────────────────────────────────────────

    private fun buildSources(
        tmdbId: Int?, imdbId: String?, title: String,
        type: MovieType, season: Int, episode: Int,
        dubLang: DubLanguage
    ): List<ScrapeSource> {
        val s        = mutableListOf<ScrapeSource>()
        val id       = tmdbId?.toString() ?: imdbId ?: ""
        val isSeries = type == MovieType.SERIES

        if (dubLang.isNativeLang) {
            // ── English / Original sources ────────────────────────

            if (id.isNotEmpty()) s.add(ScrapeSource("vidsrc.me",
                if (isSeries) "https://vidsrc.me/embed/tv?tmdb=${tmdbId ?: ""}&season=$season&episode=$episode"
                else          "https://vidsrc.me/embed/movie?tmdb=${tmdbId ?: ""}&imdb=${imdbId ?: ""}",
                "https://vidsrc.me/"))

            if (id.isNotEmpty()) s.add(ScrapeSource("vidsrc.to",
                if (isSeries) "https://vidsrc.to/embed/tv/$id/$season/$episode"
                else          "https://vidsrc.to/embed/movie/$id",
                "https://vidsrc.to/"))

            if (tmdbId != null) s.add(ScrapeSource("vidlink.pro",
                if (isSeries) "https://vidlink.pro/tv/$tmdbId/$season/$episode?autoplay=true"
                else          "https://vidlink.pro/movie/$tmdbId?autoplay=true",
                "https://vidlink.pro/"))

            if (tmdbId != null) s.add(ScrapeSource("moviesapi.club",
                if (isSeries) "https://moviesapi.club/tv/$tmdbId-$season-$episode"
                else          "https://moviesapi.club/movie/$tmdbId",
                "https://moviesapi.club/"))

            if (tmdbId != null) s.add(ScrapeSource("embed.su",
                if (isSeries) "https://embed.su/embed/tv/$tmdbId/$season/$episode"
                else          "https://embed.su/embed/movie/$tmdbId",
                "https://embed.su/"))

            if (tmdbId != null) s.add(ScrapeSource("autoembed.cc",
                if (isSeries) "https://autoembed.cc/tv/tmdb/$tmdbId-$season-$episode"
                else          "https://autoembed.cc/movie/tmdb/$tmdbId",
                "https://autoembed.cc/"))

            if (tmdbId != null) s.add(ScrapeSource("smashystream.com",
                if (isSeries) "https://player.smashystream.com/tv/$tmdbId/$season/$episode"
                else          "https://player.smashystream.com/movie/$tmdbId",
                "https://smashystream.com/"))

            s.add(ScrapeSource("vidsrc.xyz",
                if (isSeries) "https://vidsrc.xyz/embed/tv?tmdb=${tmdbId ?: ""}&season=$season&episode=$episode"
                else          "https://vidsrc.xyz/embed/movie?tmdb=${tmdbId ?: ""}&imdb=${imdbId ?: ""}",
                "https://vidsrc.xyz/"))

            s.add(ScrapeSource("2embed.stream",
                if (isSeries) "https://www.2embed.stream/embed/tv/${tmdbId ?: imdbId}/$season/$episode"
                else          "https://www.2embed.stream/embed/movie/${tmdbId ?: imdbId}",
                "https://www.2embed.stream/"))

            // 123moviesfree.net — English search
            val enc = URLEncoder.encode(title, "UTF-8")
            s.add(ScrapeSource("123moviesfree.net",
                "https://123moviesfree.net/?s=$enc",
                "https://123moviesfree.net/", lang = "English"))

            // ── API-based sources (work without JS execution) ─────
            // multiembed.mov direct stream redirect
            if (tmdbId != null || imdbId != null) {
                val vid = imdbId ?: tmdbId.toString()
                val tmdbFlag = if (tmdbId != null) 1 else 0
                s.add(ScrapeSource("multiembed",
                    if (isSeries)
                        "https://multiembed.mov/directstream.php?video_id=$vid&tmdb=$tmdbFlag&s=$season&e=$episode"
                    else
                        "https://multiembed.mov/directstream.php?video_id=$vid&tmdb=$tmdbFlag",
                    "https://multiembed.mov/"))
            }

            // autoembed.cc JSON API
            if (tmdbId != null) {
                s.add(ScrapeSource("autoembed",
                    if (isSeries)
                        "https://autoembed.cc/api/iframe.php?movie_id=$tmdbId-$season-$episode&tmdb=1"
                    else
                        "https://autoembed.cc/api/iframe.php?movie_id=$tmdbId&tmdb=1",
                    "https://autoembed.cc/"))
            }

            // moviesapi.club — JSON in script tag
            if (tmdbId != null) {
                s.add(ScrapeSource("moviesapi.club",
                    if (isSeries) "https://moviesapi.club/tv/$tmdbId-$season-$episode"
                    else          "https://moviesapi.club/movie/$tmdbId",
                    "https://moviesapi.club/"))
            }

        } else {
            // ── Dubbed / Non-English sources ─────────────────────
            //  Queries built using DubLanguage.buildSearchQueries()
            //  Each site gets the primary keyword; fallback keywords
            //  are tried inside each extractor.

            val primaryKeyword = dubLang.searchKeywords.firstOrNull() ?: "${dubLang.label} Dubbed"
            val enc            = URLEncoder.encode(title, "UTF-8")
            val encKw          = URLEncoder.encode(primaryKeyword.lowercase(), "UTF-8")
            val encLang        = URLEncoder.encode(dubLang.label.lowercase(), "UTF-8")

    // ── fzmovies (mirror-aware) — best for Hindi/Tamil/Telugu/Bengali ─
    s.add(ScrapeSource("fzmovies",
        "https://fzmovie.net/search.php?searchname=$enc+$encLang+dubbed&searchby=moviename&category=${dubLang.fzCategory}&beginsearch=Search",
        "https://fzmovie.net/", lang = dubLang.label, dubbed = true))

            // 2. downloads-anymovies.co — multi-language dub archive
            s.add(ScrapeSource("downloads-anymovies.co",
                "https://downloads-anymovies.co/?s=$enc+$encKw",
                "https://downloads-anymovies.co/", lang = dubLang.label, dubbed = true))

            // 3. 123moviesfree.net — language-keyword search
            s.add(ScrapeSource("123moviesfree.net",
                "https://123moviesfree.net/?s=$enc+$encKw",
                "https://123moviesfree.net/", lang = dubLang.label, dubbed = true))

            // 4. vegamovies.app — Hindi / Tamil / Telugu
            if (dubLang is DubLanguage.Hindi || dubLang is DubLanguage.Tamil || dubLang is DubLanguage.Telugu) {
                val encDub = URLEncoder.encode("${dubLang.label.lowercase()} dubbed", "UTF-8")
                s.add(ScrapeSource("vegamovies.app",
                    "https://vegamovies.app/?s=$enc+$encDub",
                    "https://vegamovies.app/", lang = dubLang.label, dubbed = true))
            }

            // 5. hindimovies.to — Hindi only
            if (dubLang is DubLanguage.Hindi) {
                val slug = title.lowercase().replace(Regex("[^a-z0-9]+"), "-").trimEnd('-')
                s.add(ScrapeSource("hindimovies.to",
                    "https://hindimovies.to/$slug/",
                    "https://hindimovies.to/", lang = "Hindi", dubbed = true))
            }

            // 6. English embed fallback — dubbed viewers can use original audio
            if (tmdbId != null) {
                s.add(ScrapeSource("vidsrc.me [en-fallback]",
                    if (type == MovieType.SERIES)
                        "https://vidsrc.me/embed/tv?tmdb=$tmdbId&season=$season&episode=$episode"
                    else
                        "https://vidsrc.me/embed/movie?tmdb=$tmdbId",
                    "https://vidsrc.me/", lang = "English"))
            }
        }
        return s
    }

    // ────────────────────────────────────────────────────────────────
    //  Extractor dispatcher
    // ────────────────────────────────────────────────────────────────

    private fun extractFromSource(
        src: ScrapeSource, title: String,
        isSeries: Boolean, season: Int, episode: Int,
        dubLang: DubLanguage
    ): List<StreamSource> = when (src.site) {
        "fzmovies"               -> extractFzmovies(src, dubLang)
        "downloads-anymovies.co" -> extractAnymovies(src, dubLang)
        "123moviesfree.net"      -> extract123MoviesFree(src, title, dubLang)
        "vegamovies.app"         -> extractVegamovies(src, dubLang)
        "hindimovies.to"         -> extractGenericDubSite(src, dubLang)
        else                     -> extractEmbedSource(src)
    }

    // ── Embed source (vidsrc, 2embed etc.) ───────────────────────

    private fun extractEmbedSource(src: ScrapeSource): List<StreamSource> {
        val html = fetchHtml(src.url, src.referer) ?: return emptyList()
        var streams = parseAllStreams(html, src.site, src.lang)
        if (streams.isNotEmpty()) return streams

        var currentUrl  = src.url
        var currentRef  = src.referer
        var currentHtml = html

        repeat(MAX_IFRAME_DEPTH) {
            val iframeSrc = findIframe(currentHtml, currentRef) ?: return@repeat
            if (iframeSrc == currentUrl) return@repeat
            currentRef  = currentUrl
            currentUrl  = iframeSrc
            currentHtml = fetchHtml(currentUrl, currentRef) ?: return@repeat
            streams     = parseAllStreams(currentHtml, src.site, src.lang)
            if (streams.isNotEmpty()) return streams
        }
        return emptyList()
    }

    // ── fzmovies extractor — mirror-aware ─────────────────────────
    //  fzmovies.net is DEAD since mid-2025. Current primary: fzmovies.live
    //  Official mirrors (from their Telegram channel, verified 2025-2026):
    //    .live (primary) → .host → .xyz → .de → .net (last fallback)

    private val FZMOVIES_MIRRORS = listOf(
        "https://fzmovie.net",   // primary (canonical — no 's')
        "https://www.fzmovies.net",  // alternate spelling
        "https://fzmovies.live",     // backup #1
        "https://fzmovies.host",     // backup #2
        "https://fzmovies.xyz",      // backup #3
    )

    private fun extractFzmovies(src: ScrapeSource, dubLang: DubLanguage): List<StreamSource> {
        // Sanitize title: colon, apostrophe etc. break fzmovies search
        // e.g. "Greenland 2: Migration" → "Greenland 2 Migration"
        val rawTitle = src.url.substringAfter("searchname=")
            .substringBefore("&")
            .let { java.net.URLDecoder.decode(it, "UTF-8") }
            .ifEmpty { src.url }  // fallback to URL if parsing fails

        val cleanTitle = rawTitle
            .replace(Regex("""[:\-–—'"!?.,]"""), " ")
            .replace(Regex("""\s+"""), " ")
            .trim()

        val queryPart = "searchname=${URLEncoder.encode(cleanTitle, "UTF-8")}+" +
            "${URLEncoder.encode(dubLang.label.lowercase(), "UTF-8")}+" +
            "dubbed&searchby=moviename&category=${dubLang.fzCategory}&beginsearch=Search"

        for (mirror in FZMOVIES_MIRRORS) {
            val searchUrl  = "$mirror/search.php?$queryPart"
            val referer    = "$mirror/"
            val searchHtml = fetchHtml(searchUrl, referer)
            if (searchHtml == null) { Log.w(TAG, "fzmovies mirror down: $mirror"); continue }

            val linkMatch = Pattern.compile("""href="(/movie-[^"]+?\.htm)"""").matcher(searchHtml)
            if (!linkMatch.find()) { Log.w(TAG, "fzmovies: no results on $mirror"); continue }

            val movieUrl  = "$mirror${linkMatch.group(1)}"
            val movieHtml = fetchHtml(movieUrl, referer) ?: continue

            var streams = parseAllStreams(movieHtml, "fzmovies ($mirror)", dubLang.label)
            if (streams.isNotEmpty()) return streams

            val dlMatch = Pattern.compile("""href="(/[^"]*?(?:download|dl)[^"]*?)"""", Pattern.CASE_INSENSITIVE)
                .matcher(movieHtml)

            if (!dlMatch.find()) {
                val mp4 = extractDirectMp4Links(movieHtml, "fzmovies ($mirror)", dubLang.label)
                if (mp4.isNotEmpty()) return mp4
                continue
            }

            val dlHtml = fetchHtml("$mirror${dlMatch.group(1)}", movieUrl) ?: continue
            streams = parseAllStreams(dlHtml, "fzmovies ($mirror)", dubLang.label)
            if (streams.isNotEmpty()) return streams

            val mp4 = extractDirectMp4Links(dlHtml, "fzmovies ($mirror)", dubLang.label)
            if (mp4.isNotEmpty()) return mp4
        }

        Log.w(TAG, "fzmovies: all mirrors exhausted for ${dubLang.label}")
        return emptyList()
    }

    private fun extractDirectMp4Links(html: String, site: String, language: String): List<StreamSource> {
        val results = mutableListOf<StreamSource>()
        val pat     = Pattern.compile("""href="(https?://[^"]+?\.mp4[^"]*)"""", Pattern.CASE_INSENSITIVE)
        val m       = pat.matcher(html)
        while (m.find()) {
            val url = m.group(1) ?: continue
            if (isValidStreamUrl(url))
                results.add(StreamSource(url, StreamType.MP4, detectQuality(url), language, site))
        }
        return results
    }

    // ── downloads-anymovies.co extractor ─────────────────────────

    private fun extractAnymovies(src: ScrapeSource, dubLang: DubLanguage): List<StreamSource> {
        val searchHtml = fetchHtml(src.url, src.referer) ?: return emptyList()
        val linkMatch  = Pattern.compile("""href="(https?://downloads-anymovies\.co/[^"]+?)"""")
            .matcher(searchHtml)
        if (!linkMatch.find()) return emptyList()

        val movieHtml = fetchHtml(linkMatch.group(1) ?: "", src.referer) ?: return emptyList()
        var streams = parseAllStreams(movieHtml, src.site, dubLang.label)
        if (streams.isNotEmpty()) return streams

        val dlMatch = Pattern.compile("""href="(https?://[^"]+?\.(?:mp4|m3u8)[^"]*)"""", Pattern.CASE_INSENSITIVE)
            .matcher(movieHtml)
        if (dlMatch.find()) {
            val url = dlMatch.group(1) ?: return emptyList()
            val t   = if (url.contains(".m3u8")) StreamType.HLS else StreamType.MP4
            return listOf(StreamSource(url, t, detectQuality(url), dubLang.label, src.site))
        }
        return emptyList()
    }

    // ── 123moviesfree.net extractor ──────────────────────────────
    //
    //  Flow:
    //    1. Search page → find first relevant movie/show link
    //    2. Movie detail page → find embed iframe(s)
    //    3. Follow iframe chain → extract HLS / MP4
    //
    //  For dubbed: URL already contains "[Title] [Language] Dubbed"
    //  so search results are pre-filtered by keyword.

    private fun extract123MoviesFree(
        src: ScrapeSource, title: String, dubLang: DubLanguage
    ): List<StreamSource> {
        val searchHtml = fetchHtml(src.url, src.referer) ?: return emptyList()

        // Extract movie/show page links from search results
        // 123moviesfree uses paths like /movie/SLUG/ or /tv-series/SLUG/
        val linkPat = Pattern.compile(
            """href="(https?://123moviesfree\.net/(?:movie|watch|film|tv[^"]*)/[^"]+?)"""",
            Pattern.CASE_INSENSITIVE
        )
        val linkMatcher = linkPat.matcher(searchHtml)
        val candidates  = mutableListOf<String>()
        while (linkMatcher.find() && candidates.size < 5) {
            val link = linkMatcher.group(1) ?: continue
            // Skip pagination / category links
            if (!link.contains("page") && !link.contains("category") && !link.contains("genre"))
                candidates.add(link)
        }

        if (candidates.isEmpty()) return emptyList()

        for (pageUrl in candidates) {
            val pageHtml = fetchHtml(pageUrl, src.referer) ?: continue

            // Try direct parse first
            var streams = parseAllStreams(pageHtml, "123moviesfree.net", dubLang.label)
            if (streams.isNotEmpty()) return streams

            // Follow iframe chain (streamwish / mcloud / streamtape / mixdrop)
            var iframeUrl   = findIframe(pageHtml, pageUrl)
            var depth       = 0
            var currentHtml = pageHtml
            var currentRef  = pageUrl

            while (iframeUrl != null && depth < MAX_IFRAME_DEPTH) {
                val iframeHtml = fetchHtml(iframeUrl, currentRef) ?: break
                streams = parseAllStreams(iframeHtml, "123moviesfree.net", dubLang.label)
                if (streams.isNotEmpty()) return streams
                currentRef  = iframeUrl
                currentHtml = iframeHtml
                iframeUrl   = findIframe(currentHtml, currentRef)
                depth++
            }
        }
        return emptyList()
    }

    // ── vegamovies.app extractor ──────────────────────────────────

    private fun extractVegamovies(src: ScrapeSource, dubLang: DubLanguage): List<StreamSource> {
        val searchHtml = fetchHtml(src.url, src.referer) ?: return emptyList()
        val linkMatch  = Pattern.compile("""href="(https?://vegamovies\.app/[^"]+?(?:/[^"]+){2,})"""")
            .matcher(searchHtml)
        if (!linkMatch.find()) return emptyList()
        val pageHtml = fetchHtml(linkMatch.group(1) ?: "", src.url) ?: return emptyList()
        return parseAllStreams(pageHtml, "vegamovies.app", dubLang.label)
    }

    // ── Generic dub site (hindimovies.to etc.) ────────────────────

    private fun extractGenericDubSite(src: ScrapeSource, dubLang: DubLanguage): List<StreamSource> {
        val html = fetchHtml(src.url, src.referer) ?: return emptyList()
        var streams = parseAllStreams(html, src.site, dubLang.label)
        if (streams.isNotEmpty()) return streams
        val iframeUrl  = findIframe(html, src.referer) ?: return emptyList()
        val iframeHtml = fetchHtml(iframeUrl, src.url) ?: return emptyList()
        return parseAllStreams(iframeHtml, src.site, dubLang.label)
    }

    // ────────────────────────────────────────────────────────────────
    //  Stream URL parser — all patterns
    // ────────────────────────────────────────────────────────────────

    private fun parseAllStreams(html: String, site: String, language: String): List<StreamSource> {
        val found = mutableListOf<StreamSource>()
        val seen  = mutableSetOf<String>()

        val layers = buildDecodedLayers(html)

        fun add(url: String, type: StreamType, quality: String) {
            if (!seen.contains(url) && isValidStreamUrl(url)) {
                seen.add(url)
                found.add(StreamSource(url, type, quality, language, site, "$quality ($site) [$language]"))
            }
        }

        for (layer in layers) {
            // .m3u8 HLS
            Pattern.compile("""["'`](https?://[^"'`\s]{15,}\.m3u8[^"'`\s]{0,200})["'`]""")
                .matcher(layer).run { while (find()) group(1)?.let { add(it, StreamType.HLS, detectQuality(it)) } }

            // .mpd DASH
            Pattern.compile("""["'`](https?://[^"'`\s]{15,}\.mpd[^"'`\s]{0,200})["'`]""")
                .matcher(layer).run { while (find()) group(1)?.let { add(it, StreamType.DASH, detectQuality(it)) } }

            // JWPlayer sources array: [{file:"...", label:"1080p"}]
            Pattern.compile("""sources\s*:\s*\[([^\]]{10,3000})\]""")
                .matcher(layer).run {
                    while (find()) {
                        val block   = group(1) ?: continue
                        val filePat = Pattern.compile("""file\s*:\s*["'`](https?://[^"'`\s]+)["'`]""")
                        val qualPat = Pattern.compile("""label\s*:\s*["'`]([^"'`]+)["'`]""")
                        val files   = mutableListOf<String>()
                        val quals   = mutableListOf<String>()
                        filePat.matcher(block).run { while (find()) files.add(group(1) ?: "") }
                        qualPat.matcher(block).run { while (find()) quals.add(group(1) ?: "") }
                        files.forEachIndexed { i, f ->
                            val t = when { f.contains(".m3u8") -> StreamType.HLS; f.contains(".mpd") -> StreamType.DASH; else -> StreamType.MP4 }
                            add(f, t, quals.getOrNull(i) ?: detectQuality(f))
                        }
                    }
                }

            // file: "..." VideoJS / generic
            Pattern.compile("""file\s*:\s*["'`](https?://[^"'`\s]{15,})["'`]""")
                .matcher(layer).run {
                    while (find()) group(1)?.let { u ->
                        val t = when { u.contains(".m3u8") -> StreamType.HLS; u.contains(".mpd") -> StreamType.DASH; else -> StreamType.MP4 }
                        add(u, t, detectQuality(u))
                    }
                }

            // <source src="...">
            Pattern.compile("""<source[^>]+src=["'](https?://[^"'\s]{15,})["'][^>]*>""")
                .matcher(layer).run {
                    while (find()) group(1)?.let { u ->
                        val t = when { u.contains(".m3u8") -> StreamType.HLS; u.contains(".mpd") -> StreamType.DASH; else -> StreamType.MP4 }
                        add(u, t, detectQuality(u))
                    }
                }

            // Direct .mp4
            Pattern.compile("""["'`](https?://[^"'`\s]{15,}\.mp4[^"'`\s]{0,200})["'`]""")
                .matcher(layer).run { while (find()) group(1)?.let { add(it, StreamType.MP4, detectQuality(it)) } }

            // JSON "src":"..."
            Pattern.compile(""""src"\s*:\s*"(https?://[^"]{15,})"""")
                .matcher(layer).run {
                    while (find()) group(1)?.let { u ->
                        val t = when { u.contains(".m3u8") -> StreamType.HLS; u.contains(".mpd") -> StreamType.DASH; else -> StreamType.MP4 }
                        add(u, t, detectQuality(u))
                    }
                }

            // streamwish / streamtape: "hls":"..." or "stream":"..."
            Pattern.compile(""""(?:hls|stream|source|videoUrl)"\s*:\s*"(https?://[^"]{15,})"""")
                .matcher(layer).run {
                    while (find()) group(1)?.let { u ->
                        val t = if (u.contains(".m3u8")) StreamType.HLS else StreamType.MP4
                        add(u, t, detectQuality(u))
                    }
                }
        }

        return found.sortedWith(
            compareByDescending<StreamSource> { it.type == StreamType.HLS }
                .thenByDescending { it.type == StreamType.DASH }
                .thenByDescending { qualityScore(it.quality) }
        )
    }

    // ────────────────────────────────────────────────────────────────
    //  JS deobfuscation — build decoded HTML layers
    // ────────────────────────────────────────────────────────────────

    private fun buildDecodedLayers(html: String): List<String> {
        val layers = mutableListOf(html)

        // atob() Base64 blobs
        Pattern.compile("""atob\(["'`]([A-Za-z0-9+/=]{20,})["'`]\)""")
            .matcher(html).run {
                while (find()) {
                    try { layers.add(String(Base64.decode(group(1), Base64.DEFAULT))) } catch (_: Exception) {}
                }
            }

        // Standalone base64 strings that decode to HTML/JS
        Pattern.compile("""["'`]([A-Za-z0-9+/]{80,}={0,2})["'`]""")
            .matcher(html).run {
                while (find()) {
                    try {
                        val decoded = String(Base64.decode(group(1), Base64.DEFAULT))
                        if (decoded.contains("http") || decoded.contains(".m3u8")) layers.add(decoded)
                    } catch (_: Exception) {}
                }
            }

        // Hex encoded: \x68\x74\x74\x70
        Pattern.compile("""((?:\\x[0-9a-fA-F]{2}){10,})""")
            .matcher(html).run {
                while (find()) {
                    try {
                        val decoded = group(1).replace(Regex("""\\x([0-9a-fA-F]{2})""")) {
                            it.groupValues[1].toInt(16).toChar().toString()
                        }
                        if (decoded.contains("http")) layers.add(decoded)
                    } catch (_: Exception) {}
                }
            }

        // Unicode escape: \u0068\u0074\u0074\u0070
        Pattern.compile("""((?:\\u[0-9a-fA-F]{4}){6,})""")
            .matcher(html).run {
                while (find()) {
                    try {
                        val decoded = group(1).replace(Regex("""\\u([0-9a-fA-F]{4})""")) {
                            it.groupValues[1].toInt(16).toChar().toString()
                        }
                        if (decoded.contains("http")) layers.add(decoded)
                    } catch (_: Exception) {}
                }
            }

        return layers
    }

    // ────────────────────────────────────────────────────────────────
    //  Iframe URL resolver
    // ────────────────────────────────────────────────────────────────

    private fun findIframe(html: String, base: String): String? {
        val pat = Pattern.compile(
            """<iframe[^>]+(?:src|data-src)\s*=\s*["']([^"']{10,})["'][^>]*>""",
            Pattern.CASE_INSENSITIVE
        )
        val m = pat.matcher(html)
        while (m.find()) {
            val src = m.group(1) ?: continue
            if (src.startsWith("data:") || src.contains("google") ||
                src.contains("facebook") || src.contains("recaptcha")) continue
            return when {
                src.startsWith("http") -> src
                src.startsWith("//")   -> "https:$src"
                src.startsWith("/")    -> try { java.net.URL(base).let { "${it.protocol}://${it.host}$src" } } catch (_: Exception) { null }
                else                   -> try { java.net.URL(java.net.URL(base), src).toString() } catch (_: Exception) { null }
            } ?: continue
        }
        return null
    }

    // ────────────────────────────────────────────────────────────────
    //  HTTP helper
    // ────────────────────────────────────────────────────────────────

    fun fetchHtml(url: String, referer: String = ""): String? = try {
        val req = Request.Builder().url(url)
            .apply { if (referer.isNotEmpty()) header("Referer", referer) }
            .header("Sec-Fetch-Dest", "iframe")
            .build()
        val resp = client.newCall(req).execute()
        if (resp.isSuccessful) resp.body?.string() else null
    } catch (e: Exception) { Log.w(TAG, "fetchHtml $url: ${e.message}"); null }

    // ────────────────────────────────────────────────────────────────
    //  Validators & helpers
    // ────────────────────────────────────────────────────────────────

    private fun isValidStreamUrl(url: String): Boolean {
        if (url.length < 15 || url.length > 500) return false
        val blocked = listOf("analytics", "pixel", "tracking", "ad.", "advert", ".jpg",
                             ".png", ".gif", ".css", "favicon", "doubleclick", "recaptcha")
        return blocked.none { url.contains(it, ignoreCase = true) }
    }

    fun detectQuality(url: String): String = when {
        Regex("2160|4[kK]", RegexOption.IGNORE_CASE).containsMatchIn(url) -> "4K"
        url.contains("1080", true) -> "1080P"
        url.contains("720",  true) -> "720P"
        url.contains("480",  true) -> "480P"
        url.contains("360",  true) -> "360P"
        else                       -> "Auto"
    }

    private fun qualityScore(q: String) = when (q) {
        "4K" -> 4; "1080P" -> 3; "720P" -> 2; "480P" -> 1; else -> 0
    }
}
