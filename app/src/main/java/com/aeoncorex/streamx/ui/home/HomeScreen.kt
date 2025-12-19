package com.aeoncorex.streamx.ui.home
import androidx.compose.material.icons.filled.Dashboard

import android.util.Log
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.aeoncorex.streamx.model.Channel
import com.aeoncorex.streamx.model.LiveEvent
import com.aeoncorex.streamx.navigation.encodeUrl
import com.google.firebase.Timestamp
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.firestore.ktx.toObjects
import com.google.firebase.ktx.Firebase
import com.valentinilk.shimmer.shimmer
import kotlinx.coroutines.tasks.await

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(navController: NavController) {
    val db = Firebase.firestore
    val liveEvents = remember { mutableStateOf<List<LiveEvent>>(emptyList()) }
    val featuredChannels = remember { mutableStateOf<List<Channel>>(emptyList()) }
    val allChannels = remember { mutableStateOf<List<Channel>>(emptyList()) }
    val categories = remember { mutableStateOf<List<String>>(emptyList()) }
    var selectedCategory by remember { mutableStateOf("All") }
    val isLoading = remember { mutableStateOf(true) }
    val errorState = remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        try {
            isLoading.value = true
            val eventsSnapshot = db.collection("live_events").whereEqualTo("isLive", true).get().await()
            val channelsSnapshot = db.collection("channels").get().await()

            val now = Timestamp.now().seconds
            liveEvents.value = eventsSnapshot.toObjects<LiveEvent>().filter { it.startTime.seconds <= now && it.endTime.seconds >= now }

            val channels = channelsSnapshot.toObjects<Channel>()
            featuredChannels.value = channels.filter { it.isFeatured }
            allChannels.value = channels
            categories.value = listOf("All") + channels.mapNotNull { it.category.takeIf { c -> c.isNotBlank() } }.distinct()

        } catch (e: Exception) {
            Log.e("FirestoreError", "Error fetching data: ", e)
            errorState.value = "Failed to load content. Check Firestore rules and internet connection."
        } finally {
            isLoading.value = false
        }
    }
    
    val channelsToShow = if (selectedCategory == "All") allChannels.value else allChannels.value.filter { it.category == selectedCategory }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("StreamX Ultra", fontWeight = FontWeight.ExtraBold) },
                actions = {
                    IconButton(onClick = { navController.navigate("multi_view") }) {
                        Icon(Icons.Default.Dashboard, contentDescription = "Multi-View")
                    }
                    IconButton(onClick = { /* TODO: Navigate to Search Screen */ }) {
                        Icon(Icons.Default.Search, contentDescription = "Search")
                    }
                    IconButton(onClick = { /* TODO: Navigate to Notifications Screen */ }) {
                        Icon(Icons.Default.Notifications, contentDescription = "Notifications")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        }
    ) { padding ->
        if (isLoading.value) {
            LoadingShimmerEffect(padding)
        } else if (errorState.value != null) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text(errorState.value!!, textAlign = TextAlign.Center, modifier = Modifier.padding(16.dp))
            }
        } else {
            LazyColumn(modifier = Modifier.padding(padding), contentPadding = PaddingValues(bottom = 16.dp)) {
                
                if (liveEvents.value.isNotEmpty()) {
                    item { LiveEventsSection(navController, liveEvents.value) }
                }

                if (featuredChannels.value.isNotEmpty()) {
                    item { FeaturedSection(navController, featuredChannels.value) }
                }

                item {
                    CategoryTabs(categories.value, selectedCategory) { newCategory ->
                        selectedCategory = newCategory
                    }
                }

                // চ্যানেল গ্রিড
                val chunkedChannels = channelsToShow.chunked(3)
                items(chunkedChannels.size) { index ->
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp).padding(bottom = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        chunkedChannels[index].forEach { channel ->
                            Box(modifier = Modifier.weight(1f)) {
                                ChannelGridCard(channel = channel, onClick = {
                                    val encodedUrl = encodeUrl(channel.streamUrl)
                                    navController.navigate("player/${channel.id}/$encodedUrl")
                                })
                            }
                        }
                        // রো-তে ৩টির কম আইটেম থাকলে খালি জায়গা যোগ করা হচ্ছে
                        repeat(3 - chunkedChannels[index].size) {
                            Spacer(modifier = Modifier.weight(1f))
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun LiveEventsSection(navController: NavController, events: List<LiveEvent>) {
    Column {
        Text("Live Now 🔥", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 12.dp))
        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            items(events, key = { it.id }) { event ->
                LiveEventCard(event = event, onClick = { navController.navigate("player/${encodeUrl(event.streamUrl)}") })
            }
        }
    }
}

@Composable
fun LiveEventCard(event: LiveEvent, onClick: () -> Unit) {
    Card(modifier = Modifier.width(280.dp).height(160.dp).clickable(onClick = onClick), shape = RoundedCornerShape(16.dp)) {
        Box(contentAlignment = Alignment.BottomStart) {
            AsyncImage(model = ImageRequest.Builder(LocalContext.current).data(event.thumbnailUrl).crossfade(true).build(), contentDescription = event.title, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            Box(modifier = Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.9f)))))
            
            Row(modifier = Modifier.align(Alignment.TopEnd).padding(8.dp).background(Color.Red.copy(alpha = 0.8f), CircleShape).padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                PulsatingLiveIndicator()
                Spacer(modifier = Modifier.width(4.dp))
                Text("LIVE", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            }

            Text(event.title, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp, modifier = Modifier.padding(12.dp), maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
fun PulsatingLiveIndicator() {
    val infiniteTransition = rememberInfiniteTransition(label = "pulsating")
    val scale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.2f,
        animationSpec = infiniteRepeatable(
            animation = tween(600),
            repeatMode = RepeatMode.Reverse
        ), label = "pulsating_scale"
    )
    Box(modifier = Modifier.size(6.dp).graphicsLayer { scaleX = scale; scaleY = scale }.clip(CircleShape).background(Color.White))
}

@Composable
fun FeaturedSection(navController: NavController, channels: List<Channel>) {
    Column {
        Text("Featured Channels", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 16.dp, top = 24.dp, bottom = 12.dp))
        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            items(channels, key = { it.id }) { channel ->
                Card(modifier = Modifier.width(240.dp).height(140.dp).clickable { navController.navigate("player/${encodeUrl(channel.streamUrl)}") }, shape = RoundedCornerShape(16.dp)) {
                    Box(contentAlignment = Alignment.BottomStart) {
                        AsyncImage(model = ImageRequest.Builder(LocalContext.current).data(channel.logoUrl).crossfade(true).build(), contentDescription = channel.name, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                        Box(modifier = Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.8f)))))
                        Text(channel.name, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp, modifier = Modifier.padding(12.dp))
                    }
                }
            }
        }
    }
}

@Composable
fun CategoryTabs(categories: List<String>, selectedCategory: String, onCategorySelected: (String) -> Unit) {
    ScrollableTabRow(selectedTabIndex = categories.indexOf(selectedCategory), modifier = Modifier.fillMaxWidth().padding(top = 24.dp), edgePadding = 16.dp, divider = {})
    {
        categories.forEach { category ->
            Tab(selected = category == selectedCategory, onClick = { onCategorySelected(category) },
                text = { Text(category, fontWeight = if (category == selectedCategory) FontWeight.Bold else FontWeight.Normal) }
            )
        }
    }
}

@Composable
fun ChannelGridCard(channel: Channel, onClick: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth().aspectRatio(1f).clickable(onClick = onClick), shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(modifier = Modifier.padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            AsyncImage(model = ImageRequest.Builder(LocalContext.current).data(channel.logoUrl).crossfade(true).build(), contentDescription = channel.name, modifier = Modifier.weight(1f).padding(12.dp), contentScale = ContentScale.Fit)
            Text(text = channel.name, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 4.dp))
        }
    }
}

@Composable
fun LoadingShimmerEffect(padding: PaddingValues) {
    val shimmerBrush = Brush.linearGradient(
        colors = listOf(
            Color.Gray.copy(alpha = 0.5f),
            Color.Gray.copy(alpha = 0.2f),
            Color.Gray.copy(alpha = 0.5f),
        )
    )
    LazyColumn(modifier = Modifier.padding(padding), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
        // Shimmer for Featured Section
        item {
            Spacer(modifier = Modifier.fillMaxWidth().height(160.dp).shimmer(brush = shimmerBrush).background(Color.Gray.copy(alpha = 0.3f), RoundedCornerShape(16.dp)))
        }
        // Shimmer for Grid Section
        item {
            Spacer(modifier = Modifier.height(32.dp)) // For CategoryTabs
        }
        items(2) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                repeat(3) {
                    Spacer(modifier = Modifier.weight(1f).aspectRatio(1f).shimmer(brush = shimmerBrush).background(Color.Gray.copy(alpha = 0.3f), RoundedCornerShape(16.dp)))
                }
            }
        }
    }
}