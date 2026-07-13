package com.aeoncorex.streamx.backup

import android.accounts.Account
import android.content.Context
import android.util.Log
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential
import com.google.api.client.http.ByteArrayContent
import com.google.api.client.http.javanet.NetHttpTransport
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.drive.Drive
import com.google.api.services.drive.DriveScopes
import com.google.api.services.drive.model.File as DriveFile
import com.google.gson.Gson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

/**
 * Automatic settings backup to the user's Google Drive "appdata" folder
 * — a hidden, app-private space (not visible in the user's regular
 * Drive UI, not readable by other apps) that Drive's API exposes
 * specifically for this kind of per-app settings sync.
 *
 * REUSES the app's existing Google Sign-In (see AuthScreen.kt, which
 * already signs the user in for Firebase Auth) — the DRIVE_APPDATA
 * scope was added to that same sign-in flow, so no separate
 * authentication step is needed here. If the signed-in account somehow
 * lacks the Drive scope (e.g. they signed in before this feature
 * existed and haven't re-authenticated), sync attempts fail gracefully
 * with [BackupStatus.Failed] rather than crashing — see syncNow()'s
 * error handling.
 *
 * SECURITY NOTE: this uploads plain-text host/port/username/password
 * (see BackupPayload.kt). That's a deliberate, documented trade-off —
 * the appdata folder is only reachable by (a) this app, running as the
 * signed-in user, or (b) Google itself, exactly like every other
 * Google Sign-In-based per-app cloud save. It is NOT visible to other
 * apps, other Drive users, or the account owner's own Drive file
 * browser. This is a materially different exposure than, say, writing
 * the file to public Drive storage or external storage would be.
 *
 * EXTENSIBILITY: every settings store that wants automatic backup
 * should call [syncNow] after saving locally — see
 * ProxySettingsStore.save(), which does exactly that. syncNow() reads
 * the CURRENT state of every backed-up feature (right now just
 * ProxySettingsStore) and uploads one consolidated BackupPayload, so
 * multiple stores calling it in quick succession naturally coalesce
 * into a single upload each time (protected by [syncMutex]).
 */
object BackupManager {
    private const val TAG = "BackupManager"
    private const val BACKUP_FILENAME = "streamx_backup.json"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val syncMutex = Mutex()
    private val gson = Gson()

    private val _status = MutableStateFlow<BackupStatus>(BackupStatus.Idle)
    /** Collect this in the Profile screen for a real-time sync indicator. */
    val status: StateFlow<BackupStatus> = _status.asStateFlow()

    /**
     * Triggers an immediate backup upload in the background. Safe to
     * call frequently (e.g. once per settings save) — concurrent calls
     * coalesce via [syncMutex] rather than racing each other.
     *
     * This is fire-and-forget from the caller's perspective (returns
     * immediately); observe [status] for the outcome.
     */
    fun syncNow(context: Context) {
        scope.launch {
            syncMutex.withLock {
                _status.value = BackupStatus.Syncing
                try {
                    val drive = buildDriveService(context)
                        ?: run {
                            _status.value = BackupStatus.Failed("Not signed in to Google")
                            return@launch
                        }
                    val payload = buildCurrentPayload()
                    uploadPayload(drive, payload)
                    _status.value = BackupStatus.Synced(payload.updatedAt)
                    Log.d(TAG, "Backup synced successfully")
                } catch (e: Exception) {
                    Log.w(TAG, "Backup sync failed: ${e.message}")
                    _status.value = BackupStatus.Failed(e.message ?: "Unknown error")
                }
            }
        }
    }

    /**
     * Downloads and restores the backup, applying it to every relevant
     * settings store (currently just ProxySettingsStore). Call this
     * from a "Restore from Google Drive" action — e.g. on first launch
     * after a fresh install/new device, or a manual button in Settings.
     *
     * Returns true if a backup was found and restored, false if no
     * backup exists yet for this account (not an error — a brand new
     * account simply has nothing to restore) or the user isn't signed
     * in. Exceptions during the actual restore are caught and reported
     * via [status] the same way syncNow() does.
     */
    suspend fun restoreFromBackup(context: Context): Boolean = withContext(Dispatchers.IO) {
        _status.value = BackupStatus.Syncing
        try {
            val drive = buildDriveService(context)
                ?: run {
                    _status.value = BackupStatus.Failed("Not signed in to Google")
                    return@withContext false
                }

            val fileId = findBackupFileId(drive) ?: run {
                _status.value = BackupStatus.Idle
                return@withContext false
            }

            val bytes = ByteArrayOutputStream().also { out ->
                drive.files().get(fileId).executeMediaAndDownloadTo(out)
            }.toByteArray()

            val payload = gson.fromJson(String(bytes), BackupPayload::class.java)
            applyPayload(payload)

            _status.value = BackupStatus.Synced(payload.updatedAt)
            true
        } catch (e: Exception) {
            Log.w(TAG, "Restore failed: ${e.message}")
            _status.value = BackupStatus.Failed(e.message ?: "Unknown error")
            false
        }
    }

    // ── Payload assembly / application ───────────────────────────────────────
    //
    // These two functions are the ONLY places that need editing when a
    // new backed-up feature is added — everything else (upload,
    // download, status tracking) is already generic.

    private fun buildCurrentPayload(): BackupPayload {
        val proxy = com.aeoncorex.streamx.ui.movie.ProxySettingsStore.get()
        return BackupPayload(
            proxySettings = proxy?.let {
                BackedUpProxySettings(
                    enabled = it.enabled,
                    kind = it.kind.wireValue,
                    host = it.host,
                    port = it.port,
                    username = it.username,
                    password = it.password
                )
            }
            // Future: assemble other stores' current state here too.
        )
    }

    private fun applyPayload(payload: BackupPayload) {
        payload.proxySettings?.let { backed ->
            val kind = com.aeoncorex.streamx.streaming.ProxyKind.entries
                .firstOrNull { it.wireValue == backed.kind } ?: return@let
            // Uses the store's own save() so the restored proxy is both
            // persisted locally AND immediately activated in Rust —
            // consistent with any other settings change.
            com.aeoncorex.streamx.ui.movie.ProxySettingsStore.save(
                com.aeoncorex.streamx.ui.movie.ProxySettingsStore.ProxySettings(
                    enabled = backed.enabled,
                    kind = kind,
                    host = backed.host,
                    port = backed.port,
                    username = backed.username,
                    password = backed.password
                )
            )
        }
        // Future: apply other stores' restored state here too.
    }

    // ── Drive plumbing ───────────────────────────────────────────────────────

    private fun buildDriveService(context: Context): Drive? {
        val account = GoogleSignIn.getLastSignedInAccount(context)?.account ?: return null
        val credential = GoogleAccountCredential.usingOAuth2(
            context, listOf(DriveScopes.DRIVE_APPDATA)
        ).apply { selectedAccount = account as Account }

        return Drive.Builder(
            NetHttpTransport(),
            GsonFactory.getDefaultInstance(),
            credential
        ).setApplicationName("StreamX Ultra").build()
    }

    private fun findBackupFileId(drive: Drive): String? {
        val result = drive.files().list()
            .setSpaces("appDataFolder")
            .setQ("name = '$BACKUP_FILENAME'")
            .setFields("files(id, name)")
            .execute()
        return result.files?.firstOrNull()?.id
    }

    private fun uploadPayload(drive: Drive, payload: BackupPayload) {
        val json = gson.toJson(payload)
        val content = ByteArrayContent("application/json", json.toByteArray())
        val existingId = findBackupFileId(drive)

        if (existingId != null) {
            drive.files().update(existingId, null, content).execute()
        } else {
            val metadata = DriveFile().apply {
                name = BACKUP_FILENAME
                parents = listOf("appDataFolder")
            }
            drive.files().create(metadata, content).setFields("id").execute()
        }
    }
}

/** Real-time backup state — collect [BackupManager.status] for this. */
sealed class BackupStatus {
    data object Idle : BackupStatus()
    data object Syncing : BackupStatus()
    data class Synced(val atMillis: Long) : BackupStatus()
    data class Failed(val reason: String) : BackupStatus()
}
