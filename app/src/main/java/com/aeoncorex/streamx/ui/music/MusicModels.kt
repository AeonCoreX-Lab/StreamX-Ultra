package com.aeoncorex.streamx.ui.music

import android.text.Html
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.*

// --- API Response Wrappers ---
data class ApiResponse<T>(val success: Boolean, val data: T)
data class SearchResult<T>(val results: List<T>)

// --- DTOs (Data Transfer Objects) ---
data class SongDto(
    val id: String,
    val name: String,
    val year: String?,
    val type: String?,
    val album: AlbumMiniDto?,
    val artists: ArtistMap?,
    val image: List<QualityUrl>?,
    val downloadUrl: List<QualityUrl>?
)

data class AlbumMiniDto(val id: String?, val name: String?, val url: String?)

data class AlbumDto(
    val id: String,
    val name: String,
    val description: String?,
    val year: String?,
    val image: List<QualityUrl>?,
    val artists: ArtistMap?
)

data class PlaylistDto(
    val id: String,
    val name: String,
    val subtitle: String?, // Sometimes description comes here
    val image: List<QualityUrl>?,
    val songCount: String?
)

data class ArtistMap(val primary: List<ArtistDto>?, val all: List<ArtistDto>?)
data class ArtistDto(val id: String?, val name: String?)
data class QualityUrl(val url: String)

// --- Internal App Models ---
data class MusicTrack(
    val id: String,
    val title: String,
    val artist: String,
    val coverUrl: String,
    val streamUrl: String,
    val year: String = "",
    val albumName: String = ""
)

data class MusicCollection(
    val id: String,
    val title: String,
    val subtitle: String,
    val coverUrl: String,
    val type: CollectionType // Album or Playlist
)

enum class CollectionType { ALBUM, PLAYLIST }

// --- API Service ---
interface SaavnApi {
    // Songs
    @GET("api/search/songs")
    suspend fun searchSongs(@Query("query") q: String, @Query("limit") limit: Int = 40): ApiResponse<SearchResult<SongDto>>

    // Albums
    @GET("api/search/albums")
    suspend fun searchAlbums(@Query("query") q: String, @Query("limit") limit: Int = 10): ApiResponse<SearchResult<AlbumDto>>

    @GET("api/albums")
    suspend fun getAlbumDetails(@Query("id") id: String): ApiResponse<SongDto> // Often returns song list structure or specific album structure depending on API version, assuming simplified list here for demo

    // Playlists
    @GET("api/search/playlists")
    suspend fun searchPlaylists(@Query("query") q: String, @Query("limit") limit: Int = 10): ApiResponse<SearchResult<PlaylistDto>>
    
    @GET("api/playlists")
    suspend fun getPlaylistDetails(@Query("id") id: String): ApiResponse<List<SongDto>> // Adjust based on actual response structure for details

    // Artist Songs
    @GET("api/artists/{id}/songs")
    suspend fun getArtistSongs(@Path("id") id: String): ApiResponse<SearchResult<SongDto>>
    
    // Song Details (for high quality link)
    @GET("api/songs/{id}")
    suspend fun getSongDetails(@Path("id") id: String): ApiResponse<List<SongDto>>
}

object MusicRepository {
    private val api = Retrofit.Builder()
        .baseUrl("https://jiosaavn-api-kappa-seven.vercel.app/")
        .addConverterFactory(GsonConverterFactory.create())
        .build()
        .create(SaavnApi::class.java)

    private fun clean(text: String?) = Html.fromHtml(text ?: "", Html.FROM_HTML_MODE_LEGACY).toString()

    // Mapper: SongDto -> MusicTrack
    fun SongDto.toTrack(): MusicTrack {
        val bestUrl = downloadUrl?.lastOrNull()?.url ?: ""
        val bestImage = image?.lastOrNull()?.url ?: ""
        return MusicTrack(
            id = id,
            title = clean(name),
            artist = artists?.primary?.joinToString(", ") { clean(it.name) } ?: "Unknown",
            coverUrl = bestImage,
            streamUrl = bestUrl,
            year = year ?: "",
            albumName = clean(album?.name)
        )
    }

    suspend fun searchSongs(q: String): List<MusicTrack> = try {
        api.searchSongs(q).data.results.map { it.toTrack() }
    } catch (e: Exception) { emptyList() }

    suspend fun searchAlbums(q: String): List<MusicCollection> = try {
        api.searchAlbums(q).data.results.map {
            MusicCollection(
                id = it.id,
                title = clean(it.name),
                subtitle = it.year ?: "Album",
                coverUrl = it.image?.lastOrNull()?.url ?: "",
                type = CollectionType.ALBUM
            )
        }
    } catch (e: Exception) { emptyList() }

    suspend fun searchPlaylists(q: String): List<MusicCollection> = try {
        api.searchPlaylists(q).data.results.map {
            MusicCollection(
                id = it.id,
                title = clean(it.name),
                subtitle = "Playlist",
                coverUrl = it.image?.lastOrNull()?.url ?: "",
                type = CollectionType.PLAYLIST
            )
        }
    } catch (e: Exception) { emptyList() }

    // Fetch songs for a playlist or album ID
    // Note: The API response structure varies for details, simplified here for robustness
    suspend fun getCollectionTracks(id: String, type: CollectionType): List<MusicTrack> = try {
        if (type == CollectionType.PLAYLIST) {
             // Depending on API, might need a different endpoint. Assuming getPlaylistDetails returns list of songs
             // Real implementation might need to parse specific playlist structure
             api.getPlaylistDetails(id).data.map { it.toTrack() }
        } else {
             // Placeholder for Album details fetch
             val res = api.searchSongs(id) // Fallback: searching by ID often works in loose APIs or use specific endpoint
             res.data.results.map { it.toTrack() }
        }
    } catch (e: Exception) { emptyList() }
    
    suspend fun getArtistSongs(id: String): List<MusicTrack> = try {
        api.getArtistSongs(id).data.results.map { it.toTrack() }
    } catch (e: Exception) { emptyList() }
}
