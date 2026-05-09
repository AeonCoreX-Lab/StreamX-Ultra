package com.aeoncorex.streamx.streaming

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.URL

// ─────────────────────────────────────────────────────────────────────────────
//  ModflixConfig.kt
//  Fetches live base URLs from the providers repo on GitHub.
//  URLs change often (domain shifts), so this config is always up to date.
//  Falls back to hardcoded values if GitHub is unreachable.
// ─────────────────────────────────────────────────────────────────────────────
object ModflixConfig {

    private const val TAG         = "ModflixConfig"
    private const val CONFIG_URL  =
        "https://raw.githubusercontent.com/phisher98/providers/main/modflix.json"
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
    )

    suspend fun get(key: String): String = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        if (cache == null || now - cacheTime > CACHE_MS) {
            try {
                val json = URL(CONFIG_URL).readText()
                cache = JSONObject(json)
                cacheTime = now
                Log.d(TAG, "Config refreshed from GitHub")
            } catch (e: Exception) {
                Log.w(TAG, "GitHub fetch failed, using fallback: ${e.message}")
            }
        }
        cache?.optJSONObject(key)?.optString("url")
            ?: FALLBACK[key]
            ?: throw IllegalArgumentException("No URL for provider key: $key")
    }
}
