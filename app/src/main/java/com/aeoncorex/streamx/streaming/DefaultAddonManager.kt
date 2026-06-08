package com.aeoncorex.streamx.streaming

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray

// ═════════════════════════════════════════════════════════════════════════════
//  DefaultAddonManager.kt
//
//  Stremio does this: bundles official addons compressed in the binary,
//  extracts + seeds them on first launch so users have streams immediately
//  without manually installing anything.
//
//  StreamX approach:
//    1. `assets/default_addons.json` — list of default addons to auto-install
//    2. On first launch, AddonManager.seedDefaults() downloads their JS modules
//    3. If JS module download fails → mark as "pending" so it retries on next
//       network-available app launch
//    4. User can see pre-installed addons in the Addons screen and remove them
// ═════════════════════════════════════════════════════════════════════════════
object DefaultAddonManager {

    private const val TAG              = "DefaultAddonManager"
    private const val PREFS_NAME       = "sx_first_launch"
    private const val KEY_SEEDED       = "defaults_seeded_v2"
    private const val DEFAULT_ADDONS   = "default_addons.json"

    // ── Called from MainActivity once after AddonStorage.init() ──────────────
    suspend fun seedIfFirstLaunch(context: Context) = withContext(Dispatchers.IO) {
        val prefs  = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val seeded = prefs.getBoolean(KEY_SEEDED, false)

        // Only seed once per installation (clear prefs to re-seed after update)
        if (seeded) {
            Log.d(TAG, "Already seeded — skipping")
            return@withContext
        }

        Log.d(TAG, "First launch — seeding default addons")

        try {
            val json    = context.assets.open(DEFAULT_ADDONS).bufferedReader().readText()
            val arr     = JSONArray(json)
            val toSeed  = (0 until arr.length()).map { AddonInfo.fromJson(arr.getJSONObject(it)) }

            // Ensure default source is configured
            if (AddonStorage.getSources().isEmpty()) {
                AddonStorage.addSource("aeoncorex-lab",
                    "https://aeoncorex-lab.github.io/streamx-addons/modflix")
                Log.d(TAG, "Default source seeded")
            }

            // Fetch manifest to populate the available list
            val manifest = runCatching { AddonManager.fetchManifest() }.getOrElse { emptyList() }

            var successCount = 0
            for (addon in toSeed) {
                // Skip already installed
                if (AddonStorage.isInstalled(addon.value, addon.sourceAuthor)) {
                    Log.d(TAG, "  ${addon.displayName}: already installed")
                    successCount++
                    continue
                }

                // Find matching entry from manifest for accurate metadata
                val manifestEntry = manifest.firstOrNull { it.value == addon.value }
                    ?: addon.copy(installed = false)

                val result = runCatching { AddonManager.install(manifestEntry) }
                if (result.isSuccess) {
                    Log.d(TAG, "  ✓ ${addon.displayName} installed")
                    successCount++
                } else {
                    Log.w(TAG, "  ✗ ${addon.displayName}: ${result.exceptionOrNull()?.message}")
                    // Store as pending — we'll retry on next launch until success
                    AddonStorage.addPendingAddon(addon)
                }
            }

            Log.d(TAG, "Seeded $successCount/${toSeed.size} default addons")

            // Mark seeded even if some failed — pending retries handle the rest
            prefs.edit().putBoolean(KEY_SEEDED, true).apply()

        } catch (e: Exception) {
            Log.e(TAG, "seedIfFirstLaunch error: ${e.message}")
        }
    }

    // ── Retry pending addons (called on app resume with network) ──────────────
    suspend fun retryPending() = withContext(Dispatchers.IO) {
        val pending = AddonStorage.getPendingAddons()
        if (pending.isEmpty()) return@withContext

        Log.d(TAG, "Retrying ${pending.size} pending addons")
        val manifest = runCatching { AddonManager.fetchManifest() }.getOrElse { emptyList() }

        for (addon in pending) {
            if (AddonStorage.isInstalled(addon.value, addon.sourceAuthor)) {
                AddonStorage.removePendingAddon(addon.value)
                continue
            }
            val entry = manifest.firstOrNull { it.value == addon.value } ?: addon
            runCatching { AddonManager.install(entry) }
                .onSuccess {
                    AddonStorage.removePendingAddon(addon.value)
                    Log.d(TAG, "  ✓ Retry success: ${addon.displayName}")
                }
                .onFailure { Log.w(TAG, "  ✗ Retry failed: ${addon.displayName}") }
        }
    }

    // ── Force re-seed (e.g., after app update with new default list) ──────────
    fun resetSeedFlag(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().remove(KEY_SEEDED).apply()
    }
}
