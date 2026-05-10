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
        val base = buildBase(title, isSeries, season, episode)
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

    // ── 1337x mirrors ────────────────────────────────────────────
    private val L337X_MIRRORS = listOf(
        "https://1337x.to",
        "https://1337x.st",
        "https://x1337x.eu"
    )

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
    //  1337x — Direct in-app scraping
    //
    //  BOTTLENECK FIX:
    //  Old: for each result row → fetch detail page (sequential)
    //       5 results = 1 search request + 5 detail requests = 6 total,
    //       executed ONE BY ONE → 15–30s for a dubbed search.
    //
    //  New: all detail page requests run concurrently with coroutineScope+async.
    //       5 results → 1 search + 5 detail requests PARALLEL → ~5–8s total.
    // ═══════════════════════════════════════════════════════════════

    suspend fun fetch1337x(query: String): List<StreamLink> = withContext(Dispatchers.IO) {
        for (mirror in L337X_MIRRORS) {
            try {
                val enc        = java.net.URLEncoder.encode(query, "UTF-8")
                val searchUrl  = "$mirror/search/$enc/1/"
                val searchHtml = httpClient.newCall(
                    Request.Builder()
                        .url(searchUrl)
                        .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                        .header("Accept-Language", "en-US,en;q=0.9")
                        .build()
                ).execute().body?.string() ?: continue

                val doc  = Jsoup.parse(searchHtml)
                val rows = doc.select("table.table-list tbody tr").take(5)
                if (rows.isEmpty()) continue

                // ── Parallel detail page fetches ──────────────────
                data class RowMeta(
                    val titleText:  String,
                    val detailPath: String,
                    val seeds:      Int,
                    val peers:      Int,
                    val size:       String
                )

                val metas = rows.mapNotNull { row ->
                    val nameLinks  = row.select("td.name a")
                    val titleEl    = if (nameLinks.size >= 2) nameLinks[1] else nameLinks.firstOrNull() ?: return@mapNotNull null
                    val detailPath = titleEl.attr("href")
                    if (detailPath.isBlank()) return@mapNotNull null
                    val seeds = row.select("td.seeds").text().toIntOrNull()  ?: 0
                    if (seeds <= 0) return@mapNotNull null
                    RowMeta(
                        titleText  = titleEl.text(),
                        detailPath = detailPath,
                        seeds      = seeds,
                        peers      = row.select("td.leeches").text().toIntOrNull() ?: 0,
                        size       = row.select("td.size").first()?.ownText()?.trim() ?: "Unknown"
                    )
                }
                if (metas.isEmpty()) continue

                // All detail requests fire simultaneously
                val results = coroutineScope {
                    metas.map { meta ->
                        async(Dispatchers.IO) {
                            try {
                                val detailHtml = httpClient.newCall(
                                    Request.Builder()
                                        .url("$mirror${meta.detailPath}")
                                        .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
                                        .build()
                                ).execute().body?.string() ?: return@async null

                                val magnet = Jsoup.parse(detailHtml)
                                    .select("a[href^=magnet:]").first()?.attr("href")
                                    ?: return@async null

                                StreamLink(
                                    title   = meta.titleText,
                                    magnet  = magnet,
                                    quality = detectQuality(meta.titleText),
                                    seeds   = meta.seeds,
                                    peers   = meta.peers,
                                    size    = meta.size,
                                    source  = "1337x"
                                )
                            } catch (e: Exception) {
                                Log.w("TorrentProviders", "1337x detail fetch failed: ${e.message}")
                                null
                            }
                        }
                    }.awaitAll().filterNotNull()
                }

                if (results.isNotEmpty()) return@withContext results.sortedByDescending { it.seeds }

            } catch (e: Exception) {
                Log.w("TorrentProviders", "1337x mirror failed: $mirror — ${e.message}")
            }
        }
        emptyList()
    }

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
    //  TorrentGalaxy — Excellent for Hindi/South Indian dubbed content
    //  Uses JSON search API (tgx.rs/search/api)
    // ═══════════════════════════════════════════════════════════════

    suspend fun fetchTorrentGalaxy(query: String): List<StreamLink> = withContext(Dispatchers.IO) {
        val TGX_MIRRORS = listOf(
            "https://torrentgalaxy.to",
            "https://tgx.rs",
            "https://torrentgalaxy.mx"
        )
        for (base in TGX_MIRRORS) {
            try {
                val enc  = java.net.URLEncoder.encode(query, "UTF-8")
                val url  = "$base/torrents.php?search=$enc&sort=seeders&order=desc"
                val html = httpClient.newCall(
                    Request.Builder()
                        .url(url)
                        .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
                        .header("Accept", "text/html,application/xhtml+xml")
                        .build()
                ).execute().body?.string() ?: continue

                val doc     = Jsoup.parse(html)
                // TGX result rows — each has a magnet link directly in the list
                val results = doc.select("div.tgxtablerow").take(6).mapNotNull { row ->
                    val titleEl = row.select("a.txlight").firstOrNull() ?: return@mapNotNull null
                    val magnet  = row.select("a[href^=magnet:]").firstOrNull()?.attr("href")
                                  ?: return@mapNotNull null
                    val seedsEl = row.select("span.tul").text().toIntOrNull() ?: 0
                    val peersEl = row.select("span.tdl").text().toIntOrNull() ?: 0
                    val size    = row.select("span.badge-secondary").text().ifEmpty { "Unknown" }
                    if (seedsEl <= 0) return@mapNotNull null
                    StreamLink(
                        title   = titleEl.text(),
                        magnet  = magnet,
                        quality = detectQuality(titleEl.text()),
                        seeds   = seedsEl,
                        peers   = peersEl,
                        size    = size,
                        source  = "TorrentGalaxy"
                    )
                }
                if (results.isNotEmpty()) {
                    Log.d("TorrentProviders", "TorrentGalaxy: ${results.size} results for: $query")
                    return@withContext results.sortedByDescending { it.seeds }
                }
            } catch (e: Exception) {
                Log.w("TorrentProviders", "TorrentGalaxy mirror failed: ${e.message}")
            }
        }
        emptyList()
    }

    // ═══════════════════════════════════════════════════════════════
    //  The Pirate Bay — via apibay.org JSON API (no scraping needed)
    //  Great for Dual Audio and multi-language content
    // ═══════════════════════════════════════════════════════════════

    suspend fun fetchTPB(query: String): List<StreamLink> = withContext(Dispatchers.IO) {
        val APIBAY_MIRRORS = listOf(
            "https://apibay.org/q.php",
            "https://apibay.net/q.php"
        )
        for (apiUrl in APIBAY_MIRRORS) {
            try {
                val enc      = java.net.URLEncoder.encode(query, "UTF-8")
                val url      = "$apiUrl?q=$enc&cat=200" // cat 200 = video
                val response = httpClient.newCall(
                    Request.Builder()
                        .url(url)
                        .header("User-Agent", "Mozilla/5.0")
                        .build()
                ).execute().body?.string() ?: continue

                val json    = JSONArray(response)
                val results = mutableListOf<StreamLink>()

                for (i in 0 until json.length()) {
                    val item   = json.getJSONObject(i)
                    val name   = item.optString("name", "")
                    val hash   = item.optString("info_hash", "")
                    val seeds  = item.optInt("seeders", 0)
                    val peers  = item.optInt("leechers", 0)
                    val sizeB  = item.optLong("size", 0)

                    if (hash.isEmpty() || seeds <= 0) continue

                    val magnet = buildMagnet(hash, name)
                    results.add(StreamLink(
                        title   = name,
                        magnet  = magnet,
                        quality = detectQuality(name),
                        seeds   = seeds,
                        peers   = peers,
                        size    = formatSize(sizeB),
                        source  = "TPB"
                    ))
                }

                if (results.isNotEmpty()) {
                    Log.d("TorrentProviders", "TPB: ${results.size} results for: $query")
                    return@withContext results.sortedByDescending { it.seeds }.take(8)
                }
            } catch (e: Exception) {
                Log.w("TorrentProviders", "TPB mirror failed: ${e.message}")
            }
        }
        emptyList()
    }

    // ═══════════════════════════════════════════════════════════════
    //  KickassTorrents (KAT) — Good additional source for dubbed content
    // ═══════════════════════════════════════════════════════════════

    suspend fun fetchKAT(query: String): List<StreamLink> = withContext(Dispatchers.IO) {
        val KAT_MIRRORS = listOf(
            "https://kickasstorrents.to",
            "https://katcr.to",
            "https://kickass.pm"
        )
        for (base in KAT_MIRRORS) {
            try {
                val enc  = java.net.URLEncoder.encode(query, "UTF-8")
                val url  = "$base/usearch/$enc/?field=seeders&sorder=desc"
                val html = httpClient.newCall(
                    Request.Builder()
                        .url(url)
                        .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                        .build()
                ).execute().body?.string() ?: continue

                val doc     = Jsoup.parse(html)
                val results = doc.select("tr.odd, tr.even").take(5).mapNotNull { row ->
                    val titleEl = row.select("a.cellMainLink").firstOrNull() ?: return@mapNotNull null
                    val magnet  = row.select("a[href^=magnet:]").firstOrNull()?.attr("href")
                                  ?: return@mapNotNull null
                    val seeds   = row.select("td.green.center").text().toIntOrNull() ?: 0
                    val peers   = row.select("td.red.lasttd.center").text().toIntOrNull() ?: 0
                    val size    = row.select("td:nth-child(2)").text().substringBefore(" ").ifEmpty { "Unknown" }
                    if (seeds <= 0) return@mapNotNull null
                    StreamLink(
                        title   = titleEl.text(),
                        magnet  = magnet,
                        quality = detectQuality(titleEl.text()),
                        seeds   = seeds,
                        peers   = peers,
                        size    = size,
                        source  = "KAT"
                    )
                }
                if (results.isNotEmpty()) {
                    Log.d("TorrentProviders", "KAT: ${results.size} results for: $query")
                    return@withContext results.sortedByDescending { it.seeds }
                }
            } catch (e: Exception) {
                Log.w("TorrentProviders", "KAT mirror failed: ${e.message}")
            }
        }
        emptyList()
    }

    // ═══════════════════════════════════════════════════════════════
    //  Language-aware dubbed torrent search
    //  Priority:
    //    1. EZTV title search (series)
    //    2. TorrentGalaxy (best for South Asian dubs)
    //    3. 1337x dubbed query
    //    4. TPB via apibay
    //    5. KAT
    //    6. BitSearch fallback
    //    7. NYAA (anime/dual-audio)
    // ═══════════════════════════════════════════════════════════════

    suspend fun fetchDubbed(
        title:   String,
        type:    MovieType,
        season:  Int,
        episode: Int,
        dubLang: DubLanguage
    ): List<StreamLink> = withContext(Dispatchers.IO) {
        val isSeries = type == MovieType.SERIES
        val queries  = DubQueryBuilder.buildQueries(title, dubLang, isSeries, season, episode)

        // 1. EZTV for series
        if (isSeries) {
            val eztv = fetchSeriesDubbed(title, season, episode, dubLang)
            if (eztv.isNotEmpty()) return@withContext eztv
        }

        // 2. TorrentGalaxy — best for Hindi/South Indian dubs
        for (query in queries.take(2)) {
            val tgx = fetchTorrentGalaxy(query)
            if (tgx.isNotEmpty()) return@withContext tgx
        }

        // 3. 1337x
        for (query in queries.take(2)) {
            val l337 = fetch1337x(query)
            if (l337.isNotEmpty()) return@withContext l337.sortedByDescending { it.seeds }
        }

        // 4. TPB (apibay JSON — fast)
        for (query in queries.take(2)) {
            val tpb = fetchTPB(query)
            if (tpb.isNotEmpty()) return@withContext tpb
        }

        // 5. KAT
        for (query in queries.take(2)) {
            val kat = fetchKAT(query)
            if (kat.isNotEmpty()) return@withContext kat
        }

        // 6. BitSearch fallback
        for (query in queries.take(2)) {
            val bits = fetchBitSearch(query)
            if (bits.isNotEmpty()) return@withContext bits.sortedByDescending { it.seeds }
        }

        // 7. NYAA for anime/dual-audio
        if (dubLang is DubLanguage.Japanese || dubLang is DubLanguage.DualAudio) {
            val nyaa = fetchAnime(title, episode)
            if (nyaa.isNotEmpty()) return@withContext nyaa
        }

        emptyList()
    }

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
        val MIRRORS = listOf(
            "https://torrents-csv.com",
            "https://torrents-csv.ml"
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
