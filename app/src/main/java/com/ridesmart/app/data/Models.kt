package com.ridesmart.app.data

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

enum class ProviderName(val displayName: String, val packageName: String, val color: Long) {
    UBER("Uber", "com.ubercab", 0xFF000000),
    OLA("Ola", "com.olacabs.customer", 0xFF1C8C37),
    RAPIDO("Rapido", "com.rapido.passenger", 0xFFFFC107),
    NAMMA_YATRI("Namma Yatri", "in.juspay.nammayatri", 0xFF2D8CFF);

    companion object {
        fun fromPackage(packageName: String): ProviderName? =
            entries.firstOrNull { it.packageName == packageName }
    }
}

enum class RideType(val label: String, val icon: String) {
    AUTO("Auto", "🛺"),
    BIKE("Bike", "🏍"),
    MINI("Mini / Cab", "🚗"),
    PREMIUM("Sedan / SUV", "🚙");
}

/**
 * Maps a vehicle name exactly as shown in a ride app to one of four comparison categories.
 * Calibrated to the real names across Uber, Ola, Rapido and Namma Yatri:
 *   Auto       : Auto, Auto Priority, Auto Lite
 *   Bike       : Bike, Bike Pink, Scooty
 *   Mini / Cab : Mini, Mini Non AC, Prime Plus, Cab Economy, Cab Priority, AC Cab,
 *                Uber Go AC, Go Priority
 *   Sedan / SUV: Prime Sedan, Prime SUV, Cab Premium, Cab XL, Sedan, SUV, SUV Plus,
 *                Go Sedan, Uber XL
 */
object VehicleClassifier {
    private val NOISE_WORDS = setOf(
        "pay", "payment", "cash", "offer", "offers", "coupon", "promo", "wallet", "upi",
        "schedule", "later", "book", "confirm", "request", "choose", "select",
        "min", "mins", "away", "km", "drop", "pickup", "arriving", "eta"
    )

    fun classify(name: String): RideType? {
        val lower = name.lowercase().trim()
        if (lower.isEmpty() || lower.length > 28) return null
        if ('₹' in lower || "rs." in lower) return null
        val words = lower.split(Regex("[^a-z0-9/]+")).filter { it.isNotEmpty() }
        if (words.isEmpty() || words.any { it in NOISE_WORDS }) return null
        val joined = words.joinToString(" ")
        fun has(vararg w: String) = w.any { it in words }
        // substr() catches names glued together like "UberXL" or "UberGo".
        fun substr(vararg w: String) = w.any { it in joined.replace(" ", "") }
        // Order matters: Auto/Bike win first; then Sedan/SUV (so "Go Sedan", "Cab XL",
        // "Prime SUV" land there) before everything cab-ish falls into Mini / Cab.
        return when {
            has("auto", "rickshaw", "tuk") -> RideType.AUTO
            has("bike", "moto", "motorbike", "scooty", "scooter") -> RideType.BIKE
            has("sedan", "suv", "premium", "premier", "comfort", "plush", "innova", "ertiga") ||
                substr("suv", "xl") || "6 seat" in joined || "7 seat" in joined -> RideType.PREMIUM
            has("mini", "micro", "economy", "cab", "go", "saver", "lite", "priority",
                "plus", "hatchback") || substr("ubergo") -> RideType.MINI
            else -> null
        }
    }
}

@Parcelize
data class LocationData(
    val latitude: Double,
    val longitude: Double,
    val address: String,
    val name: String = ""
) : Parcelable

data class RideQuote(
    val provider: ProviderName,
    val rideType: RideType,
    val vehicleName: String,
    val fare: Int,
    val etaMinutes: Int,
    val distance: Double,
    val duration: Int,
    val surgeMultiplier: Float = 1.0f,
    val isRealPrice: Boolean = false
)

data class RacingState(
    val selectedQuotes: List<RideQuote>,
    val bookingStatus: Map<ProviderName, BookingStatus> = emptyMap(),
    val notes: Map<ProviderName, String> = emptyMap(),
    val winner: RideQuote? = null,
    val finished: Boolean = false
)

enum class BookingStatus {
    WAITING,
    BOOKING,
    SEARCHING,
    CONFIRMED,
    CANCELLING,
    CANCELLED,
    FAILED
}

data class SmartSuggestion(
    val quote: RideQuote,
    val badge: String,
    val reason: String
)

enum class ProviderLoadState { READING, PENDING, OK, MISSING }

data class ProviderStatus(val provider: ProviderName, val state: ProviderLoadState)
