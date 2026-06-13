package com.aeoncorex.streamx.streaming

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray

// ═══════════════════════════════════════════════════════════════════════════
//  AddonManager.kt  —  FIXED v2
//
//  FIX 1: initialize() now calls DefaultAddonManager.ensureDefaultSource()
//    so the default source is always present, even after user removes it
//    or after a storage clear.  This stops the permanent
//    "No addon source configured" error on the Addons screen.
//
//  FIX 2: initialize() detects installed addons whose stream.js module is
//    missing from cache (e.g., because the first install used a wrong URL)
//    and silently re-downloads them.  This covers the case where the app
//    was seeded with himanshu8443 URLs that returned 404, leaving the
//    addon "installed" but with no cached JS — so fetchFromBundleAddon
//    always returned empty.
//
//  FIX 3: downloadModules() now also tries HttpClient.getText() as a
//    second fallback (some servers return JS with text/plain content-type).
// ═══════════════════════════════════════════════════════════════════════════
object AddonManager {
    private const val TAG = "AddonManager"

    private val MODULE_NAMES = listOf("catalog", "posts", "meta", "stream", "episodes")

    // ── Source management ─────────────────────────────────────────────────────

    fun addSource(author: String, url: String) {
        AddonStorage.addSource(author, url.trimEnd('/'))
        Log.d(TAG, "Source added: $author → $url")
    }

    fun removeSource(author: String) = AddonStorage.removeSource(author)

    fun setDefaultSource(author: String) = AddonStorage.setDefaultSource(author)

    fun getSources() = AddonStorage.getSources()

    fun getDefaultSource() = AddonStorage.getDefaultSource()

    // ── Manifest fetch ────────────────────────────────────────────────────────

    suspend fun fetchManifest(
        source: AddonSource? = null,
        force:  Boolean      = false
    ): List<AddonInfo> = withContext(Dispatchers.IO) {
        val active = source ?: AddonStorage.getDefaultSource()
            ?: throw IllegalStateException("No addon source configured")

        if (!force && !AddonStorage.isManifestExpired(active.author)) {
            val cached = AddonStorage.getManifestCache(active.author)
            if (cached.isNotEmpty()) return@withContext cached
        }

        val url = "${active.url.trimEnd('/')}/manifest.json"
        Log.d(TAG, "Fetching manifest: $url")

        val json = HttpClient.getJson(url)
            ?: throw Exception("Failed to fetch manifest from $url")

        val arr    = JSONArray(json)
        val addons = (0 until arr.length()).map { i ->
            AddonInfo.fromJson(arr.getJSONObject(i)).copy(
                sourceAuthor = active.author,
                sourceUrl    = active.url,
                installed    = AddonStorage.isInstalled(
                    arr.getJSONObject(i).optString("value", ""), active.author
                )
            )
        }

        AddonStorage.setManifestCache(active.author, addons)
        Log.d(TAG, "Manifest fetched: ${addons.size} addons from ${active.author}")
        addons
    }

    // ── Install / uninstall / update ──────────────────────────────────────────

    suspend fun install(addon: AddonInfo): Unit = withContext(Dispatchers.IO) {
        Log.d(TAG, "Installing ${addon.displayName} from ${addon.sourceUrl}")
        downloadModules(addon.sourceUrl, addon.sourceAuthor, addon.value, addon.version)
        AddonStorage.install(addon)
        Log.d(TAG, "Installed ${addon.displayName}")
    }

    fun uninstall(value: String, sourceAuthor: String? = null) {
        AddonStorage.uninstall(value, sourceAuthor)
        Log.d(TAG, "Uninstalled $value")
    }

    suspend fun update(addon: AddonInfo) {
        Log.d(TAG, "Updating ${addon.displayName}")
        downloadModules(addon.sourceUrl, addon.sourceAuthor, addon.value, addon.version)
        AddonStorage.install(addon)
        Log.d(TAG, "Updated ${addon.displayName}")
    }

    fun getInstalled(): List<AddonInfo> = AddonStorage.getInstalled()

    fun getUpdateable(author: String): List<AddonInfo> {
        val installed = AddonStorage.getInstalled().filter { it.sourceAuthor == author }
        val available = AddonStorage.getManifestCache(author)
        return installed.filter { inst ->
            available.any { a -> a.value == inst.value && a.version != inst.version }
        }
    }

    // ── Module retrieval (used by JsStreamProviderEngine) ─────────────────────

    fun getModules(value: String, sourceAuthor: String? = null): AddonModule? =
        AddonStorage.getModules(value, sourceAuthor)

    // ── App startup ───────────────────────────────────────────────────────────
    //
    // FIX: ensureDefaultSource() is called first so the source is ALWAYS
    // present before any manifest fetch or module re-download is attempted.
    // Then we detect installed addons with missing stream.js and re-download.

    suspend fun initialize() = withContext(Dispatchers.IO) {
        // FIX 1: ensure source is always present
        DefaultAddonManager.ensureDefaultSource()

        val source    = AddonStorage.getDefaultSource() ?: return@withContext
        val installed = AddonStorage.getInstalled()
        Log.d(TAG, "AddonManager init: ${installed.size} installed addons, source=${source.author}")

        // Refresh manifest if stale
        if (AddonStorage.isManifestExpired(source.author)) {
            runCatching { fetchManifest(source) }
                .onFailure { Log.w(TAG, "Manifest refresh on init failed: ${it.message}") }
        }

        // FIX 2: detect addons installed with wrong URL (no stream.js cached)
        // and silently re-download them using the current correct source URL.
        val needsRedownload = installed.filter { addon ->
            AddonStorage.getModules(addon.value, addon.sourceAuthor)?.stream.isNullOrBlank()
        }

        if (needsRedownload.isNotEmpty()) {
            Log.d(TAG, "Re-downloading ${needsRedownload.size} addons missing stream.js")
            for (addon in needsRedownload) {
                // Always use canonical source URL for re-download
                val fixedAddon = addon.copy(
                    sourceAuthor = DefaultAddonManager.DEFAULT_AUTHOR,
                    sourceUrl    = DefaultAddonManager.DEFAULT_SOURCE_URL
                )
                runCatching { downloadModules(
                    fixedAddon.sourceUrl,
                    fixedAddon.sourceAuthor,
                    fixedAddon.value,
                    fixedAddon.version
                )}.onSuccess {
                    Log.d(TAG, "  ✓ Re-downloaded stream.js for ${addon.displayName}")
                    // Update storage record with corrected source info
                    AddonStorage.install(fixedAddon.copy(installed = true))
                }.onFailure {
                    Log.w(TAG, "  ✗ Re-download failed for ${addon.displayName}: ${it.message}")
                }
            }
        }
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    internal suspend fun downloadModules(
        baseUrl:     String,
        author:      String,
        providerKey: String,
        version:     String
    ) {
        val base    = baseUrl.trimEnd('/')
        val results = MODULE_NAMES.map { modName ->
            modName to runCatching {
                val url  = "$base/dist/$providerKey/$modName.js"
                // FIX 3: try getJson first (returns null on non-200), then getText
                val code = HttpClient.getJson(url)
                    ?: HttpClient.getHtml(url)    // fallback: some servers use text/plain
                Log.d(TAG, "  $modName.js from $url: ${code?.length ?: 0} chars")
                code
            }.getOrNull()
        }.toMap()

        if (results.values.all { it == null }) {
            throw Exception(
                "No modules downloaded for '$providerKey' — check that " +
                "$base/dist/$providerKey/stream.js exists on GitHub Pages"
            )
        }

        AddonStorage.cacheModules(AddonModule(
            value        = providerKey,
            sourceAuthor = author,
            version      = version,
            cachedAt     = System.currentTimeMillis(),
            catalog      = results["catalog"],
            posts        = results["posts"],
            meta         = results["meta"],
            stream       = results["stream"],
            episodes     = results["episodes"]
        ))
        Log.d(TAG, "Modules cached for $providerKey (stream.js: ${results["stream"]?.length ?: 0} chars)")
    }
}
