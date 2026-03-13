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
    val activated_at: String? = null
)

class TrialWebViewActivity : ComponentActivity() {
    private val TAG = "TrialWeb"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val webView = WebView(this)
        setContentView(webView)

        val url = intent.getStringExtra("url") ?: ""

        // WebView settings
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowContentAccess = true
            allowFileAccess = true
        }

        // Enable cookies
        try {
            val cm = CookieManager.getInstance()
            cm.setAcceptCookie(true)
            cm.setAcceptThirdPartyCookies(webView, true)
        } catch (e: Exception) {
            Log.w(TAG, "Cookie setup failed: ${e.message}")
        }

        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                // If the page is finished, try to extract JSON if the server returns it as body
                // This is a common pattern for APIs that are also browsable
                view?.evaluateJavascript(
                    "(function() { return document.body.innerText; })();"
                ) { jsonString ->
                    handleJsonResponse(jsonString)
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

        webView.webChromeClient = WebChromeClient()

        if (url.isNotEmpty()) {
            webView.loadUrl(url)
        } else {
            Toast.makeText(this, "No URL provided", Toast.LENGTH_SHORT).show()
            setResult(Activity.RESULT_CANCELED)
            finish()
        }
    }

    private fun handleJsonResponse(rawJson: String?) {
        if (rawJson == null || rawJson == "null") return

        // Clean the string from evaluateJavascript (it might be wrapped in quotes)
        var json = rawJson.trim()
        if (json.startsWith("\"") && json.endsWith("\"")) {
            json = json.substring(1, json.length - 1)
                .replace("\\\"", "\"")
                .replace("\\\\", "\\")
        }

        try {
            val format = Json { ignoreUnknownKeys = true }
            val response = format.decodeFromString<TrialResponse>(json)

            if (response.success) {
                val expiry = response.trial_expires_at ?: response.expires_at ?: ""
                val intent = Intent().apply {
                    putExtra("activated", true)
                    putExtra("expiry", expiry)
                    putExtra("message", response.message ?: "Trial activated")
                    putExtra("redirect_url", response.redirect_url)
                }
                setResult(Activity.RESULT_OK, intent)
                Toast.makeText(this, response.message ?: "Trial activated", Toast.LENGTH_LONG).show()
                finish()
            } else if (response.error != null) {
                // Handle various error codes if needed, but the requirement is to show error field
                val errorMsg = response.error
                Log.e(TAG, "Trial error: $errorMsg ${response.debug_error ?: ""}")
                Toast.makeText(this, errorMsg, Toast.LENGTH_LONG).show()

                // If it's a conflict (already active), we might want to finish anyway
                if (errorMsg.contains("already has an active trial", ignoreCase = true)) {
                    val intent = Intent().apply {
                        putExtra("activated", true)
                        putExtra("expiry", response.trial_expires_at ?: "")
                    }
                    setResult(Activity.RESULT_OK, intent)
                    finish()
                }
            }
        } catch (e: Exception) {
            // Not valid JSON or different format, ignore and let user interact with WebView
            Log.d(TAG, "Not a trial JSON response: ${e.message}")
        }
    }
}