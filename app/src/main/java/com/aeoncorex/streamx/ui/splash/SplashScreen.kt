package com.aeoncorex.streamx.ui.splash

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.aeoncorex.streamx.R
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.delay

@Composable
fun SplashScreen(navController: NavController) {
    var startAnimation by remember { mutableStateOf(false) }

    // --- অ্যানিমেশনের জন্য স্টেট ভ্যারিয়েবল ---
    // প্রধান লোগোর জন্য জুম এবং ফেড অ্যানিমেশন
    val scaleAnim by animateFloatAsState(
        targetValue = if (startAnimation) 1f else 0.5f, // 0.5x থেকে 1x সাইজে আসবে
        animationSpec = tween(durationMillis = 1500)
    )
    val alphaAnim by animateFloatAsState(
        targetValue = if (startAnimation) 1f else 0f, // অদৃশ্য থেকে দৃশ্যমান হবে
        animationSpec = tween(durationMillis = 1500)
    )

    // নিচের ব্র্যান্ডিং টেক্সটের জন্য স্লাইড-আপ এবং ফেড অ্যানিমেশন
    val textOffsetY by animateDpAsState(
        targetValue = if (startAnimation) 0.dp else 100.dp, // 100dp নিচ থেকে উপরে আসবে
        animationSpec = tween(durationMillis = 1500, delayMillis = 500) // ৫০০ মিলিসেকেন্ড পরে শুরু হবে
    )
    val textAlpha by animateFloatAsState(
        targetValue = if (startAnimation) 1f else 0f,
        animationSpec = tween(durationMillis = 1500, delayMillis = 500)
    )

    // --- অ্যানিমেশন এবং নেভিগেশন চালু করা ---
    LaunchedEffect(key1 = true) {
        startAnimation = true
        delay(3500) // অ্যানিমেশনসহ স্প্ল্যাশ স্ক্রিন মোট কতক্ষণ দেখাবে
        
        val currentUser = FirebaseAuth.getInstance().currentUser
        val destination = if (currentUser != null) "home" else "auth"
        
        navController.navigate(destination) {
            popUpTo("splash") { inclusive = true }
        }
    }

    // --- UI ---
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(colors = listOf(Color(0xFF0A0A1E), Color(0xFF000000)))),
        contentAlignment = Alignment.Center
    ) {
        // প্রধান StreamX Ultra অ্যাপের লোগো
        Image(
            painter = painterResource(id = R.drawable.streamx_ultra_logo), // নিশ্চিত করুন এই নামে লোগো আছে
            contentDescription = "StreamX Ultra Logo",
            modifier = Modifier
                .size(200.dp)
                .graphicsLayer(scaleX = scaleAnim, scaleY = scaleAnim) // জুম অ্যানিমেশন
                .alpha(alphaAnim) // ফেড অ্যানিমেশন
        )

        // নিচের ব্র্যান্ডিং (কোম্পানির লোগো এবং টেক্সট)
        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 48.dp)
                .offset(y = textOffsetY) // স্লাইড-আপ অ্যানিমেশন
                .alpha(textAlpha), // ফেড অ্যানিমেশন
            verticalAlignment = Alignment.CenterVertically
        ) {
            // কোম্পানির ছোট লোগো
            Image(
                painter = painterResource(id = R.drawable.aeoncorex_logo),
                contentDescription = "AeonCoreX Company Logo",
                modifier = Modifier.size(24.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            // ব্র্যান্ডিং টেক্সট
            Text(
                text = "A Product of AeonCoreX",
                color = Color.White.copy(alpha = 0.8f),
                fontSize = 14.sp
            )
        }
    }
}