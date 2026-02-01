package com.aeoncorex.streamx.ui.onboarding

import android.content.Context
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive // Import added for safer loop

// --- Futuristic Neon Color Palette ---
val DeepSpaceBlack = Color(0xFF050510)
val NeonBlue = Color(0xFF2979FF)    // Welcome / System Start
val NeonCyan = Color(0xFF00E5FF)    // Movie Engine
val NeonPurple = Color(0xFFD500F9)  // Live TV
val NeonGreen = Color(0xFF00E676)   // Ad Blocker
val NeonOrange = Color(0xFFFF3D00)  // Music

data class OnboardingPage(
    val title: String,
    val description: String,
    val primaryColor: Color,
    val type: PageType
)

enum class PageType { WELCOME, MOVIE_ENGINE, LIVE_TV, AD_BLOCKER, MUSIC }

val onboardingPages = listOf(
    OnboardingPage(
        title = "Welcome to StreamX",
        description = "Initiating Quantum Entertainment Protocol. Experience the absolute pinnacle of next-gen streaming technology.",
        primaryColor = NeonBlue,
        type = PageType.WELCOME
    ),
    OnboardingPage(
        title = "Ultra-Core Movie Engine",
        description = "Powered by our proprietary Next-Gen Engine. Watch 4K movies via Public Cloud & Torrents with Zero Buffering.",
        primaryColor = NeonCyan,
        type = PageType.MOVIE_ENGINE
    ),
    OnboardingPage(
        title = "Next-Gen Live TV",
        description = "Access global channels with our Futuristic Player. Low latency, High Definition, and smoother than reality.",
        primaryColor = NeonPurple,
        type = PageType.LIVE_TV
    ),
    OnboardingPage(
        title = "Ultimate Ad-Shield",
        description = "Browse and stream from any site using our 'Ultimate Ad-Blocking Web View'. No distractions, just content.",
        primaryColor = NeonGreen,
        type = PageType.AD_BLOCKER
    ),
    OnboardingPage(
        title = "Spotify-Class Audio",
        description = "Immersive High-Fidelity Music System. Experience sound with a professional-grade UI designed for audiophiles.",
        primaryColor = NeonOrange,
        type = PageType.MUSIC
    )
)

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun OnboardingScreen(navController: NavController) {
    val context = LocalContext.current
    val pagerState = rememberPagerState(pageCount = { onboardingPages.size })
    val scope = rememberCoroutineScope()

    // Dynamic Background Color Transition
    val targetColor = onboardingPages[pagerState.currentPage].primaryColor
    val animatedBgColor by animateColorAsState(
        targetValue = targetColor,
        animationSpec = tween(durationMillis = 1000), 
        label = "BgColor"
    )

    fun finishOnboarding() {
        val sharedPref = context.getSharedPreferences("StreamXPrefs", Context.MODE_PRIVATE)
        with(sharedPref.edit()) {
            putBoolean("FinishedOnboarding", true)
            apply()
        }
        // Navigate to Auth and clear backstack
        navController.navigate("auth") {
            popUpTo("onboarding") { inclusive = true }
        }
    }

    // --- FIXED: AUTOMATIC SLIDER SWITCHING LOGIC ---
    // Using Unit as key ensures this coroutine stays alive and doesn't get cancelled 
    // when currentPage changes mid-animation.
    LaunchedEffect(Unit) {
        while (isActive) {
            delay(4000) 
            // Check if we can scroll forward and user is not currently dragging
            if (pagerState.currentPage < onboardingPages.size - 1 && !pagerState.isScrollInProgress) {
                // animateScrollToPage is suspend function, it will wait until finished
                pagerState.animateScrollToPage(pagerState.currentPage + 1)
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(DeepSpaceBlack)
    ) {
        // --- 1. Ambient Background Glow ---
        Canvas(modifier = Modifier.fillMaxSize()) {
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(animatedBgColor.copy(alpha = 0.15f), Color.Transparent),
                    center = Offset(size.width / 2, size.height / 3),
                    radius = size.width * 1.2f
                ),
                radius = size.width * 1.2f,
                center = Offset(size.width / 2, size.height / 3)
            )
        }

        // --- 2. Main Content (Slider & Bottom Controls) ---
        // Moved Column BEFORE the Skip Button so the button draws ON TOP
        Column(modifier = Modifier.fillMaxSize()) {
            
            HorizontalPager(
                state = pagerState,
                modifier = Modifier
                    .weight(0.75f)
                    .fillMaxWidth()
            ) { index ->
                OnboardingPageContent(page = onboardingPages[index])
            }

            // --- Bottom Controls ---
            Column(
                modifier = Modifier
                    .weight(0.25f)
                    .fillMaxWidth()
                    .padding(horizontal = 32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Top
            ) {
                // Animated Indicators
                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.padding(bottom = 32.dp)
                ) {
                    repeat(onboardingPages.size) { i ->
                        val isSelected = pagerState.currentPage == i
                        val width by animateDpAsState(
                            targetValue = if (isSelected) 40.dp else 10.dp,
                            animationSpec = spring(stiffness = Spring.StiffnessLow),
                            label = "width"
                        )
                        val color by animateColorAsState(
                            targetValue = if (isSelected) onboardingPages[i].primaryColor else Color.Gray.copy(alpha = 0.3f),
                            label = "color"
                        )

                        Box(
                            modifier = Modifier
                                .height(6.dp)
                                .width(width)
                                .clip(RoundedCornerShape(50))
                                .background(color)
                        )
                    }
                }

                // Futuristic Button
                val isLastPage = pagerState.currentPage == onboardingPages.size - 1
                
                Button(
                    onClick = {
                        if (isLastPage) finishOnboarding()
                        else scope.launch { pagerState.animateScrollToPage(pagerState.currentPage + 1) }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                        .graphicsLayer {
                            shadowElevation = 20f
                            shape = RoundedCornerShape(16.dp)
                            clip = true
                        },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = animatedBgColor
                    ),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Text(
                        text = if (isLastPage) "LAUNCH SYSTEM" else if (pagerState.currentPage == 0) "START TOUR" else "NEXT STEP",
                        color = DeepSpaceBlack,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Black,
                        letterSpacing = 1.sp
                    )
                }
            }
        }

        // --- 3. FIXED: Skip Button (Moved to End) ---
        // This ensures it is drawn on top of the Column and receives clicks
        TextButton(
            onClick = { finishOnboarding() },
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = 48.dp, end = 24.dp)
                .statusBarsPadding()
        ) {
            Text(
                "SKIP INTRO",
                color = Color.White.copy(alpha = 0.6f),
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp
            )
        }
    }
}

@Composable
fun OnboardingPageContent(page: OnboardingPage) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        // --- High-Level Custom Graphic ---
        Box(
            modifier = Modifier
                .size(300.dp)
                .padding(bottom = 40.dp),
            contentAlignment = Alignment.Center
        ) {
            // Background pulsing glow
            val infiniteTransition = rememberInfiniteTransition(label = "pulse")
            val scale by infiniteTransition.animateFloat(
                initialValue = 0.8f, targetValue = 1.1f,
                animationSpec = infiniteRepeatable(tween(2000), RepeatMode.Reverse), label = "scale"
            )
            val alpha by infiniteTransition.animateFloat(
                initialValue = 0.2f, targetValue = 0f,
                animationSpec = infiniteRepeatable(tween(2000), RepeatMode.Reverse), label = "alpha"
            )
            
            Box(
                modifier = Modifier
                    .size(250.dp)
                    .scale(scale)
                    .background(
                        Brush.radialGradient(listOf(page.primaryColor.copy(alpha = alpha), Color.Transparent)),
                        CircleShape
                    )
            )

            // Render Specific Tech Graphics based on Type
            when (page.type) {
                PageType.WELCOME -> TechWelcomeGraphic(page.primaryColor)
                PageType.MOVIE_ENGINE -> TechMovieGraphic(page.primaryColor)
                PageType.LIVE_TV -> TechTVGraphic(page.primaryColor)
                PageType.AD_BLOCKER -> TechShieldGraphic(page.primaryColor)
                PageType.MUSIC -> TechMusicGraphic(page.primaryColor)
            }
        }

        // --- Text Content ---
        Text(
            text = page.title.uppercase(),
            style = MaterialTheme.typography.headlineMedium.copy(
                fontWeight = FontWeight.ExtraBold,
                letterSpacing = 2.sp,
                shadow = Shadow(color = page.primaryColor, blurRadius = 20f)
            ),
            color = Color.White,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = page.description,
            style = MaterialTheme.typography.bodyLarge,
            color = Color.White.copy(alpha = 0.7f),
            textAlign = TextAlign.Center,
            lineHeight = 24.sp
        )
    }
}

// =====================================================================
// === ULTRA CUSTOM GRAPHICS (UNCHANGED) ===
// =====================================================================

@Composable
fun TechWelcomeGraphic(color: Color) {
    val infiniteTransition = rememberInfiniteTransition(label = "welcome_spin")
    
    val rotationOuter by infiniteTransition.animateFloat(
        initialValue = 0f, targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(10000, easing = LinearEasing)), label = "rot1"
    )
    val rotationInner by infiniteTransition.animateFloat(
        initialValue = 360f, targetValue = 0f,
        animationSpec = infiniteRepeatable(tween(6000, easing = LinearEasing)), label = "rot2"
    )
    val pulse by infiniteTransition.animateFloat(
        initialValue = 0.6f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1500, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "pulse"
    )

    Canvas(modifier = Modifier.size(180.dp)) {
        val cx = size.width / 2
        val cy = size.height / 2
        
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(Color.White, color, color.copy(alpha = 0.2f)),
                center = center,
                radius = 40.dp.toPx() * pulse
            ),
            radius = 35.dp.toPx() * pulse
        )

        rotate(rotationInner) {
            drawCircle(
                color = color,
                style = Stroke(width = 2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(20f, 20f), 0f)),
                radius = 55.dp.toPx()
            )
            drawCircle(color = Color.White, radius = 3.dp.toPx(), center = Offset(cx, cy - 55.dp.toPx()))
            drawCircle(color = Color.White, radius = 3.dp.toPx(), center = Offset(cx, cy + 55.dp.toPx()))
        }

        rotate(rotationOuter) {
            drawCircle(
                brush = Brush.sweepGradient(listOf(Color.Transparent, color, Color.White, color, Color.Transparent)),
                style = Stroke(width = 4.dp.toPx()),
                radius = 75.dp.toPx()
            )
        }
        
        rotate(45f) {
            drawOval(
                color = color.copy(alpha = 0.3f),
                topLeft = Offset(cx - 85.dp.toPx(), cy - 20.dp.toPx()),
                size = Size(170.dp.toPx(), 40.dp.toPx()),
                style = Stroke(width = 1.dp.toPx())
            )
        }
    }
}

@Composable
fun TechMovieGraphic(color: Color) {
    val infiniteTransition = rememberInfiniteTransition(label = "spin")
    val rotation by infiniteTransition.animateFloat(
        initialValue = 0f, targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(8000, easing = LinearEasing)), label = "rot"
    )

    Canvas(modifier = Modifier.size(160.dp)) {
        rotate(rotation) {
            drawCircle(
                brush = Brush.sweepGradient(listOf(Color.Transparent, color, Color.Transparent)),
                style = Stroke(width = 4.dp.toPx())
            )
            for (i in 0 until 12) {
                rotate(i * 30f) {
                    drawLine(
                        color = color.copy(alpha = 0.5f),
                        start = Offset(size.width / 2, 0f),
                        end = Offset(size.width / 2, 15f),
                        strokeWidth = 3.dp.toPx()
                    )
                }
            }
        }
        val path = Path().apply {
            moveTo(center.x + 40f, center.y)
            lineTo(center.x - 25f, center.y - 40f)
            lineTo(center.x - 25f, center.y + 40f)
            close()
        }
        drawPath(path, color)
        drawPath(path, color = Color.White, style = Stroke(width = 4f))
    }
}

@Composable
fun TechTVGraphic(color: Color) {
    Canvas(modifier = Modifier.size(160.dp)) {
        val w = size.width
        val h = size.height
        
        drawRoundRect(
            color = color,
            style = Stroke(width = 6.dp.toPx()),
            cornerRadius = CornerRadius(20f, 20f),
            size = Size(w, h * 0.7f),
            topLeft = Offset(0f, h * 0.15f)
        )
        
        drawLine(
            color = color,
            start = Offset(w * 0.2f, 0f),
            end = Offset(w * 0.4f, h * 0.15f),
            strokeWidth = 6.dp.toPx(),
            cap = StrokeCap.Round
        )
        drawLine(
            color = color,
            start = Offset(w * 0.8f, 0f),
            end = Offset(w * 0.6f, h * 0.15f),
            strokeWidth = 6.dp.toPx(),
            cap = StrokeCap.Round
        )
        
        drawCircle(
            color = Color.Red,
            radius = 8.dp.toPx(),
            center = Offset(w * 0.85f, h * 0.25f)
        )
    }
}

@Composable
fun TechShieldGraphic(color: Color) {
    Canvas(modifier = Modifier.size(160.dp)) {
        val w = size.width
        val h = size.height
        
        val shieldPath = Path().apply {
            moveTo(w / 2, 0f)
            cubicTo(w, 0f, w, h * 0.5f, w / 2, h)
            cubicTo(0f, h * 0.5f, 0f, 0f, w / 2, 0f)
            close()
        }
        
        drawPath(shieldPath, color.copy(alpha = 0.2f))
        
        drawPath(shieldPath, color = color, style = Stroke(width = 6.dp.toPx()))
        
        drawLine(
            color = Color.White,
            start = Offset(w * 0.35f, h * 0.35f),
            end = Offset(w * 0.65f, h * 0.6f),
            strokeWidth = 8.dp.toPx(),
            cap = StrokeCap.Round
        )
        drawLine(
            color = Color.White,
            start = Offset(w * 0.65f, h * 0.35f),
            end = Offset(w * 0.35f, h * 0.6f),
            strokeWidth = 8.dp.toPx(),
            cap = StrokeCap.Round
        )
    }
}

@Composable
fun TechMusicGraphic(color: Color) {
    val infiniteTransition = rememberInfiniteTransition(label = "music")
    
    val bar1 by infiniteTransition.animateFloat(0.3f, 1f, infiniteRepeatable(tween(500, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "b1")
    val bar2 by infiniteTransition.animateFloat(0.5f, 1f, infiniteRepeatable(tween(400, easing = LinearEasing), RepeatMode.Reverse), label = "b2")
    val bar3 by infiniteTransition.animateFloat(0.2f, 0.9f, infiniteRepeatable(tween(600, easing = FastOutLinearInEasing), RepeatMode.Reverse), label = "b3")

    Row(
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.height(120.dp)
    ) {
        MusicBar(heightScale = bar1, color = color)
        MusicBar(heightScale = bar2, color = color)
        MusicBar(heightScale = bar3, color = color)
        MusicBar(heightScale = bar2, color = color)
        MusicBar(heightScale = bar1, color = color)
    }
}

@Composable
fun MusicBar(heightScale: Float, color: Color) {
    Box(
        modifier = Modifier
            .width(16.dp)
            .fillMaxHeight(heightScale)
            .clip(RoundedCornerShape(8.dp))
            .background(color)
    )
}
