package com.aeoncorex.streamx.ui.movie

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MovieSettingsScreen(navController: NavController) {
    // Global State থেকে ডাটা রিড এবং রাইট করা হচ্ছে
    var useExternal by remember { MoviePreferences.useExternalPlayer }
    var autoPlay by remember { MoviePreferences.autoPlayNext }
    var quality by remember { MoviePreferences.defaultQuality }

    Scaffold(
        containerColor = Color.Black,
        topBar = {
            TopAppBar(
                title = { Text("PLAYER SETTINGS", color = Color.White, fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.Default.ArrowBack, null, tint = Color.Cyan)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)
            )
        }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF0F0F15), Color.Black))))

            LazyColumn(contentPadding = PaddingValues(16.dp)) {
                item { SectionHeader("PLAYBACK BEHAVIOR") }
                item {
                    SwitchSettingItem(
                        "External Player",
                        "Use VLC/MX Player instead of built-in player",
                        useExternal
                    ) { useExternal = it }
                }
                item {
                    SwitchSettingItem(
                        "Auto-Play Next",
                        "Automatically play next episode",
                        autoPlay
                    ) { autoPlay = it }
                }

                item { SectionHeader("PREFERENCES") }
                item {
                    ValueSettingItem("Default Quality", quality) { 
                        quality = if (quality == "Auto") "1080p" else "Auto"
                    }
                }
                item { ValueSettingItem("Server Region", "Global (Fastest)") {} }
            }
        }
    }
}

@Composable
fun SectionHeader(title: String) {
    Text(title, color = Color.Cyan, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 24.dp, bottom = 8.dp, start = 8.dp))
}

@Composable
fun SwitchSettingItem(title: String, subtitle: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp).background(Color(0xFF1A1A1A), RoundedCornerShape(12.dp)).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = Color.White, fontWeight = FontWeight.SemiBold)
            Text(subtitle, color = Color.Gray, fontSize = 12.sp)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange, colors = SwitchDefaults.colors(checkedThumbColor = Color.Black, checkedTrackColor = Color.Cyan))
    }
}

@Composable
fun ValueSettingItem(title: String, value: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp).background(Color(0xFF1A1A1A), RoundedCornerShape(12.dp)).clickable { onClick() }.padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(title, color = Color.White, fontWeight = FontWeight.SemiBold)
        Text(value, color = Color.Cyan)
    }
}
