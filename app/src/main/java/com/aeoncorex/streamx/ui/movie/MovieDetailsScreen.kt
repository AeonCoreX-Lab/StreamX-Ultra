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
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.Settings
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MovieDetailsScreen(
    navController: NavController,
    movieId: Int,
    movieType: String // "MOVIE" or "SERIES"
) {
    // 1. Determine Type
    val type = if (movieType.equals("MOVIE", ignoreCase = true)) MovieType.MOVIE else MovieType.SERIES
    
    // 2. State Holders
    var details by remember { mutableStateOf<FullMovieDetails?>(null) }
    var isLoading by remember { mutableStateOf(true) }
    
    // Series Specific State
    var selectedSeason by remember { mutableIntStateOf(1) }
    var episodes by remember { mutableStateOf<List<EpisodeDto>>(emptyList()) }
    var isEpisodesLoading by remember { mutableStateOf(false) }

    val context = LocalContext.current
    
    // 3. Fetch Full Details
    LaunchedEffect(movieId) {
        details = MovieRepository.getFullDetails(movieId, type)
        isLoading = false
    }

    // 4. Fetch Episodes when Season Changes (Only for Series)
    LaunchedEffect(selectedSeason, details) {
        if (type == MovieType.SERIES && details != null) {
            isEpisodesLoading = true
            // Fetch episodes using the new Repository function
            episodes = MovieRepository.getEpisodes(movieId, selectedSeason)
            isEpisodesLoading = false
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        if (isLoading) {
            CircularProgressIndicator(modifier = Modifier.align(Alignment.Center), color = Color.Red)
        } else {
            details?.let { movie ->
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    
                    // --- HEADER SECTION (Backdrop + Buttons) ---
                    item {
                        Box(modifier = Modifier.fillMaxWidth().height(500.dp)) {
                            // Backdrop Image
                            AsyncImage(
                                model = movie.basic.backdropUrl,
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize()
                            )
                            
                            // Gradient Overlay (Netflix Style)
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(
                                        Brush.verticalGradient(
                                            colors = listOf(Color.Transparent, Color.Black.copy(0.6f), Color.Black)
                                        )
                                    )
                            )
                            
                            // TOP BAR: Back Button & Settings Button
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 40.dp, start = 16.dp, end = 16.dp),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                // Back Button
                                IconButton(
                                    onClick = { navController.popBackStack() },
                                    modifier = Modifier.background(Color.Black.copy(0.5f), CircleShape)
                                ) {
                                    Icon(Icons.Default.ArrowBack, "Back", tint = Color.White)
                                }
                                
                                // Settings Button
                                IconButton(
                                    onClick = { navController.navigate("movie_settings") },
                                    modifier = Modifier.background(Color.Black.copy(0.5f), CircleShape)
                                ) {
                                    Icon(Icons.Outlined.Settings, "Settings", tint = Color.White)
                                }
                            }

                            // Movie Info Overlay
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
                                
                                // Metadata Row
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
                                
                                // BUTTONS ROW
                                Row(modifier = Modifier.fillMaxWidth()) {
                                    // PLAY BUTTON
                                    Button(
                                        onClick = {
                                            if (type == MovieType.MOVIE) {
                                                navController.navigate("server_selection/${movie.basic.id}/${movie.basic.title}/MOVIE/0/0")
                                            } else {
                                                // Default to S1E1 if playing from main button
                                                navController.navigate("server_selection/${movie.basic.id}/${movie.basic.title}/SERIES/1/1")
                                            }
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
                                    
                                    // TRAILER BUTTON
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

                    // --- SYNOPSIS ---
                    item {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(movie.basic.description, color = Color.White, lineHeight = 20.sp)
                            Spacer(Modifier.height(12.dp))
                            Text("Genres: ${movie.genres.joinToString(", ")}", color = Color.Gray, fontSize = 12.sp)
                            Text("Director: ${movie.director}", color = Color.Gray, fontSize = 12.sp)
                        }
                    }

                    // --- SERIES EPISODE SELECTOR ---
                    if (type == MovieType.SERIES && movie.seasons.isNotEmpty()) {
                        item {
                            Column(Modifier.padding(horizontal = 16.dp)) {
                                Divider(color = Color.Gray.copy(0.3f), thickness = 1.dp)
                                Spacer(Modifier.height(16.dp))
                                
                                // Season Selector (Chips)
                                Text("Episodes", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                                LazyRow(Modifier.padding(vertical = 12.dp)) {
                                    items(movie.seasons) { season ->
                                        val isSelected = season.seasonNumber == selectedSeason
                                        FilterChip(
                                            selected = isSelected,
                                            onClick = { selectedSeason = season.seasonNumber },
                                            label = { Text("Season ${season.seasonNumber}") },
                                            colors = FilterChipDefaults.filterChipColors(
                                                containerColor = Color.DarkGray,
                                                labelColor = Color.LightGray,
                                                selectedContainerColor = Color.Red,
                                                selectedLabelColor = Color.White,
                                                disabledContainerColor = Color.DarkGray,
                                                disabledLabelColor = Color.Gray
                                            ),
                                            // FIX: Explicitly passing enabled and selected to satisfy strict compiler checks
                                            border = FilterChipDefaults.filterChipBorder(
                                                enabled = true,
                                                selected = isSelected,
                                                borderColor = Color.Transparent,
                                                selectedBorderColor = Color.Transparent,
                                                disabledBorderColor = Color.Transparent,
                                                disabledSelectedBorderColor = Color.Transparent
                                            ),
                                            modifier = Modifier.padding(end = 8.dp)
                                        )
                                    }
                                }
                                
                                // Episode List
                                if (isEpisodesLoading) {
                                    Box(modifier = Modifier.fillMaxWidth().height(100.dp), contentAlignment = Alignment.Center) {
                                        CircularProgressIndicator(color = Color.Red)
                                    }
                                } else {
                                    if (episodes.isNotEmpty()) {
                                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                            episodes.forEach { episode ->
                                                EpisodeRow(
                                                    episodeNumber = episode.episodeNumber,
                                                    title = episode.name ?: "Episode ${episode.episodeNumber}",
                                                    duration = if (episode.runtime != null) "${episode.runtime}m" else "",
                                                    overview = episode.overview ?: "No description available.",
                                                    stillPath = episode.stillPath,
                                                    onClick = {
                                                        navController.navigate("server_selection/${movie.basic.id}/${movie.basic.title}/SERIES/$selectedSeason/${episode.episodeNumber}")
                                                    }
                                                )
                                            }
                                        }
                                    } else {
                                        Text("No episodes found for this season.", color = Color.Gray, fontSize = 14.sp)
                                    }
                                }
                                Spacer(Modifier.height(24.dp))
                            }
                        }
                    }

                    // --- CAST SECTION ---
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

                    // --- RECOMMENDATIONS ---
                    item {
                        if (movie.recommendations.isNotEmpty()) {
                            MovieSection(
                                title = "More Like This",
                                movies = movie.recommendations,
                                onMovieClick = { recMovie ->
                                    val typeStr = if (recMovie.type == MovieType.MOVIE) "MOVIE" else "SERIES"
                                    navController.navigate("movie_detail/${recMovie.id}/$typeStr")
                                },
                                isPortrait = true
                            )
                        }
                        Spacer(Modifier.height(50.dp))
                    }
                }
            }
        }
    }
}

// --- UPDATED HELPER COMPONENT FOR EPISODES ---
@Composable
fun EpisodeRow(
    episodeNumber: Int,
    title: String,
    duration: String,
    overview: String,
    stillPath: String?,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Thumbnail / Play Preview
        Box(
            modifier = Modifier
                .width(130.dp)
                .height(74.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(Color.DarkGray), // Background while loading
            contentAlignment = Alignment.Center
        ) {
            // Using Repo's logic (manually) to construct image URL
            val imageUrl = if (!stillPath.isNullOrEmpty()) "https://image.tmdb.org/t/p/w500$stillPath" else null
            
            if (imageUrl != null) {
                AsyncImage(
                    model = imageUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
                // Play Icon Overlay
                Box(
                    modifier = Modifier.fillMaxSize().background(Color.Black.copy(0.3f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Default.PlayArrow, null, tint = Color.White)
                }
            } else {
                // Placeholder if no image
                Icon(Icons.Default.PlayArrow, null, tint = Color.White)
            }
        }
        
        Spacer(Modifier.width(12.dp))
        
        // Info
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "$episodeNumber. $title", 
                    color = Color.White, 
                    fontWeight = FontWeight.Bold, 
                    fontSize = 14.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                if (duration.isNotEmpty()) {
                    Spacer(Modifier.width(4.dp))
                    Text(duration, color = Color.Gray, fontSize = 11.sp)
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(
                text = overview, 
                color = Color.LightGray.copy(0.7f), 
                fontSize = 12.sp, 
                maxLines = 3, 
                overflow = TextOverflow.Ellipsis,
                lineHeight = 16.sp
            )
        }
    }
}
