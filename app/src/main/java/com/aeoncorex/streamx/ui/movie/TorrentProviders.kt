package com.aeoncorex.streamx.ui.movie

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import org.json.JSONArray
import org.json.JSONObject
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.converter.scalars.ScalarsConverterFactory
import retrofit2.http.GET
import retrofit2.http.Query
import retrofit2.http.Url
import java.util.concurrent.TimeUnit

// ═══════════════════════════════════════════════════════════════════
//  TorrentProviders — Language-Aware Torrent Search
//  ──────────────────────────────────────────────────────────────
//  Sources (JSON API — no scraping):
//    EZTV           → English TV series by IMDB ID
//    TPB (apibay)   → Pirate Bay JSON API — Dual Audio, multi-lang
//    TorrentCSV     → DHT-indexed JSON API — closest to YTS for dubs
//    SolidTorrents  → REST JSON API — dubbed movies & series
//    AnimeTosho     → JSON API — anime series, better than Nyaa RSS
//
//  Sources (HTML scraping — slower but more results):
//    NYAA           → Anime torrents (RSS/HTML)
//    1337x          → All types (parallel detail page fetch)
//    TorrentGalaxy  → Hindi/South Indian dubs
//    KAT            → KickassTorrents
//    BitSearch      → General fallback
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
// ════════════════════════════════════════════════════════════════════

object DubQueryBuilder {

    fun buildQueries(
        title:    String,
        dubLang:  DubLanguage,
        isSeries: Boolean = false,
        season:   Int     = 0,
        episode:  Int     = 0
    ): List<String> {
        // FIX: titles with colons/special chars (e.g. "Predator: Badlands")
        // broke search-engine matching on several sites when combined with
        // quality terms — "Predator: Badlands Hindi 1080p BluRay" often
        // matched nothing even when a plain "Predator Badlands Hindi"
        // would. Strip punctuation that torrent release names never
        // actually contain (colons, most punctuation gets replaced with
        // spaces or dots in real release titles).
        val cleanTitle = sanitize(title)
        val base = buildBase(cleanTitle, isSeries, season, episode)

        return if (dubLang.isNativeLang) {
            listOf(
                "$base 1080p BluRay",
                "$base WEB-DL 1080p",
                "$base 720p WEB-DL",
                base
            )
        } else {
            val queries = mutableListOf<String>()
            for (kw in dubLang.searchKeywords) {
                for (qual in dubLang.torrentTerms) {
                    val q = if (qual.isEmpty()) "$base $kw".trim()
                            else "$base $kw $qual".trim()
                    queries.add(q)
                }
            }
            // Broadest possible query — just title + primary language
            // keyword, no quality/source terms at all. Placed LAST since
            // callers generally try queries in order and this is the
            // most permissive; it's also the one most likely to still
            // return something when quality-qualified queries return
            // nothing (new/rare releases especially).
            queries.add("$base ${dubLang.searchKeywords.first()}".trim())
            queries.distinct()
        }
    }

    /// Strips characters that appear in display titles (colons, most
    /// punctuation) but essentially never appear in actual torrent
    /// release names, which use spaces/dots/hyphens as separators.
    /// Keeping alphanumerics, spaces, and hyphens is enough for every
    /// site we search — anything else is more likely to hurt matching
    /// than help it.
    private fun sanitize(title: String): String =
        title.replace(Regex("[^\\p{L}\\p{N}\\s-]"), " ")
             .replace(Regex("\\s+"), " ")
             .trim()

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

    // ── Shared HTTP client ────────────────────────────────────────
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    // ── EZTV mirrors ─────────────────────────────────────────────
    private val EZTV_MIRRORS = listOf(
        "https://eztvtorrent.co/api/get-torrents",
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

    // 1337x mirrors REMOVED — no longer used here, see indexer/sites/x1337x.rs (Rust)

    // ═══════════════════════════════════════════════════════════════
    //  EZTV: Fetch TV series by IMDB ID
    // ═══════════════════════════════════════════════════════════════

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
                        source  = "EZTV"
                    )
                }
                if (!items.isNullOrEmpty()) return items
            } catch (e: Exception) {
                Log.w("TorrentProviders", "EZTV mirror failed: $url — ${e.message}")
            }
        }
        return emptyList()
    }

    // ═══════════════════════════════════════════════════════════════
    //  EZTV: Dubbed series title search
    // ═══════════════════════════════════════════════════════════════

    suspend fun fetchSeriesDubbed(
        title:   String,
        season:  Int,
        episode: Int,
        dubLang: DubLanguage
    ): List<StreamLink> = withContext(Dispatchers.IO) {
        val queries = DubQueryBuilder.buildQueries(title, dubLang, true, season, episode)
        for (query in queries.take(3)) {
            try {
                val enc  = java.net.URLEncoder.encode(query, "UTF-8")
                val html = httpClient.newCall(
                    Request.Builder()
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
                        source  = "EZTV [${dubLang.label}]"
                    )
                }.filter { it.seeds > 0 }

                if (results.isNotEmpty()) return@withContext results
            } catch (e: Exception) {
                Log.w("TorrentProviders", "EZTV dubbed failed: ${e.message}")
            }
        }
        emptyList()
    }

    // ═══════════════════════════════════════════════════════════════
    //  fetch1337x() REMOVED — replaced by IndexerNative (Rust).
    //
    //  Root cause found: this used a stale search path
    //  ("$mirror/search/$enc/1/") and a stale row selector
    //  ("table.table-list tbody tr") that no longer match 1337x's
    //  current HTML. Verified against Jackett's live 1337x.yml
    //  definition — the correct path is
    //  "sort-search/{query}/seeders/desc/1/" and rows are matched via
    //  "tr:has(a[href^='/torrent/'])". This function silently returned
    //  zero results on every call, which is why only YTS ever worked.
    //
    //  Fixed, Jackett-verified selectors now live in:
    //    app/src/main/rust/src/indexer/sites/x1337x.rs
    //  Called from Kotlin via IndexerNative.searchDubbed() / searchAll().
    // ═══════════════════════════════════════════════════════════════

    // ═══════════════════════════════════════════════════════════════
    //  NYAA: Anime torrents (RSS)
    // ═══════════════════════════════════════════════════════════════

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
                    val size   = Regex("(\\d+(\\.\\d+)?\\s*(MiB|GiB|MB|GB))").find(desc)?.value ?: "Unknown"
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
            } catch (e: Exception) { emptyList() }
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  BitSearch: General backup
    // ═══════════════════════════════════════════════════════════════

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

    // ═══════════════════════════════════════════════════════════════
    //  fetchTorrentGalaxy() REMOVED — replaced by IndexerNative (Rust).
    //
    //  Root cause: this used a stale endpoint
    //  ("$base/torrents.php?search=$enc&sort=seeders&order=desc") and
    //  stale row/class selectors ("div.tgxtablerow" with "a.txlight",
    //  "span.tul"/"span.tdl"). Verified against Jackett's live
    //  torrentgalaxyclone.yml definition — the current site uses
    //  "/get-posts/keywords:{query}" and the magnet link is directly
    //  in the listing row via "a[href^='magnet:?xt=']", no
    //  "a.txlight"/"span.tul" classes exist anymore.
    //
    //  Fixed, Jackett-verified selectors now live in:
    //    app/src/main/rust/src/indexer/sites/tgx.rs
    //  This also gains IMDB-ID exact search (search_by_imdb), which
    //  the old Kotlin version never supported.
    // ═══════════════════════════════════════════════════════════════

    // ═══════════════════════════════════════════════════════════════
    //  fetchTPB() REMOVED — replaced by IndexerNative (Rust).
    //
    //  This Kotlin version hardcoded "cat=200" (Video only), which
    //  silently excluded any drama/anime/TV release TPB categorizes
    //  outside that single code — a real coverage gap, not just a
    //  style difference. It also had no IMDB-field filtering and no
    //  query cleanup (Jackett's thepiratebay.yml strips apostrophe-"s"
    //  and CJK characters before querying, since TPB's search engine
    //  chokes on both — relevant for Chinese/Korean-titled searches).
    //
    //  Fixed, Jackett-verified version (all categories, IMDB filtering,
    //  query cleanup) now lives in:
    //    app/src/main/rust/src/indexer/sites/tpb.rs
    // ═══════════════════════════════════════════════════════════════

    // ═══════════════════════════════════════════════════════════════
    //  fetchKAT() REMOVED — replaced by IndexerNative (Rust).
    //
    //  Root cause: all three mirrors ("kickasstorrents.to", "katcr.to",
    //  "kickass.pm") are dead or redirect elsewhere, and the search
    //  path ("/usearch/{q}/?field=seeders&sorder=desc") is the old KAT
    //  URL scheme. Verified against Jackett's live
    //  kickasstorrents-to.yml — the working mirror is
    //  "kickass.torrentbay.st" with path "/search/?q={q}", rows at
    //  "table.data > tbody > tr:has(a[href^='magnet:?xt='])".
    //
    //  Fixed, Jackett-verified selectors now live in:
    //    app/src/main/rust/src/indexer/sites/kat.rs
    // ═══════════════════════════════════════════════════════════════

    // ═══════════════════════════════════════════════════════════════
    //  fetchDubbed() REMOVED — this function was DEAD CODE.
    //
    //  It was never called anywhere in TorrentRepository.kt. The actual
    //  dubbed-search path in getStreamLinks() called fetch1337x(),
    //  fetchTorrentGalaxy(), and fetchKAT() directly and in parallel —
    //  all three of which were broken (see removal notes above), which
    //  is the real reason only YTS ever returned dubbed results.
    //
    //  The replacement flow now runs entirely through
    //  IndexerNative.searchDubbed() (Rust, parallel across 1337x/TGx/
    //  KAT/TorrentDownload with title-based dub-tag filtering) alongside
    //  TorrentCSV, SolidTorrents, TPB, and EZTV — see
    //  TorrentRepository.getStreamLinks().
    // ═══════════════════════════════════════════════════════════════

    // ═══════════════════════════════════════════════════════════════
    //  TorrentCSV — Pure DHT index with JSON API (closest to YTS for dubs)
    //
    //  WHY THIS IS THE BEST DUBBED SOURCE:
    //  • No scraping — real JSON API backed by DHT crawl (100M+ torrents)
    //  • Returns infohash + seeders directly → magnet in 1 request
    //  • No Cloudflare, no rate limit for reasonable use
    //  • Works for Hindi, Tamil, Telugu, Bengali dubs reliably
    //
    //  Endpoint: GET /service/search?q={query}&size=20
    //  Response: {"torrents":[{"name","infohash","seeders","leechers","size_bytes"}]}
    // ═══════════════════════════════════════════════════════════════

    suspend fun fetchTorrentCSV(query: String): List<StreamLink> = withContext(Dispatchers.IO) {
        // torrents-csv.ml removed — confirmed parked/for-sale domain (2026),
        // no longer resolves to the actual service. torrents-csv.com is the
        // live primary; git.torrents-csv.com is the maintainer's own fallback.
        val MIRRORS = listOf(
            "https://torrents-csv.com"
        )
        for (base in MIRRORS) {
            try {
                val enc      = java.net.URLEncoder.encode(query, "UTF-8")
                val url      = "$base/service/search?q=$enc&size=20"
                val response = httpClient.newCall(
                    Request.Builder()
                        .url(url)
                        .header("User-Agent", "Mozilla/5.0")
                        .header("Accept", "application/json")
                        .build()
                ).execute().body?.string() ?: continue

                val root     = JSONObject(response)
                val torrents = root.optJSONArray("torrents") ?: continue
                val results  = mutableListOf<StreamLink>()

                for (i in 0 until torrents.length()) {
                    val item    = torrents.getJSONObject(i)
                    val name    = item.optString("name",      "")
                    val hash    = item.optString("infohash",  "")
                    val seeds   = item.optInt("seeders",      0)
                    val peers   = item.optInt("leechers",     0)
                    val sizeB   = item.optLong("size_bytes",  0)
                    if (hash.isEmpty() || seeds <= 0) continue

                    results.add(StreamLink(
                        title   = name,
                        magnet  = buildMagnet(hash, name),
                        quality = detectQuality(name),
                        seeds   = seeds,
                        peers   = peers,
                        size    = formatSize(sizeB),
                        source  = "TorrentCSV"
                    ))
                }
                if (results.isNotEmpty()) {
                    Log.d("TorrentProviders", "TorrentCSV: ${results.size} for: $query")
                    return@withContext results.sortedByDescending { it.seeds }.take(10)
                }
            } catch (e: Exception) {
                Log.w("TorrentProviders", "TorrentCSV failed: ${e.message}")
            }
        }
        emptyList()
    }

    // ═══════════════════════════════════════════════════════════════
    //  SolidTorrents — REST JSON API, good for dubbed movies & series
    //
    //  Endpoint: GET /api/v1/search?q={query}&sort=seeders&category=video
    //  Response: {"results":[{"title","infohash","size",
    //             "swarm":{"seeders","leechers"}}]}
    //
    //  Advantage over scraping: structured, consistent, fast (1 request).
    //  Good coverage of Dual Audio and Hindi dubbed Hollywood.
    // ═══════════════════════════════════════════════════════════════

    suspend fun fetchSolidTorrents(query: String): List<StreamLink> = withContext(Dispatchers.IO) {
        val MIRRORS = listOf(
            "https://solidtorrents.to",
            "https://solidtorrents.eu",
            "https://solidtorrents.net"
        )
        for (base in MIRRORS) {
            try {
                val enc      = java.net.URLEncoder.encode(query, "UTF-8")
                val url      = "$base/api/v1/search?q=$enc&sort=seeders&category=video&limit=15"
                val response = httpClient.newCall(
                    Request.Builder()
                        .url(url)
                        .header("User-Agent", "Mozilla/5.0")
                        .header("Accept", "application/json")
                        .build()
                ).execute().body?.string() ?: continue

                val root    = JSONObject(response)
                val results_arr = root.optJSONArray("results") ?: continue
                val results = mutableListOf<StreamLink>()

                for (i in 0 until results_arr.length()) {
                    val item  = results_arr.getJSONObject(i)
                    val title = item.optString("title",    "")
                    val hash  = item.optString("infohash", "")
                    val sizeB = item.optLong("size",       0)
                    val swarm = item.optJSONObject("swarm")
                    val seeds = swarm?.optInt("seeders",  0) ?: 0
                    val peers = swarm?.optInt("leechers", 0) ?: 0
                    if (hash.isEmpty() || seeds <= 0) continue

                    results.add(StreamLink(
                        title   = title,
                        magnet  = buildMagnet(hash, title),
                        quality = detectQuality(title),
                        seeds   = seeds,
                        peers   = peers,
                        size    = formatSize(sizeB),
                        source  = "SolidTorrents"
                    ))
                }
                if (results.isNotEmpty()) {
                    Log.d("TorrentProviders", "SolidTorrents: ${results.size} for: $query")
                    return@withContext results.sortedByDescending { it.seeds }.take(10)
                }
            } catch (e: Exception) {
                Log.w("TorrentProviders", "SolidTorrents failed: ${e.message}")
            }
        }
        emptyList()
    }

    // ═══════════════════════════════════════════════════════════════
    //  AnimeTosho — JSON API for anime (better than Nyaa RSS for series)
    //
    //  WHY BETTER THAN NYAA RSS:
    //  • Nyaa RSS: no filter by episode number, XML parsing, slower
    //  • AnimeTosho: JSON API, filter by episode/season, AniDB linked
    //  • Better metadata: resolution, subs vs dubs, group name
    //  • Works for both dubbed and subbed anime series
    //
    //  Endpoint: GET https://feed.animetosho.org/api?q={query}&limit=20
    //  Response: [{id,title,magnet_uri,seeders,leechers,total_size}]
    // ═══════════════════════════════════════════════════════════════

    suspend fun fetchAnimeTosho(query: String, episode: Int = 0): List<StreamLink> = withContext(Dispatchers.IO) {
        try {
            val epStr = if (episode > 0) String.format("%02d", episode) else ""
            val q     = if (epStr.isNotEmpty()) "$query $epStr" else query
            val enc   = java.net.URLEncoder.encode(q, "UTF-8")
            val url   = "https://feed.animetosho.org/api?q=$enc&limit=20"

            val response = httpClient.newCall(
                Request.Builder()
                    .url(url)
                    .header("User-Agent", "StreamX-Ultra/2.0")
                    .header("Accept", "application/json")
                    .build()
            ).execute().body?.string() ?: return@withContext emptyList()

            val arr     = JSONArray(response)
            val results = mutableListOf<StreamLink>()

            for (i in 0 until arr.length()) {
                val item   = arr.getJSONObject(i)
                val title  = item.optString("title",      "")
                val magnet = item.optString("magnet_uri", "")
                val seeds  = item.optInt("seeders",       0)
                val peers  = item.optInt("leechers",      0)
                val sizeB  = item.optLong("total_size",   0)
                if (magnet.isEmpty() || !magnet.startsWith("magnet")) continue

                results.add(StreamLink(
                    title   = title,
                    magnet  = magnet,
                    quality = when {
                        title.contains("1080", true) -> "1080p"
                        title.contains("720",  true) -> "720p"
                        title.contains("480",  true) -> "480p"
                        else                         -> "HD"
                    },
                    seeds   = seeds,
                    peers   = peers,
                    size    = formatSize(sizeB),
                    source  = "AnimeTosho"
                ))
            }
            Log.d("TorrentProviders", "AnimeTosho: ${results.size} for: $q")
            results.sortedByDescending { it.seeds }.take(10)
        } catch (e: Exception) {
            Log.w("TorrentProviders", "AnimeTosho failed: ${e.message}")
            emptyList()
        }
    }

    // ── Helpers ───────────────────────────────────────────────────

    private fun detectQuality(title: String): String = when {
        Regex("2160p|4K|UHD",     RegexOption.IGNORE_CASE).containsMatchIn(title) -> "4K"
        title.contains("1080p", true) || title.contains("1080i", true)            -> "1080p"
        title.contains("720p",  true)                                              -> "720p"
        title.contains("480p",  true)                                              -> "480p"
        Regex("BluRay|Blu-Ray|REMUX", RegexOption.IGNORE_CASE).containsMatchIn(title) -> "1080p"
        Regex("WEB-DL|WEBRip",        RegexOption.IGNORE_CASE).containsMatchIn(title) -> "720p"
        else                                                                       -> "HD"
    }

    private fun formatSize(bytes: Long): String {
        if (bytes <= 0) return "Unknown"
        val mb = bytes / (1024 * 1024)
        return if (mb > 1000) String.format("%.2f GB", mb / 1024.0) else "$mb MB"
    }

    private fun buildMagnet(hash: String, title: String): String {
        val enc = java.net.URLEncoder.encode(title, "UTF-8")
        return "magnet:?xt=urn:btih:$hash&dn=$enc"
    }
}
