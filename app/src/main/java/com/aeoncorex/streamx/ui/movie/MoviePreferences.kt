package com.aeoncorex.streamx.ui.movie

import androidx.compose.runtime.mutableStateOf

// রিয়েল-টাইম সেটিংস ম্যানেজ করার জন্য সিঙ্গেলটন অবজেক্ট
object MoviePreferences {
    val useExternalPlayer = mutableStateOf(false)
    val autoPlayNext = mutableStateOf(true)
    val defaultQuality = mutableStateOf("Auto")
}
