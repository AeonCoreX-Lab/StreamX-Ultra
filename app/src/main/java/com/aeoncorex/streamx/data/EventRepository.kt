package com.aeoncorex.streamx.data

import android.util.Log
import com.aeoncorex.streamx.model.EventsResponse
import com.aeoncorex.streamx.model.LiveEvent
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

// ════════════════════════════════════════════════════════════════════════════
//  EventRepository
//  ───────────────
//  • Fetches events.json from the GitHub raw URL (same repo as IPTV data)
//  • In-memory cache with 2-minute TTL so rapid recompositions don't re-fetch
//  • Returns empty list gracefully on any network error
// ════════════════════════════════════════════════════════════════════════════
object EventRepository {

    private const val TAG = "EventRepository"

    // ── Same base URL as your IPTV repo ──────────────────────────────────
    private const val EVENTS_URL =
        "https://raw.githubusercontent.com/cybernahid-dev/streamx-iptv-data/main/events.json"

    private const val CACHE_TTL_MS = 2 * 60_000L   // 2 minutes

    private var cachedResponse: EventsResponse? = null
    private var lastFetchTime:  Long = 0L

    // ── Public API ────────────────────────────────────────────────────────

    /**
     * Returns the full EventsResponse.
     * Hits the network only when cache is stale (> 2 min old).
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
                val json = fetchRaw(EVENTS_URL)
                val response = Gson().fromJson(json, EventsResponse::class.java)
                cachedResponse = response
                lastFetchTime  = now
                Log.d(TAG, "Fetched ${response.activeEvents.size} events")
                response
            } catch (e: Exception) {
                Log.w(TAG, "Failed to fetch events: ${e.message}")
                cachedResponse ?: EventsResponse()
            }
        }

    /**
     * Returns only currently-live events (is_live == true).
     */
    suspend fun getLiveNow(): List<LiveEvent> =
        getEvents().activeEvents.filter { it.isLive }

    /**
     * Returns live + upcoming events combined.
     */
    suspend fun getActiveEvents(): List<LiveEvent> =
        getEvents().activeEvents

    /**
     * Clears in-memory cache — call when user triggers pull-to-refresh.
     */
    fun clearCache() {
        cachedResponse = null
        lastFetchTime  = 0L
    }

    // ── Private helpers ───────────────────────────────────────────────────

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
