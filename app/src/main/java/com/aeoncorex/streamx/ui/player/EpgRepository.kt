package com.aeoncorex.streamx.ui.player

// ════════════════════════════════════════════════════════════════════════════
//  EpgRepository.kt — Real EPG from iptv-org + epg.best
//  ──────────────────────────────────────────────────────
//  Priority chain:
//    1. epg.best JSON API      — fast, covers 10k+ channels
//    2. iptv-org XMLTV guide   — large community database (fetched by region)
//    3. Realistic fallback     — generated schedule (no fake "Coming Soon")
//
//  EPG data is cached per-channel for 10 minutes.
//  XMLTV bulk guide is cached for 30 minutes (it's a large file).
//
//  The XMLTV parser reads the actual iptv-org guide files via:
//    https://iptv-org.github.io/epg/guides/<country>.epg.xml.gz
//  ─── NO API KEY NEEDED ─────────────────────────────────────────────────
// ════════════════════════════════════════════════════════════════════════════

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.*
import java.util.zip.GZIPInputStream

// ─── Data classes ────────────────────────────────────────────────────────────

data class EPGProgram(
    val title:       String,
    val startTime:   Long,        // epoch ms
    val endTime:     Long,        // epoch ms
    val description: String = "",
    val category:    String = "",
    val icon:        String = ""
)

data class EPGState(
    val current:  EPGProgram?,
    val next:     EPGProgram?,
    val progress: Float,          // 0f–1f through current program
    val source:   String = ""
)

// ─── Repository ──────────────────────────────────────────────────────────────

object EpgRepository {

    private const val TAG = "EpgRepository"

    // ── epg.best — free, no key, JSON ────────────────────────────────────
    private const val EPG_BEST_API = "https://epg.best/api"

    // ── iptv-org guide index ──────────────────────────────────────────────
    // This lists all available guide files by channel ID
    private const val IPTVORG_INDEX = "https://iptv-org.github.io/epg/index.json"

    // ── iptv-org bulk XMLTV guides by country (compressed) ───────────────
    // Pattern: https://iptv-org.github.io/epg/guides/{country}.epg.xml.gz
    // We try the likely country guides for the channel.
    private val XMLTV_GUIDE_COUNTRIES = listOf("in", "bd", "us", "gb", "pk")

    // ── Per-channel cache (10 min) ────────────────────────────────────────
    private val epgCache = mutableMapOf<String, Pair<EPGState, Long>>()
    private const val EPG_CACHE_TTL = 10 * 60_000L

    // ── XMLTV bulk cache (30 min, keyed by guide URL) ─────────────────────
    // maps guideUrl → (Map<channelId, List<EPGProgram>>, fetchTime)
    private val xmltvCache = mutableMapOf<String, Pair<Map<String, List<EPGProgram>>, Long>>()
    private const val XMLTV_CACHE_TTL = 30 * 60_000L

    // ── iptv-org channel→guide mapping cache (60 min) ─────────────────────
    private var iptvOrgIndex: Map<String, String>? = null   // channelId → guideUrl
    private var iptvOrgIndexTime = 0L
    private const val INDEX_CACHE_TTL = 60 * 60_000L

    // Date formats
    private val xmltvSdf = SimpleDateFormat("yyyyMMddHHmmss Z", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }
    private val displaySdf = SimpleDateFormat("HH:mm", Locale.getDefault())

    // ══════════════════════════════════════════════════════════════════════
    //  PUBLIC API
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Main entry point.
     * @param streamUrl  .m3u8 URL — used to derive channel ID
     * @param channelName  Friendly name from playlist
     */
    suspend fun getEPG(
        streamUrl:   String,
        channelName: String = ""
    ): EPGState = withContext(Dispatchers.IO) {

        val channelId = guessChannelId(streamUrl, channelName)

        // Return cache if fresh
        epgCache[channelId]?.let { (state, time) ->
            if (System.currentTimeMillis() - time < EPG_CACHE_TTL) {
                Log.d(TAG, "Cache hit: $channelId")
                return@withContext state
            }
        }

        val state =
            tryEpgBest(channelName)
            ?: tryIptvOrgXmltvByIndex(channelId, channelName)
            ?: tryXmltvByCountry(channelId, channelName)
            ?: generateFallbackEpg(channelName)

        epgCache[channelId] = Pair(state, System.currentTimeMillis())
        state
    }

    // ══════════════════════════════════════════════════════════════════════
    //  SOURCE 1: epg.best JSON API
    // ══════════════════════════════════════════════════════════════════════

    private fun tryEpgBest(channelName: String): EPGState? {
        if (channelName.isBlank()) return null
        return try {
            // Step A — search channel by name
            val query = channelName.take(30).replace(" ", "+")
            val searchUrl = "$EPG_BEST_API/channels?search=$query&limit=5"
            val searchJson = fetchJson(searchUrl) ?: return null

            val channels = searchJson.optJSONArray("data") ?: return null
            if (channels.length() == 0) return null

            // Pick best match (first result)
            val epgId = channels.getJSONObject(0).optString("id").ifEmpty { return null }

            // Step B — fetch today's schedule for that channel
            val today = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
            val scheduleUrl = "$EPG_BEST_API/epg?channel=$epgId&date=$today"
            val scheduleJson = fetchJson(scheduleUrl) ?: return null

            val programs = scheduleJson.optJSONArray("data") ?: return null
            if (programs.length() == 0) return null

            parseEpgPrograms(programs, source = "epg.best")
                .also { Log.d(TAG, "epg.best success for '$channelName'") }

        } catch (e: Exception) {
            Log.w(TAG, "epg.best failed: ${e.message}")
            null
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    //  SOURCE 2: iptv-org index → specific XMLTV guide
    //  https://iptv-org.github.io/epg/index.json maps channel IDs to guides
    // ══════════════════════════════════════════════════════════════════════

    private fun tryIptvOrgXmltvByIndex(channelId: String, channelName: String): EPGState? {
        return try {
            val index = loadIptvOrgIndex() ?: return null
            val guideUrl = index[channelId] ?: return null
            Log.d(TAG, "iptv-org index: $channelId → $guideUrl")
            fetchAndParseXmltvGuide(guideUrl, channelId, "iptv-org/index")
        } catch (e: Exception) {
            Log.w(TAG, "iptv-org index lookup failed: ${e.message}")
            null
        }
    }

    private fun loadIptvOrgIndex(): Map<String, String>? {
        val now = System.currentTimeMillis()
        iptvOrgIndex?.let {
            if (now - iptvOrgIndexTime < INDEX_CACHE_TTL) return it
        }
        return try {
            val json = fetchRaw(IPTVORG_INDEX, timeoutMs = 20_000) ?: return null
            val arr  = JSONArray(json)
            val map  = mutableMapOf<String, String>()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val id    = obj.optString("channel", "")
                val guide = obj.optString("url", "")
                if (id.isNotEmpty() && guide.isNotEmpty()) {
                    map[id] = guide
                }
            }
            iptvOrgIndex     = map
            iptvOrgIndexTime = now
            Log.d(TAG, "iptv-org index loaded: ${map.size} entries")
            map
        } catch (e: Exception) {
            Log.w(TAG, "iptv-org index load failed: ${e.message}")
            null
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    //  SOURCE 3: Try common country XMLTV guides directly
    //  Covers most South Asian + Western channels
    // ══════════════════════════════════════════════════════════════════════

    private fun tryXmltvByCountry(channelId: String, channelName: String): EPGState? {
        // Guess likely country from channel name
        val likely = guessCountry(channelName)
        val order  = listOf(likely) + XMLTV_GUIDE_COUNTRIES.filter { it != likely }

        for (country in order) {
            val guideUrl = "https://iptv-org.github.io/epg/guides/$country.epg.xml.gz"
            val state    = fetchAndParseXmltvGuide(guideUrl, channelId, "iptv-org/$country")
            if (state != null) return state
        }
        return null
    }

    // ══════════════════════════════════════════════════════════════════════
    //  XMLTV GUIDE FETCHER + PARSER (with gzip support + caching)
    // ══════════════════════════════════════════════════════════════════════

    private fun fetchAndParseXmltvGuide(
        guideUrl:  String,
        channelId: String,
        source:    String
    ): EPGState? {
        return try {
            val now = System.currentTimeMillis()

            // Try cache first
            xmltvCache[guideUrl]?.let { (map, time) ->
                if (now - time < XMLTV_CACHE_TTL) {
                    Log.d(TAG, "XMLTV cache hit: $guideUrl → looking up $channelId")
                    return buildEPGState(map[channelId] ?: return null, source)
                }
            }

            // Fetch XMLTV (possibly gzip)
            Log.d(TAG, "XMLTV fetch: $guideUrl")
            val content = fetchXmltvContent(guideUrl) ?: return null
            val allPrograms = parseXmltvContent(content)

            // Cache the full guide
            xmltvCache[guideUrl] = Pair(allPrograms, now)
            Log.d(TAG, "XMLTV parsed: ${allPrograms.size} channels")

            val programs = allPrograms[channelId] ?: run {
                // Also try normalised ID lookup
                val normId = channelId.lowercase().replace(Regex("[^a-z0-9]"), "")
                allPrograms.entries
                    .firstOrNull { it.key.lowercase().replace(Regex("[^a-z0-9]"), "").contains(normId) }
                    ?.value
            } ?: return null

            buildEPGState(programs, source)

        } catch (e: Exception) {
            Log.w(TAG, "XMLTV fetch/parse failed ($guideUrl): ${e.message}")
            null
        }
    }

    private fun fetchXmltvContent(url: String): String? {
        return try {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod  = "GET"
                connectTimeout = 15_000
                readTimeout    = 30_000
                setRequestProperty("User-Agent", "StreamX-Ultra/2.0")
                setRequestProperty("Accept-Encoding", "gzip")
            }
            if (conn.responseCode != 200) return null

            val encoding = conn.contentEncoding ?: ""
            val stream   = conn.inputStream

            val reader = if (url.endsWith(".gz") || encoding.contains("gzip", true)) {
                BufferedReader(InputStreamReader(GZIPInputStream(stream), "UTF-8"))
            } else {
                BufferedReader(InputStreamReader(stream, "UTF-8"))
            }
            reader.use { it.readText() }
        } catch (e: Exception) {
            Log.w(TAG, "fetchXmltvContent failed: ${e.message}")
            null
        }
    }

    /**
     * Parses XMLTV XML string into Map<channelId, List<EPGProgram>>
     * Uses regex-based parsing (no external XML library needed).
     */
    private fun parseXmltvContent(xml: String): Map<String, List<EPGProgram>> {
        val result = mutableMapOf<String, MutableList<EPGProgram>>()

        // Regex to capture <programme> blocks
        val progPattern = Regex(
            """<programme\s+start="([^"]+)"\s+stop="([^"]+)"\s+channel="([^"]+)"[^>]*>(.*?)</programme>""",
            setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
        )
        val titlePattern = Regex("""<title[^>]*>([^<]+)</title>""", RegexOption.IGNORE_CASE)
        val descPattern  = Regex("""<desc[^>]*>([^<]+)</desc>""",  RegexOption.IGNORE_CASE)
        val catPattern   = Regex("""<category[^>]*>([^<]+)</category>""", RegexOption.IGNORE_CASE)

        for (match in progPattern.findAll(xml)) {
            try {
                val startRaw  = match.groupValues[1]
                val stopRaw   = match.groupValues[2]
                val channelId = match.groupValues[3]
                val body      = match.groupValues[4]

                val start = parseXmltvTime(startRaw) ?: continue
                val stop  = parseXmltvTime(stopRaw)  ?: continue
                val title = titlePattern.find(body)?.groupValues?.get(1)?.trim() ?: "Unknown"
                val desc  = descPattern.find(body)?.groupValues?.get(1)?.trim()  ?: ""
                val cat   = catPattern.find(body)?.groupValues?.get(1)?.trim()   ?: ""

                result.getOrPut(channelId) { mutableListOf() }
                    .add(EPGProgram(title, start, stop, desc, cat))
            } catch (_: Exception) { /* skip bad entry */ }
        }

        return result
    }

    private fun buildEPGState(programs: List<EPGProgram>, source: String): EPGState? {
        val now      = System.currentTimeMillis()
        var current:  EPGProgram? = null
        var next:     EPGProgram? = null

        val sorted = programs.sortedBy { it.startTime }
        for (prog in sorted) {
            when {
                now in prog.startTime..prog.endTime -> current = prog
                prog.startTime > now && next == null -> next = prog
            }
        }
        if (current == null) return null

        val progress = ((now - current.startTime).toFloat() /
            (current.endTime - current.startTime)).coerceIn(0f, 1f)

        return EPGState(current, next, progress, source)
    }

    // ══════════════════════════════════════════════════════════════════════
    //  SOURCE 4: Parse epg.best JSON response
    // ══════════════════════════════════════════════════════════════════════

    private fun parseEpgPrograms(programs: JSONArray, source: String): EPGState? {
        val now      = System.currentTimeMillis()
        var current:  EPGProgram? = null
        var next:     EPGProgram? = null

        for (i in 0 until programs.length()) {
            val p     = programs.getJSONObject(i)
            // epg.best uses both XMLTV format and ISO8601
            val start = parseXmltvTime(p.optString("start", ""))
                ?: parseIsoTime(p.optString("start_timestamp", ""))
                ?: continue
            val stop  = parseXmltvTime(p.optString("stop", ""))
                ?: parseIsoTime(p.optString("stop_timestamp", ""))
                ?: continue
            val title = p.optString("title", "Unknown Program")
            val desc  = p.optString("desc",  "")
            val cat   = p.optString("category", "")
            val icon  = p.optString("icon",  "")

            val prog = EPGProgram(title, start, stop, desc, cat, icon)
            when {
                now in start..stop       -> current = prog
                start > now && next == null -> next = prog
            }
        }

        if (current == null) return null

        val progress = ((now - current.startTime).toFloat() /
            (current.endTime - current.startTime)).coerceIn(0f, 1f)

        return EPGState(current, next, progress, source)
    }

    // ══════════════════════════════════════════════════════════════════════
    //  SOURCE 5: Realistic Fallback (when all real sources fail)
    // ══════════════════════════════════════════════════════════════════════

    private fun generateFallbackEpg(channelName: String): EPGState {
        val now = System.currentTimeMillis()
        val cal = Calendar.getInstance()

        // Snap to nearest 30-minute block
        val minute = cal.get(Calendar.MINUTE)
        val blockStart = cal.apply {
            set(Calendar.MINUTE, if (minute < 30) 0 else 30)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        val blockEnd     = blockStart + 30 * 60_000L
        val nextBlockEnd = blockEnd   + 30 * 60_000L

        val schedule = getChannelSchedule(channelName)
        val hour     = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        val slot     = (hour * 2 + if (minute >= 30) 1 else 0) % schedule.size

        val current = EPGProgram(
            title       = schedule[slot],
            startTime   = blockStart,
            endTime     = blockEnd,
            category    = guessCategory(channelName),
            description = "Broadcast on ${channelName.ifEmpty { "this channel" }}"
        )
        val next = EPGProgram(
            title     = schedule[(slot + 1) % schedule.size],
            startTime = blockEnd,
            endTime   = nextBlockEnd,
            category  = guessCategory(channelName)
        )

        val progress = ((now - blockStart).toFloat() / (blockEnd - blockStart)).coerceIn(0f, 1f)
        return EPGState(current, next, progress, "Generated")
    }

    // ══════════════════════════════════════════════════════════════════════
    //  HELPERS
    // ══════════════════════════════════════════════════════════════════════

    private fun parseXmltvTime(raw: String): Long? = try {
        // XMLTV format: "20260516140000 +0000"
        xmltvSdf.parse(raw.trim())?.time
    } catch (_: Exception) { null }

    private fun parseIsoTime(raw: String): Long? = try {
        // ISO 8601 like "2026-05-16T14:00:00Z"
        if (raw.isBlank()) null
        else SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("UTC") }
            .parse(raw.trim())?.time
    } catch (_: Exception) { null }

    private fun fetchJson(url: String): JSONObject? {
        val raw = fetchRaw(url) ?: return null
        return try { JSONObject(raw) } catch (_: Exception) { null }
    }

    private fun fetchRaw(url: String, timeoutMs: Int = 10_000): String? = try {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = timeoutMs
            readTimeout    = timeoutMs
            setRequestProperty("User-Agent", "StreamX-Ultra/2.0")
            setRequestProperty("Accept",     "application/json, text/xml, */*")
        }
        if (conn.responseCode == 200) {
            conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } else null
    } catch (_: Exception) { null }

    private fun guessChannelId(url: String, name: String): String {
        val fromName = name.lowercase()
            .replace(Regex("[^a-z0-9]"), "")
            .take(20)
        return fromName.ifEmpty { url.hashCode().toString() }
    }

    private fun guessCountry(channelName: String): String {
        val lower = channelName.lowercase()
        return when {
            lower.contains("star") || lower.contains("zee") || lower.contains("sony") ||
            lower.contains("aaj tak") || lower.contains("ndtv")    -> "in"
            lower.contains("bbc") || lower.contains("itv") ||
            lower.contains("channel 4")                            -> "gb"
            lower.contains("cnn") || lower.contains("fox") ||
            lower.contains("nbc") || lower.contains("abc")         -> "us"
            lower.contains("btv") || lower.contains("channel i") ||
            lower.contains("ntv") || lower.contains("somoy")       -> "bd"
            lower.contains("geo") || lower.contains("ary") ||
            lower.contains("hum") || lower.contains("express")     -> "pk"
            else -> "in"
        }
    }

    private fun guessCategory(channelName: String): String {
        val lower = channelName.lowercase()
        return when {
            lower.contains("news")                                           -> "News"
            lower.contains("sport") || lower.contains("cricket") ||
            lower.contains("star sports")                                    -> "Sports"
            lower.contains("movie") || lower.contains("cinema")             -> "Movies"
            lower.contains("music") || lower.contains("mtv")                -> "Music"
            lower.contains("kids")  || lower.contains("cartoon")            -> "Kids"
            lower.contains("documentary") || lower.contains("natgeo")       -> "Documentary"
            else                                                             -> "Entertainment"
        }
    }

    // Realistic per-channel-type schedule (48 half-hour slots = 24 hours)
    private fun getChannelSchedule(channelName: String): List<String> {
        val lower = channelName.lowercase()
        return when {
            lower.contains("news") -> listOf(
                "Morning Briefing", "World News", "Business Hour", "Tech Today",
                "Breaking News", "Afternoon Update", "Market Close", "Evening News",
                "Prime Time News", "Late Night Bulletin", "International News", "Sports Roundup",
                "Weather Report", "Economy Watch", "Global Affairs", "Midnight News",
                "Early Morning News", "Dawn Report", "Headlines", "Analysis Hour",
                "Debate Night", "Fact Check", "Reporter's Diary", "Weekend Special",
                "Investigative Reports", "Science & Tech", "Health Today", "People & Places",
                "Finance Update", "Morning Briefing", "World News", "Business Hour",
                "Tech Today", "Breaking News", "Afternoon Update", "Market Close",
                "Evening News", "Prime Time News", "Late Night Bulletin", "International News",
                "Sports Roundup", "Weather Report", "Economy Watch", "Global Affairs",
                "Midnight News", "Early Morning News", "Dawn Report", "Headlines"
            )
            lower.contains("sport") || lower.contains("cricket") -> listOf(
                "Live Cricket", "Football Highlights", "Tennis Live", "Sports Center",
                "Cricket Commentary", "Basketball Weekly", "Athletics Special", "Boxing Night",
                "F1 Race Highlights", "Golf Tour", "Swimming Championships", "Badminton Open",
                "Sports Morning", "Match Replay", "Football Live", "Sports Tonight",
                "Champions League", "IPL Highlights", "Test Cricket", "ODI Special",
                "Rugby World", "Hockey League", "Table Tennis", "Esports Arena",
                "Sports Talk", "Pre-Game Show", "Post-Match Analysis", "Transfer News",
                "Sports Science", "Greatest Matches", "Legends Talk", "Sports Morning",
                "Match Replay", "Football Live", "Sports Tonight", "Champions League",
                "IPL Highlights", "Test Cricket", "ODI Special", "Rugby World",
                "Hockey League", "Table Tennis", "Esports Arena", "Sports Talk",
                "Pre-Game Show", "Post-Match Analysis", "Transfer News", "Sports Science"
            )
            lower.contains("music") || lower.contains("mtv") -> listOf(
                "Top 40 Countdown", "Pop Hits", "Bollywood Beats", "Indie Music Hour",
                "Rock Classics", "Hip Hop Zone", "R&B Lounge", "EDM Festival",
                "Acoustic Session", "Music Videos", "Artist Spotlight", "New Releases",
                "Retro Hits", "Jazz Night", "Classical Hour", "World Music",
                "Studio Sessions", "Live Concerts", "Music Awards", "Album Review",
                "Producer's Pick", "Chart Toppers", "Desi Beats", "Bhangra Mix",
                "Top 40 Countdown", "Pop Hits", "Bollywood Beats", "Indie Music Hour",
                "Rock Classics", "Hip Hop Zone", "R&B Lounge", "EDM Festival",
                "Acoustic Session", "Music Videos", "Artist Spotlight", "New Releases",
                "Retro Hits", "Jazz Night", "Classical Hour", "World Music",
                "Studio Sessions", "Live Concerts", "Music Awards", "Album Review",
                "Producer's Pick", "Chart Toppers", "Desi Beats", "Bhangra Mix"
            )
            else -> listOf(
                "Morning Show", "Talk Time", "Drama Series", "Reality Check",
                "Afternoon Movie", "Kids Zone", "Game Show", "Prime Drama",
                "Comedy Hour", "Late Night Talk", "Documentary Special", "Nature World",
                "Travel Diaries", "Food Safari", "Home & Garden", "Fashion Week",
                "Celebrity Special", "Award Night", "Season Finale", "New Episode",
                "Weekend Special", "Family Time", "Cooking Show", "Health & Wellness",
                "Morning Show", "Talk Time", "Drama Series", "Reality Check",
                "Afternoon Movie", "Kids Zone", "Game Show", "Prime Drama",
                "Comedy Hour", "Late Night Talk", "Documentary Special", "Nature World",
                "Travel Diaries", "Food Safari", "Home & Garden", "Fashion Week",
                "Celebrity Special", "Award Night", "Season Finale", "New Episode",
                "Weekend Special", "Family Time", "Cooking Show", "Health & Wellness"
            )
        }
    }

    // Format epoch ms → "HH:mm"
    fun formatTime(epochMs: Long): String = displaySdf.format(Date(epochMs))
}
