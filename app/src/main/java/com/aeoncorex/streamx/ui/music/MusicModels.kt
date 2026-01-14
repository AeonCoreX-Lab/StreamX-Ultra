package com.aeoncorex.streamx.ui.music

import android.text.Html
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.*

// --- API Response Wrappers (Saavn) ---
data class ApiResponse<T>(val success: Boolean, val data: T)
data class SearchResult<T>(val results: List<T>)

// --- DTOs: Saavn ---
data class SongDto(
    val id: String,
    val name: String,
    val year: String?,
    val album: AlbumMiniDto?,
    val artists: ArtistMap?,
    val image: List<QualityUrl>?,
    val downloadUrl: List<QualityUrl>?
)

data class AlbumMiniDto(val id: String?, val name: String?, val url: String?)
data class AlbumDto(val id: String, val name: String, val year: String?, val image: List<QualityUrl>?)
data class PlaylistDto(val id: String, val name: String, val subtitle: String?, val image: List<QualityUrl>?)
data class ArtistMap(val primary: List<ArtistDto>?, val all: List<ArtistDto>?)
data class ArtistDto(val id: String?, val name: String?)
data class QualityUrl(val url: String)

// --- DTOs: Lyrics (New Free API) ---
data class LyricsDto(
    val id: Int?,
    val trackName: String?,
    val artistName: String?,
    val plainLyrics: String?,
    val syncedLyrics: String?

// --- DTOs: Piped (YouTube) ---
data class PipedResponse(val items: List<PipedItem>)
data class PipedItem(
    val title: String,
    val uploaderName: String, // Artist
    val url: String,          // e.g. "/watch?v=dQw4w9WgXcQ"
    val thumbnail: String,
    val duration: Long,
    val type: String          // "stream"
)

data class PipedStreamResponse(val audioStreams: List<PipedAudioStream>)
data class PipedAudioStream(val url: String, val bitrate: Int, val format: String)

// --- Internal App Models ---
data class MusicTrack(
    val id: String,
    val title: String,
    val artist: String,
    val album: String = "Unknown Album", // Added Album
    val coverUrl: String,
    val streamUrl: String, // If Source is YT, this is the VideoID initially
    val year: String = "",
    val albumName: String = "",
    val source: String = "Saavn" // "Saavn" or "YouTube"
)

data class MusicCollection(
    val id: String,
    val title: String,
    val subtitle: String,
    val coverUrl: String,
    val type: CollectionType
)

enum class CollectionType { ALBUM, PLAYLIST }

// --- API Services ---

// 1. Saavn Interface
interface SaavnApi {
    @GET("api/search/songs")
    suspend fun searchSongs(@Query("query") q: String, @Query("limit") limit: Int = 40): ApiResponse<SearchResult<SongDto>>

    @GET("api/search/albums")
    suspend fun searchAlbums(@Query("query") q: String, @Query("limit") limit: Int = 10): ApiResponse<SearchResult<AlbumDto>>
    
    @GET("api/albums")
    suspend fun getAlbumDetails(@Query("id") id: String): ApiResponse<SongDto> // Often returns song list structure or specific album structure depending on API version, assuming simplified list here for demo

    @GET("api/search/playlists")
    suspend fun searchPlaylists(@Query("query") q: String, @Query("limit") limit: Int = 10): ApiResponse<SearchResult<PlaylistDto>>
    
    @GET("api/playlists")
    suspend fun getPlaylistDetails(@Query("id") id: String): ApiResponse<List<SongDto>>
    
       // Artist Songs
    @GET("api/artists/{id}/songs")
    suspend fun getArtistSongs(@Path("id") id: String): ApiResponse<SearchResult<SongDto>>
    
    // Song Details (for high quality link)
    @GET("api/songs/{id}")
    suspend fun getSongDetails(@Path("id") id: String): ApiResponse<List<SongDto>>
}

// New Free Lyrics API Interface (LrcLib)
interface LyricsApi {
    @GET("get")
    suspend fun getLyrics(
        @Query("track_name") trackName: String,
        @Query("artist_name") artistName: String,
        @Query("duration") duration: Double? = null
    ): LyricsDto
}

// 2. Piped (YouTube) Interface - Free & Unlimited
interface PipedApi {
    @GET("search")
    suspend fun search(@Query("q") q: String, @Query("filter") filter: String = "music_songs"): PipedResponse

    @GET("streams/{videoId}")
    suspend fun getStream(@Path("videoId") videoId: String): PipedStreamResponse
}

object MusicRepository {
    // Client 1: Saavn
    private val saavnApi = Retrofit.Builder()
        .baseUrl("https://jiosaavn-api-kappa-seven.vercel.app/")
        .addConverterFactory(GsonConverterFactory.create())
        .build()
        .create(SaavnApi::class.java)
    
     // New Lyrics Client
    private val lyricsRetrofit = Retrofit.Builder()
        .baseUrl("https://lrclib.net/api/") // Free Unlimited API
        .addConverterFactory(GsonConverterFactory.create())
        .build()

    val lyricsApi: LyricsApi = lyricsRetrofit.create(LyricsApi::class.java)

  // Client 3: Piped (YouTube Proxy) - Using a stable public instance
    private val pipedApi = Retrofit.Builder()
        .baseUrl("https://pipedapi.kavin.rocks/") 
        .addConverterFactory(GsonConverterFactory.create())
        .build()
        .create(PipedApi::class.java)

    private fun clean(text: String?) = Html.fromHtml(text ?: "", Html.FROM_HTML_MODE_LEGACY).toString()

    // --- Unified Search Logic ---
    suspend fun searchSongs(query: String): List<MusicTrack> = withContext(Dispatchers.IO) {
        val saavnJob = async {
            try {
                saavnApi.searchSongs(query).data.results.map { dto ->
                    MusicTrack(
                        id = dto.id,
                        title = clean(dto.name),
                        artist = dto.artists?.primary?.joinToString(", ") { clean(it.name) } ?: "Unknown",
                        coverUrl = dto.image?.lastOrNull()?.url ?: "",
                        streamUrl = dto.downloadUrl?.lastOrNull()?.url ?: "",
                        year = dto.year ?: "",
                        albumName = clean(dto.album?.name),
                        source = "Saavn"
                    )
                }
            } catch (e: Exception) { emptyList() }
        }

        val pipedJob = async {
            try {
                pipedApi.search(query).items
                    .filter { it.type == "stream" }
                    .map { item ->
                        val videoId = item.url.substringAfter("v=")
                        MusicTrack(
                            id = videoId,
                            title = item.title,
                            artist = item.uploaderName,
                            coverUrl = item.thumbnail,
                            streamUrl = videoId, // Store ID here, resolve actual URL just before playing
                            year = "",
                            albumName = "YouTube Music",
                            source = "YouTube"
                        )
                    }
            } catch (e: Exception) { 
                Log.e("Repo", "Piped Error: ${e.message}")
                emptyList() 
            }
        }

        // Merge: Saavn results first, then YouTube results
        val saavnResults = saavnJob.await()
        val pipedResults = pipedJob.await()
        return@withContext saavnResults + pipedResults
    }

    // --- Helper to get real audio link for YouTube items ---
    suspend fun getYouTubeAudioUrl(videoId: String): String = withContext(Dispatchers.IO) {
        try {
            val response = pipedApi.getStream(videoId)
            // Get the best m4a audio stream
            response.audioStreams
                .filter { it.format == "m4a" }
                .maxByOrNull { it.bitrate }?.url 
                ?: response.audioStreams.firstOrNull()?.url 
                ?: ""
        } catch (e: Exception) {
            ""
        }
    }

    // --- Keep existing methods for Albums/Playlists (Saavn Only for collections) ---
    suspend fun searchAlbums(q: String): List<MusicCollection> = try {
        saavnApi.searchAlbums(q).data.results.map {
            MusicCollection(it.id, clean(it.name), it.year ?: "Album", it.image?.lastOrNull()?.url ?: "", CollectionType.ALBUM)
        }
    } catch (e: Exception) { emptyList() }

    suspend fun searchPlaylists(q: String): List<MusicCollection> = try {
        saavnApi.searchPlaylists(q).data.results.map {
            MusicCollection(it.id, clean(it.name), "Playlist", it.image?.lastOrNull()?.url ?: "", CollectionType.PLAYLIST)
        }
    } catch (e: Exception) { emptyList() }

    suspend fun getCollectionTracks(id: String, type: CollectionType): List<MusicTrack> = try {
        if (type == CollectionType.PLAYLIST) {
             saavnApi.getPlaylistDetails(id).data.map { dto ->
                 MusicTrack(
                     id = dto.id,
                     title = clean(dto.name),
                     artist = dto.artists?.primary?.joinToString(", ") { clean(it.name) } ?: "Unknown",
                     coverUrl = dto.image?.lastOrNull()?.url ?: "",
                     streamUrl = dto.downloadUrl?.lastOrNull()?.url ?: "",
                     source = "Saavn"
                 )
             }
        } else {
             // Basic fallback for albums
             searchSongs(id) 
        }
    } catch (e: Exception) { emptyList() }
}
