package com.aeoncorex.streamx.model

import com.google.gson.annotations.SerializedName
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

data class Event(
    @SerializedName("title")
    val title: String = "",

    @SerializedName("team1_logo")
    val team1Logo: String = "",

    @SerializedName("team2_logo")
    val team2Logo: String = "",

    @SerializedName("startTime")
    val startTime: String = "", // ISO 8601 format: "2025-12-25T19:30:00Z"

    @SerializedName("tournament")
    val tournament: String = "",

    @SerializedName("channelIds")
    val channelIds: List<String> = emptyList()
) {
    // Helper function to parse startTime to LocalDateTime
    fun getStartDateTime(): LocalDateTime? {
        return try {
            Instant.parse(startTime)
                .atZone(ZoneId.systemDefault())
                .toLocalDateTime()
        } catch (e: Exception) {
            null
        }
    }

    // Helper function to get formatted time
    fun getFormattedTime(): String {
        return getStartDateTime()?.let { dateTime ->
            DateTimeFormatter.ofPattern("MMM dd, HH:mm").format(dateTime)
        } ?: "Time not available"
    }

    // Helper function to check if event is live
    fun isLive(): Boolean {
        return getStartDateTime()?.let { startTime ->
            val now = LocalDateTime.now(ZoneId.systemDefault())
            startTime.isBefore(now) && startTime.plusHours(3).isAfter(now)
        } ?: false
    }

    // Helper function to check if event is upcoming
    fun isUpcoming(): Boolean {
        return getStartDateTime()?.let { startTime ->
            val now = LocalDateTime.now(ZoneId.systemDefault())
            startTime.isAfter(now)
        } ?: false
    }

    // Helper function to extract team names from title
    fun getTeam1(): String {
        return title.split(" vs ").firstOrNull() ?: "Team 1"
    }

    fun getTeam2(): String {
        return title.split(" vs ").lastOrNull()?.split(",")?.firstOrNull() ?: "Team 2"
    }
}