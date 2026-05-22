package com.aeoncorex.streamx.ui.movie

import android.app.Activity
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
import androidx.compose.material.icons.rounded.*
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
import com.aeoncorex.streamx.ads.AdManager
import com.aeoncorex.streamx.streaming.PrefetchEngine
import com.aeoncorex.streamx.streaming.ProviderRequest
import java.net.URLEncoder

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MovieDetailsScreen(
    navController: NavController,
    movieId:       Int,
    movieType:     String
) {
    val type     = if (movieType.equals("MOVIE", ignoreCase = true)) MovieType.MOVIE else MovieType.SERIES
    val context  = LocalContext.current
    val activity = context as? Activity

    var details           by remember { mutableStateOf<FullMovieDetails?>(null) }
    var isLoading         by remember { mutableStateOf(true) }
    var selectedSeason    by remember { mutableIntStateOf(1) }
    var episodes          by remember { mutableStateOf<List<EpisodeDto>>(emptyList()) }
    var isEpisodesLoading by remember { mutableStateOf(false) }
    var adLoading         by remember { mutableStateOf(false) }
    var showTrailerSheet  by remember { mutableStateOf(false) }

    // ── Load movie details ────────────────────────────────────────
    LaunchedEffect(movieId) {
        isLoading = true
        details   = MovieRepository.getFullDetails(movieId, type)
        isLoading = false
    }

    // ── PREFETCH: fire as soon as details arrive ──────────────────
    LaunchedEffect(details) {
        val d = details ?: return@LaunchedEffect
        val isSeries = type == MovieType.SERIES
        PrefetchEngine.prefetchAllLanguages(
            tmdbId    = d.basic.id,
            imdbId    = d.imdbId,
            title     = d.basic.title,
            year      = d.basic.year.toIntOrNull(),
            isSeries  = isSeries,
            season    = if (isSeries) selectedSeason else 0,
            episode   = if (isSeries) 1 else 0,
            languages = listOf("English", "Hindi")
        )
    }

    // ── Re-prefetch when season changes ──────────────────────────
    LaunchedEffect(selectedSeason, details) {
        val d = details ?: return@LaunchedEffect
        if (type != MovieType.SERIES) return@LaunchedEffect

        isEpisodesLoading = true
        episodes          = MovieRepository.getEpisodes(movieId, selectedSeason)
        isEpisodesLoading = false

        PrefetchEngine.prefetch(ProviderRequest(
            tmdbId   = d.basic.id,
            imdbId   = d.imdbId,
            title    = d.basic.title,
            year     = d.basic.year.toIntOrNull(),
            isSeries = true,
            season   = selectedSeason,
            episode  = 1,
            language = "Hindi"
        ))
        PrefetchEngine.prefetch(ProviderRequest(
            tmdbId   = d.basic.id,
            imdbId   = d.imdbId,
            title    = d.basic.title,
            year     = d.basic.year.toIntOrNull(),
            isSeries = true,
            season   = selectedSeason,
            episode  = 1,
            language = "English"
        ))
    }

    // ── Navigation helpers ────────────────────────────────────────
    fun playNow(season: Int, episode: Int) {
        val d = details ?: return; if (activity == null) return
        val enc    = URLEncoder.encode(d.basic.title, "UTF-8")
        val imdbId = d.imdbId ?: "null"
        val typeS  = if (type == MovieType.MOVIE) "MOVIE" else "SERIES"
        adLoading  = true
        AdManager.showInterstitial(activity) {
            adLoading = false
            navController.navigate("exo_source/$imdbId/${d.basic.id}/$enc/$typeS/$season/$episode")
        }
    }

    fun playWithTorrent(season: Int, episode: Int) {
        val d = details ?: return; if (activity == null) return
        val enc    = URLEncoder.encode(d.basic.title, "UTF-8")
        val imdbId = d.imdbId ?: "null"
        val typeS  = if (type == MovieType.MOVIE) "MOVIE" else "SERIES"
        adLoading  = true
        AdManager.showInterstitial(activity) {
            adLoading = false
            navController.navigate("torrent_selection/$imdbId/${d.basic.id}/$enc/$typeS/$season/$episode")
        }
    }

    // ── Trailer sheet ─────────────────────────────────────────────
    if (showTrailerSheet) {
        details?.trailerKey?.let { key ->
            YoutubePlayerSheet(
                videoKey  = key,
                title     = details!!.basic.title,
                onDismiss = { showTrailerSheet = false }
            )
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {

        if (adLoading) {
            Box(
                Modifier.fillMaxSize().background(Color.Black.copy(0.88f)),
                contentAlignment = Alignment.Center
            ) { CircularProgressIndicator(color = Color.Cyan, modifier = Modifier.size(48.dp), strokeWidth = 3.dp) }
            return@Box
        }

        if (isLoading) {
            CircularProgressIndicator(modifier = Modifier.align(Alignment.Center), color = Color.Red)
            return@Box
        }

        details?.let { movie ->
            LazyColumn(Modifier.fillMaxSize()) {

                // ── HERO ──────────────────────────────────────────────
                item {
                    Box(Modifier.fillMaxWidth().height(500.dp)) {
                        AsyncImage(
                            model            = movie.basic.backdropUrl,
                            contentDescription = null,
                            contentScale     = ContentScale.Crop,
                            modifier         = Modifier.fillMaxSize()
                        )
                        Box(
                            Modifier.fillMaxSize().background(
                                Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(0.6f), Color.Black))
                            )
                        )
                        // Top bar
                        Row(
                            Modifier.fillMaxWidth().padding(top = 40.dp, start = 16.dp, end = 16.dp),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            IconButton(
                                onClick  = { navController.popBackStack() },
                                modifier = Modifier.background(Color.Black.copy(0.5f), CircleShape)
                            ) { Icon(Icons.Default.ArrowBack, "Back", tint = Color.White) }
                            IconButton(
                                onClick  = { navController.navigate("movie_settings") },
                                modifier = Modifier.background(Color.Black.copy(0.5f), CircleShape)
                            ) { Icon(Icons.Outlined.Settings, "Settings", tint = Color.White) }
                        }
                        // Info + Buttons
                        Column(Modifier.align(Alignment.BottomStart).padding(16.dp)) {
                            // Cinemeta logo (Netflix-style) or title text
                            if (movie.logo.isNotEmpty()) {
                                AsyncImage(
                                    model            = movie.logo,
                                    contentDescription = "${movie.basic.title} logo",
                                    modifier         = Modifier
                                        .height(56.dp)
                                        .fillMaxWidth()
                                        .padding(bottom = 8.dp),
                                    contentScale     = ContentScale.Fit
                                )
                            } else {
                                Text(
                                    movie.basic.title.uppercase(),
                                    style      = MaterialTheme.typography.headlineMedium,
                                    fontWeight = FontWeight.Black,
                                    color      = Color.White
                                )
                            }

                            Spacer(Modifier.height(8.dp))

                            // Meta row: IMDB rating, year, country, status, type badge, runtime
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (movie.imdbRating > 0) {
                                    Text(
                                        "${String.format("%.1f", movie.imdbRating)} IMDB",
                                        color = Color(0xFFFFA500),
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 13.sp
                                    )
                                    Spacer(Modifier.width(8.dp))
                                } else if (movie.basic.rating.isNotEmpty() && movie.basic.rating != "0.0") {
                                    Text(
                                        "${movie.basic.rating} TMDB",
                                        color = Color(0xFF46D369),
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 13.sp
                                    )
                                    Spacer(Modifier.width(12.dp))
                                }

                                Text(movie.basic.year, color = Color.White, fontSize = 13.sp)
                                Spacer(Modifier.width(12.dp))

                                if (movie.country.isNotEmpty()) {
                                    Box(
                                        Modifier.background(Color.DarkGray, RoundedCornerShape(4.dp))
                                            .padding(horizontal = 6.dp, vertical = 2.dp)
                                    ) { Text(movie.country, color = Color.White, fontSize = 11.sp) }
                                    Spacer(Modifier.width(8.dp))
                                }

                                if (movie.status.isNotEmpty()) {
                                    Box(
                                        Modifier.background(Color(0xFF1C1C1C), RoundedCornerShape(4.dp))
                                            .padding(horizontal = 6.dp, vertical = 2.dp)
                                    ) { Text(movie.status, color = Color.Cyan, fontSize = 11.sp) }
                                    Spacer(Modifier.width(8.dp))
                                }

                                Box(
                                    Modifier.background(Color.DarkGray, RoundedCornerShape(4.dp))
                                        .padding(horizontal = 4.dp, vertical = 2.dp)
                                ) { Text(if (type == MovieType.MOVIE) "HD" else "TV", color = Color.White, fontSize = 12.sp) }

                                Spacer(Modifier.width(12.dp))
                                Text(movie.runtime, color = Color.Gray, fontSize = 13.sp)
                            }

                            Spacer(Modifier.height(16.dp))

                            // Button row
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(
                                    onClick  = { if (type == MovieType.MOVIE) playNow(0, 0) else playNow(selectedSeason, 1) },
                                    colors   = ButtonDefaults.buttonColors(containerColor = Color.White),
                                    shape    = RoundedCornerShape(6.dp),
                                    modifier = Modifier.weight(1.3f).height(46.dp)
                                ) {
                                    Icon(Icons.Default.PlayArrow, null, tint = Color.Black, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text("Play Now", color = Color.Black, fontWeight = FontWeight.Bold, fontSize = 13.sp, maxLines = 1)
                                }
                                OutlinedButton(
                                    onClick  = { if (type == MovieType.MOVIE) playWithTorrent(0, 0) else playWithTorrent(selectedSeason, 1) },
                                    colors   = ButtonDefaults.outlinedButtonColors(contentColor = Color.Cyan),
                                    border   = androidx.compose.foundation.BorderStroke(1.dp, Color.Cyan.copy(0.6f)),
                                    shape    = RoundedCornerShape(6.dp),
                                    modifier = Modifier.weight(1f).height(46.dp),
                                    contentPadding = PaddingValues(horizontal = 8.dp)
                                ) {
                                    Icon(Icons.Rounded.Download, null, tint = Color.Cyan, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text("Torrent", color = Color.Cyan, fontSize = 12.sp, maxLines = 1)
                                }
                                if (movie.trailerKey != null) {
                                    OutlinedButton(
                                        onClick  = { showTrailerSheet = true },
                                        colors   = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                                        border   = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(0.3f)),
                                        shape    = RoundedCornerShape(6.dp),
                                        modifier = Modifier.weight(0.85f).height(46.dp),
                                        contentPadding = PaddingValues(horizontal = 8.dp)
                                    ) {
                                        Icon(Icons.Rounded.PlayCircle, null, tint = Color.White, modifier = Modifier.size(16.dp))
                                        Spacer(Modifier.width(4.dp))
                                        Text("Trailer", color = Color.White, fontSize = 12.sp, maxLines = 1)
                                    }
                                }
                            }
                        }
                    }
                }

                // ── DESCRIPTION ───────────────────────────────────────
                item {
                    Column(Modifier.padding(16.dp)) {
                        Text(movie.basic.description, color = Color.White, lineHeight = 20.sp)
                        Spacer(Modifier.height(12.dp))
                        Text("Genres: ${movie.genres.joinToString(", ")}", color = Color.Gray, fontSize = 13.sp)

                        if (movie.awards.isNotEmpty()) {
                            Spacer(Modifier.height(8.dp))
                            Text("🏆 ${movie.awards}", color = Color(0xFFFFD700), fontSize = 12.sp, fontWeight = FontWeight.Medium)
                        }

                        if (movie.cast.isNotEmpty()) {
                            Spacer(Modifier.height(6.dp))
                            Text(
                                "Cast: ${movie.cast.take(5).joinToString(", ") { it.name }}",
                                color = Color.Gray, fontSize = 13.sp,
                                maxLines = 2, overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }

                // ── CAST ROW ──────────────────────────────────────────
                if (movie.cast.isNotEmpty()) {
                    item {
                        Text("Cast", color = Color.White, fontWeight = FontWeight.Bold,
                            fontSize = 16.sp, modifier = Modifier.padding(start = 16.dp, bottom = 8.dp))
                    }
                    item {
                        LazyRow(
                            contentPadding      = PaddingValues(horizontal = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            items(movie.cast.take(10)) { actor ->
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    modifier = Modifier.width(72.dp)
                                ) {
                                    AsyncImage(
                                        model            = actor.imageUrl,
                                        contentDescription = actor.name,
                                        contentScale     = ContentScale.Crop,
                                        modifier         = Modifier.size(64.dp).clip(CircleShape)
                                            .background(Color.DarkGray, CircleShape)
                                    )
                                    Spacer(Modifier.height(4.dp))
                                    Text(
                                        actor.name, color = Color.White, fontSize = 10.sp,
                                        maxLines = 2, overflow = TextOverflow.Ellipsis,
                                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                                    )
                                    Text(
                                        actor.role, color = Color.Gray, fontSize = 9.sp,
                                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                                    )
                                }
                            }
                        }
                        Spacer(Modifier.height(16.dp))
                    }
                }

                // ── SEASON SELECTOR (Series only) ─────────────────────
                if (type == MovieType.SERIES && movie.seasons.isNotEmpty()) {
                    item {
                        Column(Modifier.padding(horizontal = 16.dp)) {
                            Text("Seasons", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                            Spacer(Modifier.height(8.dp))
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                items(movie.seasons) { season ->
                                    val sel = selectedSeason == season.seasonNumber
                                    Box(
                                        Modifier
                                            .clip(RoundedCornerShape(6.dp))
                                            .background(if (sel) Color.Red else Color(0xFF1C1C1C))
                                            .border(1.dp, if (sel) Color.Red else Color.DarkGray, RoundedCornerShape(6.dp))
                                            .clickable { selectedSeason = season.seasonNumber }
                                            .padding(horizontal = 14.dp, vertical = 8.dp)
                                    ) { Text("S${season.seasonNumber}", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium) }
                                }
                            }
                        }
                        Spacer(Modifier.height(12.dp))
                    }
                    if (isEpisodesLoading) {
                        item { Box(Modifier.fillMaxWidth().height(80.dp), Alignment.Center) { CircularProgressIndicator(color = Color.Red, modifier = Modifier.size(28.dp)) } }
                    } else {
                        items(episodes) { ep ->
                            EpisodeItem(ep = ep, accentColor = Color.Red) {
                                details?.let { d ->
                                    PrefetchEngine.prefetch(ProviderRequest(
                                        tmdbId   = d.basic.id,
                                        imdbId   = d.imdbId,
                                        title    = d.basic.title,
                                        year     = d.basic.year.toIntOrNull(),
                                        isSeries = true,
                                        season   = selectedSeason,
                                        episode  = ep.episodeNumber + 1,
                                        language = "Hindi"
                                    ))
                                }
                                playNow(selectedSeason, ep.episodeNumber)
                            }
                        }
                    }
                }

                item { Spacer(Modifier.height(32.dp)) }
            }
        }
    }
}

// ── Episode card ──────────────────────────────────────────────────────────────
@Composable
private fun EpisodeItem(ep: EpisodeDto, accentColor: Color, onPlay: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onPlay)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(width = 120.dp, height = 68.dp).clip(RoundedCornerShape(6.dp)).background(Color(0xFF1C1C1C))) {
            if (ep.stillUrl.isNotEmpty()) {
                AsyncImage(model = ep.stillUrl, contentDescription = null,
                    contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            }
            Box(Modifier.fillMaxSize().background(Color.Black.copy(0.3f)))
            Icon(Icons.Default.PlayArrow, null,
                tint = Color.White.copy(0.9f),
                modifier = Modifier.size(28.dp).align(Alignment.Center))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text("${ep.episodeNumber}. ${ep.name}", color = Color.White, fontSize = 13.sp,
                fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(4.dp))
            Text(ep.overview ?: "", color = Color.Gray, fontSize = 11.sp,
                maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 15.sp)
        }
        Text(ep.formattedRuntime, color = Color.Gray, fontSize = 11.sp, modifier = Modifier.padding(start = 8.dp))
    }
}
