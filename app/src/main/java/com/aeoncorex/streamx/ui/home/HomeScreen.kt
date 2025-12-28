package com.aeoncorex.streamx.ui.home

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import android.widget.Toast
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.rounded.SignalWifiOff
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshContainer
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha // FIXED: Added missing import
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.lerp
import androidx.compose.ui.window.Dialog
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.navigation.NavController
import coil.compose.AsyncImage
import com.aeoncorex.streamx.model.Channel
import com.aeoncorex.streamx.model.GitHubRelease
import com.aeoncorex.streamx.services.UpdateChecker
import com.valentinilk.shimmer.shimmer
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.GET
import retrofit2.http.Url
import java.net.URLEncoder
import kotlin.math.absoluteValue

// DataStore Setup for Persistence
private val Context.dataStore by preferencesDataStore(name = "favorites_prefs")
private val FAVORITES_KEY = stringSetPreferencesKey("favorite_ids")

interface IPTVApi {
    @GET("index.json")
    suspend fun getIndex(): Map<String, Any>
    @GET
    suspend fun getChannelsByUrl(@Url url: String): Map<String, Any>
}

// Utility to check internet
fun isInternetAvailable(context: Context): Boolean {
    val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    val network = connectivityManager.activeNetwork ?: return false
    val activeNetwork = connectivityManager.getNetworkCapabilities(network) ?: return false
    return activeNetwork.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(navController: NavController) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // State Variables
    val allChannels = remember { mutableStateOf<List<Channel>>(emptyList()) }
    val categories = remember { mutableStateOf(listOf("All", "Favorites")) }
    var selectedCategory by remember { mutableStateOf("All") }
    var isLoading by remember { mutableStateOf(true) }
    var searchQuery by remember { mutableStateOf("") }
    var isSearchActive by remember { mutableStateOf(false) }
    var showLinkSelectorDialog by remember { mutableStateOf(false) }
    var selectedChannelForLinks by remember { mutableStateOf<Channel?>(null) }
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    var showUpdateDialog by remember { mutableStateOf(false) }
    var latestReleaseInfo by remember { mutableStateOf<GitHubRelease?>(null) }
    
    // No Internet & Refresh States
    var showNoInternetDialog by remember { mutableStateOf(false) }
    var isRefreshing by remember { mutableStateOf(false) }
    val pullRefreshState = rememberPullToRefreshState()

    val favoriteIds by context.dataStore.data
        .map { preferences -> preferences[FAVORITES_KEY] ?: emptySet() }
        .collectAsState(initial = emptySet())

    // Function to fetch data
    val fetchData: () -> Unit = {
        scope.launch {
            if (!isInternetAvailable(context)) {
                isLoading = false
                isRefreshing = false
                showNoInternetDialog = true
                return@launch
            }

            try {
                // Check Update
                val release = UpdateChecker.checkForUpdate(context)
                if (release != null) {
                    latestReleaseInfo = release
                    showUpdateDialog = true
                }

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
                                id = (ch["id"] as? String) ?: (ch["streamUrls"]?.hashCode()?.toString() ?: ""),
                                name = (ch["name"] as? String) ?: "No Name",
                                logoUrl = (ch["logoUrl"] as? String) ?: "",
                                streamUrls = (ch["streamUrls"] as? List<String>) ?: emptyList(),
                                category = catName,
                                isFeatured = (ch["isFeatured"] as? Boolean) ?: false
                            ))
                        }
                    } catch (e: Exception) { Log.e("API", "Error loading category: $catName", e) }
                }
                allChannels.value = masterList
                categories.value = listOf("All", "Favorites") + masterList.map { it.category }.distinct()
                isLoading = false
                isRefreshing = false // Stop refreshing animation
            } catch (e: Exception) {
                isLoading = false
                isRefreshing = false
                Log.e("API", "Failed to load data", e)
                showNoInternetDialog = true // Show dialog on error
            }
        }
    }

    // Initial Load
    LaunchedEffect(Unit) {
        fetchData()
    }

    // Handle Pull to Refresh
    if (pullRefreshState.isRefreshing) {
        LaunchedEffect(true) {
            isRefreshing = true
            fetchData()
            pullRefreshState.endRefresh()
        }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            AppDrawer(
                navController = navController,
                onCloseDrawer = { scope.launch { drawerState.close() } }
            )
        }
    ) {
        FuturisticBackground()

        Scaffold(
            topBar = {
                if (!isSearchActive) {
                    CenterAlignedTopAppBar(
                        title = { 
                            Text(
                                "STREAMX ULTRA", 
                                style = MaterialTheme.typography.titleLarge.copy(
                                    fontWeight = FontWeight.Black, 
                                    color = Color.White, 
                                    letterSpacing = 1.5.sp
                                )
                            ) 
                        },
                        navigationIcon = {
                            IconButton(onClick = { scope.launch { drawerState.open() } }) {
                                Icon(Icons.Default.Menu, contentDescription = "Menu", tint = Color.White)
                            }
                        },
                        actions = {
                            IconButton(onClick = { isSearchActive = true }) {
                                Icon(Icons.Default.Search, null, tint = Color.White)
                            }
                        },
                        colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                            containerColor = Color.Black.copy(alpha = 0.2f) // Glassmorphic look
                        ),
                        modifier = Modifier.blur(0.dp) // Optional additional blur if supported
                    )
                }
            },
            containerColor = Color.Transparent
        ) { padding ->

            val filteredChannels = remember(searchQuery, selectedCategory, allChannels.value, favoriteIds) {
                allChannels.value.filter { channel ->
                    val matchesSearch = channel.name.contains(searchQuery, ignoreCase = true)
                    val matchesCategory = when (selectedCategory) {
                        "All" -> true
                        "Favorites" -> channel.id in favoriteIds
                        else -> channel.category == selectedCategory
                    }
                    matchesSearch && matchesCategory
                }
            }

            if (isSearchActive) {
                SearchBar(
                    query = searchQuery,
                    onQueryChange = { searchQuery = it },
                    onSearch = { isSearchActive = false },
                    active = true,
                    onActiveChange = { isSearchActive = it },
                    placeholder = { Text("Search Channels...") },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    trailingIcon = {
                        if (searchQuery.isNotEmpty()) {
                            IconButton(onClick = { searchQuery = "" }) { Icon(Icons.Default.Close, "Clear") }
                        } else {
                             IconButton(onClick = { isSearchActive = false }) { Icon(Icons.Default.Close, "Close") }
                        }
                    },
                    modifier = Modifier.fillMaxSize()
                ) {
                    LazyColumn {
                        items(filteredChannels) { channel ->
                            ListItem(
                                headlineContent = { Text(channel.name, fontWeight = FontWeight.Bold) },
                                leadingContent = {
                                    AsyncImage(
                                        model = channel.logoUrl,
                                        contentDescription = null,
                                        modifier = Modifier.size(40.dp).clip(RoundedCornerShape(8.dp)),
                                        contentScale = ContentScale.Fit
                                    )
                                },
                                modifier = Modifier.clickable {
                                    if (channel.streamUrls.isNotEmpty()) {
                                        selectedChannelForLinks = channel
                                        showLinkSelectorDialog = true
                                        isSearchActive = false
                                    }
                                }
                            )
                        }
                    }
                }
            } else {
                Box(modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .nestedScroll(pullRefreshState.nestedScrollConnection)
                ) {
                    if (isLoading) {
                        LoadingShimmerEffect()
                    } else {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            state = rememberLazyListState()
                        ) {
                            // 1. Featured Banner
                            val featured = allChannels.value.filter { it.isFeatured }
                            if (featured.isNotEmpty()) {
                                item { 
                                    FeaturedCarousel(
                                        featured = featured, 
                                        navController = navController,
                                        onChannelClick = { channel ->
                                            if (channel.streamUrls.isNotEmpty()) {
                                                selectedChannelForLinks = channel
                                                showLinkSelectorDialog = true
                                            }
                                        }
                                    ) 
                                }
                            }

                            // 2. Categories
                            item { 
                                CategoryTabs(categories.value, selectedCategory) { selectedCategory = it } 
                            }
                            
                            // 3. Grid Content
                            if (filteredChannels.isEmpty()) {
                                item { EmptyState(isFavorites = selectedCategory == "Favorites") }
                            } else {
                                items(filteredChannels.chunked(3)) { rowItems ->
                                    Row(
                                        Modifier.padding(horizontal = 16.dp, vertical = 8.dp), 
                                        Arrangement.spacedBy(12.dp)
                                    ) {
                                        rowItems.forEach { channel ->
                                            ChannelCard(
                                                channel = channel,
                                                isFavorite = channel.id in favoriteIds,
                                                modifier = Modifier.weight(1f),
                                                onFavoriteToggle = {
                                                    scope.launch {
                                                        context.dataStore.edit { prefs ->
                                                            val current = prefs[FAVORITES_KEY] ?: emptySet()
                                                            prefs[FAVORITES_KEY] = if (channel.id in current) current - channel.id else current + channel.id
                                                        }
                                                    }
                                                },
                                                onClick = {
                                                    if (channel.streamUrls.isNotEmpty()) {
                                                        selectedChannelForLinks = channel
                                                        showLinkSelectorDialog = true
                                                    }
                                                }
                                            )
                                        }
                                        repeat(3 - rowItems.size) { Spacer(Modifier.weight(1f)) }
                                    }
                                }
                            }
                            item { Spacer(modifier = Modifier.height(100.dp)) }
                        }
                    }

                    // Pull to Refresh Indicator
                    PullToRefreshContainer(
                        state = pullRefreshState,
                        modifier = Modifier.align(Alignment.TopCenter),
                        containerColor = MaterialTheme.colorScheme.surface,
                        contentColor = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
    }
    
    // Link Selector Dialog
    if (showLinkSelectorDialog && selectedChannelForLinks != null) {
        LinkSelectorDialog(
            channel = selectedChannelForLinks!!,
            onDismiss = { showLinkSelectorDialog = false },
            onLinkSelected = { selectedUrl ->
                showLinkSelectorDialog = false
                try {
                    val encodedUrl = URLEncoder.encode(selectedUrl, "UTF-8")
                    navController.navigate("player/$encodedUrl")
                } catch (e: Exception) {
                    Toast.makeText(context, "Error playing link", Toast.LENGTH_SHORT).show()
                }
            }
        )
    }

    // Update Dialog
    if (showUpdateDialog && latestReleaseInfo != null) {
        UpdateDialog(
            release = latestReleaseInfo!!,
            onDismiss = { showUpdateDialog = false },
            onUpdateClick = {
                showUpdateDialog = false
                UpdateChecker.downloadAndInstall(context, latestReleaseInfo!!)
            }
        )
    }

    // No Internet Dialog
    if (showNoInternetDialog) {
        AlertDialog(
            onDismissRequest = { /* Prevent dismiss on outside click */ },
            icon = { Icon(Icons.Rounded.SignalWifiOff, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
            title = { Text("No Internet Connection") },
            text = { Text("Please check your internet connection and try again.") },
            confirmButton = {
                Button(
                    onClick = {
                        showNoInternetDialog = false
                        isLoading = true
                        fetchData()
                    }
                ) {
                    Text("Retry")
                }
            },
            dismissButton = {
                TextButton(onClick = { showNoInternetDialog = false }) {
                    Text("Close App")
                }
            }
        )
    }
}

// ----------------------------------------------------------------------------------
// UI COMPONENTS
// ----------------------------------------------------------------------------------

@Composable
fun AppDrawer(navController: NavController, onCloseDrawer: () -> Unit) {
    ModalDrawerSheet(
        drawerContainerColor = Color(0xFF121212).copy(alpha = 0.95f),
        drawerContentColor = Color.White
    ) {
        Box(modifier = Modifier.fillMaxWidth().height(200.dp).background(Brush.verticalGradient(listOf(MaterialTheme.colorScheme.primary, Color.Transparent))), contentAlignment = Alignment.BottomStart) {
            Text("STREAMX ULTRA", modifier = Modifier.padding(24.dp), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = Color.White)
        }
        HorizontalDivider(color = Color.White.copy(0.1f))
        
        Spacer(Modifier.height(12.dp))
        NavigationDrawerItem(label = { Text("Home") }, selected = true, onClick = onCloseDrawer, icon = { Icon(Icons.Default.Home, null) }, colors = NavigationDrawerItemDefaults.colors(selectedContainerColor = MaterialTheme.colorScheme.primary.copy(0.2f), unselectedContainerColor = Color.Transparent, selectedTextColor = MaterialTheme.colorScheme.primary, unselectedTextColor = Color.White), modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding))
        NavigationDrawerItem(label = { Text("My Account") }, selected = false, onClick = { onCloseDrawer(); navController.navigate("account") }, icon = { Icon(Icons.Default.Person, null) }, colors = NavigationDrawerItemDefaults.colors(unselectedContainerColor = Color.Transparent, unselectedTextColor = Color.White), modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding))
        NavigationDrawerItem(label = { Text("Settings") }, selected = false, onClick = { onCloseDrawer(); navController.navigate("settings") }, icon = { Icon(Icons.Default.Settings, null) }, colors = NavigationDrawerItemDefaults.colors(unselectedContainerColor = Color.Transparent, unselectedTextColor = Color.White), modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding))
    }
}

@Composable
fun FuturisticBackground() {
    val infiniteTransition = rememberInfiniteTransition(label = "")
    val color1 by infiniteTransition.animateColor(
        initialValue = Color(0xFF0F2027), targetValue = Color(0xFF203A43),
        animationSpec = infiniteRepeatable(tween(10000), RepeatMode.Reverse), label = ""
    )
    val color2 by infiniteTransition.animateColor(
        initialValue = Color(0xFF2C5364), targetValue = Color(0xFF0F2027),
        animationSpec = infiniteRepeatable(tween(10000), RepeatMode.Reverse), label = ""
    )
    
    Box(modifier = Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(color1, color2))))
}

@Composable
fun ChannelCard(
    channel: Channel, 
    isFavorite: Boolean, 
    modifier: Modifier, 
    onFavoriteToggle: () -> Unit, 
    onClick: () -> Unit
) {
    // High Level Card Design
    Card(
        modifier = modifier
            .aspectRatio(0.85f)
            .shadow(elevation = 8.dp, shape = RoundedCornerShape(20.dp), spotColor = MaterialTheme.colorScheme.primary)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E))
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            // Background Logo with low opacity
            AsyncImage(
                model = channel.logoUrl,
                contentDescription = null,
                modifier = Modifier.fillMaxSize().padding(24.dp).alpha(0.8f),
                contentScale = ContentScale.Fit
            )
            
            // Favorite Button
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(8.dp)
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(0.4f))
                    .clickable(onClick = onFavoriteToggle),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = if (isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                    contentDescription = null,
                    tint = if (isFavorite) Color(0xFFFF4081) else Color.White,
                    modifier = Modifier.size(18.dp)
                )
            }

            // Name Overlay (Glassmorphism style at bottom)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomCenter)
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(0.9f))))
                    .padding(10.dp)
            ) {
                Text(
                    text = channel.name,
                    color = Color.White,
                    fontSize = 12.sp,
                    maxLines = 1,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.align(Alignment.Center)
                )
            }
        }
    }
}

@Composable
fun CategoryTabs(categories: List<String>, selected: String, onSelect: (String) -> Unit) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        items(categories) { cat ->
            val isSelected = cat == selected
            val animatedColor by animateColorAsState(if (isSelected) MaterialTheme.colorScheme.primary else Color(0xFF2D2D2D))
            
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(animatedColor)
                    .clickable { onSelect(cat) }
                    .padding(horizontal = 24.dp, vertical = 10.dp)
            ) {
                Text(
                    cat, 
                    color = if (isSelected) Color.White else Color.Gray, 
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium, 
                    fontSize = 14.sp
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FeaturedCarousel(featured: List<Channel>, navController: NavController, onChannelClick: (Channel) -> Unit) {
    val pagerState = rememberPagerState(pageCount = { featured.size })

    // Auto Sliding Logic
    LaunchedEffect(pagerState.currentPage) {
        if (featured.isNotEmpty()) {
            delay(4000) 
            try {
                val nextPage = (pagerState.currentPage + 1) % featured.size
                pagerState.animateScrollToPage(nextPage, animationSpec = tween(800))
            } catch (e: Exception) { /* Handle cancellation */ }
        }
    }

    Column {
        Text(
            "Featured Channels", 
            style = MaterialTheme.typography.titleMedium.copy(color = Color.White, fontWeight = FontWeight.Bold),
            modifier = Modifier.padding(start = 24.dp, top = 8.dp)
        )
        
        HorizontalPager(
            state = pagerState,
            contentPadding = PaddingValues(horizontal = 32.dp),
            pageSpacing = 16.dp,
            modifier = Modifier.padding(vertical = 16.dp)
        ) { page ->
            val pageOffset = (pagerState.currentPage - page) + pagerState.currentPageOffsetFraction
            val scale = lerp(0.9f, 1f, 1f - pageOffset.absoluteValue.coerceIn(0f, 1f))
            
            Card(
                modifier = Modifier
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        alpha = lerp(0.6f, 1f, 1f - pageOffset.absoluteValue.coerceIn(0f, 1f))
                    }
                    .fillMaxWidth()
                    .height(180.dp)
                    .clickable { onChannelClick(featured[page]) },
                shape = RoundedCornerShape(24.dp),
                elevation = CardDefaults.cardElevation(defaultElevation = 10.dp)
            ) {
                Box {
                    AsyncImage(
                        model = featured[page].logoUrl,
                        contentDescription = null,
                        contentScale = ContentScale.Crop, // Changed to Crop for better banner look
                        modifier = Modifier.fillMaxSize().background(Color.DarkGray)
                    )
                    
                    // Gradient Overlay
                    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black))))
                    
                    // Play Icon
                    Icon(
                        Icons.Default.PlayCircleOutline, 
                        contentDescription = null, 
                        tint = Color.White.copy(0.8f),
                        modifier = Modifier.align(Alignment.Center).size(48.dp)
                    )

                    Column(modifier = Modifier.align(Alignment.BottomStart).padding(20.dp)) {
                        Text(
                            featured[page].name, 
                            color = Color.White, 
                            fontWeight = FontWeight.ExtraBold, 
                            fontSize = 22.sp
                        )
                        Text(
                            featured[page].category, 
                            color = MaterialTheme.colorScheme.primary, 
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun LoadingShimmerEffect(modifier: Modifier = Modifier) {
    LazyColumn(modifier = modifier, contentPadding = PaddingValues(16.dp)) {
        item { Box(modifier = Modifier.fillMaxWidth().height(180.dp).padding(horizontal = 8.dp).clip(RoundedCornerShape(24.dp)).shimmer().background(Color.Gray.copy(0.3f))) }
        item { Row(modifier = Modifier.padding(vertical = 16.dp)) { repeat(4) { Box(modifier = Modifier.padding(end = 8.dp).clip(RoundedCornerShape(50)).width(80.dp).height(36.dp).shimmer().background(Color.Gray.copy(0.3f))) } } }
        items(5) { 
            Row(modifier = Modifier.padding(bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) { 
                repeat(3) { 
                    Box(modifier = Modifier.weight(1f).aspectRatio(0.85f).clip(RoundedCornerShape(20.dp)).shimmer().background(Color.Gray.copy(0.3f))) 
                } 
            } 
        }
    }
}

@Composable
fun EmptyState(isFavorites: Boolean) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(top = 80.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            if (isFavorites) Icons.Default.FavoriteBorder else Icons.Rounded.Warning,
            contentDescription = null,
            tint = Color.Gray,
            modifier = Modifier.size(64.dp).padding(bottom = 16.dp)
        )
        Text(
            if (isFavorites) "No Favorites Yet" else "No Channels Found",
            color = Color.White,
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold
        )
        Text(
            if (isFavorites) "Mark channels as favorite to see them here" else "Try searching for something else",
            color = Color.Gray,
            fontSize = 14.sp
        )
    }
}

@Composable
fun LinkSelectorDialog(channel: Channel, onDismiss: () -> Unit, onLinkSelected: (String) -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        Card(
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E))
        ) {
            Column(modifier = Modifier.padding(24.dp)) {
                Text("Select Stream", fontWeight = FontWeight.Bold, fontSize = 20.sp, color = Color.White)
                Spacer(modifier = Modifier.height(16.dp))
                LazyColumn {
                    itemsIndexed(channel.streamUrls) { index, url ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(Color(0xFF2D2D2D))
                                .clickable { onLinkSelected(url) }
                                .padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.PlayArrow, null, tint = MaterialTheme.colorScheme.primary)
                            Spacer(modifier = Modifier.width(16.dp)) // FIXED: Added modifier wrapper
                            Text("Server ${index + 1}", color = Color.White, fontWeight = FontWeight.Medium)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun UpdateDialog(release: GitHubRelease, onDismiss: () -> Unit, onUpdateClick: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Update Available 🚀") },
        text = {
            Column {
                Text("Version: ${release.tag_name}", fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(8.dp))
                Text(release.body.lines().filterNot { it.startsWith("versionCode:") }.joinToString("\n"))
            }
        },
        confirmButton = { Button(onClick = onUpdateClick) { Text("Update Now") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Later") } }
    )
}
