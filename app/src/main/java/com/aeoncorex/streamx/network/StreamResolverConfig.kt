package com.aeoncorex.streamx.network

import android.util.Log
import com.aeoncorex.streamx.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

// ═════════════════════════════════════════════════════════════════════════════
//  StreamResolverConfig
//  ─────────────────────────────────────────────────────────────────────────
//  Same "/config" bootstrap-URL indirection as MetadataConfig, pointed at
//  the streamx-stream-resolver Worker instead of streamx-metadata-cache.
//  See MetadataConfig.kt's header comment for the full rationale — not
//  repeated here to avoid drift between two copies of the same prose.
// ═════════════════════════════════════════════════════════════════════════════
object StreamResolverConfig {

    private const val TAG = "StreamResolverConfig"

    private val bootstrapUrl: String get() = BuildConfig.STREAM_WORKER_URL

    // Worker has no bundle for every provider name someone might put in
    // ENABLED_PROVIDERS (see index.js's enabledProviderList filtering
    // against AVAILABLE_PROVIDERS) — but if /config is ever unreachable
    // we still need *something* to try rather than resolving zero
    // providers. autoEmbed is the one provider that doesn't depend on
    // a search→meta chain (goes straight from tmdbId/imdbId), so it's
    // the safest single fallback.
    private val FALLBACK_PROVIDERS = listOf("autoEmbed")

    @Volatile private var resolvedUrl: String? = null
    @Volatile private var resolvedProviders: List<String>? = null
    private val resolveLock = Mutex()

    private val bootstrapHttpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()

    suspend fun getStreamWorkerBaseUrl(): String {
        resolvedUrl?.let { return it }
        resolveConfig()
        return resolvedUrl ?: bootstrapUrl
    }

    // Backs WorkerStreamProviderEngine's provider fan-out — same /config
    // bootstrap call as getStreamWorkerBaseUrl(), just reading the
    // "enabledProviders" field the Worker's /config also returns
    // (see streamx-stream-resolver/src/index.js: enabledProviderList()).
    // Cached the same way and by the same call, so hitting both getters
    // costs one network round trip total, not two.
    suspend fun getEnabledProviders(): List<String> {
        resolvedProviders?.let { return it }
        resolveConfig()
        return resolvedProviders ?: FALLBACK_PROVIDERS
    }

    private suspend fun resolveConfig() {
        resolveLock.withLock {
            // Re-check inside the lock: another caller may have already
            // resolved both values while we were waiting on it.
            if (resolvedUrl != null && resolvedProviders != null) return

            val fetched = fetchConfig()
            resolvedUrl = fetched?.first ?: bootstrapUrl
            resolvedProviders = fetched?.second ?: FALLBACK_PROVIDERS
        }
    }

    private suspend fun fetchConfig(): Pair<String, List<String>>? = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("${bootstrapUrl}config")
                .get()
                .build()

            bootstrapHttpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w(TAG, "/config returned ${response.code} — falling back to bootstrap URL")
                    return@withContext null
                }
                val body = response.body?.string() ?: return@withContext null
                val json = JSONObject(body)

                val url = json.optString("streamWorkerUrl").ifBlank { null }
                if (url == null) {
                    Log.w(TAG, "/config response missing streamWorkerUrl — falling back to bootstrap URL")
                }

                val providersArr = json.optJSONArray("enabledProviders")
                val providers = if (providersArr != null) {
                    (0 until providersArr.length()).mapNotNull { i -> providersArr.optString(i).ifBlank { null } }
                } else {
                    null
                }
                if (providers.isNullOrEmpty()) {
                    Log.w(TAG, "/config response missing/empty enabledProviders — falling back to $FALLBACK_PROVIDERS")
                }

                Pair(url ?: bootstrapUrl, providers?.takeIf { it.isNotEmpty() } ?: FALLBACK_PROVIDERS)
            }
        } catch (e: Exception) {
            Log.w(TAG, "/config fetch failed (${e.message}) — falling back to bootstrap URL")
            null
        }
    }
}
