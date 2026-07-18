package com.aeoncorex.streamx.ui.movie

import android.util.Log
import com.aeoncorex.streamx.streaming.CinemetaMeta
import com.aeoncorex.streamx.streaming.CinemetaPerson
import com.aeoncorex.streamx.streaming.CinemetaRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query

// ── TMDB Retrofit interface ────────────────────────────────────────────────────
// NOTE: as of the metadata-cache Worker migration, this interface no longer
// talks to api.themoviedb.org directly. It talks to our Cloudflare Worker
// (see WORKER_BASE_URL below), which caches responses in KV and forwards
// cache misses to TMDB using a server-side-only key. No api_key query param
// here anymore — the Worker injects it, the app never holds a TMDB key.
interface TmdbApi {
    @GET("tmdb/trending")
    suspend fun getTrending(): TmdbResponse

    @GET("tmdb/movies/popular")
    suspend fun getPopularMovies(): TmdbResponse

    @GET("tmdb/series/top-rated")
    suspend fun getTopRatedSeries(): TmdbResponse

    @GET("tmdb/movies/action")
    suspend fun getActionMovies(): TmdbResponse

    @GET("tmdb/movies/scifi")
    suspend fun getSciFiMovies(): TmdbResponse

    @GET("tmdb/search")
    suspend fun searchMulti(@Query("q") query: String): TmdbResponse

    @GET("tmdb/details/{type}/{id}")
    suspend fun getDetails(
        @Path("type") type: String,
        @Path("id") id: Int
    ): MovieDetailResponse

    @GET("tmdb/season/{id}/{season_number}")
    suspend fun getSeasonDetails(
        @Path("id") seriesId: Int,
        @Path("season_number") seasonNumber: Int
    ): SeasonDetailResponse

    @GET("tmdb/person/{person_id}")
    suspend fun getPersonDetails(
        @Path("person_id") personId: Int
    ): PersonApiResponse
}


// ═════════════════════════════════════════════════════════════════════════════
//  MovieRepository — v4   Dual Engine: TMDB primary + Cinemeta enrichment
//
//  Data merge strategy:
//  ┌─────────────────────────────────────────────────────────────────────┐
//  │ Field            │ Source priority                                   │
//  ├─────────────────────────────────────────────────────────────────────┤
//  │ title            │ TMDB → Cinemeta                                   │
//  │ description      │ TMDB → Cinemeta                                   │
//  │ poster           │ TMDB → Cinemeta                                   │
//  │ backdrop         │ TMDB → Cinemeta background → Cinemeta poster      │
//  │ tmdbRating       │ TMDB only  (shown as green ⭐ score)              │
//  │ imdbRating       │ Cinemeta only (shown as gold ★ IMDb)              │
//  │ year             │ TMDB → Cinemeta                                   │
//  │ runtime          │ TMDB → Cinemeta                                   │
//  │ genres           │ TMDB → Cinemeta                                   │
//  │ cast             │ TMDB only (has personId for navigation)            │
//  │ director         │ TMDB → Cinemeta                                   │
//  │ trailer          │ TMDB YouTube → Cinemeta ytId                      │
//  │ recommendations  │ TMDB only                                         │
//  │ seasons/episodes │ TMDB → Cinemeta videos                            │
//  │ logo             │ Cinemeta only                                     │
//  │ awards           │ Cinemeta only                                     │
//  │ country          │ Cinemeta only                                     │
//  │ language         │ Cinemeta only                                     │
//  │ status           │ Cinemeta only                                     │
//  └─────────────────────────────────────────────────────────────────────┘
//
//  Fallback: TMDB unreachable → full Cinemeta build (cinemetaOnly mode)
// ═════════════════════════════════════════════════════════════════════════════
object MovieRepository {

    private const val TAG               = "MovieRepo"
    private const val IMAGE_BASE_URL    = "https://image.tmdb.org/t/p/w500"
    private const val BACKDROP_BASE_URL = "https://image.tmdb.org/t/p/original"

    // ── Cross-session lookup caches ────────────────────────────────────────
    // movieId → imdbId (so episode fallback can find imdbId without re-fetching)
    private val tmdbToImdbCache      = mutableMapOf<Int, String>()
    // personId → name (so Cinemeta person fallback can search by name)
    private val tmdbPersonNameCache  = mutableMapOf<Int, String>()

    // ══════════════════════════════════════════════════════════════════════
    //  Metadata Worker — replaces direct TMDB calls
    //  ─────────────────────────────────────────────
    //  All TMDB traffic now goes through our Cloudflare Worker
    //  (streamx-metadata-cache), which caches responses in KV and holds
    //  the real TMDB API key server-side only. The app never sees a TMDB
    //  key anymore.
    //
    //  AUTH UPGRADE: the old static WORKER_AUTH_SECRET header (baked into
    //  every APK build, never expires, can't be revoked without a new
    //  release) is replaced with a per-request Firebase ID token:
    //      Authorization: Bearer <firebase-id-token>
    //  The Worker verifies this token's signature against Google's public
    //  certs and validates its issuer/audience/expiry — see the Worker's
    //  src/firebase-auth.js. Nothing secret ships inside the APK anymore.
    //
    //  URL INDIRECTION: Retrofit is built once with
    //  BuildConfig.METADATA_WORKER_URL as its baseUrl (required — Retrofit
    //  needs *some* valid absolute URL at construction time). But this
    //  interceptor rewrites every outgoing request to the ACTUAL current
    //  Worker URL resolved via MetadataConfig (which calls /config once
    //  per session — see MetadataConfig.kt for the full rationale). This
    //  means the Worker's real URL can change in the future without an
    //  app update — only the fixed bootstrap URL below is permanent.
    //
    //  BuildConfig.METADATA_WORKER_URL   e.g. "https://streamx-metadata-cache.YOUR-SUBDOMAIN.workers.dev/"
    // ══════════════════════════════════════════════════════════════════════
    private val authInterceptor = okhttp3.Interceptor { chain ->
        // OkHttp interceptors run on a background thread already (never the
        // main thread — Retrofit's suspend calls dispatch through OkHttp's
        // own dispatcher), so blocking here with runBlocking is safe and
        // does not freeze the UI.
        val token = kotlinx.coroutines.runBlocking {
            com.aeoncorex.streamx.network.FirebaseTokenProvider.getIdToken()
        }

        // Resolve the actual Worker URL (cached after the first call this
        // session — see MetadataConfig) and rewrite this request's
        // host/base-path to point there instead of the hardcoded bootstrap
        // URL Retrofit was constructed with.
        val actualBaseUrl = kotlinx.coroutines.runBlocking {
            com.aeoncorex.streamx.network.MetadataConfig.getMetadataBaseUrl()
        }

        val original = chain.request()
        val rewrittenUrl = rewriteBaseUrl(original.url, actualBaseUrl)

        val requestBuilder = original.newBuilder().url(rewrittenUrl)
        if (token != null) {
            requestBuilder.addHeader("Authorization", "Bearer $token")
        }
        val requestWithAuth = requestBuilder.build()

        val response = chain.proceed(requestWithAuth)

        // Retry once with a forced token refresh if the Worker rejected us
        // as unauthorized — covers the token-expired-mid-flight case
        // (tokens live ~1h; a request that races the boundary shouldn't
        // just fail outright) without retrying every other error type.
        if (response.code == 401 && token != null) {
            response.close()
            val freshToken = kotlinx.coroutines.runBlocking {
                com.aeoncorex.streamx.network.FirebaseTokenProvider.getIdToken(forceRefresh = true)
            }
            if (freshToken != null) {
                val retryRequest = requestWithAuth.newBuilder()
                    .header("Authorization", "Bearer $freshToken")
                    .build()
                return@Interceptor chain.proceed(retryRequest)
            }
        }

        response
    }

    /**
     * Rewrites [original]'s scheme/host/port/base-path to match
     * [actualBaseUrl], while keeping the original request's path segments
     * (beyond Retrofit's configured baseUrl) and query parameters intact.
     * This lets Retrofit be built once against a fixed bootstrap baseUrl,
     * while every real request actually goes to the current resolved URL.
     */
    private fun rewriteBaseUrl(original: okhttp3.HttpUrl, actualBaseUrl: String): okhttp3.HttpUrl {
        val actual = actualBaseUrl.toHttpUrlOrNull() ?: return original
        val bootstrap = com.aeoncorex.streamx.BuildConfig.METADATA_WORKER_URL.toHttpUrlOrNull()

        // Path segments belonging to Retrofit's bootstrap baseUrl (usually
        // none, since the base URL is just "https://host/") are stripped
        // off, then the actual base's own path segments (if any — e.g. if
        // the resolved URL includes a subpath) are prepended instead.
        val bootstrapPathSegments = bootstrap?.pathSegments.orEmpty().filter { it.isNotEmpty() }
        val originalAllSegments = original.pathSegments.filter { it.isNotEmpty() }
        val requestSpecificSegments =
            if (originalAllSegments.size >= bootstrapPathSegments.size &&
                originalAllSegments.take(bootstrapPathSegments.size) == bootstrapPathSegments
            ) {
                originalAllSegments.drop(bootstrapPathSegments.size)
            } else {
                originalAllSegments
            }

        val builder = actual.newBuilder()
        // Replace path entirely with actual's base path + the request-specific segments
        val actualBaseSegments = actual.pathSegments.filter { it.isNotEmpty() }
        builder.encodedPath("/")
        for (segment in actualBaseSegments + requestSpecificSegments) {
            builder.addPathSegment(segment)
        }
        builder.encodedQuery(original.encodedQuery)

        return builder.build()
    }

    private val okHttpClient = okhttp3.OkHttpClient.Builder()
        .addInterceptor(authInterceptor)
        .build()

    private val api = Retrofit.Builder()
        .baseUrl(com.aeoncorex.streamx.BuildConfig.METADATA_WORKER_URL)
        .client(okHttpClient)
        .addConverterFactory(GsonConverterFactory.create())
        .build()
        .create(TmdbApi::class.java)

    // ── DTO → Movie helper ─────────────────────────────────────────────────
    private fun mapToMovie(dto: MovieDto) = Movie(
        id          = dto.id,
        title       = dto.title ?: dto.name ?: "Unknown",
        description = dto.overview ?: "",
        posterUrl   = if (dto.posterPath != null) IMAGE_BASE_URL + dto.posterPath else "",
        backdropUrl = if (dto.backdropPath != null) BACKDROP_BASE_URL + dto.backdropPath else "",
        rating      = String.format("%.1f", dto.rating ?: 0.0),
        year        = (dto.releaseDate ?: dto.firstAirDate ?: "").take(4),
        type        = if (dto.name != null) MovieType.SERIES else MovieType.MOVIE
    )

    private suspend fun safeApiCall(
        call: suspend () -> TmdbResponse
    ): List<Movie> = withContext(Dispatchers.IO) {
        try { call().results.filter { it.posterPath != null }.map { mapToMovie(it) } }
        catch (e: Exception) { Log.e(TAG, "API call: ${e.message}"); emptyList() }
    }

    // ── Public listing API ─────────────────────────────────────────────────
    suspend fun getTrending()           = safeApiCall { api.getTrending() }
    suspend fun getPopularMovies()      = safeApiCall { api.getPopularMovies() }
    suspend fun getTopSeries()          = safeApiCall { api.getTopRatedSeries() }
    suspend fun getActionMovies()       = safeApiCall { api.getActionMovies() }
    suspend fun getSciFiMovies()        = safeApiCall { api.getSciFiMovies() }
    suspend fun searchMovies(q: String) = safeApiCall { api.searchMulti(q) }

    // ══════════════════════════════════════════════════════════════════════
    //  getFullDetails — dual engine merge
    // ══════════════════════════════════════════════════════════════════════
    suspend fun getFullDetails(movieId: Int, type: MovieType): FullMovieDetails? =
        withContext(Dispatchers.IO) {
            val typeStr = if (type == MovieType.MOVIE) "movie" else "tv"

            // ── 1. TMDB fetch (via Worker; may be null on network/upstream fail) ──
            val tmdb = try { api.getDetails(typeStr, movieId) }
                       catch (e: Exception) { Log.e(TAG, "TMDB detail: ${e.message}"); null }

            // ── 2. Cache imdbId from TMDB response ────────────────────────
            val imdbId = tmdb?.external_ids?.imdbId
            if (!imdbId.isNullOrEmpty()) tmdbToImdbCache[movieId] = imdbId
            val effectiveImdbId = imdbId ?: tmdbToImdbCache[movieId]

            // ── 3. Cinemeta fetch (enrichment or full fallback) ───────────
            val cinemeta: CinemetaMeta? = if (!effectiveImdbId.isNullOrEmpty()) {
                try { CinemetaRepository.get(effectiveImdbId, typeStr) }
                catch (e: Exception) { Log.w(TAG, "Cinemeta: ${e.message}"); null }
            } else null

            // ── 4. TMDB totally down → full Cinemeta build ────────────────
            if (tmdb == null && cinemeta != null) {
                Log.i(TAG, "TMDB unavailable — full Cinemeta build ($effectiveImdbId)")
                return@withContext buildFromCinemetaOnly(cinemeta, movieId, type)
            }
            if (tmdb == null) {
                Log.e(TAG, "Both sources failed for $movieId")
                return@withContext null
            }

            // ── 5. Merge TMDB + Cinemeta ─────────────────────────────────

            // Poster / backdrop — TMDB quality first
            val bestPoster = if (!tmdb.posterPath.isNullOrEmpty())
                IMAGE_BASE_URL + tmdb.posterPath
            else cinemeta?.poster ?: ""

            val bestBackdrop = when {
                !tmdb.backdropPath.isNullOrEmpty()    -> BACKDROP_BASE_URL + tmdb.backdropPath
                !cinemeta?.background.isNullOrEmpty() -> cinemeta!!.background
                !cinemeta?.poster.isNullOrEmpty()     -> cinemeta!!.poster
                else                                   -> ""
            }

            // Title / overview — TMDB first
            val title = tmdb.title ?: tmdb.name ?: cinemeta?.name ?: "Unknown"
            val description = tmdb.overview?.ifBlank { null }
                ?: cinemeta?.description ?: ""

            // Ratings — separate sources, both shown in UI
            val tmdbRating = tmdb.rating ?: 0.0          // TMDB vote_average
            val imdbRating = cinemeta?.rating ?: 0.0     // IMDb via Cinemeta

            val year = (tmdb.releaseDate ?: tmdb.firstAirDate
                ?: cinemeta?.year ?: "").take(4)

            val runtime = when {
                tmdb.runtime != null && tmdb.runtime > 0 -> "${tmdb.runtime} min"
                cinemeta?.runtimeFormatted?.isNotEmpty() == true -> cinemeta.runtimeFormatted
                else -> "N/A"
            }

            // Genres — TMDB has IDs resolved to names; Cinemeta has strings
            val genres = if (!tmdb.genres.isNullOrEmpty())
                tmdb.genres.map { it.name }
            else cinemeta?.genres ?: emptyList()

            // Cast — TMDB gives personId for navigation; Cinemeta only has names
            val cast = tmdb.credits?.cast?.take(15)?.map {
                CastMember(
                    name     = it.name,
                    role     = it.character ?: "",
                    imageUrl = if (!it.profilePath.isNullOrEmpty())
                                   IMAGE_BASE_URL + it.profilePath else "",
                    personId = it.id   // needed for person_detail navigation
                )
            } ?: emptyList()

            val director = tmdb.credits?.crew
                ?.find { it.job == "Director" }?.name
                ?: cinemeta?.director?.ifBlank { null }
                ?: "Unknown"

            // Trailer — TMDB YouTube first, Cinemeta ytId fallback
            val trailerKey = tmdb.videos?.results
                ?.find { it.site == "YouTube" && it.type == "Trailer" }?.key
                ?: cinemeta?.trailerStreams?.firstOrNull()?.ytId

            // Recommendations — TMDB only (has poster, rating, ID for navigation)
            val recommendations = tmdb.recommendations?.results
                ?.filter { !it.posterPath.isNullOrEmpty() }
                ?.take(12)?.map { mapToMovie(it) }
                ?: emptyList()

            // Seasons — TMDB data; Cinemeta fallback from episode list
            val seasons = tmdb.seasons ?: if (type == MovieType.SERIES && cinemeta != null) {
                cinemeta.videos.map { it.season }.distinct().sorted().map { sNum ->
                    SeasonDto(
                        seasonNumber = sNum,
                        episodeCount = cinemeta.videos.count { it.season == sNum },
                        name         = "Season $sNum"
                    )
                }
            } else emptyList()

            // Cinemeta-exclusive enrichments
            val logo     = cinemeta?.logo     ?: ""
            val awards   = cinemeta?.awards   ?: ""
            val country  = cinemeta?.country  ?: ""
            val language = cinemeta?.language ?: ""
            val status   = cinemeta?.status   ?: ""

            val basic = Movie(
                id          = tmdb.id,
                title       = title,
                description = description,
                posterUrl   = bestPoster,
                backdropUrl = bestBackdrop,
                rating      = String.format("%.1f", tmdbRating),
                year        = year,
                type        = type,
                logo        = logo
            )

            FullMovieDetails(
                basic            = basic,
                runtime          = runtime,
                genres           = genres,
                cast             = cast,
                director         = director,
                trailerKey       = trailerKey,
                recommendations  = recommendations,
                seasons          = seasons,
                imdbId           = effectiveImdbId,
                logo             = logo,
                awards           = awards,
                country          = country,
                language         = language,
                status           = status,
                tmdbRating       = tmdbRating,
                imdbRating       = imdbRating,
                cinemetaEnriched = cinemeta != null
            )
        }

    // ══════════════════════════════════════════════════════════════════════
    //  buildFromCinemetaOnly — full Cinemeta build when TMDB is unavailable
    // ══════════════════════════════════════════════════════════════════════
    private fun buildFromCinemetaOnly(
        meta    : CinemetaMeta,
        movieId : Int,
        type    : MovieType
    ): FullMovieDetails {
        val basic = Movie(
            id          = movieId,
            title       = meta.name,
            description = meta.description,
            posterUrl   = meta.poster,
            backdropUrl = meta.bestBackdrop,
            rating      = if (meta.rating > 0) String.format("%.1f", meta.rating) else "N/A",
            year        = meta.year.take(4),
            type        = type,
            logo        = meta.logo
        )

        // Cinemeta cast: names only, no personId — UI handles gracefully
        val cast = meta.cast.map { name ->
            CastMember(name = name, role = "", imageUrl = "", personId = 0)
        }

        val seasons = if (type == MovieType.SERIES && meta.videos.isNotEmpty()) {
            meta.videos.map { it.season }.distinct().sorted().map { sNum ->
                SeasonDto(
                    seasonNumber = sNum,
                    episodeCount = meta.videos.count { it.season == sNum },
                    name         = "Season $sNum"
                )
            }
        } else emptyList()

        return FullMovieDetails(
            basic            = basic,
            runtime          = meta.runtimeFormatted,
            genres           = meta.genres,
            cast             = cast,
            director         = meta.director.ifEmpty { "Unknown" },
            trailerKey       = meta.trailerStreams.firstOrNull()?.ytId,
            recommendations  = emptyList(),  // Cinemeta has no recommendation list
            seasons          = seasons,
            imdbId           = meta.imdbId,
            logo             = meta.logo,
            awards           = meta.awards,
            country          = meta.country,
            language         = meta.language,
            status           = meta.status,
            tmdbRating       = 0.0,          // TMDB unavailable
            imdbRating       = meta.rating,
            cinemetaEnriched = true
        )
    }

    // ══════════════════════════════════════════════════════════════════════
    //  getEpisodes — TMDB primary, Cinemeta fallback
    // ══════════════════════════════════════════════════════════════════════
    suspend fun getEpisodes(seriesId: Int, seasonNumber: Int): List<EpisodeDto> =
        withContext(Dispatchers.IO) {
            // Try TMDB first (via Worker)
            try {
                val resp = api.getSeasonDetails(seriesId, seasonNumber)
                if (!resp.episodes.isNullOrEmpty()) return@withContext resp.episodes
            } catch (e: Exception) {
                Log.e(TAG, "TMDB episodes: ${e.localizedMessage}")
            }

            // Cinemeta fallback using cached imdbId
            val imdbId = tmdbToImdbCache[seriesId]
            if (!imdbId.isNullOrEmpty()) {
                try {
                    val meta = CinemetaRepository.get(imdbId, "series")
                    if (meta != null) {
                        return@withContext meta.videos
                            .filter { it.season == seasonNumber }
                            .sortedBy { it.episode }
                            .map { v ->
                                EpisodeDto(
                                    id               = v.id.hashCode(),
                                    name             = v.title,
                                    overview         = v.overview,
                                    episodeNumber    = v.episode,
                                    stillPath        = null,
                                    runtime          = null,
                                    externalStillUrl = v.thumb
                                )
                            }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Cinemeta episodes: ${e.localizedMessage}")
                }
            }

            emptyList()
        }

    // ══════════════════════════════════════════════════════════════════════
    //  fetchPersonDetails — TMDB primary, Cinemeta name-search fallback
    // ══════════════════════════════════════════════════════════════════════
    suspend fun fetchPersonDetails(personId: Int): PersonDetails? =
        withContext(Dispatchers.IO) {
            if (personId <= 0) {
                Log.w(TAG, "fetchPersonDetails called with invalid personId=$personId")
                return@withContext null
            }

            // ── 1. TMDB (via Worker) ─────────────────────────────────────
            val tmdbPerson = try {
                val r = api.getPersonDetails(personId)
                tmdbPersonNameCache[personId] = r.name   // cache for Cinemeta fallback
                r
            } catch (e: Exception) {
                Log.e(TAG, "TMDB person $personId: ${e.localizedMessage}")
                null
            }

            // ── 2. Build from TMDB ────────────────────────────────────────
            if (tmdbPerson != null) {
                val r = tmdbPerson
                val knownForMovies = r.credits?.cast
                    ?.sortedByDescending { it.popularity ?: 0.0 }
                    ?.take(12)
                    ?.map { item ->
                        Movie(
                            id          = item.id,
                            title       = item.title ?: item.name ?: "Unknown",
                            description = "",
                            posterUrl   = if (!item.posterPath.isNullOrBlank())
                                              "$IMAGE_BASE_URL${item.posterPath}" else "",
                            backdropUrl = "",
                            rating      = String.format("%.1f", item.rating ?: 0.0),
                            year        = (item.releaseDate ?: item.firstAirDate)?.take(4) ?: "",
                            type        = if (item.mediaType == "tv") MovieType.SERIES else MovieType.MOVIE
                        )
                    } ?: emptyList()

                return@withContext PersonDetails(
                    id             = r.id,
                    name           = r.name,
                    biography      = r.biography ?: "",
                    birthday       = r.birthday,
                    deathday       = r.deathday,
                    placeOfBirth   = r.placeOfBirth,
                    gender         = r.gender ?: 0,
                    knownFor       = r.knownFor ?: "Acting",
                    popularity     = r.popularity ?: 0.0,
                    profileUrl     = if (!r.profilePath.isNullOrBlank())
                                         "$IMAGE_BASE_URL${r.profilePath}" else "",
                    knownForMovies = knownForMovies,
                    socialLinks    = PersonSocials(
                        instagramId = r.externalIds?.instagramId,
                        twitterId   = r.externalIds?.twitterId,
                        facebookId  = r.externalIds?.facebookId,
                        imdbId      = r.externalIds?.imdbId
                    )
                )
            }

            // ── 3. Cinemeta name-search fallback ──────────────────────────
            val cachedName = tmdbPersonNameCache[personId]
            if (!cachedName.isNullOrEmpty()) {
                Log.i(TAG, "TMDB person down — Cinemeta search: $cachedName")
                try {
                    val cp = CinemetaRepository.getPerson(cachedName)
                    if (cp != null) return@withContext buildPersonFromCinemeta(cp, personId)
                } catch (e: Exception) {
                    Log.w(TAG, "Cinemeta person fallback: ${e.message}")
                }
            }

            Log.e(TAG, "Person $personId: all sources failed")
            null
        }

    private fun buildPersonFromCinemeta(cp: CinemetaPerson, personId: Int): PersonDetails {
        val knownForMovies = cp.knownMovies.map { movie ->
            Movie(
                id          = movie.id.hashCode(),
                title       = movie.title,
                description = "",
                posterUrl   = movie.poster,
                backdropUrl = "",
                rating      = if (movie.rating > 0) String.format("%.1f", movie.rating) else "N/A",
                year        = movie.year.take(4),
                type        = MovieType.MOVIE
            )
        }
        return PersonDetails(
            id             = personId,
            name           = cp.name,
            biography      = cp.description,
            birthday       = cp.birthDate.ifEmpty { null },
            deathday       = cp.deathDate.ifEmpty { null },
            placeOfBirth   = cp.birthPlace.ifEmpty { null },
            gender         = when (cp.gender.lowercase()) { "female" -> 1; "male" -> 2; else -> 0 },
            knownFor       = cp.knownFor.ifEmpty { "Acting" },
            popularity     = cp.popularity,
            profileUrl     = cp.photo,
            knownForMovies = knownForMovies,
            socialLinks    = PersonSocials()
        )
    }
}
