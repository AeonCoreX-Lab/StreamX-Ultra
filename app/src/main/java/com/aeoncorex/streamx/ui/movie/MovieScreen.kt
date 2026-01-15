package com.aeoncorex.streamx.ui.movie

import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
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
import java.net.URLEncoder

// Color Definition from LiveTVScreen
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

    // Collecting Data from ViewModel
    val trendingList by viewModel.trending.collectAsState()
    val popularList by viewModel.popular.collectAsState()
    val seriesList by viewModel.series.collectAsState()
    val actionList by viewModel.action.collectAsState()
    val sciFiList by viewModel.sciFi.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()

    val featuredMovie = trendingList.firstOrNull()

    // Reuse App Drawer (Ensure AppDrawer composable is available in your project, likely in LiveTVScreen or a shared file)
    // If AppDrawer is in LiveTVScreen.kt, you might need to move it to a shared file or copy it here.
    // For now, assuming you handle the Drawer content similarly to LiveTV.

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            // Note: If AppDrawer is not shared, you might need to copy the AppDrawer code from LiveTVScreen here
            // or better, move AppDrawer to a common file like 'DrawerComponents.kt'.
            // For this code to work immediately, I am assuming AppDrawer is accessible or you will implement it.
            // If it shows red, copy the AppDrawer function from LiveTVScreen.kt to the bottom of this file.
             com.aeoncorex.streamx.ui.home.AppDrawer(navController) { scope.launch { drawerState.close() } }
        }
    ) {
        Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
            
            // 1. ULTRA BACKGROUND (Same as LiveTV)
            CyberMeshBackground()

            // 2. Main Content
            if (isLoading) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = primaryColor)
                }
            } else {
                LazyColumn(
                    state = scrollState,
                    modifier = Modifier.fillMaxSize(),
                    // Top padding added so content doesn't hide behind the floating header
                    contentPadding = PaddingValues(top = 80.dp, bottom = 100.dp) 
                ) {
                    // --- HERO SECTION ---
                    featuredMovie?.let { movie ->
                        item {
                            HeroSection(movie = movie, navController = navController)
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

            // 3. HEADER (Fixed at Top - Same style as LiveTV)
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
                            "STREAMX", // Same App Name
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
                        IconButton(onClick = { /* Implement Search if needed */ }) {
                            Icon(Icons.Default.Search, "Search", tint = Color.White)
                        }
                    },
                    colors = TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor = Color.Transparent)
                )
            }
        }
    }
}

@Composable
fun HeroSection(movie: Movie, navController: NavController) {
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
            
            // Play Button
            Button(
                onClick = {
                    val streamUrl = MovieRepository.getStreamUrl(movie.id, movie.type)
                    val encodedUrl = URLEncoder.encode(streamUrl, "UTF-8")
                    navController.navigate("movie_player/$encodedUrl")
                },
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
                // Rating badge
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

// --- SHARED UI COMPONENTS (Copied from LiveTVScreen to ensure consistency) ---

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
