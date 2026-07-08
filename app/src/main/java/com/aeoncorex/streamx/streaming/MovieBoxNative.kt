package com.aeoncorex.streamx.streaming

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

// ── Wire models (mirror Rust moviebox::types exactly) ──────────────────────

@Serializable
data class MovieBoxDub(
    val subjectId: String,
    val lanNameRaw: String,
    val lanCode: String,
    val original: Boolean,
    val dubType: Int = 0
) {
    /** "Original Audio" -> "Original", "Hindi dub" -> "Hindi" */
    val displayName: String
        get() {
            val lower = lanNameRaw.lowercase()
            if (lower.startsWith("original")) return "Original"
            return lanNameRaw.lowercase().replace("dub", "").trim()
                .split(" ").filter { it.isNotBlank() }
                .joinToString(" ") { it.replaceFirstChar(Char::uppercase) }
        }
}

@Serializable
data class MovieBoxItemDetails(
    val subjectId: String,
    val title: String,
    val detailPath: String? = null,
    val dubs: List<MovieBoxDub> = emptyList()
)

@Serializable
data class MovieBoxSearchItem(
    val title: String,
    val subjectId: String,
    val detailPath: String? = null,
    val year: String? = null
)

@Serializable
data class MovieBoxStreamFile(
    val format: String,
    val id: String,
    val url: String,
    val resolutions: Int,
    val size: String? = null,
    val duration: Long? = null,
    val codecName: String? = null
)

@Serializable
data class MovieBoxStreamResult(
    val subjectId: String,
    val se: Int,
    val ep: Int,
    val hasResource: Boolean,
    val sources: List<MovieBoxStreamFile> = emptyList(),
    val hls: List<JsonElement> = emptyList(),
    val freeEpisodes: Long? = null,
    val limited: Boolean = false,
    val note: String? = null
) {
    /** Highest-resolution direct URL, preferring HLS manifest if present. */
    fun bestPlayableUrl(): String? {
        hls.firstOrNull()?.let { el ->
            runCatching { el.jsonObject["url"]?.jsonPrimitive?.content }.getOrNull()?.let { return it }
        }
        return sources.maxByOrNull { it.resolutions }?.url
    }
}

@Serializable
data class MovieBoxCaptionFile(
    val id: String,
    val lan: String,
    val lanName: String,
    val url: String
)

@Serializable
data class MovieBoxCaptionResult(
    val extCaptions: List<MovieBoxCaptionFile> = emptyList(),
    val subjectId: String? = null
)

// ── Error wrapper for JNI results that came back as {"error": "..."} ───────

class MovieBoxException(message: String) : Exception(message)

private val movieBoxJson = Json { ignoreUnknownKeys = true; isLenient = true }

object MovieBoxNative {

    init {
        // Reuses the same native lib your torrent/JNI functions already
        // load — do NOT call System.loadLibrary twice for the same lib
        // name if TorrentEngine (or similar) already does this at app
        // startup. Keeping it here is safe (loadLibrary is idempotent)
        // but remove if it causes a duplicate-load warning in your setup.
        System.loadLibrary("streamx-native")
    }

    private external fun nativeSearch(query: String, page: Int): String
    private external fun nativeGetItemDetails(subjectId: String): String
    private external fun nativeGetStreams(subjectId: String, se: Int, ep: Int): String
    private external fun nativeGetCaptions(subjectId: String, resourceId: String): String

    private fun checkError(raw: String) {
        val el = movieBoxJson.parseToJsonElement(raw)
        val obj = el.jsonObject
        obj["error"]?.let { throw MovieBoxException(it.jsonPrimitive.content) }
    }

    suspend fun search(query: String, page: Int = 1): List<MovieBoxSearchItem> =
        withContext(Dispatchers.IO) {
            val raw = nativeSearch(query, page)
            // nativeSearch returns a bare JSON array ("[...]") on success,
            // or a JSON object ("{\"error\": ...}") on failure.
            if (raw.trimStart().startsWith("{")) {
                checkError(raw)
                throw MovieBoxException("Unexpected response shape from nativeSearch")
            }
            movieBoxJson.decodeFromString(raw)
        }

    /** Full details for a subject, including its dubs[] list. */
    suspend fun getItemDetails(subjectId: String): MovieBoxItemDetails =
        withContext(Dispatchers.IO) {
            val raw = nativeGetItemDetails(subjectId)
            checkError(raw)
            movieBoxJson.decodeFromString(raw)
        }

    /**
     * Direct stream files for [subjectId] (pass a dub's subjectId here to
     * get that dub's streams — NOT the original subjectId + a language
     * param).
     */
    suspend fun getStreams(subjectId: String, se: Int = 1, ep: Int = 1): MovieBoxStreamResult =
        withContext(Dispatchers.IO) {
            val raw = nativeGetStreams(subjectId, se, ep)
            checkError(raw)
            movieBoxJson.decodeFromString(raw)
        }

    suspend fun getCaptions(subjectId: String, resourceId: String): MovieBoxCaptionResult =
        withContext(Dispatchers.IO) {
            val raw = nativeGetCaptions(subjectId, resourceId)
            checkError(raw)
            movieBoxJson.decodeFromString(raw)
        }
}
