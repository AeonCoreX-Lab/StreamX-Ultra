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

    @Volatile private var resolvedUrl: String? = null
    private val resolveLock = Mutex()

    private val bootstrapHttpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()

    suspend fun getStreamWorkerBaseUrl(): String {
        resolvedUrl?.let { return it }

        resolveLock.withLock {
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
                val url = JSONObject(body).optString("streamWorkerUrl").ifBlank { null }
                if (url == null) {
                    Log.w(TAG, "/config response missing streamWorkerUrl — falling back to bootstrap URL")
                }
                url
            }
        } catch (e: Exception) {
            Log.w(TAG, "/config fetch failed (${e.message}) — falling back to bootstrap URL")
            null
        }
    }
}
