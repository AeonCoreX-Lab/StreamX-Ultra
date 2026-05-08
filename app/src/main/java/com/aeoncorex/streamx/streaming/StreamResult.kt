package com.aeoncorex.streamx.streaming

// ─────────────────────────────────────────────────────────────────────────────
//  StreamResult.kt — Shared data models for the provider engine
// ─────────────────────────────────────────────────────────────────────────────

enum class StreamType { HLS, MP4, MKV, DASH }

data class SubtitleTrack(
    val url:      String,
    val language: String,
    val title:    String,
    val mimeType: String = "text/vtt"
)

data class StreamResult(
    val url:       String,
    val quality:   String        = "Unknown",
    val type:      StreamType    = StreamType.HLS,
    val source:    String,
    val language:  String        = "English",
    val label:     String        = "",
    val subtitles: List<SubtitleTrack> = emptyList(),
    val headers:   Map<String, String> = emptyMap()
) {
    // ExoPlayer-compatible type string
    val mimeType: String get() = when (type) {
        StreamType.HLS  -> "application/x-mpegURL"
        StreamType.DASH -> "application/dash+xml"
        else            -> "video/mp4"
    }
}

/** Input that every provider receives */
data class ProviderRequest(
    val tmdbId:    Int?,
    val imdbId:    String?,
    val title:     String,
    val year:      Int?    = null,
    val isSeries:  Boolean = false,
    val season:    Int     = 0,
    val episode:   Int     = 0,
    val language:  String  = "English"   // "English","Hindi","Tamil","Telugu","Bengali","Japanese"
)
