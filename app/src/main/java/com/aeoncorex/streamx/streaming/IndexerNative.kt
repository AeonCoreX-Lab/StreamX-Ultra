package com.aeoncorex.streamx.streaming

import android.content.Context
import android.util.Log
import com.aeoncorex.streamx.ui.movie.StreamLink
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray

// ═════════════════════════════════════════════════════════════════════════════
//  IndexerNative.kt
//
//  Kotlin wrapper for the Rust Jackett-style multi-site torrent indexer.
//    • nativeSearchDubbed  — searches 1337x (fallback), TorrentGalaxy,
//                            KickassTorrents, KAT-WS, TorrentDownload,
//                            ExtraTorrent, TheRARBG, ThePirateBay in
//                            parallel for dubbed/dual-audio releases.
//    • nativeSearchAll     — same sites, no dub-tag filtering.
//    • nativeSearchDrama   — K-drama (TorrentQQ/Torrentsome) + general
//                            sites, for K/C/Turkish drama.
//    • nativeSearchAnimeEnglish / nativeSearchAnimeOtherDub — Nyaa.si +
//                            Tokyo Toshokan for anime.
//
//  JNI function lives in:
//    app/src/main/rust/src/lib.rs    → Java_..._IndexerNative_nativeSearchDubbed
//    app/src/main/rust/src/indexer/  → actual scraping (engine.rs + sites/*.rs)
//
//  IMPORTANT — how this connects to playback:
//  This object ONLY searches. It does not touch TorrentEngine at all.
//  The screen calling searchDubbed() gets back a List<IndexerResult>, the
//  user picks one, and the screen then calls the EXISTING:
//
//      TorrentEngine.startNative(result.magnet, saveDir)
//
//  — the exact same call already used for YTS results. TorrentEngine,
//  session.rs, http_server.rs, and MPV are completely unchanged.
// ═════════════════════════════════════════════════════════════════════════════
object IndexerNative {

    private const val TAG = "IndexerNative"

    @Volatile private var cacheDirInitialized = false

    init {
        // streamx-native.so already loaded by TorrentEngine — safe to call again
        System.loadLibrary("streamx-native")
    }

    /**
     * Sets the on-disk directory the remote indexer-config.json cache is
     * stored in (see indexer/config/loader.rs). Call this ONCE, early —
     * e.g. from your Application.onCreate() or the first screen that
     * might trigger a search — passing the app Context:
     *
     *   IndexerNative.initialize(applicationContext)
     *
     * TODO: wire this call into your Application.onCreate() (or the
     * splash/home screen's first onCreate, if there's no custom
     * Application class yet). Not calling it isn't a hard failure —
     * see the fallback note below — but the disk cache won't persist
     * as reliably across app restarts without it.
     *
     * Safe to call multiple times (subsequent calls are no-ops) and safe
     * to skip entirely: the Rust side falls back to the system temp dir
     * if this is never called, which still works but is less durable
     * across app restarts.
     */
    fun initialize(context: Context) {
        if (cacheDirInitialized) return
        cacheDirInitialized = true
        try {
            nativeSetCacheDir(context.cacheDir.absolutePath)
        } catch (e: Exception) {
            Log.w(TAG, "initialize() failed: ${e.message}")
        }
    }

    // ── Rust JNI declaration ──────────────────────────────────────────────────

    private external fun nativeSetCacheDir(path: String)

    // ── Proxy support (HTTP / SOCKS4 / SOCKS5) ─────────────────────────────────
    //
    // Design mirrors Prowlarr's IndexerProxies feature (Settings > Indexers >
    // Proxies in Prowlarr's own UI) — verified against Prowlarr's actual
    // source (NzbDrone.Core/IndexerProxies/{Http,Socks4,Socks5}): each is
    // just a host/port/username/password bundle applied to every outgoing
    // indexer request. Prowlarr's fourth type, FlareSolverr, is NOT
    // supported here since it requires an externally-running browser
    // automation server (a separate Docker container) that can't run on
    // Android — see the project's proxy research notes for the full
    // reasoning.
    //
    // STORAGE: credentials are the user's own (their VPN/proxy service
    // login) — this class does not persist them itself. Callers should
    // read/write the actual host/port/username/password via
    // EncryptedSharedPreferences (or equivalent) in their own Settings
    // screen, and call setProxy()/clearProxy() here each time the value
    // changes AND once on app startup to restore the saved setting (Rust
    // holds it in memory only — it does not survive a process restart on
    // its own).

    private external fun nativeSetProxy(
        kind: String, // "http" | "socks4" | "socks5"
        host: String,
        port: Int,
        username: String, // "" if no auth
        password: String  // "" if no auth
    ): Boolean

    private external fun nativeClearProxy()
    private external fun nativeProxyStatus(): String

    /**
     * Activates a proxy for all indexer HTTP requests (search only —
     * this does NOT affect torrent/magnet traffic, which continues
     * through the existing TorrentEngine unchanged).
     *
     * @return true if the proxy was accepted and activated, false if
     *         the config was invalid (bad host/port) — the previously
     *         active proxy (or direct connection) remains in effect
     *         either way, so a typo here never breaks search entirely.
     */
    fun setProxy(kind: ProxyKind, host: String, port: Int, username: String? = null, password: String? = null): Boolean {
        return try {
            nativeSetProxy(kind.wireValue, host, port, username.orEmpty(), password.orEmpty())
        } catch (e: Exception) {
            Log.w(TAG, "setProxy() failed: ${e.message}")
            false
        }
    }

    /** Disables the active proxy — subsequent searches connect directly. */
    fun clearProxy() {
        try {
            nativeClearProxy()
        } catch (e: Exception) {
            Log.w(TAG, "clearProxy() failed: ${e.message}")
        }
    }

    /**
     * Human-readable current proxy state for a status line in Settings,
     * e.g. "SOCKS5 12.34.56.78:1080" or "Direct (no proxy)". Never
     * includes credentials.
     */
    fun proxyStatus(): String = try {
        nativeProxyStatus()
    } catch (e: Exception) {
        Log.w(TAG, "proxyStatus() failed: ${e.message}")
        "Unknown"
    }

    /**
     * Searches all indexer sites for dubbed/dual-audio releases.
     *
     * @param query   movie/show title to search, e.g. "Avengers Endgame 2019"
     * @param imdbId  IMDB id like "tt1234567" for exact matching on sites that
     *                support it (TorrentGalaxy), or "" if unavailable — other
     *                sites always fall back to the title string regardless.
     * @return JSON array string, one object per result:
     *         [{"title":"...","magnet":"magnet:?xt=...","size":"2.1 GB",
     *           "seeds":120,"peers":15,"source":"1337x",
     *           "audio_tags":["Hindi Dubbed"],"quality":"1080p"}, ...]
     *         "[]" on any failure — never throws.
     */
    private external fun nativeSearchDubbed(query: String, imdbId: String): String

    /**
     * Plain keyword search across all sites, no dub-tag filtering.
     * Used for the English/original-language path (replaces the old,
     * broken TorrentProviders.fetch1337x() call in TorrentRepository.kt).
     * Same JSON shape as nativeSearchDubbed.
     */
    private external fun nativeSearchAll(query: String): String

    /**
     * K-drama / C-drama / Turkish drama search — returns BOTH original-voice
     * (with subs) and English/other-dub releases together. Filter the
     * result by `audioTags` client-side to build language chips, e.g.:
     *   results.filter { "Korean" in it.audioTags }        → original voice
     *   results.filter { "English Dub" in it.audioTags }   → English dub
     *
     * NOTE: results from TorrentQQ/Torrentsome (Korean-dedicated sites)
     * carry seeds=1 as a placeholder — those two sites don't publish real
     * swarm health data at all (verified against their Jackett definitions).
     * Check `source` to distinguish "seeds unknown" from "actually 1 seed".
     */
    private external fun nativeSearchDrama(query: String): String

    /** Anime search — Nyaa's "English-translated" category (dub or sub). */
    private external fun nativeSearchAnimeEnglish(query: String): String

    /** Anime search — Nyaa's "Non-English-translated" category (other-language dub/sub). */
    private external fun nativeSearchAnimeOtherDub(query: String): String

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * FIX (root cause of "0 Hindi/dub results" while the same title had
     * 80+ English results): [imdbId] is still accepted here for API
     * stability and forward-compatibility, but the Rust side
     * (indexer::engine::search_dubbed) currently ignores it and always
     * searches by [query] text only. Passing an IMDB ID used to make
     * TGx/TheRARBG search by ID instead of by [query] — which silently
     * dropped the dub-language keywords baked into [query] (e.g. "...
     * Hindi Dubbed 1080p") and returned that site's default-language
     * listing instead, which then correctly failed the dub-tag filter
     * downstream. If IMDB-based dedicated-site search is reintroduced
     * later, it must be combined with the query's language keywords,
     * not used as a full replacement for the query string.
     */
    suspend fun searchDubbed(
        query:  String,
        imdbId: String? = null
    ): List<IndexerResult> = withContext(Dispatchers.IO) {
        try {
            val json = nativeSearchDubbed(query, imdbId.orEmpty())
            parseResults(json)
        } catch (e: Exception) {
            Log.w(TAG, "searchDubbed error: ${e.message}")
            emptyList()
        }
    }

    suspend fun searchAll(query: String): List<IndexerResult> = withContext(Dispatchers.IO) {
        try {
            val json = nativeSearchAll(query)
            parseResults(json)
        } catch (e: Exception) {
            Log.w(TAG, "searchAll error: ${e.message}")
            emptyList()
        }
    }

    /**
     * K-drama / C-drama / Turkish drama — original voice + dubs together.
     * Use [IndexerResult.audioTags] to split into UI language chips.
     */
    suspend fun searchDrama(query: String): List<IndexerResult> = withContext(Dispatchers.IO) {
        try {
            val json = nativeSearchDrama(query)
            parseResults(json)
        } catch (e: Exception) {
            Log.w(TAG, "searchDrama error: ${e.message}")
            emptyList()
        }
    }

    /** Anime, English dub or sub (Nyaa "English-translated" category). */
    suspend fun searchAnimeEnglish(query: String): List<IndexerResult> = withContext(Dispatchers.IO) {
        try {
            val json = nativeSearchAnimeEnglish(query)
            parseResults(json)
        } catch (e: Exception) {
            Log.w(TAG, "searchAnimeEnglish error: ${e.message}")
            emptyList()
        }
    }

    /** Anime, non-English dub or sub (Nyaa "Non-English-translated" category). */
    suspend fun searchAnimeOtherDub(query: String): List<IndexerResult> = withContext(Dispatchers.IO) {
        try {
            val json = nativeSearchAnimeOtherDub(query)
            parseResults(json)
        } catch (e: Exception) {
            Log.w(TAG, "searchAnimeOtherDub error: ${e.message}")
            emptyList()
        }
    }

    // ── Parsing ───────────────────────────────────────────────────────────────

    private fun parseResults(json: String): List<IndexerResult> = try {
        val arr = JSONArray(json)
        (0 until arr.length()).mapNotNull { i ->
            val o      = arr.optJSONObject(i) ?: return@mapNotNull null
            val magnet = o.optString("magnet", "")
            if (!magnet.startsWith("magnet:")) return@mapNotNull null

            val tagsArr = o.optJSONArray("audio_tags")
            val tags = mutableListOf<String>()
            if (tagsArr != null) {
                for (j in 0 until tagsArr.length()) tags.add(tagsArr.optString(j))
            }

            IndexerResult(
                title     = o.optString("title", "Unknown"),
                magnet    = magnet,
                size      = o.optString("size", ""),
                seeds     = o.optInt("seeds", 0),
                peers     = o.optInt("peers", 0),
                source    = o.optString("source", ""),
                audioTags = tags,
                quality   = o.optString("quality", "SD"),
                isConfirmedDub = o.optBoolean("is_confirmed_dub", true)
            )
        }
    } catch (e: Exception) {
        Log.w(TAG, "parseResults error: ${e.message}")
        emptyList()
    }
}

/**
 * A single dubbed/dual-audio torrent result from the indexer.
 *
 * `magnet` is passed DIRECTLY to TorrentEngine.startNative(magnet, saveDir) —
 * no conversion needed, same as an existing YTS magnet link.
 */
/**
 * Proxy protocol for indexer HTTP requests — mirrors Prowlarr's proxy
 * type selector (minus FlareSolverr, see IndexerNative's proxy section
 * doc comment for why). [wireValue] is what gets passed across the JNI
 * boundary to Rust's ProxyKind (indexer/proxy/config.rs), which expects
 * exactly these lowercase strings.
 */
enum class ProxyKind(val wireValue: String) {
    HTTP("http"),
    SOCKS4("socks4"),
    SOCKS5("socks5")
}

data class IndexerResult(
    val title:     String,
    val magnet:    String,
    val size:      String,
    val seeds:     Int,
    val peers:     Int,
    val source:    String,
    val audioTags: List<String>,
    val quality:   String,
    /**
     * False when this result came from searchDubbed()'s fallback path —
     * meaning no site returned a result carrying a recognized
     * dub-language tag for the requested title, so the (untagged, but
     * still title/IMDB-matched) results are shown instead as a
     * best-effort. UI should NOT show a language chip implying a
     * confirmed dub for these — see the "label" property below, which
     * substitutes "Best match" when this is false.
     */
    val isConfirmedDub: Boolean = true
) {
    /**
     * TorrentQQ, Torrentsome, TorrentTip (Korean drama sites), and
     * eztvtorrent.co don't publish real seeder/leecher counts — the
     * Rust indexer sets seeds=1 as an honest placeholder rather than
     * fabricating a number. UI should show "health unknown" instead of
     * a seed count for these sources.
     */
    val isHealthUnknown: Boolean
        get() = source == "TorrentQQ" || source == "Torrentsome" || source == "TorrentTip" || source == "eztvtorrent.co"

    /**
     * Short label for list UI, e.g. "1080p • Hindi Dubbed • 1337x".
     * When isConfirmedDub is false, audioTags is empty by construction
     * (the fallback path only triggers when nothing carried a tag), so
     * this substitutes an explicit "Best match, language unconfirmed"
     * note instead of silently showing no language info at all.
     */
    val label: String
        get() = buildString {
            append(quality)
            if (audioTags.isNotEmpty()) {
                append(" • ${audioTags.joinToString(", ")}")
            } else if (!isConfirmedDub) {
                append(" • Best match (language unconfirmed)")
            }
            append(" • $source")
        }
}

/**
 * Maps an [IndexerResult] to the app's existing [StreamLink] shape
 * (defined in MovieModels.kt) so results flow through the same
 * TorrentCard UI and playTorrent() path as every other provider —
 * no changes needed in MovieLinkSelectionScreen.kt.
 *
 * Dub tags are folded into the title so they're visible in the list
 * even though StreamLink itself has no dedicated audioTags field.
 *
 * FIX: this previously dropped IndexerResult.isConfirmedDub entirely —
 * StreamLink had no field for it, so the Rust-side untagged fallback
 * (searchDubbed() returning best-guess, language-unconfirmed matches
 * when no site had a dub-tagged result) was indistinguishable from a
 * normal confirmed-dub result once it reached the UI. Now carried
 * through via StreamLink.isConfirmedDub, and unconfirmed matches get an
 * explicit "[Best Match]" marker in the title so the list clearly shows
 * these aren't guaranteed to be in the requested language.
 */
fun IndexerResult.toStreamLink(): StreamLink {
    val titleWithTags = when {
        audioTags.isNotEmpty() -> "$title [${audioTags.joinToString(", ")}]"
        !isConfirmedDub        -> "$title [Best Match]"
        else                    -> title
    }

    return StreamLink(
        title          = titleWithTags,
        magnet         = magnet,
        quality        = quality.uppercase(),
        seeds          = seeds,
        peers          = peers,
        size           = size,
        source         = source,
        isConfirmedDub = isConfirmedDub
    )
}
