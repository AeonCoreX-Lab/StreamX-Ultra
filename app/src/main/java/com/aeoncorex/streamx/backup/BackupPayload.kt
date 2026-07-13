package com.aeoncorex.streamx.backup

import com.google.gson.annotations.SerializedName

/**
 * The single JSON document backed up to / restored from the user's
 * Google Drive appdata folder (see BackupManager.kt).
 *
 * DESIGN: every feature's settings live here as an OPTIONAL field.
 * When a new backed-up feature is added later (e.g. private-tracker
 * credentials), add a new nullable property below — do NOT change or
 * remove existing fields, and do NOT bump [version] unless the MEANING
 * of an existing field changes (adding a new optional field is not a
 * breaking change; older app versions restoring a newer backup simply
 * ignore fields they don't recognize, and newer app versions restoring
 * an older backup see null for fields that didn't exist yet).
 *
 * [version] exists for the rare case a field's semantics must change
 * incompatibly — BackupManager can then branch on it during restore.
 */
data class BackupPayload(
    @SerializedName("version")
    val version: Int = CURRENT_VERSION,

    @SerializedName("proxy_settings")
    val proxySettings: BackedUpProxySettings? = null,

    // Future fields go here, e.g.:
    // @SerializedName("private_trackers")
    // val privateTrackers: List<BackedUpTrackerCredential>? = null,

    @SerializedName("updated_at")
    val updatedAt: Long = System.currentTimeMillis()
) {
    companion object {
        const val CURRENT_VERSION = 1
    }
}

/**
 * Plain (unencrypted-at-this-layer) mirror of
 * ProxySettingsStore.ProxySettings — kept as a separate type rather
 * than reusing that class directly so this backup schema doesn't
 * silently change shape if ProxySettingsStore's internal class ever
 * does. Field names match Gson's serialized-name annotations exactly,
 * independent of Kotlin property naming conventions on either side.
 */
data class BackedUpProxySettings(
    @SerializedName("enabled")   val enabled: Boolean,
    @SerializedName("kind")      val kind: String, // ProxyKind.wireValue ("http"/"socks4"/"socks5")
    @SerializedName("host")      val host: String,
    @SerializedName("port")      val port: Int,
    @SerializedName("username")  val username: String,
    @SerializedName("password")  val password: String
)
