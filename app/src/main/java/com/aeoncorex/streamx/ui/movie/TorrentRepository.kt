package com.aeoncorex.streamx.ui.movie

import android.util.Log
import kotlinx.coroutines.*
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.GET
import retrofit2.http.Query
import retrofit2.http.Url
import java.net.URLEncoder

// --- MODELS ---
data class YtsResponse(val data: YtsData?)
data class YtsData(val movies: List<YtsMovie>?)
data class YtsMovie(val id: Int, val title: String, val torrents: List<YtsTorrent>?)
data class YtsTorrent(
    val url:     String,
    val hash:    String,
    val quality: String,
    val seeds:   Int,
    val peers:   Int,
    val size:    String
)

interface YtsApi {
    @GET
    suspend fun listMovies(
        @Url url: String,
        @Query("query_term") query: String,
        @Query("limit") limit: Int = 20
    ): YtsResponse
}

// ═══════════════════════════════════════════════════════════════════
//  TorrentRepository — Aggregates all torrent sources
//  ──────────────────────────────────────────────────────────────────
//  [dubLang] DubLanguage.English ছাড়া অন্য কিছু হলে:
//    → IndexerNative.searchDubbed() (Rust) call হয়, যেটা 1337x + TGx +
//      KAT + TorrentDownload সবকটা একসাথে parallel search করে এবং
//      title-এ Hindi/Tamil/Dual Audio ইত্যাদি tag থাকা result গুলোই
//      ফিরিয়ে দেয়।
//    → TorrentCSV, SolidTorrents, TPB, EZTV/NYAA ও একই সাথে parallel
//      ভাবে চলে dubbed queries দিয়ে (DubQueryBuilder থেকে আসা)।
//    → পুরনো TorrentProviders.fetchDubbed()/fetch1337x()/
//      fetchTorrentGalaxy()/fetchKAT() সরিয়ে ফেলা হয়েছে (dead/broken
//      code — stale URL ও selector, verified against Jackett source)।
// ═══════════════════════════════════════════════════════════════════

object TorrentRepository {
    private val YTS_MIRRORS = listOf(
        "https://yts.mx/api/v2/list_movies.json",
        "https://yts.lt/api/v2/list_movies.json",
        "https://yts.rs/api/v2/list_movies.json"
    )

    private val api = Retrofit.Builder()
        .baseUrl("https://yts.lt/")
        .addConverterFactory(GsonConverterFactory.create())
        .build()
        .create(YtsApi::class.java)

    // HTTP trackers prioritized (UDP often blocked on mobile)
    private val TRACKERS = listOf(
        "http://tracker.bt4g.com:2095/announce",
        "http://tracker.files.fm:6969/announce",
        "http://tracker.gbitt.info:80/announce",
        "http://tracker.ipv6tracker.org:80/announce",
        "http://tracker.nyaa.uk:6969/announce",
        "http://tracker.zerobytes.xyz:1337/announce",
        "https://tracker.bt4g.com:443/announce",
        "https://tracker.nanoha.org:443/announce",
        "udp://tracker.opentrackr.org:1337/announce",
        "udp://open.demonii.com:1337/announce",
        "udp://tracker.openbittorrent.com:80",
        "udp://tracker.coppersurfer.tk:6969",
        "udp://glotorrents.pw:6969/announce",
        "udp://9.rarbg.to:2710",
        "udp://tracker.torrent.eu.org:451/announce",
        "udp://tracker.internetwarriors.net:1337/announce",
        "udp://tracker.leechers-paradise.org:6969/announce",
        "udp://tracker.cyberia.is:6969/announce",
        "udp://exodus.desync.com:6969/announce",
        "udp://ipv4.tracker.harry.lu:80/announce",
        "udp://tracker.moeking.me:6969/announce",
        "udp://tracker.skynetcloud.tk:6969/announce",
        "udp://tracker.pirateparty.gr:6969/announce",
        "udp://tracker.zerobytes.xyz:1337/announce"
    )

    // ── Main entry point ─────────────────────────────────────────

    suspend fun getStreamLinks(
        type:      MovieType,
        title:     String,
        imdbId:    String?,
        season:    Int          = 0,
        episode:   Int          = 0,
        isAnime:   Boolean      = false,
        dubLang:   DubLanguage  = DubLanguage.English   // ← NEW: dub language
    ): List<StreamLink> = withContext(Dispatchers.IO) {
        val allLinks = mutableListOf<StreamLink>()

        // ──────────────────────────────────────────────────────────
        //  ARCHITECTURE FIX — v2 parallel provider dispatch
        //
        //  OLD (buggy):
        //    • fetchDubbed() ran TGX+1337x+TPB+KAT+BitSearch SEQUENTIALLY
        //      inside a single async job → TGX timeout = 20s wasted before
        //      1337x was even tried. Dubbed search could take 60–90s.
        //    • Repository ALSO added separate TGX+TPB async jobs → same
        //      dubbed query hit TGX twice and TPB twice per search.
        //
        //  NEW (fixed):
        //    • Every provider gets its own async job → ALL run in parallel.
        //    • Each job is wrapped in withTimeoutOrNull() → one slow provider
        //      cannot block the rest. Results collected after all complete.
        //    • fetchDubbed() removed from Repository — providers called directly.
        //    • English path unchanged (YTS / EZTV / 1337x / BitSearch).
        // ──────────────────────────────────────────────────────────
        coroutineScope {
            val jobs = mutableListOf<Deferred<List<StreamLink>>>()

            if (!dubLang.isNativeLang) {
                // ── Dubbed: build query variants ──────────────────
                val isSeries = type == MovieType.SERIES
                val queries  = DubQueryBuilder.buildQueries(title, dubLang, isSeries, season, episode)
                val q1 = queries.getOrElse(0) { title }
                val q2 = queries.getOrElse(1) { q1 }

                // TorrentCSV — DHT JSON API, best for dubbed coverage
                // Run two query variants in parallel (keyword combos)
                jobs.add(async {
                    withTimeoutOrNull(12_000) { TorrentProviders.fetchTorrentCSV(q1) } ?: emptyList()
                })
                jobs.add(async {
                    if (q2 != q1)
                        withTimeoutOrNull(12_000) { TorrentProviders.fetchTorrentCSV(q2) } ?: emptyList()
                    else emptyList()
                })

                // SolidTorrents — REST JSON API, good for Dual Audio & dubbed movies
                jobs.add(async {
                    withTimeoutOrNull(12_000) { TorrentProviders.fetchSolidTorrents(q1) } ?: emptyList()
                })

                // ── Rust indexer (replaces broken Kotlin scrapers) ──────
                // FIX: TorrentProviders.fetch1337x() / fetchTorrentGalaxy() /
                // fetchKAT() used stale URLs and dead CSS selectors — none
                // of the three ever returned results (only YTS worked).
                // Root cause verified against Jackett's current indexer
                // definitions: 1337x moved to sort-search/{q}/seeders/desc/,
                // TorrentGalaxy moved to get-posts/keywords:{q}, and the KAT
                // mirrors used here were dead/blocking. The Rust indexer
                // (app/src/main/rust/src/indexer/) ports Jackett's verified
                // selectors 1:1 and additionally covers TorrentDownload.
                // One JNI call replaces the three broken jobs below.
                jobs.add(async {
                    withTimeoutOrNull(25_000) {
                        IndexerNative.searchDubbed(q1, imdbId).map { it.toStreamLink() }
                    } ?: emptyList()
                })
                if (q2 != q1) {
                    jobs.add(async {
                        withTimeoutOrNull(25_000) {
                            IndexerNative.searchDubbed(q2, imdbId).map { it.toStreamLink() }
                        } ?: emptyList()
                    })
                }

                // TPB REMOVED from here — now handled inside
                // IndexerNative.searchDubbed() via Rust's tpb.rs
                // (Jackett-verified apibay.org JSON API, same endpoint
                // this Kotlin version used, but with proper IMDB-field
                // filtering and CJK/apostrophe query cleanup that
                // TorrentProviders.fetchTPB() didn't do). No separate
                // Kotlin call needed — it's already part of the single
                // IndexerNative.searchDubbed(q1, imdbId) call above.

                // BitSearch — general fallback
                jobs.add(async {
                    withTimeoutOrNull(12_000) { TorrentProviders.fetchBitSearch(q1) } ?: emptyList()
                })

                // EZTV title search for dubbed series
                if (isSeries) {
                    jobs.add(async {
                        withTimeoutOrNull(15_000) {
                            TorrentProviders.fetchSeriesDubbed(title, season, episode, dubLang)
                        } ?: emptyList()
                    })
                }

                // NYAA + AnimeTosho for Japanese / Dual Audio
                // Rust indexer's Nyaa module added alongside — its own
                // category split (English-translated vs Non-English) is a
                // stronger dub signal than the Kotlin fetchAnime()'s title
                // parsing alone, so both run together and results merge.
                if (dubLang is DubLanguage.Japanese || dubLang is DubLanguage.DualAudio) {
                    jobs.add(async {
                        withTimeoutOrNull(12_000) { TorrentProviders.fetchAnime(title, episode) } ?: emptyList()
                    })
                    jobs.add(async {
                        withTimeoutOrNull(12_000) { TorrentProviders.fetchAnimeTosho(title, episode) } ?: emptyList()
                    })
                    jobs.add(async {
                        withTimeoutOrNull(15_000) {
                            IndexerNative.searchAnimeEnglish(title).map { it.toStreamLink() }
                        } ?: emptyList()
                    })
                }

                // ── K-drama / C-drama / Turkish drama ────────────────
                // These three don't fit the South-Asian "dubbed" model —
                // most releases are original-voice-with-subs or explicitly
                // English-dubbed, found via IndexerNative.searchDrama()
                // (TorrentQQ/Torrentsome for Korean + general sites for
                // Chinese/Turkish, filtered by title tags — see
                // indexer/sites/kdrama.rs and indexer/types.rs).
                if (dubLang is DubLanguage.Korean ||
                    dubLang is DubLanguage.Chinese ||
                    dubLang is DubLanguage.Turkish) {
                    jobs.add(async {
                        withTimeoutOrNull(20_000) {
                            IndexerNative.searchDrama(q1).map { it.toStreamLink() }
                        } ?: emptyList()
                    })
                    if (q2 != q1) {
                        jobs.add(async {
                            withTimeoutOrNull(20_000) {
                                IndexerNative.searchDrama(q2).map { it.toStreamLink() }
                            } ?: emptyList()
                        })
                    }
                }

            } else {
                // ── English (original language) path — unchanged ──

                if (isAnime) {
                    jobs.add(async {
                        try { TorrentProviders.fetchAnime(title, episode) }
                        catch (e: Exception) { emptyList() }
                    })
                    jobs.add(async {
                        try { TorrentProviders.fetchAnimeTosho(title, episode) }
                        catch (e: Exception) { emptyList() }
                    })
                    // Rust Nyaa source — English-translated category,
                    // stronger dub/sub signal than title-only parsing.
                    jobs.add(async {
                        try { IndexerNative.searchAnimeEnglish(title).map { it.toStreamLink() } }
                        catch (e: Exception) { emptyList() }
                    })
                }

                if (type == MovieType.SERIES && imdbId != null) {
                    jobs.add(async {
                        try { TorrentProviders.fetchSeries(imdbId, season, episode) }
                        catch (e: Exception) { emptyList() }
                    })
                }

                if (type == MovieType.MOVIE) {
                    jobs.add(async { fetchYtsWithMirrors(imdbId, title) })
                }

                val englishQuery = when {
                    type == MovieType.SERIES ->
                        "$title S${String.format("%02d", season)}E${String.format("%02d", episode)}"
                    else -> title
                }

                // FIX: same broken fetch1337x() as the dubbed path above —
                // stale URL/selectors, never returned results. Uses the
                // Rust indexer's plain search (no dub-tag filtering) here.
                jobs.add(async {
                    try {
                        IndexerNative.searchAll(englishQuery).map { it.toStreamLink() }
                    } catch (e: Exception) { emptyList() }
                })
                jobs.add(async {
                    try { TorrentProviders.fetchBitSearch(englishQuery) }
                    catch (e: Exception) { emptyList() }
                })
            }

            jobs.awaitAll().forEach { allLinks.addAll(it) }
        }

        return@withContext allLinks
            .distinctBy { it.magnet }
            .map { link ->
                link.copy(magnet = appendTrackersToMagnet(link.magnet))
            }
            .sortedByDescending { it.seeds }
    }

    private fun appendTrackersToMagnet(magnet: String): String {
        if (!magnet.startsWith("magnet:?")) return magnet
        val trackerParams = TRACKERS.joinToString("") { "&tr=${URLEncoder.encode(it, "UTF-8")}" }
        return magnet + trackerParams
    }

    private suspend fun fetchYtsWithMirrors(imdbId: String?, title: String): List<StreamLink> {
        for (url in YTS_MIRRORS) {
            try {
                val links = fetchYtsInternal(url, imdbId, title)
                if (links.isNotEmpty()) return links
            } catch (e: Exception) {
                continue
            }
        }
        return emptyList()
    }

    private suspend fun fetchYtsInternal(url: String, imdbId: String?, title: String): List<StreamLink> {
        var movies: List<YtsMovie>? = null

        if (!imdbId.isNullOrEmpty() && imdbId != "null") {
            val response = api.listMovies(url, imdbId)
            movies = response.data?.movies
        }

        if (movies.isNullOrEmpty()) {
            val cleanTitle = title.replace(Regex("[^a-zA-Z0-9 ]"), "")
            val response   = api.listMovies(url, cleanTitle)
            movies         = response.data?.movies
        }

        return movies?.flatMap { movie ->
            movie.torrents?.map { torrent ->
                StreamLink(
                    title   = movie.title,
                    magnet  = constructMagnet(torrent.hash, movie.title),
                    quality = torrent.quality,
                    seeds   = torrent.seeds,
                    peers   = torrent.peers,
                    size    = torrent.size,
                    source  = "YTS"
                )
            } ?: emptyList()
        } ?: emptyList()
    }

    private fun constructMagnet(hash: String, title: String): String {
        val encodedTitle  = URLEncoder.encode(title, "UTF-8")
        val trackerString = TRACKERS.joinToString("") { "&tr=${URLEncoder.encode(it, "UTF-8")}" }
        return "magnet:?xt=urn:btih:$hash&dn=$encodedTitle$trackerString"
    }
}
