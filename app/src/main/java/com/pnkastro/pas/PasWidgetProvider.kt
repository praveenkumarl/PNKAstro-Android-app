package com.pnkastro.pas

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.widget.RemoteViews
import android.text.Html
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.net.HttpURLConnection
import java.net.URL

class PasWidgetProvider : AppWidgetProvider() {
    companion object {
        private const val TAG = "PasWidgetProvider"
        private const val ACTION_UPDATE_WIDGET = "com.pnkastro.pas.ACTION_UPDATE_WIDGET"
        private const val UPDATE_INTERVAL_MS = 60_000L // 1 minute
        private const val PREFS_NAME = "pas_prefs"
        private const val PREF_STOP_SENDING_IMEI = "stop_sending_imei"
    }

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        // Ensure alarm is scheduled to run every minute
        scheduleRepeatingAlarm(context)
        // Trigger an immediate update
        fetchAndUpdate(context, appWidgetManager, appWidgetIds)
    }

    override fun onDisabled(context: Context) {
        super.onDisabled(context)
        cancelAlarm(context)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == ACTION_UPDATE_WIDGET) {
            val appWidgetManager = AppWidgetManager.getInstance(context)
            val ids = appWidgetManager.getAppWidgetIds(ComponentName(context, PasWidgetProvider::class.java))
            fetchAndUpdate(context, appWidgetManager, ids)
        }
    }

    private fun scheduleRepeatingAlarm(context: Context) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(context, PasWidgetProvider::class.java).apply { action = ACTION_UPDATE_WIDGET }
        val pending = PendingIntent.getBroadcast(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        alarmManager.setInexactRepeating(
            AlarmManager.ELAPSED_REALTIME_WAKEUP,
            SystemClock.elapsedRealtime() + UPDATE_INTERVAL_MS,
            UPDATE_INTERVAL_MS,
            pending
        )
    }

    private fun cancelAlarm(context: Context) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(context, PasWidgetProvider::class.java).apply { action = ACTION_UPDATE_WIDGET }
        val pending = PendingIntent.getBroadcast(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        alarmManager.cancel(pending)
    }

    private fun fetchAndUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        if (appWidgetIds.isEmpty()) return

        // Pattern to recognize common image file extensions (used in multiple places)
        val imagePattern = Regex(".*\\.(png|jpg|jpeg|webp|gif)(\\?.*)?${'$'}", RegexOption.IGNORE_CASE)

        CoroutineScope(Dispatchers.IO).launch {
            val url = try {
                // We'll use site_url or a dedicated widget_url if provided
                context.getString(R.string.widget_url)
            } catch (e: Exception) {
                // Fallback to a default if resource not found yet
                "https://pkastro.com/preprod/index.php"
            }

            // Append device-specific query parameters (key=device id) if available and android_hash
            val finalUrl = try {
                val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                val deviceId = prefs.getString("app_device_id", null)
                val stop = prefs.getBoolean(PREF_STOP_SENDING_IMEI, false)
                val uriBuilder = android.net.Uri.parse(url).buildUpon()
                if (!stop && !deviceId.isNullOrEmpty()) {
                    uriBuilder.appendQueryParameter("key", deviceId)
                }
                // build android_hash from stored device id deterministically (truncate/pad to 48)
                val androidHash = if (!deviceId.isNullOrEmpty()) {
                    if (deviceId.length >= 48) deviceId.substring(0, 48) else deviceId.padEnd(48, '0')
                } else null
                if (!androidHash.isNullOrEmpty()) uriBuilder.appendQueryParameter("android_hash", androidHash)
                uriBuilder.build().toString()
            } catch (e: Exception) {
                url
            }

            Log.d(TAG, "Fetching widget URL: ${maskSensitive(finalUrl = finalUrl)}")

            // Diagnostic: show the URL on the widget immediately to help debugging
            updateWidgets(appWidgetManager, appWidgetIds, "URL: ${maskSensitive(finalUrl = finalUrl)}", null, context)

             // Probe content-type so we can detect images even if URL has no extension
             val (probeCode, contentType) = try {
                 probeContentType(finalUrl)
             } catch (e: Exception) {
                 Log.w(TAG, "Content-type probe failed for ${maskSensitive(finalUrl = finalUrl)}: ${e.message}")
                 Pair(-1, null)
             }

            // Diagnostic: show probe results briefly
            updateWidgets(appWidgetManager, appWidgetIds, "Probe: $probeCode ${contentType ?: "no-type"}", null, context)

             val isImageContent = contentType?.startsWith("image/") == true

             if (isImageContent) {
                 // Download the image and display it
                 val bitmap = try { fetchImage(finalUrl) } catch (e: Exception) {
                     Log.e(TAG, "Image fetch failed for ${maskSensitive(finalUrl = finalUrl)}: ${e.message}")
                     null
                 }
                 if (bitmap != null) {
                     updateWidgets(appWidgetManager, appWidgetIds, null, bitmap, context)
                     return@launch
                 }
                 // If image fetch failed, fall back to text fetch below
             }

            val (code, fetchedBody) = try {
                fetchUrl(finalUrl)
            } catch (e: Exception) {
                Log.e(TAG, "Widget fetch failed for ${maskSensitive(finalUrl = finalUrl)}: ${e.message}")
                Pair(-1, null)
            }

            // Diagnostic: if we have a response body, show a short snippet so we can see what the server returned
            if (!fetchedBody.isNullOrEmpty()) {
                // Show the fetched body for debugging. Cap length to avoid RemoteViews/widget update limits.
                val MAX_DISPLAY = 8000
                val safeBody = fetchedBody.replace('\n', ' ').replace('\r', ' ')
                val displayBody = if (safeBody.length > MAX_DISPLAY) safeBody.substring(0, MAX_DISPLAY) + "... (truncated)" else safeBody
                updateWidgets(appWidgetManager, appWidgetIds, "BODY: $displayBody", null, context)
            }

            if ((fetchedBody.isNullOrEmpty() || code !in 200..299)) {
                // Try fallback to site_url if different
                val siteUrl = try { context.getString(R.string.site_url) } catch (e: Exception) { null }
                if (!siteUrl.isNullOrEmpty() && siteUrl != url) {
                    Log.d(TAG, "Attempting fallback to site_url: ${maskSensitive(finalUrl = siteUrl)}")
                    // If fallback looks like image, try that
                    if (imagePattern.matches(siteUrl)) {
                        val sbitmap = try { fetchImage(siteUrl) } catch (e: Exception) { null }
                        if (sbitmap != null) {
                            updateWidgets(appWidgetManager, appWidgetIds, null, sbitmap, context)
                            return@launch
                        }
                    }

                    val (scode, sbody) = try {
                        fetchUrl(siteUrl)
                    } catch (e: Exception) {
                        Log.e(TAG, "Fallback fetch failed for ${maskSensitive(finalUrl = siteUrl)}: ${e.message}")
                        Pair(-1, null)
                    }
                    Log.d(TAG, "Fallback response code: $scode")
                    if (!sbody.isNullOrEmpty() && scode in 200..299) {
                        // use fallback body
                        updateWidgets(appWidgetManager, appWidgetIds, sbody, null, context)
                        return@launch
                    }
                }
            }

            Log.d(TAG, "Primary response code: $code")

            // Diagnostic: show final HTTP code and body length
            updateWidgets(appWidgetManager, appWidgetIds, "Code:$code Len:${fetchedBody?.length ?: 0}", null, context)

             val displayText = if (!fetchedBody.isNullOrEmpty() && code in 200..299) {
                 // Convert basic HTML to plain text
                 Html.fromHtml(fetchedBody, Html.FROM_HTML_MODE_LEGACY).toString().trim()
             } else {
                 "Unable to load content"
             }

             updateWidgets(appWidgetManager, appWidgetIds, displayText, null, context)
        }
    }

    // Update helper: if bitmap is non-null, show image and hide text; otherwise show text and hide image.
    private fun updateWidgets(
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
        text: String?,
        bitmap: Bitmap?,
        context: Context
    ) {
        for (appWidgetId in appWidgetIds) {
            try {
                val views = RemoteViews(context.packageName, R.layout.widget_pas)

                if (bitmap != null) {
                    // show image
                    views.setViewVisibility(R.id.widget_image, View.VISIBLE)
                    views.setImageViewBitmap(R.id.widget_image, bitmap)
                    views.setViewVisibility(R.id.widget_text, View.GONE)
                } else {
                    // show text
                    views.setViewVisibility(R.id.widget_image, View.GONE)
                    views.setViewVisibility(R.id.widget_text, View.VISIBLE)
                    views.setTextViewText(R.id.widget_text, text ?: "Unable to load content")
                }

                // Open main activity when clicking the widget
                val launchIntent = context.packageManager.getLaunchIntentForPackage(context.packageName)
                if (launchIntent != null) {
                    try {
                        val pendingLaunch = PendingIntent.getActivity(
                            context,
                            0,
                            launchIntent,
                            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                        )
                        views.setOnClickPendingIntent(R.id.widget_root, pendingLaunch)
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to create launch PendingIntent: ${e.message}")
                    }
                }

                appWidgetManager.updateAppWidget(appWidgetId, views)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to update widget id $appWidgetId: ${e.message}")
            }
        }
    }

    private fun fetchUrl(urlStr: String): Pair<Int, String?> {
        var conn: HttpURLConnection? = null
        return try {
            val url = URL(urlStr)
            conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 10_000
                readTimeout = 10_000
                setRequestProperty("User-Agent", "PASWidget/1.0")
                // Mirror WebView cookies so authenticated sessions are honored
                try {
                    val cookieManager = android.webkit.CookieManager.getInstance()
                    val cookie = cookieManager.getCookie(urlStr)
                    if (!cookie.isNullOrEmpty()) {
                        setRequestProperty("Cookie", cookie)
                        Log.d(TAG, "Attached cookies to request for $urlStr: ${cookie.take(80)}")
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to attach cookies for $urlStr: ${e.message}")
                }
                // Helpful headers
                setRequestProperty("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                setRequestProperty("Accept-Language", "en-US,en;q=0.9")
                instanceFollowRedirects = true
            }
            val code = conn.responseCode
            val body = try {
                val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                stream?.bufferedReader()?.use { it.readText() }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to read response body for $urlStr: ${e.message}")
                null
            }
            Log.d(TAG, "fetchUrl $urlStr -> code=$code, bodyLen=${body?.length ?: 0}")
            Pair(code, body)
        } finally {
            conn?.disconnect()
        }
    }

    private fun fetchImage(urlStr: String): Bitmap? {
        var conn: HttpURLConnection? = null
        return try {
            val url = URL(urlStr)
            conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 10_000
                readTimeout = 10_000
                setRequestProperty("User-Agent", "PASWidget/1.0")
                instanceFollowRedirects = true
            }
            val code = conn.responseCode
            if (code in 200..299) {
                conn.inputStream.use { stream -> BitmapFactory.decodeStream(stream) }
            } else {
                Log.w(TAG, "Image request returned non-2xx code: $code for $urlStr")
                null
            }
        } finally {
            conn?.disconnect()
        }
    }

    // Probe content-type without downloading full body when possible. Returns (responseCode, contentType)
    private fun probeContentType(urlStr: String): Pair<Int, String?> {
        var conn: HttpURLConnection? = null
        return try {
            val url = URL(urlStr)
            conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 8_000
                readTimeout = 8_000
                setRequestProperty("User-Agent", "PASWidget/1.0")
                instanceFollowRedirects = true
            }
            val code = conn.responseCode
            val contentType = conn.contentType ?: conn.getHeaderField("Content-Type")
            Log.d(TAG, "probeContentType $urlStr -> code=$code, contentType=$contentType")
            Pair(code, contentType)
        } finally {
            conn?.disconnect()
        }
    }

    // Mask sensitive information in URLs or logs
    private fun maskSensitive(finalUrl: String? = null, deviceId: String? = null): String? {
        // Mask device ID in logs (replace with dummy value)
        val maskedDeviceId = deviceId?.let { it.substring(0, 3) + "***" + it.substring(it.length - 3) }
        // For finalUrl, replace key=...& or key=...$ with key=***& or key=***$ to mask device ID
        val maskedUrl = finalUrl?.replace(Regex("key=[^&]*&?"), "key=***&")?.replace(Regex("key=[^&]*\$"), "key=***")
        return "URL: ${maskedUrl ?: finalUrl}, DeviceID: ${maskedDeviceId ?: deviceId}"
    }
}
