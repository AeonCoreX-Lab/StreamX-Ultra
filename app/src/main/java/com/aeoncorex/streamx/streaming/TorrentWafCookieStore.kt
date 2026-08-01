package com.aeoncorex.streamx.streaming

import com.tencent.mmkv.MMKV

/**
 * Persistent, TTL-aware cache for WAF-clearance cookies solved on-device
 * for TORRENT domains specifically (1337x, KAT, TorrentGalaxy mirrors,
 * etc — anything routed through TorrentProviders' httpClient /
 * TorrentWafInterceptor).
 *
 * WHY THIS EXISTS: the app already has a WAF-solving system —
 * WafCookieResolver's WebView-based challenge solver, plus a Worker-side
 * cookie cache (wafCookieStore.js) for direct-stream providers. But that
 * Worker cache ONLY exists for domains the Cloudflare Worker itself
 * fetches — torrent traffic never goes through that Worker at all (see
 * TorrentWafInterceptor's header comment), so torrent-site cookies had
 * NOWHERE persistent to live. Before this store existed,
 * resolveLocalOnly()'s result sat only in Android's CookieManager with
 * no TTL tracking, no app-level visibility into freshness, and no way
 * to proactively refresh before expiry — every request effectively
 * gambled on whatever CookieManager happened to still have.
 *
 * This mirrors wafCookieStore.js's shape and behavior almost exactly
 * (same field names, same TTL clamp bounds, same "store what the
 * caller reports, don't try to parse Set-Cookie Max-Age ourselves"
 * design — Set-Cookie Max-Age parsing is unreliable across WAF vendors,
 * whereas WafCookieResolver already knows the real solve time and can
 * report a trustworthy TTL directly), just backed by MMKV instead of a
 * KV namespace, and entirely local to this device — there is
 * deliberately no server-side replay/sharing across devices for
 * torrent-site cookies (unlike the Worker's model), since there's no
 * Worker sitting in this request path to share them through.
 *
 * NOT encrypted (unlike ProxySettingsStore) — a WAF-clearance cookie is
 * not a user secret (it's issued by the torrent site itself to any
 * anonymous visitor who passes the challenge, not tied to an account),
 * so it doesn't need the same protection as a proxy password or a
 * future private-tracker login cookie. See TorrentPrivateTrackerStore
 * (private-tracker credentials) for where that DOES apply encryption.
 */
object TorrentWafCookieStore {
    private const val STORE_ID = "streamx_torrent_waf_cookies"
    private const val DEFAULT_TTL_SECONDS = 25 * 60 // matches wafCookieStore.js's default
    private const val MAX_TTL_SECONDS = 60 * 60      // matches wafCookieStore.js's ceiling

    private val store: MMKV by lazy { MMKV.mmkvWithID(STORE_ID) }

    data class CachedCookie(
        val cookie: String,
        val userAgent: String,
        val storedAtMillis: Long,
        val ttlSeconds: Int
    ) {
        /** Seconds remaining before this entry should be considered stale. Never negative. */
        fun secondsRemaining(nowMillis: Long = System.currentTimeMillis()): Long {
            val elapsedSeconds = (nowMillis - storedAtMillis) / 1000
            return (ttlSeconds - elapsedSeconds).coerceAtLeast(0)
        }

        fun isFresh(nowMillis: Long = System.currentTimeMillis()): Boolean = secondsRemaining(nowMillis) > 0
    }

    /**
     * Stores a solved cookie for [domain]. [ttlSeconds] should come from
     * the same solve call that obtained the cookie (WafCookieResolver
     * knows the real solve time); clamped to [60, MAX_TTL_SECONDS] the
     * same way wafCookieStore.js clamps server-side, so a caller passing
     * an unreasonable value (or omitting it) can't wedge a stale entry
     * in for hours or vanish it immediately.
     */
    fun put(domain: String, cookie: String, userAgent: String, ttlSeconds: Int = DEFAULT_TTL_SECONDS) {
        if (domain.isBlank() || cookie.isBlank()) return
        val clampedTtl = ttlSeconds.coerceIn(60, MAX_TTL_SECONDS)
        val entry = CachedCookie(cookie, userAgent, System.currentTimeMillis(), clampedTtl)
        store.encode(key(domain), encode(entry))
    }

    /**
     * Returns the cached entry for [domain] regardless of freshness — a
     * caller wanting "is this still usable" should check
     * [CachedCookie.isFresh] itself, or use [getFresh] instead. Returning
     * even a stale entry (rather than null) lets a proactive-refresh
     * caller distinguish "never solved" from "solved but expiring" —
     * see [needsRefresh].
     */
    fun get(domain: String): CachedCookie? {
        if (domain.isBlank()) return null
        val raw = store.decodeString(key(domain)) ?: return null
        return decode(raw)
    }

    /** Returns the cached entry for [domain] ONLY if it's still fresh, null otherwise. */
    fun getFresh(domain: String): CachedCookie? = get(domain)?.takeIf { it.isFresh() }

    /**
     * True if [domain] has no cached cookie at all, or its remaining TTL
     * is under [thresholdSeconds] — the same "needs a proactive
     * refresh soon" check wafCookieStore.js's listWafCookieStatus()
     * effectively powers server-side, done here entirely locally.
     */
    fun needsRefresh(domain: String, thresholdSeconds: Long): Boolean {
        val entry = get(domain) ?: return true
        return entry.secondsRemaining() < thresholdSeconds
    }

    /** Removes the cached entry for [domain], e.g. after a request using it still came back blocked. */
    fun invalidate(domain: String) {
        if (domain.isBlank()) return
        store.remove(key(domain))
    }

    /** Every domain this store currently has ANY entry for (fresh or stale) — used to drive the refresh loop. */
    fun allDomains(): List<String> =
        store.allKeys()?.filter { it.startsWith(KEY_PREFIX) }?.map { it.removePrefix(KEY_PREFIX) } ?: emptyList()

    private const val KEY_PREFIX = "waf:"
    private fun key(domain: String) = "$KEY_PREFIX${domain.lowercase()}"

    // Plain "|"-delimited encoding — deliberately not JSON, to avoid
    // pulling Gson/org.json into a tiny 4-field record. Cookie values
    // can legitimately contain almost any printable character except a
    // few reserved ones (Cookie header syntax forbids raw ';' as a
    // separator already, but not '|'), so a real WAF-clearance cookie
    // could theoretically contain '|' — the encoding below escapes it
    // to keep the format unambiguous either way.
    private fun encode(entry: CachedCookie): String = listOf(
        entry.cookie.replace("|", "\\|"),
        entry.userAgent.replace("|", "\\|"),
        entry.storedAtMillis.toString(),
        entry.ttlSeconds.toString()
    ).joinToString("|")

    private fun decode(raw: String): CachedCookie? {
        // Split on unescaped '|' only.
        val parts = Regex("""(?<!\\)\|""").split(raw)
        if (parts.size != 4) return null
        return try {
            CachedCookie(
                cookie = parts[0].replace("\\|", "|"),
                userAgent = parts[1].replace("\\|", "|"),
                storedAtMillis = parts[2].toLong(),
                ttlSeconds = parts[3].toInt()
            )
        } catch (e: NumberFormatException) {
            null
        }
    }
}
