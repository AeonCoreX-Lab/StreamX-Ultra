package com.aeoncorex.streamx.streaming.providers

import android.util.Log
import com.aeoncorex.streamx.streaming.HttpClient
import com.aeoncorex.streamx.streaming.ModflixConfig
import com.aeoncorex.streamx.streaming.ProviderRequest
import com.aeoncorex.streamx.streaming.StreamResult
import com.aeoncorex.streamx.streaming.StreamType
import com.aeoncorex.streamx.streaming.extractors.HubCloudExtractor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.jsoup.Jsoup

// ─────────────────────────────────────────────────────────────────────────────
object RidoMoviesProvider {
    private const val TAG = "RidoMoviesProvider"

    suspend fun fetch(req: ProviderRequest): List<StreamResult> = withContext(Dispatchers.IO) {
        try {
            // RidoMovies stream.js uses AutoEmbed pattern with strem.io metadata
            // Delegate directly to AutoEmbed which already handles this better
            AutoEmbedProvider.fetch(req)
        } catch (e: Exception) { Log.w(TAG, e.message ?: "error"); emptyList() }
    }
}
