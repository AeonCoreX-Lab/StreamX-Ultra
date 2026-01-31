package com.aeoncorex.streamx.ui.splash

import androidx.compose.animation.core.FastOutSlowInEasing
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.aeoncorex.streamx.R
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.delay

@Composable
fun SplashScreen(navController: NavController) {
    var startAnimation by remember { mutableStateOf(false) }

    // --- অ্যানিমেশনের জন্য স্টেট ভ্যারিয়েবল (Smoother Easing) ---
    val scaleAnim by animateFloatAsState(
        targetValue = if (startAnimation) 1f else 0.8f,
        animationSpec = tween(durationMillis = 1200, easing = FastOutSlowInEasing), label = "scale"
    )
    val alphaAnim by animateFloatAsState(
        targetValue = if (startAnimation) 1f else 0f,
        animationSpec = tween(durationMillis = 1000), label = "alpha"
    )

    val textOffsetY by animateDpAsState(
        targetValue = if (startAnimation) 0.dp else 50.dp,
        animationSpec = tween(durationMillis = 1200, delayMillis = 300, easing = FastOutSlowInEasing), label = "textOffset"
    )
    val textAlpha by animateFloatAsState(
        targetValue = if (startAnimation) 1f else 0f,
        animationSpec = tween(durationMillis = 1200, delayMillis = 300), label = "textAlpha"
    )

    // --- অ্যানিমেশন এবং নেভিগেশন ---
    LaunchedEffect(key1 = true) {
        startAnimation = true
        delay(3000) // মোট সময়
        
        val currentUser = FirebaseAuth.getInstance().currentUser
        val destination = if (currentUser != null) "home" else "auth"
        
        navController.navigate(destination) {
            popUpTo("splash") { inclusive = true }
        }
    }

    // --- UI Theme Colors (Matching the new Logo) ---
    val darkBlue = Color(0xFF0A0A1E)
    val black = Color(0xFF000000)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(colors = listOf(darkBlue, black))),
        contentAlignment = Alignment.Center
    ) {
        // প্রধান নতুন লোগো
        Image(
            painter = painterResource(id = R.drawable.streamx_ultra_logo), // আপনার নতুন লোগো
            contentDescription = "StreamX Ultra Logo",
            modifier = Modifier
                .fillMaxWidth(0.7f) // স্ক্রিনের প্রস্থের ৭০% জুড়ে থাকবে
                .aspectRatio(1f) // স্কয়ার শেইপ মেইনটেইন করবে
                .graphicsLayer(scaleX = scaleAnim, scaleY = scaleAnim)
                .alpha(alphaAnim)
        )

        // নিচের ব্র্যান্ডিং
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 60.dp)
                .offset(y = textOffsetY)
                .alpha(textAlpha),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "From",
                color = Color.Gray,
                fontSize = 12.sp
            )
            Spacer(modifier = Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                // যদি AeonCoreX এর লোগো থাকে তবে এটি আনকমেন্ট করুন
                 Image(
                    painter = painterResource(id = R.drawable.aeoncorex_logo),
                    contentDescription = "AeonCoreX Logo",
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp)) 
                Text(
                    text = "AeonCoreX Labs",
                    color = Color.White,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp
                )
            }
        }
    }
}
