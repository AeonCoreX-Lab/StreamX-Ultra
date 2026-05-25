package com.aeoncorex.streamx.model

import com.google.gson.annotations.SerializedName

// ─── Single stream server ─────────────────────────────────────────
data class EventStream(
    @SerializedName("name") val name: String = "",
    @SerializedName("url")  val url:  String = ""
)

// ─── One live/upcoming event ──────────────────────────────────────
data class LiveEvent(
    @SerializedName("event_id")     val eventId:     String            = "",
    @SerializedName("title")        val title:        String           = "",
    @SerializedName("sport")        val sport:        String           = "Other",
    @SerializedName("sport_icon")   val sportIcon:    String           = "live_tv",
    @SerializedName("sport_color")  val sportColor:   String           = "#E53935",
    @SerializedName("start_time")   val startTime:    String           = "",
    @SerializedName("end_time")     val endTime:      String           = "",
    @SerializedName("is_live")      val isLive:       Boolean          = false,
    @SerializedName("streams")      val streams:      List<EventStream> = emptyList(),
    @SerializedName("stream_count") val streamCount:  Int              = 0,
    @SerializedName("has_stream")   val hasStream:    Boolean          = false,
    @SerializedName("source")       val source:       String           = "",
    // ✅ NEW — thumbnail from JSON (e.g. streamed.pk badge images)
    @SerializedName("thumbnail")    val thumbnail:    String           = ""
)

// ─── Root JSON envelope ───────────────────────────────────────────
data class EventsResponse(
    @SerializedName("last_updated")    val lastUpdated:   String          = "",
    @SerializedName("strict_mode")     val strictMode:    Boolean         = false,
    @SerializedName("requires_stream") val requiresStream:Boolean         = false,
    @SerializedName("requires_thumbnail") val requiresThumbnail: Boolean  = false,
    @SerializedName("total_live")      val totalLive:     Int             = 0,
    @SerializedName("total_upcoming")  val totalUpcoming: Int             = 0,
    @SerializedName("total_streamed")  val totalStreamed:  Int            = 0,
    @SerializedName("active_events")   val activeEvents:  List<LiveEvent> = emptyList()
)
