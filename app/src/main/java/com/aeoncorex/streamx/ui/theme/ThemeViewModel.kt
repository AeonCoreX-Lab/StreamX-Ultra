package com.aeoncorex.streamx.ui.theme

import android.content.Context
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "theme_prefs")
private val THEME_KEY = stringPreferencesKey("app_theme")

class ThemeViewModel(context: Context) : ViewModel() {
    private val _theme = mutableStateOf(UltraVioletTheme)
    val theme: State<AppTheme> = _theme
    
    private val dataStore = context.dataStore

    init {
        viewModelScope.launch {
            dataStore.data.map { preferences ->
                val themeName = preferences[THEME_KEY] ?: ThemeName.ULTRA_VIOLET.name
                themes.find { it.name.name == themeName } ?: UltraVioletTheme
            }.collect {
                _theme.value = it
            }
        }
    }

    fun changeTheme(themeName: ThemeName) {
        viewModelScope.launch {
            dataStore.edit { preferences ->
                preferences[THEME_KEY] = themeName.name
            }
        }
    }
}