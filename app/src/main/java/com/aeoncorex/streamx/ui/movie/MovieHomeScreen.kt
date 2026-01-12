package com.aeoncorex.streamx.ui.movie

import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import coil.compose.AsyncImage
import com.aeoncorex.streamx.data.MovieRepository
import com.aeoncorex.streamx.model.Movie

@Composable
fun MovieHomeScreen(navController: NavController) {
    var allMovies by remember { mutableStateOf<List<Movie>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }

    // Supabase theke data load kora
    LaunchedEffect(Unit) {
        allMovies = MovieRepository.getMovies()
        isLoading = false
    }

    if (isLoading) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = Color(0xFF00E5FF))
        }
    } else {
        val featuredMovies = allMovies.take(5) // Prothom 5 ti movie featured hisebe

        LazyColumn(
            modifier = Modifier.fillMaxSize().background(Color.Black)
        ) {
            // Hero Section (Featured Movies)
            item {
                if (featuredMovies.isNotEmpty()) {
                    FeaturedSection(featuredMovies, navController)
                }
            }

            // Categories (Automatic grouping)
            val categories = allMovies.groupBy { it.category }
            categories.forEach { (category, movies) ->
                item {
                    CategorySection(category.ifEmpty { "Trending" }, movies, navController)
                }
            }
            
            item { Spacer(Modifier.height(80.dp)) }
        }
    }
}

@Composable
fun FeaturedSection(movies: List<Movie>, navController: NavController) {
    val pagerState = rememberPagerState(pageCount = { movies.size })
    
    Box(modifier = Modifier.height(450.dp).fillMaxWidth()) {
        HorizontalPager(state = pagerState) { page ->
            val movie = movies[page]
            Box(Modifier.fillMaxSize().clickable { navController.navigate("movie_detail/${movie.id}") }) {
                AsyncImage(
                    model = movie.coverUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
                Box(
                    Modifier.fillMaxSize().background(
                        Brush.verticalGradient(listOf(Color.Transparent, Color.Black))
                    )
                )
                Column(
                    Modifier.align(Alignment.BottomStart).padding(16.dp)
                ) {
                    Text(movie.title, color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Bold)
                    Text(movie.rating, color = Color(0xFF00E5FF), fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
fun CategorySection(title: String, movies: List<Movie>, navController: NavController) {
    Column {
        Text(title, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(16.dp))
        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            items(movies) { movie ->
                Card(
                    modifier = Modifier.width(130.dp).height(190.dp).clickable { navController.navigate("movie_detail/${movie.id}") },
                    shape = RoundedCornerShape(8.dp)
                ) {
                    AsyncImage(model = movie.posterUrl, contentDescription = null, contentScale = ContentScale.Crop)
                }
            }
        }
    }
}
