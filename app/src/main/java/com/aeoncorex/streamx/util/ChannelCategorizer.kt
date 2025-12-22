package com.aeoncorex.streamx.util

// --- নতুন: আপনার অ্যাপে যত ধরনের জেনার থাকবে তার তালিকা ---
enum class ChannelGenre {
    SPORTS, NEWS, ENTERTAINMENT, MOVIES, MUSIC, KIDS, INFORMATIVE, LIFESTYLE, RELIGION, UNKNOWN
}

// --- নতুন: দেশ অনুযায়ী ফিল্টার করার জন্য দেশের তালিকা ---
enum class Country {
    BANGLADESH, INDIA, USA, UK, UAE, UNKNOWN
}

object ChannelCategorizer {
    // JSON থেকে পাওয়া স্ট্রিং-কে ChannelGenre enum-এ পরিণত করে
    fun getGenreFromString(genreString: String?): ChannelGenre {
        return try {
            if (genreString.isNullOrBlank()) ChannelGenre.UNKNOWN
            else ChannelGenre.valueOf(genreString.uppercase().trim())
        } catch (e: IllegalArgumentException) {
            ChannelGenre.UNKNOWN
        }
    }

    // JSON থেকে পাওয়া স্ট্রিং-কে Country enum-এ পরিণত করে
    fun getCountryFromString(countryString: String?): Country {
        return try {
            if (countryString.isNullOrBlank()) Country.UNKNOWN
            else Country.valueOf(countryString.uppercase().trim())
        } catch (e: IllegalArgumentException) {
            Country.UNKNOWN
        }
    }
}