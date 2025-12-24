package com.aeoncorex.streamx.model

import com.google.gson.annotations.SerializedName
import com.aeoncorex.streamx.util.ChannelGenre

data class Channel(
    @SerializedName("id")
    val id: String = "",

    @SerializedName("name")
    val name: String = "",

    @SerializedName("logoUrl")
    val logoUrl: String = "",

    @SerializedName("streamUrls")
    val streamUrls: List<String> = emptyList(),

    @SerializedName("country")
    val country: String = "",

    @SerializedName("isFeatured")
    val isFeatured: Boolean = false,

    @SerializedName("genre")
    var genre: ChannelGenre = ChannelGenre.UNKNOWN
) {
    // Optional: সহজে access করার জন্য function
    fun hasStreams(): Boolean = streamUrls.isNotEmpty()
}