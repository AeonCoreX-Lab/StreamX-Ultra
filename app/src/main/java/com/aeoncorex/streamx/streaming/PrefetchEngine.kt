package com.aeoncorex.streamx.streaming

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

// ═════════════════════════════════════════════════════════════════════════════
//  PrefetchEngine.kt
//  ─────────────────────────────────────────────────────────────────────────
//  Background prefetch — mirrors Vega-app's useStream() hook that begins
//  fetching as soon as the details screen opens, BEFORE user taps Play.
//
//  Flow:
//    1. MovieDetailsScreen calls PrefetchEngine.prefetch(req)
//    2. Engine checks StreamCache — if fresh: done (instant)
//    3. If stale/missing: acquires in-flight lock, calls StreamProviderEngine
//    4. Result stored in StreamCache
//    5. ExoSourceSelectionScreen.LaunchedEffect reads from cache → instant UI
//
//  The scope is app-global (SupervisorJob) so prefetch survives navigation.
// ═════════════════════════════════════════════════════════════════════════════
object PrefetchEngine {

    private const val TAG = "PrefetchEngine"

    // Separate IO scope so prefetches survive composable recomposition
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Prefetch streams for this request in the background.
     * Safe to call multiple times — duplicate requests are deduplicated via StreamCache.
     */
    fun prefetch(req: ProviderRequest) {
        val key = StreamCache.streamKey(req)

        // Already fresh in cache → nothing to do
        if (StreamCache.getStreams(key) != null) {
            Log.d(TAG, "Cache fresh for '$key' — skipping prefetch")
            return
        }

        // Another coroutine already fetching this key
        if (!StreamCache.tryAcquireFetch(key)) {
            Log.d(TAG, "In-flight for '$key' — deduped")
            return
        }

        scope.launch {
            try {
                Log.d(TAG, "Prefetching: '${req.title}' [${req.language}]")
                val results = StreamProviderEngine.fetch(req)
                if (results.isNotEmpty()) {
                    StreamCache.putStreams(key, results)
                    Log.d(TAG, "Prefetch done: ${results.size} streams for '$key'")
                } else {
                    Log.d(TAG, "Prefetch: no results for '$key'")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Prefetch error for '$key': ${e.message}")
            } finally {
                StreamCache.releaseFetch(key)
            }
        }
    }

    /**
     * Prefetch for all common languages in parallel.
     * Called when details screen opens for a Hindi/South Indian film
     * to warm the cache for all dub options simultaneously.
     */
    fun prefetchAllLanguages(
        tmdbId:   Int?,
        imdbId:   String?,
        title:    String,
        year:     Int?,
        isSeries: Boolean,
        season:   Int = 0,
        episode:  Int = 0,
        languages: List<String> = listOf("English", "Hindi")
    ) {
        languages.forEach { lang ->
            prefetch(ProviderRequest(
                tmdbId   = tmdbId,
                imdbId   = imdbId,
                title    = title,
                year     = year,
                isSeries = isSeries,
                season   = season,
                episode  = episode,
                language = lang
            ))
        }
    }
}
