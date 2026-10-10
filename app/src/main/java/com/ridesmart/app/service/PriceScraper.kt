package com.ridesmart.app.service

import com.ridesmart.app.data.LocationData
import com.ridesmart.app.data.ProviderName
import com.ridesmart.app.data.RideQuote

/**
 * Converts the live prices captured by [RideAccessibilityService] into [RideQuote]s for the
 * current route. The actual open→read→next loop runs inside the accessibility service so it
 * survives RideSmart being pushed to the background (see RideAccessibilityService.runAutoScrape).
 */
object PriceScraper {

    private const val DEFAULT_ETA_MIN = 6

    fun quotesFrom(
        scraped: Map<String, RideAccessibilityService.ScrapedPrice>,
        pickup: LocationData,
        drop: LocationData
    ): List<RideQuote> {
        val dist = haversineDistance(pickup, drop)
        val dur = estimateDuration(dist)
        return scraped.flatMap { (pkg, sp) ->
            val provider = ProviderName.fromPackage(pkg) ?: return@flatMap emptyList()
            sp.prices.map { e ->
                RideQuote(
                    provider = provider,
                    rideType = e.rideType,
                    vehicleName = e.vehicleName,
                    fare = e.fare,
                    etaMinutes = e.etaMinutes ?: DEFAULT_ETA_MIN,
                    distance = dist,
                    duration = dur,
                    isRealPrice = true
                )
            }
        }
    }

    private fun haversineDistance(loc1: LocationData, loc2: LocationData): Double {
        val r = 6371.0
        val dLat = Math.toRadians(loc2.latitude - loc1.latitude)
        val dLon = Math.toRadians(loc2.longitude - loc1.longitude)
        val a = Math.sin(dLat / 2).let { it * it } +
            Math.cos(Math.toRadians(loc1.latitude)) *
            Math.cos(Math.toRadians(loc2.latitude)) *
            Math.sin(dLon / 2).let { it * it }
        return r * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
    }

    private fun estimateDuration(distanceKm: Double): Int {
        val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        val isRush = hour in 8..10 || hour in 17..20
        val speed = if (isRush) 15.0 else 25.0
        return (distanceKm / speed * 60).toInt()
    }
}
