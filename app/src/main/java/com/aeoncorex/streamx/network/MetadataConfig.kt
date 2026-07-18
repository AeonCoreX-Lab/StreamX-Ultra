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
//  MetadataConfig
//  ─────────────────────────────────────────────────────────────────────────
//  Resolves the ACTUAL metadata Worker URL to use for TMDB/Cinemeta calls,
//  so the Worker's URL can change in the future WITHOUT requiring an app
//  update.
//
//  How it works:
//    1. BuildConfig.METADATA_WORKER_URL is a fixed "bootstrap" URL, baked
//       into the APK at build time — same as before. This one URL is the
//       single anchor point that genuinely can't change without a new
//       app release.
//    2. On first use each app session, we call GET {bootstrap}/config,
//       which is public (no auth needed — see the Worker's src/index.js).
//       It returns { "metadataWorkerUrl": "<current actual URL>" }.
//    3. That URL is cached in memory for the rest of the process — every
//       subsequent TMDB/Cinemeta call in this session reuses it, no
//       repeated network round-trip.
//    4. If the /config call fails for any reason (offline, Worker down),
//       we fall back to using the bootstrap URL directly as the actual
//       URL — today they're the same Worker anyway, so this fails safe
//       rather than breaking metadata entirely on a transient error.
//
//  Why this lives here (network/) rather than inside MovieRepository or
//  CinemetaRepository: both of those need the same resolved URL, so it's
//  shared state — same reasoning as FirebaseTokenProvider living here.
// ═════════════════════════════════════════════════════════════════════════════
object MetadataConfig {

    private const val TAG = "MetadataConfig"

    // Bootstrap URL — the one fixed anchor point. Guaranteed to end in
    // "/" (see build.gradle.kts comment on METADATA_WORKER_URL).
    private val bootstrapUrl: String get() = BuildConfig.METADATA_WORKER_URL

    // Resolved once per process, then reused — a Mutex (not just
    // @Volatile) guards against two concurrent callers both triggering
    // a /config fetch on cold start (e.g. MovieRepository and
    // CinemetaRepository both used within the first second of app open).
    @Volatile private var resolvedUrl: String? = null
    private val resolveLock = Mutex()

    private val bootstrapHttpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()

    /**
     * Returns the current metadata Worker base URL to use, resolving it
     * via /config on first call and caching the result for the rest of
     * this process's lifetime. Always returns a non-null, "/"-terminated
     * URL — falls back to the bootstrap URL itself if /config can't be
     * reached, so callers never need to handle a null/failure case.
     */
    suspend fun getMetadataBaseUrl(): String {
        resolvedUrl?.let { return it }

        resolveLock.withLock {
            // Double-checked: another caller may have resolved it while
            // we were waiting for the lock.
            resolvedUrl?.let { return it }

            val fetched = fetchConfigUrl()
            val finalUrl = fetched ?: bootstrapUrl
            resolvedUrl = finalUrl
            return finalUrl
        }
    }

    private suspend fun fetchConfigUrl(): String? = withContext(Dispatchers.IO) {
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
                val url = JSONObject(body).optString("metadataWorkerUrl").ifBlank { null }
                if (url == null) {
                    Log.w(TAG, "/config response missing metadataWorkerUrl — falling back to bootstrap URL")
                }
                url
            }
        } catch (e: Exception) {
            Log.w(TAG, "/config fetch failed (${e.message}) — falling back to bootstrap URL")
            null
        }
    }
}
