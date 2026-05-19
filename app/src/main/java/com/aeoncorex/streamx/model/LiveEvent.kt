package com.aeoncorex.streamx.model

import com.google.gson.annotations.SerializedName

// ─── Single stream server ────────────────────────────────────────────────────
data class EventStream(
    @SerializedName("name") val name: String = "",
    @SerializedName("url")  val url:  String = ""
)

// ─── One live event ──────────────────────────────────────────────────────────
data class LiveEvent(
    @SerializedName("event_id")    val eventId:    String       = "",
    @SerializedName("title")       val title:      String       = "",
    @SerializedName("sport")       val sport:      String       = "Other",
    @SerializedName("sport_icon")  val sportIcon:  String       = "live_tv",
    @SerializedName("sport_color") val sportColor: String       = "#E53935",
    @SerializedName("start_time")  val startTime:  String       = "",
    @SerializedName("end_time")    val endTime:    String       = "",
    @SerializedName("is_live")     val isLive:     Boolean      = false,
    @SerializedName("streams")     val streams:    List<EventStream> = emptyList(),
    @SerializedName("source")      val source:     String       = ""
)

// ─── Root JSON envelope ──────────────────────────────────────────────────────
data class EventsResponse(
    @SerializedName("last_updated")  val lastUpdated:  String          = "",
    @SerializedName("active_events") val activeEvents: List<LiveEvent> = emptyList()
)
