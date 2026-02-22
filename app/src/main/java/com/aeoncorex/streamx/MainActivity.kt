package com.aeoncorex.streamx

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
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

    // নোটিফিকেশন পারমিশন রিকোয়েস্ট করার জন্য লঞ্চার
    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        // পারমিশন না দিলেও অ্যাপ চলবে, তবে নোটিফিকেশন কন্ট্রোল দেখা যাবে না
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Android 15 এর জন্য এজ-টু-এজ সাপোর্ট
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        
        // --- ১. ব্যাকগ্রাউন্ডে স্মার্ট ক্যাশ ক্লিন (পারফরম্যান্স অপ্টিমাইজেশন) ---
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                TorrentEngine.clearCache(applicationContext)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        // নোটিফিকেশন পারমিশন চেক করা
        checkNotificationPermission()

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

    private fun checkNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != 
                PackageManager.PERMISSION_GRANTED) {
                requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        MusicManager.release()
        
        // অ্যাপ বন্ধ হওয়ার সময়ও ব্যাকগ্রাউন্ডে ক্যাশ ক্লিয়ার করা
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                TorrentEngine.clearCache(applicationContext)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
}
