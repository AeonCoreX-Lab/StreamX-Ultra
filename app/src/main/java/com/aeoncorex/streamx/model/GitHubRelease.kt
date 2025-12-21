package com.aeoncorex.streamx.model

data class GitHubRelease(
    val tag_name: String,
    val name: String,
    val body: String,
    val assets: List<Asset>
) {
    data class Asset(
        val browser_download_url: String
    )
}