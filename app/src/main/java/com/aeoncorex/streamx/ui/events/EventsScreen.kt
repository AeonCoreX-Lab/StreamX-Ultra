package com.aeoncorex.streamx.ui.events

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.aeoncorex.streamx.model.Channel
import com.aeoncorex.streamx.model.Event
import com.aeoncorex.streamx.ui.home.HomeViewModel

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
            TopAppBar(title = { Text("Live & Upcoming Events") })
        }
    ) { padding ->
        LazyColumn(
            contentPadding = padding,
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            if (liveEvents.isNotEmpty()) {
                item {
                    SectionHeader("Live Now 🔥")
                }
                items(liveEvents) { event ->
                    EventCard(event = event, allChannels = allChannels, navController = navController)
                }
            }

            if (upcomingEvents.isNotEmpty()) {
                item {
                    SectionHeader("Upcoming ⏰")
                }
                items(upcomingEvents) { event ->
                    EventCard(event = event, allChannels = allChannels, navController = navController)
                }
            }

            if (liveEvents.isEmpty() && upcomingEvents.isEmpty()) {
                item {
                    Box(modifier = Modifier.fillParentMaxSize(), contentAlignment = androidx.compose.ui.Alignment.Center) {
                        Text("No live or upcoming events found.")
                    }
                }
            }
        }
    }
}

@Composable
fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.headlineSmall,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
    )
}

// TODO: EventCard Composable-এর UI ডিজাইন করতে হবে।
// এটি HD Streamz-এর মতো দুটি দলের লোগো, নাম, সময় এবং নিচে চ্যানেল দেখাবে।
@Composable
fun EventCard(event: Event, allChannels: List<Channel>, navController: NavController) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(event.title, fontWeight = FontWeight.Bold)
            Text(event.tournament, style = MaterialTheme.typography.bodySmall)
            // ... (এখানে আরও বিস্তারিত UI যোগ করতে হবে)
        }
    }
}