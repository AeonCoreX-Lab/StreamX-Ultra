package com.aeoncorex.streamx.ui.home

import android.content.Context
import androidx.compose.animation.animateColor
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.lerp
import androidx.compose.ui.window.Dialog
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import coil.compose.AsyncImage
import com.aeoncorex.streamx.R
import com.aeoncorex.streamx.model.Channel
import com.aeoncorex.streamx.model.Event
import com.aeoncorex.streamx.model.GitHubRelease
import com.aeoncorex.streamx.services.UpdateChecker
import com.valentinilk.shimmer.shimmer
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import retrofit2.http.GET
import retrofit2.http.Url
import java.net.URLEncoder
import kotlin.math.absoluteValue

// DataStore and API Interface
private val Context.dataStore by preferencesDataStore(name = "favorites_prefs")
private val FAVORITES_KEY = stringSetPreferencesKey("favorite_ids")

interface IPTVApi {
    @GET("index.json")
    suspend fun getIndex(): Map<String, Any>
    @GET
    suspend fun getChannelsByUrl(@Url url: String): Map<String, Any>
    @GET("categories/events.json")
    suspend fun getEvents(): Map<String, List<Event>>
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    navController: NavController,
    homeViewModel: HomeViewModel = viewModel()
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    
    val isLoading by homeViewModel.isLoading.collectAsState()
    val allChannels by homeViewModel.allChannels.collectAsState()
    val liveEvents by homeViewModel.liveEvents.collectAsState()
    val upcomingEvents by homeViewModel.upcomingEvents.collectAsState()
    
    var showLinkSelectorDialog by remember { mutableStateOf(false) }
    var selectedChannelForLinks by remember { mutableStateOf<Channel?>(null) }
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    var showUpdateDialog by remember { mutableStateOf(false) }
    var latestReleaseInfo by remember { mutableStateOf<GitHubRelease?>(null) }
    val favoriteIds by context.dataStore.data
        .map { preferences -> preferences[FAVORITES_KEY] ?: emptySet() }
        .collectAsState(initial = emptySet())

    LaunchedEffect(Unit) {
        val release = UpdateChecker.checkForUpdate(context)
        if (release != null) {
            latestReleaseInfo = release
            showUpdateDialog = true
        }
    }
    
    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = { AppDrawer(navController = navController, onCloseDrawer = { scope.launch { drawerState.close() } }) }
    ) {
        Scaffold(
            topBar = {
                CenterAlignedTopAppBar(
                    title = { Text("STREAMX ULTRA", style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Black, color = Color.White)) },
                    navigationIcon = {
                        IconButton(onClick = { scope.launch { drawerState.open() } }) {
                            Icon(Icons.Default.Menu, "Menu", tint = Color.White)
                        }
                    },
                    actions = {
                        IconButton(onClick = { /* TODO: Navigate to Explore screen */ }) {
                            Icon(Icons.Default.Search, "Search", tint = MaterialTheme.colorScheme.secondary)
                        }
                    },
                    colors = TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor = Color.Transparent)
                )
            },
            containerColor = Color.Transparent
        ) { padding ->
            if (isLoading && allChannels.isEmpty()) {
                LoadingShimmerEffect(modifier = Modifier.padding(padding))
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize().padding(padding)) {
                    item {
                        FeaturedCarousel(
                            channels = allChannels.filter { it.isFeatured },
                            navController = navController
                        )
                    }

                    if (liveEvents.isNotEmpty()) {
                        item {
                            SectionTitle("Live Now 🔥", onSeeAllClick = { /* navController.navigate("events") */ })
                            LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                items(liveEvents) { event ->
                                    // EventCard(event = event)
                                }
                            }
                        }
                    }
                    if (upcomingEvents.isNotEmpty()) {
                        item {
                            SectionTitle("Upcoming ⏰", onSeeAllClick = { /* navController.navigate("events") */ })
                            LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                items(upcomingEvents) { event ->
                                    // EventCard(event = event)
                                }
                            }
                        }
                    }

                    val channelsByCountry = remember(allChannels) { allChannels.groupBy { it.country } }
                    channelsByCountry.forEach { (country, channels) ->
                        if (channels.isNotEmpty()) {
                            item {
                                SectionTitle(country, onSeeAllClick = { /* navController.navigate("country_channels/$country") */ })
                                LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                                    items(items = channels.take(8)) { channel ->
                                        SmallChannelCard(channel = channel, onClick = {
                                            if (channel.streamUrls.isNotEmpty()) {
                                                selectedChannelForLinks = channel
                                                showLinkSelectorDialog = true
                                            }
                                        })
                                    }
                                }
                            }
                        }
                    }
                    item { Spacer(modifier = Modifier.height(32.dp)) }
                }
            }
        }
    }
    
    if (showLinkSelectorDialog && selectedChannelForLinks != null) { LinkSelectorDialog(channel = selectedChannelForLinks!!, onDismiss = { showLinkSelectorDialog = false }, onLinkSelected = { selectedUrl -> showLinkSelectorDialog = false; val encodedUrl = URLEncoder.encode(selectedUrl, "UTF-8"); navController.navigate("player/$encodedUrl") }) }
    if (showUpdateDialog && latestReleaseInfo != null) { UpdateDialog(release = latestReleaseInfo!!, onDismiss = { showUpdateDialog = false }, onUpdateClick = { showUpdateDialog = false; UpdateChecker.downloadAndInstall(context, latestReleaseInfo!!) }) }
}

@Composable
fun AppDrawer(navController: NavController, onCloseDrawer: () -> Unit) {
    ModalDrawerSheet(
        drawerContainerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f),
        drawerContentColor = MaterialTheme.colorScheme.onSurface
    ) {
        Spacer(Modifier.height(24.dp))
        Text("STREAMX ULTRA", modifier = Modifier.padding(horizontal = 28.dp, vertical = 16.dp), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(0.2f), modifier = Modifier.padding(horizontal = 28.dp))
        
        NavigationDrawerItem(label = { Text("Home Hub") }, selected = false, onClick = { onCloseDrawer(); navController.navigate("home_hub") { launchSingleTop = true } }, icon = { Icon(Icons.Default.Home, null) }, colors = NavigationDrawerItemDefaults.colors(unselectedContainerColor = Color.Transparent), modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding))
        NavigationDrawerItem(label = { Text("My Account") }, selected = false, onClick = { onCloseDrawer(); navController.navigate("account") }, icon = { Icon(Icons.Default.Person, null) }, colors = NavigationDrawerItemDefaults.colors(unselectedContainerColor = Color.Transparent), modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding))
        NavigationDrawerItem(label = { Text("Settings") }, selected = false, onClick = { onCloseDrawer(); navController.navigate("settings") }, icon = { Icon(Icons.Default.Settings, null) }, colors = NavigationDrawerItemDefaults.colors(unselectedContainerColor = Color.Transparent), modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding))
        NavigationDrawerItem(label = { Text("Theme") }, selected = false, onClick = { onCloseDrawer(); navController.navigate("theme") }, icon = { Icon(Icons.Default.InvertColors, null) }, colors = NavigationDrawerItemDefaults.colors(unselectedContainerColor = Color.Transparent), modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding))
    }
}

@Composable
fun FuturisticBackground() {
    val infiniteTransition = rememberInfiniteTransition(label = "bg_transition")
    val color1 by infiniteTransition.animateColor(
        initialValue = MaterialTheme.colorScheme.primary.copy(alpha = 0.4f), targetValue = MaterialTheme.colorScheme.secondary.copy(alpha = 0.4f),
        animationSpec = infiniteRepeatable(tween(12000), RepeatMode.Reverse), label = "bg_color"
    )
    Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Box(modifier = Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(color1, MaterialTheme.colorScheme.background))).blur(100.dp))
    }
}

@Composable
fun LoadingShimmerEffect(modifier: Modifier = Modifier) {
    LazyColumn(modifier = modifier, contentPadding = PaddingValues(16.dp)) {
        item { Box(modifier = Modifier.fillMaxWidth().height(170.dp).padding(horizontal = 24.dp, vertical = 12.dp).shimmer().background(MaterialTheme.colorScheme.surface.copy(alpha = 0.5f), RoundedCornerShape(32.dp))) }
        item { Spacer(modifier = Modifier.height(40.dp)) }
        items(3) {
            SectionTitle("Loading...", onSeeAllClick = {})
            LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                items(5) {
                     Column(modifier = Modifier.width(100.dp).shimmer(), horizontalAlignment = Alignment.CenterHorizontally) {
                        Card(shape = RoundedCornerShape(20.dp), modifier = Modifier.size(100.dp)) { Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface.copy(alpha = 0.5f))) }
                        Spacer(modifier = Modifier.height(8.dp))
                        Box(modifier = Modifier.height(12.dp).fillMaxWidth(0.8f).background(MaterialTheme.colorScheme.surface.copy(alpha = 0.5f), RoundedCornerShape(4.dp)))
                    }
                }
            }
        }
    }
}

@Composable
fun SectionTitle(title: String, onSeeAllClick: () -> Unit) {
    Row(modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 16.dp, bottom = 8.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(text = title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        TextButton(onClick = onSeeAllClick) { Text("See All") }
    }
}

@Composable
fun SmallChannelCard(channel: Channel, onClick: () -> Unit) {
    Column(modifier = Modifier.width(100.dp).clickable(onClick = onClick), horizontalAlignment = Alignment.CenterHorizontally) {
        Card(shape = RoundedCornerShape(20.dp), modifier = Modifier.size(100.dp), elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)) {
            AsyncImage(model = channel.logoUrl, contentDescription = channel.name, modifier = Modifier.fillMaxSize().padding(16.dp), contentScale = ContentScale.Fit, placeholder = painterResource(id = R.mipmap.ic_launcher))
        }
        Text(text = channel.name, style = MaterialTheme.typography.bodySmall, maxLines = 1, modifier = Modifier.padding(top = 8.dp))
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FeaturedCarousel(channels: List<Channel>, navController: NavController) {
    if (channels.isEmpty()) return
    val pagerState = rememberPagerState(pageCount = { channels.size })

    LaunchedEffect(pagerState.pageCount) {
        if (pagerState.pageCount > 1) { while (true) { delay(5000L); val nextPage = (pagerState.currentPage + 1) % pagerState.pageCount; pagerState.animateScrollToPage(nextPage) } }
    }

    HorizontalPager(state = pagerState, contentPadding = PaddingValues(horizontal = 40.dp), modifier = Modifier.padding(top = 12.dp, bottom = 12.dp)) { page ->
        val pageOffset = (pagerState.currentPage - page) + pagerState.currentPageOffsetFraction
        Card(
            modifier = Modifier
                .graphicsLayer {
                    val scale = lerp(0.85f, 1f, 1f - pageOffset.absoluteValue.coerceIn(0f, 1f))
                    scaleX = scale; scaleY = scale; alpha = lerp(0.5f, 1f, 1f - pageOffset.absoluteValue.coerceIn(0f, 1f))
                }
                .fillMaxWidth().height(170.dp).clickable { if (channels[page].streamUrls.isNotEmpty()) { val encodedUrl = URLEncoder.encode(channels[page].streamUrls.first(), "UTF-8"); navController.navigate("player/$encodedUrl") } },
            shape = RoundedCornerShape(32.dp)
        ) {
            Box {
                AsyncImage(model = channels[page].logoUrl, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(0.85f)))))
                Text(channels[page].name, color = Color.White, fontWeight = FontWeight.Black, fontSize = 20.sp, modifier = Modifier.align(Alignment.BottomStart).padding(20.dp))
            }
        }
    }
}

@Composable
fun LinkSelectorDialog(channel: Channel, onDismiss: () -> Unit, onLinkSelected: (String) -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        Card(shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
            Column(modifier = Modifier.padding(vertical = 16.dp)) {
                Text("Multiple Links Available", fontWeight = FontWeight.Bold, fontSize = 18.sp, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
                Spacer(modifier = Modifier.height(8.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(0.1f))
                LazyColumn {
                    itemsIndexed(channel.streamUrls) { index, url ->
                        Text("LINK ${index + 1}", color = MaterialTheme.colorScheme.onSurface.copy(0.9f), fontSize = 16.sp, modifier = Modifier.fillMaxWidth().clickable { onLinkSelected(url) }.padding(horizontal = 24.dp, vertical = 16.dp))
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