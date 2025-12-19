package com.aeoncorex.streamx.model
import com.google.gson.annotations.SerializedName

data class Channel(
    @SerializedName("id") val id: String = "",
    @SerializedName("name") val name: String = "",
    @SerializedName("logoUrl") val logoUrl: String = "",
    @SerializedName("streamUrl") val streamUrl: String = "",
    @SerializedName("category") var category: String = "",
    @SerializedName("isFeatured") val isFeatured: Boolean = false,
    @SerializedName("isHD") val isHD: Boolean = false,
    @SerializedName("ageGroup") val ageGroup: String? = null
)
