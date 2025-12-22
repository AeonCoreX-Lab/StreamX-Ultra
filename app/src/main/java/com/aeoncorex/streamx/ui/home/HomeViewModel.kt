package com.aeoncorex.streamx.ui.home

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aeoncorex.streamx.model.Channel
import com.aeoncorex.streamx.model.Event
import com.aeoncorex.streamx.util.ChannelCategorizer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit

class HomeViewModel : ViewModel() {

    private val api: IPTVApi = Retrofit.Builder()
        .baseUrl("https://raw.githubusercontent.com/cybernahid-dev/streamx-iptv-data/main/")
        .addConverterFactory(GsonConverterFactory.create())
        .build().create(IPTVApi::class.java)

    // UI-এর জন্য StateFlow
    private val _isLoading = MutableStateFlow(true)
    val isLoading = _isLoading.asStateFlow()

    private val _allChannels = MutableStateFlow<List<Channel>>(emptyList())
    val allChannels = _allChannels.asStateFlow()

    private val _liveEvents = MutableStateFlow<List<Event>>(emptyList())
    val liveEvents = _liveEvents.asStateFlow()

    private val _upcomingEvents = MutableStateFlow<List<Event>>(emptyList())
    val upcomingEvents = _upcomingEvents.asStateFlow()

    init {
        loadAllData()
    }

    fun loadAllData() {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                // ইভেন্ট এবং চ্যানেল একসাথে লোড করা হবে
                launch { loadEvents() }
                launch { loadChannels() }
            } catch (e: Exception) {
                Log.e("HomeViewModel", "Failed to load data", e)
            } finally {
                _isLoading.value = false
            }
        }
    }

    private suspend fun loadEvents() {
        try {
            val allEvents = api.getEvents()["events"] ?: emptyList()
            val now = LocalDateTime.now(ZoneId.systemDefault())
            
            _liveEvents.value = allEvents.filter {
                val startTime = Instant.parse(it.startTime).atZone(ZoneId.systemDefault()).toLocalDateTime()
                startTime.isBefore(now)
            }
            
            _upcomingEvents.value = allEvents.filter {
                val startTime = Instant.parse(it.startTime).atZone(ZoneId.systemDefault()).toLocalDateTime()
                startTime.isAfter(now) && ChronoUnit.HOURS.between(now, startTime) < 4
            }
        } catch (e: Exception) {
            Log.e("HomeViewModel", "Failed to load events", e)
        }
    }

    private suspend fun loadChannels() {
        try {
            val index = api.getIndex()
            val cats = index["categories"] as? List<Map<String, Any>>
            val masterList = mutableListOf<Channel>()
            cats?.forEach { cat ->
                val fileName = cat["file"] as String
                val catName = cat["name"] as String
                if (fileName.contains("events.json")) return@forEach
                try {
                    val res = api.getChannelsByUrl(fileName)
                    val rawChannels = (res["channels"] as? List<Map<String, Any>>) ?: emptyList()
                    rawChannels.forEach { ch ->
                         masterList.add(Channel(
                            id = (ch["id"] as? String) ?: "",
                            name = (ch["name"] as? String) ?: "No Name",
                            logoUrl = (ch["logoUrl"] as? String) ?: "",
                            streamUrls = (ch["streamUrls"] as? List<String>) ?: emptyList(),
                            country = catName,
                            genre = ChannelCategorizer.getGenreFromString(ch["genre"] as? String),
                            isFeatured = (ch["isFeatured"] as? Boolean) ?: false
                        ))
                    }
                } catch (e: Exception) { Log.e("HomeViewModel", "Error loading category file: $fileName", e) }
            }
            _allChannels.value = masterList
        } catch (e: Exception) {
            Log.e("HomeViewModel", "Failed to load channels", e)
        }
    }
}