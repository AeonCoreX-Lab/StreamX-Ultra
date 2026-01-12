package com.aeoncorex.streamx.ui.admin

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.aeoncorex.streamx.ui.theme.StreamXUltraTheme

class AdminActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            // [FIX] Changed StreamXTheme to match the imported StreamXUltraTheme
            StreamXUltraTheme {
                AdminAppScreen(onExit = { finish() })
            }
        }
    }
}
