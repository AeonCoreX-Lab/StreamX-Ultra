package com.aeoncorex.streamx.ui.movie

import android.app.Activity
import androidx.compose.animation.*
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import coil.compose.AsyncImage
import com.aeoncorex.streamx.ads.AdManager
import com.aeoncorex.streamx.streaming.PrefetchEngine
import com.aeoncorex.streamx.streaming.ProviderRequest
import java.net.URLEncoder

// ── Design tokens ──────────────────────────────────────────────────────────────
private val AccentRed    = Color(0xFFE50914)
private val AccentGold   = Color(0xFFFFC107)
private val AccentGreen  = Color(0xFF46D369)
private val AccentPurple = Color(0xFF7C3AED)
private val CardBg       = Color(0xFF1A1A2E)
private val SurfaceBg    = Color(0xFF0F0F1A)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MovieDetailsScreen(
    navController : NavController,
    movieId       : Int,
    movieType     : String
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
    var descExpanded      by remember { mutableStateOf(false) }

    // ── Load details ───────────────────────────────────────────────────────
    LaunchedEffect(movieId) {
        isLoading = true
        details   = MovieRepository.getFullDetails(movieId, type)
        isLoading = false
    }

    // ── Prefetch streams on arrival ────────────────────────────────────────
    LaunchedEffect(details) {
        val d = details ?: return@LaunchedEffect
        PrefetchEngine.prefetchAllLanguages(
            tmdbId   = d.basic.id,
            imdbId   = d.imdbId,
            title    = d.basic.title,
            year     = d.basic.year.toIntOrNull(),
            isSeries = type == MovieType.SERIES,
            season   = if (type == MovieType.SERIES) selectedSeason else 0,
            episode  = if (type == MovieType.SERIES) 1 else 0,
            languages = listOf("English", "Hindi")
        )
    }

    // ── Episodes load when season selected ────────────────────────────────
    LaunchedEffect(selectedSeason, details) {
        val d = details ?: return@LaunchedEffect
        if (type != MovieType.SERIES) return@LaunchedEffect
        isEpisodesLoading = true
        episodes          = MovieRepository.getEpisodes(movieId, selectedSeason)
        isEpisodesLoading = false
        PrefetchEngine.prefetch(ProviderRequest(
            tmdbId   = d.basic.id, imdbId = d.imdbId, title = d.basic.title,
            year = d.basic.year.toIntOrNull(), isSeries = true,
            season = selectedSeason, episode = 1, language = "Hindi"
        ))
    }

    // ── Navigation helpers ─────────────────────────────────────────────────
    fun playNow(season: Int, episode: Int) {
        val d = details ?: return; if (activity == null) return
        val enc   = URLEncoder.encode(d.basic.title, "UTF-8")
        val imdb  = d.imdbId ?: "null"
        val typeS = if (type == MovieType.MOVIE) "MOVIE" else "SERIES"
        adLoading = true
        AdManager.showInterstitial(activity) {
            adLoading = false
            navController.navigate("exo_source/$imdb/${d.basic.id}/$enc/$typeS/$season/$episode")
        }
    }

    fun playWithTorrent(season: Int, episode: Int) {
        val d = details ?: return; if (activity == null) return
        val enc   = URLEncoder.encode(d.basic.title, "UTF-8")
        val imdb  = d.imdbId ?: "null"
        val typeS = if (type == MovieType.MOVIE) "MOVIE" else "SERIES"
        adLoading = true
        AdManager.showInterstitial(activity) {
            adLoading = false
            navController.navigate("torrent_selection/$imdb/${d.basic.id}/$enc/$typeS/$season/$episode")
        }
    }

    // ── Trailer sheet ──────────────────────────────────────────────────────
    if (showTrailerSheet && details?.trailerKey != null) {
        YoutubePlayerSheet(
            videoKey  = details!!.trailerKey!!,
            title     = details!!.basic.title,
            onDismiss = { showTrailerSheet = false }
        )
    }

    // ══════════════════════════════════════════════════════════════════════
    //  ROOT
    // ══════════════════════════════════════════════════════════════════════
    Box(Modifier.fillMaxSize().background(SurfaceBg)) {

        // Ad loading overlay
        if (adLoading) {
            Box(
                Modifier.fillMaxSize().background(Color.Black.copy(.88f)),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(color = AccentRed,
                    modifier = Modifier.size(48.dp), strokeWidth = 3.dp)
            }
            return@Box
        }

        // Initial loading
        if (isLoading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = AccentRed)
            }
            return@Box
        }

        details?.let { movie ->
            LazyColumn(Modifier.fillMaxSize()) {

                // ══════════════════════════════════════════════════════
                //  1. HERO — backdrop + gradient + logo/title + actions
                // ══════════════════════════════════════════════════════
                item {
                    Box(Modifier.fillMaxWidth().height(520.dp)) {

                        // Backdrop image
                        AsyncImage(
                            model              = movie.basic.backdropUrl.ifEmpty { movie.basic.posterUrl },
                            contentDescription = null,
                            contentScale       = ContentScale.Crop,
                            modifier           = Modifier.fillMaxSize()
                        )

                        // Gradient scrim
                        Box(Modifier.fillMaxSize().background(
                            Brush.verticalGradient(
                                0f   to Color.Black.copy(.15f),
                                0.4f to Color.Black.copy(.5f),
                                1f   to SurfaceBg
                            )
                        ))

                        // Top bar
                        Row(
                            Modifier.fillMaxWidth()
                                .padding(top = 44.dp, start = 12.dp, end = 12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            IconButton(
                                onClick  = { navController.popBackStack() },
                                modifier = Modifier.size(40.dp)
                                    .background(Color.Black.copy(.55f), CircleShape)
                            ) { Icon(Icons.Default.ArrowBack, "Back", tint = Color.White) }

                            IconButton(
                                onClick  = { navController.navigate("movie_settings") },
                                modifier = Modifier.size(40.dp)
                                    .background(Color.Black.copy(.55f), CircleShape)
                            ) { Icon(Icons.Outlined.Settings, "Settings", tint = Color.White) }
                        }

                        // Bottom info area
                        Column(
                            Modifier
                                .align(Alignment.BottomStart)
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 16.dp)
                        ) {
                            // Logo or title
                            if (movie.logo.isNotEmpty()) {
                                AsyncImage(
                                    model              = movie.logo,
                                    contentDescription = "${movie.basic.title} logo",
                                    contentScale       = ContentScale.Fit,
                                    modifier           = Modifier.height(60.dp).fillMaxWidth(.7f)
                                )
                            } else {
                                Text(
                                    movie.basic.title,
                                    color      = Color.White,
                                    fontSize   = 26.sp,
                                    fontWeight = FontWeight.Black,
                                    lineHeight = 30.sp
                                )
                            }

                            Spacer(Modifier.height(10.dp))

                            // ── Rating row ─────────────────────────────
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                // TMDB rating
                                if (movie.tmdbRating > 0) {
                                    RatingBadge(
                                        score  = String.format("%.1f", movie.tmdbRating),
                                        label  = "TMDB",
                                        color  = AccentGreen
                                    )
                                }
                                // IMDb rating from Cinemeta
                                if (movie.imdbRating > 0) {
                                    RatingBadge(
                                        score  = String.format("%.1f", movie.imdbRating),
                                        label  = "IMDb",
                                        color  = AccentGold
                                    )
                                }
                            }

                            Spacer(Modifier.height(8.dp))

                            // ── Meta chips row ─────────────────────────
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                if (movie.basic.year.isNotEmpty()) item {
                                    MetaChip(movie.basic.year)
                                }
                                if (movie.runtime != "N/A" && movie.runtime.isNotEmpty()) item {
                                    MetaChip(movie.runtime)
                                }
                                item {
                                    MetaChip(
                                        if (type == MovieType.MOVIE) "MOVIE" else "SERIES",
                                        color = AccentRed
                                    )
                                }
                                if (movie.country.isNotEmpty()) item {
                                    MetaChip(movie.country)
                                }
                                if (movie.status.isNotEmpty()) item {
                                    MetaChip(movie.status, color = Color(0xFF00BCD4))
                                }
                                if (movie.language.isNotEmpty()) item {
                                    MetaChip(movie.language.uppercase())
                                }
                            }

                            Spacer(Modifier.height(14.dp))

                            // ── Action buttons ─────────────────────────
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                // ▶ Play
                                Button(
                                    onClick = {
                                        if (type == MovieType.MOVIE) playNow(0, 0)
                                        else playNow(selectedSeason, 1)
                                    },
                                    modifier = Modifier.weight(1f).height(46.dp),
                                    colors   = ButtonDefaults.buttonColors(
                                        containerColor = Color.White
                                    ),
                                    shape = RoundedCornerShape(8.dp)
                                ) {
                                    Icon(Icons.Rounded.PlayArrow, null,
                                        tint = Color.Black, modifier = Modifier.size(20.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text("Play", color = Color.Black, fontWeight = FontWeight.Bold)
                                }

                                // 🌐 Torrent
                                OutlinedButton(
                                    onClick = {
                                        if (type == MovieType.MOVIE) playWithTorrent(0, 0)
                                        else playWithTorrent(selectedSeason, 1)
                                    },
                                    modifier = Modifier.weight(1f).height(46.dp),
                                    border   = ButtonDefaults.outlinedButtonBorder.copy(
                                        width = 1.dp
                                    ),
                                    shape = RoundedCornerShape(8.dp),
                                    colors = ButtonDefaults.outlinedButtonColors(
                                        contentColor = Color.White
                                    )
                                ) {
                                    Icon(Icons.Rounded.Download, null,
                                        modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text("Torrent", fontWeight = FontWeight.SemiBold)
                                }

                                // ▶ Trailer
                                if (movie.trailerKey != null) {
                                    OutlinedButton(
                                        onClick  = { showTrailerSheet = true },
                                        modifier = Modifier.weight(.8f).height(46.dp),
                                        border   = ButtonDefaults.outlinedButtonBorder.copy(width = 1.dp),
                                        shape    = RoundedCornerShape(8.dp),
                                        colors   = ButtonDefaults.outlinedButtonColors(
                                            contentColor = Color.White
                                        )
                                    ) {
                                        Icon(Icons.Rounded.PlayCircle, null,
                                            modifier = Modifier.size(16.dp))
                                        Spacer(Modifier.width(4.dp))
                                        Text("Trailer", fontSize = 12.sp, maxLines = 1)
                                    }
                                }
                            }
                        }
                    }
                }

                // ══════════════════════════════════════════════════════
                //  2. GENRE PILLS
                // ══════════════════════════════════════════════════════
                if (movie.genres.isNotEmpty()) {
                    item {
                        LazyRow(
                            contentPadding        = PaddingValues(horizontal = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier              = Modifier.padding(vertical = 10.dp)
                        ) {
                            items(movie.genres) { genre ->
                                Box(
                                    Modifier
                                        .clip(RoundedCornerShape(20.dp))
                                        .background(AccentPurple.copy(.18f))
                                        .border(1.dp, AccentPurple.copy(.5f), RoundedCornerShape(20.dp))
                                        .padding(horizontal = 14.dp, vertical = 5.dp)
                                ) {
                                    Text(genre, color = Color.White.copy(.9f),
                                        fontSize = 12.sp, fontWeight = FontWeight.Medium)
                                }
                            }
                        }
                    }
                }

                // ══════════════════════════════════════════════════════
                //  3. DESCRIPTION + DIRECTOR
                // ══════════════════════════════════════════════════════
                item {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp)
                            .padding(bottom = 4.dp)
                    ) {
                        if (movie.basic.description.isNotEmpty()) {
                            Text(
                                movie.basic.description,
                                color      = Color.White.copy(.85f),
                                fontSize   = 14.sp,
                                lineHeight = 22.sp,
                                maxLines   = if (descExpanded) Int.MAX_VALUE else 4,
                                overflow   = TextOverflow.Ellipsis
                            )
                            if (movie.basic.description.length > 200) {
                                Text(
                                    if (descExpanded) "Show less" else "Show more",
                                    color     = AccentPurple,
                                    fontSize  = 13.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    modifier  = Modifier
                                        .clickable { descExpanded = !descExpanded }
                                        .padding(top = 4.dp)
                                )
                            }
                        }

                        Spacer(Modifier.height(12.dp))

                        // Director
                        if (movie.director.isNotEmpty() && movie.director != "Unknown") {
                            InfoRow(label = "Director", value = movie.director)
                        }
                    }
                }

                // ══════════════════════════════════════════════════════
                //  4. CINEMETA-EXCLUSIVE INFO (awards, country, language)
                // ══════════════════════════════════════════════════════
                if (movie.cinemetaEnriched && (movie.awards.isNotEmpty()
                    || movie.country.isNotEmpty() || movie.language.isNotEmpty())) {
                    item {
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp)
                                .padding(bottom = 12.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(CardBg)
                                .padding(14.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Rounded.Info, null,
                                    tint = AccentGold.copy(.8f),
                                    modifier = Modifier.size(14.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Details",
                                    color = AccentGold.copy(.8f), fontSize = 11.sp,
                                    fontWeight = FontWeight.ExtraBold, letterSpacing = 1.sp)
                            }
                            Spacer(Modifier.height(8.dp))
                            if (movie.country.isNotEmpty())
                                InfoRow("Country", movie.country)
                            if (movie.language.isNotEmpty())
                                InfoRow("Language", movie.language)
                            if (movie.awards.isNotEmpty()) {
                                Spacer(Modifier.height(4.dp))
                                Row(verticalAlignment = Alignment.Top) {
                                    Text("🏆 ", fontSize = 13.sp)
                                    Text(
                                        movie.awards,
                                        color      = AccentGold,
                                        fontSize   = 12.sp,
                                        lineHeight = 18.sp,
                                        fontWeight = FontWeight.Medium
                                    )
                                }
                            }
                        }
                    }
                }

                // ══════════════════════════════════════════════════════
                //  5. CAST ROW — clickable → person_detail (TMDB only)
                // ══════════════════════════════════════════════════════
                if (movie.cast.isNotEmpty()) {
                    item {
                        SectionHeader("Cast")
                    }
                    item {
                        LazyRow(
                            contentPadding        = PaddingValues(horizontal = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(14.dp),
                            modifier              = Modifier.padding(bottom = 20.dp)
                        ) {
                            items(movie.cast.take(15)) { actor ->
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    modifier = Modifier
                                        .width(76.dp)
                                        .let {
                                            if (actor.personId > 0) it.clickable {
                                                navController.navigate("person_detail/${actor.personId}")
                                            } else it
                                        }
                                ) {
                                    // Profile image
                                    Box(
                                        Modifier
                                            .size(68.dp)
                                            .clip(CircleShape)
                                            .background(CardBg, CircleShape)
                                            .border(
                                                width = if (actor.personId > 0) 1.5.dp else 0.dp,
                                                color = if (actor.personId > 0)
                                                            AccentPurple.copy(.6f) else Color.Transparent,
                                                shape = CircleShape
                                            )
                                    ) {
                                        if (actor.imageUrl.isNotEmpty()) {
                                            AsyncImage(
                                                model              = actor.imageUrl,
                                                contentDescription = actor.name,
                                                contentScale       = ContentScale.Crop,
                                                modifier           = Modifier.fillMaxSize()
                                            )
                                        } else {
                                            Box(
                                                Modifier.fillMaxSize()
                                                    .background(AccentPurple.copy(.2f), CircleShape),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                Text(
                                                    actor.name.take(1).uppercase(),
                                                    color = AccentPurple,
                                                    fontSize = 22.sp,
                                                    fontWeight = FontWeight.Bold
                                                )
                                            }
                                        }
                                    }
                                    Spacer(Modifier.height(5.dp))
                                    Text(actor.name, color = Color.White, fontSize = 10.sp,
                                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                                        textAlign = TextAlign.Center, fontWeight = FontWeight.SemiBold)
                                    if (actor.role.isNotEmpty()) {
                                        Text(actor.role, color = Color.Gray, fontSize = 9.sp,
                                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                                            textAlign = TextAlign.Center)
                                    }
                                }
                            }
                        }
                    }
                }

                // ══════════════════════════════════════════════════════
                //  6. SEASONS + EPISODES (series only)
                // ══════════════════════════════════════════════════════
                if (type == MovieType.SERIES && movie.seasons.isNotEmpty()) {
                    item {
                        SectionHeader("Episodes")
                        LazyRow(
                            contentPadding        = PaddingValues(horizontal = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier              = Modifier.padding(bottom = 12.dp)
                        ) {
                            items(movie.seasons) { season ->
                                val sel = selectedSeason == season.seasonNumber
                                Box(
                                    Modifier
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(if (sel) AccentRed else CardBg)
                                        .border(1.dp,
                                            if (sel) AccentRed else Color.White.copy(.12f),
                                            RoundedCornerShape(8.dp))
                                        .clickable { selectedSeason = season.seasonNumber }
                                        .padding(horizontal = 16.dp, vertical = 9.dp)
                                ) {
                                    Text(
                                        "S${season.seasonNumber}",
                                        color      = Color.White,
                                        fontSize   = 13.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }
                    }

                    if (isEpisodesLoading) {
                        item {
                            Box(Modifier.fillMaxWidth().height(80.dp),
                                contentAlignment = Alignment.Center) {
                                CircularProgressIndicator(color = AccentRed,
                                    modifier = Modifier.size(28.dp), strokeWidth = 2.dp)
                            }
                        }
                    } else if (episodes.isEmpty()) {
                        item {
                            Box(Modifier.fillMaxWidth().padding(24.dp),
                                contentAlignment = Alignment.Center) {
                                Text("No episodes found", color = Color.Gray, fontSize = 14.sp)
                            }
                        }
                    } else {
                        items(episodes) { ep ->
                            EpisodeItem(ep = ep, accentColor = AccentRed) {
                                details?.let { d ->
                                    PrefetchEngine.prefetch(ProviderRequest(
                                        tmdbId   = d.basic.id, imdbId = d.imdbId,
                                        title    = d.basic.title,
                                        year     = d.basic.year.toIntOrNull(),
                                        isSeries = true, season = selectedSeason,
                                        episode  = ep.episodeNumber + 1, language = "Hindi"
                                    ))
                                }
                                playNow(selectedSeason, ep.episodeNumber)
                            }
                        }
                    }
                }

                // ══════════════════════════════════════════════════════
                //  7. MORE LIKE THIS — TMDB recommendations
                // ══════════════════════════════════════════════════════
                if (movie.recommendations.isNotEmpty()) {
                    item { SectionHeader("More Like This") }
                    item {
                        LazyRow(
                            contentPadding        = PaddingValues(horizontal = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            modifier              = Modifier.padding(bottom = 24.dp)
                        ) {
                            items(movie.recommendations) { rec ->
                                RecommendationCard(movie = rec) {
                                    val typeStr = if (rec.type == MovieType.MOVIE) "MOVIE" else "SERIES"
                                    navController.navigate("movie_detail/${rec.id}/$typeStr")
                                }
                            }
                        }
                    }
                }

                // ── Cinemeta source badge ─────────────────────────────
                if (movie.cinemetaEnriched) {
                    item {
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Rounded.Verified, null,
                                tint = AccentPurple.copy(.5f),
                                modifier = Modifier.size(12.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(
                                "Enriched with Cinemeta metadata",
                                color    = Color.Gray.copy(.5f),
                                fontSize = 10.sp
                            )
                        }
                        Spacer(Modifier.height(32.dp))
                    }
                } else {
                    item { Spacer(Modifier.height(32.dp)) }
                }
            }
        }
    }
}

// ══════════════════════════════════════════════════════════════════════════════
//  Reusable composables
// ══════════════════════════════════════════════════════════════════════════════

@Composable
private fun SectionHeader(title: String) {
    Row(
        Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.width(3.dp).height(18.dp).background(
            Brush.verticalGradient(listOf(AccentRed, AccentPurple)),
            RoundedCornerShape(2.dp)
        ))
        Spacer(Modifier.width(8.dp))
        Text(title, color = Color.White, fontWeight = FontWeight.ExtraBold,
            fontSize = 16.sp, letterSpacing = .3.sp)
    }
}

@Composable
private fun RatingBadge(score: String, label: String, color: Color) {
    Row(
        Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(color.copy(.15f))
            .border(1.dp, color.copy(.5f), RoundedCornerShape(6.dp))
            .padding(horizontal = 9.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("★ ", color = color, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        Text(score, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.ExtraBold)
        Spacer(Modifier.width(4.dp))
        Text(label, color = color.copy(.8f), fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun MetaChip(text: String, color: Color = Color.White.copy(.12f)) {
    val isAccent = color != Color.White.copy(.12f)
    Box(
        Modifier
            .clip(RoundedCornerShape(5.dp))
            .background(if (isAccent) color.copy(.2f) else Color.White.copy(.08f))
            .border(
                .5.dp,
                if (isAccent) color.copy(.5f) else Color.White.copy(.15f),
                RoundedCornerShape(5.dp)
            )
            .padding(horizontal = 8.dp, vertical = 3.dp)
    ) {
        Text(text,
            color = if (isAccent) color else Color.White.copy(.8f),
            fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(Modifier.padding(vertical = 2.dp)) {
        Text("$label: ", color = Color.Gray, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        Text(value, color = Color.White.copy(.85f), fontSize = 12.sp)
    }
}

@Composable
fun RecommendationCard(movie: Movie, onClick: () -> Unit) {
    Column(
        modifier = Modifier.width(130.dp).clickable(onClick = onClick)
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(185.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(CardBg)
        ) {
            AsyncImage(
                model              = movie.posterUrl,
                contentDescription = movie.title,
                contentScale       = ContentScale.Crop,
                modifier           = Modifier.fillMaxSize()
            )
            // Bottom gradient
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(60.dp)
                    .align(Alignment.BottomCenter)
                    .background(Brush.verticalGradient(
                        listOf(Color.Transparent, Color.Black.copy(.85f))
                    ))
            )
            // Rating badge
            if (movie.rating.isNotEmpty() && movie.rating != "0.0") {
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .padding(5.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(Color.Black.copy(.7f))
                        .padding(horizontal = 5.dp, vertical = 2.dp)
                ) {
                    Text("★ ${movie.rating}", color = AccentGold,
                        fontSize = 9.sp, fontWeight = FontWeight.Bold)
                }
            }
            // Type badge
            Box(
                Modifier
                    .align(Alignment.TopStart)
                    .padding(5.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(
                        if (movie.type == MovieType.SERIES) AccentPurple.copy(.85f)
                        else AccentRed.copy(.85f)
                    )
                    .padding(horizontal = 5.dp, vertical = 2.dp)
            ) {
                Text(
                    if (movie.type == MovieType.SERIES) "TV" else "HD",
                    color = Color.White, fontSize = 8.sp, fontWeight = FontWeight.ExtraBold
                )
            }
        }
        Spacer(Modifier.height(5.dp))
        Text(
            movie.title,
            color      = Color.White.copy(.9f),
            fontSize   = 11.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines   = 2,
            lineHeight = 14.sp,
            overflow   = TextOverflow.Ellipsis
        )
        if (movie.year.isNotEmpty()) {
            Text(movie.year, color = Color.Gray, fontSize = 10.sp)
        }
    }
}

@Composable
private fun EpisodeItem(ep: EpisodeDto, accentColor: Color, onPlay: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onPlay)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Thumbnail
        Box(
            Modifier
                .size(width = 120.dp, height = 70.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(CardBg)
        ) {
            val stillUrl = ep.externalStillUrl?.ifEmpty {
                if (!ep.stillPath.isNullOrEmpty())
                    "https://image.tmdb.org/t/p/w300${ep.stillPath}" else ""
            } ?: ""
            if (stillUrl.isNotEmpty()) {
                AsyncImage(
                    model              = stillUrl,
                    contentDescription = null,
                    contentScale       = ContentScale.Crop,
                    modifier           = Modifier.fillMaxSize()
                )
            }
            Box(Modifier.fillMaxSize().background(Color.Black.copy(.35f)))
            Icon(Icons.Default.PlayArrow, null,
                tint     = Color.White.copy(.9f),
                modifier = Modifier.size(28.dp).align(Alignment.Center))
        }

        Spacer(Modifier.width(12.dp))

        Column(Modifier.weight(1f)) {
            Text(
                "${ep.episodeNumber}. ${ep.name ?: "Episode ${ep.episodeNumber}"}",
                color = Color.White, fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1, overflow = TextOverflow.Ellipsis
            )
            if (!ep.overview.isNullOrBlank()) {
                Spacer(Modifier.height(3.dp))
                Text(ep.overview, color = Color.Gray, fontSize = 11.sp,
                    maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 15.sp)
            }
        }

        if (ep.runtime != null) {
            Text("${ep.runtime}m", color = Color.Gray, fontSize = 11.sp,
                modifier = Modifier.padding(start = 8.dp))
        }
    }
    HorizontalDivider(color = Color.White.copy(.05f),
        modifier = Modifier.padding(horizontal = 16.dp))
}
