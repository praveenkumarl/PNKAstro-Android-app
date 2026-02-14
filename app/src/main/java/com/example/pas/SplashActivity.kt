package com.example.pas

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class SplashActivity : ComponentActivity() {
    // duration to show the splash (milliseconds)
    private val splashDurationMs = 1500L

    override fun onCreate(savedInstanceState: Bundle?) {
        // Use the launcher theme (windowBackground) to avoid a white flash during cold start
        setTheme(R.style.Theme_PAS_Launcher)
        super.onCreate(savedInstanceState)
        // Inflate the layout with the ImageView for precise control
        setContentView(R.layout.activity_splash)

        // Start MainActivity after a short delay
        lifecycleScope.launch {
            delay(splashDurationMs)
            startActivity(Intent(this@SplashActivity, MainActivity::class.java))
            // Use overrideActivityTransition (Android 12+) instead of deprecated overridePendingTransition
            @Suppress("DEPRECATION")
            overridePendingTransition(0, 0)
            finish()
        }
    }
}
