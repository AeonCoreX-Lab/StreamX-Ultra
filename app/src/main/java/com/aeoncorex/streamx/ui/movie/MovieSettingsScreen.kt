package com.aeoncorex.streamx.ui.movie

import androidx.compose.foundation.background
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
    // Mock State for UI demonstration
    var autoplay by remember { mutableStateOf(true) }
    var useExternalPlayer by remember { mutableStateOf(false) }
    var hardwareAcceleration by remember { mutableStateOf(true) }
    var defaultQuality by remember { mutableStateOf("Auto (1080p)") }

    Scaffold(
        containerColor = Color.Black,
        topBar = {
            TopAppBar(
                title = { Text("MOVIE CONFIG", color = Color.White, fontWeight = FontWeight.Black, letterSpacing = 1.sp) },
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
            // Subtle Background Gradient
            Box(Modifier.fillMaxSize().background(
                Brush.verticalGradient(listOf(Color(0xFF0A0A10), Color.Black))
            ))

            LazyColumn(contentPadding = PaddingValues(16.dp)) {
                item { SectionHeader("PLAYBACK") }
                item {
                    SwitchSettingItem("Autoplay Next Episode", "Automatically start next video", autoplay) { autoplay = it }
                }
                item {
                    SwitchSettingItem("Hardware Acceleration", "Smoother playback on supported devices", hardwareAcceleration) { hardwareAcceleration = it }
                }

                item { SectionHeader("VIDEO & AUDIO") }
                item {
                    ValueSettingItem("Default Quality", defaultQuality) { 
                        // In real app, open dialog
                        defaultQuality = if(defaultQuality == "Auto (1080p)") "4K UHD" else "Auto (1080p)"
                    }
                }
                item {
                     SwitchSettingItem("Use External Player", "Open links in VLC/MX Player", useExternalPlayer) { useExternalPlayer = it }
                }
                
                item { SectionHeader("SOURCES & API") }
                item {
                     ValueSettingItem("Content Region", "Global (Default)") {}
                }
                item {
                     ValueSettingItem("Clear Cache", "128 MB Used") {}
                }
            }
        }
    }
}

@Composable
fun SectionHeader(title: String) {
    Text(
        text = title,
        color = Color.Cyan,
        fontSize = 12.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(top = 24.dp, bottom = 8.dp, start = 8.dp)
    )
}

@Composable
fun SwitchSettingItem(title: String, subtitle: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
            .background(Color(0xFF15151A), RoundedCornerShape(12.dp))
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
            Text(subtitle, color = Color.Gray, fontSize = 12.sp)
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.Black,
                checkedTrackColor = Color.Cyan,
                uncheckedThumbColor = Color.Gray,
                uncheckedTrackColor = Color.DarkGray
            )
        )
    }
}

@Composable
fun ValueSettingItem(title: String, value: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
            .background(Color(0xFF15151A), RoundedCornerShape(12.dp))
            .clickable { onClick() }
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(title, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
        Text(value, color = Color.Cyan, fontSize = 14.sp)
    }
}
