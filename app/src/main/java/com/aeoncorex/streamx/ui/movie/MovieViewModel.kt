package com.aeoncorex.streamx.ui.movie

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class MovieViewModel : ViewModel() {
    // Existing States
    private val _trending = MutableStateFlow<List<Movie>>(emptyList())
    val trending: StateFlow<List<Movie>> = _trending.asStateFlow()

    private val _popular = MutableStateFlow<List<Movie>>(emptyList())
    val popular: StateFlow<List<Movie>> = _popular.asStateFlow()

    private val _series = MutableStateFlow<List<Movie>>(emptyList())
    val series: StateFlow<List<Movie>> = _series.asStateFlow()

    private val _action = MutableStateFlow<List<Movie>>(emptyList())
    val action: StateFlow<List<Movie>> = _action.asStateFlow()

    private val _sciFi = MutableStateFlow<List<Movie>>(emptyList())
    val sciFi: StateFlow<List<Movie>> = _sciFi.asStateFlow()

    private val _isLoading = MutableStateFlow(true)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    // --- NEW: SEARCH STATES ---
    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _searchResults = MutableStateFlow<List<Movie>>(emptyList())
    val searchResults: StateFlow<List<Movie>> = _searchResults.asStateFlow()

    private val _isSearchActive = MutableStateFlow(false)
    val isSearchActive: StateFlow<Boolean> = _isSearchActive.asStateFlow()

    private var searchJob: Job? = null

    init {
        fetchAllMovies()
    }

    private fun fetchAllMovies() {
        viewModelScope.launch {
            _isLoading.value = true
            _trending.value = MovieRepository.getTrending()
            _popular.value = MovieRepository.getPopularMovies()
            _series.value = MovieRepository.getTopSeries()
            _action.value = MovieRepository.getActionMovies()
            _sciFi.value = MovieRepository.getSciFiMovies()
            _isLoading.value = false
        }
    }

    // --- NEW: SEARCH LOGIC ---
    fun onSearchQueryChange(query: String) {
        _searchQuery.value = query
        searchJob?.cancel() // Cancel previous search if typing continues
        
        if (query.isNotEmpty()) {
            searchJob = viewModelScope.launch {
                delay(500) // 500ms Debounce to reduce API calls
                _searchResults.value = MovieRepository.searchMovies(query)
            }
        } else {
            _searchResults.value = emptyList()
        }
    }

    fun onSearchActiveChange(active: Boolean) {
        _isSearchActive.value = active
        if (!active) {
            _searchQuery.value = ""
            _searchResults.value = emptyList()
        }
    }
}
