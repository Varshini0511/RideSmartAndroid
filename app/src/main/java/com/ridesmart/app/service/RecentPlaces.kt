package com.ridesmart.app.service

import android.content.Context
import com.ridesmart.app.data.LocationData
import org.json.JSONArray
import org.json.JSONObject

/** Remembers places the user has picked so typing a letter or two surfaces them instantly. */
object RecentPlaces {
    private const val PREF = "recent_places"
    private const val KEY = "items"
    private const val MAX = 12

    fun add(context: Context, loc: LocationData) {
        if (loc.name.isBlank() && loc.address.isBlank()) return
        val list = load(context).toMutableList()
        list.removeAll {
            (it.name.equals(loc.name, ignoreCase = true) && loc.name.isNotBlank()) ||
                (kotlin.math.abs(it.latitude - loc.latitude) < 1e-5 &&
                    kotlin.math.abs(it.longitude - loc.longitude) < 1e-5)
        }
        list.add(0, loc)
        while (list.size > MAX) list.removeAt(list.size - 1)
        save(context, list)
    }

    /** Recents whose name/address contains [query]; with a blank query, the latest ones. */
    fun match(context: Context, query: String, limit: Int = 4): List<LocationData> {
        val q = query.trim().lowercase()
        val all = load(context)
        val hits = if (q.isEmpty()) all
        else all.filter {
            it.name.lowercase().contains(q) || it.address.lowercase().contains(q)
        }
        return hits.take(limit)
    }

    private fun load(context: Context): List<LocationData> {
        val json = prefs(context).getString(KEY, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(json)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                LocationData(
                    latitude = o.getDouble("lat"),
                    longitude = o.getDouble("lng"),
                    address = o.optString("address"),
                    name = o.optString("name")
                )
            }
        }.getOrDefault(emptyList())
    }

    private fun save(context: Context, list: List<LocationData>) {
        val arr = JSONArray()
        list.forEach {
            arr.put(
                JSONObject()
                    .put("lat", it.latitude)
                    .put("lng", it.longitude)
                    .put("address", it.address)
                    .put("name", it.name)
            )
        }
        prefs(context).edit().putString(KEY, arr.toString()).apply()
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
}
