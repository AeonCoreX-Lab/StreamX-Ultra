package com.aeoncorex.streamx.ui.premium

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.WorkspacePremium
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aeoncorex.streamx.ai.PremiumManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Activity that receives the deep link  streamx://payment/success
 * after LemonSqueezy redirects the user back to the app.
 *
 * Add to AndroidManifest.xml inside <application>:
 *
 *   <activity
 *       android:name=".ui.premium.PaymentSuccessActivity"
 *       android:exported="true"
 *       android:launchMode="singleTop">
 *     <intent-filter android:autoVerify="true">
 *       <action android:name="android.intent.action.VIEW" />
 *       <category android:name="android.intent.category.DEFAULT" />
 *       <category android:name="android.intent.category.BROWSABLE" />
 *       <data android:scheme="streamx" android:host="payment" android:pathPrefix="/success" />
 *     </intent-filter>
 *   </activity>
 */
class PaymentSuccessActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            PaymentSuccessScreen(onDone = {
                // Navigate back to main activity
                startActivity(Intent(this, Class.forName("com.aeoncorex.streamx.MainActivity")).apply {
                    flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                })
                finish()
            })
        }
    }
}

@Composable
private fun PaymentSuccessScreen(onDone: () -> Unit) {
    val scope       = rememberCoroutineScope()
    var isPremium   by remember { mutableStateOf(false) }
    var isChecking  by remember { mutableStateOf(true) }
    var pollCount   by remember { mutableIntStateOf(0) }

    // Poll Firestore until isPremium becomes true (webhook may take a few seconds)
    LaunchedEffect(Unit) {
        PremiumManager.invalidateCache()   // force fresh read
        repeat(20) { attempt ->
            pollCount   = attempt + 1
            isPremium   = PremiumManager.isPremium()
            if (isPremium) { isChecking = false; return@LaunchedEffect }
            delay(2000)
        }
        // After 40 s, give up — user can refresh manually
        isChecking = false
    }

    Box(
        Modifier.fillMaxSize()
            .background(Brush.verticalGradient(listOf(Color(0xFF0A0818), Color(0xFF0D0D20)))),
        contentAlignment = Alignment.Center
    ) {
        AnimatedContent(
            targetState = when {
                isChecking && !isPremium -> "checking"
                isPremium               -> "success"
                else                    -> "pending"
            },
            label = "payment_state"
        ) { state ->
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(40.dp)
            ) {
                when (state) {
                    "checking" -> {
                        CircularProgressIndicator(color = Color(0xFFB388FF), modifier = Modifier.size(56.dp), strokeWidth = 4.dp)
                        Spacer(Modifier.height(28.dp))
                        Text("Confirming payment…", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(8.dp))
                        Text("This usually takes a few seconds ($pollCount/20)", color = Color.Gray, fontSize = 13.sp, textAlign = TextAlign.Center)
                    }

                    "success" -> {
                        Box(
                            Modifier.size(100.dp)
                                .background(
                                    Brush.radialGradient(listOf(Color(0xFF7C4DFF).copy(0.4f), Color.Transparent)),
                                    CircleShape
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Rounded.CheckCircle, null, tint = Color(0xFF69F0AE), modifier = Modifier.size(72.dp))
                        }
                        Spacer(Modifier.height(24.dp))
                        Text("Payment Successful!", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.ExtraBold)
                        Spacer(Modifier.height(8.dp))
                        Text("Welcome to StreamX Premium 🎉\nAI features are now unlocked.", color = Color.LightGray, fontSize = 15.sp, textAlign = TextAlign.Center, lineHeight = 24.sp)
                        Spacer(Modifier.height(32.dp))

                        LaunchedEffect(Unit) { delay(2000); onDone() }

                        LinearProgressIndicator(
                            modifier = Modifier.fillMaxWidth(0.6f).height(3.dp),
                            color    = Color(0xFF7C4DFF),
                            trackColor = Color.White.copy(0.1f)
                        )
                        Spacer(Modifier.height(8.dp))
                        Text("Redirecting in 2 seconds…", color = Color.Gray, fontSize = 11.sp)
                    }

                    "pending" -> {
                        Icon(Icons.Rounded.WorkspacePremium, null, tint = Color(0xFFFFD700), modifier = Modifier.size(72.dp))
                        Spacer(Modifier.height(24.dp))
                        Text("Almost there!", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(8.dp))
                        Text("Payment received — your account will be upgraded within a minute.", color = Color.LightGray, fontSize = 14.sp, textAlign = TextAlign.Center, lineHeight = 22.sp)
                        Spacer(Modifier.height(28.dp))
                        Button(
                            onClick  = { scope.launch { PremiumManager.invalidateCache(); onDone() } },
                            colors   = ButtonDefaults.buttonColors(containerColor = Color(0xFF7C4DFF)),
                            modifier = Modifier.fillMaxWidth().height(52.dp),
                            shape    = RoundedCornerShape(14.dp)
                        ) {
                            Text("Continue to App", color = Color.White, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}
