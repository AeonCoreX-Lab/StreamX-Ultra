package com.aeoncorex.streamx

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import com.aeoncorex.streamx.navigation.AppNavigation
import com.aeoncorex.streamx.ui.theme.StreamXUltraTheme
import com.aeoncorex.streamx.ui.theme.ThemeViewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            // --- এই অংশটি সম্পূর্ণ পরিবর্তন করা হয়েছে ---

            // ১. ThemeViewModel তৈরি করা হচ্ছে
            // এটি পুরো অ্যাপের থিম স্টেট পরিচালনা করবে
            val themeViewModel: ThemeViewModel = viewModel(factory = object : ViewModelProvider.Factory {
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    @Suppress("UNCHECKED_CAST")
                    return ThemeViewModel(applicationContext) as T
                }
            })
            
            // ২. ViewModel থেকে বর্তমান থিমটি নেওয়া হচ্ছে
            val currentTheme = themeViewModel.theme.value
            
            // ৩. অ্যাপের মূল থিম হিসেবে বর্তমান থিমটি ব্যবহার করা হচ্ছে
            StreamXUltraTheme(appTheme = currentTheme) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    // ৪. AppNavigation-কে ViewModel পাস করা হচ্ছে
                    AppNavigation(themeViewModel = themeViewModel)
                }
            }
        }
    }
    
    // PiP মোডের জন্য আলাদা ফাংশনের আর প্রয়োজন নেই, কারণ এটি এখন PlayerScreen থেকে সরাসরি হ্যান্ডেল করা হয়।
    // কোডটি পরিষ্কার রাখার জন্য এটি সরিয়ে ফেলা হয়েছে।
}