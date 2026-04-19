package com.pnkastro.pas

import android.content.Context
import android.os.Bundle
import android.widget.Toast
import android.util.Log
import android.Manifest
import android.content.pm.PackageManager
import android.webkit.*
import android.net.Uri
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.content.ClipData
import android.content.ClipboardManager
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Close
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
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
import androidx.compose.ui.window.Dialog
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
import android.view.WindowManager
import androidx.core.content.FileProvider
import okhttp3.OkHttpClient
import okhttp3.Request
import android.media.MediaDrm
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.UUID
import org.json.JSONObject

class MainActivity : ComponentActivity() {
    private val TAG = "PAS_AUTH"
    private val PREFS_NAME = "pas_prefs"
    private val PREF_STOP_SENDING_IMEI = "stop_sending_imei"

    private lateinit var trialLauncher: ActivityResultLauncher<Intent>
    private val TRIAL_REQUEST_CODE = 1001

    private fun launchTrialFlow() {
        val deviceKey = getSourceAndroidId()
        val deviceModel = try { URLEncoder.encode(android.os.Build.MODEL ?: "", StandardCharsets.UTF_8.toString()) } catch (e: Exception) { android.os.Build.MODEL ?: "" }

        val trialBase = try {
            if (BuildConfig.TRIAL_URL.isNotBlank()) BuildConfig.TRIAL_URL else "https://pkastro.com/new_registration_mobile_trial.php"
        } catch (e: Exception) {
            "https://pkastro.com/new_registration_mobile_trial.php"
        }

        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val stopSendingImeis = prefs.getBoolean(PREF_STOP_SENDING_IMEI, false)

        val uriBuilder = Uri.parse(trialBase).toUri().buildUpon()
        if (!stopSendingImeis && deviceKey.isNotBlank()) {
            uriBuilder.appendQueryParameter("imei", deviceKey)
        }
        if (deviceModel.isNotBlank()) uriBuilder.appendQueryParameter("device", deviceModel)

        val androidHash = getAndroidHash48()
        if (androidHash.isNotBlank()) uriBuilder.appendQueryParameter("android_hash", androidHash)

        val url = uriBuilder.build().toString()
        Log.d(TAG, "Launching Trial Flow with URL: ${maskSensitiveQuery(url)}")

        try {
            val intent = Intent(this, TrialWebViewActivity::class.java).apply {
                putExtra("url", url)
            }
            trialLauncher.launch(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch TrialWebViewActivity: ${e.message}")
            Toast.makeText(this, "Unable to open trial view", Toast.LENGTH_SHORT).show()
        }
    }

    private val deviceIdValue = mutableStateOf<String?>(null)
    private val splashDurationMs = 1500L
    private lateinit var permissionLauncher: ActivityResultLauncher<Array<String>>
    private val hasLocationPermission = mutableStateOf(false)
    private var webUrlState = mutableStateOf<String?>(null)
    private var allowedHostState = mutableStateOf<String?>(null)

    private fun getMediaDrmId(): String {
        return try {
            val widevineUuid = UUID(-0x121074568629b532L, -0x3566d56ef403bdcfL)
            val mediaDrm = MediaDrm(widevineUuid)
            val deviceUniqueId = mediaDrm.getPropertyByteArray(MediaDrm.PROPERTY_DEVICE_UNIQUE_ID)
            deviceUniqueId.joinToString("") { "%02x".format(it) }
        } catch (e: Exception) {
            Log.e("PAS_AUTH", "MediaDrm ID failed: ${e.message}")
            ""
        }
    }

    private fun getAndroidHash48(): String {
        try {
            val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val stored = prefs.getString("android_hash", null)
            if (!stored.isNullOrBlank()) return stored
        } catch (e: Exception) {
            // ignore and fall back to computed value
        }

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

    private fun maskSensitiveQuery(url: String?): String {
        return url ?: ""
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == TRIAL_REQUEST_CODE && resultCode == Activity.RESULT_OK) {
            val migrationAvailable = data?.getBooleanExtra("migration_available", false) ?: false
            if (migrationAvailable) {
                Log.d(TAG, "onActivityResult: migration_available=true")
                val message = data?.getStringExtra("message") ?: "Migration available"
                Toast.makeText(this, message, Toast.LENGTH_LONG).show()

                try {
                    val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    val returnedHash = data?.getStringExtra("android_hash")
                    if (!returnedHash.isNullOrBlank()) {
                        prefs.edit().putString("android_hash", returnedHash).apply()
                        prefs.edit().putBoolean(PREF_STOP_SENDING_IMEI, true).apply()
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to persist android_hash: ${e.message}")
                }

                webUrlState.value = null
                authenticateAndGetUrl(deviceIdValue.value ?: "") { newUrl ->
                    webUrlState.value = newUrl
                    allowedHostState.value = try { newUrl?.let { URL(it).host } } catch (e: Exception) { null }
                }
                return
            }

            val activated = data?.getBooleanExtra("activated", false) ?: false
            val expiry = data?.getStringExtra("expiry") ?: ""

            if (activated) {
                try {
                    val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    val returnedHash = data?.getStringExtra("android_hash")
                    if (!returnedHash.isNullOrBlank()) {
                        prefs.edit().putString("android_hash", returnedHash).apply()
                        prefs.edit().putBoolean(PREF_STOP_SENDING_IMEI, true).apply()
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to persist android_hash from activation: ${e.message}")
                }

                val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                val currentDeviceId = getSourceAndroidId()
                prefs.edit().apply {
                    putString("trial_expiry", expiry)
                    putString("trial_imei", currentDeviceId)
                    putBoolean("is_trial_active", true)
                    apply()
                }

                webUrlState.value = null
                authenticateAndGetUrl(deviceIdValue.value ?: "") { newUrl ->
                    webUrlState.value = newUrl
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        val splashScreen = installSplashScreen()
        super.onCreate(savedInstanceState)
        
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        enableEdgeToEdge()

        var keepSplashScreen = true
        splashScreen.setKeepOnScreenCondition { keepSplashScreen }

        val insetsController = WindowCompat.getInsetsController(window, window.decorView)
        insetsController?.isAppearanceLightStatusBars = false

        permissionLauncher = registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { permissions ->
            hasLocationPermission.value = permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                                         permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        }

        trialLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                onActivityResult(TRIAL_REQUEST_CODE, result.resultCode, result.data)
            }
        }

        val hasFine = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val hasCoarse = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        hasLocationPermission.value = hasFine || hasCoarse

        if (!hasLocationPermission.value) {
            permissionLauncher.launch(arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            ))
        }

        run {
            val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val sourceId = getSourceAndroidId()
            deviceIdValue.value = sourceId.ifEmpty {
                prefs.getString("app_device_id", null) ?: UUID.randomUUID().toString()
            }
            prefs.edit().putString("app_device_id", deviceIdValue.value).apply()
            Log.d(TAG, "Using Device ID: ${deviceIdValue.value}")
        }

        webUrlState = mutableStateOf<String?>(null)
        allowedHostState = mutableStateOf<String?>(null)

        WebView.setWebContentsDebuggingEnabled(true)
        android.webkit.WebView.enableSlowWholeDocumentDraw()

        setContent {
            PASTheme {
                val urlToLoad = webUrlState.value
                val allowedHost = allowedHostState.value
                val permissionGranted = hasLocationPermission.value

                val showPhoneDialog = remember { mutableStateOf(false) }
                val showAboutDialog = remember { mutableStateOf(false) }
                val coroutineScope = rememberCoroutineScope()
                val composeContext = LocalContext.current

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
                                launchTrialFlow()
                            },
                            onRegisterRequested = {
                                showPhoneDialog.value = true
                            },
                            onAboutRequested = {
                                showAboutDialog.value = true
                            },
                            onShareRequested = {
                                // Placeholder for share functionality
                            }
                        )
                    },
                    modifier = Modifier.fillMaxSize().imePadding(),
                    contentWindowInsets = WindowInsets(0, 0, 0, 0)
                ) { innerPadding ->
                    if (urlToLoad == null) {
                        LoadingScreen(modifier = Modifier.padding(innerPadding))
                    } else {
                        WebViewScreen(
                            url = urlToLoad,
                            allowedHost = allowedHost,
                            permissionStatus = permissionGranted,
                            deviceId = deviceIdValue.value,
                            modifier = Modifier.padding(innerPadding)
                        )
                    }
                }

                if (showPhoneDialog.value) {
                    PhoneInputDialog(
                        initialPhone = "",
                        onCancel = { showPhoneDialog.value = false },
                        onSubmit = { phone ->
                            showPhoneDialog.value = false
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

        lifecycleScope.launch {
            try {
                val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                val hasNetwork = cm?.let { mgr ->
                    val net = mgr.activeNetwork ?: return@let false
                    val caps = mgr.getNetworkCapabilities(net) ?: return@let false
                    caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                } ?: false

                if (!hasNetwork) {
                    webUrlState.value = "file:///android_asset/no_internet.html"
                }
            } catch (e: Exception) {
                Log.w(TAG, "Connectivity check failed: ${e.message}")
            }

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

                val startTime = System.currentTimeMillis()
                delay(splashDurationMs)

                while (webUrlState.value == null && (System.currentTimeMillis() - startTime < 10000)) {
                    delay(100)
                }
            }
            keepSplashScreen = false
        }
    }

    private fun openUrlInCustomTab(baseUrl: String) {
        lifecycleScope.launch {
            try {
                val loc = withContext(Dispatchers.IO) { getCurrentLocation(this@MainActivity) }
                val finalUrl = baseUrl
                Log.d(TAG, "Opening URL in Custom Tab: ${maskSensitiveQuery(finalUrl)}")
                val builder = CustomTabsIntent.Builder()
                val customTabsIntent = builder.build()
                customTabsIntent.launchUrl(this@MainActivity, finalUrl.toUri())
            } catch (e: Exception) {
                Log.e(TAG, "Error opening custom tab: ${e.message}")
                try {
                    val intent = Intent(Intent.ACTION_VIEW, baseUrl.toUri())
                    startActivity(intent)
                } catch (ex: Exception) {
                    Log.e(TAG, "Fallback browser open failed: ${ex.message}")
                }
            }
        }
    }

    private fun authenticateAndGetUrl(deviceId: String, onResultUrl: (String?) -> Unit) {
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

        val authBaseFinal = try { authBase.replaceFirst("http://", "https://", ignoreCase = true) } catch (e: Exception) { authBase }
        val siteBaseFinal = try { siteBase.replaceFirst("http://", "https://", ignoreCase = true) } catch (e: Exception) { siteBase }

        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val sourceId = getSourceAndroidId()

        lifecycleScope.launch {
            val resultUrl = withContext(Dispatchers.IO) {
                try {
                    var urlToFetch = authBaseFinal
                    var redirectCount = 0

                    while (redirectCount < 5) {
                        try {
                            val url = URL(urlToFetch)
                            val conn = (url.openConnection() as HttpURLConnection).apply {
                                requestMethod = "GET"
                                connectTimeout = 10000
                                readTimeout = 10000
                                instanceFollowRedirects = false
                                setRequestProperty("User-Agent", "Mozilla/5.0 (Android)")
                            }

                            val responseCode = conn.responseCode
                            Log.d(TAG, "Response code: $responseCode from URL: ${maskSensitiveQuery(urlToFetch)}")

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
                                val responseBody = try {
                                    conn.inputStream.bufferedReader().use { it.readText() }
                                } catch (e: Exception) {
                                    ""
                                }
                                conn.disconnect()

                                Log.d(TAG, "Authentication successful, final URL to load: ${maskSensitiveQuery(siteBaseFinal)}")
                                return@withContext siteBaseFinal
                            } else {
                                Log.d(TAG, "Unexpected response code: $responseCode")
                                conn.disconnect()
                                return@withContext null
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Error fetching URL: ${e.message}", e)
                            return@withContext null
                        }
                    }
                    null
                } catch (e: Exception) {
                    Log.e(TAG, "Authentication error: ${e.message}", e)
                    null
                }
            }

            onResultUrl(resultUrl)
        }
    }

    private fun getSourceAndroidId(): String {
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
            painter = painterResource(id = R.drawable.splash_image),
            contentDescription = "Brand logo",
            modifier = Modifier.fillMaxWidth(0.6f),
            contentScale = ContentScale.Fit
        )

        CircularProgressIndicator(
            color = Color(0xFFFFD700),
            modifier = Modifier.padding(top = 24.dp)
        )
    }
}

@Composable
fun AppTopBar(
    brandRes: Int = R.string.app_name,
    deviceId: String? = null,
    currentUrl: String? = null,

