package com.ridesmart.app.service

import com.ridesmart.app.data.*
import java.util.Calendar

object FareEstimator {

    fun estimateAllFares(pickup: LocationData, drop: LocationData): List<RideQuote> {
        val distance = haversineDistance(pickup, drop)
        if (distance < 0.1) return emptyList()

        val duration = estimateDuration(distance)
        val surge = estimateSurge()
        val quotes = mutableListOf<RideQuote>()

        // Uber fares (Chennai)
        quotes.add(makeQuote(ProviderName.UBER, RideType.AUTO, "Uber Auto", distance, duration, surge,
            baseFare = 30, perKm = 13.0, perMin = 1.2, minFare = 50, etaBase = 4))
        quotes.add(makeQuote(ProviderName.UBER, RideType.BIKE, "Uber Moto", distance, duration, surge,
            baseFare = 20, perKm = 6.0, perMin = 0.8, minFare = 35, etaBase = 3))
        quotes.add(makeQuote(ProviderName.UBER, RideType.MINI, "Uber Go", distance, duration, surge,
            baseFare = 50, perKm = 15.0, perMin = 1.5, minFare = 80, etaBase = 3))
        quotes.add(makeQuote(ProviderName.UBER, RideType.SEDAN, "Go Sedan", distance, duration, surge,
            baseFare = 80, perKm = 18.0, perMin = 2.0, minFare = 110, etaBase = 5))
        quotes.add(makeQuote(ProviderName.UBER, RideType.SUV, "Uber XL", distance, duration, surge,
            baseFare = 100, perKm = 22.0, perMin = 2.2, minFare = 150, etaBase = 6))

        // Ola fares (Chennai)
        quotes.add(makeQuote(ProviderName.OLA, RideType.AUTO, "Auto", distance, duration, surge,
            baseFare = 30, perKm = 12.0, perMin = 1.2, minFare = 50, etaBase = 3))
        quotes.add(makeQuote(ProviderName.OLA, RideType.BIKE, "Bike", distance, duration, surge,
            baseFare = 20, perKm = 6.0, perMin = 0.8, minFare = 35, etaBase = 2))
        quotes.add(makeQuote(ProviderName.OLA, RideType.MINI, "Mini", distance, duration, surge,
            baseFare = 55, perKm = 14.0, perMin = 1.5, minFare = 80, etaBase = 4))
        quotes.add(makeQuote(ProviderName.OLA, RideType.SEDAN, "Prime Sedan", distance, duration, surge,
            baseFare = 75, perKm = 17.0, perMin = 1.9, minFare = 105, etaBase = 5))
        quotes.add(makeQuote(ProviderName.OLA, RideType.SUV, "Prime SUV", distance, duration, surge,
            baseFare = 90, perKm = 20.0, perMin = 2.1, minFare = 140, etaBase = 6))

        // Rapido fares (Chennai)
        quotes.add(makeQuote(ProviderName.RAPIDO, RideType.BIKE, "Bike", distance, duration, surge,
            baseFare = 15, perKm = 5.0, perMin = 0.8, minFare = 30, etaBase = 2))
        quotes.add(makeQuote(ProviderName.RAPIDO, RideType.AUTO, "Auto", distance, duration, surge,
            baseFare = 25, perKm = 11.0, perMin = 1.0, minFare = 45, etaBase = 3))
        quotes.add(makeQuote(ProviderName.RAPIDO, RideType.MINI, "Cab Economy", distance, duration, surge,
            baseFare = 50, perKm = 13.0, perMin = 1.3, minFare = 80, etaBase = 5))

        // Namma Yatri fares (Chennai) — zero-commission, typically cheaper
        quotes.add(makeQuote(ProviderName.NAMMA_YATRI, RideType.AUTO, "Auto", distance, duration, surge,
            baseFare = 25, perKm = 12.0, perMin = 1.0, minFare = 40, etaBase = 4))
        quotes.add(makeQuote(ProviderName.NAMMA_YATRI, RideType.MINI, "AC Cab", distance, duration, surge,
            baseFare = 60, perKm = 13.0, perMin = 1.3, minFare = 90, etaBase = 5))

        return quotes.sortedBy { it.fare }
    }

    private fun makeQuote(
        provider: ProviderName,
        rideType: RideType,
        vehicleName: String,
        distance: Double,
        duration: Int,
        surge: Float,
        baseFare: Int,
        perKm: Double,
        perMin: Double,
        minFare: Int,
        etaBase: Int
    ): RideQuote {
        val rawFare = baseFare + (distance * perKm) + (duration * perMin)
        val surgedFare = (rawFare * surge).toInt()
        val finalFare = maxOf(surgedFare, minFare)
        val etaJitter = (-1..2).random()

        return RideQuote(
            provider = provider,
            rideType = rideType,
            vehicleName = vehicleName,
            fare = finalFare,
            etaMinutes = maxOf(1, etaBase + etaJitter),
            distance = distance,
            duration = duration,
            surgeMultiplier = surge,
            isRealPrice = false
        )
    }

    private fun estimateSurge(): Float {
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        return when (hour) {
            in 7..9 -> 1.2f    // Morning rush
            in 17..20 -> 1.3f  // Evening rush
            in 22..23, in 0..5 -> 1.5f // Late night
            else -> 1.0f
        }
    }

    private fun estimateDuration(distanceKm: Double): Int {
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        val speed = when (hour) {
            in 7..10 -> 15.0   // Morning rush
            in 17..20 -> 12.0  // Evening rush
            in 22..5 -> 30.0   // Late night
            else -> 22.0       // Normal
        }
        return maxOf(5, (distanceKm / speed * 60).toInt())
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
}
