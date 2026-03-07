package com.aeoncorex.streamx

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.aeoncorex.streamx.navigation.AppNavigation
import com.aeoncorex.streamx.ui.music.MusicManager
import com.aeoncorex.streamx.ui.movie.TorrentEngine
import com.aeoncorex.streamx.ui.theme.StreamXUltraTheme
import com.aeoncorex.streamx.ui.theme.ThemeViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    companion object {
        private const val TAG = "StreamX_Native"
        init {
            try {
                // 1. MUST LOAD C++ SHARED FIRST to resolve NDK 27 symbols for libmpv.so
                System.loadLibrary("c++_shared")
                
                // 2. Load FFmpeg Dependencies
                System.loadLibrary("avutil")
                System.loadLibrary("swresample")
                System.loadLibrary("avcodec")
                System.loadLibrary("avformat")
                System.loadLibrary("swscale")
                System.loadLibrary("avfilter")
                System.loadLibrary("avdevice")
                
                // 3. Load MPV
                System.loadLibrary("mpv")
                
                // 4. Load Main Native Logic (C++ & Rust)
                System.loadLibrary("streamx-native")
                
                Log.d(TAG, "✅ All Native Libraries Loaded Successfully")
            } catch (e: Throwable) {
                Log.e(TAG, "❌ Critical Library Load Error: ${e.message}")
            }
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                TorrentEngine.clearCache(applicationContext)
            } catch (e: Exception) {
                Log.e("MainActivity", "Cache clear failed")
            }
        }

        checkPermissions()
        MusicManager.initialize(applicationContext)

        setContent {
            val themeViewModel: ThemeViewModel = viewModel(factory = object : ViewModelProvider.Factory {
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    @Suppress("UNCHECKED_CAST")
                    return ThemeViewModel(applicationContext) as T
                }
            })
            val currentTheme by themeViewModel.theme
            StreamXUltraTheme(appTheme = currentTheme) {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    AppNavigation(themeViewModel = themeViewModel)
                }
            }
        }
    }

    private fun checkPermissions() {
        val permissions = mutableListOf(Manifest.permission.RECORD_AUDIO) 
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        val notGranted = permissions.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (notGranted.isNotEmpty()) permissionLauncher.launch(notGranted.toTypedArray())
    }

    override fun onDestroy() {
        super.onDestroy()
        MusicManager.release()
    }
}
