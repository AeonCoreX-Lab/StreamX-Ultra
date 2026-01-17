package com.aeoncorex.streamx.ui.movie

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import java.net.URLEncoder

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MovieServerSelectionScreen(
    navController: NavController,
    movieId: Int,
    movieTitle: String,
    movieType: String,
    season: Int,
    episode: Int
) {
    val context = LocalContext.current
    val useExternalPlayer by remember { MoviePreferences.useExternalPlayer }
    val displayTitle = if(movieType == "SERIES") "$movieTitle S$season:E$episode" else movieTitle

    Scaffold(
        containerColor = Color.Black,
        topBar = {
            TopAppBar(
                title = { Text("SELECT SERVER", color = Color.White, fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.Default.ArrowBack, null, tint = Color.White)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Black)
            )
        }
    ) { padding ->
        LazyColumn(modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp)) {
            item {
                Text(
                    text = "Streaming: $displayTitle",
                    color = Color.Cyan,
                    fontSize = 16.sp,
                    modifier = Modifier.padding(bottom = 24.dp)
                )
            }

            items(availableServers) { server ->
                ServerCard(server) {
                    // 1. URL Generate Logic
                    val baseUrl = server.urlTemplate
                    val finalUrl = if (movieType == "SERIES") {
                        // Series Logic: .../tv/ID/SEASON/EPISODE
                        if(server.name.contains("SuperEmbed")) 
                            "${baseUrl}${movieId}&tmdb=1&s=$season&e=$episode"
                        else 
                            "${baseUrl}tv/$movieId/$season/$episode"
                    } else {
                        // Movie Logic
                        if(server.name.contains("SuperEmbed"))
                             "${baseUrl}${movieId}&tmdb=1"
                        else
                             "${baseUrl}movie/$movieId"
                    }

                    // 2. Player Launch Logic
                    if (useExternalPlayer) {
                        try {
                            val intent = Intent(Intent.ACTION_VIEW)
                            intent.setDataAndType(Uri.parse(finalUrl), "video/*")
                            // এই ফ্ল্যাগগুলো ব্রাউজার বা প্লেয়ার ওপেন করতে সাহায্য করে
                            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                            context.startActivity(Intent.createChooser(intent, "Open with..."))
                        } catch (e: ActivityNotFoundException) {
                            Toast.makeText(context, "No video player found!", Toast.LENGTH_SHORT).show()
                            // Fallback to internal if fails
                            val encoded = URLEncoder.encode(finalUrl, "UTF-8")
                            navController.navigate("movie_player/$encoded")
                        }
                    } else {
                        // Internal Player
                        val encoded = URLEncoder.encode(finalUrl, "UTF-8")
                        navController.navigate("movie_player/$encoded")
                    }
                }
            }
        }
    }
}

@Composable
fun ServerCard(server: StreamServer, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp).clickable { onClick() },
        colors = CardDefaults.cardColors(containerColor = Color(0xFF15151A)),
        border = BorderStroke(1.dp, Color.DarkGray),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.PlayArrow, null, tint = Color.Green)
                Spacer(Modifier.width(16.dp))
                Column {
                    Text(server.name, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    if(server.isMultiLang) {
                        Text("Multi-Audio (Hindi/Eng)", color = Color.Yellow, fontSize = 11.sp)
                    }
                }
            }
            Surface(color = Color.DarkGray, shape = RoundedCornerShape(4.dp)) {
                Text(server.quality, color = Color.White, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp), fontSize = 12.sp)
            }
        }
    }
}
