package com.example.pas

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.suspendCancellableCoroutine
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import kotlin.coroutines.resume

private var cachedLocation: android.location.Location? = null
private var lastLocationTime: Long = 0
private const val LOCATION_CACHE_EXPIRY_MS = 1000 * 2 // 2 seconds - very short cache to detect location changes quickly

/**
 * Append the password param (encoded lat,lon) to the provided baseUrl.
 * Format: password=lat,lon (e.g., password=12.9619768,80.194042)
 * Removes existing password params to avoid duplicates.
 *
 * If `key` is provided and the URL targets `index.php`, a `key=...` param will also be
 * appended (after removing any existing key param) so the request includes both password and key.
 */
fun appendPasswordParam(baseUrl: String, lat: Double, lon: Double, key: String? = null): String {
    val payload = "$lat,$lon"
    val encoded = URLEncoder.encode(payload, StandardCharsets.UTF_8.toString())

    // 1. Remove any existing password parameters (matches password=... followed by & or end of string)
    var cleanedUrl = baseUrl.replace(Regex("[&?]password=[^&]*"), "")

    // 2. Remove any existing key parameters to avoid duplicates
    cleanedUrl = cleanedUrl.replace(Regex("[&?]key=[^&]*"), "")

    // 3. Remove any trailing ? or &
    cleanedUrl = cleanedUrl.trimEnd('?', '&')

    // 4. Append the single correct password parameter
    val withPassword = if (cleanedUrl.contains("?")) {
        "$cleanedUrl&password=$encoded"
    } else {
        "$cleanedUrl?password=$encoded"
    }

    // 5. If key is provided and URL targets index.php, append the key param as well
    if (!key.isNullOrEmpty() && cleanedUrl.contains("index.php")) {
        val encodedKey = URLEncoder.encode(key, StandardCharsets.UTF_8.toString())
        return if (withPassword.contains("?")) {
            "$withPassword&key=$encodedKey"
        } else {
            "$withPassword?key=$encodedKey"
        }
    }

    return withPassword
}

@SuppressLint("MissingPermission")
suspend fun getCurrentLocation(context: Context): android.location.Location? {
    // Check if permission is actually granted at this moment
    val hasFine = ContextCompat.checkSelfPermission(context, android.Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
    val hasCoarse = ContextCompat.checkSelfPermission(context, android.Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    if (!hasFine && !hasCoarse) {
        Log.w("LocationSender", "Location permission not granted yet. Returning null.")
        return null
    }

    // Return cached location if it's fresh
    if (cachedLocation != null && (System.currentTimeMillis() - lastLocationTime) < LOCATION_CACHE_EXPIRY_MS) {
        return cachedLocation
    }

    val fusedClient: FusedLocationProviderClient = LocationServices.getFusedLocationProviderClient(context)
    return suspendCancellableCoroutine { cont ->
        try {
            val task = fusedClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null)
            task.addOnSuccessListener { location ->
                if (location != null) {
                    cachedLocation = location
                    lastLocationTime = System.currentTimeMillis()
                }
                cont.resume(location)
            }
            task.addOnFailureListener { ex ->
                Log.e("LocationSender", "Failed to get location: ${ex.message}")
                cont.resume(null)
            }
            task.addOnCanceledListener {
                cont.resume(null)
            }
        } catch (e: Exception) {
            Log.e("LocationSender", "Exception in getCurrentLocation: ${e.message}")
            cont.resume(null)
        }
    }
}
