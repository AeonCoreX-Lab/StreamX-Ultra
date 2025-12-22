package com.aeoncorex.streamx.model

import com.google.gson.annotations.SerializedName

data class Event(
    @SerializedName("title")
    val title: String = "",

    @SerializedName("team1_logo")
    val team1Logo: String = "",

    @SerializedName("team2_logo")
    val team2Logo: String = "",

    // startTime-কে ISO 8601 ফরম্যাটে (যেমন: "2025-12-25T19:30:00Z") আশা করা হচ্ছে
    @SerializedName("startTime")
    val startTime: String = "",

    @SerializedName("tournament")
    val tournament: String = "",

    // এই ইভেন্টটি কোন কোন চ্যানেলে দেখা যেতে পারে, তার ID-গুলোর তালিকা
    @SerializedName("channelIds")
    val channelIds: List<String> = emptyList()
)