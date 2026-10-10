package com.ridesmart.app.util

import com.ridesmart.app.data.RideQuote
import com.ridesmart.app.data.RideType
import com.ridesmart.app.data.SmartSuggestion

object SmartComparator {

    data class CategorySuggestions(val rideType: RideType, val suggestions: List<SmartSuggestion>)

    private val byFareThenEta = compareBy<RideQuote>({ it.fare }, { it.etaMinutes })

    /**
     * Live prices win over estimates for the same app + category. Each app appears at most
     * once per category (its best option), so the list never repeats a provider.
     */
    fun mergeQuotes(
        scraped: List<RideQuote>,
        estimated: List<RideQuote>,
        selected: Set<RideType>
    ): List<RideQuote> {
        val live = scraped.filter { it.rideType in selected }
        val covered = live.map { it.provider to it.rideType }.toSet()
        val fallback = estimated.filter {
            it.rideType in selected && (it.provider to it.rideType) !in covered
        }
        return (live + fallback)
            .groupBy { it.provider to it.rideType }
            .map { (_, options) -> options.minWith(byFareThenEta) }
            .sortedWith(compareBy<RideQuote> { it.rideType.ordinal }.then(byFareThenEta))
    }

    /** Suggestions are computed within each category: autos vs autos, cabs vs cabs. */
    fun suggestByCategory(quotes: List<RideQuote>): List<CategorySuggestions> {
        val groups = quotes.groupBy { it.rideType }.toSortedMap(compareBy { it.ordinal })
        val perCategory = if (groups.size == 1) 3 else 2
        return groups.map { (type, options) ->
            CategorySuggestions(type, suggest(options).take(perCategory))
        }
    }

    private fun suggest(options: List<RideQuote>): List<SmartSuggestion> {
        val out = mutableListOf<SmartSuggestion>()

        val cheapest = options.minWith(byFareThenEta)
        val nextFare = options.filter { it != cheapest }.minOfOrNull { it.fare }
        out += SmartSuggestion(
            quote = cheapest,
            badge = "Best Price",
            reason = if (nextFare != null && nextFare > cheapest.fare)
                "₹${nextFare - cheapest.fare} cheaper than next" else "Lowest fare"
        )

        val fastest = options.minWith(compareBy<RideQuote>({ it.etaMinutes }, { it.fare }))
        if (fastest != cheapest && fastest.etaMinutes < cheapest.etaMinutes) {
            out += SmartSuggestion(
                quote = fastest,
                badge = "Fastest Pickup",
                reason = "${fastest.etaMinutes} min away"
            )
        }

        val alternative = options
            .filter { q -> out.none { it.quote == q } }
            .maxByOrNull { valueScore(it, options) }
        if (alternative != null) {
            out += SmartSuggestion(
                quote = alternative,
                badge = "Best Value",
                reason = "₹${alternative.fare} · ${alternative.etaMinutes} min wait"
            )
        }
        return out
    }

    // 60% price, 40% wait — both normalised within the category so units don't skew it.
    private fun valueScore(q: RideQuote, group: List<RideQuote>): Double {
        val minF = group.minOf { it.fare }
        val maxF = group.maxOf { it.fare }
        val minE = group.minOf { it.etaMinutes }
        val maxE = group.maxOf { it.etaMinutes }
        val price = if (maxF == minF) 1.0 else (maxF - q.fare).toDouble() / (maxF - minF)
        val eta = if (maxE == minE) 1.0 else (maxE - q.etaMinutes).toDouble() / (maxE - minE)
        return 0.6 * price + 0.4 * eta
    }

    /** Largest within-category gap: comparing an auto with an SUV isn't a saving. */
    fun calculateSavings(quotes: List<RideQuote>): Int =
        quotes.groupBy { it.rideType }.values
            .filter { it.size >= 2 }
            .maxOfOrNull { group -> group.maxOf { it.fare } - group.minOf { it.fare } } ?: 0
}
