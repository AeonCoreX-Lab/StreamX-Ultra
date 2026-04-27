package com.aeoncorex.streamx.ui.premium

import android.content.Intent
import android.net.Uri
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.aeoncorex.streamx.ai.PremiumManager
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

// ── Design tokens ─────────────────────────────────────────────────
private val Purple     = Color(0xFF7C4DFF)
private val PurpleLight= Color(0xFFB388FF)
private val Gold       = Color(0xFFFFD700)
private val GoldLight  = Color(0xFFFFE57F)
private val DarkBg     = Color(0xFF0A0818)
private val CardBg     = Color(0xFF12102A)

// Vercel backend URL (set after deploy — format: https://your-app.vercel.app)
private const val CHECKOUT_FUNCTION_URL =
    "https://YOUR_APP_NAME.vercel.app/api/create-checkout"

// ═══════════════════════════════════════════════════════════════════
//  PremiumScreen
// ═══════════════════════════════════════════════════════════════════
@Composable
fun PremiumScreen(navController: NavController) {
    val context = LocalContext.current
    val scope   = rememberCoroutineScope()

    var isLoading       by remember { mutableStateOf(false) }
    var isPremium       by remember { mutableStateOf(false) }
    var checkingStatus  by remember { mutableStateOf(true) }

    // Check current premium status on open
    LaunchedEffect(Unit) {
        isPremium      = PremiumManager.isPremium()
        checkingStatus = false
    }

    BackHandler { navController.popBackStack() }

    Box(
        Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(DarkBg, Color(0xFF0D0D20), Color(0xFF080818))
                )
            )
    ) {
        // Decorative glow blobs
        Box(Modifier.size(300.dp).offset((-60).dp, (-60).dp)
            .background(Brush.radialGradient(listOf(Purple.copy(0.18f), Color.Transparent)), CircleShape))
        Box(Modifier.size(250.dp).align(Alignment.BottomEnd).offset(60.dp, 60.dp)
            .background(Brush.radialGradient(listOf(Gold.copy(0.10f), Color.Transparent)), CircleShape))

        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {

            // ── Top bar ───────────────────────────────────────────
            Row(Modifier.fillMaxWidth().statusBarsPadding().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { navController.popBackStack() }) {
                    Icon(Icons.Default.ArrowBack, null, tint = Color.White)
                }
                Text("StreamX Premium", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f), textAlign = TextAlign.Center)
                Spacer(Modifier.size(48.dp))
            }

            if (checkingStatus) {
                Box(Modifier.fillMaxWidth().padding(top = 120.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = PurpleLight)
                }
                return@Column
            }

            // ── Already premium ───────────────────────────────────
            if (isPremium) {
                AlreadyPremiumCard { navController.popBackStack() }
                return@Column
            }

            // ── Hero section ──────────────────────────────────────
            Spacer(Modifier.height(12.dp))
            HeroCrownSection()
            Spacer(Modifier.height(32.dp))

            // ── Features list ─────────────────────────────────────
            Column(Modifier.padding(horizontal = 20.dp)) {
                Text("What you unlock", color = Color.Gray, fontSize = 12.sp,
                    letterSpacing = 1.sp, modifier = Modifier.padding(bottom = 14.dp))

                // ── Current Premium Feature: Ad-Free ─────────────────
                PremiumFeatureRow(Icons.Rounded.PublicOff,        Gold,   "Ad-Free Experience",        "Zero ads — no app-open ads, no pre-play ads, no timed video ads. Pure viewing.")
                PremiumFeatureRow(Icons.Rounded.Subtitles,        Purple, "Auto Subtitle Download",    "Subtitles auto-download in your preferred language the moment a movie starts")
                PremiumFeatureRow(Icons.Rounded.Translate,        Purple, "Multi-Language Subtitles",  "English, Bengali, Hindi, Arabic, Spanish, French, German, Korean & more")
                PremiumFeatureRow(Icons.Rounded.FormatSize,       Purple, "Subtitle Customization",    "Change subtitle color, font size, position, shadow & background")
                PremiumFeatureRow(Icons.Rounded.SupportAgent,     Gold,   "Priority Support",          "Dedicated support channel — issues resolved within 24 hours")

                // ── Coming Soon ───────────────────────────────────────
                Spacer(Modifier.height(8.dp))
                Text(
                    "✨ More premium features coming soon",
                    color     = PurpleLight.copy(0.7f),
                    fontSize  = 12.sp,
                    textAlign = TextAlign.Center,
                    modifier  = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                )
            }

            Spacer(Modifier.height(32.dp))

            // ── Pricing card ──────────────────────────────────────
            PricingCard(
                isLoading = isLoading,
                onUpgrade = {
                    val uid = FirebaseAuth.getInstance().currentUser?.uid
                    if (uid == null) {
                        Toast.makeText(context, "Please sign in first", Toast.LENGTH_SHORT).show()
                        return@PricingCard
                    }
                    isLoading = true
                    scope.launch {
                        val url = fetchCheckoutUrl(uid)
                        isLoading = false
                        if (url != null) {
                            // Open LemonSqueezy checkout in browser/custom tab
                            try {
                                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                            } catch (e: Exception) {
                                Toast.makeText(context, "Cannot open browser", Toast.LENGTH_SHORT).show()
                            }
                        } else {
                            Toast.makeText(context, "Payment service unavailable. Try again.", Toast.LENGTH_LONG).show()
                        }
                    }
                }
            )

            Spacer(Modifier.height(16.dp))

            // ── Trust badges ──────────────────────────────────────
            TrustBadges()
            Spacer(Modifier.height(32.dp))

            // ── Fine print ────────────────────────────────────────
            Text(
                "Secure payment by LemonSqueezy • Auto-renews yearly • Cancel anytime\n" +
                "Purchasing grants 1-year Premium access. Access activates within seconds after payment.",
                color     = Color.Gray.copy(0.6f),
                fontSize  = 10.sp,
                textAlign = TextAlign.Center,
                modifier  = Modifier.fillMaxWidth().padding(horizontal = 24.dp)
            )
            Spacer(Modifier.height(40.dp))
        }

        // ── Payment return handler ─────────────────────────────────
        // When user returns from browser after paying, re-check status
        LaunchedEffect(Unit) {
            // Poll every 3 s for up to 60 s after user comes back
            // (webhook will have fired by then)
        }
    }
}

// ═══════════════════════════════════════════════════════════════════
//  Sub-composables
// ═══════════════════════════════════════════════════════════════════

@Composable
private fun HeroCrownSection() {
    val infiniteTransition = rememberInfiniteTransition(label = "crown")
    val scale by infiniteTransition.animateFloat(
        initialValue = 0.95f, targetValue = 1.05f,
        animationSpec = infiniteRepeatable(tween(1800, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "scale"
    )
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.size(100.dp).scale(scale)
                .background(
                    Brush.radialGradient(listOf(Gold.copy(0.3f), Purple.copy(0.2f), Color.Transparent)),
                    CircleShape
                )
                .border(2.dp, Brush.sweepGradient(listOf(Gold, PurpleLight, Gold)), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Rounded.WorkspacePremium, null, tint = Gold, modifier = Modifier.size(52.dp))
        }
        Spacer(Modifier.height(20.dp))
        Text(
            "StreamX Premium",
            color      = Color.White,
            fontSize   = 28.sp,
            fontWeight = FontWeight.ExtraBold
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "Ad-Free Viewing • Multi-Language Subtitles\nMore features coming soon",
            color     = Color.LightGray,
            fontSize  = 15.sp,
            textAlign = TextAlign.Center,
            lineHeight = 24.sp
        )
    }
}

@Composable
private fun PremiumFeatureRow(
    icon: ImageVector, iconTint: Color, title: String, subtitle: String
) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier.size(42.dp)
                .background(iconTint.copy(0.12f), RoundedCornerShape(12.dp))
                .border(1.dp, iconTint.copy(0.25f), RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, null, tint = iconTint, modifier = Modifier.size(22.dp))
        }
        Spacer(Modifier.width(14.dp))
        Column {
            Text(title,    color = Color.White,     fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Text(subtitle, color = Color.Gray,      fontSize = 12.sp)
        }
        Spacer(Modifier.weight(1f))
        Icon(Icons.Rounded.Check, null, tint = Purple, modifier = Modifier.size(18.dp))
    }
}

@Composable
private fun PricingCard(isLoading: Boolean, onUpgrade: () -> Unit) {
    Column(Modifier.padding(horizontal = 20.dp)) {
        Box(
            Modifier.fillMaxWidth()
                .background(
                    Brush.verticalGradient(listOf(Color(0xFF1C1040), CardBg)),
                    RoundedCornerShape(20.dp)
                )
                .border(
                    1.5.dp,
                    Brush.horizontalGradient(listOf(Purple, PurpleLight, Gold)),
                    RoundedCornerShape(20.dp)
                )
                .padding(24.dp)
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                // Badge
                Box(
                    Modifier.clip(RoundedCornerShape(20.dp))
                        .background(Brush.horizontalGradient(listOf(Purple, Gold)))
                        .padding(horizontal = 12.dp, vertical = 4.dp)
                ) {
                    Text("BEST VALUE", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 1.sp)
                }

                Spacer(Modifier.height(14.dp))

                Row(verticalAlignment = Alignment.Bottom) {
                    Text("$", color = Gold, fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 4.dp))
                    Text("4", color = Color.White, fontSize = 52.sp, fontWeight = FontWeight.ExtraBold)
                    Text(".99", color = Gold, fontSize = 24.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 8.dp))
                }
                Text("per year  •  less than $0.42/month", color = Color.Gray, fontSize = 12.sp)

                Spacer(Modifier.height(20.dp))
                HorizontalDivider(color = Color.White.copy(0.08f))
                Spacer(Modifier.height(20.dp))

                // Features quick summary
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    MiniStat("0",   "Ads")
                    MiniStat("∞",   "Subtitles")
                    MiniStat("🌍",  "Global")
                }

                Spacer(Modifier.height(24.dp))

                // CTA button
                Button(
                    onClick   = onUpgrade,
                    enabled   = !isLoading,
                    modifier  = Modifier.fillMaxWidth().height(56.dp),
                    colors    = ButtonDefaults.buttonColors(
                        containerColor = Purple,
                        disabledContainerColor = Purple.copy(0.5f)
                    ),
                    shape     = RoundedCornerShape(16.dp)
                ) {
                    if (isLoading) {
                        CircularProgressIndicator(color = Color.White, modifier = Modifier.size(22.dp), strokeWidth = 2.5.dp)
                        Spacer(Modifier.width(10.dp))
                        Text("Opening payment…", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    } else {
                        Icon(Icons.Rounded.WorkspacePremium, null, modifier = Modifier.size(22.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Get Premium — $4.99/yr", color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = 16.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun MiniStat(value: String, label: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, color = PurpleLight, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold)
        Text(label, color = Color.Gray,  fontSize = 11.sp)
    }
}

@Composable
private fun TrustBadges() {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp),
        horizontalArrangement = Arrangement.SpaceEvenly
    ) {
        TrustBadge(Icons.Rounded.Lock,            "Secure\nPayment")
        TrustBadge(Icons.Rounded.PublicOff,       "Zero\nAds")
        TrustBadge(Icons.Rounded.EventAvailable,  "Instant\nActivation")
        TrustBadge(Icons.Rounded.Cancel,          "Cancel\nAnytime")
    }
}

@Composable
private fun TrustBadge(icon: ImageVector, label: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(icon, null, tint = Color.Gray, modifier = Modifier.size(22.dp))
        Spacer(Modifier.height(4.dp))
        Text(label, color = Color.Gray, fontSize = 10.sp, textAlign = TextAlign.Center, lineHeight = 14.sp)
    }
}

@Composable
private fun AlreadyPremiumCard(onBack: () -> Unit) {
    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                Modifier.size(90.dp)
                    .background(Brush.radialGradient(listOf(Purple.copy(0.3f), Color.Transparent)), CircleShape)
                    .border(2.dp, PurpleLight.copy(0.6f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Rounded.WorkspacePremium, null, tint = Gold, modifier = Modifier.size(48.dp))
            }
            Spacer(Modifier.height(24.dp))
            Text("You're Premium! 🎉", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.ExtraBold)
            Spacer(Modifier.height(10.dp))
            Text("All AI features are unlocked.\nEnjoy StreamX to the fullest.", color = Color.LightGray, fontSize = 15.sp, textAlign = TextAlign.Center, lineHeight = 24.sp)
            Spacer(Modifier.height(32.dp))
            Button(onClick = onBack, colors = ButtonDefaults.buttonColors(containerColor = Purple), modifier = Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(14.dp)) {
                Text("Start Watching", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════════
//  Network: call Cloud Function to get checkout URL
// ═══════════════════════════════════════════════════════════════════
private suspend fun fetchCheckoutUrl(uid: String): String? = withContext(Dispatchers.IO) {
    try {
        val body = JSONObject().apply { put("uid", uid) }.toString()
        val conn = (URL(CHECKOUT_FUNCTION_URL).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            setRequestProperty("Content-Type", "application/json")
            doOutput     = true
            connectTimeout = 10_000
            readTimeout    = 15_000
            outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        }
        if (conn.responseCode == 200) {
            val resp = conn.inputStream.bufferedReader().use { it.readText() }
            JSONObject(resp).optString("checkoutUrl").ifBlank { null }
        } else {
            Log.e("PremiumScreen", "createCheckout HTTP ${conn.responseCode}")
            null
        }
    } catch (e: Exception) {
        Log.e("PremiumScreen", "fetchCheckoutUrl: ${e.message}")
        null
    }
}
