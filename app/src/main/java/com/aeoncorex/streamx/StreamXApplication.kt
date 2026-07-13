package com.aeoncorex.streamx

import android.app.Application
import android.util.Log
import com.aeoncorex.streamx.backup.BackupManager
import com.aeoncorex.streamx.streaming.IndexerNative
import com.aeoncorex.streamx.ui.movie.ProxySettingsStore
import com.tencent.mmkv.MMKV
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * App-wide Application class — previously this project had none (both
 * MMKV and IndexerNative.initialize() require a Context at process
 * startup, and there was nowhere to call them from before now, which is
 * why IndexerNative.initialize() carried a "TODO: wire this into your
 * Application.onCreate()" note — see IndexerNative.kt).
 *
 * Registered via AndroidManifest.xml's <application android:name=...>.
 */
class StreamXApplication : Application() {
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()

        // MMKV must be initialized exactly once, before any get/set call,
        // per Tencent's own setup docs. rootDir is unused by callers —
        // MMKV manages its own storage location internally once initialized.
        MMKV.initialize(this)
        Log.d(TAG, "MMKV initialized")

        // Restores the indexer's on-disk config cache directory (see
        // indexer/config/loader.rs) — was previously never called from
        // anywhere in the app.
        IndexerNative.initialize(this)

        // Restores any saved proxy setting from the previous session —
        // Rust's proxy state (indexer/proxy/mod.rs) is in-memory only and
        // does not survive a process restart on its own, so this must be
        // re-applied on every app launch.
        ProxySettingsStore.restoreIntoNative()

        // If this device has no local proxy setting at all — the
        // signature of either a fresh install or a new device — try
        // pulling one down from the user's Google Drive backup (if
        // they're signed in and one exists). This is the actual
        // cross-device restore path the user asked for: sign in on a
        // new phone, and their proxy setting reappears automatically
        // without them re-entering it. No-ops harmlessly if the user
        // isn't signed in yet (they'll sign in via AuthScreen first;
        // consider also calling BackupManager.restoreFromBackup() right
        // after a successful sign-in there for the case where the app
        // was already running when they signed in).
        if (ProxySettingsStore.get() == null) {
            appScope.launch {
                val restored = BackupManager.restoreFromBackup(this@StreamXApplication)
                Log.d(TAG, "Startup Drive restore attempted, found_backup=$restored")
            }
        }
    }

    companion object {
        private const val TAG = "StreamXApplication"
    }
}
