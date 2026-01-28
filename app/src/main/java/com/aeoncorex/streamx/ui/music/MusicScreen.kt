package com.aeoncorex.streamx.ui.music

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import coil.compose.AsyncImage
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// --- THEME CONSTANTS ---
val NeonPurple = Color(0xFFBC13FE)
val NeonCyan = Color(0xFF04D9FF)
val DeepDark = Color(0xFF05050A)
val SurfaceDark = Color(0xFF12121A)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MusicScreen(navController: NavController) {
    val scope = rememberCoroutineScope()
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    
    // UI State
    var searchQuery by remember { mutableStateOf("") }
    var activeFilter by remember { mutableStateOf("All") }
    
    // Data State
    var songsList by remember { mutableStateOf<List<MusicTrack>>(emptyList()) }
    var albumsList by remember { mutableStateOf<List<MusicCollection>>(emptyList()) }
    var playlistsList by remember { mutableStateOf<List<MusicCollection>>(emptyList()) }
    var isLoading by remember { mutableStateOf(false) }
    var hasSearched by remember { mutableStateOf(false) }

    // Player State (For List Items Visualizer)
    val currentSong by MusicManager.currentSong.collectAsState()
    val isPlaying by MusicManager.isPlaying.collectAsState()

    // Fetch Logic
    fun fetchData(query: String) {
        if (query.isBlank()) return
        scope.launch {
            isLoading = true
            hasSearched = true
            songsList = emptyList()
            
            val songs = MusicRepository.searchSongs(query)
            val albums = MusicRepository.searchAlbums(query)
            val playlists = MusicRepository.searchPlaylists(query)
            
            songsList = songs
            albumsList = albums
            playlistsList = playlists
            isLoading = false
        }
    }

    // Debounce Search
    LaunchedEffect(searchQuery) {
        if (searchQuery.length > 2) {
            delay(800)
            fetchData(searchQuery)
        }
    }

    LaunchedEffect(Unit) { fetchData("Trending Global") }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = { MusicAppDrawer(navController) { scope.launch { drawerState.close() } } }
    ) {
        Box(modifier = Modifier.fillMaxSize().background(DeepDark)) {
            // Background
            CyberMeshBackground()

            // Main Content
            Scaffold(
                containerColor = Color.Transparent,
                topBar = {
                    Column {
                        MusicTopBar(
                            drawerState = drawerState, 
                            query = searchQuery, 
                            onQueryChange = { searchQuery = it }
                        )
                        FilterRow(
                            selected = activeFilter, 
                            onSelect = { activeFilter = it }
                        )
                    }
                },
                // Removed bottomBar Spacer here because MainScreen handles padding
            ) { padding ->
                
                if (isLoading) {
                    Box(modifier = Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                        CyberLoader()
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize().padding(padding),
                        // Add extra bottom padding so last item is not hidden behind MainScreen MiniPlayer
                        contentPadding = PaddingValues(bottom = 120.dp) 
                    ) {
                        // Hero Section
                        if (activeFilter == "All" && !hasSearched) {
                            item { TrendingHero() }
                        }

                        // Albums
                        if ((activeFilter == "All" || activeFilter == "Albums") && albumsList.isNotEmpty()) {
                            item { SectionHeader("Top Albums") }
                            item {
                                LazyRow(
                                    contentPadding = PaddingValues(horizontal = 16.dp),
                                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                                ) {
                                    items(albumsList) { album ->
                                        FuturisticCard(album) { 
                                            scope.launch {
                                                val tracks = MusicRepository.getCollectionTracks(album.id, album.type)
                                                if (tracks.isNotEmpty()) MusicManager.playTrackList(tracks, 0)
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        // Playlists
                        if ((activeFilter == "All" || activeFilter == "Playlists") && playlistsList.isNotEmpty()) {
                            item { SectionHeader("Hot Playlists") }
                            item {
                                LazyRow(
                                    contentPadding = PaddingValues(horizontal = 16.dp),
                                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                                ) {
                                    items(playlistsList) { playlist ->
                                        FuturisticCard(playlist) {
                                            scope.launch {
                                                val tracks = MusicRepository.getCollectionTracks(playlist.id, playlist.type)
                                                if (tracks.isNotEmpty()) MusicManager.playTrackList(tracks, 0)
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        // Songs
                        if (activeFilter == "All" || activeFilter == "Songs" || activeFilter == "YouTube") {
                            item { SectionHeader("Tracks") }
                            
                            val filteredSongs = if (activeFilter == "YouTube") {
                                songsList.filter { it.source == "YouTube" }
                            } else {
                                songsList
                            }

                            itemsIndexed(filteredSongs) { index, track ->
                                TrackListItem(
                                    track = track,
                                    isPlaying = isPlaying && currentSong?.id == track.id,
                                    onClick = { MusicManager.playTrackList(filteredSongs, index) }
                                )
                            }
                        }
                    }
                }
            }
            
            // REMOVED LOCAL MINI PLAYER FROM HERE
            // It will be handled by MainScreen globally
        }
    }
}

// --- COMPONENTS ---

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MusicTopBar(drawerState: DrawerState, query: String, onQueryChange: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(
            onClick = { scope.launch { drawerState.open() } },
            modifier = Modifier.background(SurfaceDark, CircleShape).size(40.dp)
        ) {
            Icon(Icons.Rounded.Menu, null, tint = Color.White)
        }

        Spacer(modifier = Modifier.width(12.dp))

        Box(
            modifier = Modifier
                .weight(1f)
                .height(45.dp)
                .clip(RoundedCornerShape(30.dp))
                .background(SurfaceDark)
                .border(1.dp, Brush.horizontalGradient(listOf(Color.White.copy(0.1f), Color.Transparent)), RoundedCornerShape(30.dp)),
            contentAlignment = Alignment.CenterStart
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 12.dp)) {
                Icon(Icons.Default.Search, null, tint = NeonCyan, modifier = Modifier.size(20.dp))
                Spacer(modifier = Modifier.width(8.dp))
                BasicTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    textStyle = TextStyle(color = Color.White, fontSize = 16.sp),
                    singleLine = true,
                    decorationBox = { innerTextField ->
                        if (query.isEmpty()) {
                            Text("What do you want to play?", color = Color.Gray, fontSize = 14.sp)
                        }
                        innerTextField()
                    },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

@Composable
fun FilterRow(selected: String, onSelect: (String) -> Unit) {
    val filters = listOf("All", "Songs", "Albums", "YouTube")
    
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.padding(bottom = 16.dp)
    ) {
        items(filters) { filter ->
            val isSelected = selected == filter
            val bg = if (isSelected) NeonPurple else SurfaceDark
            val textColor = if (isSelected) Color.White else Color.Gray
            
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(20.dp))
                    .background(bg)
                    .clickable { onSelect(filter) }
                    .padding(horizontal = 20.dp, vertical = 8.dp)
                    .animateContentSize()
            ) {
                Text(filter, color = textColor, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
fun TrendingHero() {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .height(200.dp)
            .padding(16.dp),
        shape = RoundedCornerShape(24.dp),
        elevation = CardDefaults.cardElevation(10.dp)
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            AsyncImage(
                model = "https://images.unsplash.com/photo-1493225255756-d9584f8606e9?q=80&w=2070&auto=format&fit=crop",
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(0.8f))))
            )
            Column(
                modifier = Modifier.align(Alignment.BottomStart).padding(16.dp)
            ) {
                Text("TRENDING NOW", color = NeonCyan, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
                Text("Global Top 50", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Black)
                Text("The hottest tracks right now.", color = Color.Gray, fontSize = 12.sp)
            }
            
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(16.dp)
                    .size(50.dp)
                    .clip(CircleShape)
                    .background(NeonPurple),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Rounded.PlayArrow, null, tint = Color.White, modifier = Modifier.size(28.dp))
            }
        }
    }
}

@Composable
fun SectionHeader(title: String) {
    Text(
        text = title,
        color = Color.White,
        fontSize = 18.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(start = 16.dp, top = 24.dp, bottom = 12.dp)
    )
}

@Composable
fun FuturisticCard(item: MusicCollection, onClick: () -> Unit) {
    Column(
        modifier = Modifier.width(140.dp).clickable { onClick() }
    ) {
        Card(
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.size(140.dp),
            colors = CardDefaults.cardColors(containerColor = SurfaceDark)
        ) {
            AsyncImage(
                model = item.coverUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text(item.title, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(item.subtitle, color = Color.Gray, fontSize = 11.sp, maxLines = 1)
    }
}

@Composable
fun TrackListItem(track: MusicTrack, isPlaying: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(modifier = Modifier.size(56.dp)) {
            AsyncImage(
                model = track.coverUrl,
                contentDescription = null,
                modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(8.dp)),
                contentScale = ContentScale.Crop
            )
            
            if (isPlaying) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(0.6f), RoundedCornerShape(8.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    AudioVisualizerAnim()
                }
            }
            
            if (track.source == "YouTube") {
                Box(modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(2.dp)
                    .background(Color.Red, RoundedCornerShape(4.dp))
                    .padding(horizontal = 3.dp, vertical = 1.dp)
                ) {
                    Text("YT", color = Color.White, fontSize = 8.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
        
        Spacer(modifier = Modifier.width(16.dp))
        
        Column(modifier = Modifier.weight(1f)) {
            Text(
                track.title, 
                color = if(isPlaying) NeonCyan else Color.White, 
                fontSize = 16.sp, 
                fontWeight = FontWeight.Medium, 
                maxLines = 1, 
                overflow = TextOverflow.Ellipsis
            )
            Text(track.artist, color = Color.Gray, fontSize = 13.sp, maxLines = 1)
        }
        
        IconButton(onClick = { /* More Options */ }) {
            Icon(Icons.Rounded.MoreVert, null, tint = Color.Gray)
        }
    }
}

@Composable
fun AudioVisualizerAnim() {
    val infiniteTransition = rememberInfiniteTransition(label = "viz")
    val height1 by infiniteTransition.animateFloat(0.2f, 1f, infiniteRepeatable(tween(400), RepeatMode.Reverse), label = "")
    val height2 by infiniteTransition.animateFloat(0.3f, 0.8f, infiniteRepeatable(tween(550), RepeatMode.Reverse), label = "")
    val height3 by infiniteTransition.animateFloat(0.1f, 0.9f, infiniteRepeatable(tween(300), RepeatMode.Reverse), label = "")

    Row(
        verticalAlignment = Alignment.CenterVertically, 
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        modifier = Modifier.size(24.dp).padding(4.dp)
    ) {
        Box(Modifier.weight(1f).fillMaxHeight(height1).background(NeonCyan, CircleShape))
        Box(Modifier.weight(1f).fillMaxHeight(height2).background(NeonPurple, CircleShape))
        Box(Modifier.weight(1f).fillMaxHeight(height3).background(NeonCyan, CircleShape))
    }
}

@Composable
fun CyberLoader() {
    val infiniteTransition = rememberInfiniteTransition(label = "loader")
    val angle by infiniteTransition.animateFloat(0f, 360f, infiniteRepeatable(tween(1000, easing = LinearEasing)), label = "")
    
    Box(
        modifier = Modifier
            .size(50.dp)
            .border(3.dp, Brush.sweepGradient(listOf(NeonCyan, NeonPurple, Color.Transparent)), CircleShape)
            .rotate(angle)
    )
}

@Composable
fun CyberMeshBackground() {
    val infiniteTransition = rememberInfiniteTransition(label = "bg")
    val offset1 by infiniteTransition.animateFloat(
        initialValue = 0f, targetValue = 1000f,
        animationSpec = infiniteRepeatable(tween(30000, easing = LinearEasing), RepeatMode.Reverse), label = ""
    )
    
    Canvas(modifier = Modifier.fillMaxSize().background(DeepDark)) {
        drawRect(brush = Brush.radialGradient(colors = listOf(Color(0xFF1A1A2E), DeepDark), center = center, radius = size.maxDimension))
        drawCircle(
            brush = Brush.radialGradient(listOf(NeonPurple.copy(0.1f), Color.Transparent)), 
            radius = size.minDimension * 0.5f, 
            center = Offset(size.width * 0.8f, size.height * 0.2f + offset1)
        )
        drawCircle(
            brush = Brush.radialGradient(listOf(NeonCyan.copy(0.1f), Color.Transparent)), 
            radius = size.minDimension * 0.6f, 
            center = Offset(size.width * 0.2f, size.height * 0.8f - offset1)
        )
    }
}

@Composable
fun MusicAppDrawer(navController: NavController, onCloseDrawer: () -> Unit) {
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
            label = { Text("Live TV") }, selected = false, onClick = { onCloseDrawer(); navController.navigate("home") },
            icon = { Icon(Icons.Default.Tv, null, tint = Color.Gray) },
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

