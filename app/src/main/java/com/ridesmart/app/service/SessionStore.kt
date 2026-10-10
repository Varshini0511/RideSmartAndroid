package com.ridesmart.app.service

import android.content.Context
import com.ridesmart.app.data.LocationData
import com.ridesmart.app.data.RideType
import org.json.JSONObject

/**
 * Persists the active route to disk so it survives even a hard process kill by an aggressive
 * OEM — stronger than rememberSaveable, which only survives a system-initiated restart. On
 * relaunch the results screen can be restored exactly, so the user never re-enters pickup/drop.
 */
object SessionStore {
    private const val PREF = "session"
    private const val KEY = "active"

    data class Session(
        val pickup: LocationData,
        val drop: LocationData,
        val rideTypes: Set<RideType>
    )

    fun save(context: Context, pickup: LocationData, drop: LocationData, rideTypes: Set<RideType>) {
        val json = JSONObject()
            .put("pickup", loc(pickup))
            .put("drop", loc(drop))
            .put("types", rideTypes.joinToString(",") { it.name })
        prefs(context).edit().putString(KEY, json.toString()).apply()
    }

    fun load(context: Context): Session? {
        val raw = prefs(context).getString(KEY, null) ?: return null
        return runCatching {
            val o = JSONObject(raw)
            val types = o.optString("types").split(",")
                .mapNotNull { runCatching { RideType.valueOf(it) }.getOrNull() }
                .toSet()
                .ifEmpty { setOf(RideType.AUTO) }
            Session(loc(o.getJSONObject("pickup")), loc(o.getJSONObject("drop")), types)
        }.getOrNull()
    }

    fun clear(context: Context) {
        prefs(context).edit().remove(KEY).apply()
    }

    private fun loc(l: LocationData) = JSONObject()
        .put("lat", l.latitude).put("lng", l.longitude)
        .put("address", l.address).put("name", l.name)

    private fun loc(o: JSONObject) = LocationData(
        latitude = o.getDouble("lat"),
        longitude = o.getDouble("lng"),
        address = o.optString("address"),
        name = o.optString("name")
    )

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
}
