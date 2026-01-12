package com.aeoncorex.streamx.ui.movie

import android.net.Uri
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import java.net.URLEncoder

@Composable
fun MovieDetailScreen(navController: NavController, movieId: String) {
    var movie by remember { mutableStateOf<Movie?>(null) }
    var isLoading by remember { mutableStateOf(true) }

    LaunchedEffect(movieId) {
        val allMovies = MovieRepository.getMovies()
        movie = allMovies.find { it.id == movieId }
        isLoading = false
    }

    if (isLoading) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = Color(0xFF00E5FF))
        }
    } else {
        movie?.let { m ->
            Column(modifier = Modifier.fillMaxSize().background(Color.Black).verticalScroll(rememberScrollState())) {
                // Poster/Banner
                Box(Modifier.height(300.dp).fillMaxWidth()) {
                    AsyncImage(model = m.coverUrl, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black))))
                    
                    IconButton(onClick = { navController.popBackStack() }, modifier = Modifier.padding(top = 40.dp, start = 10.dp)) {
                        Icon(Icons.Default.ArrowBack, null, tint = Color.White)
                    }
                }

                Column(Modifier.padding(16.dp)) {
                    Text(m.title, color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(m.year, color = Color.Gray)
                        Spacer(Modifier.width(12.dp))
                        Text(m.rating, color = Color(0xFF00E5FF), fontWeight = FontWeight.Bold)
                    }
                    
                    Spacer(Modifier.height(20.dp))
                    
                    Button(
                        onClick = {
                            val encodedUrl = URLEncoder.encode(m.streamUrl, "UTF-8")
                            navController.navigate("player/$encodedUrl")
                        },
                        modifier = Modifier.fillMaxWidth().height(50.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color.White),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Icon(Icons.Default.PlayArrow, null, tint = Color.Black)
                        Text("WATCH NOW", color = Color.Black, fontWeight = FontWeight.Bold)
                    }

                    Spacer(Modifier.height(24.dp))
                    Text("Storyline", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    Text(m.description, color = Color.LightGray, fontSize = 14.sp, modifier = Modifier.padding(top = 8.dp))
                }
            }
        }
    }
}
