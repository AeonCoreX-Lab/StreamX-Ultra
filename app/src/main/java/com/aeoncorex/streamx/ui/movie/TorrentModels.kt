package com.aeoncorex.streamx.ui.movie

data class TorrentResult(
    val title: String,
    val magnet: String,
    val seeds: Int,
    val peers: Int,
    val size: String,
    val source: String // "YTS", "EZTV", "NYAA"
)

// EZTV API Response Models
data class EztvResponse(
    val torrents: List<EztvTorrent>?
)
data class EztvTorrent(
    val title: String,
    val magnet_url: String,
    val seeds: Int,
    val peers: Int,
    val size_bytes: Long,
    val episode: String,
    val season: String
)
