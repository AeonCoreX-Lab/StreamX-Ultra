package com.aeoncorex.streamx.streaming

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeout

import com.aeoncorex.streamx.streaming.providers.AnimetsuProvider
import com.aeoncorex.streamx.streaming.providers.AutoEmbedProvider
import com.aeoncorex.streamx.streaming.providers.FilmyflyProvider
import com.aeoncorex.streamx.streaming.providers.FlixHQProvider
import com.aeoncorex.streamx.streaming.providers.FourKHdHubProvider
import com.aeoncorex.streamx.streaming.providers.HdHub4uProvider
import com.aeoncorex.streamx.streaming.providers.HiAnimeProvider
import com.aeoncorex.streamx.streaming.providers.KatMoviesProvider
import com.aeoncorex.streamx.streaming.providers.KissKhProvider
import com.aeoncorex.streamx.streaming.providers.LuxMoviesProvider
import com.aeoncorex.streamx.streaming.providers.MovieBoxProvider
import com.aeoncorex.streamx.streaming.providers.Movies4uProvider
import com.aeoncorex.streamx.streaming.providers.MultiMoviesProvider
import com.aeoncorex.streamx.streaming.providers.ShowboxProvider
import com.aeoncorex.streamx.streaming.providers.SkyMoviesHdProvider
import com.aeoncorex.streamx.streaming.providers.TopMoviesProvider
import com.aeoncorex.streamx.streaming.providers.UhdMoviesProvider
import com.aeoncorex.streamx.streaming.providers.VadaPavProvider
import com.aeoncorex.streamx.streaming.providers.VegaMoviesProvider
import com.aeoncorex.streamx.streaming.providers.World4uProvider

// ─────────────────────────────────────────────────────────────────────────────
//  StreamProviderEngine.kt
//  Orchestrates all providers. Selects relevant providers based on language
//  and media type, runs them in parallel, deduplicates results.
//
//  Architecture:
//  - No backend server needed — all HTTP calls directly from device
//  - Base URLs fetched from GitHub (modflix.json) + cached 1 hour
//  - 30+ provider base, 8 fully implemented with extractors
// ─────────────────────────────────────────────────────────────────────────────
object StreamProviderEngine {

    private const val TAG        = "StreamProviderEngine"
    private const val TIMEOUT_MS = 35_000L

    // ── Provider selection by language ───────────────────────────────────────
    // Each provider is wrapped in a timeout so one slow site can't block others

    suspend fun fetch(req: ProviderRequest): List<StreamResult> = coroutineScope {
        Log.d(TAG, "Fetching: '${req.title}' lang=${req.language} " +
              "series=${req.isSeries} s=${req.season} e=${req.episode}")

        val jobs = buildList {
            when (req.language.lowercase()) {

                // ── English / Default ────────────────────────────────────────
                "english" -> {
                    add(async(Dispatchers.IO) { runSafe("AutoEmbed")  { withTimeout(TIMEOUT_MS) { AutoEmbedProvider.fetch(req)  } } })
                    add(async(Dispatchers.IO) { runSafe("MovieBox")   { withTimeout(25_000L)    { MovieBoxProvider.fetch(req)   } } })
                    add(async(Dispatchers.IO) { runSafe("Showbox")    { withTimeout(25_000L)    { ShowboxProvider.fetch(req)    } } })
                    add(async(Dispatchers.IO) { runSafe("FlixHQ")     { withTimeout(30_000L)    { FlixHQProvider.fetch(req)     } } })
                    add(async(Dispatchers.IO) { runSafe("HdHub4u")    { withTimeout(30_000L)    { HdHub4uProvider.fetch(req)    } } })
                    add(async(Dispatchers.IO) { runSafe("MultiMovies"){ withTimeout(30_000L)    { MultiMoviesProvider.fetch(req)} } })
                }

                // ── Hindi ────────────────────────────────────────────────────
                "hindi" -> {
                    add(async(Dispatchers.IO) { runSafe("VegaMovies") { withTimeout(TIMEOUT_MS) { VegaMoviesProvider.fetch(req)  } } })
                    add(async(Dispatchers.IO) { runSafe("HdHub4u")    { withTimeout(30_000L)    { HdHub4uProvider.fetch(req)     } } })
                    add(async(Dispatchers.IO) { runSafe("Filmyfly")   { withTimeout(30_000L)    { FilmyflyProvider.fetch(req)    } } })
                    add(async(Dispatchers.IO) { runSafe("KatMovies")  { withTimeout(30_000L)    { KatMoviesProvider.fetch(req)   } } })
                    add(async(Dispatchers.IO) { runSafe("TopMovies")  { withTimeout(25_000L)    { TopMoviesProvider.fetch(req)   } } })
                    add(async(Dispatchers.IO) { runSafe("LuxMovies")  { withTimeout(25_000L)    { LuxMoviesProvider.fetch(req)   } } })
                    add(async(Dispatchers.IO) { runSafe("Movies4u")   { withTimeout(25_000L)    { Movies4uProvider.fetch(req)    } } })
                    add(async(Dispatchers.IO) { runSafe("SkyMoviesHD"){ withTimeout(25_000L)    { SkyMoviesHdProvider.fetch(req) } } })
                    add(async(Dispatchers.IO) { runSafe("World4u")    { withTimeout(25_000L)    { World4uProvider.fetch(req)     } } })
                }

                // ── Tamil / Telugu ───────────────────────────────────────────
                "tamil", "telugu" -> {
                    val dubbed = req.copy(title = "${req.title} ${req.language} Dubbed")
                    add(async(Dispatchers.IO) { runSafe("Filmyfly")   { withTimeout(TIMEOUT_MS) { FilmyflyProvider.fetch(dubbed)   } } })
                    add(async(Dispatchers.IO) { runSafe("VegaMovies") { withTimeout(30_000L)    { VegaMoviesProvider.fetch(dubbed) } } })
                    add(async(Dispatchers.IO) { runSafe("HdHub4u")    { withTimeout(30_000L)    { HdHub4uProvider.fetch(dubbed)    } } })
                    add(async(Dispatchers.IO) { runSafe("Movies4u")   { withTimeout(25_000L)    { Movies4uProvider.fetch(dubbed)   } } })
                    add(async(Dispatchers.IO) { runSafe("SkyMoviesHD"){ withTimeout(25_000L)    { SkyMoviesHdProvider.fetch(dubbed) } } })
                }

                // ── Bengali ──────────────────────────────────────────────────
                "bengali" -> {
                    val dubbed = req.copy(title = "${req.title} Bengali Dubbed")
                    add(async(Dispatchers.IO) { runSafe("VegaMovies") { withTimeout(TIMEOUT_MS) { VegaMoviesProvider.fetch(dubbed) } } })
                    add(async(Dispatchers.IO) { runSafe("HdHub4u")    { withTimeout(30_000L)    { HdHub4uProvider.fetch(dubbed)    } } })
                    add(async(Dispatchers.IO) { runSafe("TopMovies")  { withTimeout(25_000L)    { TopMoviesProvider.fetch(dubbed)  } } })
                }

                // ── 4K ───────────────────────────────────────────────────────
                "4k" -> {
                    add(async(Dispatchers.IO) { runSafe("UhdMovies")  { withTimeout(TIMEOUT_MS) { UhdMoviesProvider.fetch(req)   } } })
                    add(async(Dispatchers.IO) { runSafe("4KHdHub")    { withTimeout(30_000L)    { FourKHdHubProvider.fetch(req)  } } })
                    add(async(Dispatchers.IO) { runSafe("VadaPav")    { withTimeout(25_000L)    { VadaPavProvider.fetch(req)     } } })
                    add(async(Dispatchers.IO) { runSafe("AutoEmbed")  { withTimeout(TIMEOUT_MS) { AutoEmbedProvider.fetch(req)   } } })
                }

                // ── Japanese (Anime) ─────────────────────────────────────────
                "japanese" -> {
                    add(async(Dispatchers.IO) { runSafe("HiAnime")    { withTimeout(TIMEOUT_MS) { HiAnimeProvider.fetch(req)    } } })
                    add(async(Dispatchers.IO) { runSafe("Animetsu")   { withTimeout(30_000L)    { AnimetsuProvider.fetch(req)   } } })
                    add(async(Dispatchers.IO) { runSafe("AutoEmbed")  { withTimeout(25_000L)    { AutoEmbedProvider.fetch(req)  } } })
                }

                // ── Korean ───────────────────────────────────────────────────
                "korean" -> {
                    add(async(Dispatchers.IO) { runSafe("KissKh")     { withTimeout(TIMEOUT_MS) { KissKhProvider.fetch(req)    } } })
                    add(async(Dispatchers.IO) { runSafe("FlixHQ")     { withTimeout(30_000L)    { FlixHQProvider.fetch(req)    } } })
                }

                // ── Dual Audio ───────────────────────────────────────────────
                "dual audio", "dual" -> {
                    add(async(Dispatchers.IO) { runSafe("HdHub4u")    { withTimeout(TIMEOUT_MS) { HdHub4uProvider.fetch(req.copy(title = "${req.title} Dual Audio")) } } })
                    add(async(Dispatchers.IO) { runSafe("VegaMovies") { withTimeout(30_000L)    { VegaMoviesProvider.fetch(req.copy(title = "${req.title} Dual Audio")) } } })
                    add(async(Dispatchers.IO) { runSafe("KatMovies")  { withTimeout(30_000L)    { KatMoviesProvider.fetch(req.copy(title = "${req.title} Dual Audio")) } } })
                }

                // ── Fallback: AutoEmbed + MovieBox ────────────────────────────
                else -> {
                    add(async(Dispatchers.IO) { runSafe("AutoEmbed")  { withTimeout(TIMEOUT_MS) { AutoEmbedProvider.fetch(req)  } } })
                    add(async(Dispatchers.IO) { runSafe("MovieBox")   { withTimeout(25_000L)    { MovieBoxProvider.fetch(req)   } } })
                }
            }
        }

        // Wait for all, flatten, deduplicate by URL
        val results = jobs.flatMap { it.await() }
            .distinctBy { it.url }
            .sortedBy { qualityOrder(it.quality) }

        Log.d(TAG, "Total results: ${results.size} (providers: ${jobs.size})")
        results
    }

    // ── Quality sort: best quality first ─────────────────────────────────────
    private fun qualityOrder(quality: String): Int = when {
        quality.contains("4K", true)   || quality.contains("2160", true) -> 0
        quality.contains("1080", true)                                    -> 1
        quality.contains("720", true)                                     -> 2
        quality.contains("480", true)                                     -> 3
        quality.contains("HD",  true)                                     -> 2
        else                                                               -> 4
    }

    // ── Safe runner — catches exceptions so one provider can't crash others ──
    private suspend fun runSafe(name: String, block: suspend () -> List<StreamResult>): List<StreamResult> {
        return try {
            val results = block()
            Log.d(TAG, "$name → ${results.size} streams")
            results
        } catch (e: Exception) {
            Log.w(TAG, "$name failed: ${e.message}")
            emptyList()
        }
    }
}
