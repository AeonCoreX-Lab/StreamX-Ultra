package com.aeoncorex.streamx.data

import android.util.Log
import com.aeoncorex.streamx.model.EventsResponse
import com.aeoncorex.streamx.model.LiveEvent
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

// ════════════════════════════════════════════════════════════════════
//  EventRepository
//  • Fetches events.json from GitHub raw (same repo as IPTV data)
//  • Respects strict_mode / requires_stream / requires_thumbnail flags
//  • 2-minute in-memory cache — avoids re-fetch on recomposition
//  • Graceful fallback to cached data on network error
// ════════════════════════════════════════════════════════════════════
object EventRepository {

    private const val TAG = "EventRepository"

    private const val EVENTS_URL =
        "https://raw.githubusercontent.com/cybernahid-dev/streamx-iptv-data/main/events.json"

    private const val CACHE_TTL_MS = 2 * 60_000L   // 2 minutes

    private var cachedResponse: EventsResponse? = null
    private var lastFetchTime:  Long = 0L

    // ── Public API ────────────────────────────────────────────────

    /**
     * Full EventsResponse (cached).
     */
    suspend fun getEvents(forceRefresh: Boolean = false): EventsResponse =
        withContext(Dispatchers.IO) {
            val now = System.currentTimeMillis()
            if (!forceRefresh &&
                cachedResponse != null &&
                now - lastFetchTime < CACHE_TTL_MS
            ) {
                Log.d(TAG, "Cache hit (${(now - lastFetchTime) / 1000}s old)")
                return@withContext cachedResponse!!
            }
            try {
                val json     = fetchRaw(EVENTS_URL)
                val response = Gson().fromJson(json, EventsResponse::class.java)
                cachedResponse = response
                lastFetchTime  = now
                Log.d(TAG, "Fetched ${response.activeEvents.size} events " +
                    "(live=${response.totalLive}, upcoming=${response.totalUpcoming})")
                response
            } catch (e: Exception) {
                Log.w(TAG, "Fetch failed: ${e.message}")
                cachedResponse ?: EventsResponse()
            }
        }

    /**
     * Events ready to display in the UI.
     *
     * Applies strict_mode filtering:
     *  - strict_mode=true  → only events that have at least one stream URL
     *  - strict_mode=false → all events (including upcoming without streams)
     *
     * Also honours requires_stream and requires_thumbnail flags from the JSON.
     */
    suspend fun getActiveEvents(): List<LiveEvent> {
        val resp = getEvents()
        return resp.activeEvents.filter { event ->
            val streamOk = when {
                resp.strictMode || resp.requiresStream -> event.hasStream && event.streams.isNotEmpty()
                else -> true
            }
            val thumbOk = if (resp.requiresThumbnail) event.thumbnail.isNotEmpty() else true
            streamOk && thumbOk
        }
    }

    /**
     * Only events currently live (is_live == true) with a valid stream.
     */
    suspend fun getLiveNow(): List<LiveEvent> =
        getActiveEvents().filter { it.isLive }

    /**
     * Upcoming events (not yet live).
     */
    suspend fun getUpcoming(): List<LiveEvent> =
        getActiveEvents().filter { !it.isLive }

    /**
     * Clears cache — call on pull-to-refresh.
     */
    fun clearCache() {
        cachedResponse = null
        lastFetchTime  = 0L
    }

    // ── Private ───────────────────────────────────────────────────

    private fun fetchRaw(url: String): String {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod  = "GET"
            connectTimeout = 8_000
            readTimeout    = 10_000
            setRequestProperty("User-Agent", "StreamX-Ultra/2.0")
            setRequestProperty("Accept",     "application/json")
        }
        return if (conn.responseCode == 200) {
            conn.inputStream.bufferedReader().use { it.readText() }
        } else {
            throw Exception("HTTP ${conn.responseCode}")
        }
    }
}
