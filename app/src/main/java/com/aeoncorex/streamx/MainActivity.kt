package com.aeoncorex.streamx

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import com.aeoncorex.streamx.navigation.AppNavigation
import com.aeoncorex.streamx.ui.music.MusicManager
import com.aeoncorex.streamx.ui.movie.TorrentEngine
import com.aeoncorex.streamx.ui.theme.StreamXUltraTheme
import com.aeoncorex.streamx.ui.theme.ThemeViewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // ১. লেটেস্ট অ্যান্ড্রয়েড ১৫ স্টাইল এজ-টু-এজ সাপোর্ট
        enableEdgeToEdge()
        
        // ২. পুরোনো ক্র্যাশ বা অবশিষ্টাংশ পরিষ্কার করা
        TorrentEngine.clearCache(applicationContext)
        
        // ৩. মিউজিক ইঞ্জিন ইনিশিয়ালাইজ
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
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    AppNavigation(themeViewModel = themeViewModel)
                }
            }
        }
    }

    override fun onDestroy() {
        // রিলিজ রিসোর্স এবং ক্যাশ ক্লিনআপ
        MusicManager.release()
        TorrentEngine.stop()
        TorrentEngine.clearCache(applicationContext)
        super.onDestroy()
    }
}
