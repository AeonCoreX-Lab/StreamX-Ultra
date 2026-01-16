package com.aeoncorex.streamx.ui.movie

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import coil.compose.AsyncImage
import java.net.URLEncoder

@Composable
fun MovieDetailsScreen(
    navController: NavController,
    movieId: Int,
    movieType: String // "MOVIE" or "SERIES"
) {
    val type = if (movieType == "MOVIE") MovieType.MOVIE else MovieType.SERIES
    var details by remember { mutableStateOf<FullMovieDetails?>(null) }
    var isLoading by remember { mutableStateOf(true) }
    val context = LocalContext.current

    LaunchedEffect(movieId) {
        details = MovieRepository.getFullDetails(movieId, type)
        isLoading = false
    }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        if (isLoading) {
            CircularProgressIndicator(modifier = Modifier.align(Alignment.Center), color = Color.Red)
        } else {
            details?.let { movie ->
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    // 1. HEADER (Backdrop + Buttons)
                    item {
                        Box(modifier = Modifier.fillMaxWidth().height(450.dp)) {
                            AsyncImage(
                                model = movie.basic.backdropUrl,
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize()
                            )
                            // Gradient Overlay
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(
                                        Brush.verticalGradient(
                                            colors = listOf(Color.Transparent, Color.Black.copy(0.6f), Color.Black)
                                        )
                                    )
                            )
                            
                            // Top Bar
                            IconButton(
                                onClick = { navController.popBackStack() },
                                modifier = Modifier.padding(top = 40.dp, start = 16.dp)
                            ) {
                                Icon(Icons.Default.ArrowBack, "Back", tint = Color.White)
                            }

                            // Info Overlay
                            Column(
                                modifier = Modifier
                                    .align(Alignment.BottomStart)
                                    .padding(16.dp)
                            ) {
                                Text(
                                    text = movie.basic.title.uppercase(),
                                    style = MaterialTheme.typography.headlineMedium,
                                    fontWeight = FontWeight.Black,
                                    color = Color.White
                                )
                                Spacer(Modifier.height(8.dp))
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text("${movie.basic.rating} Match", color = Color(0xFF46D369), fontWeight = FontWeight.Bold)
                                    Spacer(Modifier.width(12.dp))
                                    Text(movie.basic.year, color = Color.White)
                                    Spacer(Modifier.width(12.dp))
                                    Box(Modifier.background(Color.DarkGray, RoundedCornerShape(4.dp)).padding(horizontal = 4.dp)) {
                                        Text(if(type == MovieType.MOVIE) "HD" else "TV-MA", color = Color.White, fontSize = 12.sp)
                                    }
                                    Spacer(Modifier.width(12.dp))
                                    Text(movie.runtime, color = Color.Gray)
                                }
                                Spacer(Modifier.height(16.dp))
                                
                                // Action Buttons
                                Row(modifier = Modifier.fillMaxWidth()) {
                                    Button(
                                        onClick = {
                                            // Play Logic: Use Ultimate Player
                                            val streamUrl = MovieRepository.getStreamUrl(movie.basic.id, movie.basic.type)
                                            val encodedUrl = URLEncoder.encode(streamUrl, "UTF-8")
                                            navController.navigate("movie_player/$encodedUrl")
                                        },
                                        colors = ButtonDefaults.buttonColors(containerColor = Color.White),
                                        shape = RoundedCornerShape(4.dp),
                                        modifier = Modifier.weight(1f).height(45.dp)
                                    ) {
                                        Icon(Icons.Default.PlayArrow, null, tint = Color.Black)
                                        Spacer(Modifier.width(8.dp))
                                        Text("Play", color = Color.Black, fontWeight = FontWeight.Bold)
                                    }
                                    Spacer(Modifier.width(12.dp))
                                    // Trailer Button
                                    if (movie.trailerKey != null) {
                                        Button(
                                            onClick = {
                                                val intent = Intent(Intent.ACTION_VIEW, Uri.parse("vnd.youtube:${movie.trailerKey}"))
                                                context.startActivity(intent)
                                            },
                                            colors = ButtonDefaults.buttonColors(containerColor = Color.White.copy(0.2f)),
                                            shape = RoundedCornerShape(4.dp),
                                            modifier = Modifier.weight(1f).height(45.dp)
                                        ) {
                                            Text("Trailer", color = Color.White, fontWeight = FontWeight.Bold)
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // 2. SYNOPSIS
                    item {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(movie.basic.description, color = Color.White, lineHeight = 20.sp)
                            Spacer(Modifier.height(12.dp))
                            Text("Genres: ${movie.genres.joinToString(", ")}", color = Color.Gray, fontSize = 12.sp)
                            Text("Director: ${movie.director}", color = Color.Gray, fontSize = 12.sp)
                        }
                    }

                    // 3. CAST
                    item {
                        Text("Cast", color = Color.White, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 16.dp, bottom = 8.dp))
                        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            items(movie.cast) { actor ->
                                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(80.dp)) {
                                    AsyncImage(
                                        model = actor.imageUrl,
                                        contentDescription = null,
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier.size(70.dp).clip(CircleShape).border(1.dp, Color.Gray, CircleShape)
                                    )
                                    Spacer(Modifier.height(4.dp))
                                    Text(actor.name, color = Color.LightGray, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(actor.role, color = Color.DarkGray, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }
                        }
                        Spacer(Modifier.height(24.dp))
                    }

                    // 4. RECOMMENDATIONS
                    item {
                        if (movie.recommendations.isNotEmpty()) {
                            MovieSection("More Like This", movie.recommendations, navController, isPortrait = true)
                        }
                    }
                }
            }
        }
    }
}
