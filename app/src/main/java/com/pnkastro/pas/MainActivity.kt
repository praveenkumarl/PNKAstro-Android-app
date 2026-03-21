package com.pnkastro.pas

import android.content.Context
import android.os.Bundle
import android.widget.Toast
import android.util.Log
import android.Manifest
import android.content.pm.PackageManager
import android.webkit.*
import android.net.Uri
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.view.Menu
import android.view.MenuItem
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import com.pnkastro.pas.ui.theme.PASTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.runtime.LaunchedEffect
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import android.provider.Settings
import android.content.Intent
import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.core.content.FileProvider
import okhttp3.OkHttpClient
import okhttp3.Request
import android.media.MediaDrm
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.UUID

class MainActivity : ComponentActivity() {
    private val TAG = "PAS_AUTH"
    private val PREFS_NAME = "pas_prefs"
    private val PREF_STOP_SENDING_IMEI = "stop_sending_imei"

    // Request code used for startActivityForResult when launching TrialWebViewActivity
    private val TRIAL_REQUEST_CODE = 1001

    // Centralized launcher for the trial flow so both options menu and Compose UI can invoke it
    private fun launchTrialFlow() {
        // Use the platform source Android ID (preferred) for the imei/key parameter
        val deviceKey = getSourceAndroidId()
        val deviceModel = try { URLEncoder.encode(android.os.Build.MODEL ?: "", java.nio.charset.StandardCharsets.UTF_8.toString()) } catch (e: Exception) { android.os.Build.MODEL ?: "" }

        val trialBase = try {
            if (BuildConfig.TRIAL_URL.isNotBlank()) BuildConfig.TRIAL_URL else "https://pkastro.com/new_registration_mobile_trial.php"
        } catch (e: Exception) {
            "https://pkastro.com/new_registration_mobile_trial.php"
        }

        // Decide whether to keep sending old imei/key based on remote-controlled pref
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val stopSendingImeis = prefs.getBoolean(PREF_STOP_SENDING_IMEI, false)

        // Build the trial URL safely and append android_hash; continue including imei until cutover
        val uriBuilder = Uri.parse(trialBase).buildUpon()
        if (!stopSendingImeis && deviceKey.isNotBlank()) {
            uriBuilder.appendQueryParameter("imei", deviceKey)
        }
        if (deviceModel.isNotBlank()) uriBuilder.appendQueryParameter("device", deviceModel)

        val androidHash = getAndroidHash48()
        if (androidHash.isNotBlank()) uriBuilder.appendQueryParameter("android_hash", androidHash)

        val url = uriBuilder.build().toString()

        Log.d(TAG, "Launching Trial Flow with URL: ${maskSensitiveQuery(url)}")

        try {
            // Use TrialWebViewActivity to handle the trial registration and its JSON response
            val intent = android.content.Intent(this, TrialWebViewActivity::class.java).apply {
                putExtra("url", url)
            }
            startActivityForResult(intent, TRIAL_REQUEST_CODE)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch TrialWebViewActivity: ${e.message}")
            Toast.makeText(this, "Unable to open trial view", Toast.LENGTH_SHORT).show()
        }
    }

    // expose deviceId as a property so composables can read it
    // Use a Compose MutableState so UI recomposes when the ID changes
    private val deviceIdValue = mutableStateOf<String?>(null)

    // duration to show the launcher splash (milliseconds). Change this value to adjust splash time.
    private val splashDurationMs = 1500L

    // Initialize permission launcher
    private lateinit var permissionLauncher: ActivityResultLauncher<Array<String>>

    // Flag to track location permission status for Compose to react
    private val hasLocationPermission = mutableStateOf(false)

    // Centralized state for the final URL and allowed host
    private var webUrlState = mutableStateOf<String?>(null)
    private var allowedHostState = mutableStateOf<String?>(null)

    private fun getMediaDrmId(): String {
        return try {
            val widevineUuid = UUID(-0x121074568629b532L, -0x3566d56ef403bdcfL)
            val mediaDrm = MediaDrm(widevineUuid)
            val deviceUniqueId = mediaDrm.getPropertyByteArray(MediaDrm.PROPERTY_DEVICE_UNIQUE_ID)
            mediaDrm.release()

            // Convert byte array to a hex string or base64
            deviceUniqueId.joinToString("") { "%02x".format(it) }
        } catch (e: Exception) {
            Log.e("PAS_AUTH", "MediaDrm ID failed: ${e.message}")
            ""
        }
    }

    private fun getRawAndroidId(): String {
        // This previously returned a SHA-256 of the raw id; keep it for backward compatibility only.
        // Use getSourceAndroidId() for the actual raw device identifier when sending 'key'.
        return try {
            // Try MediaDrm ID first as it's more stable across uninstalls
            val drmId = getMediaDrmId()
            val rawId = if (drmId.isNotEmpty()) drmId else {
                try {
                    Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID) ?: ""
                } catch (e: Exception) {
                    ""
                }
            }

            // Return SHA-256(hex) of the raw id (legacy hashed form)
            if (rawId.isNotEmpty()) {
                try {
                    val digest = MessageDigest.getInstance("SHA-256")
                    val hash = digest.digest(rawId.toByteArray(StandardCharsets.UTF_8))
                    hash.joinToString("") { "%02x".format(it) }
                } catch (e: Exception) {
                    rawId
                }
            } else {
                ""
            }
        } catch (e: Exception) {
            ""
        }
    }

    // New helper to compute the 48-char android_hash required by backend. We derive it from the source ID
    // (MediaDrm id or ANDROID_ID) by computing SHA-256 and NOT truncating or padding to 48.
    // The server expects the full SHA-256 (64 hex chars).
    private fun getAndroidHash48(): String {
        val source = getSourceAndroidId()
        if (source.isEmpty()) return ""
        return try {
            val digest = MessageDigest.getInstance("SHA-256")
            val hash = digest.digest(source.toByteArray(StandardCharsets.UTF_8))
            hash.joinToString("") { "%02x".format(it) }
        } catch (e: Exception) {
            ""
        }
    }

    // helper to NOT mask sensitive query params from logs anymore as requested by user
    private fun maskSensitiveQuery(url: String?): String {
        return url ?: ""
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == TRIAL_REQUEST_CODE && resultCode == Activity.RESULT_OK) {
            val activated = data?.getBooleanExtra("activated", false) ?: false
            val expiry = data?.getStringExtra("expiry") ?: ""
            val redirectUrl = data?.getStringExtra("redirect_url")

            if (activated) {
                // Save expiry and android id in SharedPreferences as required
                val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                // Persist the platform source Android ID (plain ANDROID_ID) for trial records
                val currentDeviceId = getSourceAndroidId()
                prefs.edit().apply {
                    putString("trial_expiry", expiry)
                    putString("trial_imei", currentDeviceId)
                    putBoolean("is_trial_active", true)
                    apply()
                }

                Log.d(TAG, "Trial activated, expiry available. Redirecting...")

                // Navigate to app home/index activity by refreshing the auth flow
                // or loading the redirect_url if provided
                val baseUrl = try {
                    if (BuildConfig.SITE_URL.isNotBlank()) BuildConfig.SITE_URL else "https://pkastro.com/index.php"
                } catch (e: Exception) {
                    "https://pkastro.com/index.php"
                }

                // If the server provided a relative redirect_url like /index.php, append it to base
                val finalUrl = if (redirectUrl != null && !redirectUrl.isNullOrEmpty() && redirectUrl.startsWith("/")) {
                    val uri = Uri.parse(baseUrl)
                    "${uri.scheme}://${uri.host}$redirectUrl"
                } else {
                    baseUrl
                }

                // Update webUrlState to trigger WebView reload in MainActivity
                webUrlState.value = null // Force reload
                authenticateAndGetUrl(deviceIdValue.value ?: "") { newUrl ->
                    webUrlState.value = newUrl ?: finalUrl
                }
            }
        }
    }

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

        // Use ANDROID_ID as the stable, unique device id. Persist it in prefs for consistency.
        run {
            val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            // Store the raw source Android ID (MediaDrm id if available, otherwise Settings.Secure.ANDROID_ID)
            val sourceId = getSourceAndroidId()

            // Update state immediately with the latest platform ID
            deviceIdValue.value = sourceId.ifEmpty {
                prefs.getString("app_device_id", null) ?: java.util.UUID.randomUUID().toString()
            }

            // Persist the resulting ID
            prefs.edit().putString("app_device_id", deviceIdValue.value).apply()

            Log.d(TAG, "Using Device ID configured: ${deviceIdValue.value}")
        }

        // Initialize state to hold final URL and allowed host
        // These are now class members so they can be updated by launchTrialFlow
        webUrlState = mutableStateOf<String?>(null)
        allowedHostState = mutableStateOf<String?>(null)

        // Enable WebView remote debugging for development
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.KITKAT) {
            WebView.setWebContentsDebuggingEnabled(true)
        }

        // This is necessary to capture content outside the visible area (full page)
        android.webkit.WebView.enableSlowWholeDocumentDraw()

        // Set the Compose UI immediately
        setContent {
            PASTheme {
                val urlToLoad = webUrlState.value
                val allowedHost = allowedHostState.value
                val permissionGranted = hasLocationPermission.value

                // Hoisted dialog state so the dialog renders at the top level and is visible
                val showPhoneDialog = remember { mutableStateOf(false) }
                val showAboutDialog = remember { mutableStateOf(false) }
                val coroutineScope = rememberCoroutineScope()
                // Capture the Compose context once so non-composable lambdas can use it safely
                val composeContext = LocalContext.current
                val rootView = LocalView.current

                Scaffold(
                    topBar = {
                        AppTopBar(
                            brandRes = R.string.app_name,
                            deviceId = deviceIdValue.value,
                            currentUrl = urlToLoad,
                            onOpenInBrowser = { url ->
                                openUrlInCustomTab(url)
                            },
                            onTryRequested = {
                                // Invoke trial flow which now uses TrialWebViewActivity for handling JSON
                                launchTrialFlow()
                            },
                            onRegisterRequested = {
                                // Log and toast immediately so we can confirm the click reached the activity
                                Log.d(TAG, "onRegisterRequested invoked from AppTopBar")
                                Toast.makeText(composeContext, "Register requested", Toast.LENGTH_SHORT).show()
                                showPhoneDialog.value = true
                            },
                            onAboutRequested = {
                                showAboutDialog.value = true
                            },
                            onShareRequested = {
                                coroutineScope.launch {
                                    try {
                                        val webView = findWebViewInView(rootView)
                                        if (webView != null) {
                                            // Get the scale to convert density-independent pixels to physical pixels
                                            val scale = webView.scale
                                            val width = webView.width
                                            // Use contentHeight (which is in DP) converted to pixels
                                            val height = (webView.contentHeight * scale).toInt()

                                            // Limit snapshot height to prevent OOM errors with very long pages
                                            val snapshotHeight = if (height > 0) Math.min(height, 8000) else webView.height

                                            if (width > 0 && snapshotHeight > 0) {
                                                val bitmap = Bitmap.createBitmap(width, snapshotHeight, Bitmap.Config.ARGB_8888)
                                                val canvas = Canvas(bitmap)

                                                // Support full-page drawing
                                                webView.draw(canvas)

                                                val cachePath = File(composeContext.cacheDir, "images")
                                                cachePath.mkdirs()
                                                val file = File(cachePath, "share_snapshot.png")
                                                FileOutputStream(file).use { stream ->
                                                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
                                                }

                                                val contentUri = FileProvider.getUriForFile(composeContext, "${composeContext.packageName}.fileprovider", file)

                                                if (contentUri != null) {
                                                    val shareIntent = Intent().apply {
                                                        action = Intent.ACTION_SEND
                                                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                                        // Only share the image, do not include EXTRA_TEXT with the URL
                                                        setDataAndType(contentUri, "image/png")
                                                        putExtra(Intent.EXTRA_STREAM, contentUri)
                                                    }
                                                    composeContext.startActivity(Intent.createChooser(shareIntent, "Share Snapshot"))
                                                }
                                            } else {
                                                Toast.makeText(composeContext, "View not ready for snapshot", Toast.LENGTH_SHORT).show()
                                            }
                                        } else {
                                            // Fallback to text share if webview not found
                                            val sendIntent = Intent().apply {
                                                action = Intent.ACTION_SEND
                                                putExtra(Intent.EXTRA_TEXT, webUrlState.value ?: "")
                                                type = "text/plain"
                                            }
                                            composeContext.startActivity(Intent.createChooser(sendIntent, "Share via"))
                                        }
                                    } catch (e: Exception) {
                                        Log.e(TAG, "Sharing snapshot failed: ${e.message}")
                                        Toast.makeText(composeContext, "Unable to share snapshot", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            }
                        )
                    },
                    modifier = Modifier.fillMaxSize(),
                    contentWindowInsets = WindowInsets(0, 0, 0, 0)
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

                // Render the phone dialog at top-level so it's definitely visible
                 if (showPhoneDialog.value) {
                     PhoneInputDialog(
                         initialPhone = "",
                         onCancel = { showPhoneDialog.value = false },
                         onSubmit = { phone ->
                             showPhoneDialog.value = false
                             coroutineScope.launch {
                                 val result = withContext(Dispatchers.IO) {
                                     try {
                                         sendKeyChange(phone, getSourceAndroidId())
                                     } catch (e: Exception) {
                                         Log.e("MainActivity", "sendKeyChange error: ${e.message}")
                                         Pair(false, "ERROR: ${e.message}")
                                     }
                                 }
                                 val (success, message) = result
                                 val userMessage = if (message.isNotBlank()) message else if (success) "Registration request sent" else "Request failed"
                                 Toast.makeText(composeContext, userMessage, Toast.LENGTH_LONG).show()
                             }
                         }
                     )
                 }

                 if (showAboutDialog.value) {
                     AboutAppDialog(
                         deviceId = deviceIdValue.value ?: "unknown",
                         onDismiss = { showAboutDialog.value = false }
                     )
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
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        // Remove stored id
        prefs.edit().remove("app_device_id").apply()
        val source = getSourceAndroidId()
        val newId = if (source.isNotEmpty()) source else java.util.UUID.randomUUID().toString()
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
                    appendPasswordParam(baseUrl, loc.latitude, loc.longitude, null, getAndroidHash48())
                } else {
                    baseUrl
                }
                Log.d(TAG, "Opening external URL in Custom Tab: ${maskSensitiveQuery(finalUrl)}")
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
        // Prefer BuildConfig values injected via product flavors; fall back to string resources
        val authBase = if (try { BuildConfig.AUTH_URL.isNotBlank() } catch (e: Exception) { false }) {
            BuildConfig.AUTH_URL
        } else {
            getString(R.string.auth_url)
        }

        val siteBase = if (try { BuildConfig.SITE_URL.isNotBlank() } catch (e: Exception) { false }) {
            BuildConfig.SITE_URL
        } else {
            getString(R.string.site_url)
        }

        // Normalize any http:// BuildConfig/string URLs to https:// at runtime to enforce HTTPS-only policy.
        // This protects against stale BuildConfig values when a rebuild hasn't been performed yet.
        val authBaseNormalized = try {
            if (authBase.startsWith("http://", ignoreCase = true)) {
                // prefer the HTTPS string resource which has been updated
                getString(R.string.auth_url)
            } else authBase
        } catch (e: Exception) { authBase }

        val siteBaseNormalized = try {
            if (siteBase.startsWith("http://", ignoreCase = true)) {
                // prefer the HTTPS string resource which has been updated
                getString(R.string.site_url)
            } else siteBase
        } catch (e: Exception) { siteBase }

        // Force-convert any lingering http:// to https:// to guarantee HTTPS at runtime
        val authBaseFinal = try { authBaseNormalized.replaceFirst("http://", "https://", ignoreCase = true) } catch (e: Exception) { authBaseNormalized }
        val siteBaseFinal = try { siteBaseNormalized.replaceFirst("http://", "https://", ignoreCase = true) } catch (e: Exception) { siteBaseNormalized }

        // Decide whether to keep sending old imei/key based on remote-controlled pref
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val stopSendingImeis = prefs.getBoolean(PREF_STOP_SENDING_IMEI, false)

        // Append key and android_hash param to auth URL safely (respect existing query params) and URL-encode values
        val androidHash = getAndroidHash48()
        // Use the unhashed source id (MediaDrm id or ANDROID_ID) as the `key` parameter expected by the server
        // Ensure we use the actual current platform ID (sourceId) rather than the method parameter if it's inconsistent
        val sourceId = getSourceAndroidId()

        Log.d(TAG, "DEBUG: sourceId='$sourceId', androidHash='$androidHash'")

        val authUrl = try {
            // Do not pre-encode the deviceId here - Uri.Builder.appendQueryParameter will encode it.
            val uriBuilder = Uri.parse(authBaseFinal).buildUpon()
            if (!stopSendingImeis && sourceId.isNotBlank()) {
                uriBuilder.appendQueryParameter("key", sourceId)
            }
            if (androidHash.isNotBlank()) {
                uriBuilder.appendQueryParameter("android_hash", androidHash)
            }
            var built = uriBuilder.build().toString()
            // Ensure scheme is HTTPS
            if (built.startsWith("http://", ignoreCase = true)) built = built.replaceFirst("http://", "https://")
            built
        } catch (e: Exception) {
            // fallback: naive append but still prefer HTTPS; ensure we URL-encode values here
            var builder = authBaseNormalized
             if (!stopSendingImeis && sourceId.isNotBlank()) {
                 val encodedKey = try { java.net.URLEncoder.encode(sourceId, java.nio.charset.StandardCharsets.UTF_8.toString()) } catch (ex: Exception) { sourceId }
                 builder = if (builder.contains("?")) "$builder&key=$encodedKey" else "$builder?key=$encodedKey"
             }
             if (androidHash.isNotBlank()) {
                 val safeHash = try { java.net.URLEncoder.encode(androidHash, java.nio.charset.StandardCharsets.UTF_8.toString()) } catch (ex: Exception) { androidHash }
                 builder = if (builder.contains("?")) "$builder&android_hash=$safeHash" else "$builder?android_hash=$safeHash"
             }
             if (builder.startsWith("http://", ignoreCase = true)) builder = builder.replaceFirst("http://", "https://")
              builder
         }

        Log.d(TAG, "Starting authentication. originalBuildConfigBase=${maskSensitiveQuery(authBase)}, normalizedBase=${maskSensitiveQuery(authBaseFinal)}, authUrl=${maskSensitiveQuery(authUrl)}")
        Log.d(TAG, "Site base: original=${maskSensitiveQuery(siteBase)}, normalized=${maskSensitiveQuery(siteBaseFinal)}")

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
                            // Ensure we only fetch HTTPS; if URL is HTTP try to rewrite to HTTPS
                            if (!url.protocol.equals("https", ignoreCase = true)) {
                                val rewritten = url.toString().replaceFirst("http://", "https://", ignoreCase = true)
                                Log.w(TAG, "Rewriting non-HTTPS URL to: ${maskSensitiveQuery(rewritten)}")
                                urlToFetch = rewritten
                            }

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
                            Log.d(TAG, "Response code: $responseCode from URL: ${maskSensitiveQuery(urlToFetch)}")

                            // Extract Set-Cookie headers from response
                            val setCookieHeaders = conn.getHeaderFields()["Set-Cookie"]
                            if (setCookieHeaders != null && setCookieHeaders.isNotEmpty()) {
                                Log.d(TAG, "Received ${setCookieHeaders.size} Set-Cookie header(s)")
                                for (cookieHeader in setCookieHeaders) {
                                    Log.d(TAG, "Set-Cookie header received")
                                    // Extract just the cookie name=value part (before semicolon)
                                    val cookiePart = cookieHeader.split(";")[0].trim()
                                    if (authCookies.isEmpty()) {
                                        authCookies = cookiePart
                                    } else {
                                        authCookies += "; $cookiePart"
                                    }
                                }
                                Log.d(TAG, "Accumulated cookies for WebView injection")
                            }

                            // Check if it's a redirect
                            if (responseCode in 301..302) {
                                val location = conn.getHeaderField("Location")
                                Log.d(TAG, "Redirect to: ${maskSensitiveQuery(location)}")
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
                                // Do not log full auth response body as it may contain sensitive tokens/ids

                                // Check for automatic trial registration trigger from auth endpoint
                                if (responseBody.contains("\"action\":\"register\"") && responseBody.contains("\"imei\":")) {
                                    Log.d(TAG, "Server requested trial registration via JSON response. Switching to trial flow.")
                                    conn.disconnect()
                                    withContext(Dispatchers.Main) {
                                        Toast.makeText(this@MainActivity, "Redirecting to trial registration...", Toast.LENGTH_SHORT).show()
                                        launchTrialFlow()
                                    }
                                    return@withContext null // Stop normal flow as we're opening the Trial Activity
                                }

                                // Detect a server-side signal to stop sending legacy IMEI/key
                                if (responseBody.contains("android_hash_cutover", ignoreCase = true)
                                    || responseBody.contains("stop_sending_imei", ignoreCase = true)
                                    || responseBody.contains("stop_sending_key", ignoreCase = true)) {
                                    try {
                                        prefs.edit().putBoolean(PREF_STOP_SENDING_IMEI, true).apply()
                                        Log.d(TAG, "Server signalled android_hash cutover; will stop sending legacy IMEI/key going forward")
                                    } catch (e: Exception) {
                                        // ignore
                                    }
                                }

                                // New: detect migration confirmation from server and proceed to logged-in flow
                                // Example JSON: { "migrated": true, "message": "migration completed" }
                                if (responseBody.contains("\"migrated\":true") || responseBody.contains("\"migrated\": true")) {
                                    Log.d(TAG, "Server indicates device migration (migrated=true). Skipping trial registration and continuing logged-in flow.")
                                    try {
                                        // The server migration implies device mapping is done; stop sending legacy IMEI/key going forward
                                        prefs.edit().putBoolean(PREF_STOP_SENDING_IMEI, true).apply()
                                    } catch (e: Exception) {
                                        // ignore
                                    }
                                    // Do NOT launch trial flow; continue processing the successful auth response below
                                }

                                conn.disconnect()

                                // Store cookies in WebView's CookieManager so they persist
                                try {
                                    if (authCookies.isNotEmpty()) {
                                        val cookieManager = android.webkit.CookieManager.getInstance()
                                        // Ensure CookieManager is enabled
                                        cookieManager.setAcceptCookie(true)

                                        // Parse and inject each cookie into the CookieManager
                                        authCookies.split("; ").forEach { cookie ->
                                            if (cookie.isNotEmpty()) {
                                                cookieManager.setCookie(siteBaseNormalized, cookie)
                                                Log.d(TAG, "Injected cookie into WebView (masked)")
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

                                Log.d(TAG, "Location retrieved after $retryCount retries")

                                val finalUrl = if (loc != null) {
                                    // include device key param when loading index.php post-auth
                                    appendPasswordParam(siteBaseFinal, loc.latitude, loc.longitude, if (stopSendingImeis) null else sourceId, androidHash)
                                } else {
                                    // If still no location, add ?password=noloc parameter and append key
                                    Log.w(TAG, "No location available after retries")
                                    val base = if (siteBaseFinal.contains("?")) {
                                        "$siteBaseFinal&password=noloc"
                                    } else {
                                        "$siteBaseFinal?password=noloc"
                                    }
                                    // Append key param after cleaning existing key if any
                                    val encodedKey = try { URLEncoder.encode(sourceId, StandardCharsets.UTF_8.toString()) } catch (e: Exception) { sourceId }
                                    var result = if (base.contains("?")) "$base&key=$encodedKey" else "$base?key=$encodedKey"
                                    if (androidHash.isNotBlank()) result += "&android_hash=$androidHash"
                                    return@withContext result
                                }
                                 Log.d(TAG, "Authentication successful, final URL to load: ${maskSensitiveQuery(finalUrl)}")
                                 return@withContext finalUrl
                            } else {
                                // Other response codes are failures
                                Log.d(TAG, "Unexpected response code: $responseCode")
                                conn.disconnect()
                                return@withContext null
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Error fetching URL ${maskSensitiveQuery(urlToFetch)}: ${e.message}", e)
                            // Do not attempt HTTP fallback - enforce HTTPS-only policy
                            return@withContext null
                        }
                    }
                    null
                } catch (e: Exception) {
                    Log.e(TAG, "Authentication error: ${e.message}", e)
                    null
                }
            }

            if (resultUrl != null) {
                Log.d(TAG, "Authentication successful, will open in-app: ${maskSensitiveQuery(resultUrl)}")
                onResultUrl(resultUrl)
            } else {
                // If resultUrl is null, it might be because we intercepted a trial registration
                // We only want to show the failure toast if it wasn't a redirected trial flow
                Log.d(TAG, "Authentication finished - no URL returned (check if trial flow was launched)")
                onResultUrl(null)
            }
        }
    }

    private fun getSourceIdForMigration(): String {
        return getSourceAndroidId()
    }

    private fun getSourceAndroidId(): String {
        // Prefer the platform ANDROID_ID (stable 16-hex string on most devices) for the `key` parameter.
        // Fall back to MediaDrm unique id only if ANDROID_ID is not available.
        return try {
            val androidId = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID) ?: ""
            if (androidId.isNotEmpty()) {
                androidId
            } else {
                try {
                    getMediaDrmId()
                } catch (e: Exception) {
                    ""
                }
            }
        } catch (e: Exception) {
            ""
        }
    }

}

@Composable
fun AboutAppDialog(
    deviceId: String,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val versionName = remember {
        try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        } catch (e: Exception) {
            "Unknown"
        }
    }

    // State to control loading and error
    val isLoading = remember { mutableStateOf(true) }
    val htmlContent = remember { mutableStateOf<String?>(null) }
    val errorMessage = remember { mutableStateOf<String?>(null) }
    val aboutUrlState = remember { mutableStateOf<String?>(null) }

    // Compute About URL and fetch HTML using the centralized builder
    LaunchedEffect(Unit) {
        isLoading.value = true
        errorMessage.value = null

        // Compute the android_hash as the SHA-256 hex of the deviceId (full 64 hex chars). Avoid zero-padding.
        val androidHash = try {
            val base = deviceId ?: ""
            if (base.isBlank()) "" else sha256Hex(base)
        } catch (e: Exception) { "" }

        val aboutUrl = buildAboutUrl(context).let { url ->
            val uri = Uri.parse(url).buildUpon()
            if (!deviceId.isNullOrBlank()) {
                uri.appendQueryParameter("imei", deviceId)
            }
            if (androidHash.isNotBlank()) {
                uri.appendQueryParameter("android_hash", androidHash)
            }
            uri.build().toString()
        }
        aboutUrlState.value = aboutUrl

        withContext(Dispatchers.IO) {
            try {
                val client = OkHttpClient()
                val request = Request.Builder().url(aboutUrl).get().build()

                client.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        val body = response.body?.string()
                        if (!body.isNullOrEmpty()) {
                            htmlContent.value = body
                        } else {
                            errorMessage.value = "Empty response from server"
                        }
                    } else {
                        errorMessage.value = "Error ${response.code}: ${response.message}"
                    }
                }
            } catch (e: Exception) {
                errorMessage.value = "Exception: ${e.message}"
            }
        }

        isLoading.value = false
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
        title = { Text(text = stringResource(id = R.string.about)) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 0.dp)
            ) {
                // Key / Device ID at the top
                Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                    Text(
                        text = stringResource(id = R.string.device_id_label),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = deviceId,
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.Gray,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(
                            onClick = {
                                val clipboardManager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                val clipData = ClipData.newPlainText("deviceId", deviceId)
                                clipboardManager.setPrimaryClip(clipData)
                                Toast.makeText(context, "Device ID copied", Toast.LENGTH_SHORT).show()
                            }
                        ) {
                            Icon(
                                imageVector = Icons.Default.Share,
                                contentDescription = "Copy ID",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }

                    // Show Android hash (64-char) derived from deviceId for migration support
                    // We now use the full SHA-256 hash to avoid incorrect padding with '0's.
                    val androidHash = remember {
                        try {
                            val source = deviceId ?: ""
                            if (source.isEmpty()) "" else {
                                val digest = java.security.MessageDigest.getInstance("SHA-256")
                                val hashBytes = digest.digest(source.toByteArray(java.nio.charset.StandardCharsets.UTF_8))
                                hashBytes.joinToString("") { "%02x".format(it) }
                            }
                        } catch (e: Exception) { "" }
                    }

                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Android hash:",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = androidHash,
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.Gray,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(onClick = {
                            try {
                                val clipboardManager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                val clipData = ClipData.newPlainText("android_hash", androidHash)
                                clipboardManager.setPrimaryClip(clipData)
                                Toast.makeText(context, "Android hash copied", Toast.LENGTH_SHORT).show()
                            } catch (e: Exception) {
                                Toast.makeText(context, "Copy failed", Toast.LENGTH_SHORT).show()
                            }
                        }) {
                            Icon(imageVector = Icons.Default.Share, contentDescription = "Copy hash", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                        }
                    }

                     Text(text = "Version: $versionName", style = MaterialTheme.typography.bodyMedium)

                    Spacer(modifier = Modifier.padding(8.dp))
                    HorizontalDivider()
                    Spacer(modifier = Modifier.padding(8.dp))
                }

                // Show loading indicator or error message
                if (isLoading.value) {
                    CircularProgressIndicator(modifier = Modifier.align(Alignment.CenterHorizontally))
                } else if (errorMessage.value != null) {
                    Text(
                        text = "Error: ${errorMessage.value}",
                        color = Color.Red,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(16.dp)
                    )
                } else if (htmlContent.value != null) {
                    // Show the fetched HTML content in a WebView
                    val aboutBase = aboutUrlState.value ?: ""
                    AndroidView(
                        factory = { ctx ->
                            WebView(ctx).apply {
                                settings.apply {
                                    javaScriptEnabled = true
                                    domStorageEnabled = true
                                    loadWithOverviewMode = true
                                    useWideViewPort = true
                                    setSupportZoom(true)
                                    builtInZoomControls = true
                                    displayZoomControls = false
                                }
                                // Webview will handle its own scrolling
                                isNestedScrollingEnabled = true
                            }
                        },
                        update = { web ->
                            try {
                                web.loadDataWithBaseURL(aboutBase, htmlContent.value ?: "", "text/html", "utf-8", null)
                            } catch (e: Exception) {
                                Log.w("AboutAppDialog", "Failed to load HTML into WebView: ${e.message}")
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f, fill = false)
                            .heightIn(min = 400.dp, max = 600.dp)
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("OK") }
        }
    )
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

                        // Intercept if it's our allowed host - always append fresh location for new navigations
                        if (allowedHost == null || host == allowedHost) {
                            val activityForNav = (ctx as? ComponentActivity)
                            activityForNav?.lifecycleScope?.launch {
                                // Use centralized helper to build URL with fresh location and key replacement
                                val finalUrl = buildUrlWithFreshLocation(ctx, urlString, deviceId)
                                Log.d("WebView", "Loading URL with location via override: $finalUrl")
                                view?.loadUrl(finalUrl)
                            }
                            return true
                        }
                        return false // Let WebView handle navigation for other hosts
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
                                                                Log.i("WebView", "Body snippet for $url => ${snippet.replace(Regex("[\\r\\n]+"), " ")} ")

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
                                Log.i("WebView", "Body snippet for $url => ${snip.replace(Regex("[\\r\\n]+"), " ")}")
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
                    val finalUrl = buildUrlWithFreshLocation(ctx, url, deviceId)

                    // remember whether initial load was done without a real location (used to trigger reload when permissions granted)
                    wasNoPerm.value = finalUrl.contains("noperm") || finalUrl.contains("noperm")

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
                    val finalUrl = buildUrlWithFreshLocation(context, url, deviceId)
                    if (!finalUrl.isNullOrEmpty()) {
                        Log.d("WebView", "Permission granted! Reloading with real location: $finalUrl")
                        webview.loadUrl(finalUrl)
                    }
                }
            } else if (lastUrl.value != url) {
                val activityForUpdate = (context as? ComponentActivity)
                activityForUpdate?.lifecycleScope?.launch {
                    val finalUrl = buildUrlWithFreshLocation(context, url, deviceId)
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
                    Button(onClick = {
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
    onRegisterRequested: () -> Unit,
    onTryRequested: () -> Unit,
    onAboutRequested: () -> Unit,
    onShareRequested: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val showMenu = remember { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()

    TopAppBar(
        title = { Text(text = "${stringResource(id = brandRes)} (${BuildConfig.ENV_NAME})", color = colorResource(id = R.color.golden_yellow)) },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = colorResource(id = R.color.royal_blue_100),
            titleContentColor = colorResource(id = R.color.golden_yellow),
            actionIconContentColor = colorResource(id = R.color.golden_yellow)
        ),
        modifier = modifier,
        actions = {
            if (currentUrl != null) {
                IconButton(onClick = { onShareRequested() }) {
                    Icon(Icons.Filled.Share, contentDescription = "Share snapshot", tint = colorResource(id = R.color.golden_yellow))
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
                    text = { Text(text = stringResource(id = R.string.share)) },
                    onClick = {
                        coroutineScope.launch {
                            showMenu.value = false
                            kotlinx.coroutines.delay(120)
                            onShareRequested()
                        }
                    }
                )

                DropdownMenuItem(
                    text = { Text(text = stringResource(id = R.string.about)) },
                    onClick = {
                        coroutineScope.launch {
                            showMenu.value = false
                            kotlinx.coroutines.delay(120)
                            onAboutRequested()
                        }
                    }
                )
            }
        }
    )
}

@Composable
fun PhoneInputDialog(
    initialPhone: String,
    onCancel: () -> Unit,
    onSubmit: (String) -> Unit
) {
    var phone by remember { mutableStateOf(initialPhone) }

    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(text = "Enter phone number") },
        text = {
            OutlinedTextField(
                value = phone,
                onValueChange = { phone = it },
                label = { Text("Phone") },
                singleLine = true
            )
        },
        confirmButton = {
            TextButton(onClick = { onSubmit(phone) }) { Text("Submit") }
        },
        dismissButton = {
            TextButton(onClick = onCancel) { Text("Cancel") }
        }
    )
}

suspend fun sendKeyChange(phone: String, key: String): Pair<Boolean, String> {
    return try {
        val site = if (try { BuildConfig.SITE_URL.isNotBlank() } catch (e: Exception) { false }) BuildConfig.SITE_URL else "http://pkastro.com/preprod/index.php"
        val base = if (site.contains("index.php")) site.replace("index.php", "key_change.php") else try {
            val u = URL(site)
            val root = "${u.protocol}://${u.host}${if (u.port != -1) ":${u.port}" else ""}"
            "$root/key_change.php"
        } catch (e: Exception) {
            "http://pkastro.com/preprod/key_change.php"
        }

        val encodedPhone = URLEncoder.encode(phone, java.nio.charset.StandardCharsets.UTF_8.toString())
        val encodedKey = URLEncoder.encode(key, java.nio.charset.StandardCharsets.UTF_8.toString())
        val url = "$base?phone=$encodedPhone&key=$encodedKey"

        val client = OkHttpClient()
        val request = Request.Builder().url(url).get().build()

        // Execute and map the response to the exact messages required by the user.
        val resultPair: Pair<Boolean, String> = client.newCall(request).execute().use { resp ->
            val code = resp.code
            val rawBody = resp.body?.string() ?: ""
            val bodyStr = rawBody.trim()

            when (code) {
                200 -> {
                    val normalized = bodyStr.lowercase()
                    val message = when {
                        "device updated successfully" in normalized -> "Device updated successfully"
                        "device already registered" in normalized -> "Device already registered"
                        "request submitted" in normalized -> "Request submitted"
                        bodyStr.isNotBlank() -> bodyStr
                        else -> "Request submitted"
                    }
                    Pair(true, message)
                }
                400 -> {
                    val message = if (bodyStr.isNotBlank()) bodyStr else "Missing phone or key"
                    Pair(false, message)
                }
                409 -> {
                    val message = if (bodyStr.isNotBlank()) bodyStr else "Request already pending for this phone"
                    Pair(false, message)
                }
                500 -> {
                    val message = if (bodyStr.isNotBlank()) "ERROR: $bodyStr" else "ERROR: Internal Server Error"
                    Pair(false, message)
                }
                else -> {
                    val message = if (bodyStr.isNotBlank()) "ERROR: HTTP $code: $bodyStr" else "ERROR: HTTP $code"
                    Pair(false, message)
                }
            }
        }

        resultPair
    } catch (e: Exception) {
        Log.e("sendKeyChange", "Error: ${e.message}")
        Pair(false, "ERROR: ${e.message}")
    }
}

// Helper: build a URL by replacing/adding the `password` param (lat,lon) and `key` when index.php is present.
// This centralizes all URL modifications so every top-level navigation gets a fresh location appended.
private suspend fun buildUrlWithFreshLocation(context: Context, originalUrl: String, deviceId: String?): String {
    return try {
        // Try to get current location on IO thread; getCurrentLocation may block or be suspend depending on implementation
        val loc = withContext(Dispatchers.IO) { getCurrentLocation(context) }

        // Decide password value
        val passwordValue = if (loc != null) {
            "${loc.latitude},${loc.longitude}"
        } else {
            // keep the same fallback token used elsewhere
            "noperm"
        }

        // Use Uri to manipulate query parameters safely
        val uri = Uri.parse(originalUrl)
        val builder = uri.buildUpon()
        builder.clearQuery()

        // Collect existing params into a LinkedHashMap to preserve order (optional)
        val params = mutableMapOf<String, MutableList<String>>()
        try {
            for (name in uri.queryParameterNames) {
                params[name] = uri.getQueryParameters(name).toMutableList()
            }
        } catch (e: Exception) {
            // ignore parsing errors and fall back to naive append below
        }

        // Replace or add password param
        params["password"] = mutableListOf(passwordValue)

        // If URL targets index.php and we have a deviceId, ensure key param is set/replaced
        if (originalUrl.contains("index.php")) {
            // Prefer the platform ANDROID_ID when available (this is the expected 'key' on the server).
            // Fall back to MediaDrm id or random UUID if no stable ID is available.
            val keyToUse = when {
                !deviceId.isNullOrEmpty() -> deviceId
                else -> java.util.UUID.randomUUID().toString() // fallback to random UUID
            }

            params["key"] = mutableListOf(keyToUse)
        }

        // Rebuild query
        for ((k, vlist) in params) {
            for (v in vlist) {
                builder.appendQueryParameter(k, v)
            }
        }

        // Preserve fragment if present
        if (uri.fragment != null) builder.fragment(uri.fragment)

        builder.build().toString()
    } catch (e: Exception) {
        // Fallback: simple append when safe parsing fails
        try {
            val base = if (originalUrl.contains("?")) "$originalUrl&password=noperm" else "$originalUrl?password=noperm"
            if (originalUrl.contains("index.php") && !deviceId.isNullOrEmpty()) {
                try {
                    val encodedKey = URLEncoder.encode(deviceId, StandardCharsets.UTF_8.toString())
                    return "$base&key=$encodedKey"
                } catch (ex: Exception) {
                    return "$base&key=$deviceId"
                }
            }
            base
        } catch (ex: Exception) {
            originalUrl
        }
    }
}

// Build the About URL based on SITE_URL or strings resource; keeps behavior consistent with other helpers.
fun buildAboutUrl(context: Context): String {
    return try {
        val siteBase = if (try { BuildConfig.SITE_URL.isNotBlank() } catch (e: Exception) { false }) {
            BuildConfig.SITE_URL
        } else {
            context.getString(R.string.site_url)
        }
        if (siteBase.contains("index.php")) {
            siteBase.replace("index.php", "AboutApp.php")
        } else if (siteBase.endsWith("/")) {
            // Correctly append AboutApp.php when siteBase already ends with '/'
            "${siteBase}AboutApp.php"
        } else {
            "$siteBase/AboutApp.php"
        }
    } catch (e: Exception) {
        // Fallback to a known endpoint
        "https://pkastro.com/AboutApp.php"
    }
}

// Helper: compute SHA-256 hex of an input string. Used by About dialog and other helpers.
fun sha256Hex(input: String): String {
    return try {
        if (input.isEmpty()) return ""
        val md = java.security.MessageDigest.getInstance("SHA-256")
        val hash = md.digest(input.toByteArray(java.nio.charset.StandardCharsets.UTF_8))
        hash.joinToString("") { "%02x".format(it) }
    } catch (e: Exception) {
        ""
    }
}