package com.pnkastro.pas

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.Image
import androidx.compose.ui.layout.ContentScale
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.pnkastro.pas.ui.theme.PASTheme

class SplashActivity : ComponentActivity() {
    // duration to show the splash (milliseconds)
    private val splashDurationMs = 1000L
    private val TAG = "SplashActivity"

    override fun onCreate(savedInstanceState: Bundle?) {
        // Use the launcher theme (windowBackground) to avoid a white flash during cold start
        setTheme(R.style.Theme_PAS_Launcher)
        super.onCreate(savedInstanceState)

        // Show splash UI
        setContent {
            PASTheme {
                var showOfflineError by remember { mutableStateOf(false) }
                var isOnline by remember { mutableStateOf(isNetworkAvailable()) }

                // Full-screen splash background
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color(0xFF1a1a2e)),
                    contentAlignment = Alignment.Center
                ) {
                    // Full-screen splash image as background
                    Image(
                        painter = painterResource(id = R.drawable.splash_image),
                        contentDescription = "App Splash Background",
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )

                    // If online, show splash screen and wait
                    if (!showOfflineError && isOnline) {
                        LaunchedEffect(Unit) {
                            delay(splashDurationMs)

                            // Check if still online after splash duration
                            isOnline = isNetworkAvailable()
                            if (isOnline) {
                                proceedToMainActivity()
                            } else {
                                // Show offline error overlay
                                showOfflineError = true
                            }
                        }
                    } else if (showOfflineError || !isOnline) {
                        // Show offline error as overlay on top of splash image
                        OfflineErrorOverlay(
                            onRetry = {
                                isOnline = isNetworkAvailable()
                                if (isOnline) {
                                    showOfflineError = false
                                    proceedToMainActivity()
                                } else {
                                    Toast.makeText(
                                        this@SplashActivity,
                                        "Still offline. Please check your connection.",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }
                            }
                        )
                    }
                }
            }
        }
    }

    private fun isNetworkAvailable(): Boolean {
        val connectivityManager = getSystemService(android.content.Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false

        return when {
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> true
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> true
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> true
            else -> false
        }
    }

    private fun proceedToMainActivity() {
        startActivity(Intent(this, MainActivity::class.java))
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
        finish()
    }
}

@Composable
fun OfflineErrorOverlay(onRetry: () -> Unit) {
    // Semi-transparent dark overlay
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.6f)),
        contentAlignment = Alignment.Center
    ) {
        // Error content column
        Column(
            modifier = Modifier
                .fillMaxWidth(0.85f)
                .background(
                    color = Color(0xFF1a1a2e).copy(alpha = 0.95f),
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp)
                )
                .padding(32.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Red error icon
            Icon(
                imageVector = Icons.Default.Close,
                contentDescription = "Error",
                modifier = Modifier
                    .size(80.dp)
                    .background(
                        color = Color.Red.copy(alpha = 0.3f),
                        shape = androidx.compose.foundation.shape.CircleShape
                    )
                    .padding(16.dp),
                tint = Color.Red
            )

            Spacer(modifier = Modifier.height(24.dp))

            // Error title
            Text(
                text = "No Internet Connection",
                fontSize = 24.sp,
                color = Color.White,
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.headlineSmall
            )

            Spacer(modifier = Modifier.height(12.dp))

            // Error message
            Text(
                text = "Waiting for connection...",
                fontSize = 16.sp,
                color = Color(0xFFBBBBBB),
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.bodyMedium
            )

            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = "Please enable WiFi or mobile data and try again",
                fontSize = 14.sp,
                color = Color(0xFF999999),
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.bodySmall
            )

            Spacer(modifier = Modifier.height(32.dp))

            // Button row with Retry and Exit buttons
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Exit button
                Button(
                    onClick = { System.exit(0) },
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF555555)
                    ),
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp)
                ) {
                    Text(
                        text = "EXIT",
                        color = Color.White,
                        fontSize = 14.sp,
                        style = MaterialTheme.typography.labelMedium
                    )
                }

                // Retry button (golden color matching app theme)
                Button(
                    onClick = onRetry,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFFFFD700)
                    ),
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp)
                ) {
                    Text(
                        text = "RETRY",
                        color = Color.Black,
                        fontSize = 14.sp,
                        style = MaterialTheme.typography.labelMedium
                    )
                }
            }
        }
    }
}
