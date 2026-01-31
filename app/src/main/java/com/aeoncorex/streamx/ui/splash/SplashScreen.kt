package com.aeoncorex.streamx.ui.splash

import android.content.Context
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
import androidx.compose.ui.platform.LocalContext
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
    val context = LocalContext.current
    var startAnimation by remember { mutableStateOf(false) }

    // --- Animations ---
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

    // --- Navigation Logic ---
    LaunchedEffect(key1 = true) {
        startAnimation = true
        delay(3000) // Splash time

        // 1. Check if Onboarding is finished
        val sharedPref = context.getSharedPreferences("StreamXPrefs", Context.MODE_PRIVATE)
        val isOnboardingFinished = sharedPref.getBoolean("FinishedOnboarding", false)

        // 2. Determine Destination
        val destination = if (!isOnboardingFinished) {
            "onboarding" // First time user
        } else {
            val currentUser = FirebaseAuth.getInstance().currentUser
            if (currentUser != null) "home" else "auth" // Returning user
        }
        
        navController.navigate(destination) {
            popUpTo("splash") { inclusive = true }
        }
    }

    // --- UI Theme ---
    val darkBlue = Color(0xFF0A0A1E)
    val black = Color(0xFF000000)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(colors = listOf(darkBlue, black))),
        contentAlignment = Alignment.Center
    ) {
        Image(
            painter = painterResource(id = R.drawable.streamx_ultra_logo),
            contentDescription = "StreamX Ultra Logo",
            modifier = Modifier
                .fillMaxWidth(0.7f)
                .aspectRatio(1f)
                .graphicsLayer(scaleX = scaleAnim, scaleY = scaleAnim)
                .alpha(alphaAnim)
        )

        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 60.dp)
                .offset(y = textOffsetY)
                .alpha(textAlpha),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(text = "From", color = Color.Gray, fontSize = 12.sp)
            Spacer(modifier = Modifier.height(4.dp))
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
