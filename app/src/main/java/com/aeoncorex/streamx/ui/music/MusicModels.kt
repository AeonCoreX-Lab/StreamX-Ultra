package com.aeoncorex.streamx.ui.music

import android.text.Html
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.services.youtube.extractors.YoutubeMusicSearchExtractor
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.extractor.MediaFormat
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.*
import java.io.IOException
import java.util.concurrent.TimeUnit

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

// --- DTOs: Lyrics ---
data class LyricsDto(
    val id: Int?,
    val trackName: String?,
    val artistName: String?,
    val plainLyrics: String?,
    val syncedLyrics: String?
)

// --- Internal App Models ---
data class MusicTrack(
    val id: String,
    val title: String,
    val artist: String,
    val album: String = "Unknown Album",
    val coverUrl: String,
    val streamUrl: String, // For YT, this is the video URL; audio extracted later
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

interface SaavnApi {
    @GET("api/search/songs")
    suspend fun searchSongs(@Query("query") q: String, @Query("limit") limit: Int = 40): ApiResponse<SearchResult<SongDto>>

    @GET("api/search/albums")
    suspend fun searchAlbums(@Query("query") q: String, @Query("limit") limit: Int = 10): ApiResponse<SearchResult<AlbumDto>>
    
    @GET("api/albums")
    suspend fun getAlbumDetails(@Query("id") id: String): ApiResponse<SongDto> 

    @GET("api/search/playlists")
    suspend fun searchPlaylists(@Query("query") q: String, @Query("limit") limit: Int = 10): ApiResponse<SearchResult<PlaylistDto>>
    
    @GET("api/playlists")
    suspend fun getPlaylistDetails(@Query("id") id: String): ApiResponse<List<SongDto>>
    
    @GET("api/artists/{id}/songs")
    suspend fun getArtistSongs(@Path("id") id: String): ApiResponse<SearchResult<SongDto>>
    
    @GET("api/songs/{id}")
    suspend fun getSongDetails(@Path("id") id: String): ApiResponse<List<SongDto>>
}

interface LyricsApi {
    @GET("get")
    suspend fun getLyrics(
        @Query("track_name") trackName: String,
        @Query("artist_name") artistName: String,
        @Query("duration") duration: Double? = null
    ): LyricsDto
}

// --- Custom Downloader for NewPipe (Uses OkHttp) ---
// FIX 1: Downloader is an abstract class, so we must call its constructor ()
class OkHttpDownloader : Downloader() {
    private val client = OkHttpClient.Builder()
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    override fun execute(request: org.schabi.newpipe.extractor.downloader.Request): Response {
        val httpMethod = request.httpMethod()
        val url = request.url()
        val headers = request.headers()
        val dataToSend = request.dataToSend()

        val okRequestBuilder = Request.Builder()
            .url(url)
            .method(httpMethod, dataToSend?.toRequestBody())

        headers.forEach { (key, list) ->
            list.forEach { value ->
                okRequestBuilder.addHeader(key, value)
            }
        }

        try {
            val response = client.newCall(okRequestBuilder.build()).execute()
            val responseBody = response.body?.string() ?: ""
            return Response(response.code, response.message, response.headers.toMultimap(), responseBody, null)
        } catch (e: IOException) {
            throw IOException("NewPipe Network Error", e)
        }
    }
}

object MusicRepository {
    // Client 1: Saavn
    private val saavnApi = Retrofit.Builder()
        .baseUrl("https://jiosaavn-api-kappa-seven.vercel.app/")
        .addConverterFactory(GsonConverterFactory.create())
        .build()
        .create(SaavnApi::class.java)
    
     // Client 2: Lyrics
    private val lyricsRetrofit = Retrofit.Builder()
        .baseUrl("https://lrclib.net/api/") 
        .addConverterFactory(GsonConverterFactory.create())
        .build()
    val lyricsApi: LyricsApi = lyricsRetrofit.create(LyricsApi::class.java)

    // --- NewPipe Helper ---
    fun getDownloader(): Downloader = OkHttpDownloader()

    private fun clean(text: String?) = Html.fromHtml(text ?: "", Html.FROM_HTML_MODE_LEGACY).toString()

    // --- Unified Search Logic (Updated with NewPipe) ---
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
            } catch (e: Exception) { 
                Log.e("MusicRepo", "Saavn Error: ${e.message}")
                emptyList<MusicTrack>() 
            }
        }

        // --- NewPipe YouTube Search ---
        val youtubeJob = async {
            try {
                // Using YouTube Music Search for better music results
                val extractor = ServiceList.YouTube.getSearchExtractor(query) 
                extractor.fetchPage()
                
                extractor.initialPage.items
                    .filterIsInstance<StreamInfoItem>()
                    .map { item ->
                        // FIX 2: Use item.thumbnails?.firstOrNull()?.url as thumbnailUrl is unresolved
                        val thumb = item.thumbnails?.firstOrNull()?.url ?: ""
                        
                        MusicTrack(
                            id = item.url, // Keep URL as ID
                            title = item.name,
                            artist = item.uploaderName,
                            coverUrl = thumb,
                            streamUrl = item.url,
                            year = "",
                            albumName = "YouTube Music",
                            source = "YouTube"
                        )
                    }
            } catch (e: Exception) { 
                Log.e("NewPipe", "Error: ${e.message}")
                emptyList<MusicTrack>() 
            }
        }

        val saavnResults = saavnJob.await()
        val youtubeResults = youtubeJob.await()
        
        // Combined results: Saavn first, then YouTube
        return@withContext saavnResults + youtubeResults
    }

    // --- NewPipe Audio Extraction (High Quality) ---
    suspend fun getYouTubeAudioUrl(videoUrl: String): String = withContext(Dispatchers.IO) {
        try {
            val extractor = ServiceList.YouTube.getStreamExtractor(videoUrl)
            extractor.fetchPage()
            
            // Search for M4A format (usually better quality/compatible)
            val audioStream = extractor.audioStreams
                .filter { 
                    it.format?.name?.lowercase() == "m4a" 
                } 
                .maxByOrNull { it.bitrate }
                
            // Fallback to any available audio stream
            return@withContext audioStream?.content 
                ?: extractor.audioStreams.firstOrNull()?.content 
                ?: ""
                
        } catch (e: Exception) {
            Log.e("NewPipe", "Stream Extract Error: ${e.message}")
            return@withContext ""
        }
    }

    // --- Lyrics & Collections ---
    suspend fun getLyrics(trackName: String, artistName: String): LyricsDto? = withContext(Dispatchers.IO) {
        try {
            lyricsApi.getLyrics(trackName, artistName)
        } catch (e: Exception) {
            null
        }
    }

    suspend fun searchAlbums(q: String): List<MusicCollection> = try {
        saavnApi.searchAlbums(q).data.results.map {
            MusicCollection(it.id, clean(it.name), it.year?.toString() ?: "Album", it.image?.lastOrNull()?.url ?: "", CollectionType.ALBUM)
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
             // Logic placeholder: Saavn might treat Album lookup via search or different ID structure
             // Calling search logic using the ID as a fallback mechanism
             searchSongs(id) 
        }
    } catch (e: Exception) { emptyList() }
}