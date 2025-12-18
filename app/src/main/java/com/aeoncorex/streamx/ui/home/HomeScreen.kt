package com.aeoncorex.streamx.ui.home

import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
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
            categories.value = listOf("All") + channels.map { it.category }.distinct().filter { it.isNotBlank() }

        } catch (e: Exception) {
            Log.e("FirestoreError", "Error fetching data: ", e)
            errorState.value = "Failed to load content. Please check your connection."
        } finally {
            isLoading.value = false
        }
    }
    
    val channelsToShow = if (selectedCategory == "All") {
        allChannels.value
    } else {
        allChannels.value.filter { it.category == selectedCategory }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("StreamX Ultra", fontWeight = FontWeight.Bold) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            )
        }
    ) { padding ->
        if (isLoading.value) {
            LoadingHomeScreen()
        } else if (errorState.value != null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(errorState.value!!, textAlign = TextAlign.Center, modifier = Modifier.padding(16.dp))
            }
        } else {
            LazyColumn(
                modifier = Modifier.padding(padding),
                contentPadding = PaddingValues(bottom = 16.dp)
            ) {
                
                // --- সেকশন ১: লাইভ ইভেন্টস ---
                if (liveEvents.value.isNotEmpty()) {
                    item { LiveEventsSection(navController, liveEvents.value) }
                }

                // --- সেকশন ২: ফিচারড চ্যানেলস ---
                if (featuredChannels.value.isNotEmpty()) {
                    item { FeaturedSection(navController, featuredChannels.value) }
                }

                // --- সেকশন ৩: ক্যাটাগরি ট্যাব ---
                item {
                    CategoryTabs(categories.value, selectedCategory) { newCategory ->
                        selectedCategory = newCategory
                    }
                }

                // --- সেকশন ৪: চ্যানেল গ্রিড ---
                item {
                    ChannelsGrid(navController, channelsToShow)
                }
            }
        }
    }
}

@Composable
fun LiveEventsSection(navController: NavController, events: List<LiveEvent>) {
    Column {
        Text(
            text = "Live Now 🔥",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 12.dp)
        )
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            items(events, key = { it.id }) { event ->
                LiveEventCard(event = event, onClick = {
                    val encodedUrl = encodeUrl(event.streamUrl)
                    navController.navigate("player/$encodedUrl")
                })
            }
        }
    }
}

@Composable
fun LiveEventCard(event: LiveEvent, onClick: () -> Unit) {
    Card(
        modifier = Modifier.width(280.dp).height(160.dp).clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp)
    ) {
        Box(contentAlignment = Alignment.BottomStart) {
            AsyncImage(
                model = ImageRequest.Builder(LocalContext.current).data(event.thumbnailUrl).crossfade(true).build(),
                contentDescription = event.title,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
            Box(modifier = Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.8f)))))
            Text(
                text = event.title,
                color = Color.White,
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp,
                modifier = Modifier.padding(12.dp),
                maxLines = 2
            )
        }
    }
}

@Composable
fun FeaturedSection(navController: NavController, channels: List<Channel>) {
    Column {
        Text(
            text = "Featured Channels",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(start = 16.dp, top = 24.dp, bottom = 12.dp)
        )
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            items(channels, key = { it.id }) { channel ->
                FeaturedChannelCard(channel = channel, onClick = {
                    val encodedUrl = encodeUrl(channel.streamUrl)
                    navController.navigate("player/$encodedUrl")
                })
            }
        }
    }
}

@Composable
fun FeaturedChannelCard(channel: Channel, onClick: () -> Unit) {
    Card(
        modifier = Modifier.width(280.dp).height(160.dp).clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp)
    ) {
        Box(contentAlignment = Alignment.BottomStart) {
            AsyncImage(
                model = ImageRequest.Builder(LocalContext.current).data(channel.logoUrl).crossfade(true).build(),
                contentDescription = channel.name,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
            Box(modifier = Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.8f)))))
            Text(
                text = channel.name,
                color = Color.White,
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp,
                modifier = Modifier.padding(12.dp)
            )
        }
    }
}

@Composable
fun CategoryTabs(categories: List<String>, selectedCategory: String, onCategorySelected: (String) -> Unit) {
    Column {
        Text(
            text = "Categories",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(start = 16.dp, top = 24.dp, bottom = 4.dp)
        )
        ScrollableTabRow(
            selectedTabIndex = categories.indexOf(selectedCategory),
            modifier = Modifier.fillMaxWidth(),
            edgePadding = 16.dp
        ) {
            categories.forEach { category ->
                Tab(
                    selected = category == selectedCategory,
                    onClick = { onCategorySelected(category) },
                    text = { Text(category, fontWeight = FontWeight.SemiBold) }
                )
            }
        }
    }
}

@Composable
fun ChannelsGrid(navController: NavController, channels: List<Channel>) {
    // এই গ্রিডটি Non-lazy, কারণ এটি LazyColumn এর ভেতরে আছে
    val chunkedChannels = channels.chunked(3) // প্রতি রো-তে ৩টি করে আইটেম
    Column(
        modifier = Modifier.padding(horizontal = 12.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        chunkedChannels.forEach { rowChannels ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                rowChannels.forEach { channel ->
                    Box(modifier = Modifier.weight(1f)) {
                        ChannelGridCard(channel = channel, onClick = {
                            val encodedUrl = encodeUrl(channel.streamUrl)
                            navController.navigate("player/$encodedUrl")
                        })
                    }
                }
                // রো-তে ৩টির কম আইটেম থাকলে খালি জায়গা যোগ করা হচ্ছে
                repeat(3 - rowChannels.size) {
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
fun ChannelGridCard(channel: Channel, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1f) // 1:1 aspect ratio for a square look
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(
            modifier = Modifier.padding(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            AsyncImage(
                model = ImageRequest.Builder(LocalContext.current).data(channel.logoUrl).crossfade(true).build(),
                contentDescription = channel.name,
                modifier = Modifier.weight(1f).padding(8.dp),
                contentScale = ContentScale.Fit
            )
            Text(text = channel.name, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center, maxLines = 2)
        }
    }
}

@Composable
fun LoadingHomeScreen() {
    // একটি সুন্দর Shimmer Effect এর জন্য স্কেলিটন UI
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
        // Featured Shimmer
        item {
            Box(modifier = Modifier.fillMaxWidth().height(160.dp).background(Color.LightGray.copy(alpha = 0.5f), RoundedCornerShape(16.dp)))
        }
        // Grid Shimmer
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                repeat(3) {
                    Box(modifier = Modifier.weight(1f).aspectRatio(1f).background(Color.LightGray.copy(alpha = 0.5f), RoundedCornerShape(12.dp)))
                }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                repeat(3) {
                    Box(modifier = Modifier.weight(1f).aspectRatio(1f).background(Color.LightGray.copy(alpha = 0.5f), RoundedCornerShape(12.dp)))
                }
            }
        }
    }
}