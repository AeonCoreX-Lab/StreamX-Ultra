package com.aeoncorex.streamx.ui.home

import android.content.Context
import android.util.Log
import androidx.compose.animation.*
import androidx.compose.animation.core.*
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
import androidx.compose.material.icons.filled.*
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
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(navController: NavController) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // States
    val allChannels = remember { mutableStateOf<List<Channel>>(emptyList()) }
    val categories = remember { mutableStateOf(listOf("All", "Favorites")) }
    var selectedCategory by remember { mutableStateOf("All") }
    var isLoading by remember { mutableStateOf(true) }
    var searchQuery by remember { mutableStateOf("") }
    var isSearchActive by remember { mutableStateOf(false) }

    // States for Link Selector Dialog
    var showLinkSelectorDialog by remember { mutableStateOf(false) }
    var selectedChannelForLinks by remember { mutableStateOf<Channel?>(null) }
    
    // State for the Navigation Drawer
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)

    // States for Update Dialog
    var showUpdateDialog by remember { mutableStateOf(false) }
    var latestReleaseInfo by remember { mutableStateOf<GitHubRelease?>(null) }

    val favoriteIds by context.dataStore.data
        .map { preferences -> preferences[FAVORITES_KEY] ?: emptySet() }
        .collectAsState(initial = emptySet())

    LaunchedEffect(Unit) {
        // First, check for updates
        val release = UpdateChecker.checkForUpdate(context)
        if (release != null) {
            latestReleaseInfo = release
            showUpdateDialog = true
        }
        
        // Then, load channel data
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
        } catch (e: Exception) {
            isLoading = false
            Log.e("API", "Failed to load initial data", e)
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
                Column {
                    CenterAlignedTopAppBar(
                        title = { if (!isSearchActive) Text("STREAMX ULTRA", style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Black, color = Color.White, letterSpacing = 2.sp)) },
                        navigationIcon = {
                            IconButton(onClick = { scope.launch { drawerState.open() } }) {
                                Icon(Icons.Default.Menu, contentDescription = "Menu", tint = Color.White)
                            }
                        },
                        actions = {
                            IconButton(onClick = { isSearchActive = !isSearchActive; if (!isSearchActive) searchQuery = "" }) {
                                Icon(if (isSearchActive) Icons.Default.Close else Icons.Default.Search, null, tint = Color.Cyan)
                            }
                        },
                        colors = TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor = Color.Transparent)
                    )
                    AnimatedVisibility(visible = isSearchActive) {
                        SearchBar(query = searchQuery, onQueryChange = { searchQuery = it }, onClear = { searchQuery = "" })
                    }
                }
            },
            containerColor = Color.Transparent
        ) { padding ->
            if (isLoading) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Color.Cyan) }
            } else {
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

                LazyColumn(modifier = Modifier.fillMaxSize().padding(padding)) {
                    if (searchQuery.isEmpty()) {
                        val featured = allChannels.value.filter { it.isFeatured }
                        if (featured.isNotEmpty()) {
                            item { FeaturedCarousel(featured, navController) }
                        }
                        item { CategoryTabs(categories.value, selectedCategory) { selectedCategory = it } }
                    }
                    
                    if (filteredChannels.isEmpty()) {
                        item { EmptyState(isFavorites = selectedCategory == "Favorites") }
                    } else {
                        items(filteredChannels.chunked(3)) { rowItems ->
                            Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), Arrangement.spacedBy(16.dp)) {
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
        }
    }
    
    if (showLinkSelectorDialog && selectedChannelForLinks != null) {
        LinkSelectorDialog(
            channel = selectedChannelForLinks!!,
            onDismiss = { showLinkSelectorDialog = false },
            onLinkSelected = { selectedUrl ->
                showLinkSelectorDialog = false
                val encodedUrl = URLEncoder.encode(selectedUrl, "UTF-8")
                navController.navigate("player/$encodedUrl")
            }
        )
    }

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
}

@Composable
fun AppDrawer(navController: NavController, onCloseDrawer: () -> Unit) {
    ModalDrawerSheet(
        drawerContainerColor = Color(0xFF0F172A),
        drawerContentColor = Color.White
    ) {
        Spacer(Modifier.height(24.dp))
        Text(
            "STREAMX ULTRA",
            modifier = Modifier.padding(horizontal = 28.dp, vertical = 16.dp),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold
        )
        Divider(color = Color.White.copy(0.2f), modifier = Modifier.padding(horizontal = 28.dp))
        
        NavigationDrawerItem(
            label = { Text("Home") },
            selected = true,
            onClick = { onCloseDrawer() },
            icon = { Icon(Icons.Default.Home, null) },
            colors = NavigationDrawerItemDefaults.colors(
                selectedContainerColor = Color.Cyan.copy(0.2f),
                unselectedContainerColor = Color.Transparent
            ),
            modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding)
        )
        NavigationDrawerItem(
            label = { Text("My Account") },
            selected = false,
            onClick = { onCloseDrawer(); navController.navigate("account") },
            icon = { Icon(Icons.Default.Person, null) },
            colors = NavigationDrawerItemDefaults.colors(unselectedContainerColor = Color.Transparent),
            modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding)
        )
        NavigationDrawerItem(
            label = { Text("Settings") },
            selected = false,
            onClick = { onCloseDrawer(); navController.navigate("settings") },
            icon = { Icon(Icons.Default.Settings, null) },
            colors = NavigationDrawerItemDefaults.colors(unselectedContainerColor = Color.Transparent),
            modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding)
        )
        NavigationDrawerItem(
            label = { Text("Theme") },
            selected = false,
            onClick = { onCloseDrawer(); navController.navigate("theme") },
            icon = { Icon(Icons.Default.InvertColors, null) },
            colors = NavigationDrawerItemDefaults.colors(unselectedContainerColor = Color.Transparent),
            modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding)
        )
    }
}

@Composable
fun FuturisticBackground() {
    val infiniteTransition = rememberInfiniteTransition(label = "")
    val color1 by infiniteTransition.animateColor(
        initialValue = Color(0xFF1E1B4B), targetValue = Color(0xFF312E81),
        animationSpec = infiniteRepeatable(tween(10000), RepeatMode.Reverse), label = ""
    )
    Box(modifier = Modifier.fillMaxSize().background(Color(0xFF020617))) {
        Box(modifier = Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(color1, Color(0xFF020617)))).blur(80.dp))
    }
}

@Composable
fun SearchBar(query: String, onQueryChange: (String) -> Unit, onClear: () -> Unit) {
    Box(Modifier.fillMaxWidth().padding(16.dp).clip(RoundedCornerShape(16.dp)).background(Color.White.copy(0.05f)).border(1.dp, Color.White.copy(0.1f), RoundedCornerShape(16.dp))) {
        TextField(
            value = query, onValueChange = onQueryChange,
            placeholder = { Text("Search TV Channels...", color = Color.White.copy(0.3f)) },
            modifier = Modifier.fillMaxWidth(),
            colors = TextFieldDefaults.colors(focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent, cursorColor = Color.Cyan, focusedTextColor = Color.White, unfocusedTextColor = Color.White, focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent),
            singleLine = true,
            trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = onClear) { Icon(Icons.Default.Close, null, tint = Color.White.copy(0.5f)) } }
        )
    }
}

@Composable
fun ChannelCard(channel: Channel, isFavorite: Boolean, modifier: Modifier, onFavoriteToggle: () -> Unit, onClick: () -> Unit) {
    Column(modifier = modifier.clickable(onClick = onClick), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .aspectRatio(1f)
                .clip(RoundedCornerShape(28.dp))
                .background(Color.White.copy(0.03f))
                .border(
                    width = 1.dp,
                    brush = Brush.horizontalGradient(colors = listOf(Color.White.copy(0.1f), Color.Cyan.copy(0.1f))),
                    shape = RoundedCornerShape(28.dp)
                ),
            contentAlignment = Alignment.Center
        ) {
            AsyncImage(model = channel.logoUrl, contentDescription = null, modifier = Modifier.fillMaxSize(0.65f), contentScale = ContentScale.Fit)
            Box(Modifier.align(Alignment.TopEnd).padding(4.dp).size(32.dp).clip(CircleShape).background(Color.Black.copy(0.3f)).clickable(onClick = onFavoriteToggle), contentAlignment = Alignment.Center) {
                Icon(if (isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder, null, tint = if (isFavorite) Color.Red else Color.White, modifier = Modifier.size(16.dp))
            }
        }
        Text(channel.name, color = Color.White.copy(0.7f), fontSize = 11.sp, maxLines = 1, modifier = Modifier.padding(top = 8.dp), fontWeight = FontWeight.Medium)
    }
}

@Composable
fun CategoryTabs(categories: List<String>, selected: String, onSelect: (String) -> Unit) {
    LazyRow(contentPadding = PaddingValues(horizontal = 24.dp, vertical = 20.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        items(categories) { cat ->
            val isSelected = cat == selected
            Box(modifier = Modifier.clip(CircleShape).background(if (isSelected) Color.Cyan else Color.White.copy(0.05f)).clickable { onSelect(cat) }.padding(horizontal = 20.dp, vertical = 8.dp)) {
                Text(cat, color = if (isSelected) Color.Black else Color.White.copy(0.6f), fontWeight = if (isSelected) FontWeight.ExtraBold else FontWeight.Normal, fontSize = 13.sp)
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FeaturedCarousel(featured: List<Channel>, navController: NavController) {
    val pagerState = rememberPagerState(pageCount = { featured.size })

    LaunchedEffect(pagerState.pageCount) {
        if (pagerState.pageCount > 1) {
            while (true) {
                delay(5000L)
                val nextPage = (pagerState.currentPage + 1) % pagerState.pageCount
                pagerState.animateScrollToPage(nextPage)
            }
        }
    }

    HorizontalPager(
        state = pagerState,
        contentPadding = PaddingValues(horizontal = 40.dp),
        modifier = Modifier.padding(top = 12.dp, bottom = 12.dp)
    ) { page ->
        val pageOffset = (pagerState.currentPage - page) + pagerState.currentPageOffsetFraction

        Card(
            modifier = Modifier
                .graphicsLayer {
                    val scale = lerp(0.85f, 1f, 1f - pageOffset.absoluteValue.coerceIn(0f, 1f))
                    scaleX = scale
                    scaleY = scale
                    alpha = lerp(0.5f, 1f, 1f - pageOffset.absoluteValue.coerceIn(0f, 1f))
                }
                .fillMaxWidth()
                .height(170.dp)
                .clickable {
                    if (featured[page].streamUrls.isNotEmpty()) {
                        val encodedUrl = URLEncoder.encode(featured[page].streamUrls.first(), "UTF-8")
                        navController.navigate("player/$encodedUrl")
                    }
                },
            shape = RoundedCornerShape(32.dp)
        ) {
            Box {
                AsyncImage(model = featured[page].logoUrl, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(0.85f)))))
                Text(featured[page].name, color = Color.White, fontWeight = FontWeight.Black, fontSize = 20.sp, modifier = Modifier.align(Alignment.BottomStart).padding(20.dp))
            }
        }
    }
}

@Composable
fun EmptyState(isFavorites: Boolean) {
    Box(Modifier.fillMaxWidth().padding(top = 80.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(if (isFavorites) "Your favorites list is empty" else "No matching channels", color = Color.White.copy(0.4f), fontSize = 15.sp)
            Text(if (isFavorites) "Tap the heart to save channels" else "Try another search term", color = Color.Cyan.copy(0.3f), fontSize = 12.sp)
        }
    }
}

@Composable
fun LinkSelectorDialog(
    channel: Channel,
    onDismiss: () -> Unit,
    onLinkSelected: (String) -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF27272A))
        ) {
            Column(modifier = Modifier.padding(vertical = 16.dp)) {
                Text(
                    text = "Multiple Links Available",
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp,
                    color = Color.White,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)
                )
                Spacer(modifier = Modifier.height(8.dp))
                Divider(color = Color.White.copy(0.1f))
                LazyColumn {
                    itemsIndexed(channel.streamUrls) { index, url ->
                        Text(
                            text = "LINK ${index + 1}",
                            color = Color.White.copy(0.9f),
                            fontSize = 16.sp,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onLinkSelected(url) }
                                .padding(horizontal = 24.dp, vertical = 16.dp)
                        )
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
        title = { Text("New Update Available! (${release.tag_name})") },
        text = {
            val cleanBody = release.body.lines().filterNot { it.startsWith("versionCode:") }.joinToString("\n")
            Text(cleanBody)
        },
        confirmButton = { Button(onClick = onUpdateClick) { Text("Update Now") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Later") } }
    )
}