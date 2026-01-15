package com.aeoncorex.streamx.ui.movie

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class MovieViewModel : ViewModel() {
    // Using StateFlow for reactive UI updates
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

    init {
        fetchAllMovies()
    }

    private fun fetchAllMovies() {
        viewModelScope.launch {
            _isLoading.value = true
            // Running in parallel could be faster, but sequential is safer for rate limits
            _trending.value = MovieRepository.getTrending()
            _popular.value = MovieRepository.getPopularMovies()
            _series.value = MovieRepository.getTopSeries()
            _action.value = MovieRepository.getActionMovies()
            _sciFi.value = MovieRepository.getSciFiMovies()
            _isLoading.value = false
        }
    }
}
