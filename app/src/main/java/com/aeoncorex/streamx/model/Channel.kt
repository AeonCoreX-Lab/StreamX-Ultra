package com.aeoncorex.streamx.model

import com.aeoncorex.streamx.util.ChannelGenre // এই ফাইলটি আমাদের তৈরি করতে হবে
import com.google.gson.annotations.SerializedName

data class Channel(
    @SerializedName("id")
    val id: String = "",

    @SerializedName("name")
    val name: String = "",

    @SerializedName("logoUrl")
    val logoUrl: String = "",

    @SerializedName("streamUrls")
    val streamUrls: List<String> = emptyList(),

    // --- এই অংশটি পরিবর্তন করা হয়েছে ---
    @SerializedName("country") // JSON-এও 'country' ব্যবহার করা ভালো
    var country: String = "",

    @SerializedName("isFeatured")
    val isFeatured: Boolean = false,

    // --- এই নতুন ফিল্ডটি যোগ করা হয়েছে ---
    @SerializedName("genre")
    var genre: ChannelGenre = ChannelGenre.UNKNOWN
)