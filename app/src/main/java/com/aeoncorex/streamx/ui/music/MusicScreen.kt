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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import coil.compose.AsyncImage
import kotlinx.coroutines.launch

// Color Constants
val GlassWhite = Color(0x1AFFFFFF)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MusicScreen(navController: NavController) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)

    // UI State
    var searchQuery by remember { mutableStateOf("Trending") }
    var isSearchActive by remember { mutableStateOf(false) }
    
    // Data State
    var songsList by remember { mutableStateOf<List<MusicTrack>>(emptyList()) }
    var albumsList by remember { mutableStateOf<List<MusicCollection>>(emptyList()) }
    var playlistsList by remember { mutableStateOf<List<MusicCollection>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }

    // Player State
    val currentSong by MusicManager.currentSong.collectAsState()
    val isPlaying by MusicManager.isPlaying.collectAsState()

    // Fetch Data
    fun fetchData(query: String) {
        scope.launch {
            isLoading = true
            // Parallel fetching
            val songs = MusicRepository.searchSongs(query)
            val albums = MusicRepository.searchAlbums(query)
            val playlists = MusicRepository.searchPlaylists(query)
            
            songsList = songs
            albumsList = albums
            playlistsList = playlists
            isLoading = false
        }
    }

    LaunchedEffect(Unit) { fetchData("Trending") }

    fun playCollection(collection: MusicCollection) {
        scope.launch {
            val tracks = MusicRepository.getCollectionTracks(collection.id, collection.type)
            if (tracks.isNotEmpty()) {
                MusicManager.playTrackList(tracks, 0)
            }
        }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            MusicAppDrawer(navController, onCloseDrawer = { scope.launch { drawerState.close() } })
        }
    ) {
        // ULTRA BACKGROUND
        CyberMeshBackground()

        Box(modifier = Modifier.fillMaxSize()) {
            
            // MAIN CONTENT
            if (isLoading) {
                 Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(top = 90.dp, bottom = 120.dp) // Space for Header & MiniPlayer
                ) {
                    // 1. Playlists Section (Horizontal)
                    if (playlistsList.isNotEmpty()) {
                        item { SectionTitle("POPULAR PLAYLISTS") }
                        item {
                            LazyRow(
                                contentPadding = PaddingValues(horizontal = 16.dp),
                                horizontalArrangement = Arrangement.spacedBy(16.dp)
                            ) {
                                items(playlistsList) { playlist ->
                                    CollectionCard(playlist) { playCollection(playlist) }
                                }
                            }
                            Spacer(modifier = Modifier.height(24.dp))
                        }
                    }

                    // 2. Albums Section (Horizontal)
                    if (albumsList.isNotEmpty()) {
                        item { SectionTitle("TOP ALBUMS") }
                        item {
                            LazyRow(
                                contentPadding = PaddingValues(horizontal = 16.dp),
                                horizontalArrangement = Arrangement.spacedBy(16.dp)
                            ) {
                                items(albumsList) { album ->
                                    CollectionCard(album) { playCollection(album) }
                                }
                            }
                            Spacer(modifier = Modifier.height(24.dp))
                        }
                    }

                    // 3. Songs Section (Vertical)
                    item { SectionTitle("SONGS") }
                    itemsIndexed(songsList) { index, track ->
                        SongRowItem(track) {
                             MusicManager.playTrackList(songsList, index)
                        }
                    }
                }
            }

            // HEADER (Floating Glass)
            MusicHeader(
                drawerState = drawerState,
                onSearchClick = { isSearchActive = true }
            )
            
            // SEARCH OVERLAY
            if (isSearchActive) {
                Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(0.95f)).zIndex(2f)) {
                     SearchBar(
                        query = searchQuery,
                        onQueryChange = { searchQuery = it },
                        onSearch = { isSearchActive = false; fetchData(searchQuery) },
                        active = true,
                        onActiveChange = { isSearchActive = it },
                        placeholder = { Text("Search Music, Artists...", color = Color.Gray) },
                        leadingIcon = { Icon(Icons.Default.Search, null, tint = MaterialTheme.colorScheme.primary) },
                        trailingIcon = {
                            IconButton(onClick = { isSearchActive = false }) { Icon(Icons.Default.Close, null, tint = Color.White) }
                        },
                        colors = SearchBarDefaults.colors(containerColor = Color.Black, dividerColor = Color.Gray),
                        modifier = Modifier.fillMaxWidth()
                    ) {}
                }
            }

            // MINI PLAYER (Bottom Fixed)
            AnimatedVisibility(
                visible = currentSong != null,
                enter = slideInVertically { it },
                exit = slideOutVertically { it },
                modifier = Modifier.align(Alignment.BottomCenter)
            ) {
                currentSong?.let { track ->
                    MiniPlayer(
                        track = track,
                        isPlaying = isPlaying,
                        onClick = { navController.navigate("music_player") },
                        onTogglePlay = { MusicManager.togglePlayPause() }
                    )
                }
            }
        }
    }
}

// --- UI COMPONENTS ---

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MusicHeader(drawerState: DrawerState, onSearchClick: () -> Unit) {
    val scope = rememberCoroutineScope()
    val primaryColor = MaterialTheme.colorScheme.primary
    val secondaryColor = MaterialTheme.colorScheme.secondary
    
    Box(modifier = Modifier
        .statusBarsPadding()
        .padding(horizontal = 16.dp, vertical = 8.dp)
        .fillMaxWidth()
        .clip(RoundedCornerShape(24.dp))
        .background(GlassWhite)
        .border(1.dp, Brush.horizontalGradient(listOf(Color.White.copy(0.1f), Color.White.copy(0.05f))), RoundedCornerShape(24.dp))
        .zIndex(1f)
    ) {
        CenterAlignedTopAppBar(
            title = {
                Text(
                    "STREAMX MUSIC",
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
                IconButton(onClick = onSearchClick) {
                    Icon(Icons.Default.Search, "Search", tint = Color.White)
                }
            },
            colors = TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor = Color.Transparent)
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
                Text("MUSIC X", color = primaryColor, fontSize = 30.sp, fontWeight = FontWeight.Black, letterSpacing = 2.sp)
                Text("ULTRA SOUND", color = Color.Gray, fontSize = 12.sp, letterSpacing = 4.sp)
            }
        }
        HorizontalDivider(color = Color.White.copy(0.1f))
        Spacer(Modifier.height(12.dp))
        NavigationDrawerItem(
            label = { Text("HOME") }, selected = true, onClick = onCloseDrawer,
            icon = { Icon(Icons.Default.Home, null, tint = primaryColor) },
            colors = NavigationDrawerItemDefaults.colors(selectedContainerColor = primaryColor.copy(0.1f), selectedTextColor = primaryColor, unselectedTextColor = Color.Gray),
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
        )
         NavigationDrawerItem(
            label = { Text("LIVE TV") }, selected = false, onClick = { onCloseDrawer(); navController.navigate("home") },
            icon = { Icon(Icons.Rounded.LiveTv, null, tint = Color.Gray) },
            colors = NavigationDrawerItemDefaults.colors(unselectedTextColor = Color.Gray),
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
        )
    }
}

@Composable
fun CyberMeshBackground() {
    val infiniteTransition = rememberInfiniteTransition(label = "bg")
    val offset1 by infiniteTransition.animateFloat(
        initialValue = 0f, targetValue = 1000f,
        animationSpec = infiniteRepeatable(tween(20000, easing = LinearEasing), RepeatMode.Reverse), label = ""
    )
    val bgColor = Color(0xFF0F0F15)
    val primary = MaterialTheme.colorScheme.primary
    val secondary = MaterialTheme.colorScheme.secondary

    Canvas(modifier = Modifier.fillMaxSize().background(bgColor)) {
        drawRect(brush = Brush.radialGradient(colors = listOf(Color(0xFF1A1A2E), bgColor), center = center, radius = size.maxDimension))
        drawCircle(brush = Brush.radialGradient(listOf(secondary.copy(0.15f), Color.Transparent)), radius = size.minDimension * 0.6f, center = Offset(size.width * 0.8f, size.height * 0.2f + offset1))
        drawCircle(brush = Brush.radialGradient(listOf(primary.copy(0.1f), Color.Transparent)), radius = size.minDimension * 0.7f, center = Offset(size.width * 0.2f, size.height * 0.8f - offset1))
    }
}

@Composable
fun SectionTitle(title: String) {
    Text(
        text = title,
        color = Color.White,
        fontSize = 18.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 1.sp,
        modifier = Modifier.padding(start = 16.dp, bottom = 12.dp, top = 8.dp)
    )
}

@Composable
fun CollectionCard(collection: MusicCollection, onClick: () -> Unit) {
    Column(modifier = Modifier.width(150.dp).clickable { onClick() }) {
        Card(
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.size(150.dp),
            elevation = CardDefaults.cardElevation(8.dp)
        ) {
            AsyncImage(
                model = collection.coverUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text(collection.title, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(collection.subtitle, color = Color.Gray, fontSize = 12.sp, maxLines = 1)
    }
}

@Composable
fun SongRowItem(track: MusicTrack, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AsyncImage(
            model = track.coverUrl,
            contentDescription = null,
            modifier = Modifier.size(50.dp).clip(RoundedCornerShape(8.dp)),
            contentScale = ContentScale.Crop
        )
        Spacer(modifier = Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(track.title, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.CenterVertically) {
                if(track.year.isNotEmpty()) {
                    Text(track.year, color = MaterialTheme.colorScheme.primary, fontSize = 10.sp, modifier = Modifier.border(1.dp, MaterialTheme.colorScheme.primary.copy(0.5f), RoundedCornerShape(4.dp)).padding(horizontal = 4.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                }
                Text(track.artist, color = Color.Gray, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Icon(Icons.Rounded.PlayArrow, null, tint = Color.Gray.copy(0.5f))
    }
}

@Composable
fun MiniPlayer(track: MusicTrack, isPlaying: Boolean, onClick: () -> Unit, onTogglePlay: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E)),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 16.dp).height(64.dp).clickable { onClick() }
            .border(1.dp, Color.White.copy(0.1f), RoundedCornerShape(12.dp))
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            Row(modifier = Modifier.fillMaxSize().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                AsyncImage(model = track.coverUrl, contentDescription = null, modifier = Modifier.size(48.dp).clip(RoundedCornerShape(8.dp)).animateContentSize(), contentScale = ContentScale.Crop)
                Column(modifier = Modifier.weight(1f).padding(horizontal = 12.dp)) {
                    Text(track.title, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                    Text(track.artist, color = Color.Gray, fontSize = 12.sp, maxLines = 1)
                }
                IconButton(onClick = onTogglePlay) {
                    Icon(imageVector = if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, contentDescription = null, tint = Color.White)
                }
            }
            val position by MusicManager.currentPosition.collectAsState()
            val duration by MusicManager.duration.collectAsState()
            val progress = if (duration > 0) position.toFloat() / duration else 0f
            LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth().height(2.dp).align(Alignment.BottomCenter), color = MaterialTheme.colorScheme.primary, trackColor = Color.Transparent)
        }
    }
}
