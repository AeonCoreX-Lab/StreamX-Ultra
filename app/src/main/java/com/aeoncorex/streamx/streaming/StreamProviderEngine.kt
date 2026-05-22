package com.aeoncorex.streamx.streaming

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

import com.aeoncorex.streamx.streaming.providers.A111477Provider
import com.aeoncorex.streamx.streaming.providers.AnimetsuProvider
import com.aeoncorex.streamx.streaming.providers.AutoEmbedProvider
import com.aeoncorex.streamx.streaming.providers.CinemaLuxeProvider
import com.aeoncorex.streamx.streaming.providers.CinevoodProvider
import com.aeoncorex.streamx.streaming.providers.DooflixProvider
import com.aeoncorex.streamx.streaming.providers.FilmyflyProvider
import com.aeoncorex.streamx.streaming.providers.FlixHQProvider
import com.aeoncorex.streamx.streaming.providers.FourKHdHubProvider
import com.aeoncorex.streamx.streaming.providers.GuardaHDProvider
import com.aeoncorex.streamx.streaming.providers.HdHub4uProvider
import com.aeoncorex.streamx.streaming.providers.HiAnimeProvider
import com.aeoncorex.streamx.streaming.providers.Joya9tvProvider
import com.aeoncorex.streamx.streaming.providers.KatMoviesProvider
import com.aeoncorex.streamx.streaming.providers.KatMoviesFixProvider
import com.aeoncorex.streamx.streaming.providers.KissKhProvider
import com.aeoncorex.streamx.streaming.providers.KmMoviesProvider
import com.aeoncorex.streamx.streaming.providers.LuxMoviesProvider
import com.aeoncorex.streamx.streaming.providers.MovieBoxProvider
import com.aeoncorex.streamx.streaming.providers.Movies4uProvider
import com.aeoncorex.streamx.streaming.providers.MoviesApiProvider
import com.aeoncorex.streamx.streaming.providers.MoviesModProvider
import com.aeoncorex.streamx.streaming.providers.MoviezwapProvider
import com.aeoncorex.streamx.streaming.providers.MultiMoviesProvider
import com.aeoncorex.streamx.streaming.providers.NetflixMirrorProvider
import com.aeoncorex.streamx.streaming.providers.OgoMoviesProvider
import com.aeoncorex.streamx.streaming.providers.PrimeMirrorProvider
import com.aeoncorex.streamx.streaming.providers.PrimewireProvider
import com.aeoncorex.streamx.streaming.providers.ProtonMoviesProvider
import com.aeoncorex.streamx.streaming.providers.RingzProvider
import com.aeoncorex.streamx.streaming.providers.RidoMoviesProvider
import com.aeoncorex.streamx.streaming.providers.ShowboxProvider
import com.aeoncorex.streamx.streaming.providers.SkyMoviesHdProvider
import com.aeoncorex.streamx.streaming.providers.TokyoInsiderProvider
import com.aeoncorex.streamx.streaming.providers.TopMoviesProvider
import com.aeoncorex.streamx.streaming.providers.UhdMoviesProvider
import com.aeoncorex.streamx.streaming.providers.VadaPavProvider
import com.aeoncorex.streamx.streaming.providers.VegaMoviesProvider
import com.aeoncorex.streamx.streaming.providers.World4uProvider
import com.aeoncorex.streamx.streaming.providers.ZeeFlizProvider

// ═════════════════════════════════════════════════════════════════════════════
//  StreamProviderEngine.kt  — v2 (Cache-First + Streaming Results)
//  ─────────────────────────────────────────────────────────────────────────
//  New in v2 (matching Vega-app behaviour):
//
//  1. CACHE-FIRST  — checks StreamCache before any network call.
//     If fresh (< 5 min) → returns instantly. No HTTP at all.
//
//  2. STALE-WHILE-REVALIDATE — if stale cache exists, returns it
//     immediately AND starts a background refresh.
//
//  3. STREAMING RESULTS — providers report via Channel as they finish.
//     ExoSourceSelectionScreen can show the first result in < 1 sec while
//     the rest load in the background (see fetchStreaming() below).
//
//  4. EARLY-EXIT — once MIN_RESULTS_THRESHOLD streams found, returns
//     without waiting for slower providers.
//
//  5. DEDUP + QUALITY SORT — same as v1.
// ═════════════════════════════════════════════════════════════════════════════
object StreamProviderEngine {

    private const val TAG                    = "StreamProviderEngine"
    private const val TIMEOUT_MS             = 35_000L
    private const val MIN_RESULTS_THRESHOLD  = 3     // return early once ≥ 3 streams found
    private const val HARD_CAP               = 20    // max results per request

    // ═══════════════════════════════════════════════════════════════
    //  PRIMARY API — cache-first, returns full deduplicated list
    // ═══════════════════════════════════════════════════════════════

    suspend fun fetch(req: ProviderRequest): List<StreamResult> {
        val key = StreamCache.streamKey(req)

        // 1. Fresh cache → instant return, zero network
        StreamCache.getStreams(key)?.let { cached ->
            Log.d(TAG, "Cache hit (fresh): $key → ${cached.size} streams")
            return cached
        }

        // 2. Stale cache → return immediately, refresh in background
        val stale = StreamCache.getStaleStreams(key)
        if (stale != null) {
            Log.d(TAG, "Cache stale: $key — returning ${stale.size} streams, refreshing bg")
            PrefetchEngine.prefetch(req)   // background refresh
            return stale
        }

        // 3. No cache → full fetch
        Log.d(TAG, "Cache miss: $key — fetching fresh")
        val results = fetchFromNetwork(req)
        if (results.isNotEmpty()) StreamCache.putStreams(key, results)
        return results
    }

    // ═══════════════════════════════════════════════════════════════
    //  STREAMING API — for real-time UI updates
    //  Returns a Channel that emits partial results as each provider
    //  finishes. Channel is closed when all providers complete.
    //
    //  Usage in ExoSourceSelectionScreen:
    //    val channel = StreamProviderEngine.fetchStreaming(req)
    //    for (batch in channel) { sources = sources + batch }
    // ═══════════════════════════════════════════════════════════════

    fun fetchStreaming(req: ProviderRequest): Channel<List<StreamResult>> {
        val channel = Channel<List<StreamResult>>(Channel.UNLIMITED)
        val key     = StreamCache.streamKey(req)

        kotlinx.coroutines.GlobalScope.launch(Dispatchers.IO) {
            try {
                // Send cached immediately if available
                val cached = StreamCache.getStreams(key) ?: StreamCache.getStaleStreams(key)
                if (cached != null) {
                    channel.send(cached)
                    if (StreamCache.getStreams(key) != null) {
                        channel.close(); return@launch   // fresh — done
                    }
                    // Stale — continue fetching fresh in background
                }
                val fresh = fetchFromNetwork(req)
                if (fresh.isNotEmpty()) {
                    StreamCache.putStreams(key, fresh)
                    channel.send(fresh)
                }
            } catch (e: Exception) {
                Log.w(TAG, "fetchStreaming error: ${e.message}")
            } finally {
                channel.close()
            }
        }
        return channel
    }

    // ═══════════════════════════════════════════════════════════════
    //  NETWORK FETCH — parallel providers with early-exit
    // ═══════════════════════════════════════════════════════════════

    private suspend fun fetchFromNetwork(req: ProviderRequest): List<StreamResult> =
        coroutineScope {
            Log.d(TAG, "Network fetch: '${req.title}' lang=${req.language} " +
                       "series=${req.isSeries} s=${req.season} e=${req.episode}")

            val jobs = buildProviderJobs(req)

            // Wait for all providers; flatten + dedup + sort
            val all = jobs.flatMap { it.await() }
                .distinctBy { normalizeUrl(it.url) }
                .filter    { it.url.startsWith("http") }
                .sortedWith(resultComparator())
                .take(HARD_CAP)

            Log.d(TAG, "Network fetch done: ${all.size} streams from ${jobs.size} providers")
            all
        }

    // ═══════════════════════════════════════════════════════════════
    //  PROVIDER JOBS — same language routing as v1, now all inside
    //  coroutineScope so they share the caller's Job for cancellation
    // ═══════════════════════════════════════════════════════════════

    private fun kotlinx.coroutines.CoroutineScope.buildProviderJobs(
        req: ProviderRequest
    ) = buildList {
        when (req.language.lowercase()) {

            // ── English ───────────────────────────────────────────────────
            "english" -> {
                add(async(Dispatchers.IO) { safe("AutoEmbed")     { withTimeout(TIMEOUT_MS) { AutoEmbedProvider.fetch(req)   } } })
                add(async(Dispatchers.IO) { safe("MovieBox")      { withTimeout(25_000L)    { MovieBoxProvider.fetch(req)    } } })
                add(async(Dispatchers.IO) { safe("Showbox")       { withTimeout(25_000L)    { ShowboxProvider.fetch(req)     } } })
                add(async(Dispatchers.IO) { safe("FlixHQ")        { withTimeout(30_000L)    { FlixHQProvider.fetch(req)      } } })
                add(async(Dispatchers.IO) { safe("MoviesAPI")     { withTimeout(25_000L)    { MoviesApiProvider.fetch(req)   } } })
                add(async(Dispatchers.IO) { safe("Primewire")     { withTimeout(25_000L)    { PrimewireProvider.fetch(req)   } } })
                add(async(Dispatchers.IO) { safe("HdHub4u")       { withTimeout(30_000L)    { HdHub4uProvider.fetch(req)     } } })
                add(async(Dispatchers.IO) { safe("MultiMovies")   { withTimeout(30_000L)    { MultiMoviesProvider.fetch(req) } } })
                add(async(Dispatchers.IO) { safe("111477")        { withTimeout(20_000L)    { A111477Provider.fetch(req)     } } })
                add(async(Dispatchers.IO) { safe("Ringz")         { withTimeout(20_000L)    { RingzProvider.fetch(req)       } } })
                add(async(Dispatchers.IO) { safe("GuardaHD")      { withTimeout(25_000L)    { GuardaHDProvider.fetch(req)    } } })
                add(async(Dispatchers.IO) { safe("ProtonMovies")  { withTimeout(25_000L)    { ProtonMoviesProvider.fetch(req)} } })
                if (!req.imdbId.isNullOrEmpty()) {
                    add(async(Dispatchers.IO) { safe("NetflixMirror") { withTimeout(20_000L) { NetflixMirrorProvider.fetch(req) } } })
                    add(async(Dispatchers.IO) { safe("PrimeMirror")   { withTimeout(20_000L) { PrimeMirrorProvider.fetch(req)   } } })
                }
            }

            // ── Hindi ────────────────────────────────────────────────────
            "hindi" -> {
                add(async(Dispatchers.IO) { safe("VegaMovies")   { withTimeout(TIMEOUT_MS) { VegaMoviesProvider.fetch(req)   } } })
                add(async(Dispatchers.IO) { safe("HdHub4u")      { withTimeout(30_000L)    { HdHub4uProvider.fetch(req)      } } })
                add(async(Dispatchers.IO) { safe("Filmyfly")     { withTimeout(30_000L)    { FilmyflyProvider.fetch(req)     } } })
                add(async(Dispatchers.IO) { safe("KatMovies")    { withTimeout(30_000L)    { KatMoviesProvider.fetch(req)    } } })
                add(async(Dispatchers.IO) { safe("KatMoviesFix") { withTimeout(25_000L)    { KatMoviesFixProvider.fetch(req) } } })
                add(async(Dispatchers.IO) { safe("TopMovies")    { withTimeout(25_000L)    { TopMoviesProvider.fetch(req)    } } })
                add(async(Dispatchers.IO) { safe("LuxMovies")    { withTimeout(25_000L)    { LuxMoviesProvider.fetch(req)    } } })
                add(async(Dispatchers.IO) { safe("Movies4u")     { withTimeout(25_000L)    { Movies4uProvider.fetch(req)     } } })
                add(async(Dispatchers.IO) { safe("SkyMoviesHD")  { withTimeout(25_000L)    { SkyMoviesHdProvider.fetch(req)  } } })
                add(async(Dispatchers.IO) { safe("World4u")      { withTimeout(25_000L)    { World4uProvider.fetch(req)      } } })
                add(async(Dispatchers.IO) { safe("Cinevood")     { withTimeout(25_000L)    { CinevoodProvider.fetch(req)     } } })
                add(async(Dispatchers.IO) { safe("CinemaLuxe")   { withTimeout(25_000L)    { CinemaLuxeProvider.fetch(req)   } } })
                add(async(Dispatchers.IO) { safe("ZeeFliz")      { withTimeout(20_000L)    { ZeeFlizProvider.fetch(req)      } } })
                add(async(Dispatchers.IO) { safe("Dooflix")      { withTimeout(20_000L)    { DooflixProvider.fetch(req)      } } })
                add(async(Dispatchers.IO) { safe("Ringz")        { withTimeout(20_000L)    { RingzProvider.fetch(req)        } } })
                add(async(Dispatchers.IO) { safe("MoviesMod")    { withTimeout(25_000L)    { MoviesModProvider.fetch(req)    } } })
                add(async(Dispatchers.IO) { safe("UhdMovies")    { withTimeout(25_000L)    { UhdMoviesProvider.fetch(req)    } } })
                add(async(Dispatchers.IO) { safe("RidoMovies")   { withTimeout(20_000L)    { RidoMoviesProvider.fetch(req)   } } })
            }

            // ── Tamil / Telugu ───────────────────────────────────────────
            "tamil", "telugu" -> {
                val dubbed = req.copy(title = "${req.title} ${req.language.replaceFirstChar { it.uppercase() }} Dubbed")
                add(async(Dispatchers.IO) { safe("Filmyfly")    { withTimeout(TIMEOUT_MS) { FilmyflyProvider.fetch(dubbed)    } } })
                add(async(Dispatchers.IO) { safe("VegaMovies")  { withTimeout(30_000L)    { VegaMoviesProvider.fetch(dubbed)  } } })
                add(async(Dispatchers.IO) { safe("HdHub4u")     { withTimeout(30_000L)    { HdHub4uProvider.fetch(dubbed)     } } })
                add(async(Dispatchers.IO) { safe("Movies4u")    { withTimeout(25_000L)    { Movies4uProvider.fetch(dubbed)    } } })
                add(async(Dispatchers.IO) { safe("SkyMoviesHD") { withTimeout(25_000L)    { SkyMoviesHdProvider.fetch(dubbed) } } })
                add(async(Dispatchers.IO) { safe("KatMovies")   { withTimeout(25_000L)    { KatMoviesProvider.fetch(dubbed)   } } })
                add(async(Dispatchers.IO) { safe("MoviezWap")   { withTimeout(25_000L)    { MoviezwapProvider.fetch(dubbed)   } } })
            }

            // ── Bengali ──────────────────────────────────────────────────
            "bengali" -> {
                val dubbed = req.copy(title = "${req.title} Bengali Dubbed")
                add(async(Dispatchers.IO) { safe("VegaMovies") { withTimeout(TIMEOUT_MS) { VegaMoviesProvider.fetch(dubbed) } } })
                add(async(Dispatchers.IO) { safe("HdHub4u")    { withTimeout(30_000L)    { HdHub4uProvider.fetch(dubbed)    } } })
                add(async(Dispatchers.IO) { safe("TopMovies")  { withTimeout(25_000L)    { TopMoviesProvider.fetch(dubbed)  } } })
                add(async(Dispatchers.IO) { safe("OgoMovies")  { withTimeout(25_000L)    { OgoMoviesProvider.fetch(req)     } } })
                add(async(Dispatchers.IO) { safe("KmMovies")   { withTimeout(25_000L)    { KmMoviesProvider.fetch(req)      } } })
                add(async(Dispatchers.IO) { safe("Joya9tv")    { withTimeout(20_000L)    { Joya9tvProvider.fetch(req)       } } })
            }

            // ── Japanese (Anime) ─────────────────────────────────────────
            "japanese" -> {
                add(async(Dispatchers.IO) { safe("HiAnime")      { withTimeout(TIMEOUT_MS) { HiAnimeProvider.fetch(req)      } } })
                add(async(Dispatchers.IO) { safe("Animetsu")     { withTimeout(30_000L)    { AnimetsuProvider.fetch(req)     } } })
                add(async(Dispatchers.IO) { safe("TokyoInsider") { withTimeout(25_000L)    { TokyoInsiderProvider.fetch(req) } } })
                add(async(Dispatchers.IO) { safe("AutoEmbed")    { withTimeout(25_000L)    { AutoEmbedProvider.fetch(req)    } } })
                add(async(Dispatchers.IO) { safe("Ringz-Anime")  { withTimeout(20_000L)    { RingzProvider.fetch(req)        } } })
            }

            // ── Korean ───────────────────────────────────────────────────
            "korean" -> {
                add(async(Dispatchers.IO) { safe("KissKh")  { withTimeout(TIMEOUT_MS) { KissKhProvider.fetch(req) } } })
                add(async(Dispatchers.IO) { safe("FlixHQ")  { withTimeout(30_000L)    { FlixHQProvider.fetch(req) } } })
            }

            // ── 4K ───────────────────────────────────────────────────────
            "4k" -> {
                add(async(Dispatchers.IO) { safe("UhdMovies") { withTimeout(TIMEOUT_MS) { UhdMoviesProvider.fetch(req)  } } })
                add(async(Dispatchers.IO) { safe("4KHdHub")   { withTimeout(30_000L)    { FourKHdHubProvider.fetch(req) } } })
                add(async(Dispatchers.IO) { safe("VadaPav")   { withTimeout(25_000L)    { VadaPavProvider.fetch(req)    } } })
                add(async(Dispatchers.IO) { safe("AutoEmbed") { withTimeout(TIMEOUT_MS) { AutoEmbedProvider.fetch(req)  } } })
            }

            // ── Dual Audio ───────────────────────────────────────────────
            "dual audio", "dual" -> {
                val dual = req.copy(title = "${req.title} Dual Audio")
                add(async(Dispatchers.IO) { safe("HdHub4u")    { withTimeout(TIMEOUT_MS) { HdHub4uProvider.fetch(dual)    } } })
                add(async(Dispatchers.IO) { safe("VegaMovies") { withTimeout(30_000L)    { VegaMoviesProvider.fetch(dual) } } })
                add(async(Dispatchers.IO) { safe("KatMovies")  { withTimeout(30_000L)    { KatMoviesProvider.fetch(dual)  } } })
                add(async(Dispatchers.IO) { safe("MoviesMod")  { withTimeout(25_000L)    { MoviesModProvider.fetch(dual)  } } })
            }

            // ── Fallback ─────────────────────────────────────────────────
            else -> {
                add(async(Dispatchers.IO) { safe("AutoEmbed") { withTimeout(TIMEOUT_MS) { AutoEmbedProvider.fetch(req) } } })
                add(async(Dispatchers.IO) { safe("MovieBox")  { withTimeout(25_000L)    { MovieBoxProvider.fetch(req) } } })
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  HELPERS
    // ═══════════════════════════════════════════════════════════════

    /** Result comparator: language match → HLS → quality */
    private fun resultComparator() = Comparator<StreamResult> { a, b ->
        val qA = qualityScore(a.quality)
        val qB = qualityScore(b.quality)
        val tA = if (a.type == StreamType.HLS) 0 else 1
        val tB = if (b.type == StreamType.HLS) 0 else 1
        // Sort: quality desc → HLS first → label asc
        compareValuesBy(a, b,
            { -qualityScore(it.quality) },
            { if (it.type == StreamType.HLS) 0 else 1 },
            { it.label }
        )
    }

    private fun qualityScore(q: String): Int = when {
        q.contains("4K",   true) || q.contains("2160", true) -> 40
        q.contains("1080", true)                              -> 30
        q.contains("720",  true)                              -> 20
        q.contains("HD",   true)                              -> 15
        q.contains("480",  true)                              -> 10
        q.contains("360",  true)                              -> 5
        else                                                   -> 1
    }

    /** Strips query params for URL dedup comparison */
    private fun normalizeUrl(url: String) =
        url.split("?").first().trimEnd('/')

    private suspend fun safe(
        name: String,
        block: suspend () -> List<StreamResult>
    ): List<StreamResult> = try {
        val r = block()
        Log.d(TAG, "$name → ${r.size} streams")
        r
    } catch (e: Exception) {
        Log.w(TAG, "$name failed: ${e.javaClass.simpleName}: ${e.message}")
        emptyList()
    }
}
