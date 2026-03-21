package com.pnkastro.pas

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.pnkastro.pas.ui.theme.PASTheme
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class TrialResponse(
    val success: Boolean,
    val message: String? = null,
    val session_key: String? = null,
    val expires_at: String? = null,
    val trial_expires_at: String? = null,
    val redirect_url: String? = null,
    val error: String? = null,
    val debug_error: String? = null,
    val imei: String? = null,
    val code: String? = null,
    val device_model: String? = null,
    val activated_at: String? = null,
    val migrated: Boolean? = false
)

class TrialWebViewActivity : ComponentActivity() {
    private val TAG = "TrialWeb"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val url = intent.getStringExtra("url") ?: ""

        if (url.isEmpty()) {
            Toast.makeText(this, "No URL provided", Toast.LENGTH_SHORT).show()
            setResult(Activity.RESULT_CANCELED)
            finish()
            return
        }

        setContent {
            PASTheme {
                Scaffold(
                    topBar = {
                        AppTopBar(
                            brandRes = R.string.app_name,
                            deviceId = null,
                            currentUrl = null,
                            onOpenInBrowser = {},
                            onRegisterRequested = {},
                            onTryRequested = {},
                            onAboutRequested = {},
                            onShareRequested = {}
                        )
                    },
                    modifier = Modifier.fillMaxSize(),
                    contentWindowInsets = WindowInsets(0, 0, 0, 0)
                ) { innerPadding ->
                    Box(modifier = Modifier.padding(innerPadding).fillMaxSize()) {
                        AndroidView(
                            factory = { ctx ->
                                WebView(ctx).apply {
                                    settings.apply {
                                        javaScriptEnabled = true
                                        domStorageEnabled = true
                                        allowContentAccess = true
                                        allowFileAccess = true
                                    }

                                    // Enable cookies
                                    try {
                                        val cm = CookieManager.getInstance()
                                        cm.setAcceptCookie(true)
                                        cm.setAcceptThirdPartyCookies(this, true)
                                    } catch (e: Exception) {
                                        Log.w(TAG, "Cookie setup failed: ${e.message}")
                                    }

                                    webViewClient = object : WebViewClient() {
                                        override fun onPageFinished(view: WebView?, url: String?) {
                                            super.onPageFinished(view, url)
                                            view?.evaluateJavascript(
                                                "(function() { return document.body.innerText; })();"
                                            ) { jsonString ->
                                                if (jsonString != null && jsonString != "null" && jsonString.isNotBlank()) {
                                                    handleJsonResponse(jsonString)
                                                }
                                            }
                                        }

                                        override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                                            val uri: Uri = request?.url ?: return false
                                            Log.d(TAG, "shouldOverride: $uri")

                                            // Intercept the custom scheme used by the server to indicate trial activation
                                            if (uri.scheme == "myapp" && uri.host == "trial") {
                                                val activated = (uri.getQueryParameter("activated") == "1") || (uri.getQueryParameter("activated")?.lowercase() == "true")
                                                val expiry = uri.getQueryParameter("expiry") ?: ""

                                                val data = Intent()
                                                data.putExtra("activated", activated)
                                                data.putExtra("expiry", expiry)

                                                setResult(Activity.RESULT_OK, data)
                                                finish()
                                                return true
                                            }

                                            // Otherwise let the WebView load the URL normally
                                            return false
                                        }
                                    }
                                    webChromeClient = WebChromeClient()
                                    loadUrl(url)
                                }
                            },
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }
            }
        }
    }

    private fun handleJsonResponse(rawJson: String?) {
        if (rawJson == null || rawJson == "null" || rawJson.isBlank()) return

        // Clean the string from evaluateJavascript (it's wrapped in double quotes and escaped)
        var json = rawJson.trim()
        if (json.startsWith("\"") && json.endsWith("\"")) {
            json = json.substring(1, json.length - 1)
                .replace("\\\"", "\"")
                .replace("\\\\", "\\")
                .replace("\\n", "\n")
                .replace("\\r", "\r")
        }

        // Try to find the first '{' and last '}' in case of extra text
        val start = json.indexOf('{')
        val end = json.lastIndexOf('}')
        if (start != -1 && end != -1 && end > start) {
            json = json.substring(start, end + 1)
        } else {
            return // Not valid JSON
        }

        try {
            val format = Json { ignoreUnknownKeys = true }
            val response = format.decodeFromString<TrialResponse>(json)

            if (response.success || response.migrated == true) {
                val expiry = response.trial_expires_at ?: response.expires_at ?: ""
                val intent = Intent().apply {
                    putExtra("activated", true)
                    putExtra("migrated", response.migrated == true)
                    putExtra("expiry", expiry)
                    putExtra("message", response.message ?: if (response.migrated == true) "Migration successful" else "Trial activated")
                    putExtra("redirect_url", response.redirect_url)
                }
                setResult(Activity.RESULT_OK, intent)
                Toast.makeText(this, response.message ?: if (response.migrated == true) "Migration complete" else "Trial activated", Toast.LENGTH_LONG).show()
                finish()
            } else if (!response.error.isNullOrBlank()) {
                val errorMsg = response.error
                Log.e(TAG, "Trial error: $errorMsg ${response.debug_error ?: ""}")
                Toast.makeText(this, errorMsg, Toast.LENGTH_LONG).show()

                // For conflict 409 (device already has active trial), navigate to home
                if (errorMsg.contains("already has an active trial", ignoreCase = true)) {
                    val intent = Intent().apply {
                        putExtra("activated", true)
                        putExtra("expiry", response.trial_expires_at ?: "")
                        putExtra("message", errorMsg)
                    }
                    setResult(Activity.RESULT_OK, intent)
                    finish()
                }
            }
        } catch (e: Exception) {
            // Not valid trial JSON, ignore and let user interact with WebView
            Log.d(TAG, "JSON parsing failed or not a trial response: ${e.message}")
        }
    }
}