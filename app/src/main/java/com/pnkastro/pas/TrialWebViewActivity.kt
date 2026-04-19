package com.pnkastro.pas

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
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.pnkastro.pas.ui.theme.PASTheme
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.ByteArrayInputStream
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
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Network
import android.net.NetworkRequest
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import android.content.Context
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp

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
    private val isOffline = mutableStateOf(false)
    private val pageFailed = mutableStateOf(false)
    // When true, prevent automatic clearing of the offline/error UI until the user explicitly retries/exits
    private val manualErrorLock = mutableStateOf(false)

    // Keep a direct reference to the current WebView so activity-level callbacks can reload it
    private var currentWebView: WebView? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    private fun isNetworkAvailable(): Boolean {
        val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = connectivityManager.activeNetwork ?: return false
        val activeNetwork = connectivityManager.getNetworkCapabilities(network) ?: return false
        return when {
            activeNetwork.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> true
            activeNetwork.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> true
            activeNetwork.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> true
            else -> false
        }
    }

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

        isOffline.value = !isNetworkAvailable()

        setContent {
            PASTheme {
                val offline by remember { isOffline }
                val failed by remember { pageFailed }
                val webViewRef = remember { mutableStateOf<WebView?>(null) }

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
                        if (offline || failed) {
                            val errorMsg = if (offline) {
                                "Connection failed. Please check your internet or try again later."
                            } else {
                                "Server error or unexpected response. Try again or contact support."
                            }

                            OfflineErrorScreen(onRetry = {
                                if (isNetworkAvailable()) {
                                    isOffline.value = false
                                    pageFailed.value = false
                                    // Prefer reload on the actual WebView reference
                                    currentWebView?.post {
                                        try {
                                            currentWebView?.reload()
                                        } catch (e: Exception) {
                                            Log.w(TAG, "Retry reload failed: ${e.message}")
                                        }
                                    }
                                } else {
                                    isOffline.value = true
                                    Toast.makeText(this@TrialWebViewActivity, "Still no internet connection", Toast.LENGTH_SHORT).show()
                                }
                            }, message = errorMsg)
                        } else {
                            AndroidView(
                                factory = { ctx ->
                                    WebView(ctx).apply {
                                        // store reference for activity-level reloads
                                        currentWebView = this
                                        webViewRef.value = this

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
                                        @Suppress("unused")
                                        val androidBridge = object {
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
                                        }

                                        addJavascriptInterface(androidBridge, "AndroidBridge")

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
                                                view?.alpha = 0f
                                                if (!manualErrorLock.value) pageFailed.value = false
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
                                                        // First, check for obvious server/PHP error markers in the returned text.
                                                        try {
                                                            val lowered = jsonString.lowercase()
                                                            val serverErrorMarkers = listOf(
                                                                "php error",
                                                                "database query failed",
                                                                "fatal error",
                                                                "internal server error",
                                                                "warning:",
                                                                "uncaught exception",
                                                                "stack trace",
                                                                "cannot connect",
                                                                "404 not found",
                                                                "500 internal",
                                                                "sql syntax",
                                                                "mysql"
                                                            )
                                                            if (serverErrorMarkers.any { lowered.contains(it) }) {
                                                                Log.e(TAG, "Detected server-side error text in page; suppressing raw HTML and showing friendly UI")
                                                                pageFailed.value = true
                                                                manualErrorLock.value = true
                                                                // Suppress the raw server HTML so user sees friendly retry UI
                                                                runOnUiThread {
                                                                    try {
                                                                        view?.stopLoading()
                                                                        view?.loadUrl("about:blank")
                                                                    } catch (e: Exception) {
                                                                        Log.w(TAG, "Failed to suppress server error page: ${e.message}")
                                                                    }
                                                                }
                                                                return@evaluateJavascript
                                                            }
                                                        } catch (e: Exception) {
                                                            // If detection fails, continue to normal processing
                                                            Log.w(TAG, "Server error detection failed: ${e.message}")
                                                        }

                                                        val intercepted = handleJsonResponse(jsonString)
                                                        if (!intercepted) {
                                                            view.alpha = 1f
                                                        }
                                                    } else {
                                                        view.alpha = 1f
                                                    }
                                                }
                                            }

                                            override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: android.webkit.WebResourceError?) {
                                                // Trigger only for main frame errors
                                                if (request?.isForMainFrame == true) {
                                                    Log.e(TAG, "onReceivedError: ${error?.description}")
                                                    // Detect ERR_CACHE_MISS and route to the site index/home to recover from back/button cache races
                                                    try {
                                                        val desc = error?.description?.toString() ?: ""
                                                        if (desc.contains("ERR_CACHE_MISS", ignoreCase = true)) {
                                                            manualErrorLock.value = false
                                                            pageFailed.value = false
                                                            val home = try { if (BuildConfig.SITE_URL.isNotBlank()) BuildConfig.SITE_URL else "https://pkastro.com/index.php" } catch (e: Exception) { "https://pkastro.com/index.php" }
                                                            runOnUiThread {
                                                                try { view?.loadUrl(home) } catch (e: Exception) { Log.w(TAG, "Failed to load home on cache-miss: ${e.message}") }
                                                            }
                                                            return
                                                        }
                                                    } catch (e: Exception) {
                                                        Log.w(TAG, "ERR_CACHE_MISS detection failed: ${e.message}")
                                                    }
                                                    pageFailed.value = true
                                                    manualErrorLock.value = true
                                                    // Prevent the WebView from rendering raw error content
                                                    try {
                                                        runOnUiThread {
                                                            view?.stopLoading()
                                                            view?.loadUrl("about:blank")
                                                        }
                                                    } catch (e: Exception) {
                                                        Log.w(TAG, "Failed to suppress error page: ${e.message}")
                                                    }
                                                }
                                            }

                                            override fun onReceivedHttpError(view: WebView?, request: WebResourceRequest?, errorResponse: WebResourceResponse?) {
                                                // Only handle main frame errors to avoid interfering with subresources
                                                if (request?.isForMainFrame == true) {
                                                    val status = errorResponse?.statusCode ?: 0
                                                    Log.e(TAG, "onReceivedHttpError: $status")
                                                    if (status >= 400) {
                                                        pageFailed.value = true
                                                        manualErrorLock.value = true
                                                        // Suppress the raw server error HTML so the user sees the friendly retry UI
                                                        try {
                                                            runOnUiThread {
                                                                view?.stopLoading()
                                                                view?.loadUrl("about:blank")
                                                            }
                                                        } catch (e: Exception) {
                                                            Log.w(TAG, "Failed to suppress HTTP error page: ${e.message}")
                                                        }
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

        // Register a network callback to update isOffline and auto-retry when network returns
        try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val nrBuilder = NetworkRequest.Builder().addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            networkCallback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    super.onAvailable(network)
                    Log.d(TAG, "Network available")
                    runOnUiThread {
                        isOffline.value = false
                        // Only auto-clear and reload when user hasn't explicitly locked the error overlay
                        if (!manualErrorLock.value) {
                            pageFailed.value = false
                            currentWebView?.post {
                                try { currentWebView?.reload() } catch (e: Exception) { Log.w(TAG, "Reload after network available failed: ${e.message}") }
                            }
                        } else {
                            Log.d(TAG, "Network available but manual error lock is set; waiting for user action")
                        }
                    }
                }

                override fun onLost(network: Network) {
                    super.onLost(network)
                    Log.d(TAG, "Network lost")
                    runOnUiThread {
                        isOffline.value = !isNetworkAvailable()
                    }
                }
            }
            cm.registerNetworkCallback(nrBuilder.build(), networkCallback!!)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to register network callback: ${e.message}")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // Unregister network callback
        try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            if (networkCallback != null) cm.unregisterNetworkCallback(networkCallback!!)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to unregister network callback: ${e.message}")
        }
        try { currentWebView?.destroy() } catch (_: Exception) {}
        currentWebView = null
    }

    override fun onBackPressed() {
        // If the page was marked as failed (network or server error), return a helpful message so
        // the caller (MainActivity) can show a toast or take action. Otherwise return a generic cancel.
        try {
            val out = Intent()
            if (pageFailed.value) {
                out.putExtra("error_message", "Connection or server error while loading trial page")
            } else if (isOffline.value) {
                out.putExtra("error_message", "No internet connection")
            }
            setResult(RESULT_CANCELED, out)
        } catch (e: Exception) {
            Log.w(TAG, "onBackPressed: failed to attach error_message: ${e.message}")
        }
        super.onBackPressed()
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

@Composable
fun OfflineErrorScreen(onRetry: () -> Unit, message: String = "Connection failed. Please check your internet or try again later.") {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.padding(24.dp)
        ) {
            Image(
                painter = painterResource(id = R.mipmap.ic_launcher),
                contentDescription = "App Logo",
                modifier = Modifier.size(120.dp)
            )

            Spacer(modifier = Modifier.height(32.dp))

            Icon(
                imageVector = Icons.Default.Close,
                contentDescription = "Offline",
                tint = Color.Red,
                modifier = Modifier.size(48.dp)
            )

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = "Connection Error",
                color = Color.White,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = message,
                color = Color.LightGray,
                fontSize = 16.sp,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(40.dp))

            Button(
                onClick = onRetry,
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFFFFD700), // Gold/Yellow
                    contentColor = Color.Black
                ),
                modifier = Modifier.fillMaxWidth(0.7f).height(50.dp),
                shape = CircleShape
            ) {
                Text("Retry", fontWeight = FontWeight.Bold, fontSize = 16.sp)
            }
        }
    }
}