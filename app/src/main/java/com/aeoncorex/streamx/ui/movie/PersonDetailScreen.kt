package com.aeoncorex.streamx.ui.movie

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import coil.compose.AsyncImage
import kotlinx.coroutines.launch

@Composable
fun PersonDetailScreen(
    personId:    Int,
    navController: NavController,
    repository:  MovieRepository = MovieRepository,
) {
    val context = LocalContext.current
    val scope   = rememberCoroutineScope()

    var person    by remember { mutableStateOf<PersonDetails?>(null) }
    var isLoading by remember { mutableStateOf(true) }
    var bioExpanded by remember { mutableStateOf(false) }
    var isCinemetaFallback by remember { mutableStateOf(false) }

    val Purple    = Color(0xFF7C3AED)
    val DarkBg    = Color(0xFF0A0A12)
    val CardBg    = Color(0xFF111120)

    LaunchedEffect(personId) {
        if (personId <= 0) {
            // Cinemeta-only cast member — no TMDB personId available
            isLoading = false
            return@LaunchedEffect
        }
        val result = repository.fetchPersonDetails(personId)
        person = result
        isLoading = false
        isCinemetaFallback = result != null && result.socialLinks.imdbId == null
                            && result.socialLinks.instagramId == null
                            && result.socialLinks.twitterId == null
                            && result.socialLinks.facebookId == null
    }

    BackHandler { navController.popBackStack() }

    Box(
        Modifier.fillMaxSize()
            .background(Brush.verticalGradient(listOf(DarkBg, Color(0xFF08080F))))
    ) {
        when {
            isLoading -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = Purple)
                }
            }

            person == null -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Rounded.PersonOff, null,
                            tint = Color.Gray, modifier = Modifier.size(56.dp))
                        Spacer(Modifier.height(12.dp))
                        Text("Person not found", color = Color.Gray, fontSize = 16.sp)
                        Spacer(Modifier.height(8.dp))
                        Text("TMDB and Cinemeta both unavailable", color = Color(0xFF4A4A5A), fontSize = 12.sp)
                    }
                }
            }

            else -> {
                val p = person!!
                LazyColumn(Modifier.fillMaxSize()) {

                    // ── Hero ──────────────────────────────────────────
                    item {
                        Box(Modifier.fillMaxWidth().height(380.dp)) {
                            AsyncImage(
                                model              = p.profileUrl,
                                contentDescription = null,
                                contentScale       = ContentScale.FillWidth,
                                modifier           = Modifier.fillMaxSize(),
                            )
                            Box(
                                Modifier.fillMaxSize().background(
                                    Brush.verticalGradient(
                                        listOf(
                                            Color.Black.copy(0.3f),
                                            Color.Black.copy(0.1f),
                                            DarkBg,
                                        )
                                    )
                                )
                            )

                            IconButton(
                                onClick  = { navController.popBackStack() },
                                modifier = Modifier
                                    .statusBarsPadding()
                                    .padding(12.dp)
                                    .background(Color.Black.copy(0.5f), CircleShape)
                            ) {
                                Icon(Icons.Default.ArrowBack, null, tint = Color.White)
                            }

                            Column(
                                modifier            = Modifier.align(Alignment.BottomCenter)
                                    .padding(bottom = 24.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                AsyncImage(
                                    model              = p.profileUrl,
                                    contentDescription = p.name,
                                    contentScale       = ContentScale.Crop,
                                    modifier           = Modifier
                                        .size(110.dp)
                                        .clip(CircleShape)
                                        .border(3.dp, Purple, CircleShape),
                                )
                                Spacer(Modifier.height(12.dp))
                                Text(
                                    p.name,
                                    color      = Color.White,
                                    fontSize   = 24.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    textAlign  = TextAlign.Center,
                                    modifier   = Modifier.padding(horizontal = 24.dp),
                                )
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    p.knownFor,
                                    color    = Purple,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Medium,
                                )
                                if (isCinemetaFallback) {
                                    Spacer(Modifier.height(4.dp))
                                    Text(
                                        "Data from Cinemeta",
                                        color    = Color(0xFF00C9A7),
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Medium,
                                    )
                                }
                            }
                        }
                    }

                    // ── Social links (TMDB only) ──────────────────────
                    if (!isCinemetaFallback) {
                        item {
                            val links = listOf(
                                p.socialLinks.instagramId?.let { Triple("https://instagram.com/$it", Icons.Rounded.CameraAlt, "Instagram") },
                                p.socialLinks.twitterId?.let   { Triple("https://twitter.com/$it",   Icons.Rounded.AlternateEmail, "X (Twitter)") },
                                p.socialLinks.facebookId?.let  { Triple("https://facebook.com/$it",  Icons.Rounded.Facebook,  "Facebook") },
                                p.socialLinks.imdbId?.let      { Triple("https://imdb.com/name/$it", Icons.Rounded.Movie,      "IMDb") },
                            ).filterNotNull()

                            if (links.isNotEmpty()) {
                                Row(
                                    Modifier.fillMaxWidth().padding(vertical = 8.dp),
                                    horizontalArrangement = Arrangement.Center,
                                    verticalAlignment     = Alignment.CenterVertically,
                                ) {
                                    links.forEach { (url, icon, label) ->
                                        IconButton(
                                            onClick  = {
                                                runCatching {
                                                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                                                }
                                            },
                                            modifier = Modifier
                                                .size(44.dp)
                                                .background(Color.White.copy(0.08f), CircleShape),
                                        ) {
                                            Icon(icon, label, tint = Color.White, modifier = Modifier.size(20.dp))
                                        }
                                        Spacer(Modifier.width(8.dp))
                                    }
                                }
                            }
                        }
                    }

                    // ── Personal Info ─────────────────────────────────
                    item {
                        Column(
                            Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
                                .clip(RoundedCornerShape(16.dp))
                                .background(CardBg)
                                .padding(16.dp)
                        ) {
                            Text("Personal Info", color = Color.White,
                                fontWeight = FontWeight.Bold, fontSize = 15.sp)
                            Spacer(Modifier.height(12.dp))

                            val infoItems = listOfNotNull(
                                "Known For"     to p.knownFor,
                                "Gender"        to when (p.gender) { 1 -> "Female"; 2 -> "Male"; else -> null },
                                "Birthday"      to p.birthday?.let { formatDate(it) },
                                "Deathday"      to p.deathday?.let { formatDate(it) },
                                "Place of Birth" to p.placeOfBirth,
                            ).filter { it.second != null }

                            if (infoItems.isEmpty()) {
                                Text(
                                    "Detailed personal information unavailable",
                                    color = Color(0xFF4A4A5A),
                                    fontSize = 13.sp,
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)
                                )
                            } else {
                                infoItems.forEach { (label, value) ->
                                    Row(
                                        Modifier.fillMaxWidth().padding(vertical = 5.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                    ) {
                                        Text(label,
                                            color = Color.Gray, fontSize = 13.sp,
                                            modifier = Modifier.weight(0.45f))
                                        Text(value ?: "",
                                            color = Color.White, fontSize = 13.sp,
                                            fontWeight = FontWeight.Medium,
                                            modifier = Modifier.weight(0.55f))
                                    }
                                    HorizontalDivider(color = Color.White.copy(0.05f), thickness = 0.5.dp)
                                }
                            }
                        }
                    }

                    // ── Biography ─────────────────────────────────────
                    if (p.biography.isNotBlank()) {
                        item {
                            Column(
                                Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
                                    .clip(RoundedCornerShape(16.dp))
                                    .background(CardBg)
                                    .padding(16.dp)
                            ) {
                                Row(
                                    Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment     = Alignment.CenterVertically,
                                ) {
                                    Text("Biography", color = Color.White,
                                        fontWeight = FontWeight.Bold, fontSize = 15.sp)
                                    TextButton(onClick = { bioExpanded = !bioExpanded }) {
                                        Text(
                                            if (bioExpanded) "Show less" else "Read more",
                                            color = Purple, fontSize = 12.sp,
                                        )
                                    }
                                }
                                Spacer(Modifier.height(8.dp))
                                Text(
                                    p.biography,
                                    color      = Color.White.copy(0.75f),
                                    fontSize   = 13.sp,
                                    lineHeight = 20.sp,
                                    maxLines   = if (bioExpanded) Int.MAX_VALUE else 5,
                                    overflow   = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    } else if (isCinemetaFallback) {
                        item {
                            Column(
                                Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
                                    .clip(RoundedCornerShape(16.dp))
                                    .background(CardBg)
                                    .padding(16.dp)
                            ) {
                                Text("Biography", color = Color.White,
                                    fontWeight = FontWeight.Bold, fontSize = 15.sp)
                                Spacer(Modifier.height(8.dp))
                                Text(
                                    "Biography not available from alternative data source.",
                                    color = Color(0xFF4A4A5A),
                                    fontSize = 13.sp,
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)
                                )
                            }
                        }
                    }

                    // ── Known For ─────────────────────────────────────
                    if (p.knownForMovies.isNotEmpty()) {
                        item {
                            Column(Modifier.padding(top = 16.dp, bottom = 8.dp)) {
                                Text(
                                    "Known For",
                                    color      = Color.White,
                                    fontWeight = FontWeight.Bold,
                                    fontSize   = 16.sp,
                                    modifier   = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                                )
                                LazyRow(
                                    contentPadding      = PaddingValues(horizontal = 20.dp),
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                ) {
                                    items(p.knownForMovies) { movie ->
                                        KnownForCard(movie) {
                                            val typeStr = if (movie.type == MovieType.MOVIE) "MOVIE" else "SERIES"
                                            navController.navigate("movie_detail/${movie.id}/$typeStr")
                                        }
                                    }
                                }
                            }
                        }
                    } else if (isCinemetaFallback) {
                        item {
                            Column(
                                Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
                                    .clip(RoundedCornerShape(16.dp))
                                    .background(CardBg)
                                    .padding(16.dp)
                            ) {
                                Text("Known For", color = Color.White,
                                    fontWeight = FontWeight.Bold, fontSize = 15.sp)
                                Spacer(Modifier.height(8.dp))
                                Text(
                                    "Filmography not available from alternative data source.",
                                    color = Color(0xFF4A4A5A),
                                    fontSize = 13.sp,
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)
                                )
                            }
                        }
                    }

                    item { Spacer(Modifier.height(60.dp)) }
                }
            }
        }
    }
}

@Composable
private fun KnownForCard(movie: Movie, onClick: () -> Unit) {
    Column(
        modifier            = Modifier.width(110.dp).clickable { onClick() },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier.fillMaxWidth().height(160.dp)
                .clip(RoundedCornerShape(12.dp))
        ) {
            AsyncImage(
                model              = movie.posterUrl,
                contentDescription = movie.title,
                contentScale       = ContentScale.Crop,
                modifier           = Modifier.fillMaxSize(),
            )
            if (movie.rating != "0.0" && movie.rating.isNotBlank()) {
                Box(
                    Modifier.align(Alignment.TopEnd)
                        .padding(5.dp)
                        .background(Color.Black.copy(0.7f), RoundedCornerShape(4.dp))
                        .padding(horizontal = 4.dp, vertical = 2.dp)
                ) {
                    Text("⭐ ${movie.rating}", color = Color(0xFFFFD700), fontSize = 9.sp)
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            movie.title,
            color    = Color.White,
            fontSize = 11.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            lineHeight = 15.sp,
        )
        if (movie.year.isNotBlank()) {
            Text(movie.year, color = Color.Gray, fontSize = 10.sp)
        }
    }
}

private fun formatDate(raw: String): String {
    return try {
        val parts = raw.split("-")
        val months = listOf("","January","February","March","April","May","June",
            "July","August","September","October","November","December")
        val day   = parts.getOrNull(2)?.toIntOrNull() ?: return raw
        val month = parts.getOrNull(1)?.toIntOrNull() ?: return raw
        val year  = parts.getOrNull(0) ?: return raw
        val age   = if (java.util.Calendar.getInstance().get(java.util.Calendar.YEAR).toString() == year) ""
                    else " (${java.util.Calendar.getInstance().get(java.util.Calendar.YEAR) - year.toInt()} years old)"
        "${months.getOrNull(month) ?: month} $day, $year$age"
    } catch (_: Exception) { raw }
}
