package com.aeoncorex.streamx.util

object ChannelCategorizer {
    fun getGenreFromString(genreString: String?): ChannelGenre {
        return when (genreString?.uppercase()) {
            "NEWS" -> ChannelGenre.NEWS
            "SPORTS" -> ChannelGenre.SPORTS
            "ENTERTAINMENT" -> ChannelGenre.ENTERTAINMENT
            "MOVIES" -> ChannelGenre.MOVIES
            "MUSIC" -> ChannelGenre.MUSIC
            "KIDS", "CHILDREN" -> ChannelGenre.KIDS
            "EDUCATIONAL", "EDUCATION" -> ChannelGenre.EDUCATIONAL
            "RELIGIOUS", "RELIGION" -> ChannelGenre.RELIGIOUS
            "LIFESTYLE" -> ChannelGenre.LIFESTYLE
            "DOCUMENTARY" -> ChannelGenre.DOCUMENTARY
            "REGIONAL" -> ChannelGenre.REGIONAL
            "INTERNATIONAL" -> ChannelGenre.INTERNATIONAL
            else -> ChannelGenre.UNKNOWN
        }
    }
}