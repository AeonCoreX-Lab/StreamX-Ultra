package com.aeoncorex.streamx.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.aeoncorex.streamx.ui.auth.AuthScreen
import com.aeoncorex.streamx.ui.home.HomeScreen
import com.aeoncorex.streamx.ui.player.PlayerScreen
import com.aeoncorex.streamx.ui.splash.SplashScreen

@Composable
fun AppNavigation() {
    val navController = rememberNavController()
    NavHost(navController = navController, startDestination = "splash") {
        composable("splash") {
            SplashScreen(navController)
        }
        composable("auth") {
            AuthScreen(navController)
        }
        composable("home") {
            HomeScreen(navController)
        }
        
        // --- এই অংশটি সম্পূর্ণ আপডেট করা হয়েছে ---
        composable(
            // পরিবর্তন ১: Route-এর আর্গুমেন্টের নাম পরিবর্তন করা হলো স্পষ্টতার জন্য
            route = "player/{encodedUrl}", 
            arguments = listOf(
                navArgument("encodedUrl") { type = NavType.StringType }
            )
        ) { backStackEntry ->
            // পরিবর্তন ২: এনকোড করা URL সরাসরি গ্রহণ করা হচ্ছে
            val encodedUrl = backStackEntry.arguments?.getString("encodedUrl") ?: ""
            
            // PlayerScreen-কে এখন এনকোড করা URL-টি সরাসরি পাঠানো হচ্ছে
            // এখানে আর কোনো ডিকোডিং করা হচ্ছে না।
            PlayerScreen(
                // পরিবর্তন ৩: সঠিক প্যারামিটারের নাম (encodedUrl) ব্যবহার করা হচ্ছে
                encodedUrl = encodedUrl, 
                onBack = { navController.popBackStack() }
            )
        }
    }
}

// এই ইউটিলিটি ফাংশনটির এখানে প্রয়োজন নেই, কারণ এটি HomeScreen-এ ব্যবহার করা হয়।
// ফাইলটি পরিষ্কার রাখার জন্য এটি মুছে ফেলতে পারেন অথবা রেখে দিতে পারেন।
/*
fun encodeUrl(url: String): String {
    return URLEncoder.encode(url, StandardCharsets.UTF_8.toString())
}
*/