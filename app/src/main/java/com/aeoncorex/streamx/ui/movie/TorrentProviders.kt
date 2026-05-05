package com.aeoncorex.streamx.ui.movie

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.converter.scalars.ScalarsConverterFactory
import retrofit2.http.GET
import retrofit2.http.Query
import retrofit2.http.Url

// ═══════════════════════════════════════════════════════════════════
//  TorrentProviders — Language-Aware Torrent Search
//  ──────────────────────────────────────────────────────────────
//  Sources:
//    EZTV    → TV series by IMDB ID (eztv.re mirrors + eztvtorrent.co)
//    NYAA    → Anime torrents (RSS)
//    1337x   → All types via backend (language-aware query)
//    BitSearch→ Backup search engine
//
//  DubQueryBuilder:
//    Builds ranked query list for any dub language.
//    Example: buildQueries("Avengers Endgame", DubLanguage.Tamil)
//    → ["Avengers Endgame Tamil Dubbed 1080p",
//       "Avengers Endgame Tamil Dub 1080p",
//       "Avengers Endgame Tamil Dubbed 720p",
//       "Avengers Endgame Tamil Dubbed"]
// ═══════════════════════════════════════════════════════════════════

interface TorrentApi {
    @GET
    suspend fun getSeriesTorrents(
        @Url url: String,
        @Query("imdb_id") imdbId: String,
        @Query("limit") limit: Int = 100
    ): EztvResponse

    @GET("/")
    suspend fun getAnimeTorrents(
        @Query("page")     page:     String = "rss",
        @Query("q")        query:    String,
        @Query("c")        category: String = "1_2",
        @Query("s")        sort:     String = "seeders"
    ): String

    @GET("/")
    suspend fun searchBitSearch(@Query("q") query: String): String
}

// ════════════════════════════════════════════════════════════════════
//  DubQueryBuilder — Language-Aware Torrent Query Generator
//  ──────────────────────────────────────────────────────────────
//  Usage:
//    val queries = DubQueryBuilder.buildQueries(
//        title    = "Spider-Man No Way Home",
//        dubLang  = MovieSourceScraper.DubLanguage.Tamil,
//        isSeries = false
//    )
//    // → ["Spider-Man No Way Home Tamil Dubbed 1080p",
//    //    "Spider-Man No Way Home Tamil Dub 1080p",
//    //    "Spider-Man No Way Home Tamil Dubbed 720p",
//    //    "Spider-Man No Way Home Tamil Dubbed"]
// ════════════════════════════════════════════════════════════════════

object DubQueryBuilder {

    /**
     * Build ranked torrent search queries for [title] + [dubLang].
     * For series, injects S##E## into every query.
     * Returns queries ordered best→fallback.
     */
    fun buildQueries(
        title:    String,
        dubLang:  MovieSourceScraper.DubLanguage,
        isSeries: Boolean = false,
        season:   Int     = 0,
        episode:  Int     = 0
    ): List<String> {
        val base = buildBase(title, isSeries, season, episode)
        return if (dubLang.isNativeLang) {
            // English: try multiple quality terms
            listOf(
                "$base 1080p BluRay",
                "$base WEB-DL 1080p",
                "$base 720p WEB-DL",
                base
            )
        } else {
            // Dubbed: cartesian of (searchKeywords × torrentTerms) ranked
            val queries = mutableListOf<String>()
            for (kw in dubLang.searchKeywords) {
                for (qual in dubLang.torrentTerms) {
                    queries.add("$base $kw".trim())   // e.g. "Movie Tamil Dubbed 1080p"
                }
            }
            // Also add base + first keyword as plain fallback
            queries.add("$base ${dubLang.searchKeywords.first()}".trim())
            queries.distinct()
        }
    }

    private fun buildBase(title: String, isSeries: Boolean, season: Int, episode: Int): String {
        if (!isSeries || season == 0) return title
        val s = "S${season.toString().padStart(2, '0')}"
        val e = if (episode > 0) "E${episode.toString().padStart(2, '0')}" else ""
        return "$title $s$e"
    }
}

// ════════════════════════════════════════════════════════════════════
//  TorrentProviders
// ════════════════════════════════════════════════════════════════════

object TorrentProviders {

    // ── EZTV mirrors — eztvtorrent.co added alongside existing ones
    private val EZTV_MIRRORS = listOf(
        "https://eztvtorrent.co/api/get-torrents",   // NEW — primary fast mirror
        "https://eztv.re/api/get-torrents",
        "https://eztvx.to/api/get-torrents",
        "https://eztv1.xyz/api/get-torrents"
    )

    private val eztvApi = Retrofit.Builder()
        .baseUrl("https://eztv.re/")
        .addConverterFactory(GsonConverterFactory.create())
        .build()
        .create(TorrentApi::class.java)

    private val nyaaApi = Retrofit.Builder()
        .baseUrl("https://nyaa.si/")
        .addConverterFactory(ScalarsConverterFactory.create())
        .build()
        .create(TorrentApi::class.java)

    private val bitSearchApi = Retrofit.Builder()
        .baseUrl("https://bitsearch.to/")
        .addConverterFactory(ScalarsConverterFactory.create())
        .build()
        .create(TorrentApi::class.java)

    // ── EZTV: Fetch TV series by IMDB ID ─────────────────────────
    //  Tries all mirrors in order (eztvtorrent.co first, fastest mirror wins)

    suspend fun fetchSeries(imdbId: String, season: Int, episode: Int): List<StreamLink> {
        val cleanId = imdbId.replace("tt", "")

        for (url in EZTV_MIRRORS) {
            try {
                val response = eztvApi.getSeriesTorrents(url, cleanId)
                val items = response.torrents?.filter {
                    (it.season == "$season" && it.episode == "$episode") ||
                    (it.season.toIntOrNull() == season && it.episode.toIntOrNull() == episode)
                }?.map {
                    StreamLink(
                        title   = it.title,
                        magnet  = it.magnet_url,
                        quality = when {
                            it.title.contains("2160p", true) || it.title.contains("4K", true) -> "4K"
                            it.title.contains("1080p", true) -> "1080p"
                            it.title.contains("720p",  true) -> "720p"
                            else                             -> "HD"
                        },
                        seeds   = it.seeds,
                        peers   = it.peers,
                        size    = formatSize(it.size_bytes),
                        source  = "EZTV (${url.substringAfter("//").substringBefore("/")})"
                    )
                }
                if (!items.isNullOrEmpty()) return items
            } catch (e: Exception) {
                Log.w("TorrentProviders", "EZTV mirror failed: $url — ${e.message}")
            }
        }
        return emptyList()
    }

    // ── EZTV: Fetch dubbed series using title search ──────────────
    //  For dubbed content (Hindi/Tamil etc.) EZTV uses title search not IMDB API.
    //  Queries eztvtorrent.co search endpoint.

    suspend fun fetchSeriesDubbed(
        title:    String,
        season:   Int,
        episode:  Int,
        dubLang:  MovieSourceScraper.DubLanguage
    ): List<StreamLink> = withContext(Dispatchers.IO) {
        val queries = DubQueryBuilder.buildQueries(title, dubLang, true, season, episode)
        // eztvtorrent.co supports title search: /search/{query}
        for (query in queries.take(3)) {
            try {
                val enc  = java.net.URLEncoder.encode(query, "UTF-8")
                val html = okhttp3.OkHttpClient().newCall(
                    okhttp3.Request.Builder()
                        .url("https://eztvtorrent.co/search/$enc")
                        .header("User-Agent", "Mozilla/5.0")
                        .build()
                ).execute().body?.string() ?: continue

                val doc     = Jsoup.parse(html)
                val results = doc.select("tr.forum_header_border").mapNotNull { row ->
                    val titleEl  = row.select("td.forum_thread_post a.magnet").first()
                    val magnetEl = row.select("a.magnet").first()
                    val seeds    = row.select("td.seeds").text().toIntOrNull() ?: 0
                    val size     = row.select("td:nth-child(4)").text()
                    val magnet   = magnetEl?.attr("href") ?: return@mapNotNull null
                    if (!magnet.startsWith("magnet")) return@mapNotNull null
                    StreamLink(
                        title   = titleEl?.text() ?: query,
                        magnet  = magnet,
                        quality = detectQuality(titleEl?.text() ?: ""),
                        seeds   = seeds,
                        peers   = 0,
                        size    = size,
                        source  = "EZTVTorrent.co [${dubLang.label}]"
                    )
                }.filter { it.seeds > 0 }

                if (results.isNotEmpty()) return@withContext results
            } catch (e: Exception) {
                Log.w("TorrentProviders", "eztvtorrent search failed: ${e.message}")
            }
        }
        emptyList()
    }

    // ── NYAA: Anime torrents ──────────────────────────────────────

    suspend fun fetchAnime(queryName: String, episode: Int): List<StreamLink> {
        return withContext(Dispatchers.IO) {
            try {
                val epStr = if (episode < 10) "0$episode" else "$episode"
                val xml   = nyaaApi.getAnimeTorrents(query = "$queryName $epStr")
                val doc   = Jsoup.parse(xml, "", org.jsoup.parser.Parser.xmlParser())

                doc.select("item").mapNotNull { item ->
                    val title  = item.select("title").text()
                    val magnet = item.select("link").text()
                    val desc   = item.select("description").text()

                    val sizeRegex = Regex("(\\d+(\\.\\d+)?\\s*(MiB|GiB|MB|GB))")
                    val size      = sizeRegex.find(desc)?.value ?: "Unknown"

                    if (magnet.startsWith("magnet")) StreamLink(
                        title   = title,
                        magnet  = magnet,
                        quality = if (title.contains("1080")) "1080p" else "HD",
                        seeds   = 20,
                        peers   = 5,
                        size    = size,
                        source  = "NYAA"
                    ) else null
                }
            } catch (e: Exception) {
                emptyList()
            }
        }
    }

    // ── BitSearch: Backup for anything ───────────────────────────

    suspend fun fetchBitSearch(query: String): List<StreamLink> {
        return withContext(Dispatchers.IO) {
            try {
                val html = bitSearchApi.searchBitSearch(query)
                val doc  = Jsoup.parse(html)

                doc.select("li.search-result").mapNotNull { element ->
                    val title  = element.select("h5.title a").text()
                    val magnet = element.select("a.dl-magnet").attr("href")
                    val stats  = element.select("div.stats div")

                    if (!magnet.startsWith("magnet")) return@mapNotNull null
                    val size  = stats.getOrNull(1)?.text() ?: "Unknown"
                    val seeds = stats.getOrNull(2)?.text()?.toIntOrNull() ?: 0
                    val peers = stats.getOrNull(3)?.text()?.toIntOrNull() ?: 0
                    if (seeds <= 0) return@mapNotNull null

                    StreamLink(
                        title   = title,
                        magnet  = magnet,
                        quality = detectQuality(title),
                        seeds   = seeds,
                        peers   = peers,
                        size    = size,
                        source  = "BitSearch"
                    )
                }
            } catch (e: Exception) {
                Log.e("TorrentProviders", "BitSearch failed: ${e.message}")
                emptyList()
            }
        }
    }

    // ── Language-aware dubbed torrent search ─────────────────────
    //  Main entry for dubbed torrent requests.
    //  Tries multiple sources in priority order, stops at first success.
    //
    //  Priority:
    //    1. eztvtorrent.co title search (for series)
    //    2. BitSearch with dubbed query
    //    3. NYAA (for anime/dual-audio)

    suspend fun fetchDubbed(
        title:    String,
        type:     MovieType,
        season:   Int,
        episode:  Int,
        dubLang:  MovieSourceScraper.DubLanguage
    ): List<StreamLink> = withContext(Dispatchers.IO) {
        val isSeries = type == MovieType.SERIES
        val queries  = DubQueryBuilder.buildQueries(title, dubLang, isSeries, season, episode)

        // 1. Try eztvtorrent.co for series
        if (isSeries) {
            val eztv = fetchSeriesDubbed(title, season, episode, dubLang)
            if (eztv.isNotEmpty()) return@withContext eztv
        }

        // 2. BitSearch — best for all types including movies
        for (query in queries.take(2)) {
            val bits = fetchBitSearch(query)
            if (bits.isNotEmpty()) return@withContext bits.sortedByDescending { it.seeds }
        }

        // 3. NYAA fallback for anime
        if (dubLang is MovieSourceScraper.DubLanguage.Japanese ||
            dubLang is MovieSourceScraper.DubLanguage.DualAudio) {
            val nyaa = fetchAnime(title, episode)
            if (nyaa.isNotEmpty()) return@withContext nyaa
        }

        emptyList()
    }

    // ── Helpers ───────────────────────────────────────────────────

    private fun detectQuality(title: String): String = when {
        Regex("2160p|4K|UHD", RegexOption.IGNORE_CASE).containsMatchIn(title) -> "4K"
        title.contains("1080p", true) || title.contains("1080i", true)        -> "1080p"
        title.contains("720p",  true)                                          -> "720p"
        title.contains("480p",  true)                                          -> "480p"
        Regex("BluRay|Blu-Ray|REMUX", RegexOption.IGNORE_CASE).containsMatchIn(title) -> "1080p"
        Regex("WEB-DL|WEBRip",        RegexOption.IGNORE_CASE).containsMatchIn(title) -> "720p"
        else                                                                   -> "HD"
    }

    private fun formatSize(bytes: Long): String {
        val mb = bytes / (1024 * 1024)
        return if (mb > 1000) String.format("%.2f GB", mb / 1024.0) else "$mb MB"
    }
}
