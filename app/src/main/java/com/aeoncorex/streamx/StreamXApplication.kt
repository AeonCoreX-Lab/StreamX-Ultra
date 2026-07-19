package com.aeoncorex.streamx

import android.app.Application
import android.util.Log
import com.aeoncorex.streamx.backup.BackupManager
import com.aeoncorex.streamx.streaming.IndexerNative
import com.aeoncorex.streamx.streaming.WorkerStreamProviderEngine
import com.aeoncorex.streamx.ui.movie.ProxySettingsStore
import com.tencent.mmkv.MMKV
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class StreamXApplication : Application() {
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()

        MMKV.initialize(this)
        Log.d(TAG, "MMKV initialized")

        IndexerNative.initialize(this)

        ProxySettingsStore.restoreIntoNative()

        if (ProxySettingsStore.get() == null) {
            appScope.launch {
                val restored = BackupManager.restoreFromBackup(this@StreamXApplication)
                Log.d(TAG, "Startup Drive restore attempted, found_backup=$restored")
            }
        }

        // Give WorkerStreamProviderEngine the Application context so it can
        // spin up an Android WebView for on-device WAF challenge solving when
        // the Cloudflare Worker's headless fetch() hits a 403/challenge page
        // (see WafCookieResolver.kt and WorkerStreamProviderEngine's
        // resolveWithWafRetry() for the full flow). Must be called here,
        // before any fetch() call, because WebView needs a Context and the
        // engine itself is a singleton with no access to one otherwise.
        WorkerStreamProviderEngine.init(this)
        Log.d(TAG, "WorkerStreamProviderEngine initialized")
    }

    companion object {
        private const val TAG = "StreamXApplication"
    }
}
