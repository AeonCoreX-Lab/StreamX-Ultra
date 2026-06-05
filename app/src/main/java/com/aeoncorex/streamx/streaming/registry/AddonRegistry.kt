package com.aeoncorex.streamx.streaming.registry

import android.util.Log
import com.aeoncorex.streamx.streaming.AddonDescriptor
import com.aeoncorex.streamx.streaming.AddonFlags
import com.aeoncorex.streamx.streaming.AddonInfo
import com.aeoncorex.streamx.streaming.AddonKind
import com.aeoncorex.streamx.streaming.AddonManifest
import com.aeoncorex.streamx.streaming.HttpClient
import org.json.JSONArray
import org.json.JSONObject

// ═════════════════════════════════════════════════════════════════════════════
//  AddonRegistry.kt  —  Community Addon Discovery
//  Mirrors Stremio's api.strem.io/addonscollection.json system
// ═════════════════════════════════════════════════════════════════════════════
object AddonRegistry {

    private const val TAG = "AddonRegistry"

    // ── Registry URLs (all from streamx-addons GitHub Pages) ─────────────────

    /** Official bundle addons — manifest.json from streamx-addons repo */
    private const val OFFICIAL_MANIFEST_URL =
        "https://aeoncorex-lab.github.io/streamx-addons/manifest.json"

    /** Community HTTP addons — registry.json (Stremio-protocol addons) */
    private const val COMMUNITY_REGISTRY_URL =
        "https://aeoncorex-lab.github.io/streamx-addons/registry.json"

    /** Modflix domain URL map */
    private const val MODFLIX_URL =
        "https://aeoncorex-lab.github.io/streamx-addons/modflix.json"

    private const val CACHE_TTL_MS = 6 * 60 * 60 * 1_000L  // 6 hours

    // In-memory cache
    private var communityCache: Pair<List<AddonDescriptor>, Long>? = null

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Fetch community HTTP addons from registry.json
     * These are Stremio-protocol addons submitted by community developers
     */
    suspend fun fetchCommunity(force: Boolean = false): List<AddonDescriptor> {
        communityCache?.takeIf { isFresh(it.second) && !force }?.first?.let { return it }

        val list = fetchRegistryRaw(COMMUNITY_REGISTRY_URL)
        communityCache = list to System.currentTimeMillis()
        return list
    }

    /**
     * Install a single HTTP addon by URL (user pastes manifest URL or deeplink delivers it)
     * Works with any Stremio-compatible addon
     */
    suspend fun installByUrl(manifestUrl: String): AddonDescriptor {
        val normalized = normalizeManifestUrl(manifestUrl)
        val json = HttpClient.getJson(normalized)
            ?: throw Exception("Cannot reach: $normalized")

        val manifest = AddonManifest.fromJson(JSONObject(json))
        Log.d(TAG, "Resolved addon: ${manifest.name} (${manifest.id})")

        return AddonDescriptor(
            manifest     = manifest,
            transportUrl = normalized,
            kind         = AddonKind.HTTP_ENDPOINT,
            flags        = AddonFlags()
        )
    }

    /**
     * Normalize a manifest URL — handles:
     *   https://addon.com           → https://addon.com/manifest.json
     *   https://addon.com/          → https://addon.com/manifest.json
     *   https://addon.com/manifest.json → unchanged
     *   addon.com/manifest.json     → https://addon.com/manifest.json
     */
    fun normalizeManifestUrl(url: String): String {
        var u = url.trim()
        if (!u.startsWith("http")) u = "https://$u"
        u = u.trimEnd('/')
        if (!u.endsWith("/manifest.json")) u += "/manifest.json"
        return u
    }

    // ── Private ───────────────────────────────────────────────────────────────

    private suspend fun fetchRegistryRaw(url: String): List<AddonDescriptor> {
        Log.d(TAG, "Fetching registry: $url")
        val json = HttpClient.getJson(url) ?: run {
            Log.w(TAG, "Registry unreachable: $url")
            return emptyList()
        }
        return parseRegistry(json)
    }

    private fun parseRegistry(json: String): List<AddonDescriptor> = try {
        val arr = JSONArray(json)
        (0 until arr.length()).mapNotNull { i ->
            runCatching {
                val o = arr.getJSONObject(i)
                AddonDescriptor(
                    manifest     = AddonManifest.fromJson(o.getJSONObject("manifest")),
                    transportUrl = o.getString("transportUrl"),
                    kind         = AddonKind.valueOf(o.optString("kind", "HTTP_ENDPOINT")),
                    flags        = AddonFlags.fromJson(o.optJSONObject("flags") ?: JSONObject())
                )
            }.getOrNull()
        }.also { Log.d(TAG, "Parsed ${it.size} community addons") }
    } catch (e: Exception) {
        Log.w(TAG, "Registry parse error: ${e.message}")
        emptyList()
    }

    private fun isFresh(ts: Long) = System.currentTimeMillis() - ts < CACHE_TTL_MS
}
