package com.aeoncorex.streamx.streaming

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

// ═══════════════════════════════════════════════════════════════════════════
//  AddonStorage.kt  —  Complete final version
//
//  Persists:
//    • Bundle addon sources  (GitHub Pages repos serving JS modules)
//    • Installed bundle addons + cached JS module code strings
//    • HTTP addons (Stremio-protocol live endpoints)
//    • Manifest cache (24h TTL per source)
//    • Pending addons (failed first-launch downloads → retry on next launch)
// ═══════════════════════════════════════════════════════════════════════════
object AddonStorage {

    private const val TAG = "AddonStorage"

    // ── Storage keys ──────────────────────────────────────────────────────────
    private const val PREFS_NAME          = "sx_addons"
    private const val KEY_SOURCES         = "addon_sources"
    private const val KEY_INSTALLED       = "installed_addons"
    private const val KEY_MODULES         = "addon_modules"
    private const val KEY_HTTP_ADDONS     = "http_addons"
    private const val KEY_PENDING         = "pending_addons"
    private const val KEY_MANIFEST_PREFIX = "manifest_"
    private const val KEY_MANIFEST_TIME   = "manifest_time_"
    private const val MANIFEST_TTL_MS     = 24 * 60 * 60 * 1_000L   // 24 h

    @Volatile private var prefs: SharedPreferences? = null

    // ── Init ──────────────────────────────────────────────────────────────────

    fun init(context: Context) {
        prefs = context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        Log.d(TAG, "AddonStorage initialised")
    }

    // ════════════════════════════════════════════════════════════════════════
    //  Bundle Addon Sources
    //  A "source" is a GitHub Pages URL that serves:
    //    /manifest.json           — list of available providers
    //    /dist/{key}/stream.js    — bundled JS for each provider
    // ════════════════════════════════════════════════════════════════════════

    fun getSources(): List<AddonSource> {
        val json = prefs?.getString(KEY_SOURCES, null) ?: return emptyList()
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).map { AddonSource.fromJson(arr.getJSONObject(it)) }
        } catch (e: Exception) {
            Log.w(TAG, "getSources parse error: ${e.message}")
            emptyList()
        }
    }

    fun saveSources(sources: List<AddonSource>) {
        val arr = JSONArray().apply { sources.forEach { put(it.toJson()) } }
        prefs?.edit()?.putString(KEY_SOURCES, arr.toString())?.apply()
    }

    fun getDefaultSource(): AddonSource? =
        getSources().firstOrNull { it.isDefault } ?: getSources().firstOrNull()

    fun addSource(author: String, url: String) {
        val existing = getSources().toMutableList()
        val normUrl  = url.trimEnd('/')
        val idx      = existing.indexOfFirst { it.author == author }
        if (idx >= 0) {
            existing[idx] = existing[idx].copy(url = normUrl)
        } else {
            existing.add(AddonSource(author, normUrl, isDefault = existing.isEmpty()))
        }
        if (existing.none { it.isDefault }) existing[0] = existing[0].copy(isDefault = true)
        saveSources(existing)
        Log.d(TAG, "Source added: $author → $normUrl")
    }

    fun removeSource(author: String) {
        var list = getSources().filter { it.author != author }
        if (list.isNotEmpty() && list.none { it.isDefault }) {
            list = list.toMutableList().also { it[0] = it[0].copy(isDefault = true) }
        }
        saveSources(list)
        // Also clear manifest cache for this source
        prefs?.edit()
            ?.remove(KEY_MANIFEST_PREFIX + author)
            ?.remove(KEY_MANIFEST_TIME + author)
            ?.apply()
        Log.d(TAG, "Source removed: $author")
    }

    fun setDefaultSource(author: String) {
        saveSources(getSources().map { it.copy(isDefault = it.author == author) })
    }

    // ════════════════════════════════════════════════════════════════════════
    //  Installed Bundle Addons
    // ════════════════════════════════════════════════════════════════════════

    fun getInstalled(): List<AddonInfo> {
        val json = prefs?.getString(KEY_INSTALLED, null) ?: return emptyList()
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).map { AddonInfo.fromJson(arr.getJSONObject(it)) }
        } catch (e: Exception) {
            Log.w(TAG, "getInstalled parse error: ${e.message}")
            emptyList()
        }
    }

    fun saveInstalled(list: List<AddonInfo>) {
        val arr = JSONArray().apply { list.forEach { put(it.toJson()) } }
        prefs?.edit()?.putString(KEY_INSTALLED, arr.toString())?.apply()
    }

    fun install(addon: AddonInfo) {
        val list = getInstalled().toMutableList()
        val idx  = list.indexOfFirst {
            it.value == addon.value && it.sourceAuthor == addon.sourceAuthor
        }
        val now    = System.currentTimeMillis()
        val record = addon.copy(
            installed   = true,
            installedAt = if (idx >= 0) list[idx].installedAt else now,
            updatedAt   = now
        )
        if (idx >= 0) list[idx] = record else list.add(record)
        saveInstalled(list)
        Log.d(TAG, "Bundle addon installed: ${addon.displayName}")
    }

    fun uninstall(value: String, sourceAuthor: String?) {
        saveInstalled(getInstalled().filter { a ->
            if (a.value != value) return@filter true
            sourceAuthor != null && a.sourceAuthor != sourceAuthor
        })
        removeModules(value, sourceAuthor)
        Log.d(TAG, "Bundle addon uninstalled: $value")
    }

    fun isInstalled(value: String, sourceAuthor: String? = null): Boolean =
        getInstalled().any { a ->
            a.value == value && (sourceAuthor == null || a.sourceAuthor == sourceAuthor)
        }

    fun getInstalledByType(type: String): List<AddonInfo> =
        getInstalled().filter { it.type.equals(type, ignoreCase = true) && !it.disabled }

    fun setDisabled(value: String, sourceAuthor: String?, disabled: Boolean) {
        val list = getInstalled().map { a ->
            if (a.value == value && (sourceAuthor == null || a.sourceAuthor == sourceAuthor))
                a.copy(disabled = disabled)
            else a
        }
        saveInstalled(list)
    }

    // ════════════════════════════════════════════════════════════════════════
    //  HTTP Addons  (Stremio-protocol live endpoints)
    //  These are installed via "Add by URL" — any Stremio addon works.
    // ════════════════════════════════════════════════════════════════════════

    fun getHttpAddons(): List<AddonDescriptor> {
        val json = prefs?.getString(KEY_HTTP_ADDONS, null) ?: return emptyList()
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).mapNotNull { i ->
                runCatching { AddonDescriptor.fromJson(arr.getJSONObject(i)) }.getOrNull()
            }
        } catch (e: Exception) {
            Log.w(TAG, "getHttpAddons parse error: ${e.message}")
            emptyList()
        }
    }

    /** Add or update an HTTP addon by manifest.id */
    fun saveHttpAddon(desc: AddonDescriptor) {
        val list = getHttpAddons().toMutableList()
        val idx  = list.indexOfFirst { it.manifest.id == desc.manifest.id }
        if (idx >= 0) list[idx] = desc else list.add(desc)
        val arr  = JSONArray().apply { list.forEach { put(it.toJson()) } }
        prefs?.edit()?.putString(KEY_HTTP_ADDONS, arr.toString())?.apply()
        Log.d(TAG, "HTTP addon saved: ${desc.manifest.name} (${desc.manifest.id})")
    }

    fun removeHttpAddon(manifestId: String) {
        val list = getHttpAddons().filter { it.manifest.id != manifestId }
        val arr  = JSONArray().apply { list.forEach { put(it.toJson()) } }
        prefs?.edit()?.putString(KEY_HTTP_ADDONS, arr.toString())?.apply()
        Log.d(TAG, "HTTP addon removed: $manifestId")
    }

    fun isHttpAddonInstalled(manifestId: String): Boolean =
        getHttpAddons().any { it.manifest.id == manifestId }

    /** HTTP addons that support the given resource and content type */
    fun getHttpAddonsForResource(resource: String, contentType: String, id: String): List<AddonDescriptor> =
        getHttpAddons().filter { desc ->
            desc.manifest.resources.any { r ->
                val name = when (r) {
                    is String             -> r
                    is Map<*, *>          -> r["name"]?.toString() ?: ""
                    else                  -> ""
                }
                name == resource
            } && (desc.manifest.types.isEmpty() || contentType in desc.manifest.types)
        }

    // ════════════════════════════════════════════════════════════════════════
    //  Cached JS Modules
    // ════════════════════════════════════════════════════════════════════════

    fun getModules(value: String, sourceAuthor: String? = null): AddonModule? {
        val json = prefs?.getString(KEY_MODULES, null) ?: return null
        return try {
            val arr  = JSONArray(json)
            val all  = (0 until arr.length()).map { AddonModule.fromJson(arr.getJSONObject(it)) }
            val hits = all.filter { it.value == value }
            if (hits.isEmpty()) return null
            if (sourceAuthor != null) {
                hits.firstOrNull { it.sourceAuthor == sourceAuthor }
                    ?: hits.maxByOrNull { it.cachedAt }
            } else {
                hits.maxByOrNull { it.cachedAt }
            }
        } catch (e: Exception) { null }
    }

    fun cacheModules(mod: AddonModule) {
        val existing = try {
            val json = prefs?.getString(KEY_MODULES, null)
            val arr  = JSONArray(json ?: "[]")
            (0 until arr.length()).map { AddonModule.fromJson(arr.getJSONObject(it)) }.toMutableList()
        } catch (e: Exception) { mutableListOf() }

        val idx = existing.indexOfFirst {
            it.value == mod.value && it.sourceAuthor == mod.sourceAuthor
        }
        if (idx >= 0) existing[idx] = mod else existing.add(mod)
        val arr = JSONArray().apply { existing.forEach { put(it.toJson()) } }
        prefs?.edit()?.putString(KEY_MODULES, arr.toString())?.apply()
        Log.d(TAG, "Modules cached for: ${mod.value}")
    }

    fun removeModules(value: String, sourceAuthor: String?) {
        val json = prefs?.getString(KEY_MODULES, null) ?: return
        val list = try {
            val arr = JSONArray(json)
            (0 until arr.length())
                .map { AddonModule.fromJson(arr.getJSONObject(it)) }
                .filter { m ->
                    m.value != value ||
                    (sourceAuthor != null && m.sourceAuthor != sourceAuthor)
                }
        } catch (e: Exception) { return }
        val arr = JSONArray().apply { list.forEach { put(it.toJson()) } }
        prefs?.edit()?.putString(KEY_MODULES, arr.toString())?.apply()
    }

    // ════════════════════════════════════════════════════════════════════════
    //  Manifest Cache  (24 h TTL per source)
    // ════════════════════════════════════════════════════════════════════════

    fun isManifestExpired(author: String, ttlMs: Long = MANIFEST_TTL_MS): Boolean {
        val last = prefs?.getLong(KEY_MANIFEST_TIME + author, 0L) ?: 0L
        return System.currentTimeMillis() - last > ttlMs
    }

    fun getManifestCache(author: String): List<AddonInfo> {
        val json = prefs?.getString(KEY_MANIFEST_PREFIX + author, null) ?: return emptyList()
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).map { AddonInfo.fromJson(arr.getJSONObject(it)) }
        } catch (e: Exception) { emptyList() }
    }

    fun setManifestCache(author: String, list: List<AddonInfo>) {
        val arr = JSONArray().apply { list.forEach { put(it.toJson()) } }
        prefs?.edit()
            ?.putString(KEY_MANIFEST_PREFIX + author, arr.toString())
            ?.putLong(KEY_MANIFEST_TIME + author, System.currentTimeMillis())
            ?.apply()
    }

    fun invalidateManifestCache(author: String) {
        prefs?.edit()
            ?.remove(KEY_MANIFEST_PREFIX + author)
            ?.remove(KEY_MANIFEST_TIME + author)
            ?.apply()
    }

    // ════════════════════════════════════════════════════════════════════════
    //  Pending Addons
    //  Failed to download on first launch → retry on next launch with network
    // ════════════════════════════════════════════════════════════════════════

    fun getPendingAddons(): List<AddonInfo> {
        val json = prefs?.getString(KEY_PENDING, null) ?: return emptyList()
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).map { AddonInfo.fromJson(arr.getJSONObject(it)) }
        } catch (e: Exception) { emptyList() }
    }

    fun addPendingAddon(addon: AddonInfo) {
        val list = getPendingAddons().toMutableList()
        if (list.none { it.value == addon.value && it.sourceAuthor == addon.sourceAuthor }) {
            list.add(addon)
            val arr = JSONArray().apply { list.forEach { put(it.toJson()) } }
            prefs?.edit()?.putString(KEY_PENDING, arr.toString())?.apply()
            Log.d(TAG, "Pending addon added: ${addon.displayName}")
        }
    }

    fun removePendingAddon(value: String) {
        val list = getPendingAddons().filter { it.value != value }
        val arr  = JSONArray().apply { list.forEach { put(it.toJson()) } }
        prefs?.edit()?.putString(KEY_PENDING, arr.toString())?.apply()
    }

    fun clearPendingAddons() {
        prefs?.edit()?.remove(KEY_PENDING)?.apply()
    }

    // ════════════════════════════════════════════════════════════════════════
    //  Convenience helpers
    // ════════════════════════════════════════════════════════════════════════

    /** Total installed count across bundle + HTTP addons */
    fun getTotalInstalledCount(): Int = getInstalled().size + getHttpAddons().size

    /** All addons that can provide streams, across both types */
    fun getAllStreamProviders(): Pair<List<AddonInfo>, List<AddonDescriptor>> =
        getInstalled().filter { !it.disabled } to getHttpAddons()

    /** Nuke everything (used in tests / "Reset app" feature) */
    fun clearAll() {
        prefs?.edit()?.clear()?.apply()
        Log.d(TAG, "AddonStorage cleared")
    }
}

// ════════════════════════════════════════════════════════════════════════════
//  Data classes
// ════════════════════════════════════════════════════════════════════════════

data class AddonSource(
    val author:    String,
    val url:       String,
    val isDefault: Boolean = false
) {
    fun toJson() = JSONObject().apply {
        put("author", author)
        put("url", url)
        put("isDefault", isDefault)
    }
    companion object {
        fun fromJson(o: JSONObject) = AddonSource(
            author    = o.getString("author"),
            url       = o.getString("url"),
            isDefault = o.optBoolean("isDefault", false)
        )
    }
}

data class AddonInfo(
    val value:        String,
    val displayName:  String,
    val version:      String  = "1.0",
    val icon:         String  = "",
    val disabled:     Boolean = false,
    val type:         String  = "global",
    val sourceAuthor: String  = "",
    val sourceUrl:    String  = "",
    val installed:    Boolean = false,
    val installedAt:  Long    = 0L,
    val updatedAt:    Long    = 0L
) {
    fun toJson() = JSONObject().apply {
        put("value",        value)
        put("displayName",  displayName)
        put("version",      version)
        put("icon",         icon)
        put("disabled",     disabled)
        put("type",         type)
        put("sourceAuthor", sourceAuthor)
        put("sourceUrl",    sourceUrl)
        put("installed",    installed)
        put("installedAt",  installedAt)
        put("updatedAt",    updatedAt)
    }
    companion object {
        fun fromJson(o: JSONObject) = AddonInfo(
            value        = o.optString("value",        ""),
            displayName  = o.optString("display_name", o.optString("displayName", "")),
            version      = o.optString("version",      "1.0"),
            icon         = o.optString("icon",         ""),
            disabled     = o.optBoolean("disabled",    false),
            type         = o.optString("type",         "global"),
            sourceAuthor = o.optString("sourceAuthor", ""),
            sourceUrl    = o.optString("sourceUrl",    ""),
            installed    = o.optBoolean("installed",   false),
            installedAt  = o.optLong("installedAt",    0L),
            updatedAt    = o.optLong("updatedAt",      0L)
        )
    }
}

data class AddonModule(
    val value:        String,
    val sourceAuthor: String  = "",
    val version:      String  = "1.0",
    val cachedAt:     Long    = 0L,
    val catalog:      String? = null,
    val posts:        String? = null,
    val meta:         String? = null,
    val stream:       String? = null,
    val episodes:     String? = null
) {
    fun toJson() = JSONObject().apply {
        put("value",        value)
        put("sourceAuthor", sourceAuthor)
        put("version",      version)
        put("cachedAt",     cachedAt)
        catalog?.let  { put("catalog",  it) }
        posts?.let    { put("posts",    it) }
        meta?.let     { put("meta",     it) }
        stream?.let   { put("stream",   it) }
        episodes?.let { put("episodes", it) }
    }
    companion object {
        fun fromJson(o: JSONObject) = AddonModule(
            value        = o.getString("value"),
            sourceAuthor = o.optString("sourceAuthor", ""),
            version      = o.optString("version",      "1.0"),
            cachedAt     = o.optLong("cachedAt",       0L),
            catalog      = if (o.has("catalog"))  o.getString("catalog")  else null,
            posts        = if (o.has("posts"))    o.getString("posts")    else null,
            meta         = if (o.has("meta"))     o.getString("meta")     else null,
            stream       = if (o.has("stream"))   o.getString("stream")   else null,
            episodes     = if (o.has("episodes")) o.getString("episodes") else null
        )
    }
}
