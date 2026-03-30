package com.pnkastro.pas

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.pnkastro.pas.ui.theme.PASTheme
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import android.webkit.WebSettings
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.GeolocationPermissions
import androidx.core.content.ContextCompat
import android.Manifest
import android.content.pm.PackageManager
import android.view.WindowManager
import okhttp3.OkHttpClient
import okhttp3.Request

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
    val android_hash: String? = null,
    val phone: String? = null,
    val code: String? = null,
    val device_model: String? = null,
    val activated_at: String? = null,
    val migrated: Boolean? = false,
    val migration_available: Boolean? = false,
    val type: String? = null,
    val current_imei: String? = null,
    val expiry: String? = null
)

class TrialWebViewActivity : ComponentActivity() {
    private val TAG = "TrialWeb"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Disable decor fits system windows to allow Compose to handle insets and IME padding correctly.
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)

        // Set soft input mode to adjust Resize so the window resizes when the keyboard appears.
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)

        val url = intent.getStringExtra("url") ?: ""

        if (url.isEmpty()) {
            Toast.makeText(this, "No URL provided", Toast.LENGTH_SHORT).show()
            setResult(RESULT_CANCELED)
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
                            currentUrl = url,
                            onOpenInBrowser = {},
                            onRegisterRequested = {},
                            onTryRequested = {},
                            onAboutRequested = {},
                            onShareRequested = {
                                // Share the trial URL passed into this activity
                                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                    type = "text/plain"
                                    putExtra(Intent.EXTRA_TEXT, url)
                                }
                                startActivity(Intent.createChooser(shareIntent, "Share link"))
                            }
                        )
                    },
                    modifier = Modifier.fillMaxSize().imePadding(),
                    contentWindowInsets = WindowInsets(0, 0, 0, 0)
                ) { innerPadding ->
                    Box(modifier = Modifier.padding(innerPadding).fillMaxSize()) {
                        AndroidView(
                            factory = { ctx ->
                                WebView(ctx).apply {
                                    // Enable WebView debugging for easier inspection
                                    try {
                                        WebView.setWebContentsDebuggingEnabled(true)
                                    } catch (e: Exception) {
                                        Log.w(TAG, "Failed to enable WebView debugging: ${e.message}")
                                    }

                                    settings.apply {
                                        javaScriptEnabled = true
                                        domStorageEnabled = true
                                        allowContentAccess = true
                                        allowFileAccess = true
                                        // Allow mixed content while debugging/troubleshooting (use with caution)
                                        mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                                    }

                                    // Add JS interface so the page can directly notify the app
                                    addJavascriptInterface(object {
                                        @JavascriptInterface
                                        fun onMigrationSuccess(json: String) {
                                            Log.d(TAG, "AndroidBridge.onMigrationSuccess called with: $json")
                                            runOnUiThread {
                                                handleJsonResponse(json)
                                            }
                                        }

                                        @JavascriptInterface
                                        fun onActivationSuccess(json: String) {
                                            Log.d(TAG, "AndroidBridge.onActivationSuccess called with: $json")
                                            runOnUiThread {
                                                handleJsonResponse(json)
                                            }
                                        }
                                    }, "AndroidBridge")

                                    // Enable cookies
                                    try {
                                        val cm = CookieManager.getInstance()
                                        cm.setAcceptCookie(true)
                                        cm.setAcceptThirdPartyCookies(this, true)
                                    } catch (e: Exception) {
                                        Log.w(TAG, "Cookie setup failed: ${e.message}")
                                    }

                                    // WebChromeClient that logs console messages to Logcat
                                    webChromeClient = object : WebChromeClient() {
                                        override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
                                            try {
                                                Log.d("WVC", "${consoleMessage?.message()} ${consoleMessage?.sourceId()}:${consoleMessage?.lineNumber()}")
                                            } catch (e: Exception) {
                                                // ignore logging failures
                                            }
                                            return true
                                        }

                                        // Handle window.open() calls so they load in the same WebView (prevents blank popup windows)
                                        override fun onCreateWindow(view: WebView?, isDialog: Boolean, isUserGesture: Boolean, resultMsg: android.os.Message?): Boolean {
                                            try {
                                                val transport = resultMsg?.obj as? WebView.WebViewTransport
                                                // Use the originating WebView to receive popup content so it renders in-place
                                                transport?.webView = view
                                                resultMsg?.sendToTarget()
                                                return true
                                            } catch (e: Exception) {
                                                Log.w("WebView", "onCreateWindow failed: " + e.message)
                                            }
                                            return false
                                        }

                                        // Grant geolocation permission to the site when app has location runtime permission
                                        override fun onGeolocationPermissionsShowPrompt(origin: String, callback: GeolocationPermissions.Callback) {
                                            val activity = ctx as? ComponentActivity
                                            val granted = if (activity != null) {
                                                (ContextCompat.checkSelfPermission(activity, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) ||
                                                (ContextCompat.checkSelfPermission(activity, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED)
                                            } else {
                                                false
                                            }
                                            try {
                                                // Callback expects (origin: String, allow: Boolean, retain: Boolean)
                                                callback.invoke(origin, granted, false)
                                            } catch (e: Exception) {
                                                Log.w(TAG, "Geolocation callback invoke failed: ${e.message}")
                                            }
                                        }
                                    }

                                    // Use OkHttp to proxy trial endpoints so we can inspect status/headers/body
                                    val okClient = OkHttpClient()

                                    webViewClient = object : WebViewClient() {
                                        override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                                            super.onPageStarted(view, url, favicon)
                                            // Hide the WebView while loading to prevent JSON flickering if possible
                                            view?.alpha = 0f
                                        }

                                        override fun onPageFinished(view: WebView?, url: String?) {
                                            super.onPageFinished(view, url)

                                            // Inject a small JS shim that converts absolute trial activation URLs to relative paths
                                            // and clears any `baseUrl` variable so the page uses relative endpoints like 'trial_activate.php'.
                                            // This is a safe, non-destructive rewrite performed only for the trial registration page.
                                            try {
                                                view?.evaluateJavascript(
                                                    "(function(){try{if(window.baseUrl){window.baseUrl='';}Array.from(document.querySelectorAll('a')).forEach(function(a){try{if(a.href && /trial_activate(\\.php)?/.test(a.href)){a.href=a.href.replace(/^.*trial_activate/,'trial_activate');}}catch(e){} });Array.from(document.forms).forEach(function(f){try{if(f.action && /trial_activate(\\.php)?/.test(f.action)){f.action=f.action.replace(/^.*trial_activate/,'trial_activate');}}catch(e){} });}catch(e){} })();"
                                                ) { /* ignore result */ }
                                            } catch (e: Exception) {
                                                Log.w(TAG, "JS injection for relative trial URLs failed: ${e.message}")
                                            }

                                            // Extract content to check for JSON
                                            view?.evaluateJavascript(
                                                "(function() { return document.body.innerText; })();"
                                            ) { jsonString ->
                                                if (jsonString != null && jsonString != "null" && jsonString.isNotBlank()) {
                                                    val intercepted = handleJsonResponse(jsonString)
                                                    // if not intercepted (not a terminal JSON), show the view
                                                    if (!intercepted) {
                                                        view.alpha = 1f
                                                    }
                                                } else {
                                                    view.alpha = 1f
                                                }
                                            }
                                        }

                                        override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                                            val uri: Uri = request?.url ?: return false
                                            Log.d(TAG, "shouldOverride: $uri")

                                            // If the link is an HTTP/HTTPS URL, force it to load inside this WebView so the app
                                            // retains cookies and the Android ID handling remains within the app.
                                            val scheme = uri.scheme?.lowercase()
                                            if (scheme == "http" || scheme == "https") {
                                                try {
                                                    view?.loadUrl(uri.toString())
                                                } catch (e: Exception) {
                                                    Log.w(TAG, "Failed to load URL in-WebView: ${e.message}")
                                                }
                                                return true
                                            }

                                            // Intercept the custom scheme used by the server to indicate trial activation
                                            if (scheme == "myapp" && uri.host == "trial") {
                                                val activated = (uri.getQueryParameter("activated") == "1") || (uri.getQueryParameter("activated")?.lowercase() == "true")
                                                val expiry = uri.getQueryParameter("expiry") ?: ""

                                                val data = Intent()
                                                data.putExtra("activated", activated)
                                                data.putExtra("expiry", expiry)

                                                setResult(RESULT_OK, data)
                                                finish()
                                                return true
                                            }

                                            // For other schemes (mailto:, intent:, market:, etc.) allow the system to handle them.
                                            return try {
                                                val external = Intent(Intent.ACTION_VIEW, uri)
                                                startActivity(external)
                                                true
                                            } catch (e: Exception) {
                                                Log.w(TAG, "No external handler for $uri: ${e.message}")
                                                false
                                            }
                                        }

                                        override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                                            val reqUrl = request?.url?.toString() ?: return null

                                            // Only intercept likely trial activation endpoints to minimize interference
                                            if (reqUrl.contains("trial_activate", ignoreCase = true) || reqUrl.contains("trial-activate", ignoreCase = true) || reqUrl.contains("trial_activate.php", ignoreCase = true)) {
                                                Log.d(TAG, "Intercepting trial request via OkHttp: $reqUrl")

                                                try {
                                                    val builder = Request.Builder().url(reqUrl)

                                                    // Forward cookies from CookieManager if present
                                                    try {
                                                        val cookie = CookieManager.getInstance().getCookie(reqUrl)
                                                        if (!cookie.isNullOrBlank()) {
                                                            builder.header("Cookie", cookie)
                                                        }
                                                    } catch (e: Exception) {
                                                        Log.w(TAG, "Failed to attach cookies: ${e.message}")
                                                    }

                                                    // Forward some common headers if needed
                                                    request.requestHeaders.forEach { (k, v) ->
                                                        try { builder.header(k, v) } catch (e: Exception) {
                                                            Log.w(TAG, "Failed to set header $k: ${e.message}")
                                                        }
                                                    }

                                                    val okReq = builder.build()
                                                    val resp = okClient.newCall(okReq).execute()

                                                    val code = resp.code
                                                    val reason = resp.message
                                                    val contentTypeRaw = resp.header("Content-Type") ?: ""
                                                    val mime = contentTypeRaw.split(";")[0].ifEmpty { "text/html" }
                                                    val charset = contentTypeRaw.split(";", limit = 2).getOrNull(1)?.substringAfter("charset=")?.trim() ?: "utf-8"

                                                    val bodyString = resp.body?.string() ?: ""
                                                    val snippet = if (bodyString.length > 200) bodyString.substring(0, 200) + "...[truncated]" else bodyString
                                                    Log.d(TAG, "OkHttp intercept: code=$code mime=$mime len=${bodyString.length} snippet=${snippet.replace(Regex("[\r\n]+"), " ")}")

                                                    // Try to parse JSON for terminal detection
                                                    val isJson = bodyString.trimStart().startsWith("{")

                                                    if (isJson) {
                                                        try {
                                                            val format = Json { ignoreUnknownKeys = true }
                                                            val trialResp = format.decodeFromString<TrialResponse>(bodyString)
                                                            Log.d(TAG, "OkHttp parsed TrialResponse: success=${trialResp.success} migration_available=${trialResp.migration_available} migrated=${trialResp.migrated} android_hash_present=${!trialResp.android_hash.isNullOrBlank()} trial_expires_at=${trialResp.trial_expires_at}")

                                                            // If terminal (migration check or activation/migration), handle in app and suppress raw JSON
                                                            if (trialResp.migration_available == true || trialResp.migrated == true || !trialResp.trial_expires_at.isNullOrBlank() || !trialResp.expires_at.isNullOrBlank() || !trialResp.android_hash.isNullOrBlank()) {
                                                                Log.d(TAG, "Terminal JSON detected via OkHttp response; handling in app and returning blank HTML")
                                                                runOnUiThread { handleJsonResponse(bodyString) }
                                                                val blank = "<html><body></body></html>"
                                                                return WebResourceResponse("text/html", "utf-8", code, reason, mapOf("Content-Type" to "text/html"), ByteArrayInputStream(blank.toByteArray(Charsets.UTF_8)))
                                                            }
                                                        } catch (e: Exception) {
                                                            Log.w(TAG, "Failed to parse JSON during OkHttp intercept: ${e.message}")
                                                        }
                                                    }

                                                    // Build response headers map (use first header value)
                                                    val headerMap = mutableMapOf<String, String>()
                                                    resp.headers.names().forEach { name ->
                                                        val value = resp.header(name)
                                                        if (value != null) headerMap[name] = value
                                                    }

                                                    // Return the fetched response to the WebView (preserve status/headers/body)
                                                    val inputStream = ByteArrayInputStream(bodyString.toByteArray(Charsets.UTF_8))
                                                    return WebResourceResponse(mime, charset, code, reason, headerMap, inputStream)

                                                } catch (e: Exception) {
                                                    Log.e(TAG, "OkHttp intercept failed: ${e.message}")
                                                    return null
                                                }
                                            }

                                            return null
                                        }
                                    }

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

    private fun handleJsonResponse(rawJson: String?): Boolean {
        if (rawJson == null || rawJson == "null" || rawJson.isBlank()) return false

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
            return false // Not valid JSON
        }

        try {
            val format = Json { ignoreUnknownKeys = true }
            val response = format.decodeFromString<TrialResponse>(json)

            // DEBUG: log all branches and important variables
            Log.d(TAG, "handleJsonResponse: success=${response.success} migration_available=${response.migration_available} migrated=${response.migrated} android_hash=${response.android_hash} trial_expires_at=${response.trial_expires_at} expires_at=${response.expires_at} type=${response.type} current_imei=${response.current_imei} expiry=${response.expiry} message=${response.message}")

            if (response.success) {
                // Handle different success types based on the instruction table

                // 1. Check Phone Endpoint (?check_phone=1)
                if (response.migration_available == true) {
                    // Intercept check_phone response and pass details back to the caller so the app
                    // can enable migration UI. Do not let the raw JSON be shown in the WebView.
                    val info = when (response.type) {
                        "account" -> "account"
                        "subscription" -> "subscription"
                        else -> "unknown"
                    }
                    Log.d(TAG, "Check phone intercepted: type=$info migration_available=${response.migration_available}")

                    val result = Intent().apply {
                        putExtra("migration_available", true)
                        putExtra("migration_type", response.type)
                        putExtra("current_imei", response.current_imei)
                        putExtra("expiry", response.expiry)
                        putExtra("message", response.message)
                        // If server returned an android_hash (new identifier), include it so the caller can persist it
                        if (!response.android_hash.isNullOrBlank()) {
                            putExtra("android_hash", response.android_hash)
                        }
                        // Also include phone/imei fields when available to aid caller UI
                        if (!response.phone.isNullOrBlank()) putExtra("phone", response.phone)
                        if (!response.imei.isNullOrBlank()) putExtra("imei", response.imei)
                    }

                    // Let caller decide; we still show a short toast for instant feedback
                    Toast.makeText(this, response.message ?: "Migration available", Toast.LENGTH_SHORT).show()
                    setResult(RESULT_OK, result)
                    finish()
                    return true
                }

                // 2. Migration or Trial Activation Success
                val isMigrated = response.migrated == true
                // Terminal JSON response found - intercept and close

                val expiry = response.trial_expires_at ?: response.expires_at ?: response.expiry ?: ""

                val resultIntent = Intent().apply {
                    putExtra("activated", true)
                    putExtra("migrated", isMigrated)
                    putExtra("expiry", expiry)
                    putExtra("message", response.message)
                    putExtra("redirect_url", response.redirect_url)
                    // If server provides a new android_hash, we pass it back to MainActivity to persist
                    if (!response.android_hash.isNullOrBlank()) {
                        putExtra("android_hash", response.android_hash)
                    }
                }

                setResult(RESULT_OK, resultIntent)
                val toastMsg = response.message ?: if (isMigrated) "Migration complete" else "Trial activated"
                Toast.makeText(this, toastMsg, Toast.LENGTH_LONG).show()
                finish()
                return true
            } else {
                // success: false + error field
                val errorMsg = response.error ?: "Unknown error"
                Log.e(TAG, "Server error: $errorMsg")

                // Specific error handling from instructions
                if (errorMsg.contains("already has an active trial", ignoreCase = true)) {
                    val expiry = response.trial_expires_at ?: ""
                    Toast.makeText(this, "Device already active until $expiry", Toast.LENGTH_LONG).show()
                    // If already active, we can treat it as 'activated' to let them in
                    val resultIntent = Intent().apply {
                        putExtra("activated", true)
                        putExtra("expiry", expiry)
                    }
                    setResult(RESULT_OK, resultIntent)
                    finish()
                    return true
                } else if (errorMsg.contains("Invalid code", ignoreCase = true)) {
                    Toast.makeText(this, "Trial code expired or invalid", Toast.LENGTH_SHORT).show()
                    // Stay in WebView to let user try again
                    return false
                } else if (errorMsg.contains("Invalid phone", ignoreCase = true) || errorMsg.contains("invalid imei", ignoreCase = true)) {
                    Toast.makeText(this, "Invalid phone/device ID. Try again", Toast.LENGTH_SHORT).show()
                    return false
                } else if (errorMsg.startsWith("PHP Error") || errorMsg.startsWith("Database query failed")) {
                    Toast.makeText(this, "Server error, try again", Toast.LENGTH_SHORT).show()
                    return false
                } else {
                    Toast.makeText(this, errorMsg, Toast.LENGTH_LONG).show()
                    return false
                }
            }
        } catch (e: Exception) {
            // Not valid trial JSON, ignore and let user interact with WebView
            Log.d(TAG, "JSON parsing failed or not a trial response: ${e.message}")
            return false
        }
    }
}