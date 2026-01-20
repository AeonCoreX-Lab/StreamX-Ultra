package com.aeoncorex.streamx.ui.movie

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.GET
import retrofit2.http.Query

// Consumet Response Model
data class ConsumetResponse(val results: List<ConsumetResult>?)
data class ConsumetResult(val id: String, val title: String, val url: String?)

interface ConsumetApi {
    @GET("movies/flixhq/search")
    suspend fun search(@Query("query") query: String): ConsumetResponse
}

object ConsumetProvider {
    // This is a public demo instance. For production, host your own via Vercel/Render.
    private const val BASE_URL = "https://consumet-api-delta-gold.vercel.app/" 

    private val api = Retrofit.Builder()
        .baseUrl(BASE_URL)
        .addConverterFactory(GsonConverterFactory.create())
        .build()
        .create(ConsumetApi::class.java)

    suspend fun getStreamLinks(title: String, type: String): List<StreamLink> = withContext(Dispatchers.IO) {
        val links = mutableListOf<StreamLink>()
        try {
            val searchRes = api.search(title)
            val bestMatch = searchRes.results?.firstOrNull() 

            if (bestMatch != null) {
                // We construct a link object. 
                // Note: The player needs to handle "http_stream" specifically if it's not a magnet.
                links.add(
                    StreamLink(
                        title = "[HTTP] ${bestMatch.title}",
                        magnet = "http_stream_placeholder", // Logic in Player needs to handle fetching actual stream if this is selected
                        quality = "Auto",
                        seeds = 100, // Fake high seed count so it appears at top
                        size = "Stream", 
                        source = "Web"
                    )
                )
            }
        } catch (e: Exception) {
            Log.e("Consumet", "Error: ${e.message}")
        }
        return@withContext links
    }
}
