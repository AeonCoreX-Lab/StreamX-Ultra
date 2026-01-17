package com.aeoncorex.streamx.ui.movie

import android.annotation.SuppressLint
import android.webkit.WebChromeClient
import android.webkit.WebView
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
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.navigation.NavController
import coil.compose.AsyncImage
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

@Composable
fun MovieDetailsScreen(
    navController: NavController,
    movieId: Int,
    type: String
) {
    val movieDetails by produceState<FullMovieDetails?>(initialValue = null, key1 = movieId) {
        value = MovieRepository.getFullDetails(movieId, type)
    }

    var showServerDialog by remember { mutableStateOf(false) }
    var showTrailerDialog by remember { mutableStateOf(false) }

    Box(modifier = Modifier.fillMaxSize().background(Color(0xFF0F0F15))) {
        if (movieDetails == null) {
            CircularProgressIndicator(modifier = Modifier.align(Alignment.Center), color = Color.Cyan)
        } else {
            val movie = movieDetails!!
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                // 1. HERO SECTION
                item {
                    Box(modifier = Modifier.height(450.dp).fillMaxWidth()) {
                        AsyncImage(
                            model = movie.basic.posterUrl,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                        Box(modifier = Modifier.fillMaxSize().background(
                            Brush.verticalGradient(listOf(Color.Transparent, Color(0xFF0F0F15)))
                        ))
                        
                        // Header Bar with Back & Settings
                        Row(
                            Modifier.fillMaxWidth().padding(top = 40.dp, start = 16.dp, end = 16.dp),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            IconButton(onClick = { navController.popBackStack() }) {
                                Icon(Icons.Default.ArrowBack, null, tint = Color.White)
                            }
                            // UPDATED: Navigates to the new Movie Settings Screen
                            IconButton(onClick = { navController.navigate("movie_settings") }) {
                                Icon(Icons.Default.Settings, null, tint = Color.White)
                            }
                        }

                        // Play Button
                        IconButton(
                            onClick = { showServerDialog = true },
                            modifier = Modifier.align(Alignment.Center)
                                .size(70.dp)
                                .background(Color.Red.copy(0.9f), CircleShape)
                        ) {
                            Icon(Icons.Default.PlayArrow, null, tint = Color.White, modifier = Modifier.size(40.dp))
                        }
                    }
                }

                // 2. INFO SECTION (Merged TMDB + OMDb)
                item {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(movie.basic.title, color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(8.dp))
                        
                        // Ratings Row
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Star, null, tint = Color(0xFFFFD700), modifier = Modifier.size(18.dp))
                            Text(" ${movie.basic.rating}", color = Color.White, fontSize = 12.sp)
                            Spacer(Modifier.width(16.dp))
                            Text("IMDb: ${movie.imdbRating}", color = Color(0xFFF5C518), fontWeight = FontWeight.Bold, fontSize = 12.sp)
                            Spacer(Modifier.width(16.dp))
                            Box(modifier = Modifier.border(1.dp, Color.Cyan, RoundedCornerShape(4.dp)).padding(horizontal = 4.dp)) {
                                Text(movie.ageRating, color = Color.Cyan, fontSize = 10.sp)
                            }
                        }

                        // Trailer Button
                        if (movie.trailerKey != null) {
                            Spacer(Modifier.height(16.dp))
                            Button(
                                onClick = { showTrailerDialog = true },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF333333)),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("Watch Trailer", color = Color.White)
                            }
                        }

                        Spacer(Modifier.height(16.dp))
                        Text(movie.basic.description, color = Color.Gray, fontSize = 14.sp, lineHeight = 20.sp)
                        
                        if(movie.awards != "No Awards" && movie.awards != "N/A") {
                            Spacer(Modifier.height(16.dp))
                            Text("🏆 ${movie.awards}", color = Color.Cyan, fontSize = 12.sp)
                        }
                    }
                }

                // 3. CAST SECTION
                item {
                     Text("  Cast", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp, modifier = Modifier.padding(16.dp))
                     LazyRow(contentPadding = PaddingValues(horizontal = 16.dp)) {
                         items(movie.cast) { cast ->
                             Column(
                                 horizontalAlignment = Alignment.CenterHorizontally,
                                 modifier = Modifier.width(100.dp).padding(end = 8.dp)
                             ) {
                                 AsyncImage(
                                     model = cast.imageUrl, contentDescription = null,
                                     contentScale = ContentScale.Crop,
                                     modifier = Modifier.size(80.dp).clip(CircleShape)
                                 )
                                 Text(cast.name, color = Color.LightGray, fontSize = 11.sp, maxLines = 1)
                             }
                         }
                     }
                }
                
                // 4. RECOMMENDATIONS
                item {
                    Spacer(Modifier.height(24.dp))
                    Text("  You may also like", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp, modifier = Modifier.padding(16.dp))
                    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp)) {
                        items(movie.recommendations) { rec ->
                            AsyncImage(
                                model = rec.posterUrl, contentDescription = null,
                                modifier = Modifier.width(120.dp).height(180.dp).clip(RoundedCornerShape(8.dp))
                                    .clickable { navController.navigate("movie_detail/${rec.id}/${if(rec.type==MovieType.MOVIE) "MOVIE" else "SERIES"}") }
                                    .padding(end = 8.dp),
                                contentScale = ContentScale.Crop
                            )
                        }
                    }
                    Spacer(Modifier.height(50.dp))
                }
            }

            // --- SERVER SELECTION DIALOG ---
            if (showServerDialog) {
                AlertDialog(
                    onDismissRequest = { showServerDialog = false },
                    containerColor = Color(0xFF1E1E2C),
                    title = { Text("Select Server", color = Color.White) },
                    text = {
                        Column {
                            movie.servers.forEach { server ->
                                Card(
                                    colors = CardDefaults.cardColors(containerColor = Color(0xFF2B2B38)),
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable {
                                        showServerDialog = false
                                        val encodedUrl = URLEncoder.encode(server.url, StandardCharsets.UTF_8.toString())
                                        navController.navigate("movie_player/$encodedUrl")
                                    }
                                ) {
                                    Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                                        Text(server.name, color = Color.White)
                                        Text(server.quality, color = Color.Cyan, fontSize = 10.sp)
                                    }
                                }
                            }
                        }
                    },
                    confirmButton = { TextButton(onClick = { showServerDialog = false }) { Text("Cancel", color = Color.Red) } }
                )
            }

            // --- IN-APP TRAILER DIALOG ---
            if (showTrailerDialog && movie.trailerKey != null) {
                Dialog(onDismissRequest = { showTrailerDialog = false }) {
                    Box(modifier = Modifier.fillMaxWidth().height(250.dp).background(Color.Black)) {
                        AndroidView(factory = { ctx ->
                            WebView(ctx).apply {
                                settings.javaScriptEnabled = true
                                webChromeClient = WebChromeClient()
                                loadUrl("https://www.youtube.com/embed/${movie.trailerKey}?autoplay=1")
                            }
                        }, modifier = Modifier.fillMaxSize())
                    }
                }
            }
        }
    }
}
