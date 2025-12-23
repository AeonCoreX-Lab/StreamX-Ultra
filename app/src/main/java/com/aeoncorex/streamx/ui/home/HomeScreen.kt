package com.aeoncorex.streamx.ui.home

import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import coil.compose.AsyncImage
import com.aeoncorex.streamx.model.Channel
import kotlinx.coroutines.delay
import java.net.URLEncoder

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    navController: NavController,
    openDrawer: () -> Unit,
    homeViewModel: HomeViewModel = viewModel()
) {
    val allChannels by homeViewModel.allChannels.collectAsState()
    val isLoading by homeViewModel.isLoading.collectAsState()
    
    // Group channels for display (HD Streamz style categorization)
    val featuredChannels = remember(allChannels) { allChannels.filter { it.isFeatured } }
    val sportsChannels = remember(allChannels) { allChannels.filter { it.genre.name == "SPORTS" } }
    val newsChannels = remember(allChannels) { allChannels.filter { it.genre.name == "NEWS" } }
    val entertainmentChannels = remember(allChannels) { allChannels.filter { it.genre.name == "ENTERTAINMENT" } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("STREAMX ULTRA", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = openDrawer) {
                        Icon(Icons.Default.Menu, contentDescription = "Menu")
                    }
                },
                actions = {
                    IconButton(onClick = { /* Search Logic */ }) {
                        Icon(Icons.Default.Search, contentDescription = "Search")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent,
                    titleContentColor = MaterialTheme.colorScheme.primary
                )
            )
        },
        containerColor = Color.Transparent // Background handle kora ache FuturisticBackground diye
    ) { padding ->
        FuturisticBackground() // আপনার আগের গ্রেডিয়েন্ট ব্যাকগ্রাউন্ড
        
        if (isLoading) {
             Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                 CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
             }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(bottom = 20.dp)
            ) {
                // 1. Featured Slider (Top Carousel)
                if (featuredChannels.isNotEmpty()) {
                    item {
                        Text(
                            "Featured Live \uD83D\uDD25",
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onBackground
                        )
                        FeaturedSlider(channels = featuredChannels, navController = navController)
                        Spacer(modifier = Modifier.height(16.dp))
                    }
                }

                // 2. HD Streamz Style Horizontal Categories
                item { CategoryRow(title = "Live Sports ⚽", channels = sportsChannels, navController = navController) }
                item { CategoryRow(title = "Breaking News \uD83D\uDCF0", channels = newsChannels, navController = navController) }
                item { CategoryRow(title = "Entertainment \uD83C\uDFAC", channels = entertainmentChannels, navController = navController) }
                
                // 3. Country Wise (Fallback)
                item {
                    SectionTitle(title = "Explore by Country", onSeeAllClick = {})
                    // Country chips logic here if needed
                }
            }
        }
    }
}

@Composable
fun FeaturedSlider(channels: List<Channel>, navController: NavController) {
    val pagerState = rememberPagerState(pageCount = { channels.size })
    
    // Auto Scroll Logic
    LaunchedEffect(pagerState) {
        while(true) {
            delay(4000)
            val nextPage = (pagerState.currentPage + 1) % channels.size
            pagerState.animateScrollToPage(nextPage)
        }
    }

    Column {
        HorizontalPager(
            state = pagerState,
            contentPadding = PaddingValues(horizontal = 24.dp),
            pageSpacing = 16.dp
        ) { page ->
            val channel = channels[page]
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(200.dp)
                    .clickable { 
                         if (channel.streamUrls.isNotEmpty()) {
                            val encoded = URLEncoder.encode(channel.streamUrls.first(), "UTF-8")
                            navController.navigate("player/$encoded")
                        }
                    },
                shape = RoundedCornerShape(24.dp),
                elevation = CardDefaults.cardElevation(8.dp)
            ) {
                Box(modifier = Modifier.fillMaxSize()) {
                    // Logo / Banner
                    AsyncImage(
                        model = channel.logoUrl,
                        contentDescription = null,
                        contentScale = ContentScale.Crop, // Use Crop for full banner feel
                        modifier = Modifier.fillMaxSize()
                    )
                    // Gradient Overlay for Text Readability
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(
                                Brush.verticalGradient(
                                    colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.9f))
                                )
                            )
                    )
                    // Text Content
                    Column(
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .padding(16.dp)
                    ) {
                        Text(
                            text = "LIVE NOW",
                            color = Color.Red,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier
                                .background(Color.White, RoundedCornerShape(4.dp))
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = channel.name,
                            color = Color.White,
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Black
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun CategoryRow(title: String, channels: List<Channel>, navController: NavController) {
    if (channels.isEmpty()) return
    
    Column(modifier = Modifier.padding(vertical = 12.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(text = title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
            Text(text = "See All", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge, modifier = Modifier.clickable { })
        }
        
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            items(channels) { channel ->
                FuturisticChannelCard(channel = channel, onClick = {
                     if (channel.streamUrls.isNotEmpty()) {
                        val encoded = URLEncoder.encode(channel.streamUrls.first(), "UTF-8")
                        navController.navigate("player/$encoded")
                    }
                })
            }
        }
    }
}

@Composable
fun FuturisticChannelCard(channel: Channel, onClick: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.width(110.dp).clickable(onClick = onClick)
    ) {
        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
            modifier = Modifier.size(110.dp).padding(4.dp),
            elevation = CardDefaults.cardElevation(4.dp)
        ) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                AsyncImage(
                    model = channel.logoUrl,
                    contentDescription = channel.name,
                    modifier = Modifier.size(70.dp),
                    contentScale = ContentScale.Fit
                )
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = channel.name,
            maxLines = 1,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.9f)
        )
    }
}
