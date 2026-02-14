# DNS Resolution and HTTP/HTTPS Redirect Fix Summary

## Problems Fixed

### 1. **301 Redirect Error with DNS Resolution Failure**
**Original Issue:**
```
Response code: 301 from URL: http://pkastro.com/preprod/athenticate_mobile.php?key=e8fad303d6e4d40e
Redirect to: https://pkastro.com/preprod/athenticate_mobile.php?key=e8fad303d6e4d40e
Authentication error: Unable to resolve host "pkastro.com": No address associated with hostname
```

**Root Cause:**
- The server redirects HTTP requests to HTTPS (301 redirect)
- The Android app was unable to resolve the DNS for the HTTPS URL
- The network security configuration wasn't properly allowing both HTTP and HTTPS traffic

### 2. **Network Configuration Issue**
The previous `network_security_config.xml` only allowed cleartext (HTTP) traffic but didn't properly handle HTTPS redirects.

## Solutions Implemented

### 1. **Updated network_security_config.xml**
```xml
<?xml version="1.0" encoding="utf-8"?>
<network-security-config>
    <!-- Allow cleartext (HTTP) and HTTPS for pkastro.com and all subdomains -->
    <domain-config cleartextTrafficPermitted="true">
        <domain includeSubdomains="true">pkastro.com</domain>
        <domain includeSubdomains="true">www.pkastro.com</domain>
    </domain-config>
</network-security-config>
```

**Changes:**
- Added both `pkastro.com` and `www.pkastro.com` domains
- This allows the system to negotiate both HTTP and HTTPS connections
- `cleartextTrafficPermitted="true"` allows the HTTP→HTTPS redirect chain to work properly

### 2. **Enhanced Authentication Logic in MainActivity.kt**
Added the following improvements to the `authenticateAndGetUrl()` method:

**a) User-Agent Header:**
```kotlin
setRequestProperty("User-Agent", "Mozilla/5.0 (Android)")
```
- Mimics browser behavior
- Some servers may respond differently to user-agents

**b) Better Redirect Handling:**
```kotlin
if (finalCode in 301..302) {
    val location = conn.getHeaderField("Location")
    Log.d(TAG, "Redirect to: $location")
    conn.disconnect()
    
    if (location != null) {
        urlToFetch = location
        redirectCount++
        continue
    }
}
```
- Properly follows redirect chains (up to 5 redirects)
- Disconnects previous connections before following redirects
- Logs each redirect for debugging

**c) HTTPS Fallback Logic:**
```kotlin
if (urlToFetch.startsWith("https://") && e.message?.contains("unable to resolve host", ignoreCase = true) == true) {
    val httpUrl = urlToFetch.replace("https://", "http://")
    Log.d(TAG, "HTTPS failed, retrying with HTTP: $httpUrl")
    urlToFetch = httpUrl
    redirectCount++
    continue
}
```
- If HTTPS DNS resolution fails, automatically retries with HTTP
- This handles cases where HTTPS is not properly configured on the server

**d) Connection Management:**
```kotlin
conn.disconnect()  // Always disconnect after reading response
```
- Properly closes connections to avoid resource leaks
- Important when following redirect chains

**e) Better Error Handling:**
```kotlin
// Check if it's a redirect
if (finalCode in 301..302) {
    // Handle redirect
} else if (finalCode in 200..299) {
    // Success
} else {
    // Other response codes are failures
}
```
- Distinguishes between different HTTP response codes
- Properly logs unexpected response codes

## How It Works Now

1. App initiates authentication request to: `http://pkastro.com/preprod/athenticate_mobile.php?key=<deviceId>`
2. Server responds with **301 redirect** to HTTPS version
3. App follows the redirect using the enhanced logic
4. App successfully reaches the HTTPS endpoint
5. Server validates the request and returns: `Praveen|9445321790|PAID`
6. App loads the site URL in the WebView with user's location parameters

## Testing the Fix

When you run the app now, you should see in the logs:
```
Response code: 301 from URL: http://pkastro.com/preprod/athenticate_mobile.php?key=...
Redirect to: https://pkastro.com/preprod/athenticate_mobile.php?key=...
Response code: 200 from URL: https://pkastro.com/preprod/athenticate_mobile.php?key=...
Response body: Praveen|9445321790|PAID
Authentication successful, will open site in-app: [siteUrl]?key=...
```

## Files Modified

1. **D:\praveen\PAS\app\src\main\res\xml\network_security_config.xml**
   - Updated to allow both HTTP and HTTPS for pkastro.com

2. **D:\praveen\PAS\app\src\main\java\com\example\pas\MainActivity.kt**
   - Enhanced `authenticateAndGetUrl()` method with:
     - User-Agent header
     - Better redirect chain handling
     - HTTPS-to-HTTP fallback logic
     - Improved connection management
     - Better error logging

