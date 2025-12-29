package com.aeoncorex.streamx

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
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
            // ১. ThemeViewModel তৈরি করা হচ্ছে (Factory ব্যবহার করে যাতে Context পাস করা যায়)
            val themeViewModel: ThemeViewModel = viewModel(factory = object : ViewModelProvider.Factory {
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    @Suppress("UNCHECKED_CAST")
                    return ThemeViewModel(applicationContext) as T
                }
            })
            
            // ২. ViewModel থেকে বর্তমান থিমটি observe করা হচ্ছে। 
            // এখানে 'by' ব্যবহার করা হয়েছে যাতে থিম চেঞ্জ হলে UI অটোমেটিক রি-কম্পোজ হয়।
            val currentTheme by themeViewModel.theme
            
            // ৩. অ্যাপের মূল থিম হিসেবে বর্তমান থিমটি সেট করা হচ্ছে
            StreamXUltraTheme(appTheme = currentTheme) {
                // Surface ব্যবহার করা জরুরি যাতে থিম অনুযায়ী ব্যাকগ্রাউন্ড কালার অটো আপডেট হয়
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    // ৪. AppNavigation-এ ViewModel পাস করা হচ্ছে যাতে সব স্ক্রিন থিম অ্যাক্সেস করতে পারে
                    AppNavigation(themeViewModel = themeViewModel)
                }
            }
        }
    }
}
