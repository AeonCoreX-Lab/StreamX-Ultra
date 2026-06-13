package com.aeoncorex.streamx.streaming

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray

// ═════════════════════════════════════════════════════════════════════════════
//  DefaultAddonManager.kt  —  FIXED v2
//
//  FIX 1 (Bug: "No addon source configured"):
//    Previous code seeded source URL with a wrong /modflix suffix:
//      "https://aeoncorex-lab.github.io/streamx-addons/modflix"
//    Manifest fetch tried  …/modflix/manifest.json  → 404.
//    Module download tried …/modflix/dist/{key}/{mod}.js → 404.
//    Correct URL (matches GitHub Pages repo structure):
//      "https://aeoncorex-lab.github.io/streamx-addons"
//    Manifest → …/manifest.json  ✓
//    Modules  → …/dist/{key}/{mod}.js  ✓
//
//  FIX 2 (Bug: source disappears after user removes it or on fresh install):
//    Added ensureDefaultSource() called every launch from AddonManager.initialize().
//    If getSources() is empty, the default source is automatically re-added.
//    This prevents the permanent "No addon source configured" stuck state.
//
//  FIX 3 (Bug: default_addons.json had wrong sourceAuthor/sourceUrl):
//    default_addons.json previously used "himanshu8443" author and
//    himanshu8443.github.io URL. All entries are now "AeonCoreX-Lab"
//    pointing to aeoncorex-lab.github.io/streamx-addons. AddonManager.install()
//    calls downloadModules(addon.sourceUrl, …) so the URL in each entry
//    MUST match the GitHub Pages origin that actually hosts the dist/ files.
// ═════════════════════════════════════════════════════════════════════════════
object DefaultAddonManager {

    private const val TAG            = "DefaultAddonManager"
    private const val PREFS_NAME     = "sx_first_launch"
    private const val KEY_SEEDED     = "defaults_seeded_v3"   // bumped so old seeded flag is ignored
    private const val DEFAULT_ADDONS = "default_addons.json"

    // ── Canonical default source ──────────────────────────────────────────────
    //
    // FIX: removed the stray /modflix suffix that was causing 404s on every
    // manifest fetch and module download.
    //   Manifest URL: https://aeoncorex-lab.github.io/streamx-addons/manifest.json
    //   Module URL:   https://aeoncorex-lab.github.io/streamx-addons/dist/{key}/{mod}.js
    //
    // This constant is the single source of truth — change here to update everywhere.
    const val DEFAULT_AUTHOR     = "AeonCoreX-Lab"
    const val DEFAULT_SOURCE_URL = "https://aeoncorex-lab.github.io/streamx-addons"

    // ── Ensure source always present (call every launch) ─────────────────────
    //
    // FIX: Previously the source was only added once on first launch. If the
    // user removed it (or storage was cleared), fetchManifest() would throw
    // "No addon source configured" permanently until the app was re-installed.
    //
    // Now called from AddonManager.initialize() on every cold start — only
    // adds if no source is configured, so it's a no-op in the normal case.
    fun ensureDefaultSource() {
        if (AddonStorage.getSources().isNotEmpty()) return
        AddonStorage.addSource(DEFAULT_AUTHOR, DEFAULT_SOURCE_URL)
        Log.d(TAG, "Default source auto-restored: $DEFAULT_AUTHOR → $DEFAULT_SOURCE_URL")
    }

    // ── Called from MainActivity once after AddonStorage.init() ──────────────
    suspend fun seedIfFirstLaunch(context: Context) = withContext(Dispatchers.IO) {
        // Ensure source exists regardless of seed state (covers reinstall / cleared storage)
        ensureDefaultSource()

        val prefs  = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val seeded = prefs.getBoolean(KEY_SEEDED, false)

        if (seeded) {
            Log.d(TAG, "Already seeded — skipping bundle install")
            return@withContext
        }

        Log.d(TAG, "First launch — seeding default addons from $DEFAULT_SOURCE_URL")

        try {
            val json    = context.assets.open(DEFAULT_ADDONS).bufferedReader().readText()
            val arr     = JSONArray(json)
            val toSeed  = (0 until arr.length()).map { AddonInfo.fromJson(arr.getJSONObject(it)) }

            // Fetch manifest to get accurate metadata (version, icon, etc.)
            val manifest = runCatching { AddonManager.fetchManifest() }.getOrElse { emptyList() }
            Log.d(TAG, "Manifest has ${manifest.size} addons")

            var successCount = 0
            for (addon in toSeed) {
                if (AddonStorage.isInstalled(addon.value, addon.sourceAuthor)) {
                    Log.d(TAG, "  ${addon.displayName}: already installed")
                    successCount++
                    continue
                }

                // Prefer manifest entry (accurate metadata) — fall back to default_addons.json entry
                val entry = manifest.firstOrNull { it.value == addon.value }
                    ?.copy(
                        sourceAuthor = DEFAULT_AUTHOR,
                        sourceUrl    = DEFAULT_SOURCE_URL
                    )
                    ?: addon.copy(
                        sourceAuthor = DEFAULT_AUTHOR,
                        sourceUrl    = DEFAULT_SOURCE_URL
                    )

                val result = runCatching { AddonManager.install(entry) }
                if (result.isSuccess) {
                    Log.d(TAG, "  ✓ ${addon.displayName} installed")
                    successCount++
                } else {
                    Log.w(TAG, "  ✗ ${addon.displayName}: ${result.exceptionOrNull()?.message}")
                    // Pending retry: store with corrected source info so the retry also uses the right URL
                    AddonStorage.addPendingAddon(entry)
                }
            }

            Log.d(TAG, "Seeded $successCount/${toSeed.size} default addons")
            prefs.edit().putBoolean(KEY_SEEDED, true).apply()

        } catch (e: Exception) {
            Log.e(TAG, "seedIfFirstLaunch error: ${e.message}")
        }
    }

    // ── Retry pending addons (called on app resume with network) ──────────────
    suspend fun retryPending() = withContext(Dispatchers.IO) {
        ensureDefaultSource()   // guard: ensure source is present before retry

        val pending = AddonStorage.getPendingAddons()
        if (pending.isEmpty()) return@withContext

        Log.d(TAG, "Retrying ${pending.size} pending addons")
        val manifest = runCatching { AddonManager.fetchManifest() }.getOrElse { emptyList() }

        for (addon in pending) {
            if (AddonStorage.isInstalled(addon.value, addon.sourceAuthor)) {
                AddonStorage.removePendingAddon(addon.value)
                continue
            }
            // Always use correct source info on retry
            val entry = (manifest.firstOrNull { it.value == addon.value }
                ?.copy(sourceAuthor = DEFAULT_AUTHOR, sourceUrl = DEFAULT_SOURCE_URL))
                ?: addon.copy(sourceAuthor = DEFAULT_AUTHOR, sourceUrl = DEFAULT_SOURCE_URL)

            runCatching { AddonManager.install(entry) }
                .onSuccess {
                    AddonStorage.removePendingAddon(addon.value)
                    Log.d(TAG, "  ✓ Retry success: ${addon.displayName}")
                }
                .onFailure { Log.w(TAG, "  ✗ Retry failed: ${addon.displayName}: ${it.message}") }
        }
    }

    // ── Force re-seed (e.g., after app update with new default list) ──────────
    fun resetSeedFlag(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().remove(KEY_SEEDED).apply()
    }
}
