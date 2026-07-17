package com.aeoncorex.streamx.streaming

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

// ═════════════════════════════════════════════════════════════════════════════
//  CinemetaRepository.kt
//  ─────────────────────────────────────────────────────────────────────────
//  Cinemeta metadata, now routed through the StreamX metadata-cache Worker
//  instead of hitting v3-cinemeta.strem.io directly. Same rationale as the
//  TMDB migration in MovieRepository.kt: every user sharing one Worker-side
//  KV cache means far fewer (and far more resilient) upstream calls than
//  each device calling Cinemeta independently with only a local, per-session
//  in-memory cache.
//
//  Worker routes used:
//    GET /cinemeta/meta/{type}/{imdbId}
//    GET /cinemeta/person?name={name}
//
//  AUTH: same Firebase ID token scheme as MovieRepository — see
//  network/FirebaseTokenProvider.kt and the Worker's src/firebase-auth.js.
// ═════════════════════════════════════════════════════════════════════════════
object CinemetaRepository {

    private const val TAG      = "CinemetaRepo"
    private val WORKER_BASE get() =
        com.aeoncorex.streamx.BuildConfig.METADATA_WORKER_URL.trimEnd('/')
    private const val TTL_MS   = 30 * 60 * 1_000L   // 30 min in-memory (in ADDITION to Worker's KV cache — avoids a network round-trip entirely for repeat views within one app session)

    // In-memory cache: imdbId → (data, timestamp)
    private val cache = HashMap<String, Pair<CinemetaMeta, Long>>()
    // Person cache: name → (data, timestamp)
    private val personCache = HashMap<String, Pair<CinemetaPerson, Long>>()

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * GET against the metadata Worker with a Firebase ID token attached,
     * retrying once with a forced token refresh on a 401 — mirrors the
     * retry policy in MovieRepository's OkHttp authInterceptor. Kept as
     * a plain suspend helper here (rather than an OkHttp interceptor)
     * since CinemetaRepository goes through the shared HttpClient.getJson,
     * not its own Retrofit/OkHttp client.
     */
    private suspend fun authedGetJson(url: String): String? {
        val token = com.aeoncorex.streamx.network.FirebaseTokenProvider.getIdToken()
        val headers = if (token != null) mapOf("Authorization" to "Bearer $token") else emptyMap()

        // HttpClient.getJson returns null both on network failure AND on a
        // non-2xx response, so we can't distinguish "401" from "no network"
        // from this call alone. Rather than widen HttpClient's return type
        // (shared by scrapers/JS engine — out of scope here), we just retry
        // once with a forced-fresh token whenever the first attempt failed
        // and we had a token to refresh. A forced refresh is cheap (one
        // Firebase call) and this only fires on the failure path, so it
        // doesn't add cost to the normal success case.
        val result = HttpClient.getJson(url, headers)
        if (result != null || token == null) return result

        val freshToken = com.aeoncorex.streamx.network.FirebaseTokenProvider.getIdToken(forceRefresh = true)
            ?: return null
        return HttpClient.getJson(url, mapOf("Authorization" to "Bearer $freshToken"))
    }

    /**
     * Fetch enriched metadata from Cinemeta by IMDB ID (via the metadata Worker).
     */
    suspend fun get(imdbId: String, type: String): CinemetaMeta? = withContext(Dispatchers.IO) {
        if (imdbId.isBlank() || !imdbId.startsWith("tt")) return@withContext null

        val cacheKey = "$imdbId|$type"
        val cached   = cache[cacheKey]
        if (cached != null && System.currentTimeMillis() - cached.second < TTL_MS) {
            Log.d(TAG, "Cache hit: $imdbId")
            return@withContext cached.first
        }

        val typeStr = if (type.equals("movie", true)) "movie" else "series"
        val url     = "$WORKER_BASE/cinemeta/meta/$typeStr/$imdbId"

        Log.d(TAG, "Fetching: $url")
        val json = authedGetJson(url) ?: return@withContext null

        val parsed = parse(json) ?: return@withContext null
        cache[cacheKey] = Pair(parsed, System.currentTimeMillis())
        Log.d(TAG, "Cinemeta OK: ${parsed.name} (${parsed.year})")
        parsed
    }

    /**
     * Search person by name (via the metadata Worker).
     * Returns basic person info + their known movies from search results.
     */
    suspend fun getPerson(name: String): CinemetaPerson? = withContext(Dispatchers.IO) {
        if (name.isBlank()) return@withContext null

        // Check cache first
        val cached = personCache[name]
        if (cached != null && System.currentTimeMillis() - cached.second < TTL_MS) {
            Log.d(TAG, "Person cache hit: $name")
            return@withContext cached.first
        }

        val encodedName = java.net.URLEncoder.encode(name, "UTF-8")
        val url = "$WORKER_BASE/cinemeta/person?name=$encodedName"

        Log.d(TAG, "Fetching person: $url")
        val json = authedGetJson(url) ?: return@withContext null

        val parsed = parsePerson(json, name) ?: return@withContext null
        personCache[name] = Pair(parsed, System.currentTimeMillis())
        Log.d(TAG, "Cinemeta Person OK: ${parsed.name}")
        parsed
    }

    // ── Parser ────────────────────────────────────────────────────────────────

    private fun parse(json: String): CinemetaMeta? = try {
        val root = JSONObject(json).optJSONObject("meta") ?: return null

        val videos = buildList {
            val arr = root.optJSONArray("videos") ?: return@buildList
            for (i in 0 until arr.length()) {
                val v = arr.getJSONObject(i)
                add(CinemetaVideo(
                    id       = v.optString("id"),
                    title    = v.optString("title", v.optString("name", "Episode ${v.optInt("number", i + 1)}")),
                    season   = v.optInt("season", 1),
                    episode  = v.optInt("episode", v.optInt("number", i + 1)),
                    thumb    = v.optString("thumbnail").ifEmpty { v.optString("thumb") },
                    overview = v.optString("overview"),
                    released = v.optString("released")
                ))
            }
        }

        val cast = buildList {
            val arr = root.optJSONArray("cast") ?: return@buildList
            for (i in 0 until minOf(arr.length(), 15)) add(arr.getString(i))
        }

        val genres = buildList {
            val arr = root.optJSONArray("genres") ?: return@buildList
            for (i in 0 until arr.length()) add(arr.getString(i))
        }

        val links = buildList {
            val arr = root.optJSONArray("links") ?: return@buildList
            for (i in 0 until arr.length()) {
                val l = arr.getJSONObject(i)
                add(CinemetaLink(
                    name     = l.optString("name"),
                    category = l.optString("category"),
                    url      = l.optString("url")
                ))
            }
        }

        CinemetaMeta(
            imdbId      = root.optString("imdb_id").ifEmpty { root.optString("id") },
            name        = root.optString("name"),
            type        = root.optString("type", "movie"),
            year        = root.optString("year"),
            poster      = root.optString("poster"),
            background  = root.optString("background").ifEmpty { root.optString("fanart") },
            logo        = root.optString("logo"),
            description = root.optString("description").ifEmpty { root.optString("overview") },
            runtime     = root.optString("runtime"),
            country     = root.optString("country"),
            language    = root.optString("language"),
            status      = root.optString("status"),
            rating      = root.optDouble("imdbRating", root.optDouble("rating", 0.0)),
            genres      = genres,
            cast        = cast,
            director    = root.optString("director").ifEmpty { root.optString("writer") },
            awards      = root.optString("awards"),
            videos      = videos,
            links       = links,
            trailerStreams = buildList {
                val arr = root.optJSONArray("trailerStreams") ?: return@buildList
                for (i in 0 until arr.length()) {
                    val t = arr.getJSONObject(i)
                    add(CinemetaTrailer(t.optString("title"), t.optString("ytId")))
                }
            }
        )
    } catch (e: Exception) {
        Log.w(TAG, "Parse error: ${e.message}")
        null
    }

    /**
     * Parse person search results from Cinemeta.
     */
    private fun parsePerson(json: String, queryName: String): CinemetaPerson? = try {
        val root = JSONObject(json)
        val metas = root.optJSONArray("metas") ?: return null

        // Find best matching person from results
        var bestMatch: JSONObject? = null
        var bestScore = 0

        for (i in 0 until metas.length()) {
            val meta = metas.getJSONObject(i)
            val type = meta.optString("type", "")
            val name = meta.optString("name", "")

            if (type == "person" || type == "actor") {
                val score = calculateNameMatchScore(name, queryName)
                if (score > bestScore) {
                    bestScore = score
                    bestMatch = meta
                }
            }
        }

        // If no person type found, try any result with good name match
        if (bestMatch == null) {
            for (i in 0 until metas.length()) {
                val meta = metas.getJSONObject(i)
                val name = meta.optString("name", "")
                val score = calculateNameMatchScore(name, queryName)
                if (score > bestScore && score > 70) {
                    bestScore = score
                    bestMatch = meta
                }
            }
        }

        bestMatch?.let { meta ->
            CinemetaPerson(
                id          = meta.optString("id"),
                name        = meta.optString("name", queryName),
                photo       = meta.optString("poster").ifEmpty { meta.optString("background") },
                description = meta.optString("description", ""),
                knownFor    = meta.optString("known_for", "Acting"),
                birthDate   = meta.optString("birthDate", ""),
                birthPlace  = meta.optString("birthPlace", ""),
                deathDate   = meta.optString("deathDate", ""),
                gender      = meta.optString("gender", ""),
                popularity  = meta.optDouble("popularity", 0.0),
                knownMovies = buildList {
                    val links = meta.optJSONArray("links")
                    if (links != null) {
                        for (j in 0 until minOf(links.length(), 12)) {
                            val link = links.getJSONObject(j)
                            add(CinemetaKnownMovie(
                                id       = link.optString("imdb_id", "tt0000000"),
                                title    = link.optString("name", "Unknown"),
                                poster   = link.optString("poster", ""),
                                year     = link.optString("year", ""),
                                rating   = link.optDouble("rating", 0.0)
                            ))
                        }
                    }
                }
            )
        }
    } catch (e: Exception) {
        Log.w(TAG, "Person parse error: ${e.message}")
        null
    }

    /**
     * Simple name matching score (0-100)
     */
    private fun calculateNameMatchScore(resultName: String, queryName: String): Int {
        val r = resultName.lowercase().trim()
        val q = queryName.lowercase().trim()

        return when {
            r == q -> 100
            r.contains(q) || q.contains(r) -> 85
            r.split(" ").any { q.contains(it) } -> 60
            else -> 0
        }
    }

    fun clearCache() {
        cache.clear()
        personCache.clear()
    }
}

// ── Data models ───────────────────────────────────────────────────────────────

data class CinemetaMeta(
    val imdbId:        String,
    val name:          String,
    val type:          String,
    val year:          String,
    val poster:        String,
    val background:    String,
    val logo:          String,
    val description:   String,
    val runtime:       String,
    val country:       String,
    val language:      String,
    val status:        String,
    val rating:        Double,
    val genres:        List<String>,
    val cast:          List<String>,
    val director:      String,
    val awards:        String,
    val videos:        List<CinemetaVideo>,
    val links:         List<CinemetaLink>,
    val trailerStreams: List<CinemetaTrailer>
) {
    val runtimeFormatted: String get() = when {
        runtime.startsWith("PT") -> {
            val h = Regex("""(\d+)H""").find(runtime)?.groupValues?.get(1)?.toIntOrNull() ?: 0
            val m = Regex("""(\d+)M""").find(runtime)?.groupValues?.get(1)?.toIntOrNull() ?: 0
            when {
                h > 0 && m > 0 -> "${h}h ${m}m"
                h > 0           -> "${h}h"
                m > 0           -> "${m}m"
                else            -> runtime
            }
        }
        runtime.contains("min") -> runtime
        runtime.isNotEmpty()    -> "$runtime min"
        else                    -> "N/A"
    }

    val bestBackdrop: String get() = background.ifEmpty { poster }

    fun episodesForSeason(season: Int) =
        videos.filter { it.season == season }.sortedBy { it.episode }
}

data class CinemetaVideo(
    val id:       String,
    val title:    String,
    val season:   Int,
    val episode:  Int,
    val thumb:    String,
    val overview: String,
    val released: String
)

data class CinemetaLink(
    val name:     String,
    val category: String,
    val url:      String
)

data class CinemetaTrailer(
    val title: String,
    val ytId:  String
)

/**
 * NEW: Cinemeta Person data model
 */
data class CinemetaPerson(
    val id:          String,
    val name:        String,
    val photo:       String,
    val description: String,
    val knownFor:    String,
    val birthDate:   String,
    val birthPlace:  String,
    val deathDate:   String,
    val gender:      String,
    val popularity:  Double,
    val knownMovies: List<CinemetaKnownMovie>
)

data class CinemetaKnownMovie(
    val id:     String,
    val title:  String,
    val poster: String,
    val year:   String,
    val rating: Double
)
