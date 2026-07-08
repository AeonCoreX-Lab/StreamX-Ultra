package com.aeoncorex.streamx.ui.movie

import android.app.Activity
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.aeoncorex.streamx.ads.AdManager
import com.aeoncorex.streamx.streaming.MovieBoxDub
import com.aeoncorex.streamx.streaming.MovieBoxException
import com.aeoncorex.streamx.streaming.MovieBoxItemDetails
import com.aeoncorex.streamx.streaming.MovieBoxNative
import com.aeoncorex.streamx.streaming.MovieBoxStreamResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.net.URLDecoder
import java.net.URLEncoder

// ── Language chips shown in the torrent filter row ────────────────────────
private data class LangChip(
    val lang:  DubLanguage,
    val flag:  String,
    val label: String
)

private val LANG_CHIPS = listOf(
    LangChip(DubLanguage.English,   "🇺🇸", "English"),
    LangChip(DubLanguage.Hindi,     "🇮🇳", "Hindi"),
    LangChip(DubLanguage.Tamil,     "🇮🇳", "Tamil"),
    LangChip(DubLanguage.Telugu,    "🇮🇳", "Telugu"),
    LangChip(DubLanguage.Bengali,   "🇧🇩", "Bengali"),
    LangChip(DubLanguage.Kannada,   "🇮🇳", "Kannada"),
    LangChip(DubLanguage.Malayalam, "🇮🇳", "Malayalam"),
    LangChip(DubLanguage.Japanese,  "🇯🇵", "Japanese"),
    LangChip(DubLanguage.DualAudio, "🌐",  "Dual Audio"),
)

// ═══════════════════════════════════════════════════════════════════════════
//  MovieBox section state — real-time dub + episode aware
// ═══════════════════════════════════════════════════════════════════════════

@Stable
private class MovieBoxSectionState {
    var isResolvingSubject by mutableStateOf(true)
    var subjectNotFound     by mutableStateOf(false)
    var itemDetails         by mutableStateOf<MovieBoxItemDetails?>(null)
    var selectedDub         by mutableStateOf<MovieBoxDub?>(null)
    var streamResult        by mutableStateOf<MovieBoxStreamResult?>(null)
    var isCheckingStream    by mutableStateOf(false)
    var streamError         by mutableStateOf<String?>(null)

    val isAvailable: Boolean
        get() = streamResult?.hasResource == true && streamResult?.bestPlayableUrl() != null
}

private suspend fun MovieBoxSectionState.resolveSubject(title: String) {
    isResolvingSubject = true
    subjectNotFound = false
    itemDetails = null
    selectedDub = null
    try {
        val results = MovieBoxNative.search(title)
        val best = results.firstOrNull()
        if (best == null) {
            subjectNotFound = true
            return
        }
        val details = MovieBoxNative.getItemDetails(best.subjectId)
        itemDetails = details
        selectedDub = details.dubs.firstOrNull { it.original } ?: details.dubs.firstOrNull()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        subjectNotFound = true
    } finally {
        isResolvingSubject = false
    }
}

private suspend fun MovieBoxSectionState.checkStreamFor(dub: MovieBoxDub, se: Int, ep: Int) {
    isCheckingStream = true
    streamError = null
    streamResult = null
    try {
        val result = MovieBoxNative.getStreams(dub.subjectId, se, ep)
        streamResult = result
        if (!result.hasResource || result.bestPlayableUrl() == null) {
            streamError = "No stream available for ${dub.displayName} · S${se}E${ep}"
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: MovieBoxException) {
        streamError = e.message ?: "Stream lookup failed"
    } catch (e: Exception) {
        streamError = "Stream lookup failed: ${e.localizedMessage}"
    } finally {
        isCheckingStream = false
    }
}

// ═══════════════════════════════════════════════════════════════════════════
//  Screen
// ═══════════════════════════════════════════════════════════════════════════

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MovieLinkSelectionScreen(
    navController: NavController,
    imdbId: String, tmdbId: Int, title: String,
    type: String, season: Int, episode: Int
) {
    val context      = LocalContext.current
    val activity     = context as? Activity
    val decodedTitle = remember(title) {
        try { URLDecoder.decode(title, "UTF-8") } catch (_: Exception) { title }
    }
    val isSeries = type.equals("SERIES", true) || type.equals("TV", true)

    // ── Episode/season selection — starts from whatever MovieDetailsScreen
    // passed in, but is fully user-adjustable from here now, for BOTH the
    // torrent flow and the MovieBox flow. This is the piece that was
    // previously missing: MovieDetailsScreen fixed (season, episode) once
    // and this screen never let the user change it without navigating back.
    var selectedSeason  by remember { mutableIntStateOf(season.coerceAtLeast(1)) }
    var selectedEpisode by remember { mutableIntStateOf(episode.coerceAtLeast(1)) }
    var availableSeasons  by remember { mutableStateOf<List<SeasonDto>>(emptyList()) }
    var availableEpisodes by remember { mutableStateOf<List<EpisodeDto>>(emptyList()) }
    var isLoadingSeasons  by remember { mutableStateOf(isSeries) }
    var isLoadingEpisodes by remember { mutableStateOf(false) }
    var showEpisodeSheet  by remember { mutableStateOf(false) }

    var selectedLang by remember { mutableStateOf<DubLanguage>(DubLanguage.English) }
    var torrentLinks by remember { mutableStateOf<List<StreamLink>>(emptyList()) }
    var isLoading    by remember { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var adLoading    by remember { mutableStateOf(false) }

    val movieBoxState = remember { MovieBoxSectionState() }

    // ── Load season list once (series only) — needed so the episode
    // sheet shows real season numbers/episode counts instead of a blind
    // stepper. Movies skip this entirely (isLoadingSeasons starts false).
    LaunchedEffect(tmdbId, isSeries) {
        if (!isSeries || tmdbId <= 0) { isLoadingSeasons = false; return@LaunchedEffect }
        isLoadingSeasons = true
        try {
            val full = MovieRepository.getFullDetails(tmdbId, MovieType.SERIES)
            availableSeasons = full?.seasons ?: emptyList()
        } catch (_: Exception) {
            availableSeasons = emptyList()
        } finally {
            isLoadingSeasons = false
        }
    }

    // ── Load episode list whenever the selected season changes ─────────
    LaunchedEffect(selectedSeason, isSeries, tmdbId) {
        if (!isSeries || tmdbId <= 0) return@LaunchedEffect
        isLoadingEpisodes = true
        try {
            availableEpisodes = MovieRepository.getEpisodes(tmdbId, selectedSeason)
        } catch (_: Exception) {
            availableEpisodes = emptyList()
        } finally {
            isLoadingEpisodes = false
        }
    }

    // ── Torrent search — re-fires on title/season/episode/language change
    LaunchedEffect(decodedTitle, selectedSeason, selectedEpisode, selectedLang) {
        isLoading = true; errorMessage = null; torrentLinks = emptyList()
        try {
            val movieType = if (isSeries) MovieType.SERIES else MovieType.MOVIE
            val isAnime   = listOf("Naruto","One Piece","Demon Slayer","Jujutsu","Attack on Titan","Dragon Ball")
                .any { decodedTitle.contains(it, ignoreCase = true) }
            val validImdb = if (imdbId != "null" && imdbId.isNotEmpty()) imdbId else null
            val result    = TorrentRepository.getStreamLinks(
                type = movieType, title = decodedTitle, imdbId = validImdb,
                season = selectedSeason, episode = selectedEpisode, isAnime = isAnime,
                dubLang = selectedLang
            )
            currentCoroutineContext().ensureActive()
            torrentLinks = result
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            errorMessage = "Search failed: ${e.localizedMessage}"
        } finally {
            isLoading = false
        }
    }

    // ── MovieBox subject resolution — once per title, independent of
    // season/episode (a subject covers all episodes of a series).
    LaunchedEffect(decodedTitle) {
        movieBoxState.resolveSubject(decodedTitle)
    }

    // ── MovieBox stream re-check — fires on dub change OR episode change,
    // so switching either one always re-validates against the real API
    // instead of assuming the previous result still applies.
    LaunchedEffect(movieBoxState.selectedDub, selectedSeason, selectedEpisode) {
        val dub = movieBoxState.selectedDub ?: return@LaunchedEffect
        movieBoxState.checkStreamFor(dub, se = selectedSeason, ep = selectedEpisode)
    }

    fun playTorrent(magnet: String) {
        if (activity == null) return
        adLoading = true
        AdManager.showInterstitial(activity) {
            adLoading = false
            val enc = URLEncoder.encode(magnet, "UTF-8")
            val encImdb = URLEncoder.encode(imdbId.takeIf { it != "null" } ?: "", "UTF-8")
            navController.navigate("torrent_player/$enc?imdbId=$encImdb")
        }
    }

    fun playMovieBoxStream(streamUrl: String) {
        if (activity == null) return
        adLoading = true
        AdManager.showInterstitial(activity) {
            adLoading = false
            // Tag with MOVIEBOX_URL_MARKER so MoviePlayerScreen's
            // PlaybackMode detection shows the instant-play UI (no
            // torrent seeds/speed stats, indeterminate buffering spinner,
            // "Instant" badge) instead of assuming it's a bare direct URL.
            // MoviePlayerScreen strips this prefix before it ever reaches
            // MPV — the player only ever opens the real stream URL.
            val tagged = MOVIEBOX_URL_MARKER + streamUrl
            val enc = URLEncoder.encode(tagged, "UTF-8")
            val encImdb = URLEncoder.encode(imdbId.takeIf { it != "null" } ?: "", "UTF-8")
            // Pass through the exact dub subject_id + se/ep that produced
            // this URL, so MoviePlayerScreen can re-call
            // MovieBoxNative.getStreams() for a fresh signed URL if this
            // one expires mid-playback, instead of failing with no
            // recovery. Falls back to empty/0 if for some reason no dub
            // is selected (shouldn't happen — onPlay only fires when
            // state.isAvailable is true, which requires a selected dub).
            val dubSubjectId = movieBoxState.selectedDub?.subjectId ?: ""
            val encMbSubjectId = URLEncoder.encode(dubSubjectId, "UTF-8")
            navController.navigate(
                "torrent_player/$enc?imdbId=$encImdb&mbSubjectId=$encMbSubjectId&mbSe=$selectedSeason&mbEp=$selectedEpisode"
            )
        }
    }

    Box(Modifier.fillMaxSize()) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Column {
                            Text("4K / 1080P Sources", color = Color.White,
                                fontSize = 15.sp, fontWeight = FontWeight.Bold)
                            Text(
                                if (isSeries) "$decodedTitle · S${selectedSeason}E${selectedEpisode}" else decodedTitle,
                                color = Color.Gray, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis
                            )
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = { navController.popBackStack() }) {
                            Icon(Icons.Default.ArrowBack, null, tint = Color.Cyan)
                        }
                    },
                    actions = {
                        if (isSeries) {
                            TextButton(onClick = { showEpisodeSheet = true }) {
                                Icon(Icons.Rounded.ViewList, null, tint = Color.Cyan, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("S${selectedSeason}E${selectedEpisode}", color = Color.Cyan, fontSize = 13.sp)
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFF0A0A14))
                )
            },
            containerColor = Color.Black
        ) { padding ->
            Box(
                Modifier.fillMaxSize().padding(padding)
                    .background(Brush.verticalGradient(listOf(Color(0xFF0A0A14), Color.Black)))
            ) {
                Column(Modifier.fillMaxSize()) {

                    // ── MovieBox Instant Play (real-time dub + episode aware) ──
                    MovieBoxInstantPlayCard(
                        state = movieBoxState,
                        onPlay = ::playMovieBoxStream,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp)
                    )

                    // ── Language Filter Row (torrent) ────────────────────
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(LANG_CHIPS) { chip ->
                            val isSelected = selectedLang::class == chip.lang::class
                            Box(
                                modifier = Modifier
                                    .background(
                                        if (isSelected) Color(0xFF003333) else Color(0xFF0F0F1A),
                                        RoundedCornerShape(20.dp)
                                    )
                                    .border(
                                        1.dp,
                                        if (isSelected) Color.Cyan else Color.White.copy(0.1f),
                                        RoundedCornerShape(20.dp)
                                    )
                                    .clickable { selectedLang = chip.lang }
                                    .padding(horizontal = 12.dp, vertical = 7.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                                ) {
                                    Text(chip.flag, fontSize = 14.sp)
                                    Text(
                                        chip.label,
                                        color = if (isSelected) Color.Cyan else Color.White.copy(0.75f),
                                        fontSize = 12.sp,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                                    )
                                }
                            }
                        }
                    }

                    // ── Results area (torrent) ───────────────────────────
                    Box(Modifier.fillMaxSize()) {
                        when {
                            isLoading -> Column(
                                Modifier.align(Alignment.Center),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                CircularProgressIndicator(color = Color.Cyan, modifier = Modifier.size(36.dp), strokeWidth = 3.dp)
                                Spacer(Modifier.height(12.dp))
                                val loadMsg = if (selectedLang == DubLanguage.English)
                                    "Scanning P2P Networks…"
                                else
                                    "Searching ${LANG_CHIPS.find { it.lang::class == selectedLang::class }?.label ?: ""} dubs…"
                                Text(loadMsg, color = Color.Gray, fontSize = 13.sp)
                                Text("YTS · RARBG · EZTV · 1337x", color = Color.Gray.copy(0.5f), fontSize = 11.sp)
                            }
                            errorMessage != null -> Column(
                                Modifier.align(Alignment.Center).padding(24.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Icon(Icons.Rounded.SearchOff, null, tint = Color.Gray, modifier = Modifier.size(44.dp))
                                Spacer(Modifier.height(12.dp))
                                Text(errorMessage!!, color = Color.Gray, textAlign = TextAlign.Center, fontSize = 13.sp)
                            }
                            torrentLinks.isEmpty() -> Column(
                                Modifier.align(Alignment.Center).padding(24.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Icon(Icons.Rounded.CloudOff, null, tint = Color.Gray, modifier = Modifier.size(44.dp))
                                Spacer(Modifier.height(12.dp))
                                val langLabel = LANG_CHIPS.find { it.lang::class == selectedLang::class }?.label ?: "selected language"
                                Text("No $langLabel torrents found", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                                Text(
                                    if (selectedLang == DubLanguage.English) "Try instant stream instead"
                                    else "Try a different language or instant stream",
                                    color = Color.Gray, fontSize = 13.sp, textAlign = TextAlign.Center
                                )
                                Spacer(Modifier.height(16.dp))
                                Button(
                                    onClick = { navController.popBackStack() },
                                    colors  = ButtonDefaults.buttonColors(containerColor = Color(0xFF0E2A1E))
                                ) { Text("← Use Instant Play", color = Color.Cyan) }
                            }
                            else -> LazyColumn(
                                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                item {
                                    val langLabel = LANG_CHIPS.find { it.lang::class == selectedLang::class }
                                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 4.dp)) {
                                        Icon(Icons.Rounded.DownloadForOffline, null, tint = Color.Cyan, modifier = Modifier.size(16.dp))
                                        Spacer(Modifier.width(6.dp))
                                        Text(
                                            "${torrentLinks.size} ${langLabel?.flag ?: ""} ${langLabel?.label ?: ""} sources found",
                                            color = Color.Cyan, fontSize = 13.sp, fontWeight = FontWeight.Bold
                                        )
                                    }
                                }
                                items(torrentLinks) { link ->
                                    TorrentCard(link) { playTorrent(link.magnet) }
                                }
                                item { Spacer(Modifier.height(20.dp)) }
                            }
                        }
                    }
                }
            }
        }

        if (adLoading) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(0.88f)), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Color.Cyan, modifier = Modifier.size(40.dp))
            }
        }

        if (showEpisodeSheet && isSeries) {
            EpisodeSelectSheet(
                seasons = availableSeasons,
                episodes = availableEpisodes,
                selectedSeason = selectedSeason,
                selectedEpisode = selectedEpisode,
                isLoadingSeasons = isLoadingSeasons,
                isLoadingEpisodes = isLoadingEpisodes,
                onSeasonSelected = { selectedSeason = it },
                onEpisodeSelected = { selectedEpisode = it; showEpisodeSheet = false },
                onDismiss = { showEpisodeSheet = false }
            )
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════
//  Episode/season picker sheet
// ═══════════════════════════════════════════════════════════════════════════

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EpisodeSelectSheet(
    seasons: List<SeasonDto>,
    episodes: List<EpisodeDto>,
    selectedSeason: Int,
    selectedEpisode: Int,
    isLoadingSeasons: Boolean,
    isLoadingEpisodes: Boolean,
    onSeasonSelected: (Int) -> Unit,
    onEpisodeSelected: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF0A0A14)
    ) {
        Column(Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
            Text(
                "Select Episode",
                color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp)
            )

            // ── Season row ───────────────────────────────────────────
            if (isLoadingSeasons) {
                Box(Modifier.fillMaxWidth().height(48.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = Color.Cyan, modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                }
            } else if (seasons.isNotEmpty()) {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(seasons) { s ->
                        val isSelected = s.seasonNumber == selectedSeason
                        Box(
                            Modifier
                                .background(
                                    if (isSelected) Color(0xFF003333) else Color(0xFF12121C),
                                    RoundedCornerShape(10.dp)
                                )
                                .border(
                                    1.dp,
                                    if (isSelected) Color.Cyan else Color.White.copy(0.1f),
                                    RoundedCornerShape(10.dp)
                                )
                                .clickable { onSeasonSelected(s.seasonNumber) }
                                .padding(horizontal = 16.dp, vertical = 10.dp)
                        ) {
                            Text(
                                "S${s.seasonNumber}",
                                color = if (isSelected) Color.Cyan else Color.White.copy(0.8f),
                                fontSize = 13.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                            )
                        }
                    }
                }
            } else {
                // Fallback when no season metadata is available at all —
                // still lets the user step through season numbers manually
                // instead of being stuck on whatever MovieDetailsScreen sent.
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = { if (selectedSeason > 1) onSeasonSelected(selectedSeason - 1) }) {
                        Icon(Icons.Rounded.Remove, null, tint = Color.Cyan)
                    }
                    Text(
                        "Season $selectedSeason", color = Color.White, fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f), textAlign = TextAlign.Center
                    )
                    IconButton(onClick = { onSeasonSelected(selectedSeason + 1) }) {
                        Icon(Icons.Rounded.Add, null, tint = Color.Cyan)
                    }
                }
            }

            Spacer(Modifier.height(12.dp))
            HorizontalDivider(color = Color.White.copy(0.08f))
            Spacer(Modifier.height(8.dp))

            // ── Episode list ─────────────────────────────────────────
            Box(Modifier.heightIn(max = 420.dp)) {
                when {
                    isLoadingEpisodes -> Box(
                        Modifier.fillMaxWidth().height(200.dp), contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(color = Color.Cyan, modifier = Modifier.size(28.dp), strokeWidth = 2.dp)
                    }
                    episodes.isEmpty() -> Box(
                        Modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.Center
                    ) {
                        Text("No episode metadata — pick manually below", color = Color.Gray, fontSize = 12.sp)
                    }
                    else -> LazyColumn(
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        items(episodes) { ep ->
                            val isSelected = ep.episodeNumber == selectedEpisode
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .background(
                                        if (isSelected) Color(0xFF003333) else Color(0xFF12121C),
                                        RoundedCornerShape(10.dp)
                                    )
                                    .border(
                                        1.dp,
                                        if (isSelected) Color.Cyan else Color.White.copy(0.07f),
                                        RoundedCornerShape(10.dp)
                                    )
                                    .clickable { onEpisodeSelected(ep.episodeNumber) }
                                    .padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    Modifier.size(28.dp).background(Color(0xFF0A0A1A), RoundedCornerShape(6.dp)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text("${ep.episodeNumber}", color = Color.Cyan, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                }
                                Spacer(Modifier.width(10.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        ep.name ?: "Episode ${ep.episodeNumber}",
                                        color = Color.White, fontSize = 13.sp,
                                        maxLines = 1, overflow = TextOverflow.Ellipsis
                                    )
                                    if (ep.runtime != null) {
                                        Text("${ep.runtime} min", color = Color.Gray, fontSize = 11.sp)
                                    }
                                }
                                if (isSelected) {
                                    Icon(Icons.Rounded.CheckCircle, null, tint = Color.Cyan, modifier = Modifier.size(18.dp))
                                }
                            }
                        }
                    }
                }
            }

            // Manual fallback stepper — always available even with a full
            // episode list, for shows whose TMDB episode count is wrong/lagging.
            Spacer(Modifier.height(8.dp))
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                IconButton(onClick = { if (selectedEpisode > 1) onEpisodeSelected(selectedEpisode - 1) }) {
                    Icon(Icons.Rounded.Remove, null, tint = Color.Gray)
                }
                Text("Ep. $selectedEpisode", color = Color.Gray, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 12.dp))
                IconButton(onClick = { onEpisodeSelected(selectedEpisode + 1) }) {
                    Icon(Icons.Rounded.Add, null, tint = Color.Gray)
                }
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════
//  MovieBox Instant Play card
// ═══════════════════════════════════════════════════════════════════════════

@Composable
private fun MovieBoxInstantPlayCard(
    state: MovieBoxSectionState,
    onPlay: (streamUrl: String) -> Unit,
    modifier: Modifier = Modifier
) {
    if (state.subjectNotFound && !state.isResolvingSubject) return

    Column(
        modifier
            .fillMaxWidth()
            .background(
                Brush.horizontalGradient(listOf(Color(0xFF0A1F1A), Color(0xFF0A0A14))),
                RoundedCornerShape(14.dp)
            )
            .border(1.dp, Color(0xFF00E676).copy(alpha = 0.25f), RoundedCornerShape(14.dp))
            .padding(14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.FlashOn, null, tint = Color(0xFF00E676), modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text("Instant Play", color = Color(0xFF00E676), fontSize = 14.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            if (state.isResolvingSubject) {
                CircularProgressIndicator(color = Color(0xFF00E676), modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
            }
        }

        when {
            state.isResolvingSubject -> {
                Spacer(Modifier.height(6.dp))
                Text("Checking instant sources…", color = Color.Gray, fontSize = 12.sp)
            }

            state.itemDetails != null -> {
                Spacer(Modifier.height(8.dp))

                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(state.itemDetails!!.dubs) { dub ->
                        val isSelected = dub.subjectId == state.selectedDub?.subjectId
                        Box(
                            Modifier
                                .background(
                                    if (isSelected) Color(0xFF003322) else Color(0xFF12121C),
                                    RoundedCornerShape(16.dp)
                                )
                                .border(
                                    1.dp,
                                    if (isSelected) Color(0xFF00E676) else Color.White.copy(0.1f),
                                    RoundedCornerShape(16.dp)
                                )
                                .clickable { state.selectedDub = dub }
                                .padding(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            Text(
                                dub.displayName,
                                color = if (isSelected) Color(0xFF00E676) else Color.White.copy(0.75f),
                                fontSize = 12.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                            )
                        }
                    }
                }

                Spacer(Modifier.height(10.dp))

                when {
                    state.isCheckingStream -> Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(color = Color.Gray, modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text("Resolving stream…", color = Color.Gray, fontSize = 12.sp)
                    }

                    state.isAvailable -> Button(
                        onClick = { state.streamResult?.bestPlayableUrl()?.let(onPlay) },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00E676)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Rounded.PlayArrow, null, tint = Color.Black, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(
                            "Play ${state.selectedDub?.displayName ?: ""} · ${state.streamResult?.sources?.maxByOrNull { it.resolutions }?.resolutions ?: 0}p",
                            color = Color.Black, fontWeight = FontWeight.Bold, fontSize = 13.sp
                        )
                    }

                    state.streamError != null -> Text(
                        state.streamError!!, color = Color.Gray, fontSize = 12.sp
                    )
                }
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════
//  Torrent result card (unchanged)
// ═══════════════════════════════════════════════════════════════════════════

@Composable
fun TorrentCard(link: StreamLink, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF0F0F1A), RoundedCornerShape(10.dp))
            .border(1.dp, Color.White.copy(0.07f), RoundedCornerShape(10.dp))
            .clickable { onClick() }
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(44.dp)) {
            val seedColor = if (link.seeds > 20) Color.Green else if (link.seeds > 5) Color.Yellow else Color.Red
            Icon(Icons.Rounded.ArrowUpward, null, tint = seedColor, modifier = Modifier.size(18.dp))
            Text("${link.seeds}", color = seedColor, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            Text("seeds", color = Color.Gray, fontSize = 9.sp)
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(link.title, color = Color.White, fontSize = 13.sp,
                fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(5.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                val (qbg, qfg) = when (link.quality) {
                    "4K","2160P" -> Color(0xFF1A0A00) to Color(0xFFFF6D00)
                    "1080P"      -> Color(0xFF001A0A) to Color(0xFF00E676)
                    "720P"       -> Color(0xFF001A1A) to Color(0xFF00E5FF)
                    else         -> Color(0xFF1A1A1A) to Color.Gray
                }
                Box(Modifier.background(qbg, RoundedCornerShape(4.dp)).padding(horizontal = 6.dp, vertical = 2.dp)) {
                    Text(link.quality, color = qfg, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                }
                Box(
                    Modifier.background(Color(0xFF0A0A1A), RoundedCornerShape(4.dp))
                        .border(1.dp, Color.Cyan.copy(0.2f), RoundedCornerShape(4.dp))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) { Text(link.source.take(8), color = Color.Cyan.copy(0.8f), fontSize = 9.sp) }
                if (link.size.isNotEmpty()) {
                    Text(link.size, color = Color.Gray, fontSize = 10.sp,
                        modifier = Modifier.align(Alignment.CenterVertically))
                }
            }
        }
        Icon(Icons.Rounded.PlayCircle, null, tint = Color.Cyan, modifier = Modifier.size(30.dp))
    }
}
