package com.aeoncorex.streamx.ui.home

import android.app.Activity
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import android.widget.Toast
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.* // FIXED: Import for animations (tween, infiniteRepeatable, etc.)
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
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
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.*
import androidx.compose.runtime.getValue // FIXED: Required for 'by' delegates
import androidx.compose.runtime.setValue // FIXED: Required for 'by' delegates
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.lerp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.zIndex // FIXED: Import for zIndex
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

// DataStore Setup
private val Context.dataStore by preferencesDataStore(name = "favorites_prefs")
private val FAVORITES_KEY = stringSetPreferencesKey("favorite_ids")

val GlassWhite = Color(0x1AFFFFFF)

interface IPTVApi {
    @GET("index.json")
    suspend fun getIndex(): Map<String, Any>
    @GET
    suspend fun getChannelsByUrl(@Url url: String): Map<String, Any>
}

fun isInternetAvailable(context: Context): Boolean {
    val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    val network = connectivityManager.activeNetwork ?: return false
    val activeNetwork = connectivityManager.getNetworkCapabilities(network) ?: return false
    return activeNetwork.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun LiveTVScreen(navController: NavController) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // THEME COLORS (Dynamic)
    val primaryColor = MaterialTheme.colorScheme.primary
    val secondaryColor = MaterialTheme.colorScheme.secondary
    val backgroundColor = MaterialTheme.colorScheme.background
    val surfaceColor = MaterialTheme.colorScheme.surface

    // State
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
    var showNoInternetDialog by remember { mutableStateOf(false) }
    var isRefreshing by remember { mutableStateOf(false) }

    val pullRefreshState = rememberPullToRefreshState()

    val favoriteIds by context.dataStore.data
        .map { preferences -> preferences[FAVORITES_KEY] ?: emptySet() }
        .collectAsState(initial = emptySet())

    val categoryCounts = remember(allChannels.value, favoriteIds) {
        val counts = allChannels.value.groupingBy { it.category }.eachCount().toMutableMap()
        counts["All"] = allChannels.value.size
        counts["Favorites"] = favoriteIds.size
        counts
    }

    val fetchData: (isRetry: Boolean) -> Unit = { isRetry ->
        scope.launch {
            if (!isInternetAvailable(context)) {
                isLoading = false
                isRefreshing = false
                if(isRetry) Toast.makeText(context, "Connection Failed", Toast.LENGTH_SHORT).show()
                showNoInternetDialog = true
                return@launch
            }

            try {
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
                isRefreshing = false
            } catch (e: Exception) {
                isLoading = false
                isRefreshing = false
                if(isRetry) Toast.makeText(context, "Error Fetching Data", Toast.LENGTH_SHORT).show()
                showNoInternetDialog = true
            }
        }
    }

    LaunchedEffect(Unit) { fetchData(false) }

    if (pullRefreshState.isRefreshing) {
        LaunchedEffect(true) {
            isRefreshing = true
            fetchData(false)
            delay(1000)
            pullRefreshState.endRefresh()
        }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            AppDrawer(navController, onCloseDrawer = { scope.launch { drawerState.close() } })
        }
    ) {
        // ULTRA BACKGROUND
        CyberMeshBackground()

        // Using Box to layer content
        Box(
            modifier = Modifier
                .fillMaxSize()
                .nestedScroll(pullRefreshState.nestedScrollConnection)
        ) {
            
            // 1. Content Area
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
                // Full Screen Search Overlay
                Box(modifier = Modifier.fillMaxSize().background(backgroundColor.copy(0.95f)).zIndex(2f)) {
                     SearchBar(
                        query = searchQuery,
                        onQueryChange = { searchQuery = it },
                        onSearch = { isSearchActive = false },
                        active = true,
                        onActiveChange = { isSearchActive = it },
                        placeholder = { Text("Search Global Database...", color = Color.Gray) },
                        leadingIcon = { Icon(Icons.Default.Search, null, tint = primaryColor) },
                        trailingIcon = {
                            if (searchQuery.isNotEmpty()) {
                                IconButton(onClick = { searchQuery = "" }) { Icon(Icons.Default.Close, "Clear", tint = Color.White) }
                            } else {
                                IconButton(onClick = { isSearchActive = false }) { Icon(Icons.Default.Close, "Close", tint = Color.White) }
                            }
                        },
                        colors = SearchBarDefaults.colors(
                            containerColor = Color.Black,
                            dividerColor = secondaryColor,
                            inputFieldColors = TextFieldDefaults.colors(focusedTextColor = Color.White)
                        ),
                        modifier = Modifier.fillMaxSize()
                    ) {
                        LazyColumn(contentPadding = PaddingValues(16.dp)) {
                            items(filteredChannels) { channel ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            if (channel.streamUrls.isNotEmpty()) {
                                                selectedChannelForLinks = channel
                                                showLinkSelectorDialog = true
                                                isSearchActive = false
                                            }
                                        }
                                        .padding(vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    AsyncImage(
                                        model = channel.logoUrl,
                                        contentDescription = null,
                                        modifier = Modifier.size(50.dp).clip(RoundedCornerShape(12.dp)).border(1.dp, primaryColor.copy(0.5f), RoundedCornerShape(12.dp)),
                                        contentScale = ContentScale.Fit
                                    )
                                    Spacer(Modifier.width(16.dp))
                                    Text(channel.name, color = Color.White, fontWeight = FontWeight.Bold)
                                }
                                HorizontalDivider(color = Color.White.copy(0.1f))
                            }
                        }
                    }
                }
            } else {
                // Main List Content
                if (isLoading) {
                     // Add some top padding to avoid overlap with TopBar since Scaffold is gone
                    Box(modifier = Modifier.padding(top = 80.dp)) {
                         LoadingShimmerEffect()
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        state = rememberLazyListState(),
                        contentPadding = PaddingValues(top = 80.dp, bottom = 100.dp) // Added top padding for custom TopBar
                    ) {
                        // 1. Featured Section
                        val featured = allChannels.value.filter { it.isFeatured }
                        if (featured.isNotEmpty()) {
                            item {
                                Spacer(Modifier.height(8.dp))
                                HeroCarousel(
                                    featured = featured,
                                    navController = navController,
                                    onChannelClick = { channel ->
                                        if (channel.streamUrls.isNotEmpty()) {
                                            selectedChannelForLinks = channel
                                            showLinkSelectorDialog = true
                                        }
                                    }
                                )
                                Spacer(Modifier.height(16.dp))
                            }
                        }

                        // 2. Sticky Header
                        stickyHeader {
                            Surface(
                                color = Color.Black.copy(0.8f),
                                modifier = Modifier.fillMaxWidth().animateContentSize()
                            ) {
                                ModernCategorySelector(
                                    categories = categories.value,
                                    selected = selectedCategory,
                                    counts = categoryCounts,
                                    onSelect = { selectedCategory = it }
                                )
                            }
                        }

                        // 3. Channel Grid
                        if (filteredChannels.isEmpty()) {
                            item { EmptyState(isFavorites = selectedCategory == "Favorites") }
                        } else {
                            items(filteredChannels.chunked(3)) { rowItems ->
                                Row(
                                    Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                                    Arrangement.spacedBy(12.dp)
                                ) {
                                    rowItems.forEach { channel ->
                                        HolographicChannelCard(
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
                    }
                }
            }

            // 2. Custom Top Bar (Floating Overlay)
            // Removed Scaffold topBar, placed manually here
            if (!isSearchActive) {
                Box(modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .clip(RoundedCornerShape(24.dp))
                    .background(GlassWhite)
                    .border(1.dp, Brush.horizontalGradient(listOf(Color.White.copy(0.1f), Color.White.copy(0.05f))), RoundedCornerShape(24.dp))
                    .zIndex(1f) // Ensure it's on top of list
                ) {
                    CenterAlignedTopAppBar(
                        title = {
                            Text(
                                "STREAMX",
                                style = TextStyle(
                                    fontFamily = MaterialTheme.typography.displayMedium.fontFamily,
                                    fontWeight = FontWeight.Black,
                                    fontSize = 22.sp,
                                    letterSpacing = 2.sp,
                                    brush = Brush.horizontalGradient(listOf(primaryColor, secondaryColor))
                                )
                            )
                        },
                        navigationIcon = {
                            IconButton(onClick = { scope.launch { drawerState.open() } }) {
                                Icon(Icons.Default.Menu, "Menu", tint = Color.White)
                            }
                        },
                        actions = {
                            IconButton(onClick = { isSearchActive = true }) {
                                Icon(Icons.Default.Search, "Search", tint = Color.White)
                            }
                        },
                        colors = TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor = Color.Transparent)
                    )
                }
            }
        }
    }

    // Dialogs
    if (showLinkSelectorDialog && selectedChannelForLinks != null) {
        LinkSelectorDialog(selectedChannelForLinks!!, { showLinkSelectorDialog = false }) { selectedUrl ->
            showLinkSelectorDialog = false
            try {
                val encodedUrl = URLEncoder.encode(selectedUrl, "UTF-8")
                navController.navigate("player/$encodedUrl")
            } catch (e: Exception) {
                Toast.makeText(context, "Error", Toast.LENGTH_SHORT).show()
            }
        }
    }

    if (showUpdateDialog && latestReleaseInfo != null) {
        UpdateDialog(latestReleaseInfo!!, { showUpdateDialog = false }) {
            showUpdateDialog = false
            UpdateChecker.downloadAndInstall(context, latestReleaseInfo!!)
        }
    }

    if (showNoInternetDialog) {
        AlertDialog(
            onDismissRequest = { },
            icon = { Icon(Icons.Rounded.SignalWifiOff, null, tint = primaryColor) },
            title = { Text("OFFLINE MODE", color = Color.White) },
            text = { Text("Neural Uplink Disconnected. Check connection.", color = Color.Gray) },
            confirmButton = { Button(onClick = { showNoInternetDialog = false; isLoading = true; fetchData(true) }, colors = ButtonDefaults.buttonColors(containerColor = primaryColor)) { Text("RECONNECT", color = Color.Black) } },
            dismissButton = { TextButton(onClick = { (context as? Activity)?.finish() }) { Text("EXIT SYSTEM", color = Color.Gray) } },
            containerColor = surfaceColor,
            shape = RoundedCornerShape(16.dp)
        )
    }
}

// ----------------------------------------------------------------------------------
// COMPONENTS
// ----------------------------------------------------------------------------------

@Composable
fun CyberMeshBackground() {
    val infiniteTransition = rememberInfiniteTransition(label = "bg")
    val offset1 by infiniteTransition.animateFloat(
        initialValue = 0f, targetValue = 1000f,
        animationSpec = infiniteRepeatable(tween(20000, easing = LinearEasing), RepeatMode.Reverse), label = ""
    )

    val bgColor = MaterialTheme.colorScheme.background
    val primary = MaterialTheme.colorScheme.primary
    val secondary = MaterialTheme.colorScheme.secondary

    Canvas(modifier = Modifier.fillMaxSize().background(bgColor)) {
        drawRect(
            brush = Brush.radialGradient(
                colors = listOf(Color(0xFF0F0F15), bgColor),
                center = center,
                radius = size.maxDimension
            )
        )
        drawCircle(
            brush = Brush.radialGradient(listOf(secondary.copy(0.15f), Color.Transparent)),
            radius = size.minDimension * 0.6f,
            center = Offset(size.width * 0.8f, size.height * 0.2f + offset1)
        )
        drawCircle(
            brush = Brush.radialGradient(listOf(primary.copy(0.1f), Color.Transparent)),
            radius = size.minDimension * 0.7f,
            center = Offset(size.width * 0.2f, size.height * 0.8f - offset1)
        )
    }
}

@Composable
fun HolographicChannelCard(
    channel: Channel,
    isFavorite: Boolean,
    modifier: Modifier,
    onFavoriteToggle: () -> Unit,
    onClick: () -> Unit
) {
    val primaryColor = MaterialTheme.colorScheme.primary
    val interactionSource = remember { MutableInteractionSource() }

    Card(
        modifier = modifier
            .aspectRatio(0.85f)
            .clickable(interactionSource = interactionSource, indication = null, onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color.Transparent),
        elevation = CardDefaults.cardElevation(0.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(GlassWhite)
                .border(1.dp, Brush.verticalGradient(listOf(Color.White.copy(0.2f), Color.Transparent)), RoundedCornerShape(16.dp))
                .clip(RoundedCornerShape(16.dp))
        ) {
            Box(Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.Center) {
                AsyncImage(
                    model = channel.logoUrl,
                    contentDescription = null,
                    modifier = Modifier.fillMaxWidth().aspectRatio(1f),
                    contentScale = ContentScale.Fit
                )
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomCenter)
                    .height(50.dp)
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(0.9f))))
            )
            Text(
                text = channel.name.uppercase(),
                color = Color.White,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 8.dp, start = 4.dp, end = 4.dp)
            )
            IconButton(
                onClick = onFavoriteToggle,
                modifier = Modifier.align(Alignment.TopEnd).size(30.dp)
            ) {
                Icon(
                    imageVector = if (isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                    contentDescription = null,
                    tint = if (isFavorite) primaryColor else Color.White.copy(0.5f),
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}

@Composable
fun ModernCategorySelector(
    categories: List<String>,
    selected: String,
    counts: Map<String, Int>,
    onSelect: (String) -> Unit
) {
    val primaryColor = MaterialTheme.colorScheme.primary
    Column(modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(categories) { cat ->
                val isSelected = cat == selected
                val count = counts[cat] ?: 0
                val icon = getCategoryIcon(cat)
                val backgroundColor = if (isSelected) primaryColor else Color(0xFF2A2A2A)
                val contentColor = if (isSelected) Color.Black else Color.White
                val borderColor = if (isSelected) primaryColor else Color.Gray.copy(0.3f)

                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = backgroundColor,
                    border = BorderStroke(1.dp, borderColor),
                    modifier = Modifier
                        .height(40.dp)
                        .clickable { onSelect(cat) }
                        .animateContentSize()
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(imageVector = icon, contentDescription = null, tint = contentColor, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(text = cat, color = contentColor, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                        if (count > 0) {
                            Spacer(Modifier.width(6.dp))
                            Text(text = "($count)", color = contentColor.copy(0.7f), fontSize = 11.sp, fontWeight = FontWeight.Normal)
                        }
                    }
                }
            }
        }
        HorizontalDivider(color = Color.White.copy(0.1f))
    }
}

fun getCategoryIcon(category: String): ImageVector {
    return when {
        category.contains("All", true) -> Icons.Rounded.Apps
        category.contains("Favorite", true) -> Icons.Rounded.Favorite
        category.contains("Sport", true) || category.contains("Cricket", true) || category.contains("Football", true) -> Icons.Rounded.SportsSoccer
        category.contains("News", true) -> Icons.Rounded.Newspaper
        category.contains("Movie", true) || category.contains("Cinema", true) -> Icons.Rounded.Movie
        category.contains("Music", true) -> Icons.Rounded.MusicNote
        category.contains("Kid", true) || category.contains("Cartoon", true) -> Icons.Rounded.ChildCare
        category.contains("Live", true) -> Icons.Rounded.LiveTv
        else -> Icons.Rounded.Tv
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun HeroCarousel(featured: List<Channel>, navController: NavController, onChannelClick: (Channel) -> Unit) {
    val pagerState = rememberPagerState(pageCount = { featured.size })
    val primaryColor = MaterialTheme.colorScheme.primary

    LaunchedEffect(pagerState.currentPage) {
        if (featured.isNotEmpty()) {
            delay(4000)
            try {
                val nextPage = (pagerState.currentPage + 1) % featured.size
                pagerState.animateScrollToPage(nextPage, animationSpec = tween(800))
            } catch (e: Exception) { /* safe */ }
        }
    }

    Column {
        Text(
            "FEATURED LIVE",
            style = TextStyle(color = primaryColor, fontWeight = FontWeight.Bold, letterSpacing = 1.sp, fontSize = 12.sp),
            modifier = Modifier.padding(start = 16.dp, bottom = 8.dp)
        )
        HorizontalPager(
            state = pagerState,
            contentPadding = PaddingValues(horizontal = 24.dp),
            pageSpacing = 16.dp
        ) { page ->
            val pageOffset = (pagerState.currentPage - page) + pagerState.currentPageOffsetFraction
            val scale = lerp(0.92f, 1f, 1f - pageOffset.absoluteValue.coerceIn(0f, 1f))

            Card(
                modifier = Modifier
                    .graphicsLayer { scaleX = scale; scaleY = scale }
                    .height(220.dp)
                    .fillMaxWidth()
                    .clickable { onChannelClick(featured[page]) },
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = Color.Black),
                elevation = CardDefaults.cardElevation(8.dp)
            ) {
                Box(Modifier.fillMaxSize()) {
                    AsyncImage(
                        model = featured[page].logoUrl,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize().alpha(0.8f)
                    )
                    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(0.6f), Color.Black.copy(0.9f)))))
                    Column(modifier = Modifier.align(Alignment.BottomStart).padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(8.dp).background(Color.Red, CircleShape))
                            Spacer(Modifier.width(6.dp))
                            Text("LIVE NOW", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        }
                        Spacer(Modifier.height(4.dp))
                        Text(featured[page].name, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Black, maxLines = 1)
                        Spacer(Modifier.height(8.dp))
                        Box(modifier = Modifier.background(primaryColor, RoundedCornerShape(4.dp)).padding(horizontal = 12.dp, vertical = 6.dp)) {
                            Text("WATCH STREAM", color = Color.Black, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                    Box(
                        modifier = Modifier.align(Alignment.Center).size(48.dp).background(Color.Black.copy(0.4f), CircleShape).border(1.dp, Color.White, CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Default.PlayArrow, null, tint = Color.White)
                    }
                }
            }
        }
    }
}

@Composable
fun AppDrawer(navController: NavController, onCloseDrawer: () -> Unit) {
    val primaryColor = MaterialTheme.colorScheme.primary
    val bgColor = MaterialTheme.colorScheme.background

    ModalDrawerSheet(
        drawerContainerColor = Color(0xFF101010),
        drawerContentColor = Color.White
    ) {
        Box(modifier = Modifier.fillMaxWidth().height(200.dp).background(Brush.linearGradient(listOf(bgColor, Color(0xFF1A1A1A)))), contentAlignment = Alignment.CenterStart) {
            Column(Modifier.padding(24.dp)) {
                Text("STREAMX", color = primaryColor, fontSize = 30.sp, fontWeight = FontWeight.Black, letterSpacing = 2.sp)
                Text("ULTRA EDITION", color = Color.Gray, fontSize = 12.sp, letterSpacing = 4.sp)
            }
        }
        HorizontalDivider(color = Color.White.copy(0.1f))
        Spacer(Modifier.height(12.dp))
        val itemModifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
        NavigationDrawerItem(
            label = { Text("DASHBOARD") }, selected = true, onClick = onCloseDrawer,
            icon = { Icon(Icons.Default.Home, null, tint = primaryColor) },
            colors = NavigationDrawerItemDefaults.colors(selectedContainerColor = primaryColor.copy(0.1f), selectedTextColor = primaryColor, unselectedTextColor = Color.Gray),
            modifier = itemModifier
        )
        NavigationDrawerItem(
            label = { Text("PROFILE") }, selected = false, onClick = { onCloseDrawer(); navController.navigate("account") },
            icon = { Icon(Icons.Default.Person, null, tint = Color.Gray) },
            colors = NavigationDrawerItemDefaults.colors(unselectedTextColor = Color.Gray),
            modifier = itemModifier
        )
        NavigationDrawerItem(
            label = { Text("SYSTEM") }, selected = false, onClick = { onCloseDrawer(); navController.navigate("settings") },
            icon = { Icon(Icons.Default.Settings, null, tint = Color.Gray) },
            colors = NavigationDrawerItemDefaults.colors(unselectedTextColor = Color.Gray),
            modifier = itemModifier
        )
    }
}

@Composable
fun LoadingShimmerEffect() {
    Column(Modifier.padding(16.dp)) {
        Box(Modifier.fillMaxWidth().height(200.dp).clip(RoundedCornerShape(24.dp)).shimmer().background(Color.DarkGray))
        Spacer(Modifier.height(24.dp))
        Row { repeat(3) { Box(Modifier.width(80.dp).height(30.dp).clip(RoundedCornerShape(8.dp)).shimmer().background(Color.DarkGray)); Spacer(Modifier.width(10.dp)) } }
        Spacer(Modifier.height(24.dp))
        Row { repeat(3) { Box(Modifier.weight(1f).height(120.dp).clip(RoundedCornerShape(16.dp)).shimmer().background(Color.DarkGray)); Spacer(Modifier.width(10.dp)) } }
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
            tint = Color.DarkGray,
            modifier = Modifier.size(80.dp).padding(bottom = 16.dp)
        )
        Text(
            if (isFavorites) "NO FAVORITES LOGGED" else "NO SIGNAL FOUND",
            color = Color.Gray,
            fontSize = 16.sp,
            letterSpacing = 2.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
fun LinkSelectorDialog(channel: Channel, onDismiss: () -> Unit, onLinkSelected: (String) -> Unit) {
    val primaryColor = MaterialTheme.colorScheme.primary
    val surfaceColor = MaterialTheme.colorScheme.surface

    Dialog(onDismissRequest = onDismiss) {
        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = surfaceColor),
            modifier = Modifier.border(1.dp, Color.White.copy(0.1f), RoundedCornerShape(16.dp))
        ) {
            Column(modifier = Modifier.padding(24.dp)) {
                Text("SELECT STREAM", fontWeight = FontWeight.Bold, fontSize = 16.sp, color = primaryColor, letterSpacing = 1.sp)
                Spacer(modifier = Modifier.height(16.dp))
                LazyColumn {
                    itemsIndexed(channel.streamUrls) { index, url ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 6.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(Color(0xFF202020))
                                .clickable { onLinkSelected(url) }
                                .padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.PlayArrow, null, tint = primaryColor)
                            Spacer(modifier = Modifier.width(16.dp))
                            Text("SERVER 0${index + 1}", color = Color.White, fontWeight = FontWeight.Medium, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun UpdateDialog(release: GitHubRelease, onDismiss: () -> Unit, onUpdateClick: () -> Unit) {
    val primaryColor = MaterialTheme.colorScheme.primary
    val surfaceColor = MaterialTheme.colorScheme.surface

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("SYSTEM UPDATE DETECTED", color = primaryColor, fontSize = 16.sp) },
        text = {
            Column {
                Text("PATCH: ${release.tag_name}", fontWeight = FontWeight.Bold, color = Color.White)
                Spacer(modifier = Modifier.height(8.dp))
                Text(release.body.lines().filterNot { it.startsWith("versionCode:") }.joinToString("\n"), color = Color.Gray)
            }
        },
        confirmButton = { Button(onClick = onUpdateClick, colors = ButtonDefaults.buttonColors(containerColor = primaryColor)) { Text("INSTALL PATCH", color = Color.Black) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("IGNORE", color = Color.Gray) } },
        containerColor = surfaceColor
    )
}
