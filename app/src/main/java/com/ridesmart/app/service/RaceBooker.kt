package com.ridesmart.app.service

import android.content.Context
import android.util.Log
import com.ridesmart.app.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/**
 * Race booking: request the chosen rides one after another (only one app can be on screen
 * at a time; once requested, each app keeps searching for a driver on its own), then watch
 * every app's screen text and notifications. The first to assign a driver wins and the
 * other requests are cancelled.
 */
class RaceBooker(private val context: Context) {

    companion object {
        private const val TAG = "RaceBooker"
        private const val STEP_TIMEOUT_MS = 10_000L
        private const val CONFIRM_TIMEOUT_MS = 5 * 60_000L

        private val CONFIRMED_PATTERNS = listOf(
            "\\botp\\b", "\\bpin\\b.*\\d{4}", "driver (is )?(on the way|arriving|assigned)",
            "captain (is )?(on the way|arriving|assigned)", "\\barriving in\\b",
            "ride (is )?confirmed", "booking confirmed", "has arrived", "reached your pickup"
        ).map { Regex(it, RegexOption.IGNORE_CASE) }

        private val BOOK_LABELS = listOf("book", "confirm", "request", "book ride", "confirm booking")
        private val BOOK_PREFIXES = listOf("book ", "confirm ", "request ", "choose ")
        private val PICKUP_CONFIRM_LABELS = listOf("confirm pickup", "confirm pick-up", "confirm location", "done")

        private val CANCEL_LABELS = listOf(
            "cancel ride", "cancel booking", "cancel request", "cancel search", "cancel trip", "cancel"
        )
        private val CANCEL_REASON_LABELS = listOf(
            "booked another ride", "booked another cab", "i booked another ride", "changed my mind",
            "booked by mistake", "other"
        )
        private val CANCEL_CONFIRM_LABELS = listOf(
            "yes, cancel", "yes cancel", "yes, cancel ride", "confirm cancellation",
            "cancel ride", "cancel booking", "submit", "confirm", "yes"
        )
    }

    private val _racingState = MutableStateFlow<RacingState?>(null)
    val racingState: StateFlow<RacingState?> = _racingState

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var raceJob: Job? = null

    fun startRaceBooking(quotes: List<RideQuote>, pickup: LocationData, drop: LocationData) {
        raceJob?.cancel()
        // One request per app — an app can't run two searches at once.
        val picked = quotes.distinctBy { it.provider }.take(3)
        if (picked.isEmpty()) return
        _racingState.value = RacingState(
            selectedQuotes = picked,
            bookingStatus = picked.associate { it.provider to BookingStatus.WAITING }
        )
        raceJob = scope.launch { runRace(picked, pickup, drop) }
    }

    private suspend fun runRace(quotes: List<RideQuote>, pickup: LocationData, drop: LocationData) {
        val requested = mutableListOf<RideQuote>()

        for (quote in quotes) {
            confirmedAmong(requested)?.let { winner -> finishWithWinner(winner, requested); return }

            RideAccessibilityService.clearSignals(quote.provider.packageName)
            setStatus(quote.provider, BookingStatus.BOOKING)
            if (requestRide(quote, pickup, drop)) {
                requested += quote
                setStatus(quote.provider, BookingStatus.SEARCHING)
            } else {
                setStatus(quote.provider, BookingStatus.FAILED)
                setNote(quote.provider, "Couldn't find \"${quote.vehicleName}\" — book it in the app")
            }
        }

        DeepLinks.returnToRideSmart(context)

        if (requested.isEmpty()) {
            _racingState.update { it?.copy(finished = true) }
            return
        }

        val winner = withTimeoutOrNull(CONFIRM_TIMEOUT_MS) {
            var found: RideQuote? = null
            while (found == null) {
                found = confirmedAmong(requested)
                if (found == null) delay(1000)
            }
            found
        }

        if (winner != null) {
            finishWithWinner(winner, requested)
        } else {
            for (q in requested) {
                setNote(q.provider, "No driver in 5 min")
                cancelRequest(q)
            }
            _racingState.update { it?.copy(finished = true) }
            DeepLinks.returnToRideSmart(context)
        }
    }

    /** Opens the app on the route, taps the vehicle row, then its Book/Confirm button. */
    private suspend fun requestRide(quote: RideQuote, pickup: LocationData, drop: LocationData): Boolean {
        val pkg = quote.provider.packageName

        // Get onto the fare screen — deep link first, else drive the app by hand.
        DeepLinks.openWithRoute(context, quote.provider, pickup, drop)
        if (!waitUntil(4000) { RideAccessibilityService.showsPrices(pkg) }) {
            driveToDestination(quote.provider, drop)
            if (!waitUntil(6000) { RideAccessibilityService.showsPrices(pkg) }) return false
        }

        // Select the exact vehicle the user picked (e.g. "Uber Go", "AC Cab").
        val vehicle = RideAccessibilityService.normalize(quote.vehicleName)
        val selected = waitUntil(STEP_TIMEOUT_MS) {
            RideAccessibilityService.clickMatching(pkg) { text ->
                text == vehicle || (text.length <= vehicle.length + 8 && text.endsWith(vehicle))
            }
        }
        if (!selected) return false
        delay(800)

        // Tap Book/Confirm by label; fall back to the big button at the bottom.
        val booked = waitUntil(STEP_TIMEOUT_MS) {
            RideAccessibilityService.clickMatching(pkg) { text ->
                text.length <= 30 && (text in BOOK_LABELS || BOOK_PREFIXES.any { text.startsWith(it) })
            } || RideAccessibilityService.clickPrimaryButton(pkg)
        }
        if (!booked) return false
        delay(1500)

        // Some apps ask to confirm the pickup pin before searching.
        if (!RideAccessibilityService.clickFirst(pkg, PICKUP_CONFIRM_LABELS)) {
            RideAccessibilityService.clickPrimaryButton(pkg)
        }
        delay(1000)
        return true
    }

    /** Same person-like flow the price scraper uses, for apps whose deep link ignores the route. */
    private suspend fun driveToDestination(provider: ProviderName, drop: LocationData) {
        val pkg = provider.packageName
        val destination = drop.name.ifBlank { drop.address }.ifBlank { return }
        DeepLinks.openApp(context, provider)
        delay(2500)
        RideAccessibilityService.clickContaining(
            pkg, listOf("where to", "where are you going", "enter destination",
                "drop location", "search destination", "set drop", "drop")
        )
        delay(1200)
        if (!waitUntil(6000) { RideAccessibilityService.typeDestination(pkg, destination) }) return
        delay(1800)
        waitUntil(6000) { RideAccessibilityService.tapTopSuggestion(pkg, destination) }
        delay(1500)
    }

    private suspend fun finishWithWinner(winner: RideQuote, requested: List<RideQuote>) {
        Log.d(TAG, "WINNER: ${winner.provider.displayName} ${winner.vehicleName}")
        setStatus(winner.provider, BookingStatus.CONFIRMED)
        _racingState.update { it?.copy(winner = winner) }

        // Rides never requested (race ended early) just drop out.
        _racingState.value?.selectedQuotes
            ?.filter { q -> q.provider != winner.provider && requested.none { it.provider == q.provider } }
            ?.forEach { setStatus(it.provider, BookingStatus.CANCELLED) }

        for (q in requested.filter { it.provider != winner.provider }) {
            cancelRequest(q)
        }

        _racingState.update { it?.copy(finished = true) }
        // Leave the user on the winning app so they can see driver details and OTP.
        DeepLinks.openApp(context, winner.provider)
    }

    private suspend fun cancelRequest(quote: RideQuote) {
        val pkg = quote.provider.packageName
        setStatus(quote.provider, BookingStatus.CANCELLING)
        DeepLinks.openApp(context, quote.provider)

        val tapped = waitUntil(8000) { RideAccessibilityService.clickFirst(pkg, CANCEL_LABELS) }
        if (tapped) {
            delay(1200)
            RideAccessibilityService.clickFirst(pkg, CANCEL_REASON_LABELS)
            delay(600)
            waitUntil(4000) { RideAccessibilityService.clickFirst(pkg, CANCEL_CONFIRM_LABELS) }
            delay(1200)
        }

        if (tapped) {
            setStatus(quote.provider, BookingStatus.CANCELLED)
        } else {
            setStatus(quote.provider, BookingStatus.FAILED)
            setNote(quote.provider, "Couldn't cancel automatically — cancel it in ${quote.provider.displayName}")
        }
    }

    private fun confirmedAmong(requested: List<RideQuote>): RideQuote? {
        val screens = RideAccessibilityService.screenText.value
        val notes = RideAccessibilityService.notifications.value
        return requested.firstOrNull { q ->
            val text = "${screens[q.provider.packageName].orEmpty()}\n${notes[q.provider.packageName].orEmpty()}"
            CONFIRMED_PATTERNS.any { it.containsMatchIn(text) }
        }
    }

    private suspend fun waitUntil(timeoutMs: Long, attempt: () -> Boolean): Boolean =
        withTimeoutOrNull(timeoutMs) {
            while (!attempt()) delay(500)
            true
        } ?: false

    private fun setStatus(provider: ProviderName, status: BookingStatus) {
        _racingState.update { it?.copy(bookingStatus = it.bookingStatus + (provider to status)) }
    }

    private fun setNote(provider: ProviderName, note: String) {
        _racingState.update { it?.copy(notes = it.notes + (provider to note)) }
    }

    /** Stops the race and cancels any request that is still searching. */
    fun abortRace() {
        raceJob?.cancel()
        val state = _racingState.value ?: return
        val active = state.selectedQuotes.filter {
            state.bookingStatus[it.provider] in setOf(BookingStatus.BOOKING, BookingStatus.SEARCHING)
        }
        if (active.isEmpty()) {
            _racingState.value = null
            return
        }
        raceJob = scope.launch {
            active.forEach { cancelRequest(it) }
            _racingState.update { it?.copy(finished = true) }
            DeepLinks.returnToRideSmart(context)
        }
    }

    fun dismiss() {
        raceJob?.cancel()
        _racingState.value = null
    }
}
