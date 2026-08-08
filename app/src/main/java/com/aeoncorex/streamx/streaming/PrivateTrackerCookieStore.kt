package com.aeoncorex.streamx.streaming

import com.tencent.mmkv.MMKV
import java.security.SecureRandom

/**
 * Persistent, encrypted storage for private-tracker session cookies —
 * the per-user secret half of the streamx-indexer crate's
 * schema.rs::AuthConfig split (see that file's doc comment for the full
 * design). A site's `auth` block in the registry JSON only ever
 * describes WHAT a site needs (cookie method, login-check selector,
 * user-facing instructions); the actual cookie value the user's own
 * login produced lives ONLY here, on-device, and is never committed to
 * any repo or sent to any StreamX-operated server.
 *
 * ENCRYPTED (unlike TorrentWafCookieStore, which stores anonymous
 * WAF-clearance cookies any visitor gets for passing a bot challenge):
 * a private-tracker cookie is tied to a real user account — ratio,
 * invite history, sometimes payment/donation status. Reuses
 * ProxySettingsStore's exact crypt-key pattern and KEY_STORE_ID (see
 * that file's doc comment, which already earmarked this store for
 * "future private-tracker credentials") rather than introducing a
 * second key-management scheme.
 *
 * How a cookie ends up here: the app shows the user a login screen
 * (TrackerLoginScreen) for each site whose SiteConfig has
 * `auth.method == "cookie"` — a real WebView pointed at that tracker's
 * own site. The user logs in exactly as they would in a normal
 * browser; once login succeeds, the screen captures the resulting
 * session cookie automatically via CookieManager and calls put() below.
 * The user never types a password into this app's own UI, and nothing
 * they type is seen or stored by this app — only the cookie a real
 * login already produced. See AuthConfig's doc comment in schema.rs for
 * why fully automating the login form itself (submitting
 * username/password without a visible page) is deliberately NOT
 * implemented — every login here happens on the tracker's own real
 * page, visibly, with the user in control of it.
 */
object PrivateTrackerCookieStore {
    private const val STORE_ID = "streamx_private_tracker_cookies"
    private const val KEY_STORE_ID = "streamx_key_store" // same store ProxySettingsStore already uses

    private val store: MMKV by lazy {
        val cryptKey = getOrCreateCryptKey()
        MMKV.mmkvWithID(STORE_ID, MMKV.SINGLE_PROCESS_MODE, cryptKey)
    }

    private fun getOrCreateCryptKey(): String {
        val keyStore = MMKV.mmkvWithID(KEY_STORE_ID) // no encryption on this one — see ProxySettingsStore's doc comment
        val existing = keyStore.decodeString("crypt_key")
        if (!existing.isNullOrEmpty()) return existing

        val randomBytes = ByteArray(16)
        SecureRandom().nextBytes(randomBytes)
        val newKey = randomBytes.joinToString("") { "%02x".format(it) }
        keyStore.encode("crypt_key", newKey)
        return newKey
    }

    data class TrackerCredential(
        val cookie: String,
        val savedAtMillis: Long,
        /**
         * Set by the caller after a successful login-check request (see
         * AuthConfig.login_check_path/login_check_selector) — this store
         * doesn't verify anything itself, it just remembers the last
         * outcome so the Settings UI can show "Working" / "Needs
         * re-login" without re-checking on every screen open.
         */
        val lastVerifiedOk: Boolean?
    )

    // ── Public API ────────────────────────────────────────────────────────────

    /** Saves (or overwrites) the cookie for [siteId] — the registry's SiteConfig.id, e.g. "iptorrents". */
    fun put(siteId: String, cookie: String) {
        if (siteId.isBlank() || cookie.isBlank()) return
        val entry = TrackerCredential(cookie, System.currentTimeMillis(), lastVerifiedOk = null)
        store.encode(key(siteId), encode(entry))
    }

    /** The stored cookie + metadata for [siteId], or null if the user hasn't configured this tracker. */
    fun get(siteId: String): TrackerCredential? {
        if (siteId.isBlank()) return null
        val raw = store.decodeString(key(siteId)) ?: return null
        return decode(raw)
    }

    /** Convenience for callers that only need the raw cookie string (e.g. the JNI bridge — see IndexerNative.kt). */
    fun cookieFor(siteId: String): String? = get(siteId)?.cookie

    /**
     * Records the result of a login-check request against
     * AuthConfig.login_check_path/login_check_selector, so the Settings
     * UI can show a status badge without re-checking every time it's
     * opened. Does nothing if [siteId] has no stored cookie (nothing to
     * annotate).
     */
    fun setVerified(siteId: String, ok: Boolean) {
        val existing = get(siteId) ?: return
        store.encode(key(siteId), encode(existing.copy(lastVerifiedOk = ok)))
    }

    /** Removes the stored cookie for [siteId] — e.g. the user tapped "Log out" or "Remove tracker". */
    fun remove(siteId: String) {
        if (siteId.isBlank()) return
        store.remove(key(siteId))
    }

    /** Every site id this store currently has a cookie for. */
    fun allSiteIds(): List<String> =
        store.allKeys()?.filter { it.startsWith(KEY_PREFIX) }?.map { it.removePrefix(KEY_PREFIX) } ?: emptyList()

    /**
     * Every stored (siteId -> cookie) pair, ready to hand across the JNI
     * boundary — see IndexerNative's searchX() functions, which pass
     * this as a JSON object to Rust's MapAuthProvider (lib.rs). Sites
     * with no stored cookie are simply absent from the map (not an
     * empty-string entry) — Rust's cookie_for() treats a missing key and
     * an explicit None identically either way, so there's no behavioral
     * difference, but omitting them keeps the JSON payload small as the
     * private-tracker list in the registry grows.
     */
    fun allCookies(): Map<String, String> =
        allSiteIds().mapNotNull { id -> cookieFor(id)?.let { id to it } }.toMap()

    // ── Internal encoding ────────────────────────────────────────────────────

    private const val KEY_PREFIX = "tracker:"
    private fun key(siteId: String) = "$KEY_PREFIX${siteId.lowercase()}"

    // Same delimited (not JSON) per-entry encoding TorrentWafCookieStore
    // uses, for the same reason — a tiny fixed-shape record doesn't need
    // a JSON library pulled in just for this. lastVerifiedOk is encoded
    // as "1"/"0"/"" (unknown) rather than Kotlin's null, since the plain
    // string format has no native null representation.
    private fun encode(entry: TrackerCredential): String = listOf(
        entry.cookie.replace("|", "\\|"),
        entry.savedAtMillis.toString(),
        when (entry.lastVerifiedOk) { true -> "1"; false -> "0"; null -> "" }
    ).joinToString("|")

    private fun decode(raw: String): TrackerCredential? {
        val parts = Regex("""(?<!\\)\|""").split(raw)
        if (parts.size != 3) return null
        return try {
            TrackerCredential(
                cookie = parts[0].replace("\\|", "|"),
                savedAtMillis = parts[1].toLong(),
                lastVerifiedOk = when (parts[2]) { "1" -> true; "0" -> false; else -> null }
            )
        } catch (e: NumberFormatException) {
            null
        }
    }
}
