package com.aeoncorex.streamx.streaming

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import org.json.JSONArray

// ═══════════════════════════════════════════════════════════════════════════
//  AddonManager.kt
//  Manages provider sources + install/uninstall/update of JS addons.
//  Mirrors ExtensionManager.ts from vega-app.
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
                    arr.getJSONObject(i).getString("value"), active.author
                )
            )
        }

        AddonStorage.setManifestCache(active.author, addons)
        Log.d(TAG, "Manifest fetched: ${addons.size} addons")
        addons
    }

    // ── Install / uninstall / update ──────────────────────────────────────────

    suspend fun install(addon: AddonInfo): Unit = withContext(Dispatchers.IO) {
        Log.d(TAG, "Installing ${addon.displayName}")
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
        val installed  = AddonStorage.getInstalled().filter { it.sourceAuthor == author }
        val available  = AddonStorage.getManifestCache(author)
        return installed.filter { inst ->
            available.any { a -> a.value == inst.value && a.version != inst.version }
        }
    }

    // ── Module retrieval (used by JsStreamProviderEngine) ─────────────────────

    fun getModules(value: String, sourceAuthor: String? = null): AddonModule? =
        AddonStorage.getModules(value, sourceAuthor)

    // ── App startup ───────────────────────────────────────────────────────────

    suspend fun initialize() = withContext(Dispatchers.IO) {
        val source    = AddonStorage.getDefaultSource() ?: return@withContext
        val installed = AddonStorage.getInstalled()
        Log.d(TAG, "AddonManager init: ${installed.size} installed addons")

        if (AddonStorage.isManifestExpired(source.author)) {
            runCatching { fetchManifest(source) }
                .onFailure { Log.w(TAG, "Manifest refresh on init failed: ${it.message}") }
        }
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private suspend fun downloadModules(
        baseUrl:      String,
        author:       String,
        providerKey:  String,
        version:      String
    ) {
        val base    = baseUrl.trimEnd('/')
        val results = MODULE_NAMES.map { modName ->
            modName to runCatching {
                val url  = "$base/dist/$providerKey/$modName.js"
                val code = HttpClient.getJson(url)  // text, not JSON — just plain GET
                    ?: HttpClient.getHtml(url)       // fallback
                Log.d(TAG, "  $modName.js: ${code?.length ?: 0} chars")
                code
            }.getOrNull()
        }.toMap()

        if (results.values.all { it == null }) {
            throw Exception("No modules found for provider: $providerKey at $base/dist/$providerKey/")
        }

        val mod = AddonModule(
            value        = providerKey,
            sourceAuthor = author,
            version      = version,
            cachedAt     = System.currentTimeMillis(),
            catalog      = results["catalog"],
            posts        = results["posts"],
            meta         = results["meta"],
            stream       = results["stream"],
            episodes     = results["episodes"]
        )
        AddonStorage.cacheModules(mod)
        Log.d(TAG, "Modules cached for $providerKey")
    }
}
