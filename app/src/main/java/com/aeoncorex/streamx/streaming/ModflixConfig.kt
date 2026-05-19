package com.aeoncorex.streamx.streaming

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

// ─────────────────────────────────────────────────────────────────────────────
//  ModflixConfig.kt
//  Fetches live base URLs from the providers repo on GitHub.
//  URLs change often (domain shifts), so this config is always up to date.
//  Falls back to hardcoded values if GitHub is unreachable.
// ─────────────────────────────────────────────────────────────────────────────
object ModflixConfig {

    private const val TAG         = "ModflixConfig"
    private const val CONFIG_URL  =
        "https://raw.githubusercontent.com/himanshu8443/providers/main/modflix.json"
    private const val CACHE_MS    = 3_600_000L   // 1 hour

    @Volatile private var cache:     JSONObject? = null
    @Volatile private var cacheTime: Long        = 0L

    // ── Hardcoded fallback (updated May 2026) ────────────────────────────────
    private val FALLBACK = mapOf(
        "autoEmbed"    to "https://autoembed.cc",
        "aed"          to "https://watch-drama.autoembed.cc",
        "aea"          to "https://watch-anime.autoembed.cc",
        "rive"         to "https://www.rivestream.app",
        "consumet"     to "https://consumet.zendax.tech",
        "hdhub4u"     to "https://hdhub4u.foo",
        "kissKh"       to "https://kisskh.do",
        "hdhub"        to "https://new4.hdhub4u.fo",
        "kat"          to "https://katmoviehd.pictures",
        "Vega"         to "https://vegamovies.vodka",
        "filmyfly"     to "https://new2.filmyfiy.org",
        "showbox"      to "https://www.showbox.media",
        "movieBox"     to "https://api6.aoneroom.com",
        "Topmovies"    to "https://moviesleech.link",
        "multi"        to "https://multimovies.autos",
        "filepress"    to "https://new14.filepress.store",
        "dc"           to "https://dramacool.org.ro",
        "4khdhub"      to "https://4khdhub.dad",
        "movies4u"     to "https://movies4u.vg",
        "skymovieshd"  to "https://skymovieshd.fast",
        "lux"          to "https://rogmovies.blog",
        "vadapav"      to "https://vadapav.mov",
        "nfMirror"     to "https://net22.cc",
        "primewire"    to "https://primewire.si",
        "embedsu"      to "https://moviemaze.cc",
        // Extra providers
        "guardahd"     to "https://mostraguarda.stream",
        "protonMovies" to "https://www.protonmovies.net",
        "Moviesmod"    to "https://moviesmod.day",
        "1cinevood"    to "https://www.1cinevood.net",
        "cinemaLuxe"   to "https://cinemaluxe.net",
        "Joya9tv"      to "https://joya9tv.com",
        "zeefliz"      to "https://zeefliz.vip",
        "dooflix"      to "https://dooflix.stream",
        "ogomovies"    to "https://www.ogomovies.io",
        "kmMovies"     to "https://kmmovies.org",
        "moviezwap"    to "https://moviezwap.org",
        "katfix"       to "https://katmoviesfix.net",
        "moviesapi"    to "https://moviesapi.club",
        "UhdMovies"    to "https://uhdmovies.pink",
        "Ringz"        to "https://privatereporz.pages.dev",
        "w4u"          to "https://world4ufree.tw",
    )

    suspend fun get(key: String): String = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        if (cache == null || now - cacheTime > CACHE_MS) refreshCache()
        // modflix.json structure: { "key": { "url": "https://..." } }
        // or flat: { "key": "https://..." }
        val cached = cache
        if (cached != null) {
            val obj = cached.optJSONObject(key)
            if (obj != null) return@withContext obj.optString("url", "")
                .ifEmpty { FALLBACK[key] ?: fallbackError(key) }
            val flat = cached.optString(key, "")
            if (flat.isNotEmpty()) return@withContext flat
        }
        FALLBACK[key] ?: fallbackError(key)
    }

    private fun fallbackError(key: String): String {
        Log.e(TAG, "No URL found for provider key: $key — add it to FALLBACK map")
        throw IllegalArgumentException("No URL for provider key: $key")
    }

    private fun refreshCache() {
        try {
            // Use OkHttp for reliable network call with proper timeouts
            val req = okhttp3.Request.Builder()
                .url(CONFIG_URL)
                .header("User-Agent", "StreamX-Ultra/2.0")
                .header("Cache-Control", "no-cache")
                .build()
            val client = okhttp3.OkHttpClient.Builder()
                .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                .build()
            client.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) {
                    val body = resp.body?.string() ?: return
                    cache = JSONObject(body)
                    cacheTime = System.currentTimeMillis()
                    Log.d(TAG, "ModflixConfig refreshed: ${cache!!.length()} providers")
                } else {
                    Log.w(TAG, "GitHub returned ${resp.code}, using fallback")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "ModflixConfig fetch failed (using fallback): ${e.message}")
            // Don't crash — FALLBACK map covers all providers
        }
    }
}
