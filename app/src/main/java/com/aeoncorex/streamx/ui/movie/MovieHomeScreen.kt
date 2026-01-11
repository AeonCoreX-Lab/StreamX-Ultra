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
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.lerp
import androidx.navigation.NavController
import coil.compose.AsyncImage
import com.aeoncorex.streamx.data.MovieRepository
import com.aeoncorex.streamx.model.Movie
import com.aeoncorex.streamx.ui.home.CyberMeshBackground
import com.valentinilk.shimmer.shimmer
import java.net.URLEncoder
import kotlin.math.absoluteValue

@Composable
fun MovieHomeScreen(navController: NavController) {
    // Collect Data
    val moviesState = MovieRepository.getMoviesFlow().collectAsState(initial = emptyList())
    val allMovies = moviesState.value
    
    // Derived States
    val featuredMovies = remember(allMovies) { allMovies.filter { it.isFeatured }.take(5) }
    val topRatedMovies = remember(allMovies) { allMovies.sortedByDescending { it.rating }.take(10) }
    val categories = remember(allMovies) { allMovies.groupBy { it.category } }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        CyberMeshBackground() // Your existing background

        if (allMovies.isEmpty()) {
            LoadingShimmer()
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 100.dp)
            ) {
                // 1. Hero Pager (Netflix Style)
                item {
                    if (featuredMovies.isNotEmpty()) {
                        HeroCarousel(movies = featuredMovies, onPlay = { playMovie(navController, it.streamUrl) }, onDetail = { navController.navigate("movie_detail/${it.id}") })
                    }
                }

                // 2. Top 10 Row (Special UI)
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
        
        // Gradient Top Bar
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(80.dp)
                .background(Brush.verticalGradient(listOf(Color.Black.copy(0.8f), Color.Transparent)))
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
            color = Color.White.copy(0.5f), // Outline effect simulation
            modifier = Modifier.align(Alignment.BottomStart).offset(x = (-15).dp, y = 15.dp).graphicsLayer { alpha = 1f },
            style = LocalTextStyle.current.copy(
                shadow = androidx.compose.ui.graphics.Shadow(offset = androidx.compose.ui.geometry.Offset(2f, 2f), blurRadius = 4f)
            )
        )
        // Poster overlapping number
        AsyncImage(
            model = movie.posterUrl,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.padding(start = 25.dp).fillMaxSize().clip(RoundedCornerShape(8.dp))
        )
        // Top 10 SVG Badge (Simulated)
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
