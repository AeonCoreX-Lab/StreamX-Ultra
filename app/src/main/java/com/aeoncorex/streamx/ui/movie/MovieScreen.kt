package com.aeoncorex.streamx.ui.movie

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.*
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import coil.compose.AsyncImage
import kotlinx.coroutines.launch

// Color Definition
val GlassWhite = Color(0x1AFFFFFF)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MovieScreen(
    navController: NavController,
    viewModel: MovieViewModel = viewModel()
) {
    val scrollState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val primaryColor = MaterialTheme.colorScheme.primary
    val secondaryColor = MaterialTheme.colorScheme.secondary

    // Collecting Data
    val trendingList by viewModel.trending.collectAsState()
    val popularList by viewModel.popular.collectAsState()
    val seriesList by viewModel.series.collectAsState()
    val actionList by viewModel.action.collectAsState()
    val sciFiList by viewModel.sciFi.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()

    // Search Data
    val isSearchActive by viewModel.isSearchActive.collectAsState()
    val searchQuery by viewModel.searchQuery.collectAsState()
    val searchResults by viewModel.searchResults.collectAsState()

    val featuredMovie = trendingList.firstOrNull()

    // Helper function for Navigation
    fun openDetails(movie: Movie) {
        val typeStr = if (movie.type == MovieType.MOVIE) "MOVIE" else "SERIES"
        navController.navigate("movie_detail/${movie.id}/$typeStr")
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
             com.aeoncorex.streamx.ui.home.AppDrawer(navController) { scope.launch { drawerState.close() } }
        }
    ) {
        Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
            
            // 1. BACKGROUND
            CyberMeshBackground()

            // 2. MAIN CONTENT
            if (isLoading) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = primaryColor)
                }
            } else {
                LazyColumn(
                    state = scrollState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(top = 80.dp, bottom = 100.dp) 
                ) {
                    featuredMovie?.let { movie ->
                        item { HeroSection(movie = movie, onPlayClick = { openDetails(movie) }) }
                    }
                    item { MovieSection("Trending Now", trendingList, ::openDetails) }
                    item { MovieSection("Popular Movies", popularList, ::openDetails) }
                    item { MovieSection("Top Rated Series", seriesList, ::openDetails, isPortrait = false) }
                    item { MovieSection("Action Blockbusters", actionList, ::openDetails) }
                    item { MovieSection("Sci-Fi & Cyberpunk", sciFiList, ::openDetails) }
                }
            }

            // 3. HEADER (Floating with Search Trigger)
            if (!isSearchActive) {
                Box(
                    modifier = Modifier
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
                                "STREAMX", 
                                style = TextStyle(
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
                            IconButton(onClick = { viewModel.onSearchActiveChange(true) }) {
                                Icon(Icons.Default.Search, "Search", tint = Color.White)
                            }
                        },
                        colors = TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor = Color.Transparent)
                    )
                }
            }

            // 4. SEARCH OVERLAY (Functional)
            AnimatedVisibility(
                visible = isSearchActive,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.zIndex(2f)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.95f))
                ) {
                    Column(Modifier.fillMaxSize()) {
                        SearchBar(
                            query = searchQuery,
                            onQueryChange = viewModel::onSearchQueryChange,
                            onSearch = { /* Close Keyboard handled by system */ },
                            active = true,
                            onActiveChange = viewModel::onSearchActiveChange,
                            placeholder = { Text("Search Movies & Series...", color = Color.Gray) },
                            leadingIcon = { Icon(Icons.Default.Search, null, tint = primaryColor) },
                            trailingIcon = { 
                                IconButton(onClick = { viewModel.onSearchActiveChange(false) }) { 
                                    Icon(Icons.Default.Close, null, tint = Color.White) 
                                } 
                            },
                            colors = SearchBarDefaults.colors(
                                containerColor = Color(0xFF121212),
                                dividerColor = primaryColor,
                                inputFieldColors = TextFieldDefaults.colors(
                                    focusedTextColor = Color.White,
                                    cursorColor = primaryColor
                                )
                            ),
                            modifier = Modifier.fillMaxWidth() // SearchBar takes full width in active mode
                        ) {
                            // Search Results
                            if (searchResults.isEmpty() && searchQuery.isNotEmpty()) {
                                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                    Text("Searching...", color = Color.Gray)
                                }
                            } else {
                                LazyVerticalGrid(
                                    columns = GridCells.Fixed(3),
                                    contentPadding = PaddingValues(16.dp),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    items(searchResults) { movie ->
                                        MovieCard(movie = movie, isPortrait = true, onClick = { 
                                            openDetails(movie)
                                            viewModel.onSearchActiveChange(false) // Close search on click
                                        })
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

// --- SUB COMPONENTS ---

@Composable
fun HeroSection(movie: Movie, onPlayClick: () -> Unit) {
    Box(modifier = Modifier.fillMaxWidth().height(550.dp)) {
        AsyncImage(
            model = movie.backdropUrl,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
        )
        Box(
            modifier = Modifier.fillMaxSize().background(
                Brush.verticalGradient(colors = listOf(Color.Transparent, Color.Black.copy(0.2f), Color.Black))
            )
        )
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(horizontal = 24.dp, vertical = 40.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = movie.title.uppercase(),
                style = MaterialTheme.typography.displaySmall,
                color = Color.White,
                fontWeight = FontWeight.Black,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("TMDB ${movie.rating}", color = Color.Green, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                Spacer(Modifier.width(12.dp))
                Text(movie.year, color = Color.White, fontSize = 12.sp)
                Spacer(Modifier.width(12.dp))
                Box(Modifier.background(Color.DarkGray, RoundedCornerShape(4.dp)).padding(horizontal = 4.dp)) {
                    Text(if(movie.type == MovieType.MOVIE) "MOVIE" else "SERIES", color = Color.White, fontSize = 10.sp)
                }
            }
            Spacer(Modifier.height(24.dp))
            Button(
                onClick = onPlayClick,
                colors = ButtonDefaults.buttonColors(containerColor = Color.White),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.fillMaxWidth().height(50.dp)
            ) {
                Icon(Icons.Rounded.PlayArrow, null, tint = Color.Black)
                Spacer(Modifier.width(8.dp))
                Text("Play Now", color = Color.Black, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
fun MovieSection(title: String, movies: List<Movie>, onMovieClick: (Movie) -> Unit, isPortrait: Boolean = true) {
    if (movies.isEmpty()) return
    Column(modifier = Modifier.padding(bottom = 24.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = Color.White,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(start = 16.dp, bottom = 12.dp)
        )
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(movies) { movie ->
                MovieCard(movie, isPortrait) { onMovieClick(movie) }
            }
        }
    }
}

@Composable
fun MovieCard(movie: Movie, isPortrait: Boolean, onClick: () -> Unit) {
    val width = if (isPortrait) 130.dp else 220.dp
    val height = if (isPortrait) 190.dp else 130.dp
    Column(modifier = Modifier.width(width).clickable { onClick() }) {
        Card(
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier.size(width = width, height = height),
            elevation = CardDefaults.cardElevation(8.dp)
        ) {
            Box(Modifier.fillMaxSize()) {
                AsyncImage(
                    model = if (isPortrait) movie.posterUrl else movie.backdropUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(4.dp)
                        .background(Color.Black.copy(0.7f), RoundedCornerShape(4.dp))
                        .padding(horizontal = 4.dp, vertical = 2.dp)
                ) {
                    Text(movie.rating, color = Color.Yellow, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
        if (!isPortrait) {
            Spacer(Modifier.height(8.dp))
            Text(movie.title, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
        }
    }
}

// Background Component
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
