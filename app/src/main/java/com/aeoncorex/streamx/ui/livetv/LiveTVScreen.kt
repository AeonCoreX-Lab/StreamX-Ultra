package com.aeoncorex.streamx.ui.livetv

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.aeoncorex.streamx.ui.home.HomeViewModel
import com.aeoncorex.streamx.ui.home.FuturisticChannelCard
import com.aeoncorex.streamx.util.ChannelGenre
import java.net.URLEncoder

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LiveTVScreen(
    navController: NavController,
    homeViewModel: HomeViewModel = viewModel()
) {
    val allChannels by homeViewModel.allChannels.collectAsState()
    
    // Genre List logic
    val genres = remember { ChannelGenre.values().filterNot { it == ChannelGenre.UNKNOWN } }
    var selectedGenre by remember { mutableStateOf<ChannelGenre?>(null) } // Null means "All"

    // Efficiently filter channels
    val displayedChannels = remember(allChannels, selectedGenre) {
        if (selectedGenre == null) allChannels else allChannels.filter { it.genre == selectedGenre }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Live TV Guide") }) }
    ) { padding ->
        Column(modifier = Modifier.padding(padding)) {
            
            // 1. Genre Filter Chips (Horizontal Scroll)
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                item {
                    FilterChip(
                        selected = selectedGenre == null,
                        onClick = { selectedGenre = null },
                        label = { Text("All") },
                        colors = FilterChipDefaults.filterChipColors(selectedContainerColor = MaterialTheme.colorScheme.primary)
                    )
                }
                items(genres) { genre ->
                    FilterChip(
                        selected = selectedGenre == genre,
                        onClick = { selectedGenre = genre },
                        label = { Text(genre.name.lowercase().replaceFirstChar { it.uppercase() }) }
                    )
                }
            }

            // 2. Channel Grid (Better for browsing many channels)
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 100.dp),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                items(displayedChannels) { channel ->
                    FuturisticChannelCard(channel = channel, onClick = {
                        if (channel.streamUrls.isNotEmpty()) {
                            val encoded = URLEncoder.encode(channel.streamUrls.first(), "UTF-8")
                            navController.navigate("player/$encoded")
                        }
                    })
                }
            }
        }
    }
}
