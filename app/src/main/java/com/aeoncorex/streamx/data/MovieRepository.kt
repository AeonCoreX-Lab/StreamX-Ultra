package com.aeoncorex.streamx.data

import com.aeoncorex.streamx.BuildConfig
import com.aeoncorex.streamx.model.Movie
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

object MovieRepository {
    private val SUPABASE_URL = BuildConfig.SUPABASE_URL
    private val SUPABASE_KEY = BuildConfig.SUPABASE_KEY
    private val TMDB_KEY = BuildConfig.TMDB_KEY

    // মুভি লোড করার ফাংশন
    suspend fun getMovies(): List<Movie> = withContext(Dispatchers.IO) {
        try {
            val url = URL("$SUPABASE_URL/rest/v1/movies?select=*")
            val conn = url.openConnection() as HttpURLConnection
            conn.setRequestProperty("apikey", SUPABASE_KEY)
            conn.setRequestProperty("Authorization", "Bearer $SUPABASE_KEY")
            
            val response = conn.inputStream.bufferedReader().readText()
            val jsonArray = JSONArray(response)
            val list = mutableListOf<Movie>()

            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                val tmdbId = obj.optString("tmdb_id", "")
                val streamUrl = obj.getString("stream_url")

                // TMDB ID থাকলে অটো ডাটা আনবে
                if (tmdbId.isNotEmpty()) {
                    fetchTMDB(tmdbId, streamUrl)?.let { list.add(it) }
                }
            }
            list
        } catch (e: Exception) { emptyList() }
    }

    // অ্যাডমিন অ্যাপ থেকে মুভি সেভ করার ফাংশন
    suspend fun postMovie(tmdbId: String, streamUrl: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val url = URL("$SUPABASE_URL/rest/v1/movies")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("apikey", SUPABASE_KEY)
            conn.setRequestProperty("Authorization", "Bearer $SUPABASE_KEY")
            conn.setRequestProperty("Content-Type", "application/json")
            conn.doOutput = true

            val json = JSONObject().apply {
                put("tmdb_id", tmdbId)
                put("stream_url", streamUrl)
            }
            conn.outputStream.write(json.toString().toByteArray())
            conn.responseCode == 201
        } catch (e: Exception) { false }
    }

    private fun fetchTMDB(id: String, stream: String): Movie? {
        return try {
            val res = URL("https://api.themoviedb.org/3/movie/$id?api_key=$TMDB_KEY").readText()
            val json = JSONObject(res)
            Movie(
                id = id,
                title = json.getString("title"),
                description = json.getString("overview"),
                posterUrl = "https://image.tmdb.org/t/p/w500${json.getString("poster_path")}",
                coverUrl = "https://image.tmdb.org/t/p/original${json.getString("backdrop_path")}",
                streamUrl = stream,
                year = json.getString("release_date").split("-")[0],
                rating = "IMDb " + String.format("%.1f", json.getDouble("vote_average"))
            )
        } catch (e: Exception) { null }
    }
}
