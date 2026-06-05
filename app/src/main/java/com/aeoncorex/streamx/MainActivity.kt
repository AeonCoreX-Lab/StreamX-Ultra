package com.aeoncorex.streamx

import android.content.Intent
import android.net.Uri
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.aeoncorex.streamx.ads.AdManager
import com.aeoncorex.streamx.navigation.AppNavigation
import com.aeoncorex.streamx.streaming.AddonManager
import com.aeoncorex.streamx.streaming.AddonStorage
import com.aeoncorex.streamx.streaming.DefaultAddonManager
import com.aeoncorex.streamx.streaming.StreamCache
import com.aeoncorex.streamx.streaming.registry.AddonRegistry
import com.aeoncorex.streamx.ui.music.MusicManager
import com.aeoncorex.streamx.ui.movie.TorrentEngine
import com.aeoncorex.streamx.ui.theme.StreamXUltraTheme
import com.aeoncorex.streamx.ui.theme.ThemeViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val TAG = "MainActivity"

    // ── Default addon source ──────────────────────────────────────────────────
    private val DEFAULT_ADDON_AUTHOR = "streamx"
    private val DEFAULT_ADDON_URL    =
        "https://aeoncorex-lab.github.io/streamx-addons"

    // ── Pending deeplink (received before UI is ready) ────────────────────────
    private var pendingInstallUrl: String? = null

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { }

    // ═══════════════════════════════════════════════════════════════════════
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        // ── Init order matters ────────────────────────────────────────────────
        AdManager.initialize(application)
        AddonStorage.init(applicationContext)
        StreamCache.init(applicationContext)

        // ── Handle deeplink if app was opened via streamx:// ──────────────────
        handleIntent(intent)

        // ── Background tasks ──────────────────────────────────────────────────
        lifecycleScope.launch(Dispatchers.IO) {
            runCatching { StreamCache.evictExpired() }
            runCatching { TorrentEngine.clearCache(applicationContext) }

            // Seed official addons on first launch
            DefaultAddonManager.seedIfFirstLaunch(applicationContext)

            // Refresh manifest cache
            AddonManager.initialize()

            // Retry failed addon installs
            DefaultAddonManager.retryPending()
        }

        checkPermissions()
        MusicManager.initialize(applicationContext)

        setContent {
            val themeViewModel: ThemeViewModel = viewModel(factory = object : ViewModelProvider.Factory {
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    @Suppress("UNCHECKED_CAST")
                    return ThemeViewModel(applicationContext) as T
                }
            })
            val currentTheme by themeViewModel.theme
            StreamXUltraTheme(appTheme = currentTheme) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color    = MaterialTheme.colorScheme.background
                ) {
                    AppNavigation(
                        themeViewModel   = themeViewModel,
                        pendingInstallUrl = pendingInstallUrl
                    )
                }
            }
        }
    }

    // ── Called when app is already running and gets a new intent ──────────────
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    // ── Deeplink handler ──────────────────────────────────────────────────────
    // Handles:
    //   streamx://install-addon?url=https://addon.vercel.app/manifest.json
    //   streamx://add-source?url=https://github.io/providers&author=name
    //   https://aeoncorex-lab.github.io/streamx-addons/install?url=...
    private fun handleIntent(intent: Intent) {
        val uri = intent.data ?: return

        Log.d(TAG, "Deeplink: $uri")

        when {
            // ── Install HTTP addon ────────────────────────────────────────────
            uri.scheme == "streamx" && uri.host == "install-addon" -> {
                val url = uri.getQueryParameter("url") ?: return
                Log.d(TAG, "Install addon deeplink: $url")
                pendingInstallUrl = url
                installAddonFromUrl(url)
            }

            // ── Add bundle source ─────────────────────────────────────────────
            uri.scheme == "streamx" && uri.host == "add-source" -> {
                val url    = uri.getQueryParameter("url")    ?: return
                val author = uri.getQueryParameter("author") ?: return
                Log.d(TAG, "Add source deeplink: $author → $url")
                addBundleSource(author, url)
            }

            // ── HTTPS fallback deeplink from catalog website ───────────────────
            uri.scheme == "https" && uri.host == "aeoncorex-lab.github.io"
                && uri.path?.startsWith("/streamx-addons/install") == true -> {
                val url = uri.getQueryParameter("url") ?: return
                Log.d(TAG, "Web install deeplink: $url")
                pendingInstallUrl = url
                installAddonFromUrl(url)
            }
        }
    }

    private fun installAddonFromUrl(url: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val normalized = AddonRegistry.normalizeManifestUrl(url)
                val desc = AddonRegistry.installByUrl(normalized)
                AddonStorage.saveHttpAddon(desc)
                Log.d(TAG, "Addon auto-installed from deeplink: ${desc.manifest.name}")
            } catch (e: Exception) {
                Log.w(TAG, "Auto-install failed: ${e.message}")
                // pendingInstallUrl stays set → AppNavigation opens AddonScreen
                // and the screen will show the install dialog with the URL
            }
        }
    }

    private fun addBundleSource(author: String, url: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            AddonStorage.addSource(author, url)
            AddonManager.initialize()
            Log.d(TAG, "Bundle source added: $author → $url")
        }
    }

    private fun checkPermissions() {
        val permissions = mutableListOf(
            Manifest.permission.RECORD_AUDIO,
        )
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.S_V2) {
            permissions += listOf(
                Manifest.permission.WRITE_EXTERNAL_STORAGE,
                Manifest.permission.READ_EXTERNAL_STORAGE
            )
        } else {
            permissions += Manifest.permission.READ_MEDIA_VIDEO
            permissions += Manifest.permission.READ_MEDIA_AUDIO
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions += Manifest.permission.POST_NOTIFICATIONS
        }
        val notGranted = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (notGranted.isNotEmpty()) permissionLauncher.launch(notGranted.toTypedArray())
    }

    override fun onDestroy() {
        super.onDestroy()
        MusicManager.release()
    }
}
