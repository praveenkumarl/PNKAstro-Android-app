package com.example.pas

import android.os.Bundle
import android.widget.Toast
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material3.Scaffold
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.background
import androidx.compose.ui.graphics.Color
import com.example.pas.ui.theme.PASTheme
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

// New imports for WebView + Compose interop and back handling
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebSettings
import android.webkit.WebResourceRequest
import android.webkit.WebResourceError
import android.webkit.WebResourceResponse
import android.webkit.WebChromeClient
import android.webkit.ConsoleMessage
import android.webkit.GeolocationPermissions
import androidx.activity.compose.BackHandler
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.platform.LocalView

// Add imports for image resource in Compose
import androidx.compose.foundation.Image
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.layout.ContentScale
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.colorResource

// Location permission imports
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.result.ActivityResultLauncher

// Additional imports for TopAppBar, icons, menu, clipboard, Custom Tabs
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.IconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Share
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.core.net.toUri
import androidx.compose.ui.res.stringResource

// Imports for window insets and status bar
import androidx.core.view.WindowCompat
import androidx.compose.material3.TopAppBarDefaults
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen

class MainActivity : ComponentActivity() {
    private val TAG = "PAS_AUTH"

    // expose deviceId as a property so composables can read it
    // Use a Compose MutableState so UI recomposes when the ID changes
    private val deviceIdValue = androidx.compose.runtime.mutableStateOf<String?>(null)

    // duration to show the launcher splash (milliseconds). Change this value to adjust splash time.
    private val splashDurationMs = 1500L

    // Initialize permission launcher
    private lateinit var permissionLauncher: ActivityResultLauncher<Array<String>>

    // Flag to track location permission status for Compose to react
    private val hasLocationPermission = mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        // Install modern Splash Screen before super.onCreate()
        val splashScreen = installSplashScreen()

        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Keep the splash screen on screen until authentication is complete
        var keepSplashScreen = true
        splashScreen.setKeepOnScreenCondition { keepSplashScreen }

        // Configure status bar for edge-to-edge display with black background
        val insetsController = WindowCompat.getInsetsController(window, window.decorView)
        insetsController.isAppearanceLightStatusBars = false  // Dark icons for black background

        // Initialize permission launcher for multiple permissions
        permissionLauncher = registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { permissions ->
            hasLocationPermission.value = permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                                         permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true
            Log.d(TAG, "Location permissions result: ${hasLocationPermission.value}")
        }

        // Request location permissions on app load if not already granted
        val hasFine = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val hasCoarse = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        hasLocationPermission.value = hasFine || hasCoarse

        if (!hasLocationPermission.value) {
            Log.d(TAG, "Requesting location permissions...")
            permissionLauncher.launch(arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            ))
        }

        // Use an app-scoped generated ID persisted in SharedPreferences to avoid using device identifiers
        // This ID is generated once (UUID) and reused across app launches.
        run {
            val prefs = getSharedPreferences("pas_prefs", Context.MODE_PRIVATE)
            var storedId = prefs.getString("app_device_id", null)
            if (storedId.isNullOrEmpty()) {
                storedId = java.util.UUID.randomUUID().toString()
                prefs.edit().putString("app_device_id", storedId).apply()
            }
            deviceIdValue.value = storedId
        }

        // State to hold final URL and allowed host
        val webUrlState = mutableStateOf<String?>(null)
        val allowedHostState = mutableStateOf<String?>(null)

        // Enable WebView remote debugging so you can inspect the WebView from desktop Chrome when debugging APKs
        WebView.setWebContentsDebuggingEnabled(true)

        // Set the Compose UI immediately
        setContent {
            PASTheme {
                val urlToLoad = webUrlState.value
                val allowedHost = allowedHostState.value
                val permissionGranted = hasLocationPermission.value

                Scaffold(
                    topBar = {
                        AppTopBar(
                            brandRes = R.string.app_name,
                            deviceId = deviceIdValue.value,
                            currentUrl = urlToLoad,
                            onOpenInBrowser = { url ->
                                openUrlInCustomTab(url)
                            },
                            onResetDeviceId = {
                                resetDeviceId()
                            }
                        )
                    },
                    modifier = Modifier.fillMaxSize()
                ) { innerPadding ->
                    if (urlToLoad == null) {
                        LoadingScreen(
                            modifier = Modifier.padding(innerPadding)
                        )
                    } else {
                        // pass deviceIdValue into WebViewScreen so it can append key for index.php urls
                        WebViewScreen(
                            url = urlToLoad,
                            allowedHost = allowedHost,
                            permissionStatus = permissionGranted,
                            deviceId = deviceIdValue.value,
                            modifier = Modifier.padding(innerPadding)
                        )
                    }
                }
            }
        }

        // Run authentication and delay splash dismissal
        lifecycleScope.launch {
            if (deviceIdValue.value != null) {
                launch {
                    authenticateAndGetUrl(deviceIdValue.value!!) { finalUrl ->
                        webUrlState.value = finalUrl
                        allowedHostState.value = try {
                            finalUrl?.let { URL(it).host }
                        } catch (e: Exception) {
                            null
                        }
                    }
                }

                // Show splash for at least splashDurationMs, then wait for auth to finish
                val startTime = System.currentTimeMillis()
                delay(splashDurationMs)

                // Wait for auth to complete if it's still running
                while (webUrlState.value == null && (System.currentTimeMillis() - startTime < 10000)) {
                    delay(100)
                }
            }
            // Dismiss the splash screen once we have a URL or timeout
            keepSplashScreen = false
        }
    }

    // Regenerate and persist a new app-scoped device ID, update state and restart activity to re-run auth flow
    private fun resetDeviceId() {
        val prefs = getSharedPreferences("pas_prefs", Context.MODE_PRIVATE)
        val newId = java.util.UUID.randomUUID().toString()
        prefs.edit().putString("app_device_id", newId).apply()
        deviceIdValue.value = newId
        Toast.makeText(this, "Device ID reset", Toast.LENGTH_SHORT).show()
        // Restart activity to ensure auth flows with the new ID (simplest approach)
        recreate()
    }

    // helper that will append the password param (lat,lon) to the given URL then open it in a Custom Tab
    private fun openUrlInCustomTab(baseUrl: String) {
        lifecycleScope.launch {
            try {
                val loc = withContext(Dispatchers.IO) { getCurrentLocation(this@MainActivity) }
                val finalUrl = if (loc != null) {
                    appendPasswordParam(baseUrl, loc.latitude, loc.longitude)
                } else {
                    baseUrl
                }
                Log.d(TAG, "Opening external URL in Custom Tab: $finalUrl")
                val builder = CustomTabsIntent.Builder()
                val customTabsIntent = builder.build()
                customTabsIntent.launchUrl(this@MainActivity, finalUrl.toUri())
            } catch (e: Exception) {
                Log.e(TAG, "Error opening custom tab: ${e.message}")
                // fallback: open with ACTION_VIEW
                try {
                    val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, baseUrl.toUri())
                    startActivity(intent)
                } catch (ex: Exception) {
                    Log.e(TAG, "Fallback browser open failed: ${ex.message}")
                }
            }
        }
    }

    private fun authenticateAndGetUrl(deviceId: String, onResultUrl: (String?) -> Unit) {
        val authUrl = getString(R.string.auth_url) + "?key=" + deviceId
        val siteBase = getString(R.string.site_url)

        Log.d(TAG, "Starting authentication with URL: $authUrl")
        Log.d(TAG, "Site base URL: $siteBase")

        // Launch coroutine tied to lifecycle
        lifecycleScope.launch {
            val resultUrl = withContext(Dispatchers.IO) {
                try {
                    var urlToFetch = authUrl
                    var redirectCount = 0
                    var authCookies = ""  // Store cookies from auth response

                    // Follow redirects manually
                    while (redirectCount < 5) {
                        try {
                            val url = URL(urlToFetch)
                            val conn = (url.openConnection() as HttpURLConnection).apply {
                                requestMethod = "GET"
                                connectTimeout = 10000
                                readTimeout = 10000
                                instanceFollowRedirects = false
                                // Set a proper user agent to mimic browser behavior
                                setRequestProperty("User-Agent", "Mozilla/5.0 (Android)")
                                // If we have cookies from previous auth, send them along
                                if (authCookies.isNotEmpty()) {
                                    setRequestProperty("Cookie", authCookies)
                                }
                            }

                            val responseCode = conn.responseCode
                            Log.d(TAG, "Response code: $responseCode from URL: $urlToFetch")

                            // Extract Set-Cookie headers from response
                            val setCookieHeaders = conn.getHeaderFields()["Set-Cookie"]
                            if (setCookieHeaders != null && setCookieHeaders.isNotEmpty()) {
                                Log.d(TAG, "Received ${setCookieHeaders.size} Set-Cookie header(s)")
                                for (cookieHeader in setCookieHeaders) {
                                    Log.d(TAG, "Set-Cookie: $cookieHeader")
                                    // Extract just the cookie name=value part (before semicolon)
                                    val cookiePart = cookieHeader.split(";")[0].trim()
                                    if (authCookies.isEmpty()) {
                                        authCookies = cookiePart
                                    } else {
                                        authCookies += "; $cookiePart"
                                    }
                                }
                                Log.d(TAG, "Accumulated cookies: $authCookies")
                            }

                            // Check if it's a redirect
                            if (responseCode in 301..302) {
                                val location = conn.getHeaderField("Location")
                                Log.d(TAG, "Redirect to: $location")
                                conn.disconnect()

                                if (location != null) {
                                    urlToFetch = location
                                    redirectCount++
                                    continue
                                } else {
                                    break
                                }
                            } else if (responseCode in 200..299) {
                                // Read response body on success
                                val responseBody = try {
                                    conn.inputStream.bufferedReader().use { it.readText() }
                                } catch (e: Exception) {
                                    ""
                                }
                                Log.d(TAG, "Response body length: ${responseBody.length}")
                                conn.disconnect()

                                // Store cookies in WebView's CookieManager so they persist
                                try {
                                    if (authCookies.isNotEmpty()) {
                                        val cookieManager = android.webkit.CookieManager.getInstance()
                                        val domain = URL(siteBase).host

                                        // Parse and inject each cookie into the CookieManager
                                        authCookies.split("; ").forEach { cookie ->
                                            if (cookie.isNotEmpty()) {
                                                cookieManager.setCookie(siteBase, cookie)
                                                Log.d(TAG, "Injected cookie into WebView: $cookie for domain: $domain")
                                            }
                                        }

                                        // Flush cookies to ensure they're saved
                                        cookieManager.flush()
                                    }
                                } catch (e: Exception) {
                                    Log.w(TAG, "Failed to inject cookies: ${e.message}")
                                }

                                // Authentication successful, now get location (with retry)
                                var loc = getCurrentLocation(this@MainActivity)
                                var retryCount = 0

                                // If location is null on first try, wait briefly for permissions and retry
                                while (loc == null && retryCount < 3) {
                                    Log.d(TAG, "Location null on attempt ${retryCount + 1}, retrying in 500ms...")
                                    delay(500)
                                    loc = getCurrentLocation(this@MainActivity)
                                    retryCount++
                                }

                                Log.d(TAG, "Location retrieved after $retryCount retries - Lat: ${loc?.latitude}, Lon: ${loc?.longitude}")

                                val finalUrl = if (loc != null) {
                                    // include device key param when loading index.php post-auth
                                    appendPasswordParam(siteBase, loc.latitude, loc.longitude, deviceId)
                                } else {
                                    // If still no location, add ?password=noloc parameter and append key
                                    Log.w(TAG, "No location available after retries")
                                    val base = if (siteBase.contains("?")) {
                                        "$siteBase&password=noloc"
                                    } else {
                                        "$siteBase?password=noloc"
                                    }
                                    // Append key param after cleaning existing key if any
                                    try {
                                        val encodedKey = java.net.URLEncoder.encode(deviceId, java.nio.charset.StandardCharsets.UTF_8.toString())
                                        "$base&key=$encodedKey"
                                    } catch (e: Exception) {
                                        base
                                    }
                                }
                                Log.d(TAG, "Authentication successful with cookies preserved, final URL to load: $finalUrl")
                                return@withContext finalUrl
                            } else {
                                // Other response codes are failures
                                Log.d(TAG, "Unexpected response code: $responseCode")
                                conn.disconnect()
                                return@withContext null
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Error fetching URL $urlToFetch: ${e.message}", e)
                            // If we get a DNS error on HTTPS, try HTTP as fallback
                            if (urlToFetch.startsWith("https://") && e.message?.contains("unable to resolve host", ignoreCase = true) == true) {
                                val httpUrl = urlToFetch.replace("https://", "http://")
                                Log.d(TAG, "HTTPS failed, retrying with HTTP: $httpUrl")
                                urlToFetch = httpUrl
                                redirectCount++
                                continue
                            } else {
                                return@withContext null
                            }
                        }
                    }
                    null
                } catch (e: Exception) {
                    Log.e(TAG, "Authentication error: ${e.message}", e)
                    null
                }
            }

            if (resultUrl != null) {
                Log.d(TAG, "Authentication successful, will open in-app: $resultUrl")
                onResultUrl(resultUrl)
            } else {
                Log.d(TAG, "Authentication failed - no URL returned")
                Toast.makeText(this@MainActivity, "Authentication failed. Please try again.", Toast.LENGTH_LONG).show()
                onResultUrl(null)
            }
        }
    }
}

@Composable
fun WebViewScreen(url: String, allowedHost: String?, permissionStatus: Boolean, deviceId: String?, modifier: Modifier = Modifier) {
    // Keep a reference to the last URL to avoid re-creating WebView unnecessarily
    val lastUrl = remember { mutableStateOf(url) }
    // Track if the initial load was done without permissions
    val wasNoPerm = remember { mutableStateOf(false) }
    val context = LocalContext.current
    val rootView = LocalView.current

    // Track if page failed (network error or blank DOM)
    val pageFailed = remember { mutableStateOf(false) }
    val webViewRef = remember { mutableStateOf<WebView?>(null) }

    Box(modifier = modifier.fillMaxSize()) {
        // Provide Android WebView inside Compose
        AndroidView(factory = { ctx ->
            WebView(ctx).apply {
                webViewRef.value = this
                layoutParams = android.view.ViewGroup.LayoutParams(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT
                )
                // Enhanced settings for better compatibility
                settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    loadWithOverviewMode = true
                    useWideViewPort = true
                    cacheMode = WebSettings.LOAD_DEFAULT
                    userAgentString = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
                    mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                    allowFileAccess = true
                    allowContentAccess = true
                    // allow sites to use geolocation API inside WebView
                    setGeolocationEnabled(true)
                    // allow support for window.open/popups to be handled
                    setSupportMultipleWindows(true)
                    // Enable pinch zoom for accessibility
                    setBuiltInZoomControls(true)
                    setDisplayZoomControls(false)
                }

                // Ensure cookies are enabled (some sites rely on cookies for auth/session)
                try {
                    val cookieManager = android.webkit.CookieManager.getInstance()
                    cookieManager.setAcceptCookie(true)
                    cookieManager.setAcceptThirdPartyCookies(this, true)
                } catch (e: Exception) {
                    Log.w("WebView", "Failed to configure CookieManager: ${e.message}")
                }

                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                        // Only intercept top-level (main frame) navigations; do not touch subresources like favicon/js/css
                        if (request == null || request.isForMainFrame == false) return false

                        val uri = request.url
                        val host = uri.host
                        val urlString = uri.toString()

                        // Only intercept if it's our allowed host and doesn't already have the password
                        if ((allowedHost == null || host == allowedHost) && !urlString.contains("password=")) {
                            val activityForNav = (ctx as? ComponentActivity)
                            activityForNav?.lifecycleScope?.launch {
                                val loc = getCurrentLocation(ctx)
                                val finalUrl = if (loc != null) {
                                    // if this navigation targets index.php, include device key
                                    if (urlString.contains("index.php") && !deviceId.isNullOrEmpty()) {
                                        appendPasswordParam(urlString, loc.latitude, loc.longitude, deviceId)
                                    } else {
                                        appendPasswordParam(urlString, loc.latitude, loc.longitude)
                                    }
                                } else {
                                    // ensure we append even if loc is null to avoid infinite loop
                                    val fallback = if (urlString.contains("?")) "$urlString&password=noperm" else "$urlString?password=noperm"
                                    if (fallback.contains("index.php") && !deviceId.isNullOrEmpty()) {
                                        try {
                                            val encodedKey = java.net.URLEncoder.encode(deviceId, java.nio.charset.StandardCharsets.UTF_8.toString())
                                            "$fallback&key=$encodedKey"
                                        } catch (e: Exception) {
                                            fallback
                                        }
                                    } else {
                                        fallback
                                    }
                                }
                                Log.d("WebView", "Loading URL with location via override: $finalUrl")
                                view?.loadUrl(finalUrl)
                            }
                            return true
                        }
                        return false // Let WebView handle normal navigation (including urls with password)
                    }

                    override fun onPageFinished(view: WebView, url: String?) {
                        super.onPageFinished(view, url)
                        Log.i("WebView", "Page finished loading: $url")
                        // probe document length to help detect blank pages
                        try {
                            view.evaluateJavascript("(function(){return (document && document.documentElement)?document.documentElement.innerHTML.length:0;})()") { value ->
                                Log.i("WebView", "DOM length for $url => $value")
                                // value comes quoted like "123"; handle it
                                val len = try {
                                    value?.replace("\"", "")?.toInt() ?: 0
                                } catch (e: Exception) {
                                    0
                                }

                                if (len <= 2) {
                                    // Don't mark failure immediately — SPAs often populate DOM after onPageFinished.
                                    // Schedule a re-check after 1200ms and only mark failed if DOM still small AND title/snippet are empty.
                                    try {
                                        view.postDelayed({
                                            try {
                                                view.evaluateJavascript("(function(){return (document && document.documentElement)?document.documentElement.innerHTML.length:0;})()") { retryVal ->
                                                    val retryLen = try { retryVal?.replace("\"", "")?.toInt() ?: 0 } catch (e: Exception) { 0 }
                                                    Log.i("WebView", "DOM retry length for $url => $retryLen")

                                                    // Now probe title and a small body snippet
                                                    try {
                                                        view.evaluateJavascript("(function(){try{return document.title||'';}catch(e){return ''}})()") { titleVal ->
                                                            val title = titleVal?.replace("\"", "") ?: ""
                                                            Log.i("WebView", "Document title for $url => $title")

                                                            view.evaluateJavascript("(function(){try{var t=(document.body&&document.body.innerText)?document.body.innerText:''; return t.substring(0, Math.min(200, t.length));}catch(e){return ''}})()") { snippetVal ->
                                                                val snippet = snippetVal?.replace("\"", "") ?: ""
                                                                Log.i("WebView", "Body snippet for $url => ${snippet.replace(Regex("[\\r\\n]+"), " ")}")

                                                                // Only consider the page failed when DOM is tiny AND title/snippet are empty (indicates truly blank)
                                                                if (retryLen <= 2 && title.isEmpty() && snippet.isEmpty()) {
                                                                    Log.w("WebView", "Final detection: marking page as failed (len=$retryLen titleEmpty=${title.isEmpty()} snippetEmpty=${snippet.isEmpty()})")
                                                                    pageFailed.value = true
                                                                } else {
                                                                    pageFailed.value = false
                                                                }
                                                            }
                                                        }
                                                    } catch (e: Exception) {
                                                        Log.w("WebView", "Retry JS probe (title/snippet) failed: ${e.message}")
                                                        // If probing fails, be conservative and don't mark failed immediately
                                                        pageFailed.value = false
                                                    }

                                                }
                                            } catch (e: Exception) {
                                                Log.w("WebView", "Retry JS probe failed: ${e.message}")
                                            }
                                        }, 1200)
                                    } catch (e: Exception) {
                                        // Fallback: if postDelayed fails, mark failed as before
                                        Log.w("WebView", "postDelayed failed: ${e.message}")
                                        pageFailed.value = true
                                    }
                                } else {
                                    pageFailed.value = false
                                }
                            }

                            // Also log document.title and a short body text snippet for immediate debugging
                            view.evaluateJavascript("(function(){try{return document.title||'';}catch(e){return ''}})()") { titleVal ->
                                val title = titleVal?.replace("\"", "") ?: ""
                                Log.i("WebView", "Document title for $url => $title")
                            }

                            view.evaluateJavascript("(function(){try{var t=(document.body&&document.body.innerText)?document.body.innerText:''; return t.substring(0, Math.min(200, t.length));}catch(e){return ''}})()") { snippet ->
                                val snip = snippet?.replace("\"", "") ?: ""
                                Log.i("WebView", "Body snippet for $url => ${snip.replace(Regex("[\r\n]+"), " ")}")
                            }

                        } catch (e: Exception) {
                            Log.w("WebView", "Failed to evaluate JS on pageFinished: ${e.message}")
                        }
                    }

                    override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                        super.onReceivedError(view, request, error)
                        // Only treat main-frame errors as fatal for the page; subresource errors (favicon/js) should be logged but not hide the page
                        if (request.isForMainFrame == true) {
                            Log.e("WebView", "Received error loading ${request.url}: ${error.errorCode} ${error.description}")
                            pageFailed.value = true
                        } else {
                            Log.w("WebView", "Subresource error ${request.url}: ${error.errorCode} ${error.description}")
                        }
                    }

                    override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, errorResponse: WebResourceResponse) {
                        super.onReceivedHttpError(view, request, errorResponse)
                        // Don't mark the whole page as failed for subresource HTTP errors like favicon 404
                        if (request.isForMainFrame == true) {
                            Log.e("WebView", "HTTP error ${errorResponse.statusCode} for ${request.url}")
                            pageFailed.value = true
                        } else {
                            Log.w("WebView", "HTTP error ${errorResponse.statusCode} for subresource ${request.url}")
                        }
                    }
                }

                webChromeClient = object : WebChromeClient() {
                    override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
                        Log.i("WebViewConsole", "${consoleMessage?.message()} -- ${consoleMessage?.sourceId()} (${consoleMessage?.lineNumber()})")
                        return super.onConsoleMessage(consoleMessage)
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
                        callback.invoke(origin, granted, false)
                    }
                }

                // Disable long-click context menu (prevent 'open in new tab' style UI)
                setOnLongClickListener { true }
                isHapticFeedbackEnabled = false

                // load the initial url with location
                val activityForInit = (ctx as? ComponentActivity)
                activityForInit?.lifecycleScope?.launch {
                    val loc = getCurrentLocation(ctx)
                    wasNoPerm.value = (loc == null)

                    val finalUrl = if (loc != null) {
                        if (url.contains("index.php") && !deviceId.isNullOrEmpty()) {
                            appendPasswordParam(url, loc.latitude, loc.longitude, deviceId)
                        } else {
                            appendPasswordParam(url, loc.latitude, loc.longitude)
                        }
                    } else {
                        val base = if (url.contains("?")) "$url&password=noperm" else "$url?password=noperm"
                        if (base.contains("index.php") && !deviceId.isNullOrEmpty()) {
                            try {
                                val encodedKey = java.net.URLEncoder.encode(deviceId, java.nio.charset.StandardCharsets.UTF_8.toString())
                                "$base&key=$encodedKey"
                            } catch (e: Exception) {
                                base
                            }
                        } else {
                            base
                        }
                    }
                    Log.d("WebView", "Initial load with location: $finalUrl")
                    loadUrl(finalUrl)
                }
            }
        }, update = { webview ->
            // Save reference in case it changed
            webViewRef.value = webview

            // Trigger a reload ONLY if permission was granted after a 'noperm' load
            if (permissionStatus && wasNoPerm.value) {
                wasNoPerm.value = false
                val activity = context as? ComponentActivity
                activity?.lifecycleScope?.launch {
                    val loc = getCurrentLocation(context)
                    if (loc != null) {
                        val finalUrl = if (url.contains("index.php") && !deviceId.isNullOrEmpty()) {
                            appendPasswordParam(url, loc.latitude, loc.longitude, deviceId)
                        } else {
                            appendPasswordParam(url, loc.latitude, loc.longitude)
                        }
                        Log.d("WebView", "Permission granted! Reloading with real location: $finalUrl")
                        webview.loadUrl(finalUrl)
                    }
                }
            } else if (lastUrl.value != url) {
                val activityForUpdate = (context as? ComponentActivity)
                activityForUpdate?.lifecycleScope?.launch {
                    val loc = getCurrentLocation(context)
                    val finalUrl = if (loc != null) {
                        if (url.contains("index.php") && !deviceId.isNullOrEmpty()) {
                            appendPasswordParam(url, loc.latitude, loc.longitude, deviceId)
                        } else {
                            appendPasswordParam(url, loc.latitude, loc.longitude)
                        }
                    } else {
                        url
                    }
                    Log.d("WebView", "Updating URL: $finalUrl")
                    webview.loadUrl(finalUrl)
                }
                lastUrl.value = url
            }
        })

        // Show an overlay error UI when page load fails
        if (pageFailed.value) {
            Box(modifier = Modifier
                .fillMaxSize()
                .background(Color(0xAA000000)), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(text = "Page failed to load", color = Color.White)
                    androidx.compose.material3.Button(onClick = {
                        pageFailed.value = false
                        val w = webViewRef.value
                        if (w != null) {
                            try {
                                Log.d("WebView", "Retrying load for: ${w.url}")
                                w.reload()
                            } catch (e: Exception) {
                                Log.e("WebView", "Retry failed: ${e.message}")
                            }
                        }
                    }) {
                        Text(text = "Retry")
                    }
                }
            }
        }
    }

    // Handle back press: if WebView can go back, go back; otherwise let Activity handle it
    BackHandler {
        val webView = findWebViewInView(rootView)
        if (webView != null && webView.canGoBack()) {
            webView.goBack()
        } else {
            // no more history: finish activity
            val activity = rootView.context as? ComponentActivity
            activity?.finish()
        }
    }
}

// Simple helper to search the view tree for a WebView instance
fun findWebViewInView(view: android.view.View): WebView? {
    if (view is WebView) return view
    if (view is android.view.ViewGroup) {
        for (i in 0 until view.childCount) {
            val child = view.getChildAt(i)
            val found = findWebViewInView(child)
            if (found != null) return found
        }
    }
    return null
}

@Composable
fun LoadingScreen(modifier: Modifier = Modifier) {

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF1a1a2e))
            .padding(16.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Show the brand image centered (same asset used by splash). Replace with your drawable
        // file `brand_logo` (we created a `brand_logo.xml` that points to the mipmap foreground).
        Image(
            painter = painterResource(id = R.drawable.brand_logo),
            contentDescription = "Brand logo",
            modifier = Modifier.fillMaxWidth(0.6f),
            contentScale = ContentScale.Fit
        )

        // Optional: keep a spinner to indicate loading
        CircularProgressIndicator(
            color = Color(0xFFFFD700),
            modifier = Modifier.padding(top = 24.dp)
        )


        // Removed the textual PERIYANAYAKI/ASTRO SOLUTION to avoid duplicating brand messaging.
    }
}

@Preview(showBackground = true)
@Composable
fun LoadingScreenPreview() {
    PASTheme {
        LoadingScreen()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppTopBar(
    brandRes: Int,
    deviceId: String?,
    currentUrl: String?,
    onOpenInBrowser: (String) -> Unit,
    onResetDeviceId: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val showMenu = remember { mutableStateOf(false) }

    TopAppBar(
        title = { Text(text = stringResource(id = brandRes), color = colorResource(id = R.color.golden_yellow)) },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = colorResource(id = R.color.royal_blue_100),
            titleContentColor = colorResource(id = R.color.golden_yellow),
            actionIconContentColor = colorResource(id = R.color.golden_yellow)
        ),
        modifier = modifier,
        actions = {
            if (currentUrl != null) {
                IconButton(onClick = { onOpenInBrowser(currentUrl) }) {
                    Icon(Icons.Filled.Share, contentDescription = "Open in browser", tint = colorResource(id = R.color.golden_yellow))
                }
            }

            IconButton(onClick = { showMenu.value = true }) {
                Icon(Icons.Default.MoreVert, contentDescription = "More options", tint = colorResource(id = R.color.golden_yellow))
            }

            DropdownMenu(
                expanded = showMenu.value,
                onDismissRequest = { showMenu.value = false }
            ) {
                DropdownMenuItem(
                    text = { Text(text = "Device ID: ${deviceId ?: "unknown"}") },
                    onClick = {
                        val clipboardManager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        val clipData = ClipData.newPlainText("deviceId", deviceId ?: "")
                        clipboardManager.setPrimaryClip(clipData)
                        Toast.makeText(context, "Device ID copied to clipboard", Toast.LENGTH_SHORT).show()
                        showMenu.value = false
                    }
                )

                // New menu item to reset Device ID
                DropdownMenuItem(
                    text = { Text(text = "Reset Device ID") },
                    onClick = {
                        onResetDeviceId()
                        showMenu.value = false
                    }
                )
            }
        }
    )
}