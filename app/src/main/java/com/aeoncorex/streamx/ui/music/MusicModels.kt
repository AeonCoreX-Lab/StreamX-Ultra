package com.aeoncorex.streamx.ui.music

import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query

// --- API Models (Based on your JSON) ---
data class ApiResponse<T>(val success: Boolean, val data: T)

data class SearchResult(
    val results: List<SongDto>
)

data class SongDto(
    val id: String,
    val name: String,
    val artists: ArtistMap,
    val image: List<QualityUrl>?,
    val downloadUrl: List<QualityUrl>?
)

data class ArtistMap(val primary: List<Artist>?)
data class Artist(val name: String)
data class QualityUrl(val quality: String, val url: String)

// --- Internal App Model ---
data class MusicTrack(
    val id: String,
    val title: String,
    val artist: String,
    val coverUrl: String,
    val streamUrl: String
)

// --- Retrofit Interface ---
interface SaavnApi {
    // Search for songs
    @GET("api/search/songs")
    suspend fun searchSongs(@Query("query") query: String): ApiResponse<SearchResult>

    // Get specific song details (Returns a list inside data)
    @GET("api/songs/{id}")
    suspend fun getSongDetails(@Path("id") id: String): ApiResponse<List<SongDto>>
}

// --- API Client ---
object MusicRepository {
    private val api = Retrofit.Builder()
        .baseUrl("https://jiosaavn-api-kappa-seven.vercel.app/")
        .addConverterFactory(GsonConverterFactory.create())
        .build()
        .create(SaavnApi::class.java)

    suspend fun search(query: String): List<MusicTrack> {
        return try {
            val response = api.searchSongs(query)
            response.data.results.map { it.toMusicTrack() }
        } catch (e: Exception) {
            emptyList()
        }
    }

    suspend fun getStreamUrl(id: String): String? {
        return try {
            val response = api.getSongDetails(id)
            val song = response.data.firstOrNull()
            // Get highest quality audio (320kbps usually last)
            song?.downloadUrl?.lastOrNull()?.url ?: song?.downloadUrl?.firstOrNull()?.url
        } catch (e: Exception) {
            null
        }
    }

    private fun SongDto.toMusicTrack(): MusicTrack {
        // Get highest quality image (500x500 usually last)
        val cover = image?.lastOrNull()?.url ?: ""
        val artistNames = artists.primary?.joinToString(", ") { it.name } ?: "Unknown Artist"
        
        return MusicTrack(
            id = id,
            title = name.replace("&quot;", "\"").replace("&amp;", "&"),
            artist = artistNames,
            coverUrl = cover,
            streamUrl = "" // Will be fetched on play
        )
    }
}
