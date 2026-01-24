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
import java.net.URLEncoder

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MovieDetailsScreen(
    navController: NavController,
    movieId: Int,
    movieType: String
) {
    val type = if (movieType.equals("MOVIE", ignoreCase = true)) MovieType.MOVIE else MovieType.SERIES
    
    var details by remember { mutableStateOf<FullMovieDetails?>(null) }
    var isLoading by remember { mutableStateOf(true) }
    
    // Series State
    var selectedSeason by remember { mutableIntStateOf(1) }
    var episodes by remember { mutableStateOf<List<EpisodeDto>>(emptyList()) }
    var isEpisodesLoading by remember { mutableStateOf(false) }

    val context = LocalContext.current
    
    // Data Fetching
    LaunchedEffect(movieId) {
        isLoading = true
        details = MovieRepository.getFullDetails(movieId, type)
        isLoading = false
    }

    LaunchedEffect(selectedSeason, details) {
        if (type == MovieType.SERIES && details != null) {
            isEpisodesLoading = true
            episodes = MovieRepository.getEpisodes(movieId, selectedSeason)
            isEpisodesLoading = false
        }
    }

    // --- NAVIGATION TO LINK SELECTION ---
    // UPDATED: Now passes both IMDB ID and TMDB ID
    fun openLinkSelection(season: Int, episode: Int) {
        if (details == null) return
        
        val titleEnc = URLEncoder.encode(details!!.basic.title, "UTF-8")
        val imdbId = details!!.imdbId ?: "null"
        val tmdbId = details!!.basic.id // Get TMDB ID from basic info
        val typeStr = if (type == MovieType.MOVIE) "MOVIE" else "SERIES"
        
        // Pass TMDB ID in the route so the next screen can use it for SuperEmbed/2Embed
        navController.navigate("link_selection/$imdbId/$tmdbId/$titleEnc/$typeStr/$season/$episode")
    }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        if (isLoading) {
            CircularProgressIndicator(modifier = Modifier.align(Alignment.Center), color = Color.Red)
        } else {
            details?.let { movie ->
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    
                    // 1. HERO SECTION (Backdrop + Info)
                    item {
                        Box(modifier = Modifier.fillMaxWidth().height(500.dp)) {
                            AsyncImage(
                                model = movie.basic.backdropUrl,
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize()
                            )
                            Box(
                                modifier = Modifier.fillMaxSize().background(
                                    Brush.verticalGradient(
                                        colors = listOf(Color.Transparent, Color.Black.copy(0.6f), Color.Black)
                                    )
                                )
                            )
                            
                            // Top Bar (Back & Settings)
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(top = 40.dp, start = 16.dp, end = 16.dp),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                IconButton(
                                    onClick = { navController.popBackStack() },
                                    modifier = Modifier.background(Color.Black.copy(0.5f), CircleShape)
                                ) {
                                    Icon(Icons.Default.ArrowBack, "Back", tint = Color.White)
                                }
                                IconButton(
                                    onClick = { navController.navigate("movie_settings") },
                                    modifier = Modifier.background(Color.Black.copy(0.5f), CircleShape)
                                ) {
                                    Icon(Icons.Outlined.Settings, "Settings", tint = Color.White)
                                }
                            }

                            // Movie Info & Buttons
                            Column(modifier = Modifier.align(Alignment.BottomStart).padding(16.dp)) {
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
                                
                                Row(modifier = Modifier.fillMaxWidth()) {
                                    // PLAY BUTTON -> Link Selector
                                    Button(
                                        onClick = {
                                            if (type == MovieType.MOVIE) {
                                                openLinkSelection(0, 0)
                                            } else {
                                                // Default to S1E1 (or current selected season) for Series Play button
                                                openLinkSelection(selectedSeason, 1)
                                            }
                                        },
                                        colors = ButtonDefaults.buttonColors(containerColor = Color.White),
                                        shape = RoundedCornerShape(4.dp),
                                        modifier = Modifier.weight(1f).height(45.dp)
                                    ) {
                                        Icon(Icons.Default.PlayArrow, null, tint = Color.Black)
                                        Spacer(Modifier.width(8.dp))
                                        Text("Play Now", color = Color.Black, fontWeight = FontWeight.Bold)
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

                    // 2. DESCRIPTION & GENRES
                    item {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(movie.basic.description, color = Color.White, lineHeight = 20.sp)
                            Spacer(Modifier.height(12.dp))
                            Text("Genres: ${movie.genres.joinToString(", ")}", color = Color.Gray, fontSize = 12.sp)
                            Text("Director: ${movie.director}", color = Color.Gray, fontSize = 12.sp)
                        }
                    }

                    // 3. SERIES EPISODES SECTION
                    if (type == MovieType.SERIES && movie.seasons.isNotEmpty()) {
                        item {
                            Column(Modifier.padding(horizontal = 16.dp)) {
                                Divider(color = Color.Gray.copy(0.3f), thickness = 1.dp)
                                Spacer(Modifier.height(16.dp))
                                
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
                                                selectedLabelColor = Color.White
                                            ),
                                            modifier = Modifier.padding(end = 8.dp)
                                        )
                                    }
                                }
                                
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
                                                        // Pass correct season and episode
                                                        openLinkSelection(selectedSeason, episode.episodeNumber)
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

                    // 4. CAST SECTION
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

                    // 5. MORE LIKE THIS (RECOMMENDATIONS)
                    if (movie.recommendations.isNotEmpty()) {
                        item {
                            Text(
                                "More Like This", 
                                color = Color.White, 
                                fontWeight = FontWeight.Bold, 
                                modifier = Modifier.padding(start = 16.dp, bottom = 12.dp)
                            )
                            
                            LazyRow(
                                contentPadding = PaddingValues(horizontal = 16.dp),
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                items(movie.recommendations) { recMovie ->
                                    RecommendationCard(movie = recMovie) {
                                        // Recursively open details for recommended item
                                        val typeStr = if (recMovie.type == MovieType.MOVIE) "MOVIE" else "SERIES"
                                        navController.navigate("movie_detail/${recMovie.id}/$typeStr")
                                    }
                                }
                            }
                            Spacer(Modifier.height(50.dp))
                        }
                    }
                }
            }
        }
    }
}

// --- HELPER COMPONENTS ---

@Composable
fun RecommendationCard(movie: Movie, onClick: () -> Unit) {
    Column(modifier = Modifier.width(130.dp).clickable { onClick() }) {
        Card(
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier.size(width = 130.dp, height = 190.dp),
            elevation = CardDefaults.cardElevation(4.dp)
        ) {
            Box(Modifier.fillMaxSize()) {
                AsyncImage(
                    model = movie.posterUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
                // Rating Badge
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
        Spacer(Modifier.height(6.dp))
        Text(
            text = movie.title, 
            color = Color.White, 
            fontSize = 12.sp, 
            fontWeight = FontWeight.SemiBold, 
            maxLines = 1, 
            overflow = TextOverflow.Ellipsis
        )
    }
}

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
        Box(
            modifier = Modifier
                .width(130.dp)
                .height(74.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(Color.DarkGray),
            contentAlignment = Alignment.Center
        ) {
            val imageUrl = if (!stillPath.isNullOrEmpty()) "https://image.tmdb.org/t/p/w500$stillPath" else null
            
            if (imageUrl != null) {
                AsyncImage(
                    model = imageUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
                Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(0.3f)), contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.PlayArrow, null, tint = Color.White)
                }
            } else {
                Icon(Icons.Default.PlayArrow, null, tint = Color.White)
            }
        }
        
        Spacer(Modifier.width(12.dp))
        
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
