package com.aeoncorex.streamx.ui.events

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import coil.compose.AsyncImage
import com.aeoncorex.streamx.model.Channel
import com.aeoncorex.streamx.model.Event
import com.aeoncorex.streamx.ui.home.FuturisticBackground
import com.aeoncorex.streamx.ui.home.HomeViewModel
import java.net.URLEncoder

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EventsScreen(
    navController: NavController,
    homeViewModel: HomeViewModel = viewModel()
) {
    val liveEvents by homeViewModel.liveEvents.collectAsState()
    val upcomingEvents by homeViewModel.upcomingEvents.collectAsState()
    val allChannels by homeViewModel.allChannels.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Sports Events", fontWeight = FontWeight.Bold) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent,
                    titleContentColor = MaterialTheme.colorScheme.onBackground
                )
            )
        },
        containerColor = Color.Transparent
    ) { padding ->
        // Background from HomeScreen
        FuturisticBackground()

        LazyColumn(
            contentPadding = PaddingValues(top = padding.calculateTopPadding(), bottom = 20.dp),
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Live Events Section
            if (liveEvents.isNotEmpty()) {
                item {
                    SectionHeader("Live Now \uD83D\uDD25", Color.Red)
                }
                items(liveEvents) { event ->
                    FuturisticEventCard(
                        event = event,
                        isLive = true,
                        allChannels = allChannels,
                        navController = navController
                    )
                }
            }

            // Upcoming Events Section
            if (upcomingEvents.isNotEmpty()) {
                item {
                    SectionHeader("Upcoming Matches \uD83D\uDD52", MaterialTheme.colorScheme.primary)
                }
                items(upcomingEvents) { event ->
                    FuturisticEventCard(
                        event = event,
                        isLive = false,
                        allChannels = allChannels,
                        navController = navController
                    )
                }
            }

            // Empty State
            if (liveEvents.isEmpty() && upcomingEvents.isEmpty()) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(300.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            "No events scheduled right now.",
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun SectionHeader(title: String, color: Color) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleLarge,
        fontWeight = FontWeight.Bold,
        color = color,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
    )
}

@Composable
fun FuturisticEventCard(
    event: Event,
    isLive: Boolean,
    allChannels: List<Channel>,
    navController: NavController
) {
    // Find the first available channel for this event
    val targetChannel = remember(event, allChannels) {
        allChannels.firstOrNull { it.id in event.channelIds }
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .height(140.dp)
            .padding(horizontal = 16.dp)
            .clickable {
                // Navigate to Player if channel exists
                if (targetChannel != null && targetChannel.streamUrls.isNotEmpty()) {
                    try {
                        val encoded = URLEncoder.encode(targetChannel.streamUrls.first(), "UTF-8")
                        navController.navigate("player/$encoded")
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            },
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = Color.Transparent),
        elevation = CardDefaults.cardElevation(8.dp)
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            // Glassmorphism Background
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.linearGradient(
                            colors = listOf(
                                Color(0xFF1A1A2E).copy(alpha = 0.9f),
                                Color(0xFF16213E).copy(alpha = 0.95f)
                            )
                        )
                    )
            )

            // Content
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                // Team 1
                TeamItem(name = "Team 1", logoUrl = event.team1Logo, modifier = Modifier.weight(1f))

                // VS / Time Section
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.weight(0.8f)
                ) {
                    Text(
                        text = event.tournament,
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.Gray,
                        maxLines = 1
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    
                    if (isLive) {
                        Text(
                            text = "LIVE",
                            color = Color.White,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier
                                .background(Color.Red, RoundedCornerShape(4.dp))
                                .padding(horizontal = 8.dp, vertical = 2.dp)
                        )
                    } else {
                         // Parse Time nicely if possible, else show raw or default
                         Text(
                            text = formatTime(event.startTime), 
                            color = MaterialTheme.colorScheme.primary,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "VS",
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Black,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.2f)
                    )
                }

                // Team 2
                TeamItem(name = "Team 2", logoUrl = event.team2Logo, modifier = Modifier.weight(1f))
            }
        }
    }
}

@Composable
fun TeamItem(name: String, logoUrl: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        // Team Logo Circle
        Box(
            modifier = Modifier
                .size(60.dp)
                .background(Color.White.copy(alpha = 0.1f), CircleShape)
                .padding(8.dp),
            contentAlignment = Alignment.Center
        ) {
            AsyncImage(
                model = logoUrl,
                contentDescription = name,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit,
                // Placeholder styling if needed
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
        // We might not have team names in the Event model separate from title, 
        // so we can leave this blank or try to parse from title if needed.
        // For now, relying on Logo is cleaner for HD Streamz look.
    }
}

// Simple helper to format ISO time string to something readable (e.g., "19:30")
fun formatTime(isoString: String): String {
    return try {
        // Very basic extraction for display purposes to avoid crashing without heavy libraries
        // Expected: "2025-12-25T19:30:00Z" -> "19:30"
        if (isoString.contains("T")) {
            isoString.substringAfter("T").substring(0, 5)
        } else {
            isoString
        }
    } catch (e: Exception) {
        "Soon"
    }
}
