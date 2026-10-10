package com.ridesmart.app.service

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.ridesmart.app.BuildConfig
import com.ridesmart.app.data.LocationData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

object GeocodingService {

    private const val AUTOCOMPLETE_URL = "https://places.googleapis.com/v1/places:autocomplete"
    private const val DETAILS_URL = "https://places.googleapis.com/v1/places/"
    private val API_KEY = BuildConfig.MAPS_API_KEY

    data class PlaceSuggestion(
        val placeId: String,
        val name: String,
        val address: String
    )

    data class SearchOutcome(
        val suggestions: List<PlaceSuggestion> = emptyList(),
        val error: String? = null
    )

    data class ResolveOutcome(
        val location: LocationData? = null,
        val error: String? = null
    )

    @Volatile
    private var cachedCert: String? = null

    suspend fun search(context: Context, query: String, limit: Int = 5): SearchOutcome {
        if (query.length < 3) return SearchOutcome()
        if (API_KEY.isBlank()) {
            return SearchOutcome(error = "Google API key is missing. Set MAPS_API_KEY in local.properties and rebuild.")
        }

        return withContext(Dispatchers.IO) {
            try {
                val body = JSONObject().apply {
                    put("input", query)
                    put("includedRegionCodes", JSONArray().put("in"))
                    put("languageCode", "en")
                }
                val (code, response) = request(
                    context = context,
                    url = URL(AUTOCOMPLETE_URL),
                    method = "POST",
                    body = body.toString().toByteArray(Charsets.UTF_8)
                )
                if (code !in 200..299) {
                    return@withContext SearchOutcome(error = friendlyError(context, code, response))
                }

                val predictions = JSONObject(response).optJSONArray("suggestions")
                    ?: return@withContext SearchOutcome()
                val results = mutableListOf<PlaceSuggestion>()
                for (i in 0 until minOf(predictions.length(), limit)) {
                    val prediction = predictions.optJSONObject(i)
                        ?.optJSONObject("placePrediction")
                        ?: continue
                    val placeId = prediction.optString("placeId")
                    if (placeId.isBlank()) continue
                    val structured = prediction.optJSONObject("structuredFormat")
                    val name = structured?.optJSONObject("mainText")?.optString("text").orEmpty()
                        .ifBlank { prediction.optJSONObject("text")?.optString("text").orEmpty() }
                    val secondary = structured?.optJSONObject("secondaryText")?.optString("text").orEmpty()
                    if (name.isBlank()) continue
                    results.add(
                        PlaceSuggestion(
                            placeId = placeId,
                            name = name,
                            address = secondary.ifBlank { name }
                        )
                    )
                }
                SearchOutcome(suggestions = results)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                SearchOutcome(error = "Couldn't reach Google. Check your internet connection.")
            }
        }
    }

    suspend fun resolve(context: Context, suggestion: PlaceSuggestion): ResolveOutcome {
        if (API_KEY.isBlank()) {
            return ResolveOutcome(error = "Google API key is missing. Set MAPS_API_KEY in local.properties and rebuild.")
        }
        if (!suggestion.placeId.matches(Regex("^[A-Za-z0-9_-]+$"))) {
            return ResolveOutcome(error = "That place could not be opened.")
        }

        return withContext(Dispatchers.IO) {
            try {
                val (code, response) = request(
                    context = context,
                    url = URL(DETAILS_URL + suggestion.placeId),
                    method = "GET",
                    body = null,
                    fieldMask = "location,displayName,formattedAddress"
                )
                if (code !in 200..299) {
                    return@withContext ResolveOutcome(error = friendlyError(context, code, response))
                }

                val json = JSONObject(response)
                val location = json.optJSONObject("location")
                    ?: return@withContext ResolveOutcome(error = "Google did not return a map location for that place.")
                ResolveOutcome(
                    location = LocationData(
                        latitude = location.getDouble("latitude"),
                        longitude = location.getDouble("longitude"),
                        address = json.optString("formattedAddress", suggestion.address),
                        name = json.optJSONObject("displayName")?.optString("text").orEmpty()
                            .ifBlank { suggestion.name }
                    )
                )
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                ResolveOutcome(error = "Couldn't reach Google. Check your internet connection.")
            }
        }
    }

    // The Maps key is restricted to this Android app. Places API (New) rejects
    // the request unless these headers match the package and signing certificate
    // registered on the key. The legacy Places endpoint is disabled for this key.
    private fun request(
        context: Context,
        url: URL,
        method: String,
        body: ByteArray?,
        fieldMask: String? = null
    ): Pair<Int, String> {
        val cert = signingCertSha1(context)
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 8000
            readTimeout = 8000
            setRequestProperty("X-Goog-Api-Key", API_KEY)
            setRequestProperty("X-Android-Package", context.packageName)
            setRequestProperty("X-Android-Cert", cert)
            setRequestProperty("Accept", "application/json")
            if (fieldMask != null) {
                setRequestProperty("X-Goog-FieldMask", fieldMask)
            }
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
            }
        }
        try {
            if (body != null) {
                conn.outputStream.use { it.write(body) }
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            return code to text
        } finally {
            conn.disconnect()
        }
    }

    private fun friendlyError(context: Context, code: Int, body: String): String {
        val apiMessage = runCatching {
            JSONObject(body).optJSONObject("error")?.optString("message").orEmpty()
        }.getOrDefault("")
        val blocked = apiMessage.contains("blocked", ignoreCase = true) ||
            body.contains("API_KEY_ANDROID_APP_BLOCKED")
        if (blocked) {
            val cert = formatSha1(signingCertSha1(context))
            return "Google blocked this install. On the API key, allow Android app " +
                "${context.packageName} with SHA-1 $cert."
        }
        if (apiMessage.contains("legacy", ignoreCase = true)) {
            return "Enable Places API (New) for this key. The old Places API is turned off."
        }
        if (apiMessage.isNotBlank()) return apiMessage
        return "Place search failed ($code)."
    }

    private fun signingCertSha1(context: Context): String {
        cachedCert?.let { return it }
        val pm = context.packageManager
        val packageName = context.packageName
        val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val info = pm.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES)
            info.signingInfo?.apkContentsSigners ?: emptyArray()
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(packageName, PackageManager.GET_SIGNATURES).signatures ?: emptyArray()
        }
        val encoded = signatures.firstOrNull()?.toByteArray() ?: return ""
        val digest = MessageDigest.getInstance("SHA-1").digest(encoded)
        val hex = digest.joinToString("") { "%02X".format(it) }
        cachedCert = hex
        return hex
    }

    private fun formatSha1(hex: String): String {
        if (hex.length < 2) return hex
        return hex.chunked(2).joinToString(":")
    }
}
