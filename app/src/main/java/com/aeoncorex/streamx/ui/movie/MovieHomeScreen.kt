package com.aeoncorex.streamx.ui.movie

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.rounded.Info
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
import com.aeoncorex.streamx.ui.home.CyberMeshBackground
import java.net.URLEncoder

@Composable
fun MovieHomeScreen(navController: NavController) {
    val featuredMovie = remember { MovieRepository.getFeaturedMovie() }
    val categories = remember { MovieRepository.getMoviesByCategory() }
    val primaryColor = MaterialTheme.colorScheme.primary

    Box(modifier = Modifier.fillMaxSize()) {
        // Reuse your dynamic background
        CyberMeshBackground()

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 100.dp) // Space for BottomNav
        ) {
            // 1. Hero Banner (Netflix Style Big Header)
            item {
                HeroMovieSection(movie = featuredMovie, onPlay = {
                     playMovie(navController, it.streamUrl)
                })
            }

            // 2. Categories (Horizontal Rows)
            categories.forEach { (categoryName, movies) ->
                item {
                    Text(
                        text = categoryName,
                        color = Color.White,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(start = 16.dp, top = 24.dp, bottom = 12.dp)
                    )
                    
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        items(movies) { movie ->
                            MoviePosterCard(movie = movie) {
                                playMovie(navController, movie.streamUrl)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun HeroMovieSection(movie: Movie, onPlay: (Movie) -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(500.dp)
    ) {
        // Background Image
        AsyncImage(
            model = movie.coverUrl,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
        )

        // Gradient Overlay (Bottom to Top)
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color.Transparent,
                            Color.Black.copy(0.6f),
                            MaterialTheme.colorScheme.background // Seamless blend
                        )
                    )
                )
        )

        // Content
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = movie.title.uppercase(),
                style = MaterialTheme.typography.displaySmall,
                color = Color.White,
                fontWeight = FontWeight.Black,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
            
            Spacer(Modifier.height(8.dp))
            
            // Meta Tags
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(movie.year, color = Color.Green, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.width(12.dp))
                ContainerTag(movie.category)
                Spacer(Modifier.width(12.dp))
                Text(movie.rating, color = Color.Yellow, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }

            Spacer(Modifier.height(24.dp))

            // Buttons
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Button(
                    onClick = { onPlay(movie) },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.PlayArrow, null, tint = Color.Black)
                    Spacer(Modifier.width(8.dp))
                    Text("PLAY", color = Color.Black, fontWeight = FontWeight.Bold)
                }

                Button(
                    onClick = { /* Open Details */ },
                    colors = ButtonDefaults.buttonColors(containerColor = Color.White.copy(0.2f)),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Rounded.Info, null, tint = Color.White)
                    Spacer(Modifier.width(8.dp))
                    Text("INFO", color = Color.White, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
fun ContainerTag(text: String) {
    Box(
        modifier = Modifier
            .background(Color.White.copy(0.2f), RoundedCornerShape(4.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp)
    ) {
        Text(text, color = Color.White, fontSize = 10.sp)
    }
}

@Composable
fun MoviePosterCard(movie: Movie, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .width(140.dp)
            .aspectRatio(0.67f) // Standard Poster Ratio
            .clickable { onClick() },
        shape = RoundedCornerShape(12.dp),
        elevation = CardDefaults.cardElevation(8.dp)
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            AsyncImage(
                model = movie.posterUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
            // Optional: Title Overlay if image fails
        }
    }
}

fun playMovie(navController: NavController, url: String) {
    try {
        val encodedUrl = URLEncoder.encode(url, "UTF-8")
        navController.navigate("player/$encodedUrl")
    } catch (e: Exception) {
        e.printStackTrace()
    }
}
