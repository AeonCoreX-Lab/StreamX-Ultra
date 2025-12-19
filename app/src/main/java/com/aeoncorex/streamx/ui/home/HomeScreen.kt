package com.aeoncorex.streamx.ui.home

import android.util.Log
import androidx.compose.animation.animateColor
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import coil.compose.AsyncImage
import com.aeoncorex.streamx.model.Channel
import com.google.accompanist.pager.ExperimentalPagerApi
import com.google.accompanist.pager.HorizontalPager
import com.google.accompanist.pager.calculateCurrentOffsetForPage
import com.google.accompanist.pager.rememberPagerState
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.GET
import retrofit2.http.Url
import java.net.URLEncoder
import kotlin.math.absoluteValue

// Retrofit API setup
interface IPTVApi {
    @GET("index.json")
    suspend fun getIndex(): Map<String, Any>
    @GET
    suspend fun getChannelsByUrl(@Url url: String): Map<String, Any>
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalPagerApi::class)
@Composable
fun HomeScreen(navController: NavController) {
    val allChannels = remember { mutableStateOf<List<Channel>>(emptyList()) }
    val categories = remember { mutableStateOf<List<String>>(listOf("All")) }
    var selectedCategory by remember { mutableStateOf("All") }
    var isLoading by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        try {
            val api = Retrofit.Builder()
                .baseUrl("https://raw.githubusercontent.com/cybernahid-dev/streamx-iptv-data/main/")
                .addConverterFactory(GsonConverterFactory.create())
                .build().create(IPTVApi::class.java)
            
            val index = api.getIndex()
            val cats = index["categories"] as? List<Map<String, Any>>
            val masterList = mutableListOf<Channel>()

            cats?.forEach { cat ->
                val fileName = cat["file"] as String
                val catName = cat["name"] as String
                try {
                    val res = api.getChannelsByUrl(fileName)
                    val rawChannels = (res["channels"] as? List<Map<String, Any>>) 
                        ?: (res["categories"] as? List<Map<String, Any>>)?.flatMap { it["channels"] as List<Map<String, Any>> }
                    
                    rawChannels?.forEach { ch ->
                        masterList.add(Channel(
                            id = (ch["id"] as? String) ?: "",
                            name = (ch["name"] as? String) ?: "No Name",
                            logoUrl = (ch["logoUrl"] as? String) ?: "",
                            streamUrl = (ch["streamUrl"] as? String) ?: "",
                            category = catName,
                            isFeatured = (ch["isFeatured"] as? Boolean) ?: false
                        ))
                    }
                } catch (e: Exception) { Log.e("API", "Error parsing category: $catName", e) }
            }
            allChannels.value = masterList
            categories.value = listOf("All") + masterList.map { it.category }.distinct()
            isLoading = false
        } catch (e: Exception) { 
            Log.e("API", "Error fetching index", e)
            isLoading = false 
        }
    }

    val infiniteTransition = rememberInfiniteTransition(label = "aurora_bg_transition")
    val color1 by infiniteTransition.animateColor(initialValue = Color(0xFF3B82F6), targetValue = Color(0xFF9333EA), animationSpec = infiniteRepeatable(tween(5000), RepeatMode.Reverse), label = "aurora_color1")
    val color2 by infiniteTransition.animateColor(initialValue = Color(0xFFEC4899), targetValue = Color(0xFF10B981), animationSpec = infiniteRepeatable(tween(7000), RepeatMode.Reverse), label = "aurora_color2")
    val bgBrush = Brush.linearGradient(listOf(color1, color2))

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("STREAMX ULTRA", fontWeight = FontWeight.Black, letterSpacing = 2.sp) },
                actions = { IconButton(onClick = { /* TODO: Search */ }) { Icon(Icons.Default.Search, null, tint = Color.White) } },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor = Color.Transparent, titleContentColor = Color.White)
            )
        },
        containerColor = Color.Transparent
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().background(Color(0xFF020617))) {
            Box(modifier = Modifier.fillMaxSize().blur(150.dp).background(bgBrush))

            if (isLoading) {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.Center), color = Color.Cyan)
            } else {
                LazyColumn(modifier = Modifier.padding(padding)) {
                    item { Spacer(modifier = Modifier.height(16.dp)) }
                    
                    val featured = allChannels.value.filter { it.isFeatured }
                    if (featured.isNotEmpty()) {
                        item { FeaturedCarousel(featured, navController) }
                    }
                    
                    item { CategoryTabs(categories.value, selectedCategory) { selectedCategory = it } }
                    
                    val filteredChannels = if (selectedCategory == "All") allChannels.value else allChannels.value.filter { it.category == selectedCategory }
                    itemsIndexed(filteredChannels.chunked(3)) { index, rowChannels ->
                        var isVisible by remember { mutableStateOf(false) }
                        LaunchedEffect(Unit) { isVisible = true }
                        
                        [span_1](start_span)// FIX: Explicitly specified generic types for tween animations[span_1](end_span)
                        val alpha by animateFloatAsState(
                            targetValue = if(isVisible) 1f else 0f, 
                            animationSpec = tween<Float>(durationMillis = 300, delayMillis = index * 100)
                        )
                        val offsetY by animateDpAsState(
                            targetValue = if(isVisible) 0.dp else 50.dp, 
                            animationSpec = tween<androidx.compose.ui.unit.Dp>(durationMillis = 300, delayMillis = index * 100)
                        )
                        
                        Row(
                            Modifier
                                .padding(horizontal = 16.dp, vertical = 8.dp)
                                .graphicsLayer { 
                                    this.alpha = alpha
                                    this.translationY = offsetY.toPx() 
                                },
                            horizontalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            rowChannels.forEach { channel -> 
                                ChannelCard(channel, Modifier.weight(1f)) {
                                    val encodedUrl = URLEncoder.encode(channel.streamUrl, "UTF-8")
                                    navController.navigate("player/$encodedUrl")
                                }
                            }
                            if (rowChannels.size < 3) {
                                repeat(3 - rowChannels.size) { Spacer(Modifier.weight(1f)) }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ChannelCard(channel: Channel, modifier: Modifier, onClick: () -> Unit) {
    Column(
        modifier = modifier.clickable { onClick() },
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .aspectRatio(1f)
                .clip(RoundedCornerShape(24.dp))
                .background(Color.White.copy(0.1f))
                .border(
                    width = 1.dp, 
                    brush = Brush.horizontalGradient(listOf(Color.White.copy(0.2f), Color.Transparent)), 
                    shape = RoundedCornerShape(24.dp)
                )
                .padding(16.dp),
            contentAlignment = Alignment.Center
        ) {
            AsyncImage(model = channel.logoUrl, contentDescription = channel.name, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
        }
        Text(channel.name, color = Color.White.copy(0.8f), fontSize = 12.sp, maxLines = 1, modifier = Modifier.padding(top = 8.dp))
    }
}

@Composable
fun CategoryTabs(categories: List<String>, selected: String, onSelect: (String) -> Unit) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        items(categories) { cat ->
            val isSelected = cat == selected
            val brush = if (isSelected) Brush.horizontalGradient(listOf(Color(0xFF3B82F6), Color.Cyan)) else Brush.horizontalGradient(listOf(Color.White.copy(0.1f), Color.White.copy(0.05f)))
            
            Text(
                cat, 
                modifier = Modifier
                    .clip(CircleShape)
                    .background(brush)
                    .clickable { onSelect(cat) }
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                color = if (isSelected) Color.Black else Color.White,
                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                fontSize = 14.sp
            )
        }
    }
}

@OptIn(ExperimentalPagerApi::class)
@Composable
fun FeaturedCarousel(featured: List<Channel>, navController: NavController) {
    val pagerState = rememberPagerState()
    
    Column {
        Text(
            "Featured", 
            style = MaterialTheme.typography.titleLarge, 
            fontWeight = FontWeight.Bold, 
            color = Color.White, 
            modifier = Modifier.padding(start = 16.dp, bottom = 12.dp)
        )
        HorizontalPager(
            count = featured.size,
            state = pagerState,
            contentPadding = PaddingValues(horizontal = 48.dp)
        ) { page ->
            val pageOffset = calculateCurrentOffsetForPage(page).absoluteValue
            val scale = lerp(1f, 0.85f, pageOffset)
            
            Card(
                modifier = Modifier
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        alpha = lerp(1f, 0.5f, pageOffset)
                    }
                    .width(300.dp)
                    .height(160.dp)
                    .clickable { 
                        val encodedUrl = URLEncoder.encode(featured[page].streamUrl, "UTF-8")
                        navController.navigate("player/$encodedUrl")
                    },
                shape = RoundedCornerShape(20.dp)
            ) {
                Box {
                    AsyncImage(model = featured[page].logoUrl, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black))))
                    Text(
                        featured[page].name, 
                        modifier = Modifier.align(Alignment.BottomStart).padding(16.dp), 
                        color = Color.White, 
                        fontWeight = FontWeight.Bold, 
                        fontSize = 18.sp
                    )
                }
            }
        }
    }
}

fun lerp(start: Float, stop: Float, fraction: Float): Float {
    return (1 - fraction) * start + fraction * stop
}
