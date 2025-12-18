package com.aeoncorex.streamx

import android.app.PictureInPictureParams
import android.os.Build
import android.os.Bundle
import android.util.Rational
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.aeoncorex.streamx.navigation.AppNavigation
import com.aeoncorex.streamx.ui.theme.StreamXUltraTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            // আপনার অ্যাপের থিম এখানে ব্যবহার করা হয়েছে
            StreamXUltraTheme {
                AppNavigation()
            }
        }
    }

    // =================== এই ফাংশনটি যোগ করা হয়েছে ===================
    // এই ফাংশনটি PlayerScreen থেকে কল করে PiP মোড চালু করা হবে
    fun enterPiPMode() {
        // PiP মোড শুধুমাত্র অ্যান্ড্রয়েড O (API 26) বা তার নতুন ভার্সনে কাজ করে
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val params = PictureInPictureParams.Builder()
                // ভিডিওর জন্য 16:9 অনুপাত সেট করা হলো
                .setAspectRatio(Rational(16, 9))
                .build()
            // PiP মোডে প্রবেশ করার জন্য সিস্টেমকে নির্দেশ দেওয়া হচ্ছে
            enterPictureInPictureMode(params)
        }
    }
    // ===================================================================
}