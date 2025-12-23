package com.aeoncorex.streamx.ui.home

import android.content.Context
import android.util.Log
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
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
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.lerp
import androidx.compose.ui.window.Dialog
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import coil.compose.AsyncImage
import com.aeoncorex.streamx.R
import com.aeoncorex.streamx.model.Channel
import com.aeoncorex.streamx.model.Event
import com.aeoncorex.streamx.model.GitHubRelease
import com.aeoncorex.streamx.services.UpdateChecker
import com.aeoncorex.streamx.util.ChannelCategorizer
import com.aeoncorex.streamx.util.ChannelGenre
import com.valentinilk.shimmer.shimmer
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.GET
import retrofit2.http.Url
import java.net.URLEncoder
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlin.math.absoluteValue

// --- CONFIGURATION & DATA ---

enum class HomeTab { EVENTS, LIVE_TV, FAVORITES }
private val Context.dataStore by preferencesDataStore(name = "favorites_prefs")
private val FAVORITES_KEY = stringSetPreferencesKey("favorite_ids")

interface IPTVApi {
    @GET("index.json") suspend fun getIndex(): Map<String, Any>
    @GET suspend fun getChannelsByUrl(@Url url: String): Map<String, Any>
    @GET("categories/events.json") suspend fun getEvents(): Map<String, List<Event>>
}

// --- VIEWMODEL (Embedded) ---

class HomeViewModel : ViewModel() {
    private val api: IPTVApi = Retrofit.Builder()
        .baseUrl("https://raw.githubusercontent.com/cybernahid-dev/streamx-iptv-data/main/")
        .addConverterFactory(GsonConverterFactory.create())
        .build().create(IPTVApi::class.java)

    private val _isLoading = MutableStateFlow(true)
    val isLoading = _isLoading.asStateFlow()
    private val _allChannels = MutableStateFlow<List<Channel>>(emptyList())
    val allChannels = _allChannels.asStateFlow()
    private val _liveEvents = MutableStateFlow<List<Event>>(emptyList())
    val liveEvents = _liveEvents.asStateFlow()
    private val _upcomingEvents = MutableStateFlow<List<Event>>(emptyList())
    val upcomingEvents = _upcomingEvents.asStateFlow()

    init { loadAllData() }

    private fun loadAllData() {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                val eventsJob = launch { loadEvents() }
                val channelsJob = launch { loadChannels() }
                eventsJob.join(); channelsJob.join()
            } catch (e: Exception) { Log.e("HomeVM", "Error", e) } 
            finally { _isLoading.value = false }
        }
    }

    private suspend fun loadEvents() {
        try {
            val allEvents = api.getEvents()["events"] ?: emptyList()
            val now = LocalDateTime.now(ZoneId.systemDefault())
            _liveEvents.value = allEvents.filter { event -> 
                try { Instant.parse(event.startTime).atZone(ZoneId.systemDefault()).toLocalDateTime().isBefore(now) } catch(e:Exception){false}
            }
            _upcomingEvents.value = allEvents.filter { event -> 
                 try {
                    val startTime = Instant.parse(event.startTime).atZone(ZoneId.systemDefault()).toLocalDateTime()
                    startTime.isAfter(now) && ChronoUnit.HOURS.between(now, startTime) < 24 
                 } catch(e:Exception){false}
            }
        } catch (e: Exception) { Log.e("HomeVM", "Event Error", e) }
    }

    private suspend fun loadChannels() {
        try {
            val index = api.getIndex()
            val cats = index["categories"] as? List<Map<String, Any>>
            val masterList = mutableListOf<Channel>()
            cats?.forEach { cat ->
                val fileName = cat["file"] as String
                val catName = cat["name"] as String
                if (fileName.contains("events.json")) return@forEach
                try {
                    val res = api.getChannelsByUrl(fileName)
                    (res["channels"] as? List<Map<String, Any>>)?.forEach { ch ->
                         masterList.add(Channel(
                            id = (ch["id"] as? String) ?: ch.hashCode().toString(),
                            name = (ch["name"] as? String) ?: "No Name",
                            logoUrl = (ch["logoUrl"] as? String) ?: "",
                            streamUrls = (ch["streamUrls"] as? List<String>) ?: emptyList(),
                            country = catName,
                            genre = ChannelCategorizer.getGenreFromString(ch["genre"] as? String),
                            isFeatured = (ch["isFeatured"] as? Boolean) ?: false
                        ))
                    }
                } catch (e: Exception) { Log.e("HomeVM", "Cat Error: $fileName", e) }
            }
            _allChannels.value = masterList
        } catch (e: Exception) { Log.e("HomeVM", "Channel Error", e) }
    }
}

// --- MAIN SCREEN ---

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    navController: NavController,
    homeViewModel: HomeViewModel = viewModel()
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    
    // UI States
    var currentTab by remember { mutableStateOf(HomeTab.EVENTS) }
    var isSearchActive by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    
    // Data States
    val isLoading by homeViewModel.isLoading.collectAsState()
    val allChannels by homeViewModel.allChannels.collectAsState()
    
    // Popups
    var showLinkSelectorDialog by remember { mutableStateOf(false) }
    var selectedChannelForLinks by remember { mutableStateOf<Channel?>(null) }
    var showUpdateDialog by remember { mutableStateOf(false) }
    var latestReleaseInfo by remember { mutableStateOf<GitHubRelease?>(null) }

    LaunchedEffect(Unit) {
        val release = UpdateChecker.checkForUpdate(context)
        if (release != null) { latestReleaseInfo = release; showUpdateDialog = true }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = { AppDrawer(navController, onCloseDrawer = { scope.launch { drawerState.close() } }) }
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            FuturisticBackground()
            
            Scaffold(
                topBar = {
                    if (!isSearchActive) {
                        CenterAlignedTopAppBar(
                            title = { Text("STREAMX ULTRA", fontWeight = FontWeight.ExtraBold, letterSpacing = 2.sp, color = Color.White) },
                            navigationIcon = {
                                IconButton(onClick = { scope.launch { drawerState.open() } }) {
                                    Icon(Icons.Default.Menu, "Menu", tint = Color.Cyan)
                                }
                            },
                            actions = {
                                IconButton(onClick = { isSearchActive = true }) {
                                    Icon(Icons.Default.Search, "Search", tint = Color.Cyan)
                                }
                            },
                            colors = TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor = Color.Transparent)
                        )
                    }
                },
                containerColor = Color.Transparent
            ) { padding ->
                Column(modifier = Modifier.padding(padding)) {
                    
                    if (isSearchActive) {
                        SearchUI(
                            allChannels = allChannels,
                            onClose = { isSearchActive = false },
                            onChannelClick = { ch -> selectedChannelForLinks = ch; showLinkSelectorDialog = true }
                        )
                    } else {
                        // Tabs
                        HomeTabRow(selectedTab = currentTab, onTabSelected = { currentTab = it })
                        
                        // Content
                        AnimatedContent(
                            targetState = currentTab,
                            label = "TabAnimation",
                            transitionSpec = { fadeIn(tween(300)) togetherWith fadeOut(tween(300)) }
                        ) { tab ->
                            when (tab) {
                                HomeTab.EVENTS -> EventsTabContent(navController, homeViewModel, isLoading) { ch -> selectedChannelForLinks = ch; showLinkSelectorDialog = true }
                                HomeTab.LIVE_TV -> LiveTVTabContent(homeViewModel, isLoading) { ch -> selectedChannelForLinks = ch; showLinkSelectorDialog = true }
                                HomeTab.FAVORITES -> FavoritesTabContent(homeViewModel) { ch -> selectedChannelForLinks = ch; showLinkSelectorDialog = true }
                            }
                        }
                    }
                }
            }
        }
    }

    // Dialogs
    if (showLinkSelectorDialog && selectedChannelForLinks != null) {
        LinkSelectorDialog(selectedChannelForLinks!!, { showLinkSelectorDialog = false }) { url ->
            showLinkSelectorDialog = false
            val encodedUrl = URLEncoder.encode(url, "UTF-8")
            navController.navigate("player/$encodedUrl")
        }
    }
    if (showUpdateDialog && latestReleaseInfo != null) {
        UpdateDialog(latestReleaseInfo!!, { showUpdateDialog = false }) {
            showUpdateDialog = false
            UpdateChecker.downloadAndInstall(context, latestReleaseInfo!!)
        }
    }
}

// --- SUB-SCREENS (CONTENT) ---

@Composable
fun EventsTabContent(
    navController: NavController, 
    viewModel: HomeViewModel, 
    isLoading: Boolean,
    onChannelClick: (Channel) -> Unit
) {
    val liveEvents by viewModel.liveEvents.collectAsState()
    val upcomingEvents by viewModel.upcomingEvents.collectAsState()
    val allChannels by viewModel.allChannels.collectAsState()

    if (isLoading && liveEvents.isEmpty() && upcomingEvents.isEmpty()) {
        LoadingShimmer()
    } else if (liveEvents.isEmpty() && upcomingEvents.isEmpty()) {
        EmptyStateMessage("No Live Events Found")
    } else {
        LazyColumn(contentPadding = PaddingValues(bottom = 20.dp)) {
            // Featured Carousel at the top of Events tab (like HD Streamz)
            item { 
                FeaturedCarousel(
                    channels = allChannels.filter { it.isFeatured }, 
                    navController = navController, 
                    onChannelClick = onChannelClick
                ) 
            }

            if (liveEvents.isNotEmpty()) {
                item { SectionHeader("LIVE NOW 🔥") }
                items(liveEvents) { event -> EventCard(event, allChannels, onChannelClick) }
            }
            if (upcomingEvents.isNotEmpty()) {
                item { SectionHeader("UPCOMING ⏰") }
                items(upcomingEvents) { event -> EventCard(event, allChannels, onChannelClick) }
            }
        }
    }
}

@Composable
fun LiveTVContentTab(viewModel: HomeViewModel, isLoading: Boolean, onChannelClick: (Channel) -> Unit) {
    val allChannels by viewModel.allChannels.collectAsState()
    val genres = remember { ChannelGenre.values().filterNot { it == ChannelGenre.UNKNOWN } }
    var selectedGenre by remember { mutableStateOf<ChannelGenre?>(null) }

    if (isLoading && allChannels.isEmpty()) {
        LoadingShimmer()
    } else {
        LazyColumn(contentPadding = PaddingValues(bottom = 20.dp)) {
            // Genre Filter
            item {
                LazyRow(
                    contentPadding = PaddingValues(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(genres) { genre ->
                        val isSelected = selectedGenre == genre
                        FilterChip(
                            selected = isSelected,
                            onClick = { selectedGenre = if (isSelected) null else genre },
                            label = { Text(genre.name.lowercase().capitalize(), color = if(isSelected) Color.Black else Color.White) },
                            colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Color.Cyan, containerColor = Color.White.copy(0.1f))
                        )
                    }
                }
            }

            // Channels grouped by Country
            val grouped = remember(allChannels, selectedGenre) {
                val filtered = if (selectedGenre != null) allChannels.filter { it.genre == selectedGenre } else allChannels
                filtered.groupBy { it.country }
            }

            grouped.forEach { (country, channels) ->
                if (channels.isNotEmpty()) {
                    item { SectionHeader(country) }
                    item {
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            items(channels) { channel ->
                                SmallChannelCard(channel, onChannelClick)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun LiveTVTabContent(homeViewModel: HomeViewModel, isLoading: Boolean, onChannelClick: (Channel) -> Unit) {
    // Just delegating to the function above to keep names consistent
    LiveTVContentTab(homeViewModel, isLoading, onChannelClick)
}


@Composable
fun FavoritesContentTab(viewModel: HomeViewModel, onChannelClick: (Channel) -> Unit) {
    val context = LocalContext.current
    val allChannels by viewModel.allChannels.collectAsState()
    val favoriteIds by context.dataStore.data.map { it[FAVORITES_KEY] ?: emptySet() }.collectAsState(initial = emptySet())
    val favorites = remember(allChannels, favoriteIds) { allChannels.filter { it.id in favoriteIds } }

    if (favorites.isEmpty()) {
        EmptyStateMessage("No Favorites Yet")
    } else {
        LazyVerticalGrid(
            columns = GridCells.Adaptive(100.dp),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            items(favorites) { channel -> SmallChannelCard(channel, onChannelClick) }
        }
    }
}

// --- COMPONENTS ---

@Composable
fun HomeTabRow(selectedTab: HomeTab, onTabSelected: (HomeTab) -> Unit) {
    TabRow(
        selectedTabIndex = selectedTab.ordinal,
        containerColor = Color.Transparent,
        contentColor = Color.Cyan,
        indicator = { tabPositions ->
            TabRowDefaults.Indicator(
                Modifier.tabIndicatorOffset(tabPositions[selectedTab.ordinal]),
                color = Color.Cyan,
                height = 3.dp
            )
        },
        divider = { HorizontalDivider(color = Color.White.copy(0.1f)) }
    ) {
        HomeTab.values().forEach { tab ->
            Tab(
                selected = selectedTab == tab,
                onClick = { onTabSelected(tab) },
                text = { Text(tab.name.replace("_", " "), fontWeight = FontWeight.Bold) },
                unselectedContentColor = Color.White.copy(0.6f)
            )
        }
    }
}

@Composable
fun EventCard(event: Event, allChannels: List<Channel>, onChannelClick: (Channel) -> Unit) {
    val channels = remember(event.channelIds, allChannels) { allChannels.filter { it.id in event.channelIds } }
    
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White.copy(0.08f)),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                // Team 1
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.weight(1f)) {
                    AsyncImage(model = event.team1Logo, contentDescription = null, modifier = Modifier.size(40.dp))
                    Text(event.title.split(" vs ").firstOrNull() ?: "", color = Color.White, fontSize = 12.sp, maxLines = 1)
                }
                Text("VS", color = Color.Red, fontWeight = FontWeight.Bold, fontSize = 20.sp, modifier = Modifier.padding(horizontal = 8.dp))
                // Team 2
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.weight(1f)) {
                    AsyncImage(model = event.team2Logo, contentDescription = null, modifier = Modifier.size(40.dp))
                    Text(event.title.split(" vs ").lastOrNull()?.split(",")?.firstOrNull() ?: "", color = Color.White, fontSize = 12.sp, maxLines = 1)
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(event.tournament, color = Color.Cyan, fontSize = 12.sp, modifier = Modifier.align(Alignment.CenterHorizontally))
            
            if (channels.isNotEmpty()) {
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp), color = Color.White.copy(0.1f))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(channels) { ch ->
                        AsyncImage(
                            model = ch.logoUrl, contentDescription = null,
                            modifier = Modifier.size(30.dp).clip(CircleShape).clickable { onChannelClick(ch) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun SmallChannelCard(channel: Channel, onClick: () -> Unit) {
    Column(
        modifier = Modifier.width(100.dp).clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Card(
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = Color.White.copy(0.1f)),
            modifier = Modifier.size(100.dp).border(1.dp, Color.White.copy(0.1f), RoundedCornerShape(20.dp))
        ) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                AsyncImage(
                    model = channel.logoUrl, contentDescription = null,
                    modifier = Modifier.fillMaxSize(0.7f), contentScale = ContentScale.Fit,
                    placeholder = painterResource(id = R.mipmap.ic_launcher)
                )
            }
        }
        Text(
            text = channel.name, style = MaterialTheme.typography.bodySmall,
            maxLines = 1, color = Color.White.copy(0.8f),
            modifier = Modifier.padding(top = 6.dp), textAlign = TextAlign.Center
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FeaturedCarousel(channels: List<Channel>, navController: NavController, onChannelClick: (Channel) -> Unit) {
    if (channels.isEmpty()) return
    val pagerState = rememberPagerState(pageCount = { channels.size })
    LaunchedEffect(pagerState.pageCount) {
        if (pagerState.pageCount > 1) { while(true) { delay(5000); pagerState.animateScrollToPage((pagerState.currentPage + 1) % pagerState.pageCount) } }
    }
    HorizontalPager(state = pagerState, contentPadding = PaddingValues(horizontal = 32.dp), pageSpacing = 16.dp, modifier = Modifier.padding(vertical = 16.dp)) { page ->
        val channel = channels[page]
        Card(
            modifier = Modifier.fillMaxWidth().height(180.dp).clickable { onChannelClick(channel) },
            shape = RoundedCornerShape(24.dp)
        ) {
            Box {
                AsyncImage(model = channel.logoUrl, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(0.9f)))))
                Text(channel.name, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 20.sp, modifier = Modifier.align(Alignment.BottomStart).padding(16.dp))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchUI(allChannels: List<Channel>, onClose: () -> Unit, onChannelClick: (Channel) -> Unit) {
    var query by remember { mutableStateOf("") }
    val filtered = remember(query) { if(query.isEmpty()) emptyList() else allChannels.filter { it.name.contains(query, true) } }
    
    SearchBar(
        query = query, onQueryChange = { query = it }, onSearch = {}, active = true, onActiveChange = { if(!it) onClose() },
        placeholder = { Text("Search channels...") },
        leadingIcon = { Icon(Icons.Default.Search, null) },
        trailingIcon = { IconButton(onClick = onClose) { Icon(Icons.Default.Close, null) } }
    ) {
        LazyVerticalGrid(columns = GridCells.Adaptive(100.dp), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            items(filtered) { ch -> SmallChannelCard(ch) { onChannelClick(ch) } }
        }
    }
}

// --- HELPERS ---

@Composable
fun FuturisticBackground() {
    val infiniteTransition = rememberInfiniteTransition(label = "bg")
    val color1 by infiniteTransition.animateColor(
        initialValue = Color(0xFF1A1A2E), targetValue = Color(0xFF16213E),
        animationSpec = infiniteRepeatable(tween(5000), RepeatMode.Reverse), label = "c1"
    )
    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(color1, Color.Black))))
}

@Composable
fun SectionHeader(title: String) {
    Text(title, fontWeight = FontWeight.Bold, color = Color.Cyan, fontSize = 18.sp, modifier = Modifier.padding(start = 16.dp, top = 24.dp, bottom = 8.dp))
}

@Composable
fun EmptyStateMessage(msg: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(msg, color = Color.Gray, fontSize = 16.sp)
    }
}

@Composable
fun LoadingShimmer() {
    Column(Modifier.padding(16.dp)) {
        Box(Modifier.fillMaxWidth().height(180.dp).clip(RoundedCornerShape(16.dp)).shimmer().background(Color.Gray))
        Spacer(Modifier.height(16.dp))
        Row {
            repeat(3) { Box(Modifier.size(100.dp).clip(RoundedCornerShape(16.dp)).shimmer().background(Color.Gray)); Spacer(Modifier.width(16.dp)) }
        }
    }
}

@Composable
fun LinkSelectorDialog(channel: Channel, onDismiss: () -> Unit, onLinkSelected: (String) -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E))) {
            Column(Modifier.padding(16.dp)) {
                Text(channel.name, color = Color.White, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(16.dp))
                LazyColumn {
                    itemsIndexed(channel.streamUrls) { i, url ->
                        TextButton(onClick = { onLinkSelected(url) }, modifier = Modifier.fillMaxWidth()) {
                            Text("Link ${i + 1}", color = Color.Cyan)
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
        title = { Text("Update Available") },
        text = { Text("Version ${release.tag_name} is available.") },
        confirmButton = { Button(onClick = onUpdateClick) { Text("Update") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Later") } }
    )
}

@Composable
fun AppDrawer(navController: NavController, onCloseDrawer: () -> Unit) {
    ModalDrawerSheet(drawerContainerColor = Color(0xFF121212)) {
        Spacer(Modifier.height(20.dp))
        Text("STREAMX", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(24.dp))
        HorizontalDivider(color = Color.Gray)
        NavigationDrawerItem(label = { Text("Home", color = Color.White) }, selected = true, onClick = onCloseDrawer, icon = { Icon(Icons.Default.Home, null, tint = Color.White) }, colors = NavigationDrawerItemDefaults.colors(unselectedContainerColor = Color.Transparent))
        NavigationDrawerItem(label = { Text("Account", color = Color.White) }, selected = false, onClick = { navController.navigate("account") }, icon = { Icon(Icons.Default.Person, null, tint = Color.White) }, colors = NavigationDrawerItemDefaults.colors(unselectedContainerColor = Color.Transparent))
        NavigationDrawerItem(label = { Text("Settings", color = Color.White) }, selected = false, onClick = { navController.navigate("settings") }, icon = { Icon(Icons.Default.Settings, null, tint = Color.White) }, colors = NavigationDrawerItemDefaults.colors(unselectedContainerColor = Color.Transparent))
    }
}

// String extension for capitalizing
fun String.capitalize(): String {
    return this.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
}