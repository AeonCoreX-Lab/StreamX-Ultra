package com.aeoncorex.streamx.ui.movie

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
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
        val movies = MovieRepository.getMoviesFromFirestore()
        movie = movies.find { it.id == movieId }
        isLoading = false
    }

    if (isLoading) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = Color.White)
        }
    } else if (movie != null) {
        Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                // Cover Image with Back Button
                Box(modifier = Modifier.fillMaxWidth().height(400.dp)) {
                    AsyncImage(
                        model = movie!!.coverUrl,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                    Box(modifier = Modifier.fillMaxSize().background(
                        Brush.verticalGradient(listOf(Color.Transparent, Color.Black))
                    ))
                    IconButton(
                        onClick = { navController.popBackStack() },
                        modifier = Modifier.padding(top = 40.dp, start = 16.dp)
                    ) {
                        Icon(Icons.Default.ArrowBack, "Back", tint = Color.White)
                    }
                }

                // Movie Info
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(movie!!.title, fontSize = 28.sp, fontWeight = FontWeight.Bold, color = Color.White)
                    Row(modifier = Modifier.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(movie!!.year, color = Color.Gray)
                        Spacer(Modifier.width(12.dp))
                        Surface(color = Color.DarkGray, shape = RoundedCornerShape(4.dp)) {
                            Text(movie!!.rating, color = Color.White, modifier = Modifier.padding(horizontal = 6.dp), fontSize = 12.sp)
                        }
                        Spacer(Modifier.width(12.dp))
                        Text(movie!!.category, color = MaterialTheme.colorScheme.primary)
                    }

                    // Play Button
                    Button(
                        onClick = {
                            val encodedUrl = URLEncoder.encode(movie!!.streamUrl, "UTF-8")
                            navController.navigate("player/$encodedUrl")
                        },
                        modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color.White),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Icon(Icons.Default.PlayArrow, null, tint = Color.Black)
                        Text("PLAY NOW", color = Color.Black, fontWeight = FontWeight.Bold)
                    }

                    Text("Storyline", fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
                    Spacer(Modifier.height(8.dp))
                    Text(movie!!.description, color = Color.LightGray, lineHeight = 22.sp)
                }
            }
        }
    }
}
