package com.aeoncorex.streamx.ui.splash

import android.content.Context
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.aeoncorex.streamx.R
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.delay
import androidx.compose.foundation.Image
import kotlin.math.*

// ── Brand palette ────────────────────────────────────────────
private val Void       = Color(0xFF020409)
private val DeepBlue   = Color(0xFF040D1A)
private val Teal       = Color(0xFF00D4E8)
private val TealDim    = Color(0xFF0097A7)
private val TealGlow   = Color(0x3300D4E8)
private val Purple     = Color(0xFF6C3FFE)
private val GridLine   = Color(0x1100D4E8)

@Composable
fun SplashScreen(navController: NavController) {
    val context = LocalContext.current

    // ── Master enter switch ──────────────────────────────────
    var entered by remember { mutableStateOf(false) }
    var exiting by remember { mutableStateOf(false) }

    // ── Enter animations ─────────────────────────────────────
    val logoAlpha   by animateFloatAsState(if (entered) 1f else 0f,
        tween(900, easing = FastOutSlowInEasing), label = "la")
    val logoScale   by animateFloatAsState(if (entered) 1f else 0.72f,
        tween(1000, easing = FastOutSlowInEasing), label = "ls")
    val taglineAlpha by animateFloatAsState(if (entered) 1f else 0f,
        tween(700, delayMillis = 500, easing = FastOutSlowInEasing), label = "ta")
    val taglineY     by animateFloatAsState(if (entered) 0f else 24f,
        tween(700, delayMillis = 500, easing = FastOutSlowInEasing), label = "ty")
    val footerAlpha  by animateFloatAsState(if (entered) 1f else 0f,
        tween(600, delayMillis = 900), label = "fa")

    // ── Exit fade ────────────────────────────────────────────
    val screenAlpha  by animateFloatAsState(if (exiting) 0f else 1f,
        tween(600, easing = FastOutSlowInEasing), label = "sa")

    // ── Infinite ambient animations ──────────────────────────
    val infiniteTransition = rememberInfiniteTransition(label = "ambient")

    // Scan-line Y sweep across full height
    val scanY by infiniteTransition.animateFloat(0f, 1f,
        infiniteRepeatable(tween(3500, easing = LinearEasing), RepeatMode.Restart), label = "sy")

    // Outer ring pulse
    val ringScale by infiniteTransition.animateFloat(0.88f, 1.12f,
        infiniteRepeatable(tween(2200, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "rs")
    val ringAlpha by infiniteTransition.animateFloat(0.6f, 0.1f,
        infiniteRepeatable(tween(2200), RepeatMode.Reverse), label = "ra")

    // Inner glow breathe
    val glowRadius by infiniteTransition.animateFloat(80f, 140f,
        infiniteRepeatable(tween(2800, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "gr")

    // Grid offset drift
    val gridOffset by infiniteTransition.animateFloat(0f, 48f,
        infiniteRepeatable(tween(6000, easing = LinearEasing), RepeatMode.Restart), label = "go")

    // Navigation logic
    LaunchedEffect(Unit) {
        entered = true
        delay(3000)
        exiting = true
        delay(650)

        val prefs = context.getSharedPreferences("StreamXPrefs", Context.MODE_PRIVATE)
        val onboardingDone = prefs.getBoolean("FinishedOnboarding", false)
        val dest = when {
            !onboardingDone -> "onboarding"
            FirebaseAuth.getInstance().currentUser != null -> "home"
            else -> "auth"
        }
        navController.navigate(dest) { popUpTo("splash") { inclusive = true } }
    }

    // ── Root ─────────────────────────────────────────────────
    Box(
        Modifier
            .fillMaxSize()
            .alpha(screenAlpha)
            .background(Brush.radialGradient(
                colors = listOf(DeepBlue, Void),
                radius = 1200f
            )),
        contentAlignment = Alignment.Center
    ) {

        // ── Animated grid canvas ──────────────────────────────
        Canvas(Modifier.fillMaxSize()) {
            drawGrid(gridOffset, size.width, size.height)
        }

        // ── Ambient glow blob behind logo ─────────────────────
        Canvas(Modifier.fillMaxSize().blur(60.dp)) {
            val cx = size.width / 2f
            val cy = size.height / 2f
            drawCircle(
                brush = Brush.radialGradient(
                    listOf(TealGlow, Color.Transparent),
                    center = Offset(cx, cy), radius = glowRadius
                ),
                center = Offset(cx, cy),
                radius = glowRadius
            )
            drawCircle(
                brush = Brush.radialGradient(
                    listOf(Color(0x22_6C3FFE), Color.Transparent),
                    center = Offset(cx, cy), radius = glowRadius * 1.4f
                ),
                center = Offset(cx, cy),
                radius = glowRadius * 1.4f
            )
        }

        // ── Scan line (full screen) ───────────────────────────
        Canvas(Modifier.fillMaxSize()) {
            val y = scanY * size.height
            drawLine(
                brush = Brush.horizontalGradient(
                    listOf(Color.Transparent, Teal.copy(alpha = 0.4f),
                           Teal.copy(alpha = 0.7f), Teal.copy(alpha = 0.4f), Color.Transparent)
                ),
                start = Offset(0f, y),
                end   = Offset(size.width, y),
                strokeWidth = 1.5f
            )
            // Glow trail
            drawLine(
                brush = Brush.horizontalGradient(
                    listOf(Color.Transparent, Teal.copy(alpha = 0.15f), Color.Transparent)
                ),
                start = Offset(0f, y),
                end   = Offset(size.width, y),
                strokeWidth = 24f
            )
        }

        // ── Pulsing rings around logo ─────────────────────────
        Canvas(
            Modifier
                .size(280.dp)
                .scale(ringScale)
                .alpha(if (entered) ringAlpha else 0f)
        ) {
            val c = Offset(size.width / 2, size.height / 2)
            val r = size.minDimension / 2f
            // Outer ring
            drawCircle(color = Teal.copy(alpha = 0.25f), center = c,
                radius = r, style = Stroke(1.5f))
            // Inner ring
            drawCircle(color = Teal.copy(alpha = 0.12f), center = c,
                radius = r * 0.78f, style = Stroke(1f))
            // Corner tick marks
            listOf(0f, 90f, 180f, 270f).forEach { angle ->
                rotate(angle, c) {
                    drawLine(Teal.copy(alpha = 0.6f),
                        start = Offset(c.x + r * 0.88f, c.y),
                        end   = Offset(c.x + r,         c.y),
                        strokeWidth = 2f)
                }
            }
        }

        // ── Logo ─────────────────────────────────────────────
        Image(
            painter = painterResource(R.drawable.streamx_ultra_logo),
            contentDescription = "StreamX",
            modifier = Modifier
                .size(160.dp)
                .scale(logoScale)
                .alpha(logoAlpha)
        )

        // ── Tagline + footer ──────────────────────────────────
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 56.dp)
                .alpha(taglineAlpha)
                .offset(y = taglineY.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Teal divider line
            Canvas(Modifier.width(48.dp).height(1.dp)) {
                drawLine(
                    Brush.horizontalGradient(listOf(Color.Transparent, Teal, Color.Transparent)),
                    Offset(0f, 0f), Offset(size.width, 0f), strokeWidth = 1f
                )
            }
            Spacer(Modifier.height(12.dp))
            Text(
                "FROM",
                color = Teal.copy(alpha = 0.6f),
                fontSize = 9.sp,
                letterSpacing = 4.sp,
                fontWeight = FontWeight.Light
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "AEONCOREX LABS",
                color = Color.White.copy(alpha = 0.85f),
                fontSize = 13.sp,
                letterSpacing = 3.sp,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center
            )
        }

        // ── Corner HUD brackets ───────────────────────────────
        Canvas(Modifier.fillMaxSize().alpha(if (entered) footerAlpha else 0f)) {
            val pad  = 24.dp.toPx()
            val len  = 20.dp.toPx()
            val col  = Teal.copy(alpha = 0.35f)
            val sw   = 1.5f

            // Top-left
            drawLine(col, Offset(pad, pad), Offset(pad + len, pad), sw)
            drawLine(col, Offset(pad, pad), Offset(pad, pad + len), sw)
            // Top-right
            drawLine(col, Offset(size.width - pad, pad), Offset(size.width - pad - len, pad), sw)
            drawLine(col, Offset(size.width - pad, pad), Offset(size.width - pad, pad + len), sw)
            // Bottom-left
            drawLine(col, Offset(pad, size.height - pad), Offset(pad + len, size.height - pad), sw)
            drawLine(col, Offset(pad, size.height - pad), Offset(pad, size.height - pad - len), sw)
            // Bottom-right
            drawLine(col, Offset(size.width - pad, size.height - pad), Offset(size.width - pad - len, size.height - pad), sw)
            drawLine(col, Offset(size.width - pad, size.height - pad), Offset(size.width - pad, size.height - pad - len), sw)
        }
    }
}

// ── Draw perspective grid ─────────────────────────────────────
private fun DrawScope.drawGrid(offset: Float, w: Float, h: Float) {
    val step = 48f
    val col  = GridLine

    var x = -(offset % step)
    while (x < w) {
        drawLine(col, Offset(x, 0f), Offset(x, h), 0.5f)
        x += step
    }
    var y = -(offset % step)
    while (y < h) {
        drawLine(col, Offset(0f, y), Offset(w, y), 0.5f)
        y += step
    }
}
