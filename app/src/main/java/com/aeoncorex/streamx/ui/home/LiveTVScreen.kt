package com.aeoncorex.streamx.ui.home

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import android.widget.Toast
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.*
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
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.lerp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.zIndex
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.navigation.NavController
import coil.compose.AsyncImage
import com.aeoncorex.streamx.data.EventRepository
import com.aeoncorex.streamx.model.Channel
import com.aeoncorex.streamx.model.EventStream
import com.aeoncorex.streamx.model.GitHubRelease
import com.aeoncorex.streamx.model.LiveEvent
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
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.absoluteValue

// ─── DataStore ───────────────────────────────────────────────────────────────
private val Context.dataStore by preferencesDataStore(name = "favorites_prefs")
private val FAVORITES_KEY = stringSetPreferencesKey("favorite_ids")

val GlassWhite     = Color(0x1AFFFFFF)
val HeaderBackground = Color(0xFF0F0F15)

// ─── Retrofit API ────────────────────────────────────────────────────────────
interface IPTVApi {
    @GET("index.json")
    suspend fun getIndex(): Map<String, Any>
    @GET
    suspend fun getChannelsByUrl(@Url url: String): Map<String, Any>
}

fun isInternetAvailable(context: Context): Boolean {
    val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    val net = cm.activeNetwork ?: return false
    val caps = cm.getNetworkCapabilities(net) ?: return false
    return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
}

// ─── Sport colour helper ──────────────────────────────────────────────────────
fun sportColorFromHex(hex: String): Color = try {
    Color(android.graphics.Color.parseColor(hex))
} catch (_: Exception) { Color(0xFFE53935) }

// ════════════════════════════════════════════════════════════════════════════
//  ROOT SCREEN  (tab host)
// ════════════════════════════════════════════════════════════════════════════
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun LiveTVScreen(navController: NavController) {
    val context = LocalContext.current
    val scope   = rememberCoroutineScope()

    // HD Streamz has: EVENTS | LIVE TV | LIVE RADIO | FAVOURITES
    // We implement: EVENTS | LIVE TV
    var selectedTab by remember { mutableIntStateOf(0) }
    val tabs = listOf("EVENTS", "LIVE TV")

    val primaryColor   = MaterialTheme.colorScheme.primary
    val secondaryColor = MaterialTheme.colorScheme.secondary

    // ── Shared state ─────────────────────────────────────────────────────
    val allChannels    = remember { mutableStateOf<List<Channel>>(emptyList()) }
    val categories     = remember { mutableStateOf(listOf("All", "Favorites")) }
    var isLoading      by remember { mutableStateOf(true) }
    var isRefreshing   by remember { mutableStateOf(false) }
    var searchQuery    by remember { mutableStateOf("") }
    var isSearchActive by remember { mutableStateOf(false) }
    var selectedChannelForLinks by remember { mutableStateOf<Channel?>(null) }
    var showLinkDialog by remember { mutableStateOf(false) }
    var showUpdateDialog by remember { mutableStateOf(false) }
    var latestRelease  by remember { mutableStateOf<GitHubRelease?>(null) }
    var showNoInternet by remember { mutableStateOf(false) }
    val drawerState    = rememberDrawerState(DrawerValue.Closed)

    val favoriteIds by context.dataStore.data
        .map { it[FAVORITES_KEY] ?: emptySet() }
        .collectAsState(initial = emptySet())

    val categoryCounts = remember(allChannels.value, favoriteIds) {
        val counts = allChannels.value.groupingBy { it.category }.eachCount().toMutableMap()
        counts["All"]       = allChannels.value.size
        counts["Favorites"] = favoriteIds.size
        counts
    }

    val fetchData: (Boolean) -> Unit = { isRetry ->
        scope.launch {
            if (!isInternetAvailable(context)) {
                isLoading = false; isRefreshing = false
                showNoInternet = true
                if (isRetry) Toast.makeText(context, "Connection Failed", Toast.LENGTH_SHORT).show()
                return@launch
            }
            if (isRetry) EventRepository.clearCache()
            try {
                val release = UpdateChecker.checkForUpdate(context)
                if (release != null) { latestRelease = release; showUpdateDialog = true }

                val api = Retrofit.Builder()
                    .baseUrl("https://raw.githubusercontent.com/cybernahid-dev/streamx-iptv-data/main/")
                    .addConverterFactory(GsonConverterFactory.create())
                    .build().create(IPTVApi::class.java)

                val index = api.getIndex()
                val cats  = index["categories"] as? List<Map<String, Any>>
                val master = mutableListOf<Channel>()

                cats?.forEach { cat ->
                    val fileName = cat["file"] as String
                    val catName  = cat["name"]  as String
                    try {
                        val res = api.getChannelsByUrl(fileName)
                        val raw = (res["channels"] as? List<Map<String, Any>>)
                            ?: (res["categories"] as? List<Map<String, Any>>)
                                ?.flatMap { it["channels"] as List<Map<String, Any>> }
                        raw?.forEach { ch ->
                            master.add(Channel(
                                id         = (ch["id"] as? String) ?: ch.hashCode().toString(),
                                name       = (ch["name"] as? String) ?: "Unknown",
                                logoUrl    = (ch["logoUrl"] as? String) ?: "",
                                streamUrls = (ch["streamUrls"] as? List<String>) ?: emptyList(),
                                category   = catName,
                                isFeatured = (ch["isFeatured"] as? Boolean) ?: false
                            ))
                        }
                    } catch (e: Exception) { Log.e("IPTV", "cat error: $catName", e) }
                }
                allChannels.value = master
                categories.value  = listOf("All", "Favorites") + master.map { it.category }.distinct()
                isLoading = false; isRefreshing = false
            } catch (e: Exception) {
                isLoading = false; isRefreshing = false
                showNoInternet = true
                if (isRetry) Toast.makeText(context, "Fetch Error", Toast.LENGTH_SHORT).show()
            }
        }
    }
    LaunchedEffect(Unit) { fetchData(false) }

    ModalNavigationDrawer(
        drawerState   = drawerState,
        drawerContent = { AppDrawer(navController) { scope.launch { drawerState.close() } } }
    ) {
        CyberMeshBackground()

        Box(Modifier.fillMaxSize()) {
            PullToRefreshBox(
                isRefreshing = isRefreshing,
                onRefresh    = { isRefreshing = true; scope.launch { fetchData(true); delay(800); isRefreshing = false } },
                modifier     = Modifier.fillMaxSize()
            ) {
                // ── Search overlay ────────────────────────────────────────
                if (isSearchActive) {
                    SearchOverlay(
                        query         = searchQuery,
                        channels      = allChannels.value,
                        primaryColor  = primaryColor,
                        onQueryChange = { searchQuery = it },
                        onDismiss     = { isSearchActive = false },
                        onChannelClick = { ch ->
                            selectedChannelForLinks = ch
                            showLinkDialog    = true
                            isSearchActive    = false
                        }
                    )
                } else {
                    // ── Main content ──────────────────────────────────────
                    Column(Modifier.fillMaxSize()) {

                        // Fixed header (status-bar aware)
                        Surface(
                            color          = HeaderBackground,
                            shadowElevation = 8.dp,
                            modifier       = Modifier.fillMaxWidth().zIndex(2f)
                        ) {
                            Column(
                                Modifier
                                    .fillMaxWidth()
                                    .windowInsetsPadding(WindowInsets.statusBars)
                            ) {
                                // ── Top App Bar ───────────────────────────
                                Box(
                                    Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 16.dp, vertical = 8.dp)
                                        .clip(RoundedCornerShape(24.dp))
                                        .background(Color(0xFF1E1E24))
                                        .border(
                                            1.dp,
                                            Brush.horizontalGradient(listOf(Color.White.copy(0.1f), Color.White.copy(0.05f))),
                                            RoundedCornerShape(24.dp)
                                        )
                                ) {
                                    CenterAlignedTopAppBar(
                                        title = {
                                            Text(
                                                "STREAMX",
                                                style = TextStyle(
                                                    fontWeight  = FontWeight.Black,
                                                    fontSize    = 22.sp,
                                                    letterSpacing = 2.sp,
                                                    brush       = Brush.horizontalGradient(listOf(primaryColor, secondaryColor))
                                                )
                                            )
                                        },
                                        navigationIcon = {
                                            IconButton(onClick = { scope.launch { drawerState.open() } }) {
                                                Icon(Icons.Default.Menu, null, tint = Color.White)
                                            }
                                        },
                                        actions = {
                                            IconButton(onClick = { isSearchActive = true }) {
                                                Icon(Icons.Default.Search, null, tint = Color.White)
                                            }
                                        },
                                        colors = TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor = Color.Transparent)
                                    )
                                }

                                // ── HD Streamz style Tab Row ──────────────
                                HDStreamzTabRow(
                                    tabs        = tabs,
                                    selectedTab = selectedTab,
                                    primaryColor = primaryColor,
                                    onTabClick  = { selectedTab = it }
                                )
                            }
                        }

                        // ── Tab Content ───────────────────────────────────
                        Box(Modifier.fillMaxSize().weight(1f)) {
                            when (selectedTab) {
                                0 -> EventsTabContent(
                                    navController = navController,
                                    primaryColor  = primaryColor
                                )
                                1 -> LiveTVTabContent(
                                    navController           = navController,
                                    allChannels             = allChannels.value,
                                    categories              = categories.value,
                                    categoryCounts          = categoryCounts,
                                    favoriteIds             = favoriteIds,
                                    isLoading               = isLoading,
                                    primaryColor            = primaryColor,
                                    onChannelClick          = { ch ->
                                        selectedChannelForLinks = ch
                                        showLinkDialog = true
                                    },
                                    onFavoriteToggle        = { ch ->
                                        scope.launch {
                                            context.dataStore.edit { prefs ->
                                                val cur = prefs[FAVORITES_KEY] ?: emptySet()
                                                prefs[FAVORITES_KEY] =
                                                    if (ch.id in cur) cur - ch.id else cur + ch.id
                                            }
                                        }
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // ── Dialogs ───────────────────────────────────────────────────────────
    if (showLinkDialog && selectedChannelForLinks != null) {
        LinkSelectorDialog(
            channel   = selectedChannelForLinks!!,
            onDismiss = { showLinkDialog = false }
        ) { url ->
            showLinkDialog = false
            try {
                navController.navigate("player/${URLEncoder.encode(url, "UTF-8")}")
            } catch (_: Exception) { Toast.makeText(context, "Error", Toast.LENGTH_SHORT).show() }
        }
    }

    if (showUpdateDialog && latestRelease != null) {
        UpdateDialog(latestRelease!!, { showUpdateDialog = false }) {
            showUpdateDialog = false
            UpdateChecker.downloadAndInstall(context, latestRelease!!)
        }
    }
}

// ════════════════════════════════════════════════════════════════════════════
//  HD STREAMZ STYLE TAB ROW
//  Exact look: icon + label, blue underline indicator
// ════════════════════════════════════════════════════════════════════════════
@Composable
fun HDStreamzTabRow(
    tabs:         List<String>,
    selectedTab:  Int,
    primaryColor: Color,
    onTabClick:   (Int) -> Unit
) {
    val tabIcons = listOf(
        Icons.Rounded.LiveTv,        // EVENTS
        Icons.Rounded.Tv,            // LIVE TV
    )

    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .background(HeaderBackground)
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            tabs.forEachIndexed { index, label ->
                val isSelected = selectedTab == index
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication        = null
                        ) { onTabClick(index) }
                        .padding(vertical = 10.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        imageVector        = tabIcons[index],
                        contentDescription = label,
                        tint               = if (isSelected) primaryColor else Color.Gray,
                        modifier           = Modifier.size(22.dp)
                    )
                    Spacer(Modifier.height(3.dp))
                    Text(
                        text       = label,
                        color      = if (isSelected) primaryColor else Color.Gray,
                        fontSize   = 11.sp,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                        letterSpacing = 0.5.sp
                    )
                }
            }
        }
        // Blue underline indicator (exact HD Streamz look)
        Row(Modifier.fillMaxWidth()) {
            tabs.forEachIndexed { index, _ ->
                Box(
                    Modifier
                        .weight(1f)
                        .height(2.dp)
                        .background(
                            if (selectedTab == index) primaryColor else Color.Transparent
                        )
                )
            }
        }
        HorizontalDivider(color = Color.White.copy(0.08f))
    }
}

// ════════════════════════════════════════════════════════════════════════════
//  TAB 0 — EVENTS  (HD Streamz Events screen clone)
// ════════════════════════════════════════════════════════════════════════════
@Composable
fun EventsTabContent(
    navController: NavController,
    primaryColor:  Color
) {
    val scope   = rememberCoroutineScope()
    val context = LocalContext.current

    // Filter chips: All | Live Now | Today's | Tomorrow | Upcoming
    val filterChips = listOf("All", "Live Now", "Today's", "Tomorrow", "Upcoming")
    var selectedFilter by remember { mutableStateOf("All") }

    // Events data
    var allEvents  by remember { mutableStateOf<List<LiveEvent>>(emptyList()) }
    var isLoading  by remember { mutableStateOf(true) }

    // Server picker dialog
    var showServerDialog  by remember { mutableStateOf(false) }
    var selectedEvent     by remember { mutableStateOf<LiveEvent?>(null) }

    LaunchedEffect(Unit) {
        scope.launch {
            allEvents = try { EventRepository.getActiveEvents() } catch (_: Exception) { emptyList() }
            isLoading = false
        }
    }

    // Filter logic
    val now     = remember { System.currentTimeMillis() }
    val todayCal = Calendar.getInstance().apply { timeInMillis = now }
    val tomorrowCal = Calendar.getInstance().apply { timeInMillis = now; add(Calendar.DAY_OF_YEAR, 1) }

    fun isSameDay(epochMs: Long, cal: Calendar): Boolean {
        val c = Calendar.getInstance().apply { timeInMillis = epochMs }
        return c.get(Calendar.YEAR) == cal.get(Calendar.YEAR) &&
               c.get(Calendar.DAY_OF_YEAR) == cal.get(Calendar.DAY_OF_YEAR)
    }

    val filteredEvents = remember(allEvents, selectedFilter) {
        when (selectedFilter) {
            "Live Now"  -> allEvents.filter { it.isLive }
            "Today's"   -> allEvents.filter { ev ->
                val startMs = parseEventTime(ev.startTime)
                startMs != null && isSameDay(startMs, todayCal)
            }
            "Tomorrow"  -> allEvents.filter { ev ->
                val startMs = parseEventTime(ev.startTime)
                startMs != null && isSameDay(startMs, tomorrowCal)
            }
            "Upcoming"  -> allEvents.filter { ev ->
                val startMs = parseEventTime(ev.startTime) ?: return@filter false
                startMs > now + 24 * 3600_000L
            }
            else        -> allEvents
        }
    }

    // Group by sport (like HD Streamz groups by league)
    val grouped = remember(filteredEvents) {
        filteredEvents.groupBy { it.sport }
    }

    Column(Modifier.fillMaxSize()) {
        // ── Filter chips (scrollable) — HD Streamz style ──────────────────
        LazyRow(
            contentPadding       = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(filterChips) { chip ->
                val isSelected = chip == selectedFilter
                Surface(
                    shape  = RoundedCornerShape(20.dp),
                    color  = if (isSelected) primaryColor.copy(0.15f) else Color.Transparent,
                    border = BorderStroke(
                        1.dp,
                        if (isSelected) primaryColor else Color.White.copy(0.2f)
                    ),
                    modifier = Modifier
                        .height(34.dp)
                        .clickable { selectedFilter = chip }
                ) {
                    Row(
                        Modifier.padding(horizontal = 14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (isSelected) {
                            Icon(
                                Icons.Default.Check,
                                null,
                                tint     = primaryColor,
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(Modifier.width(4.dp))
                        }
                        Text(
                            chip.uppercase(),
                            color      = if (isSelected) primaryColor else Color.White,
                            fontSize   = 11.sp,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                            letterSpacing = 0.5.sp
                        )
                    }
                }
            }
        }

        HorizontalDivider(color = Color.White.copy(0.08f))

        // ── Events List ───────────────────────────────────────────────────
        if (isLoading) {
            EventsShimmer()
        } else if (filteredEvents.isEmpty()) {
            EventsEmptyState(primaryColor)
        } else {
            LazyColumn(
                contentPadding = PaddingValues(bottom = 100.dp),
                modifier       = Modifier.fillMaxSize()
            ) {
                grouped.forEach { (sport, events) ->
                    // Sport group header (like "Cricket / Indian Premier League")
                    item {
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp)
                        ) {
                            Text(
                                sport.uppercase(),
                                color      = primaryColor,
                                fontWeight = FontWeight.Bold,
                                fontSize   = 13.sp,
                                letterSpacing = 1.sp
                            )
                            Text(
                                sport,
                                color    = Color.Gray,
                                fontSize = 11.sp
                            )
                        }
                    }

                    // Horizontal row of match cards (HD Streamz style)
                    item {
                        LazyRow(
                            contentPadding        = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            items(events, key = { it.eventId }) { event ->
                                HDStreamzMatchCard(
                                    event        = event,
                                    primaryColor = primaryColor,
                                    onClick      = {
                                        selectedEvent    = event
                                        showServerDialog = true
                                    }
                                )
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                    }
                }
            }
        }
    }

    // ── Server Picker Dialog ──────────────────────────────────────────────
    if (showServerDialog && selectedEvent != null) {
        EventServerDialog(
            event     = selectedEvent!!,
            onDismiss = { showServerDialog = false },
            onStreamSelected = { stream ->
                showServerDialog = false
                try {
                    navController.navigate("player/${URLEncoder.encode(stream.url, "UTF-8")}")
                } catch (_: Exception) {
                    Toast.makeText(context, "Invalid stream", Toast.LENGTH_SHORT).show()
                }
            }
        )
    }
}

// ─── HD Streamz exact match card ─────────────────────────────────────────────
@Composable
fun HDStreamzMatchCard(
    event:        LiveEvent,
    primaryColor: Color,
    onClick:      () -> Unit
) {
    val accentColor = sportColorFromHex(event.sportColor)
    val cardBg      = Color(0xFF1A1A24)

    // Format time label — "LIVE" / "Today, HH:mm" / "Tomorrow, HH:mm" / "dd MMM, HH:mm"
    val timeLabel = remember(event.startTime, event.isLive) {
        if (event.isLive) {
            val ms = parseEventTime(event.startTime) ?: return@remember "LIVE"
            val elapsed = System.currentTimeMillis() - ms
            val mins    = (elapsed / 60_000).coerceAtLeast(0)
            "${mins}:${String.format("%02d", 0)} • LIVE"
        } else {
            parseEventTime(event.startTime)?.let { ms ->
                val now = System.currentTimeMillis()
                val cal = Calendar.getInstance().apply { timeInMillis = ms }
                val todayCal = Calendar.getInstance()
                val tomorrowCal = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, 1) }
                val hm = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(ms))

                fun sameDay(a: Calendar, b: Calendar) =
                    a.get(Calendar.YEAR) == b.get(Calendar.YEAR) &&
                    a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR)

                when {
                    sameDay(cal, todayCal)    -> "In ${((ms - now) / 60_000)} min, $hm"
                    sameDay(cal, tomorrowCal) -> "Tomorrow, $hm"
                    else -> "${SimpleDateFormat("dd MMM", Locale.getDefault()).format(Date(ms))}, $hm"
                }
            } ?: "--:--"
        }
    }

    Card(
        modifier  = Modifier
            .width(220.dp)
            .height(160.dp)
            .clickable(onClick = onClick),
        shape     = RoundedCornerShape(12.dp),
        colors    = CardDefaults.cardColors(containerColor = cardBg),
        elevation = CardDefaults.cardElevation(4.dp)
    ) {
        Box(Modifier.fillMaxSize()) {

            // Left accent bar
            Box(
                Modifier
                    .fillMaxHeight()
                    .width(4.dp)
                    .background(accentColor)
            )

            Column(
                Modifier
                    .fillMaxSize()
                    .padding(start = 12.dp, end = 10.dp, top = 8.dp, bottom = 8.dp)
            ) {
                // ── Time badge (top-left, like HD Streamz) ──────────────
                Row(
                    Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(if (event.isLive) Color(0xFFE53935).copy(0.15f) else Color.White.copy(0.08f))
                        .border(
                            1.dp,
                            if (event.isLive) Color(0xFFE53935).copy(0.5f) else Color.White.copy(0.15f),
                            RoundedCornerShape(4.dp)
                        )
                        .padding(horizontal = 8.dp, vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (event.isLive) {
                        EventBlinkDot()
                        Spacer(Modifier.width(5.dp))
                    }
                    Text(
                        timeLabel,
                        color      = if (event.isLive) Color(0xFFE53935) else Color.White,
                        fontSize   = 10.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                Spacer(Modifier.height(10.dp))

                // ── Team logos + VS (HD Streamz style split card) ────────
                val parts = event.title.split(" vs ", " VS ", " v ", ignoreCase = true)
                val team1 = parts.getOrElse(0) { event.title }
                val team2 = parts.getOrElse(1) { "" }

                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment     = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    // Team 1
                    Column(
                        Modifier.weight(1f),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Box(
                            Modifier
                                .size(52.dp)
                                .clip(CircleShape)
                                .background(accentColor.copy(0.12f))
                                .border(1.dp, accentColor.copy(0.3f), CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                team1.take(2).uppercase(),
                                color      = Color.White,
                                fontWeight = FontWeight.Black,
                                fontSize   = 16.sp
                            )
                        }
                        Spacer(Modifier.height(4.dp))
                        Text(
                            team1.take(12),
                            color     = Color.White,
                            fontSize  = 10.sp,
                            maxLines  = 1,
                            overflow  = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                        )
                    }

                    // VS divider
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("VS", color = Color.Gray, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        if (event.sport.isNotEmpty()) {
                            Spacer(Modifier.height(2.dp))
                            Text(
                                event.sport.take(8),
                                color    = accentColor,
                                fontSize = 8.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    // Team 2
                    if (team2.isNotEmpty()) {
                        Column(
                            Modifier.weight(1f),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Box(
                                Modifier
                                    .size(52.dp)
                                    .clip(CircleShape)
                                    .background(Color.White.copy(0.05f))
                                    .border(1.dp, Color.White.copy(0.15f), CircleShape),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    team2.take(2).uppercase(),
                                    color      = Color.White,
                                    fontWeight = FontWeight.Black,
                                    fontSize   = 16.sp
                                )
                            }
                            Spacer(Modifier.height(4.dp))
                            Text(
                                team2.take(12),
                                color    = Color.White,
                                fontSize = 10.sp,
                                maxLines = 1,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        }
    }
}

// ─── Events empty state ───────────────────────────────────────────────────────
@Composable
fun EventsEmptyState(primaryColor: Color) {
    Column(
        Modifier.fillMaxSize().padding(top = 80.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            Icons.Rounded.LiveTv, null,
            tint     = Color.DarkGray,
            modifier = Modifier.size(72.dp)
        )
        Spacer(Modifier.height(16.dp))
        Text("NO LIVE EVENTS RIGHT NOW", color = Color.Gray, fontSize = 14.sp, letterSpacing = 1.sp)
        Spacer(Modifier.height(8.dp))
        Text("Check back later for live sports", color = Color.DarkGray, fontSize = 12.sp)
    }
}

// ─── Events shimmer ───────────────────────────────────────────────────────────
@Composable
fun EventsShimmer() {
    Column(Modifier.padding(16.dp)) {
        repeat(3) {
            Box(Modifier.width(120.dp).height(14.dp).clip(RoundedCornerShape(4.dp)).shimmer().background(Color.DarkGray))
            Spacer(Modifier.height(8.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                items(3) {
                    Box(Modifier.width(220.dp).height(160.dp).clip(RoundedCornerShape(12.dp)).shimmer().background(Color.DarkGray))
                }
            }
            Spacer(Modifier.height(20.dp))
        }
    }
}

// ─── Blinking dot ─────────────────────────────────────────────────────────────
@Composable
fun EventBlinkDot() {
    val inf = rememberInfiniteTransition(label = "dot")
    val alpha by inf.animateFloat(
        initialValue = 1f, targetValue = 0.2f,
        animationSpec = infiniteRepeatable(tween(600, easing = LinearEasing), RepeatMode.Reverse),
        label = "blink"
    )
    Box(Modifier.size(7.dp).background(Color.Red.copy(alpha), CircleShape))
}

// ─── Server picker dialog ─────────────────────────────────────────────────────
@Composable
fun EventServerDialog(
    event:           LiveEvent,
    onDismiss:       () -> Unit,
    onStreamSelected: (EventStream) -> Unit
) {
    val accent = sportColorFromHex(event.sportColor)

    Dialog(onDismissRequest = onDismiss) {
        Card(
            shape  = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF121218)),
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, accent.copy(0.4f), RoundedCornerShape(20.dp))
        ) {
            Column(Modifier.padding(20.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (event.isLive) { EventBlinkDot(); Spacer(Modifier.width(8.dp)) }
                    Text(event.sport.uppercase(), color = accent, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                }
                Spacer(Modifier.height(4.dp))
                Text(event.title, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 2)
                Spacer(Modifier.height(4.dp))
                Text("SELECT SERVER TO WATCH", color = Color.Gray, fontSize = 10.sp, letterSpacing = 1.sp)
                HorizontalDivider(color = Color.White.copy(0.1f), modifier = Modifier.padding(vertical = 12.dp))

                event.streams.forEachIndexed { index, stream ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(Color(0xFF1E1E2A))
                            .border(1.dp, Color.White.copy(0.08f), RoundedCornerShape(10.dp))
                            .clickable { onStreamSelected(stream) }
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            Modifier.size(32.dp).background(accent.copy(0.15f), CircleShape).border(1.dp, accent.copy(0.5f), CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Default.PlayArrow, null, tint = accent, modifier = Modifier.size(20.dp))
                        }
                        Spacer(Modifier.width(14.dp))
                        Column {
                            Text(stream.name, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                            Text("Tap to play", color = Color.Gray, fontSize = 11.sp)
                        }
                        Spacer(Modifier.weight(1f))
                        if (index == 0) {
                            Text(
                                "BEST", color = accent, fontSize = 9.sp, fontWeight = FontWeight.Bold,
                                modifier = Modifier.clip(RoundedCornerShape(4.dp)).background(accent.copy(0.15f)).padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }
                    if (index < event.streams.lastIndex) Spacer(Modifier.height(8.dp))
                }
                Spacer(Modifier.height(12.dp))
                TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) {
                    Text("CANCEL", color = Color.Gray, fontSize = 12.sp)
                }
            }
        }
    }
}

// ─── Time parser ──────────────────────────────────────────────────────────────
fun parseEventTime(iso: String): Long? = try {
    SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
        .apply { timeZone = TimeZone.getTimeZone("UTC") }
        .parse(iso.trim())?.time
} catch (_: Exception) { null }

// ════════════════════════════════════════════════════════════════════════════
//  TAB 1 — LIVE TV
//  ─────────────────
//  "All" mode  →  HD Streamz-style sections: Category header + horizontal row
//  Single cat  →  3-column grid (original HolographicChannelCard)
//  "Favorites" →  same grid, filtered to favorited channels
//
//  index.json delivers these categories in order:
//    Bangladesh | Sports | USA | Informative | India | UK | Kids | Music | UAE
//  Each gets its own icon / accent colour from getCategoryMeta().
// ════════════════════════════════════════════════════════════════════════════
@Composable
fun LiveTVTabContent(
    navController:    NavController,
    allChannels:      List<Channel>,
    categories:       List<String>,
    categoryCounts:   Map<String, Int>,
    favoriteIds:      Set<String>,
    isLoading:        Boolean,
    primaryColor:     Color,
    onChannelClick:   (Channel) -> Unit,
    onFavoriteToggle: (Channel) -> Unit
) {
    // selectedCategory is local to this tab — resets when you switch back to Events
    var selectedCategory by remember { mutableStateOf("All") }

    // Derived filtered list
    val filteredChannels = remember(allChannels, selectedCategory, favoriteIds) {
        allChannels.filter { ch ->
            when (selectedCategory) {
                "All"       -> true
                "Favorites" -> ch.id in favoriteIds
                else        -> ch.category == selectedCategory
            }
        }
    }

    // In "All" mode we group by category, preserving index.json order
    // (categories list already has the correct server order: All, Favorites, Bangladesh, Sports…)
    val orderedCategoryNames = remember(categories) {
        categories.filter { it != "All" && it != "Favorites" }
    }

    Column(Modifier.fillMaxSize()) {

        // ── Category chip row ─────────────────────────────────────────────
        ModernCategorySelector(
            categories = categories,
            selected   = selectedCategory,
            counts     = categoryCounts,
            onSelect   = { selectedCategory = it }
        )

        // ── Body ──────────────────────────────────────────────────────────
        if (isLoading) {
            Box(Modifier.fillMaxSize()) { LoadingShimmerEffect() }
            return@Column
        }

        LazyColumn(
            contentPadding = PaddingValues(bottom = 100.dp),
            modifier       = Modifier.fillMaxSize()
        ) {

            // ── Featured carousel (only in "All" mode) ────────────────────
            val featured = allChannels.filter { it.isFeatured }
            if (featured.isNotEmpty() && selectedCategory == "All") {
                item {
                    Spacer(Modifier.height(8.dp))
                    HeroCarousel(featured, navController) { onChannelClick(it) }
                    Spacer(Modifier.height(16.dp))
                }
            }

            // ── ALL mode: category sections (HD Streamz "Explore Categories" + country rows) ─
            if (selectedCategory == "All") {

                // "Explore Categories" quick-access pills (like HD Streamz top of Live TV)
                item {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                        Text(
                            "Explore Categories",
                            color      = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize   = 15.sp,
                            modifier   = Modifier.padding(bottom = 10.dp)
                        )
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            items(orderedCategoryNames) { cat ->
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    modifier = Modifier
                                        .clickable(
                                            interactionSource = remember { MutableInteractionSource() },
                                            indication        = null
                                        ) { selectedCategory = cat }
                                ) {
                                    FuturisticCategoryIcon(category = cat, size = 64.dp)
                                    Spacer(Modifier.height(7.dp))
                                    Text(
                                        cat,
                                        color      = Color.White,
                                        fontSize   = 10.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines   = 1
                                    )
                                }
                            }
                        }
                    }
                    HorizontalDivider(
                        color    = Color.White.copy(0.07f),
                        modifier = Modifier.padding(top = 14.dp)
                    )
                }

                // One section per category (in index.json order)
                orderedCategoryNames.forEach { catName ->
                    val chans = allChannels.filter { it.category == catName }
                    if (chans.isEmpty()) return@forEach
                    val meta  = getCategoryMeta(catName)

                    item(key = "header_$catName") {
                        // Section header
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 6.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment     = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                // Coloured left bar
                                Box(
                                    Modifier
                                        .width(3.dp)
                                        .height(18.dp)
                                        .background(meta.color, RoundedCornerShape(2.dp))
                                )
                                Spacer(Modifier.width(8.dp))
                                FuturisticCategoryIcon(category = catName, size = 36.dp)
                                Spacer(Modifier.width(8.dp))
                                Column {
                                    Text(
                                        catName,
                                        color      = Color.White,
                                        fontWeight = FontWeight.Bold,
                                        fontSize   = 14.sp
                                    )
                                    Text(
                                        "${chans.size} channels",
                                        color    = Color.Gray,
                                        fontSize = 10.sp
                                    )
                                }
                            }
                            // "See All ›" — switches category chip
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(meta.color.copy(0.12f))
                                    .border(1.dp, meta.color.copy(0.3f), RoundedCornerShape(6.dp))
                                    .clickable { selectedCategory = catName }
                                    .padding(horizontal = 10.dp, vertical = 4.dp)
                            ) {
                                Text(
                                    "See All  ›",
                                    color      = meta.color,
                                    fontSize   = 11.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }
                    }

                    item(key = "row_$catName") {
                        // Horizontal channel preview row
                        LazyRow(
                            contentPadding        = PaddingValues(horizontal = 12.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            items(chans.take(10), key = { it.id }) { ch ->
                                HDStreamzChannelCard(
                                    channel          = ch,
                                    isFavorite       = ch.id in favoriteIds,
                                    accentColor      = meta.color,
                                    onClick          = { onChannelClick(ch) },
                                    onFavoriteToggle = { onFavoriteToggle(ch) }
                                )
                            }
                        }
                        HorizontalDivider(
                            color    = Color.White.copy(0.06f),
                            modifier = Modifier.padding(top = 12.dp)
                        )
                    }
                }

            } else {
                // ── Single category / Favorites — 3-column grid ───────────
                if (filteredChannels.isEmpty()) {
                    item { EmptyState(isFavorites = selectedCategory == "Favorites") }
                } else {
                    items(
                        items = filteredChannels.chunked(3),
                        key   = { row -> row.first().id }
                    ) { row ->
                        Row(
                            Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                            Arrangement.spacedBy(12.dp)
                        ) {
                            row.forEach { ch ->
                                HolographicChannelCard(
                                    channel          = ch,
                                    isFavorite       = ch.id in favoriteIds,
                                    modifier         = Modifier.weight(1f),
                                    onFavoriteToggle = { onFavoriteToggle(ch) },
                                    onClick          = { onChannelClick(ch) }
                                )
                            }
                            repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                        }
                    }
                }
            }
        }
    }
}

// ─── HD Streamz horizontal channel card ───────────────────────────────────────
@Composable
fun HDStreamzChannelCard(
    channel:         Channel,
    isFavorite:      Boolean,
    accentColor:     Color,
    onClick:         () -> Unit,
    onFavoriteToggle: () -> Unit
) {
    Box(
        Modifier
            .width(140.dp)
            .height(148.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0xFF14141E))
            .border(
                width = 1.dp,
                brush = Brush.verticalGradient(
                    listOf(accentColor.copy(.4f), accentColor.copy(.08f))
                ),
                shape = RoundedCornerShape(16.dp)
            )
            .clickable(onClick = onClick)
    ) {
        // ── Logo — centred, Fit, occupies top 70% ─────────────────
        Box(
            Modifier
                .fillMaxWidth()
                .height(105.dp)
                .align(Alignment.TopCenter)
                .padding(horizontal = 14.dp, vertical = 12.dp),
            contentAlignment = Alignment.Center
        ) {
            if (channel.logoUrl.isNotEmpty()) {
                AsyncImage(
                    model              = channel.logoUrl,
                    contentDescription = channel.name,
                    contentScale       = ContentScale.Fit,
                    modifier           = Modifier.fillMaxSize(),
                    // placeholder shown while loading
                    placeholder        = coil.compose.rememberAsyncImagePainter(null),
                    error              = coil.compose.rememberAsyncImagePainter(null)
                )
            } else {
                // Fallback: first letter in accent circle
                Box(
                    Modifier
                        .size(52.dp)
                        .background(accentColor.copy(.2f), CircleShape)
                        .border(1.5.dp, accentColor.copy(.4f), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        channel.name.take(1).uppercase(),
                        color      = accentColor,
                        fontSize   = 20.sp,
                        fontWeight = FontWeight.ExtraBold
                    )
                }
            }
        }

        // ── Bottom gradient + name ────────────────────────────────
        Box(
            Modifier
                .fillMaxWidth()
                .height(55.dp)
                .align(Alignment.BottomCenter)
                .background(
                    Brush.verticalGradient(
                        listOf(Color.Transparent, Color(0xFF0D0D16))
                    )
                )
        )
        Text(
            text       = channel.name,
            color      = Color.White,
            fontSize   = 10.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines   = 2,
            overflow   = TextOverflow.Ellipsis,
            textAlign  = androidx.compose.ui.text.style.TextAlign.Center,
            lineHeight  = 13.sp,
            modifier   = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(bottom = 8.dp, start = 6.dp, end = 6.dp)
        )

        // ── Favourite icon ────────────────────────────────────────
        Box(
            Modifier
                .align(Alignment.TopEnd)
                .padding(6.dp)
                .size(22.dp)
                .background(Color.Black.copy(.5f), CircleShape)
                .clickable(onClick = onFavoriteToggle),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = if (isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                contentDescription = null,
                tint     = if (isFavorite) accentColor else Color.White.copy(.5f),
                modifier = Modifier.size(13.dp)
            )
        }
    }
}

// ════════════════════════════════════════════════════════════════════════════
//  EXISTING COMPONENTS (unchanged from original)
// ════════════════════════════════════════════════════════════════════════════

@Composable
fun CyberMeshBackground() {
    val inf = rememberInfiniteTransition(label = "bg")
    val off by inf.animateFloat(
        initialValue = 0f, targetValue = 1000f,
        animationSpec = infiniteRepeatable(tween(20000, easing = LinearEasing), RepeatMode.Reverse),
        label = "off"
    )
    val bgColor   = MaterialTheme.colorScheme.background
    val primary   = MaterialTheme.colorScheme.primary
    val secondary = MaterialTheme.colorScheme.secondary

    Canvas(Modifier.fillMaxSize().background(bgColor)) {
        drawRect(Brush.radialGradient(listOf(Color(0xFF0F0F15), bgColor), center, size.maxDimension))
        drawCircle(Brush.radialGradient(listOf(secondary.copy(0.15f), Color.Transparent)), size.minDimension * 0.6f, Offset(size.width * 0.8f, size.height * 0.2f + off))
        drawCircle(Brush.radialGradient(listOf(primary.copy(0.1f), Color.Transparent)), size.minDimension * 0.7f, Offset(size.width * 0.2f, size.height * 0.8f - off))
    }
}

@Composable
fun SearchOverlay(
    query:          String,
    channels:       List<Channel>,
    primaryColor:   Color,
    onQueryChange:  (String) -> Unit,
    onDismiss:      () -> Unit,
    onChannelClick: (Channel) -> Unit
) {
    val filtered = remember(query, channels) {
        if (query.isBlank()) channels.take(30)
        else channels.filter { it.name.contains(query, ignoreCase = true) }
    }
    val secondaryColor = MaterialTheme.colorScheme.secondary

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(0.97f))
            .zIndex(3f)
            .windowInsetsPadding(WindowInsets.statusBars)
    ) {
        SearchBar(
            query          = query,
            onQueryChange  = onQueryChange,
            onSearch       = { onDismiss() },
            active         = true,
            onActiveChange = { if (!it) onDismiss() },
            placeholder    = { Text("Search channels...", color = Color.Gray) },
            leadingIcon    = { Icon(Icons.Default.Search, null, tint = primaryColor) },
            trailingIcon   = {
                IconButton(onClick = { if (query.isNotEmpty()) onQueryChange("") else onDismiss() }) {
                    Icon(Icons.Default.Close, null, tint = Color.White)
                }
            },
            colors = SearchBarDefaults.colors(
                containerColor   = Color.Black,
                dividerColor     = secondaryColor,
                inputFieldColors = TextFieldDefaults.colors(focusedTextColor = Color.White)
            ),
            modifier = Modifier.fillMaxSize()
        ) {
            LazyColumn(contentPadding = PaddingValues(16.dp)) {
                items(filtered) { ch ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { onChannelClick(ch) }
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        AsyncImage(
                            model = ch.logoUrl, contentDescription = null,
                            modifier = Modifier.size(48.dp).clip(RoundedCornerShape(10.dp)).border(1.dp, primaryColor.copy(0.4f), RoundedCornerShape(10.dp)),
                            contentScale = ContentScale.Fit
                        )
                        Spacer(Modifier.width(14.dp))
                        Column {
                            Text(ch.name, color = Color.White, fontWeight = FontWeight.Bold)
                            Text(ch.category, color = Color.Gray, fontSize = 11.sp)
                        }
                    }
                    HorizontalDivider(color = Color.White.copy(0.07f))
                }
            }
        }
    }
}

@Composable
fun HolographicChannelCard(channel: Channel, isFavorite: Boolean, modifier: Modifier, onFavoriteToggle: () -> Unit, onClick: () -> Unit) {
    val primaryColor      = MaterialTheme.colorScheme.primary
    val interactionSource = remember { MutableInteractionSource() }
    Card(
        modifier  = modifier.aspectRatio(0.85f).clickable(interactionSource, null, onClick = onClick),
        shape     = RoundedCornerShape(16.dp),
        colors    = CardDefaults.cardColors(containerColor = Color.Transparent),
        elevation = CardDefaults.cardElevation(0.dp)
    ) {
        val accentColor = getCategoryMeta(channel.category).color
        Box(
            Modifier.fillMaxSize().background(Color(0xFF12121C))
                .border(1.dp, Brush.verticalGradient(listOf(accentColor.copy(0.35f), accentColor.copy(0.06f))), RoundedCornerShape(16.dp))
                .clip(RoundedCornerShape(16.dp))
        ) {
            Box(Modifier.fillMaxSize().padding(start = 14.dp, end = 14.dp, top = 14.dp, bottom = 38.dp),
                contentAlignment = Alignment.Center) {
                if (channel.logoUrl.isNotEmpty()) {
                    AsyncImage(model = channel.logoUrl, contentDescription = null,
                        modifier = Modifier.fillMaxWidth().aspectRatio(1f), contentScale = ContentScale.Fit)
                } else {
                    Box(Modifier.size(44.dp).background(accentColor.copy(.2f), CircleShape)
                        .border(1.dp, accentColor.copy(.4f), CircleShape),
                        contentAlignment = Alignment.Center) {
                        Text(channel.name.take(1).uppercase(), color = accentColor,
                            fontSize = 18.sp, fontWeight = FontWeight.ExtraBold)
                    }
                }
            }
            Box(Modifier.fillMaxWidth().align(Alignment.BottomCenter).height(50.dp)
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(0.92f)))))
            Text(channel.name.uppercase(), color = Color.White, fontSize = 10.sp,
                fontWeight = FontWeight.Bold, maxLines = 2, lineHeight = 13.sp,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                    .padding(bottom = 7.dp, start = 4.dp, end = 4.dp))
            Box(Modifier.align(Alignment.TopEnd).padding(5.dp).size(22.dp)
                    .background(Color.Black.copy(.5f), CircleShape)
                    .clickable(onClick = onFavoriteToggle),
                contentAlignment = Alignment.Center) {
                Icon(if (isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder, null,
                    tint = if (isFavorite) primaryColor else Color.White.copy(0.5f),
                    modifier = Modifier.size(13.dp))
            }
        }
    }
}

// ════════════════════════════════════════════════════════════════════════════
//  MODERN CATEGORY SELECTOR
//  HD Streamz style: circular icon pill with emoji + name + count badge.
//  Active chip shows accent colour ring + filled bg.
//  Order preserved exactly as index.json delivers it.
// ════════════════════════════════════════════════════════════════════════════
@Composable
fun ModernCategorySelector(
    categories: List<String>,
    selected:   String,
    counts:     Map<String, Int>,
    onSelect:   (String) -> Unit
) {
    Column(Modifier.fillMaxWidth()) {
        LazyRow(
            contentPadding        = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items(categories) { cat ->
                val meta    = getCategoryMeta(cat)
                val isSel   = cat == selected
                val count   = counts[cat] ?: 0
                val accent  = meta.color

                // HD Streamz uses pill-shaped chips with icon circle + label
                Surface(
                    shape  = RoundedCornerShape(50.dp),         // full pill
                    color  = if (isSel) accent.copy(0.18f) else Color(0xFF1E1E28),
                    border = BorderStroke(
                        width = if (isSel) 1.5.dp else 1.dp,
                        color = if (isSel) accent else Color.White.copy(0.12f)
                    ),
                    modifier = Modifier
                        .height(42.dp)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication        = null
                        ) { onSelect(cat) }
                        .animateContentSize()
                ) {
                    Row(
                        modifier = Modifier.padding(start = 6.dp, end = 14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Icon circle — small futuristic icon
                        FuturisticCategoryIcon(category = cat, size = 30.dp)
                        Spacer(Modifier.width(8.dp))
                        // Label
                        Text(
                            text       = cat,
                            color      = if (isSel) accent else Color.White,
                            fontWeight = if (isSel) FontWeight.Bold else FontWeight.Medium,
                            fontSize   = 13.sp
                        )
                        // Count badge (hidden when 0)
                        if (count > 0) {
                            Spacer(Modifier.width(5.dp))
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(50.dp))
                                    .background(if (isSel) accent.copy(0.3f) else Color.White.copy(0.1f))
                                    .padding(horizontal = 6.dp, vertical = 1.dp)
                            ) {
                                Text(
                                    text     = count.toString(),
                                    color    = if (isSel) accent else Color.Gray,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }
            }
        }
        HorizontalDivider(color = Color.White.copy(0.08f))
    }
}

// ════════════════════════════════════════════════════════════════════════════
//  FUTURISTIC CATEGORY ICON
//  Canvas-drawn, animated 3-D style icons for each category.
//  Replaces flat emoji — every category gets a unique design.
// ════════════════════════════════════════════════════════════════════════════
@Composable
fun FuturisticCategoryIcon(category: String, size: androidx.compose.ui.unit.Dp = 64.dp) {
    val meta = getCategoryMeta(category)
    val col  = meta.color

    val inf = rememberInfiniteTransition(label = "fi")
    val rot by inf.animateFloat(0f, 360f,
        infiniteRepeatable(tween(9000, easing = LinearEasing)), "r")
    val pulse by inf.animateFloat(.55f, 1f,
        infiniteRepeatable(tween(1800, easing = EaseInOutSine), RepeatMode.Reverse), "p")
    val glow by inf.animateFloat(.2f, .65f,
        infiniteRepeatable(tween(2200, easing = EaseInOutSine), RepeatMode.Reverse), "g")

    Box(
        Modifier
            .size(size)
            .background(
                Brush.radialGradient(listOf(col.copy(.28f), Color(0xFF08080F))),
                CircleShape
            )
            .border(
                width = 1.5.dp,
                brush = Brush.sweepGradient(
                    listOf(col.copy(pulse), col.copy(.08f), col.copy(pulse))
                ),
                shape = CircleShape
            ),
        contentAlignment = Alignment.Center
    ) {
        val iconSize = size * .62f
        Canvas(Modifier.size(iconSize)) {
            val w = size.toPx()
            val cx = size.value / 2f
            val cy = size.value / 2f

            when {
                // ── ALL — 3×3 glowing grid ─────────────────────────
                category.equals("All", true) -> {
                    val gap = 7.5f
                    val dotR = 3.5f
                    for (row in 0..2) for (col2 in 0..2) {
                        val x = (col2 - 1) * gap
                        val y = (row - 1) * gap
                        val alphaDot = if ((row + col2) % 2 == 0) pulse else (.4f + pulse * .3f)
                        drawCircle(col.copy(glow * .5f), dotR * 2.2f, Offset(cx + x, cy + y))
                        drawCircle(col.copy(alphaDot), dotR, Offset(cx + x, cy + y))
                    }
                }

                // ── FAVORITES — pulsing heart ──────────────────────
                category.equals("Favorites", true) -> {
                    val s = 11f
                    val path = androidx.compose.ui.graphics.Path().apply {
                        moveTo(cx, cy + s * .6f)
                        cubicTo(cx - s * 1.8f, cy - s * .5f, cx - s * 2f, cy - s * 1.8f, cx, cy - s * .6f)
                        cubicTo(cx + s * 2f, cy - s * 1.8f, cx + s * 1.8f, cy - s * .5f, cx, cy + s * .6f)
                    }
                    drawPath(path, col.copy(glow * .4f), style = Stroke(width = 8f))
                    drawPath(path, col.copy(pulse))
                }

                // ── SPORTS — spinning soccer hexagon ring ──────────
                category.contains("Sport", true) -> {
                    val r = 12f
                    val spokeR = 10f
                    // Outer spinning ring
                    drawCircle(col.copy(.15f), r + 4f)
                    drawCircle(col.copy(glow * .4f), r + 4f, style = Stroke(1.5f))
                    // 6 hexagon dots rotating
                    for (i in 0..5) {
                        val angle = Math.toRadians((rot + i * 60.0)).toFloat()
                        val x = cx + spokeR * kotlin.math.cos(angle)
                        val y = cy + spokeR * kotlin.math.sin(angle)
                        drawCircle(col.copy(pulse), 3f, Offset(x, y))
                        drawCircle(col.copy(glow * .5f), 5f, Offset(x, y))
                    }
                    // Center circle
                    drawCircle(col.copy(.3f), 4f)
                    drawCircle(col, 2.5f)
                }

                // ── MUSIC — animated wave bars ─────────────────────
                category.contains("Music", true) -> {
                    val bars = 5
                    val barW = 4f
                    val gap2 = 3f
                    val totalW = bars * barW + (bars - 1) * gap2
                    val startX = cx - totalW / 2f
                    for (i in 0..bars - 1) {
                        val ph = Math.toRadians((rot * 3 + i * 55.0)).toFloat()
                        val h = 6f + 10f * ((kotlin.math.sin(ph) + 1f) / 2f)
                        val x = startX + i * (barW + gap2)
                        drawRoundRect(
                            col.copy(pulse),
                            topLeft = Offset(x, cy - h),
                            size    = androidx.compose.ui.geometry.Size(barW, h * 2f),
                            cornerRadius = androidx.compose.ui.geometry.CornerRadius(barW / 2f)
                        )
                    }
                }

                // ── KIDS — spinning star ───────────────────────────
                category.contains("Kid", true) -> {
                    val outerR = 13f; val innerR = 6f; val pts = 5
                    val path2 = androidx.compose.ui.graphics.Path()
                    for (i in 0 until pts * 2) {
                        val r2 = if (i % 2 == 0) outerR else innerR
                        val angle = Math.toRadians((rot + i * 180.0 / pts - 90.0)).toFloat()
                        val x = cx + r2 * kotlin.math.cos(angle)
                        val y = cy + r2 * kotlin.math.sin(angle)
                        if (i == 0) path2.moveTo(x, y) else path2.lineTo(x, y)
                    }
                    path2.close()
                    drawPath(path2, col.copy(glow * .4f), style = Stroke(6f))
                    drawPath(path2, col.copy(pulse))
                }

                // ── NEWS / INFORMATIVE — radiating arc signal ──────
                category.contains("News", true) ||
                category.contains("Info", true) ||
                category.contains("Document", true) -> {
                    for (i in 1..3) {
                        val r2 = i * 5f
                        val alpha = (pulse * .7f) * ((4 - i) / 3f)
                        drawArc(col.copy(alpha), -120f, 60f, false,
                            Offset(cx - r2, cy - r2),
                            androidx.compose.ui.geometry.Size(r2 * 2, r2 * 2),
                            style = Stroke(2f))
                        drawArc(col.copy(alpha), 0f, 60f, false,
                            Offset(cx - r2, cy - r2),
                            androidx.compose.ui.geometry.Size(r2 * 2, r2 * 2),
                            style = Stroke(2f))
                    }
                    drawCircle(col.copy(pulse), 3.5f)
                }

                // ── MOVIES / ENTERTAINMENT — clapperboard ──────────
                category.contains("Movie", true) ||
                category.contains("Entertain", true) -> {
                    // Film reel ring
                    drawCircle(col.copy(.2f), 13f)
                    drawCircle(col.copy(glow * .4f), 13f, style = Stroke(1.5f))
                    for (i in 0..5) {
                        val angle = Math.toRadians((rot + i * 60.0)).toFloat()
                        val x = cx + 9f * kotlin.math.cos(angle)
                        val y = cy + 9f * kotlin.math.sin(angle)
                        drawCircle(col.copy(pulse), 2.5f, Offset(x, y))
                    }
                    drawCircle(col.copy(.5f), 4.5f)
                    drawCircle(Color(0xFF0D0D16), 3f)
                }

                // ── BANGLADESH — green circle + red sun ───────────
                category.equals("Bangladesh", true) -> {
                    // Green background disc
                    drawCircle(Color(0xFF006A4E).copy(pulse * .9f), 14f)
                    drawCircle(Color(0xFF006A4E), 14f, style = Stroke(1.5f))
                    // Red circle offset left (Bangladesh flag)
                    drawCircle(Color(0xFFF42A41).copy(glow + .2f), 8f, Offset(cx - 1f, cy))
                    drawCircle(Color(0xFFF42A41).copy(pulse), 7f, Offset(cx - 1f, cy))
                    // Inner glow
                    drawCircle(Color(0xFFF42A41).copy(.3f), 11f, Offset(cx - 1f, cy))
                }

                // ── INDIA — tricolor + rotating wheel ─────────────
                category.equals("India", true) -> {
                    val h = 9f
                    drawRect(Color(0xFFFF9933).copy(pulse), Offset(cx - 13f, cy - h), androidx.compose.ui.geometry.Size(26f, h))
                    drawRect(Color.White.copy(.9f), Offset(cx - 13f, cy - h / 3f), androidx.compose.ui.geometry.Size(26f, h * 0.67f))
                    drawRect(Color(0xFF138808).copy(pulse), Offset(cx - 13f, cy), androidx.compose.ui.geometry.Size(26f, h))
                    // Ashoka wheel
                    drawCircle(Color(0xFF000080).copy(glow * .7f + .2f), 4f)
                    for (i in 0..11) {
                        val angle = Math.toRadians((rot + i * 30.0)).toFloat()
                        val x1 = cx + 1.5f * kotlin.math.cos(angle)
                        val y1 = cy + 1.5f * kotlin.math.sin(angle)
                        val x2 = cx + 4f * kotlin.math.cos(angle)
                        val y2 = cy + 4f * kotlin.math.sin(angle)
                        drawLine(Color(0xFF000080).copy(pulse), Offset(x1, y1), Offset(x2, y2), 1f)
                    }
                }

                // ── USA — stars + stripes ──────────────────────────
                category.equals("USA", true) -> {
                    // Red/white stripes
                    val stripeH = 26f / 7f
                    for (i in 0..6) {
                        val stripe = if (i % 2 == 0) Color(0xFFBF0A30) else Color.White
                        drawRect(stripe.copy(pulse * .9f),
                            Offset(cx - 13f, cy - 13f + i * stripeH),
                            androidx.compose.ui.geometry.Size(26f, stripeH))
                    }
                    // Blue canton
                    drawRect(Color(0xFF002868).copy(pulse),
                        Offset(cx - 13f, cy - 13f),
                        androidx.compose.ui.geometry.Size(12f, 13f))
                    // Stars
                    for (i in 0..4) {
                        val angle = Math.toRadians((rot * 0.5 + i * 72.0)).toFloat()
                        val x = (cx - 7f) + 4f * kotlin.math.cos(angle)
                        val y = (cy - 6.5f) + 4f * kotlin.math.sin(angle)
                        drawCircle(Color.White.copy(pulse), 1.3f, Offset(x, y))
                    }
                }

                // ── UK — Union Jack cross ──────────────────────────
                category.equals("UK", true) -> {
                    drawRect(Color(0xFF012169).copy(pulse), Offset(cx - 13f, cy - 13f), androidx.compose.ui.geometry.Size(26f, 26f))
                    // White X diagonals
                    drawLine(Color.White.copy(.8f), Offset(cx - 13f, cy - 13f), Offset(cx + 13f, cy + 13f), 4f)
                    drawLine(Color.White.copy(.8f), Offset(cx + 13f, cy - 13f), Offset(cx - 13f, cy + 13f), 4f)
                    // Red X diagonals (narrower)
                    drawLine(Color(0xFFC8102E).copy(pulse), Offset(cx - 13f, cy - 13f), Offset(cx + 13f, cy + 13f), 2f)
                    drawLine(Color(0xFFC8102E).copy(pulse), Offset(cx + 13f, cy - 13f), Offset(cx - 13f, cy + 13f), 2f)
                    // White cross
                    drawLine(Color.White, Offset(cx, cy - 13f), Offset(cx, cy + 13f), 6f)
                    drawLine(Color.White, Offset(cx - 13f, cy), Offset(cx + 13f, cy), 6f)
                    // Red cross
                    drawLine(Color(0xFFC8102E).copy(pulse), Offset(cx, cy - 13f), Offset(cx, cy + 13f), 3.5f)
                    drawLine(Color(0xFFC8102E).copy(pulse), Offset(cx - 13f, cy), Offset(cx + 13f, cy), 3.5f)
                }

                // ── GENERIC FLAG countries — rotating ring + country accent ─
                category.contains("UAE", true) ||
                category.contains("Pakistan", true) ||
                category.contains("Saudi", true) ||
                category.contains("Arabic", true) -> {
                    drawCircle(col.copy(.25f), 12f)
                    for (i in 0..7) {
                        val angle = Math.toRadians((rot + i * 45.0)).toFloat()
                        val x = cx + 10f * kotlin.math.cos(angle)
                        val y = cy + 10f * kotlin.math.sin(angle)
                        drawCircle(col.copy(if (i % 2 == 0) pulse else glow), 2.5f, Offset(x, y))
                    }
                    drawCircle(col.copy(pulse), 4f)
                }

                // ── DEFAULT — orbiting dots ────────────────────────
                else -> {
                    drawCircle(col.copy(.2f), 13f)
                    for (i in 0..2) {
                        val angle = Math.toRadians((rot + i * 120.0)).toFloat()
                        val x = cx + 9f * kotlin.math.cos(angle)
                        val y = cy + 9f * kotlin.math.sin(angle)
                        drawCircle(col.copy(glow * .6f), 5f, Offset(x, y))
                        drawCircle(col.copy(pulse), 3f, Offset(x, y))
                    }
                    drawCircle(col.copy(pulse), 4f)
                }
            }
        }
    }
}

// ════════════════════════════════════════════════════════════════════════════
//  CATEGORY METADATA
//  Maps every category name from index.json to an icon + emoji flag/symbol.
//  index.json categories: Bangladesh, Sports, USA, Informative, India,
//                         UK, Kids, Music, UAE, Favorites, All
// ════════════════════════════════════════════════════════════════════════════

data class CategoryMeta(
    val icon:  ImageVector,
    val emoji: String,      // shown on chip next to icon
    val color: Color        // accent colour for section header
)

fun getCategoryMeta(category: String): CategoryMeta = when {
    // ── Special ───────────────────────────────────────────────────────────
    category.equals("All", true)       -> CategoryMeta(Icons.Rounded.Apps,       "🌐", Color(0xFF7C4DFF))
    category.equals("Favorites", true) -> CategoryMeta(Icons.Rounded.Favorite,   "❤️", Color(0xFFE91E63))

    // ── Content types ─────────────────────────────────────────────────────
    category.contains("Sport", true)   -> CategoryMeta(Icons.Rounded.SportsSoccer,"⚽", Color(0xFF00C853))
    category.contains("Music", true)   -> CategoryMeta(Icons.Rounded.MusicNote,   "🎵", Color(0xFFFF6D00))
    category.contains("Kid", true)     -> CategoryMeta(Icons.Rounded.ChildCare,   "🧸", Color(0xFFFFD600))
    category.contains("Info", true) ||
    category.contains("Document", true)-> CategoryMeta(Icons.Rounded.Info,        "📰", Color(0xFF00B0FF))
    category.contains("News", true)    -> CategoryMeta(Icons.Rounded.Newspaper,   "📡", Color(0xFFFF1744))
    category.contains("Movie", true)   -> CategoryMeta(Icons.Rounded.Movie,       "🎬", Color(0xFFAA00FF))
    category.contains("Entertain", true)-> CategoryMeta(Icons.Rounded.Tv,         "🎭", Color(0xFFFF6F00))

    // ── Countries ─────────────────────────────────────────────────────────
    category.equals("Bangladesh", true) -> CategoryMeta(Icons.Rounded.Flag,       "🇧🇩", Color(0xFF00C853))
    category.equals("India", true)      -> CategoryMeta(Icons.Rounded.Flag,       "🇮🇳", Color(0xFFFF6F00))
    category.equals("USA", true)        -> CategoryMeta(Icons.Rounded.Flag,       "🇺🇸", Color(0xFF2962FF))
    category.equals("UK", true)         -> CategoryMeta(Icons.Rounded.Flag,       "🇬🇧", Color(0xFFD50000))
    category.equals("UAE", true)        -> CategoryMeta(Icons.Rounded.Flag,       "🇦🇪", Color(0xFF00BFA5))
    category.equals("Pakistan", true)   -> CategoryMeta(Icons.Rounded.Flag,       "🇵🇰", Color(0xFF1B5E20))
    category.equals("Saudi", true) ||
    category.contains("Arabic", true)   -> CategoryMeta(Icons.Rounded.Flag,       "🇸🇦", Color(0xFF558B2F))

    else -> CategoryMeta(Icons.Rounded.Tv, "📺", Color(0xFF546E7A))
}

// Backward-compat shim (used by ModernCategorySelector)
fun getCategoryIcon(category: String): ImageVector = getCategoryMeta(category).icon

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun HeroCarousel(featured: List<Channel>, navController: NavController, onChannelClick: (Channel) -> Unit) {
    val pagerState   = rememberPagerState { featured.size }
    val primaryColor = MaterialTheme.colorScheme.primary

    LaunchedEffect(pagerState.currentPage) {
        if (featured.isNotEmpty()) {
            delay(4000)
            try { pagerState.animateScrollToPage((pagerState.currentPage + 1) % featured.size, animationSpec = tween(800)) } catch (_: Exception) {}
        }
    }

    Column {
        Text("FEATURED LIVE", style = TextStyle(color = primaryColor, fontWeight = FontWeight.Bold, letterSpacing = 1.sp, fontSize = 12.sp), modifier = Modifier.padding(start = 16.dp, bottom = 8.dp))
        HorizontalPager(state = pagerState, contentPadding = PaddingValues(horizontal = 24.dp), pageSpacing = 16.dp) { page ->
            val off   = (pagerState.currentPage - page) + pagerState.currentPageOffsetFraction
            val scale = lerp(0.92f, 1f, 1f - off.absoluteValue.coerceIn(0f, 1f))
            Card(
                modifier  = Modifier.graphicsLayer { scaleX = scale; scaleY = scale }.height(220.dp).fillMaxWidth().clickable { onChannelClick(featured[page]) },
                shape     = RoundedCornerShape(16.dp),
                colors    = CardDefaults.cardColors(containerColor = Color.Black),
                elevation = CardDefaults.cardElevation(8.dp)
            ) {
                Box(Modifier.fillMaxSize()) {
                    AsyncImage(model = featured[page].logoUrl, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize().alpha(0.8f))
                    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(0.6f), Color.Black.copy(0.9f)))))
                    Column(Modifier.align(Alignment.BottomStart).padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(8.dp).background(Color.Red, CircleShape)); Spacer(Modifier.width(6.dp))
                            Text("LIVE NOW", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        }
                        Spacer(Modifier.height(4.dp))
                        Text(featured[page].name, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Black, maxLines = 1)
                        Spacer(Modifier.height(8.dp))
                        Box(Modifier.background(primaryColor, RoundedCornerShape(4.dp)).padding(horizontal = 12.dp, vertical = 6.dp)) {
                            Text("WATCH STREAM", color = Color.Black, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                    Box(Modifier.align(Alignment.Center).size(48.dp).background(Color.Black.copy(0.4f), CircleShape).border(1.dp, Color.White, CircleShape), contentAlignment = Alignment.Center) {
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
    val bgColor      = MaterialTheme.colorScheme.background
    ModalDrawerSheet(drawerContainerColor = Color(0xFF101010), drawerContentColor = Color.White) {
        Box(Modifier.fillMaxWidth().height(200.dp).background(Brush.linearGradient(listOf(bgColor, Color(0xFF1A1A1A)))), contentAlignment = Alignment.CenterStart) {
            Column(Modifier.padding(24.dp)) {
                Text("STREAMX", color = primaryColor, fontSize = 30.sp, fontWeight = FontWeight.Black, letterSpacing = 2.sp)
                Text("ULTRA EDITION", color = Color.Gray, fontSize = 12.sp, letterSpacing = 4.sp)
            }
        }
        HorizontalDivider(color = Color.White.copy(0.1f))
        Spacer(Modifier.height(12.dp))
        val im = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
        NavigationDrawerItem("DASHBOARD", true, onCloseDrawer, Icons.Default.Home, primaryColor, im)
        NavigationDrawerItem("PROFILE", false, { onCloseDrawer(); navController.navigate("account") }, Icons.Default.Person, Color.Gray, im)
        NavigationDrawerItem("SYSTEM", false, { onCloseDrawer(); navController.navigate("settings") }, Icons.Default.Settings, Color.Gray, im)
    }
}

@Composable
private fun NavigationDrawerItem(label: String, selected: Boolean, onClick: () -> Unit, icon: ImageVector, tintColor: Color, modifier: Modifier) {
    val primaryColor = MaterialTheme.colorScheme.primary
    NavigationDrawerItem(
        label    = { Text(label) },
        selected = selected,
        onClick  = onClick,
        icon     = { Icon(icon, null, tint = tintColor) },
        colors   = NavigationDrawerItemDefaults.colors(
            selectedContainerColor = primaryColor.copy(0.1f),
            selectedTextColor      = primaryColor,
            unselectedTextColor    = Color.Gray
        ),
        modifier = modifier
    )
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
    Column(Modifier.fillMaxWidth().padding(top = 80.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(if (isFavorites) Icons.Default.FavoriteBorder else Icons.Rounded.Warning, null, tint = Color.DarkGray, modifier = Modifier.size(80.dp).padding(bottom = 16.dp))
        Text(if (isFavorites) "NO FAVORITES LOGGED" else "NO SIGNAL FOUND", color = Color.Gray, fontSize = 16.sp, letterSpacing = 2.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun LinkSelectorDialog(channel: Channel, onDismiss: () -> Unit, onLinkSelected: (String) -> Unit) {
    val primaryColor  = MaterialTheme.colorScheme.primary
    val surfaceColor  = MaterialTheme.colorScheme.surface
    Dialog(onDismissRequest = onDismiss) {
        Card(shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = surfaceColor), modifier = Modifier.border(1.dp, Color.White.copy(0.1f), RoundedCornerShape(16.dp))) {
            Column(Modifier.padding(24.dp)) {
                Text("SELECT STREAM", fontWeight = FontWeight.Bold, fontSize = 16.sp, color = primaryColor, letterSpacing = 1.sp)
                Spacer(Modifier.height(16.dp))
                LazyColumn {
                    itemsIndexed(channel.streamUrls) { index, url ->
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 6.dp).clip(RoundedCornerShape(8.dp)).background(Color(0xFF202020)).clickable { onLinkSelected(url) }.padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.PlayArrow, null, tint = primaryColor)
                            Spacer(Modifier.width(16.dp))
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
        title   = { Text("SYSTEM UPDATE DETECTED", color = primaryColor, fontSize = 16.sp) },
        text    = { Column { Text("PATCH: ${release.tag_name}", fontWeight = FontWeight.Bold, color = Color.White); Spacer(Modifier.height(8.dp)); Text(release.body.lines().filterNot { it.startsWith("versionCode:") }.joinToString("\n"), color = Color.Gray) } },
        confirmButton = { Button(onClick = onUpdateClick, colors = ButtonDefaults.buttonColors(containerColor = primaryColor)) { Text("INSTALL PATCH", color = Color.Black) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("IGNORE", color = Color.Gray) } },
        containerColor = surfaceColor
    )
}
