package com.aeoncorex.streamx

import android.Manifest
import android.content.Context
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
import java.io.File
import java.io.FileOutputStream

class MainActivity : ComponentActivity() {

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean -> }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        
        // --- ব্যাকগ্রাউন্ড অপ্টিমাইজেশন ও AI মডেল এক্সট্রাকশন ---
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                TorrentEngine.clearCache(applicationContext)
                
                // অ্যাপ ওপেন হওয়ার সাথে সাথেই মডেল এক্সট্র্যাক্ট করা শুরু হবে
                val modelDir = File(filesDir, "sherpa-model")
                val essentialFiles = listOf("encoder.onnx", "decoder.onnx", "joiner.onnx", "tokens.txt")
                
                val allFilesExist = essentialFiles.all { File(modelDir, it).exists() }
                
                if (!allFilesExist) {
                    Log.d("StreamX_AI", "Extracting AI models in background...")
                    copyAssetFolder(applicationContext, "sherpa-model", modelDir)
                    Log.d("StreamX_AI", "AI models extraction complete.")
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

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
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                TorrentEngine.clearCache(applicationContext)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    // Asset কপি করার ইউটিলিটি ফাংশন
    private fun copyAssetFolder(context: Context, sourceFolder: String, destinationFolder: File) {
        if (!destinationFolder.exists()) destinationFolder.mkdirs()
        val assets = context.assets.list(sourceFolder) ?: return
        for (asset in assets) {
            val sourcePath = "$sourceFolder/$asset"
            val destFile = File(destinationFolder, asset)
            val subAssets = context.assets.list(sourcePath)
            if (!subAssets.isNullOrEmpty()) {
                copyAssetFolder(context, sourcePath, destFile)
            } else {
                if (!destFile.exists()) {
                    context.assets.open(sourcePath).use { input ->
                        FileOutputStream(destFile).use { output -> input.copyTo(output) }
                    }
                }
            }
        }
    }
}
