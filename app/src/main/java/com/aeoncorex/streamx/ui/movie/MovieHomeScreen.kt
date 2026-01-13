package com.aeoncorex.streamx.ui.movie

import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.navigation.NavController
import coil.compose.AsyncImage
import com.aeoncorex.streamx.data.MovieRepository
import com.aeoncorex.streamx.model.Movie
import com.aeoncorex.streamx.ui.home.CyberMeshBackground // Ensure this import exists or use the function at bottom
import com.valentinilk.shimmer.shimmer
import kotlinx.coroutines.launch
import java.net.URLEncoder

// Define GlassWhite color locally if not available globally
val GlassWhite = Color(0x1AFFFFFF)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MovieHomeScreen(navController: NavController) {
    val scope = rememberCoroutineScope()
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)

    // Colors
    val primaryColor = MaterialTheme.colorScheme.primary
    val secondaryColor = MaterialTheme.colorScheme.secondary
    val backgroundColor = MaterialTheme.colorScheme.background

    // Data Collection
    val moviesState = MovieRepository.getMoviesFlow().collectAsState(initial = emptyList())
    val allMovies = moviesState.value

    // Search State
    var searchQuery by remember { mutableStateOf("") }
    var isSearchActive by remember { mutableStateOf(false) }

    // Derived States
    val filteredMovies = remember(allMovies, searchQuery) {
        if (searchQuery.isEmpty()) allMovies else allMovies.filter {
            it.title.contains(searchQuery, ignoreCase = true)
        }
    }
    
    val featuredMovies = remember(allMovies) { allMovies.filter { it.isFeatured }.take(5) }
    val topRatedMovies = remember(allMovies) { allMovies.sortedByDescending { it.rating }.take(10) }
    val categories = remember(allMovies) { allMovies.groupBy { it.category } }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            MovieAppDrawer(navController, onCloseDrawer = { scope.launch { drawerState.close() } })
        }
    ) {
        Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
            // Background
            CyberMeshBackground() 

            // Content
            if (isSearchActive) {
                // --- SEARCH OVERLAY ---
                Box(modifier = Modifier.fillMaxSize().background(backgroundColor.copy(0.95f)).zIndex(2f)) {
                    SearchBar(
                        query = searchQuery,
                        onQueryChange = { searchQuery = it },
                        onSearch = { isSearchActive = false },
                        active = true,
                        onActiveChange = { isSearchActive = it },
                        placeholder = { Text("Search Movies...", color = Color.Gray) },
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
                            items(filteredMovies) { movie ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { 
                                            navController.navigate("movie_detail/${movie.id}")
                                            isSearchActive = false 
                                        }
                                        .padding(vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    AsyncImage(
                                        model = movie.posterUrl,
                                        contentDescription = null,
                                        modifier = Modifier.width(50.dp).aspectRatio(0.7f).clip(RoundedCornerShape(4.dp)),
                                        contentScale = ContentScale.Crop
                                    )
                                    Spacer(Modifier.width(16.dp))
                                    Column {
                                        Text(movie.title, color = Color.White, fontWeight = FontWeight.Bold)
                                        Text(movie.year, color = Color.Gray, fontSize = 12.sp)
                                    }
                                }
                                HorizontalDivider(color = Color.White.copy(0.1f))
                            }
                        }
                    }
                }
            } else {
                // --- MAIN CONTENT ---
                if (allMovies.isEmpty()) {
                    // Added top padding for shimmer so it doesn't hide behind header
                    Box(modifier = Modifier.padding(top = 80.dp)) {
                        LoadingShimmer()
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        // Add top padding (80.dp) for the header and bottom padding for nav bar
                        contentPadding = PaddingValues(bottom = 100.dp) 
                    ) {
                        // 1. Hero Pager (Netflix Style)
                        item {
                            if (featuredMovies.isNotEmpty()) {
                                HeroCarousel(movies = featuredMovies, onPlay = { playMovie(navController, it.streamUrl) }, onDetail = { navController.navigate("movie_detail/${it.id}") })
                            }
                        }

                        // 2. Top 10 Row
                        if (topRatedMovies.isNotEmpty()) {
                            item {
                                CategoryTitle("Top 10 Movies Today")
                                LazyRow(
                                    contentPadding = PaddingValues(horizontal = 16.dp),
                                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                                ) {
                                    itemsIndexed(topRatedMovies) { index, movie ->
                                        Top10Card(index + 1, movie) { navController.navigate("movie_detail/${movie.id}") }
                                    }
                                }
                            }
                        }

                        // 3. Standard Categories
                        categories.forEach { (categoryName, movies) ->
                            item {
                                CategoryTitle(categoryName)
                                LazyRow(
                                    contentPadding = PaddingValues(horizontal = 16.dp),
                                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    items(movies) { movie ->
                                        StandardMovieCard(movie) { navController.navigate("movie_detail/${movie.id}") }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // --- CUSTOM FLOATING HEADER (LiveTV Style) ---
            if (!isSearchActive) {
                Box(modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .clip(RoundedCornerShape(24.dp))
                    .background(GlassWhite)
                    .border(1.dp, Brush.horizontalGradient(listOf(Color.White.copy(0.1f), Color.White.copy(0.05f))), RoundedCornerShape(24.dp))
                    .zIndex(1f)
                ) {
                    CenterAlignedTopAppBar(
                        title = {
                            Text(
                                "MOVIES", // Title Changed to MOVIES
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
}

// -----------------------------------------------------------------------------
// COMPONENTS (Copied/Adapted for Movies)
// -----------------------------------------------------------------------------

@Composable
fun MovieAppDrawer(navController: NavController, onCloseDrawer: () -> Unit) {
    val primaryColor = MaterialTheme.colorScheme.primary
    val bgColor = MaterialTheme.colorScheme.background

    ModalDrawerSheet(
        drawerContainerColor = Color(0xFF101010),
        drawerContentColor = Color.White
    ) {
        Box(modifier = Modifier.fillMaxWidth().height(200.dp).background(Brush.linearGradient(listOf(bgColor, Color(0xFF1A1A1A)))), contentAlignment = Alignment.CenterStart) {
            Column(Modifier.padding(24.dp)) {
                Text("STREAMX", color = primaryColor, fontSize = 30.sp, fontWeight = FontWeight.Black, letterSpacing = 2.sp)
                Text("CINEMA HUB", color = Color.Gray, fontSize = 12.sp, letterSpacing = 4.sp)
            }
        }
        HorizontalDivider(color = Color.White.copy(0.1f))
        Spacer(Modifier.height(12.dp))
        
        val itemModifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
        
        NavigationDrawerItem(
            label = { Text("HOME") }, selected = false, onClick = { onCloseDrawer(); navController.navigate("home") }, // Assuming home route exists
            icon = { Icon(Icons.Default.Home, null, tint = Color.Gray) },
            colors = NavigationDrawerItemDefaults.colors(unselectedTextColor = Color.Gray),
            modifier = itemModifier
        )
        NavigationDrawerItem(
            label = { Text("MOVIES") }, selected = true, onClick = onCloseDrawer,
            icon = { Icon(Icons.Default.Movie, null, tint = primaryColor) },
            colors = NavigationDrawerItemDefaults.colors(selectedContainerColor = primaryColor.copy(0.1f), selectedTextColor = primaryColor, unselectedTextColor = Color.Gray),
            modifier = itemModifier
        )
         NavigationDrawerItem(
            label = { Text("LIVE TV") }, selected = false, onClick = { onCloseDrawer(); navController.navigate("livetv") }, // Assuming livetv route
            icon = { Icon(Icons.Default.LiveTv, null, tint = Color.Gray) },
            colors = NavigationDrawerItemDefaults.colors(unselectedTextColor = Color.Gray),
            modifier = itemModifier
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun HeroCarousel(movies: List<Movie>, onPlay: (Movie) -> Unit, onDetail: (Movie) -> Unit) {
    val pagerState = rememberPagerState(pageCount = { movies.size })

    Box(modifier = Modifier.fillMaxWidth().height(550.dp)) {
        HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize()) { page ->
            val movie = movies[page]
            Box(modifier = Modifier.fillMaxSize()) {
                AsyncImage(
                    model = movie.coverUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
                // Gradient Overlay
                Box(modifier = Modifier.fillMaxSize().background(Brush.verticalGradient(
                    colors = listOf(Color.Transparent, Color.Black.copy(0.2f), Color.Black)
                )))
            }
        }

        // Hero Content Overlay
        Column(
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 40.dp, start = 20.dp, end = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            val currentMovie = movies[pagerState.currentPage]
            
            // Text Tags
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                Tag(currentMovie.category)
                Spacer(Modifier.width(8.dp))
                Tag(currentMovie.year)
            }
            Spacer(Modifier.height(16.dp))

            // Action Buttons
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clickable { /* Add to List */ }) {
                    Icon(Icons.Rounded.Add, null, tint = Color.White)
                    Text("My List", color = Color.White, fontSize = 10.sp)
                }

                Button(
                    onClick = { onPlay(currentMovie) },
                    colors = ButtonDefaults.buttonColors(containerColor = Color.White),
                    shape = RoundedCornerShape(4.dp),
                    modifier = Modifier.width(120.dp)
                ) {
                    Icon(Icons.Filled.PlayArrow, null, tint = Color.Black)
                    Text("Play", color = Color.Black, fontWeight = FontWeight.Bold)
                }

                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clickable { onDetail(currentMovie) }) {
                    Icon(Icons.Filled.Info, null, tint = Color.White)
                    Text("Info", color = Color.White, fontSize = 10.sp)
                }
            }
        }
        
        // Pager Indicators
        Row(
            Modifier.align(Alignment.BottomCenter).padding(bottom = 10.dp),
            horizontalArrangement = Arrangement.Center
        ) {
            repeat(movies.size) { iteration ->
                val color = if (pagerState.currentPage == iteration) Color.White else Color.Gray
                Box(modifier = Modifier.padding(2.dp).clip(RoundedCornerShape(2.dp)).background(color).size(6.dp))
            }
        }
    }
}

@Composable
fun Top10Card(rank: Int, movie: Movie, onClick: () -> Unit) {
    Box(modifier = Modifier.width(160.dp).height(220.dp).clickable { onClick() }) {
        // Large Rank Number
        Text(
            text = "$rank",
            fontSize = 110.sp,
            fontWeight = FontWeight.Black,
            color = Color.White.copy(0.5f), 
            modifier = Modifier.align(Alignment.BottomStart).offset(x = (-15).dp, y = 15.dp).graphicsLayer { alpha = 1f },
            style = LocalTextStyle.current.copy(
                shadow = androidx.compose.ui.graphics.Shadow(offset = androidx.compose.ui.geometry.Offset(2f, 2f), blurRadius = 4f)
            )
        )
        // Poster
        AsyncImage(
            model = movie.posterUrl,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.padding(start = 25.dp).fillMaxSize().clip(RoundedCornerShape(8.dp))
        )
        // Top 10 Badge
        Box(Modifier.align(Alignment.TopEnd).padding(4.dp).background(Color.Red, RoundedCornerShape(2.dp)).padding(horizontal = 4.dp)) {
            Text("TOP 10", color = Color.White, fontSize = 8.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
fun StandardMovieCard(movie: Movie, onClick: () -> Unit) {
    Column(modifier = Modifier.width(120.dp).clickable { onClick() }) {
        AsyncImage(
            model = movie.posterUrl,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.height(180.dp).fillMaxWidth().clip(RoundedCornerShape(4.dp))
        )
    }
}

@Composable
fun CategoryTitle(title: String) {
    Text(
        text = title,
        color = Color.White,
        fontSize = 18.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(start = 16.dp, top = 24.dp, bottom = 12.dp)
    )
}

@Composable
fun Tag(text: String) {
    Text(text, color = Color.White, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 4.dp))
    Text("•", color = Color.Gray, fontSize = 12.sp)
}

@Composable
fun LoadingShimmer() {
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Box(Modifier.fillMaxWidth().height(400.dp).clip(RoundedCornerShape(16.dp)).shimmer().background(Color.Gray))
        Spacer(Modifier.height(20.dp))
        Row {
            repeat(3) {
                Box(Modifier.width(120.dp).height(180.dp).clip(RoundedCornerShape(4.dp)).shimmer().background(Color.Gray))
                Spacer(Modifier.width(10.dp))
            }
        }
    }
}

fun playMovie(navController: NavController, url: String) {
    try {
        val encodedUrl = URLEncoder.encode(url, "UTF-8")
        navController.navigate("player/$encodedUrl")
    } catch (e: Exception) { e.printStackTrace() }
}