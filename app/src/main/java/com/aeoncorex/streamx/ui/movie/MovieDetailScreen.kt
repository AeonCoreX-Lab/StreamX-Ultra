package com.aeoncorex.streamx.ui.movie

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import coil.compose.AsyncImage
import com.aeoncorex.streamx.data.MovieRepository
import com.aeoncorex.streamx.model.Movie
import java.net.URLEncoder

@Composable
fun MovieDetailScreen(navController: NavController, movieId: String) {
    var movie by remember { mutableStateOf<Movie?>(null) }
    var relatedMovies by remember { mutableStateOf<List<Movie>>(emptyList()) }
    val scrollState = rememberScrollState()

    LaunchedEffect(movieId) {
        val movies = MovieRepository.getMoviesFromFirestore()
        movie = movies.find { it.id == movieId }
        // Filter "More Like This" based on category, excluding current movie
        relatedMovies = movies.filter { it.category == movie?.category && it.id != movieId }.take(10)
    }

    if (movie != null) {
        val m = movie!!
        Box(modifier = Modifier.fillMaxSize().background(Color(0xFF0F0F0F))) {
            
            // 1. Parallax Header Image
            AsyncImage(
                model = m.coverUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(450.dp)
                    .graphicsLayer {
                        translationY = scrollState.value * 0.5f // Parallax Effect
                        alpha = 1f - (scrollState.value / 1000f) // Fade out
                    }
            )

            // Gradient Overlay
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(460.dp)
                    .background(Brush.verticalGradient(
                        colors = listOf(Color.Transparent, Color(0xFF0F0F0F).copy(0.6f), Color(0xFF0F0F0F))
                    ))
            )

            // 2. Scrollable Content
            Column(modifier = Modifier.fillMaxSize().verticalScroll(scrollState)) {
                Spacer(Modifier.height(350.dp)) // Push content down

                Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                    // Title & Meta
                    Text(m.title, fontSize = 32.sp, fontWeight = FontWeight.Black, color = Color.White, lineHeight = 38.sp)
                    Spacer(Modifier.height(8.dp))
                    
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(m.year, color = Color.Green, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.width(12.dp))
                        Box(Modifier.background(Color.DarkGray, RoundedCornerShape(4.dp)).padding(horizontal = 6.dp, vertical = 2.dp)) {
                            Text(m.rating, color = Color.White, fontSize = 12.sp)
                        }
                        Spacer(Modifier.width(12.dp))
                        Text("4K HDR", color = Color.LightGray, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }

                    // Action Buttons
                    Spacer(Modifier.height(24.dp))
                    Button(
                        onClick = {
                            val encodedUrl = URLEncoder.encode(m.streamUrl, "UTF-8")
                            navController.navigate("player/$encodedUrl")
                        },
                        modifier = Modifier.fillMaxWidth().height(50.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color.White),
                        shape = RoundedCornerShape(4.dp)
                    ) {
                        Icon(Icons.Default.PlayArrow, null, tint = Color.Black)
                        Spacer(Modifier.width(8.dp))
                        Text("Play", color = Color.Black, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    }
                    
                    Spacer(Modifier.height(8.dp))
                    
                    Button(
                        onClick = { /* Download Logic */ },
                        modifier = Modifier.fillMaxWidth().height(50.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2A2A2A)),
                        shape = RoundedCornerShape(4.dp)
                    ) {
                        Icon(Icons.Default.Download, null, tint = Color.White)
                        Spacer(Modifier.width(8.dp))
                        Text("Download", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    }

                    // Synopsis
                    Spacer(Modifier.height(24.dp))
                    Text(m.description, color = Color.White.copy(0.8f), fontSize = 15.sp, lineHeight = 22.sp)
                    
                    // Interactive Icons Row
                    Spacer(Modifier.height(24.dp))
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
                        ActionIcon(Icons.Default.Add, "My List")
                        Spacer(Modifier.width(32.dp))
                        ActionIcon(Icons.Outlined.ThumbUp, "Rate")
                        Spacer(Modifier.width(32.dp))
                        ActionIcon(Icons.Default.Share, "Share")
                    }

                    // 3. More Like This Section
                    if (relatedMovies.isNotEmpty()) {
                        Spacer(Modifier.height(32.dp))
                        Text("More Like This", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color.White)
                        Spacer(Modifier.height(16.dp))
                        
                        // Grid Layout simulation using Rows
                        val chunks = relatedMovies.chunked(3)
                        chunks.forEach { rowMovies ->
                            Row(modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                                rowMovies.forEach { rel ->
                                    AsyncImage(
                                        model = rel.posterUrl,
                                        contentDescription = null,
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier
                                            .width(110.dp)
                                            .height(160.dp)
                                            .clip(RoundedCornerShape(4.dp))
                                            .background(Color.DarkGray)
                                            .clickable { navController.navigate("movie_detail/${rel.id}") }
                                    )
                                }
                                // Fill empty space if row has less than 3 items
                                repeat(3 - rowMovies.size) { Spacer(Modifier.width(110.dp)) }
                            }
                        }
                    }
                    Spacer(Modifier.height(40.dp))
                }
            }

            // Top Bar Back Button
            IconButton(
                onClick = { navController.popBackStack() },
                modifier = Modifier.padding(top = 40.dp, start = 8.dp)
            ) {
                Icon(Icons.Default.ArrowBack, "Back", tint = Color.White, modifier = Modifier.size(28.dp))
            }
        }
    }
}

@Composable
fun ActionIcon(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(icon, null, tint = Color.White, modifier = Modifier.size(28.dp))
        Spacer(Modifier.height(4.dp))
        Text(label, color = Color.Gray, fontSize = 12.sp)
    }
}