package com.aeoncorex.streamx.ui.movie

import android.util.Log
import com.aeoncorex.streamx.backup.BackupManager
import com.aeoncorex.streamx.streaming.IndexerNative
import com.aeoncorex.streamx.streaming.ProxyKind
import com.tencent.mmkv.MMKV
import java.security.SecureRandom

/**
 * Persistent storage for the user's proxy settings (HTTP / SOCKS4 /
 * SOCKS5 — see IndexerNative.setProxy()), backed by MMKV with AES
 * encryption applied to the whole store, since it holds a password
 * field.
 *
 * ENCRYPTION KEY NOTE: MMKV's cryptKey is generated once (a random
 * 16-byte value) and itself stored in a plain (non-encrypted) MMKV
 * instance under [KEY_STORE_ID] the first time this object is used, then
 * reused on every subsequent launch. This protects the proxy password
 * from casual inspection (e.g. someone browsing the app's data
 * directory) but — like any on-device symmetric key an app can derive
 * without user input — would not withstand a determined attacker with
 * root access to the device, since the key itself has to live somewhere
 * readable by the app's own process. This is a materially better bar
 * than the previous state (nothing was persisted at all), not a claim
 * of hardware-backed security.
 *
 * Future private-tracker credentials (see the app's roadmap) should use
 * this same store/pattern rather than introducing a second MMKV
 * instance — one encrypted store for all "user's own service login"
 * secrets keeps the key-management story in one place.
 */
object ProxySettingsStore {
    private const val TAG = "ProxySettingsStore"
    private const val STORE_ID = "streamx_secure_settings"
    private const val KEY_STORE_ID = "streamx_key_store" // unencrypted, holds only the crypt key

    private const val KEY_PROXY_ENABLED = "proxy_enabled"
    private const val KEY_PROXY_KIND = "proxy_kind"
    private const val KEY_PROXY_HOST = "proxy_host"
    private const val KEY_PROXY_PORT = "proxy_port"
    private const val KEY_PROXY_USERNAME = "proxy_username"
    private const val KEY_PROXY_PASSWORD = "proxy_password"

    private val store: MMKV by lazy {
        val cryptKey = getOrCreateCryptKey()
        MMKV.mmkvWithID(STORE_ID, MMKV.SINGLE_PROCESS_MODE, cryptKey)
    }

    private fun getOrCreateCryptKey(): String {
        val keyStore = MMKV.mmkvWithID(KEY_STORE_ID) // no encryption on this one — see class doc
        val existing = keyStore.decodeString("crypt_key")
        if (!existing.isNullOrEmpty()) return existing

        val randomBytes = ByteArray(16)
        SecureRandom().nextBytes(randomBytes)
        val newKey = randomBytes.joinToString("") { "%02x".format(it) }
        keyStore.encode("crypt_key", newKey)
        return newKey
    }

    // ── Public API ────────────────────────────────────────────────────────────

    data class ProxySettings(
        val enabled: Boolean,
        val kind: ProxyKind,
        val host: String,
        val port: Int,
        val username: String,
        val password: String
    )

    /** Current saved settings, or null if the user has never configured a proxy. */
    fun get(): ProxySettings? {
        if (!store.decodeBool(KEY_PROXY_ENABLED, false)) return null
        val host = store.decodeString(KEY_PROXY_HOST) ?: return null
        val kindWire = store.decodeString(KEY_PROXY_KIND) ?: return null
        val kind = ProxyKind.entries.firstOrNull { it.wireValue == kindWire } ?: return null
        return ProxySettings(
            enabled = true,
            kind = kind,
            host = host,
            port = store.decodeInt(KEY_PROXY_PORT, 0),
            username = store.decodeString(KEY_PROXY_USERNAME).orEmpty(),
            password = store.decodeString(KEY_PROXY_PASSWORD).orEmpty()
        )
    }

    /**
     * Saves the given settings AND immediately activates them via
     * IndexerNative.setProxy(). Returns true if Rust accepted the proxy
     * config (see IndexerNative.setProxy()'s own return-value doc) —
     * the settings are saved either way, so a rejected config can still
     * be corrected by the user without losing what they typed.
     *
     * @param context if provided, triggers an automatic background
     *   backup to Google Drive after saving locally (see
     *   BackupManager.syncNow() — collect BackupManager.status for a
     *   real-time indicator, e.g. in the Profile screen). Pass null to
     *   skip this (e.g. when applying a just-downloaded restore, where
     *   re-uploading immediately would be redundant — see
     *   BackupManager.applyPayload(), which does exactly that).
     */
    fun save(settings: ProxySettings, context: android.content.Context? = null): Boolean {
        store.encode(KEY_PROXY_ENABLED, settings.enabled)
        store.encode(KEY_PROXY_KIND, settings.kind.wireValue)
        store.encode(KEY_PROXY_HOST, settings.host)
        store.encode(KEY_PROXY_PORT, settings.port)
        store.encode(KEY_PROXY_USERNAME, settings.username)
        store.encode(KEY_PROXY_PASSWORD, settings.password)

        val accepted = if (settings.enabled) {
            IndexerNative.setProxy(settings.kind, settings.host, settings.port, settings.username, settings.password)
        } else {
            IndexerNative.clearProxy()
            true
        }

        // Automatic background backup — see BackupManager.syncNow() doc
        // comment. Fire-and-forget: local save/activation above already
        // succeeded regardless of backup outcome, which is reported
        // separately via BackupManager.status.
        context?.let { BackupManager.syncNow(it) }

        return accepted
    }

    /** Clears the saved proxy and disables it in Rust. */
    fun clear() {
        store.encode(KEY_PROXY_ENABLED, false)
        store.remove(KEY_PROXY_HOST)
        store.remove(KEY_PROXY_USERNAME)
        store.remove(KEY_PROXY_PASSWORD)
        IndexerNative.clearProxy()
    }

    /**
     * Re-applies the saved proxy setting into Rust's in-memory state —
     * call once at app startup (Rust does not persist proxy config
     * itself; see indexer/proxy/mod.rs's module doc). No-op if no
     * proxy was ever saved or it was saved as disabled.
     */
    fun restoreIntoNative() {
        val settings = get() ?: return
        val ok = IndexerNative.setProxy(settings.kind, settings.host, settings.port, settings.username, settings.password)
        Log.d(TAG, "Restored proxy on startup: ${settings.kind} ${settings.host}:${settings.port}, accepted=$ok")
    }
}
