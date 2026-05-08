package com.aeoncorex.streamx.streaming.providers

import android.util.Log
import com.aeoncorex.streamx.streaming.HttpClient
import com.aeoncorex.streamx.streaming.ModflixConfig
import com.aeoncorex.streamx.streaming.ProviderRequest
import com.aeoncorex.streamx.streaming.StreamResult
import com.aeoncorex.streamx.streaming.StreamType
import com.aeoncorex.streamx.streaming.SubtitleTrack
import com.aeoncorex.streamx.streaming.extractors.HubCloudExtractor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.jsoup.Jsoup

// ═════════════════════════════════════════════════════════════════════════════
//  MoreProviders.kt — All remaining providers
//  Ported faithfully from vega-providers dist/*.js
// ═════════════════════════════════════════════════════════════════════════════

// ─────────────────────────────────────────────────────────────────────────────
//  KatMoviesProvider — Hindi/English HubCloud, katmoviehd.pictures
//  Ported from: dist/katmovies/stream.js + posts.js
// ─────────────────────────────────────────────────────────────────────────────
object KatMoviesProvider {
    private const val TAG = "KatMoviesProvider"
    private val HEADERS = mapOf(
        "Cookie"     to "xla=s4t",
        "User-Agent" to HttpClient.DESKTOP_UA,
        "Referer"    to "https://google.com",
    )

    suspend fun fetch(req: ProviderRequest): List<StreamResult> = withContext(Dispatchers.IO) {
        try {
            val base  = ModflixConfig.get("kat")
            val query = cleanTitle(req.title) + if (req.isSeries) " Season ${req.season}" else ""
            val searchUrl = "$base/page/1/?s=${query.replace(" ", "+")}"
            val html      = HttpClient.getHtml(searchUrl, HEADERS) ?: return@withContext emptyList()
            val doc       = Jsoup.parse(html, base)

            val titleL  = req.title.lowercase()
            val postUrl = doc.select("article a[href], .recent-movies a")
                .firstOrNull { el ->
                    val t = (el.attr("title") + el.text()).lowercase()
                    t.contains(titleL.take(6))
                }?.attr("href")
                ?: doc.selectFirst("article a[href]")?.attr("href")
                ?: return@withContext emptyList()

            val postHtml = HttpClient.getHtml(postUrl, HEADERS) ?: return@withContext emptyList()
            val postDoc  = Jsoup.parse(postHtml, postUrl)
            val links    = postDoc.select("a[href*=hubcloud], a[href*=hubdrive], a[href*=/drive/]")
                .map { it.attr("href") }.distinct().take(3)

            links.flatMap { HubCloudExtractor.extract(it, "KatMovies") }
        } catch (e: Exception) { Log.w(TAG, e.message); emptyList() }
    }
    private fun cleanTitle(t: String) = t.replace(Regex("""[:"'!?.,]"""), " ").replace(Regex("""\s+"""), " ").trim()
}

// ─────────────────────────────────────────────────────────────────────────────
//  FlixHQProvider — English via consumet flixhq API
//  Ported from: dist/flixhq/stream.js + posts.js
// ─────────────────────────────────────────────────────────────────────────────
object FlixHQProvider {
    private const val TAG = "FlixHQProvider"

    suspend fun fetch(req: ProviderRequest): List<StreamResult> = withContext(Dispatchers.IO) {
        try {
            val consumet = ModflixConfig.get("consumet")
            val query    = req.title.replace(" ", "+")
            val type     = if (req.isSeries) "show" else "movie"
            val searchUrl = "$consumet/movies/flixhq/${query}?type=$type"
            val searchJson = HttpClient.getJson(searchUrl) ?: return@withContext emptyList()
            val results    = JSONObject(searchJson).optJSONArray("results") ?: return@withContext emptyList()
            val titleL     = req.title.lowercase()

            // Find best match
            var episodeId: String? = null
            var mediaId:   String? = null
            for (i in 0 until results.length()) {
                val item  = results.getJSONObject(i)
                val title = item.optString("title", "").lowercase()
                if (!title.contains(titleL.take(5))) continue

                mediaId = item.optString("id")

                if (!req.isSeries) {
                    // Movie: get episode from info
                    val infoJson = HttpClient.getJson("$consumet/movies/flixhq/info?id=$mediaId") ?: continue
                    val epArr    = JSONObject(infoJson).optJSONArray("episodes")
                    episodeId    = epArr?.optJSONObject(0)?.optString("id")
                } else {
                    // Series: find season+episode
                    val infoJson = HttpClient.getJson("$consumet/movies/flixhq/info?id=$mediaId") ?: continue
                    val epArr    = JSONObject(infoJson).optJSONArray("episodes")
                    if (epArr != null) {
                        for (j in 0 until epArr.length()) {
                            val ep = epArr.getJSONObject(j)
                            if (ep.optInt("season") == req.season && ep.optInt("number") == req.episode) {
                                episodeId = ep.optString("id"); break
                            }
                        }
                    }
                }
                if (episodeId != null) break
            }
            if (episodeId == null || mediaId == null) return@withContext emptyList()

            // Get servers
            val serversJson = HttpClient.getJson("$consumet/movies/flixhq/servers?episodeId=$episodeId&mediaId=$mediaId")
                ?: return@withContext emptyList()
            val servers     = JSONObject(serversJson).optJSONArray("servers") ?: return@withContext emptyList()

            buildList {
                for (i in 0 until servers.length()) {
                    val server    = servers.getJSONObject(i).optString("name")
                    val watchUrl  = "$consumet/movies/flixhq/watch?server=$server&episodeId=$episodeId&mediaId=$mediaId"
                    val watchJson = HttpClient.getJson(watchUrl) ?: continue
                    val watchData = JSONObject(watchJson)
                    val subs      = mutableListOf<SubtitleTrack>()
                    watchData.optJSONArray("subtitles")?.let { arr ->
                        for (j in 0 until arr.length()) {
                            val sub = arr.getJSONObject(j)
                            subs.add(SubtitleTrack(
                                url      = sub.optString("url"),
                                language = sub.optString("lang", "en").take(2),
                                title    = sub.optString("lang", "Unknown"),
                                mimeType = "text/vtt"
                            ))
                        }
                    }
                    watchData.optJSONArray("sources")?.let { arr ->
                        for (j in 0 until arr.length()) {
                            val src  = arr.getJSONObject(j)
                            val url  = src.optString("url").takeIf { it.isNotEmpty() } ?: continue
                            val qual = src.optString("quality", "auto")
                            add(StreamResult(
                                url       = url, quality = qual,
                                type      = if (src.optBoolean("isM3U8")) StreamType.HLS else StreamType.MP4,
                                source    = "FlixHQ ($server)",
                                label     = "$qual — FlixHQ [$server]",
                                subtitles = subs
                            ))
                        }
                    }
                }
            }
        } catch (e: Exception) { Log.w(TAG, e.message); emptyList() }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
//  TopMoviesProvider — Hindi HubCloud, moviesleech.link
//  Ported from: dist/topmovies/stream.js + posts.js
// ─────────────────────────────────────────────────────────────────────────────
object TopMoviesProvider {
    private const val TAG = "TopMoviesProvider"
    private val HEADERS = mapOf(
        "Cookie"     to "popads_user_id=6ba8fe60a481387a3249f05aa058822d",
        "User-Agent" to HttpClient.DESKTOP_UA,
    )

    suspend fun fetch(req: ProviderRequest): List<StreamResult> = withContext(Dispatchers.IO) {
        try {
            val base      = ModflixConfig.get("Topmovies")
            val query     = "${req.title} ${if (req.isSeries) "Season ${req.season}" else ""}".trim()
            val searchUrl = "$base/search/${query.replace(" ", "+")}/page/1/"
            val html      = HttpClient.getHtml(searchUrl, HEADERS) ?: return@withContext emptyList()
            val doc       = Jsoup.parse(html, base)
            val postUrl   = doc.selectFirst(".items.full article a[href], .result-item a[href]")
                ?.attr("href") ?: return@withContext emptyList()

            val postHtml = HttpClient.getHtml(postUrl, HEADERS) ?: return@withContext emptyList()
            val postDoc  = Jsoup.parse(postHtml, postUrl)

            // If series, look for episode link
            val targetUrl = if (req.isSeries) {
                val epLinks = postDoc.select("h3 a, h4 a, a.maxbutton")
                epLinks.firstOrNull { it.text().contains("Episode ${req.episode}", true) }
                    ?.attr("href") ?: epLinks.getOrNull(req.episode - 1)?.attr("href") ?: postUrl
            } else postUrl

            // Follow meta-refresh if needed
            val finalHtml = if (targetUrl != postUrl) {
                val h = HttpClient.getHtml(targetUrl, HEADERS) ?: return@withContext emptyList()
                val metaUrl = Regex("""content="0;url=(.*?)"""", RegexOption.IGNORE_CASE)
                    .find(h)?.groupValues?.get(1)
                if (metaUrl != null) HttpClient.getHtml(metaUrl, HEADERS) ?: h else h
            } else postHtml

            val finalDoc = Jsoup.parse(finalHtml, targetUrl)
            finalDoc.select("a[href*=hubcloud], a[href*=hubdrive], a[href*=/drive/]")
                .map { it.attr("href") }.distinct().take(3)
                .flatMap { HubCloudExtractor.extract(it, "TopMovies") }
        } catch (e: Exception) { Log.w(TAG, e.message); emptyList() }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
//  UhdMoviesProvider — 4K content via GDFLIX, uhdmovies.pink
//  Ported from: dist/uhd/stream.js + posts.js
// ─────────────────────────────────────────────────────────────────────────────
object UhdMoviesProvider {
    private const val TAG = "UhdMoviesProvider"
    private val HEADERS   = mapOf("User-Agent" to HttpClient.DESKTOP_UA, "Cookie" to "xla=s4t")

    suspend fun fetch(req: ProviderRequest): List<StreamResult> = withContext(Dispatchers.IO) {
        try {
            val base      = ModflixConfig.get("UhdMovies")
            val query     = req.title.replace(" ", "+")
            val searchUrl = "$base/?s=$query"
            val html      = HttpClient.getHtml(searchUrl, HEADERS) ?: return@withContext emptyList()
            val doc       = Jsoup.parse(html, base)
            val titleL    = req.title.lowercase()

            val postUrl = doc.select("article a[href]")
                .firstOrNull { it.text().lowercase().contains(titleL.take(5)) }
                ?.attr("href")
                ?: doc.selectFirst("article a[href]")?.attr("href")
                ?: return@withContext emptyList()

            val postHtml = HttpClient.getHtml(postUrl, HEADERS) ?: return@withContext emptyList()
            val postDoc  = Jsoup.parse(postHtml, postUrl)

            // UHD uses GDFLIX links
            val gdLinks = postDoc.select("a[href*=gdflix], a[href*=gd-]")
                .map { it.attr("href") }.filter { it.startsWith("http") }.take(2)

            if (gdLinks.isNotEmpty()) {
                return@withContext gdLinks.flatMap {
                    com.aeoncorex.streamx.streaming.extractors.GdflixExtractor.extract(it, "UhdMovies 4K")
                }
            }

            // Fallback HubCloud
            postDoc.select("a[href*=hubcloud], a[href*=hubdrive]")
                .map { it.attr("href") }.distinct().take(2)
                .flatMap { HubCloudExtractor.extract(it, "UhdMovies") }
        } catch (e: Exception) { Log.w(TAG, e.message); emptyList() }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
//  LuxMoviesProvider — HubCloud + filepress, rogmovies.blog
//  Same extractor as VegaMovies. Ported from: dist/luxMovies/stream.js
// ─────────────────────────────────────────────────────────────────────────────
object LuxMoviesProvider {
    private const val TAG = "LuxMoviesProvider"
    private val HEADERS   = mapOf("Cookie" to "xla=s4t", "User-Agent" to HttpClient.DESKTOP_UA)

    suspend fun fetch(req: ProviderRequest): List<StreamResult> = withContext(Dispatchers.IO) {
        try {
            val base      = ModflixConfig.get("lux")
            val query     = req.title.replace(" ", "+")
            val searchUrl = "$base/search.php?q=$query&page=1"
            val json      = HttpClient.getJson(searchUrl, HEADERS)
            val postUrl   = if (json != null) {
                val hits   = JSONObject(json).optJSONArray("hits") ?: return@withContext emptyList()
                val titleL = req.title.lowercase()
                var url: String? = null
                for (i in 0 until hits.length()) {
                    val doc = hits.getJSONObject(i).optJSONObject("document") ?: continue
                    if (doc.optString("post_title", "").lowercase().contains(titleL.take(5))) {
                        url = doc.optString("permalink")
                        break
                    }
                }
                url ?: hits.optJSONObject(0)?.optJSONObject("document")?.optString("permalink")
            } else {
                val html = HttpClient.getHtml("$base/?s=$query", HEADERS) ?: return@withContext emptyList()
                Jsoup.parse(html, base).selectFirst("article a[href]")?.attr("href")
            } ?: return@withContext emptyList()

            val postHtml = HttpClient.getHtml(postUrl, HEADERS) ?: return@withContext emptyList()
            val postDoc  = Jsoup.parse(postHtml, postUrl)
            postDoc.select("a[href*=hubcloud], a[href*=hubdrive], a[href*=/drive/]")
                .map { it.attr("href") }.distinct().take(3)
                .flatMap { HubCloudExtractor.extract(it, "LuxMovies") }
        } catch (e: Exception) { Log.w(TAG, e.message); emptyList() }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
//  MultiMoviesProvider — WordPress AJAX embed, multimovies.autos
//  Ported from: dist/multi/stream.js + posts.js
// ─────────────────────────────────────────────────────────────────────────────
object MultiMoviesProvider {
    private const val TAG = "MultiMoviesProvider"
    private val HEADERS   = mapOf(
        "User-Agent" to HttpClient.DESKTOP_UA,
        "Referer"    to "https://multimovies.online/",
    )

    suspend fun fetch(req: ProviderRequest): List<StreamResult> = withContext(Dispatchers.IO) {
        try {
            val base      = ModflixConfig.get("multi")
            val query     = req.title.replace(" ", "+")
            val html      = HttpClient.getHtml("$base/?s=$query", HEADERS) ?: return@withContext emptyList()
            val doc       = Jsoup.parse(html, base)
            val titleL    = req.title.lowercase()

            val postUrl = doc.select(".items.full article a, .result-item a")
                .firstOrNull { (it.attr("alt") + it.text()).lowercase().contains(titleL.take(5)) }
                ?.attr("href")
                ?: doc.selectFirst(".items.full article a[href]")?.attr("href")
                ?: return@withContext emptyList()

            // For series, find episode
            val targetUrl = if (req.isSeries) {
                val postHtml = HttpClient.getHtml(postUrl, HEADERS) ?: return@withContext emptyList()
                val postDoc  = Jsoup.parse(postHtml, postUrl)
                // Episode pages are nested: Season → Episode
                val seasonLinks = postDoc.select("a[href*=season-${req.season}], a[href*=temporada-${req.season}]")
                val seasonUrl   = seasonLinks.firstOrNull()?.attr("href") ?: postUrl
                if (seasonUrl != postUrl) {
                    val sHtml  = HttpClient.getHtml(seasonUrl, HEADERS) ?: return@withContext emptyList()
                    val sDoc   = Jsoup.parse(sHtml, seasonUrl)
                    sDoc.select("a[href]").firstOrNull { it.text().contains("Episode ${req.episode}") || it.text().contains("Ep ${req.episode}") }
                        ?.attr("href") ?: seasonUrl
                } else postUrl
            } else postUrl

            val epHtml  = HttpClient.getHtml(targetUrl, HEADERS) ?: return@withContext emptyList()
            val epDoc   = Jsoup.parse(epHtml, targetUrl)
            val postId  = epDoc.selectFirst("#player-option-1")?.attr("data-post") ?: return@withContext emptyList()
            val nume    = epDoc.selectFirst("#player-option-1")?.attr("data-nume") ?: "1"
            val typeVal = epDoc.selectFirst("#player-option-1")?.attr("data-type") ?: "movie"
            val ajaxUrl = "${targetUrl.split("/").take(3).joinToString("/")}/wp-admin/admin-ajax.php"

            val formBody = "action=doo_player_ajax&post=$postId&nume=$nume&type=$typeVal"
            val ajaxResp = HttpClient.postJson(ajaxUrl, formBody, mapOf(
                "Content-Type" to "application/x-www-form-urlencoded",
                "Referer"      to targetUrl,
            ) + HEADERS) ?: return@withContext emptyList()

            val embedUrl = JSONObject(ajaxResp).optString("embed_url").takeIf { it.isNotEmpty() }
                ?: return@withContext emptyList()

            // embedUrl is an iframe src — try to extract HLS from it
            val embedHtml = HttpClient.getHtml(embedUrl, mapOf("Referer" to targetUrl)) ?: return@withContext emptyList()
            val hlsMatch  = Regex("""(?:file|source|src)\s*[:=]\s*["'`](https?://[^"'`\s]+\.m3u8[^"'`\s]*)""")
                .find(embedHtml)
            if (hlsMatch != null) {
                return@withContext listOf(StreamResult(
                    url    = hlsMatch.groupValues[1], type = StreamType.HLS,
                    source = "MultiMovies", label = "HD — MultiMovies [HLS]"
                ))
            }
            // Fallback: mp4
            val mp4Match = Regex("""(?:file|source|src)\s*[:=]\s*["'`](https?://[^"'`\s]+\.mp4[^"'`\s]*)""")
                .find(embedHtml)
            if (mp4Match != null) {
                return@withContext listOf(StreamResult(
                    url    = mp4Match.groupValues[1], type = StreamType.MP4,
                    source = "MultiMovies", label = "HD — MultiMovies [MP4]"
                ))
            }
            emptyList()
        } catch (e: Exception) { Log.w(TAG, e.message); emptyList() }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
//  AnimetsuProvider — Anime HLS via backend.animetsu.to API
//  Ported from: dist/animetsu/stream.js + posts.js
//  Note: episodeId format = "animeId:episodeNumber"
// ─────────────────────────────────────────────────────────────────────────────
object AnimetsuProvider {
    private const val TAG      = "AnimetsuProvider"
    private const val API_BASE = "https://backend.animetsu.to"
    private const val M3U8_PROXY = "https://m3u8.8man.workers.dev"
    private val SERVERS = listOf("pahe", "zoro")

    suspend fun fetch(req: ProviderRequest): List<StreamResult> = coroutineScope {
        try {
            // Search
            val searchJson = withContext(Dispatchers.IO) {
                HttpClient.getJson("$API_BASE/api/anime/search?q=${req.title.replace(" ", "+")}&page=1",
                    mapOf("Referer" to "https://animetsu.to/"))
            } ?: return@coroutineScope emptyList()

            val titleL = req.title.lowercase()
            var animeId: String? = null
            val searchData = JSONObject(searchJson).optJSONArray("data")
                ?: JSONObject(searchJson).optJSONArray("results")
            searchData?.let { arr ->
                for (i in 0 until arr.length()) {
                    val item  = arr.getJSONObject(i)
                    val title = (item.optString("title") + item.optString("englishTitle")).lowercase()
                    if (title.contains(titleL.take(5))) {
                        animeId = item.optString("id").takeIf { it.isNotEmpty() }
                            ?: item.optInt("id").toString()
                        break
                    }
                }
            }
            if (animeId == null) return@coroutineScope emptyList()

            val epNum = if (req.isSeries) req.episode else 1

            // Fetch sub + dub streams in parallel from all servers
            SERVERS.flatMap { server ->
                listOf("sub", "dub").map { subType ->
                    async(Dispatchers.IO) {
                        try {
                            val url  = "$API_BASE/api/anime/tiddies?server=$server&id=$animeId&num=$epNum&subType=$subType"
                            val json = HttpClient.getJson(url, mapOf("Referer" to "https://animetsu.to/")) ?: return@async emptyList<StreamResult>()
                            val data = JSONObject(json)
                            val sources = data.optJSONArray("sources") ?: return@async emptyList<StreamResult>()
                            buildList {
                                for (i in 0 until sources.length()) {
                                    val s    = sources.getJSONObject(i)
                                    val srcUrl = s.optString("url").takeIf { it.isNotEmpty() } ?: continue
                                    // Proxy through m3u8 worker
                                    val proxied = "$M3U8_PROXY?url=${java.net.URLEncoder.encode(srcUrl, "UTF-8")}"
                                    val qual    = s.optString("quality", "HD")
                                    val lang    = if (subType == "dub") "English" else "Japanese"
                                    add(StreamResult(
                                        url      = proxied, quality = qual, type = StreamType.HLS,
                                        source   = "Animetsu ($server-$subType)",
                                        language = lang,
                                        label    = "$qual — Animetsu [$server ${subType.uppercase()}]",
                                        headers  = mapOf("Referer" to "https://animetsu.to/")
                                    ))
                                }
                            }
                        } catch (e: Exception) { emptyList() }
                    }
                }
            }.awaitAll().flatten()
        } catch (e: Exception) { Log.w(TAG, e.message); emptyList() }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
//  VadaPavProvider — Direct file links, vadapav.mov
//  Very simple: URL is the direct stream link. Ported from: dist/vadapav/stream.js
// ─────────────────────────────────────────────────────────────────────────────
object VadaPavProvider {
    private const val TAG = "VadaPavProvider"

    suspend fun fetch(req: ProviderRequest): List<StreamResult> = withContext(Dispatchers.IO) {
        try {
            val base  = ModflixConfig.get("vadapav")
            val query = req.title.replace(" ", "+")
            val html  = HttpClient.getHtml("$base/s/$query") ?: return@withContext emptyList()
            val doc   = Jsoup.parse(html, base)
            val titleL = req.title.lowercase()

            // Find matching directory entry
            val entries = doc.select(".directory-entry:not(:contains(Parent Directory))")
            val match   = entries.firstOrNull { it.text().lowercase().contains(titleL.take(5)) }
                ?: entries.firstOrNull()
            val dirUrl  = match?.attr("href")?.let {
                if (it.startsWith("http")) it else "$base$it"
            } ?: return@withContext emptyList()

            // Browse directory for video files
            val dirHtml = HttpClient.getHtml(dirUrl) ?: return@withContext emptyList()
            val dirDoc  = Jsoup.parse(dirHtml, dirUrl)

            buildList {
                dirDoc.select("a[href]").forEach { el ->
                    val href = el.attr("href")
                    val url  = if (href.startsWith("http")) href else "$base$href"
                    val ext  = url.substringAfterLast(".").lowercase().substringBefore("?")
                    if (ext in listOf("mkv", "mp4", "avi", "m3u8")) {
                        val type = if (ext == "m3u8") StreamType.HLS else StreamType.MKV
                        val qual = detectQuality(url)
                        add(StreamResult(
                            url    = url, quality = qual, type = type,
                            source = "VadaPav", label = "$qual — VadaPav"
                        ))
                    }
                }
            }.take(4)
        } catch (e: Exception) { Log.w(TAG, e.message); emptyList() }
    }
    private fun detectQuality(url: String) = when {
        url.contains("2160") || url.contains("4k", true) -> "4K"
        url.contains("1080")                              -> "1080p"
        url.contains("720")                               -> "720p"
        url.contains("480")                               -> "480p"
        else                                              -> "HD"
    }
}

// ─────────────────────────────────────────────────────────────────────────────
//  FourKHdHubProvider — 4K HubCloud, 4khdhub.dad
//  Ported from: dist/4khdhub/stream.js + posts.js (same as HubCloud pattern)
// ─────────────────────────────────────────────────────────────────────────────
object FourKHdHubProvider {
    private const val TAG = "4KHdHubProvider"
    private val HEADERS   = mapOf("Cookie" to "xla=s4t", "User-Agent" to HttpClient.DESKTOP_UA)

    suspend fun fetch(req: ProviderRequest): List<StreamResult> = withContext(Dispatchers.IO) {
        try {
            val base  = ModflixConfig.get("4khdhub")
            val query = req.title.replace(" ", "+") + if (req.isSeries) "+Season+${req.season}" else ""
            val html  = HttpClient.getHtml("$base/?s=$query", HEADERS) ?: return@withContext emptyList()
            val doc   = Jsoup.parse(html, base)
            val titleL = req.title.lowercase()

            val postUrl = doc.select("article a[href]")
                .firstOrNull { it.text().lowercase().contains(titleL.take(5)) }
                ?.attr("href")
                ?: doc.selectFirst("article a[href]")?.attr("href")
                ?: return@withContext emptyList()

            val postHtml = HttpClient.getHtml(postUrl, HEADERS) ?: return@withContext emptyList()
            val postDoc  = Jsoup.parse(postHtml, postUrl)
            postDoc.select("a[href*=hubcloud], a[href*=/drive/]")
                .map { it.attr("href") }.distinct().take(3)
                .flatMap { HubCloudExtractor.extract(it, "4KHdHub") }
        } catch (e: Exception) { Log.w(TAG, e.message); emptyList() }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
//  Movies4uProvider — HubCloud + filepress, movies4u.vg
//  Ported from: dist/movies4u/stream.js + posts.js
// ─────────────────────────────────────────────────────────────────────────────
object Movies4uProvider {
    private const val TAG = "Movies4uProvider"
    private val HEADERS   = mapOf("Cookie" to "xla=s4t", "User-Agent" to HttpClient.DESKTOP_UA)

    suspend fun fetch(req: ProviderRequest): List<StreamResult> = withContext(Dispatchers.IO) {
        try {
            val base      = ModflixConfig.get("movies4u")
            val query     = req.title.replace(" ", "+")
            val html      = HttpClient.getHtml("$base/?s=$query", HEADERS) ?: return@withContext emptyList()
            val doc       = Jsoup.parse(html, base)
            val titleL    = req.title.lowercase()

            val postUrl = doc.select("article a[href]")
                .firstOrNull { it.text().lowercase().contains(titleL.take(5)) }
                ?.attr("href")
                ?: doc.selectFirst("article a[href]")?.attr("href")
                ?: return@withContext emptyList()

            val postHtml = HttpClient.getHtml(postUrl, HEADERS) ?: return@withContext emptyList()
            val postDoc  = Jsoup.parse(postHtml, postUrl)
            postDoc.select("a[href*=hubcloud], a[href*=fastdl], a[href*=filepress]")
                .map { it.attr("href") }.distinct().take(3)
                .flatMap { link ->
                    when {
                        link.contains("filepress") -> {
                            val id   = link.split("/").last()
                            val base2= link.split("/").dropLast(2).joinToString("/")
                            val b1   = """{"id":"$id","method":"indexDownlaod","captchaValue":null}"""
                            val r1   = HttpClient.postJson("$base2/api/file/downlaod/", b1, mapOf("Referer" to base2))
                            val j1   = r1?.let { runCatching { JSONObject(it) }.getOrNull() }
                            if (j1?.optBoolean("status") == true) {
                                val tok = j1.optString("data")
                                val b2  = """{"id":"$tok","method":"indexDownlaod","captchaValue":null}"""
                                val r2  = HttpClient.postJson("$base2/api/file/downlaod2/", b2, mapOf("Referer" to base2))
                                val url = r2?.let { JSONObject(it).optJSONArray("data")?.optString(0) }
                                if (!url.isNullOrEmpty()) listOf(StreamResult(url=url, type=StreamType.MKV, source="Movies4u (filepress)", label="HD — Movies4u [filepress]"))
                                else emptyList()
                            } else emptyList()
                        }
                        else -> HubCloudExtractor.extract(link, "Movies4u")
                    }
                }
        } catch (e: Exception) { Log.w(TAG, e.message); emptyList() }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
//  SkyMoviesHdProvider — HubCloud, skymovieshd.fast
//  Ported from: dist/skyMovieHD/stream.js + posts.js
// ─────────────────────────────────────────────────────────────────────────────
object SkyMoviesHdProvider {
    private const val TAG = "SkyMoviesHdProvider"
    private val HEADERS   = mapOf("Cookie" to "xla=s4t", "User-Agent" to HttpClient.DESKTOP_UA)

    suspend fun fetch(req: ProviderRequest): List<StreamResult> = withContext(Dispatchers.IO) {
        try {
            val base  = ModflixConfig.get("skymovieshd")
            val query = "${req.title} ${if (req.isSeries) "Season ${req.season}" else ""}".trim()
            val html  = HttpClient.getHtml("$base/?s=${query.replace(" ", "+")}", HEADERS) ?: return@withContext emptyList()
            val doc   = Jsoup.parse(html, base)
            val titleL = req.title.lowercase()

            val postUrl = doc.select("article a[href], .recent-movies a")
                .firstOrNull { it.text().lowercase().contains(titleL.take(5)) }
                ?.attr("href")
                ?: doc.selectFirst("article a[href]")?.attr("href")
                ?: return@withContext emptyList()

            val postHtml = HttpClient.getHtml(postUrl, HEADERS) ?: return@withContext emptyList()
            val postDoc  = Jsoup.parse(postHtml, postUrl)
            postDoc.select("a[href*=hubcloud], a[href*=hubdrive], a[href*=/drive/]")
                .map { it.attr("href") }.distinct().take(3)
                .flatMap { HubCloudExtractor.extract(it, "SkyMoviesHD") }
        } catch (e: Exception) { Log.w(TAG, e.message); emptyList() }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
//  World4uProvider — HubCloud, world4ufree.tw
//  Ported from: dist/world4u/stream.js + posts.js
// ─────────────────────────────────────────────────────────────────────────────
object World4uProvider {
    private const val TAG = "World4uProvider"
    private val HEADERS   = mapOf("User-Agent" to HttpClient.DESKTOP_UA)

    suspend fun fetch(req: ProviderRequest): List<StreamResult> = withContext(Dispatchers.IO) {
        try {
            val base  = ModflixConfig.get("w4u")
            val query = req.title.replace(" ", "+")
            val html  = HttpClient.getHtml("$base/?s=$query", HEADERS) ?: return@withContext emptyList()
            val doc   = Jsoup.parse(html, base)

            val postUrl = doc.selectFirst("article a[href]")?.attr("href") ?: return@withContext emptyList()
            val postHtml = HttpClient.getHtml(postUrl, HEADERS) ?: return@withContext emptyList()
            val postDoc  = Jsoup.parse(postHtml, postUrl)
            postDoc.select("a[href*=hubcloud], a[href*=mediafire], a[href*=/drive/]")
                .map { it.attr("href") }.distinct().take(3)
                .flatMap { HubCloudExtractor.extract(it, "World4u") }
        } catch (e: Exception) { Log.w(TAG, e.message); emptyList() }
    }
}
