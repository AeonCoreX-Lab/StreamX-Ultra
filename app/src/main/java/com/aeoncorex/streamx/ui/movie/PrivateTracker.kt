package com.aeoncorex.streamx.ui.movie

import android.util.Log
import android.util.Xml
import com.aeoncorex.streamx.backup.BackupManager
import com.aeoncorex.streamx.streaming.TorrentWafInterceptor
import com.tencent.mmkv.MMKV
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.xmlpull.v1.XmlPullParser
import java.security.SecureRandom
import java.util.concurrent.TimeUnit

// ═════════════════════════════════════════════════════════════════════════════
//  PrivateTracker.kt
//  ─────────────────────────────────────────────────────────────────────────
//  Private-tracker support via the Torznab/Newznab search API — the same
//  API convention Jackett, Prowlarr, Sonarr, and Radarr all speak, and
//  what the overwhelming majority of private trackers expose natively (or
//  via their own Jackett-compatible endpoint) without needing per-site
//  HTML-scraping rules. A tracker here is just a base URL + API key —
//  no cookie, no HTML selectors, no per-site Kotlin code required to add
//  a new one.
//
//  WHY KOTLIN, NOT THE RUST streamx-indexer CRATE: a private tracker's
//  Torznab endpoint can itself sit behind a Cloudflare-style challenge
//  layer, same as any public site — and solving that needs a real
//  WebView (see WafCookieResolver.kt), which Rust/reqwest fundamentally
//  cannot do. Putting this here means torrentSearch() below can reuse
//  TorrentWafInterceptor on the exact same OkHttpClient as every other
//  Kotlin-side provider — a challenge-walled private tracker "just
//  works" the same way BitSearch/TorrentCSV already do, with zero extra
//  code. See docs discussion: the API key itself is NOT something WAF-
//  solving can discover or replace — it's a credential the tracker
//  issues to the user's account, pasted once, same as Jackett/Prowlarr/
//  Sonarr require. What IS fully automatic is any bot-challenge sitting
//  in front of that endpoint.
//
//  Torznab response format: RSS 2.0 with a torznab:attr extension per
//  <item> for structured fields (size, seeders, peers, infohash, etc).
//  Parsed with a plain XmlPullParser — no XML library dependency beyond
//  what Android already ships — since the format is simple and a
//  private tracker's exact <item> field set can vary slightly (not
//  every tracker populates every attr), so a forgiving field-by-field
//  parse is more robust here than a strict deserializer.
// ═════════════════════════════════════════════════════════════════════════════

/**
 * One configured private tracker. [id] is a locally-generated stable
 * identifier (not tied to the tracker itself) used for storage keys and
 * list diffing — see [PrivateTrackerStore].
 */
data class PrivateTracker(
    val id: String,
    val name: String,
    /** Torznab base URL, e.g. "https://example-tracker.com/api/v1/torznab" — no trailing slash, no query string. */
    val baseUrl: String,
    val apiKey: String,
    val enabled: Boolean = true
) {
    companion object {
        fun newId(): String {
            val bytes = ByteArray(9)
            SecureRandom().nextBytes(bytes)
            return bytes.joinToString("") { "%02x".format(it) }
        }
    }
}

/**
 * Persistent storage for the user's configured private trackers.
 * Follows [com.aeoncorex.streamx.ui.movie.ProxySettingsStore]'s exact
 * pattern — that file's own doc comment says future private-tracker
 * credentials should use this same store/pattern, so: AES-encrypted
 * MMKV store (an API key is a real account-tied secret, same
 * sensitivity class as the proxy password ProxySettingsStore already
 * protects this way), one random-generated encryption key stored in
 * plain (unencrypted) MMKV alongside — see ProxySettingsStore's own
 * comment for why that split is safe (the OS already sandboxes the
 * app's MMKV directory from other apps; this key only defends against
 * a casual read of the raw storage file, e.g. an ADB backup pulled from
 * a rooted device, not a fully compromised device).
 */
object PrivateTrackerStore {
    private const val TAG = "PrivateTrackerStore"
    private const val STORE_ID = "streamx_private_trackers"
    private const val KEY_ID = "streamx_private_trackers_key"
    private const val KEY_LIST = "trackers_v1"

    private val keyStore: MMKV by lazy { MMKV.mmkvWithID(KEY_ID) }
    private val encryptionKey: String by lazy {
        keyStore.decodeString("key") ?: run {
            val bytes = ByteArray(32)
            SecureRandom().nextBytes(bytes)
            val generated = bytes.joinToString("") { "%02x".format(it) }
            keyStore.encode("key", generated)
            generated
        }
    }
    private val store: MMKV by lazy {
        MMKV.mmkvWithID(STORE_ID, MMKV.SINGLE_PROCESS_MODE, encryptionKey)
    }

    fun getAll(): List<PrivateTracker> {
        val raw = store.decodeString(KEY_LIST) ?: return emptyList()
        return decodeList(raw)
    }

    fun getEnabled(): List<PrivateTracker> = getAll().filter { it.enabled }

    fun add(name: String, baseUrl: String, apiKey: String, context: android.content.Context? = null): PrivateTracker {
        val tracker = PrivateTracker(
            id = PrivateTracker.newId(),
            name = name.trim(),
            baseUrl = baseUrl.trim().trimEnd('/'),
            apiKey = apiKey.trim()
        )
        val updated = getAll() + tracker
        persist(updated, context)
        return tracker
    }

    fun update(tracker: PrivateTracker, context: android.content.Context? = null) {
        val updated = getAll().map { if (it.id == tracker.id) tracker else it }
        persist(updated, context)
    }

    fun remove(id: String, context: android.content.Context? = null) {
        val updated = getAll().filterNot { it.id == id }
        persist(updated, context)
    }

    fun setEnabled(id: String, enabled: Boolean, context: android.content.Context? = null) {
        val updated = getAll().map { if (it.id == id) it.copy(enabled = enabled) else it }
        persist(updated, context)
    }

    /**
     * Overwrites the entire tracker list — used only by
     * [com.aeoncorex.streamx.backup.BackupManager] when restoring a
     * backup. Not for normal add/remove/toggle use (those go through
     * the targeted functions above, which read-modify-write instead of
     * replacing the whole list). Deliberately does NOT call syncNow()
     * itself — BackupManager.restoreFromBackup() is what just downloaded
     * this data FROM the backup; immediately re-uploading it back would
     * be a pointless round-trip.
     */
    fun replaceAll(trackers: List<PrivateTracker>) {
        store.encode(KEY_LIST, encodeList(trackers))
    }

    private fun persist(trackers: List<PrivateTracker>, context: android.content.Context?) {
        store.encode(KEY_LIST, encodeList(trackers))
        // Automatic background backup — see BackupManager.syncNow()'s
        // doc comment and ProxySettingsStore.save()'s identical
        // fire-and-forget pattern. Local save above already succeeded
        // regardless of backup outcome; that's reported separately via
        // BackupManager.status.
        context?.let { BackupManager.syncNow(it) }
    }

    // Same delimiter-based encoding as TorrentWafCookieStore, for the
    // same reason (avoid pulling a JSON library dependency into a tiny
    // fixed-shape record) — one tracker per line, fields "|"-delimited
    // with '|' and newline both escaped, since a user-chosen tracker
    // name could contain either.
    private fun encodeList(trackers: List<PrivateTracker>): String =
        trackers.joinToString("\n") { t ->
            listOf(t.id, t.name, t.baseUrl, t.apiKey, t.enabled.toString())
                .joinToString("|") { it.replace("\\", "\\\\").replace("|", "\\|").replace("\n", "\\n") }
        }

    private fun decodeList(raw: String): List<PrivateTracker> {
        if (raw.isBlank()) return emptyList()
        return raw.split("\n").mapNotNull { line ->
            if (line.isBlank()) return@mapNotNull null
            val parts = splitEscaped(line)
            if (parts.size != 5) {
                Log.w(TAG, "skipping malformed tracker entry (expected 5 fields, got ${parts.size})")
                return@mapNotNull null
            }
            try {
                PrivateTracker(
                    id = parts[0],
                    name = parts[1],
                    baseUrl = parts[2],
                    apiKey = parts[3],
                    enabled = parts[4].toBoolean()
                )
            } catch (e: Exception) {
                Log.w(TAG, "skipping malformed tracker entry: ${e.message}")
                null
            }
        }
    }

    private fun splitEscaped(line: String): List<String> {
        val parts = Regex("""(?<!\\)\|""").split(line)
        return parts.map { it.replace("\\n", "\n").replace("\\|", "|").replace("\\\\", "\\") }
    }
}

/**
 * Torznab search client. One function, reused for every configured
 * tracker — a tracker here is data (base URL + API key), not code, so
 * adding tracker #2, #3, #N never touches this function.
 */
object PrivateTrackerSearch {
    private const val TAG = "PrivateTrackerSearch"

    // Same httpClient shape as TorrentProviders.httpClient (see that
    // file) — same generous connect/read timeouts for slow/overseas
    // trackers, and the SAME TorrentWafInterceptor instance, so a
    // Cloudflare-walled tracker endpoint gets solved the exact same way
    // as any other Kotlin-side provider — no separate WAF plumbing
    // needed for private trackers specifically.
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .addInterceptor(TorrentWafInterceptor)
        .build()

    /**
     * Searches [tracker] for [query], returning results already mapped
     * to [StreamLink] — same shape every other provider in
     * TorrentProviders.kt returns, so callers merge this into the same
     * list with no extra mapping step. Never throws: any failure
     * (network, bad XML, non-2xx status, tracker misconfiguration)
     * returns an empty list, matching every other provider's
     * try/catch-to-emptyList() convention at the TorrentRepository call
     * site.
     */
    suspend fun search(tracker: PrivateTracker, query: String): List<StreamLink> = withContext(Dispatchers.IO) {
        try {
            val url = "${tracker.baseUrl}/api?t=search" +
                "&q=${java.net.URLEncoder.encode(query, "UTF-8")}" +
                "&apikey=${java.net.URLEncoder.encode(tracker.apiKey, "UTF-8")}"

            val request = Request.Builder().url(url).build()
            val response = httpClient.newCall(request).execute()

            if (!response.isSuccessful) {
                Log.w(TAG, "${tracker.name}: HTTP ${response.code}")
                response.close()
                return@withContext emptyList()
            }

            val body = response.body?.string()
            response.close()
            if (body.isNullOrBlank()) return@withContext emptyList()

            parseTorznabResponse(body, tracker.name)
        } catch (e: Exception) {
            Log.w(TAG, "${tracker.name}: search failed: ${e.message}")
            emptyList()
        }
    }

    /**
     * Parses a Torznab RSS response into [StreamLink]s. Forgiving by
     * design — reads whichever torznab:attr name/value pairs a given
     * <item> actually has (real trackers vary in which attrs they
     * populate) rather than requiring a fixed strict shape. An <item>
     * missing a magnet/infohash entirely is skipped (nothing playable to
     * offer); every other missing field just falls back to a sane
     * default (0 seeds/peers, empty size) instead of dropping the whole
     * item.
     */
    internal fun parseTorznabResponse(xml: String, sourceName: String): List<StreamLink> {
        val results = mutableListOf<StreamLink>()

        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        parser.setInput(xml.reader())

        var inItem = false
        var title = ""
        var magnet = ""
        var size = ""
        var seeds = 0
        var peers = 0

        var eventType = parser.eventType
        while (eventType != XmlPullParser.END_DOCUMENT) {
            when (eventType) {
                XmlPullParser.START_TAG -> when (parser.name) {
                    "item" -> {
                        inItem = true
                        title = ""; magnet = ""; size = ""; seeds = 0; peers = 0
                    }
                    "title" -> if (inItem) title = readText(parser)
                    "link" -> if (inItem && magnet.isBlank()) {
                        // Some trackers put the magnet/torrent-download
                        // URL directly in <link> instead of an attr.
                        val text = readText(parser)
                        if (text.startsWith("magnet:")) magnet = text
                    }
                    "torznab:attr", "newznab:attr" -> if (inItem) {
                        val attrName = parser.getAttributeValue(null, "name")
                        val attrValue = parser.getAttributeValue(null, "value")
                        when (attrName) {
                            "magneturl" -> if (attrValue != null) magnet = attrValue
                            "infohash" -> if (attrValue != null && magnet.isBlank()) {
                                magnet = "magnet:?xt=urn:btih:$attrValue"
                            }
                            "size" -> attrValue?.toLongOrNull()?.let { size = formatBytes(it) }
                            "seeders" -> attrValue?.toIntOrNull()?.let { seeds = it }
                            "peers" -> attrValue?.toIntOrNull()?.let {
                                // Torznab's "peers" attr is total swarm
                                // size (seeders + leechers combined), not
                                // leechers alone — subtract seeders so
                                // this lines up with every other
                                // provider's seeds/peers meaning
                                // (peers == leechers) in this app.
                                peers = (it - seeds).coerceAtLeast(0)
                            }
                        }
                    }
                }
                XmlPullParser.END_TAG -> if (parser.name == "item") {
                    inItem = false
                    if (title.isNotBlank() && magnet.isNotBlank()) {
                        results.add(
                            StreamLink(
                                title = title,
                                magnet = magnet,
                                quality = guessQuality(title),
                                seeds = seeds,
                                peers = peers,
                                size = size,
                                source = sourceName,
                                isConfirmedDub = true
                            )
                        )
                    }
                }
            }
            eventType = parser.next()
        }

        return results
    }

    private fun readText(parser: XmlPullParser): String =
        if (parser.next() == XmlPullParser.TEXT) parser.text.trim().also { parser.nextTag() } else ""

    private fun guessQuality(title: String): String {
        val t = title.lowercase()
        return when {
            "2160p" in t || "4k" in t -> "4K"
            "1080p" in t -> "1080p"
            "720p" in t -> "720p"
            "480p" in t -> "480p"
            else -> "SD"
        }
    }

    private fun formatBytes(bytes: Long): String {
        if (bytes <= 0) return ""
        val gb = bytes / 1_073_741_824.0
        if (gb >= 1) return "%.2f GB".format(gb)
        val mb = bytes / 1_048_576.0
        return "%.0f MB".format(mb)
    }
}
