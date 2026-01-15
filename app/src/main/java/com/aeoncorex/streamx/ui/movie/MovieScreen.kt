package com.aeoncorex.streamx.ui.movie

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.rounded.*
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
import androidx.navigation.NavController
import coil.compose.AsyncImage
import com.aeoncorex.streamx.ui.home.CyberMeshBackground // Ensure this exists in LiveTVScreen.kt
import com.aeoncorex.streamx.ui.home.GlassWhite      // Ensure this exists in LiveTVScreen.kt
import kotlinx.coroutines.launch
import java.net.URLEncoder

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MovieScreen(navController: NavController) {
    val scrollState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val primaryColor = MaterialTheme.colorScheme.primary
    val secondaryColor = MaterialTheme.colorScheme.secondary

    // Data States
    var trendingList by remember { mutableStateOf<List<Movie>>(emptyList()) }
    var popularList by remember { mutableStateOf<List<Movie>>(emptyList()) }
    var seriesList by remember { mutableStateOf<List<Movie>>(emptyList()) }
    var actionList by remember { mutableStateOf<List<Movie>>(emptyList()) }
    var sciFiList by remember { mutableStateOf<List<Movie>>(emptyList()) }
    var featuredMovie by remember { mutableStateOf<Movie?>(null) }
    var isLoading by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        isLoading = true
        // Fetching Data safely using Repo with Secret Key
        trendingList = MovieRepository.getTrending()
        featuredMovie = trendingList.firstOrNull()

        popularList = MovieRepository.getPopularMovies()
        seriesList = MovieRepository.getTopSeries()
        actionList = MovieRepository.getActionMovies()
        sciFiList = MovieRepository.getSciFiMovies()
        isLoading = false
    }

    // Reuse App Drawer (Ensure AppDrawer composable is available)
    // For now assuming existing Drawer structure
    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        // 1. Futuristic Background
        CyberMeshBackground()

        if (isLoading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = primaryColor)
            }
        } else {
            LazyColumn(
                state = scrollState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 100.dp)
            ) {
                // --- HERO SECTION ---
                featuredMovie?.let { movie ->
                    item {
                        Box(modifier = Modifier.fillMaxWidth().height(550.dp)) {
                            AsyncImage(
                                model = movie.backdropUrl,
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize()
                            )
                            // Gradient Overlay
                            Box(
                                modifier = Modifier.fillMaxSize().background(
                                    Brush.verticalGradient(
                                        colors = listOf(Color.Transparent, Color.Black.copy(0.2f), Color.Black)
                                    )
                                )
                            )
                            // Content
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
                                // Buttons
                                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                                    Button(
                                        onClick = {
                                            val streamUrl = MovieRepository.getStreamUrl(movie.id, movie.type)
                                            val encodedUrl = URLEncoder.encode(streamUrl, "UTF-8")
                                            navController.navigate("movie_player/$encodedUrl")
                                        },
                                        colors = ButtonDefaults.buttonColors(containerColor = Color.White),
                                        shape = RoundedCornerShape(8.dp),
                                        modifier = Modifier.weight(1f).height(45.dp)
                                    ) {
                                        Icon(Icons.Rounded.PlayArrow, null, tint = Color.Black)
                                        Spacer(Modifier.width(8.dp))
                                        Text("Play", color = Color.Black, fontWeight = FontWeight.Bold)
                                    }
                                    Button(
                                        onClick = { /* Info Click */ },
                                        colors = ButtonDefaults.buttonColors(containerColor = Color.White.copy(0.2f)),
                                        shape = RoundedCornerShape(8.dp),
                                        modifier = Modifier.weight(1f).height(45.dp)
                                    ) {
                                        Icon(Icons.Rounded.Info, null, tint = Color.White)
                                        Spacer(Modifier.width(8.dp))
                                        Text("Info", color = Color.White, fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                        }
                    }
                }

                // --- CATEGORY LISTS ---
                item { MovieSection("Trending Now", trendingList, navController) }
                item { MovieSection("Popular Movies", popularList, navController) }
                item { MovieSection("Top Rated Series", seriesList, navController, isPortrait = false) }
                item { MovieSection("Action Blockbusters", actionList, navController) }
                item { MovieSection("Sci-Fi & Cyberpunk", sciFiList, navController) }
            }
        }

        // 2. HEADER (Fixed at Top)
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
                        "MOVIES",
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
                    IconButton(onClick = { scope.launch { drawerState.open() } }) { // Ensure Drawer is passed if needed
                         Icon(Icons.Default.Menu, "Menu", tint = Color.White)
                    }
                },
                actions = {
                    IconButton(onClick = { /* Search */ }) {
                        Icon(Icons.Default.Search, "Search", tint = Color.White)
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor = Color.Transparent)
            )
        }
    }
}

@Composable
fun MovieSection(title: String, movies: List<Movie>, navController: NavController, isPortrait: Boolean = true) {
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
                MovieCard(movie, isPortrait) {
                    val streamUrl = MovieRepository.getStreamUrl(movie.id, movie.type)
                    val encodedUrl = URLEncoder.encode(streamUrl, "UTF-8")
                    navController.navigate("movie_player/$encodedUrl")
                }
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
