package com.ridesmart.app.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.app.Notification
import android.content.Context
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.ridesmart.app.data.LocationData
import com.ridesmart.app.data.ProviderName
import com.ridesmart.app.data.RideType
import com.ridesmart.app.data.VehicleClassifier
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

class RideAccessibilityService : AccessibilityService() {

    // Swallow any stray coroutine error so the accessibility service (and the app) can't be
    // crashed by an unexpected failure while reading another app's screen.
    private val scope = CoroutineScope(
        Dispatchers.Main + SupervisorJob() +
            kotlinx.coroutines.CoroutineExceptionHandler { _, e ->
                Log.e(TAG, "coroutine error: ${e.message}")
            }
    )
    private var scanJob: Job? = null
    private var autoScrapeJob: Job? = null
    private var singleJob: Job? = null

    enum class ScrapeStatus { IDLE, SCRAPING, DONE }

    companion object {
        private const val TAG = "RideA11y"
        private val RIDE_PACKAGES = ProviderName.entries.map { it.packageName }.toSet()
        private val PRICE_REGEX = Regex("""(?:₹|Rs\.?|INR)\s*([0-9][0-9,]{1,5})""")
        // Like PRICE_REGEX but also eats an optional leading "Fare " so the name before it is clean.
        private val FARE_REGEX = Regex("""(?i)(?:fare\s*)?(?:₹|rs\.?|inr)\s*([0-9][0-9,]{1,5})""")
        private val ETA_REGEX = Regex("""(\d{1,2})\s*min""", RegexOption.IGNORE_CASE)
        private val PROMO_REGEX = Regex("""\b(off|save|saved|cashback|discount)\b""", RegexOption.IGNORE_CASE)

        // How long to wait per app for its price list to render before moving on. Capture
        // returns the instant a price is read, so this is only the cap for a slow render.
        private const val PER_APP_MS = 10000L
        // Max time for the typed-route flow (Rapido/NY) to produce fares.
        private const val DRIVE_WAIT_MS = 10000L
        // Hard watchdog per app — nothing may exceed this, so the loop can never hang.
        private const val MAX_PER_APP_MS = 35000L
        // After the first price appears, keep reading this long to catch the whole list.
        private const val SETTLE_MS = 2500L
        // Labels that open the pickup ("from") and drop ("where to") search fields.
        private val PICKUP_FIELD_LABELS = listOf(
            "pickup", "pick up", "pick-up", "from", "current location", "enter pickup"
        )
        private val DROP_FIELD_LABELS = listOf(
            "where to", "where are you going", "drop", "destination", "search destination",
            "enter drop", "to "
        )
        // ONLY pickup-confirmation buttons — specific enough that they never match a
        // generic "Next/Done" on a home screen, and never a Book/Choose-ride button.
        private val PICKUP_CONFIRM_LABELS = listOf(
            "confirm pickup", "confirm pick-up", "confirm your pickup", "confirm location"
        )

        private val _isRunning = MutableStateFlow(false)
        val isRunning: StateFlow<Boolean> = _isRunning

        private val _scrapeStatus = MutableStateFlow(ScrapeStatus.IDLE)
        val scrapeStatus: StateFlow<ScrapeStatus> = _scrapeStatus

        private val _currentProvider = MutableStateFlow<ProviderName?>(null)
        val currentProvider: StateFlow<ProviderName?> = _currentProvider

        private val _scrapedPrices = MutableStateFlow<Map<String, ScrapedPrice>>(emptyMap())
        val scrapedPrices: StateFlow<Map<String, ScrapedPrice>> = _scrapedPrices

        // Latest visible text per ride app and latest notification per ride app.
        // Used by the race booker to spot "driver assigned" screens.
        private val _screenText = MutableStateFlow<Map<String, String>>(emptyMap())
        val screenText: StateFlow<Map<String, String>> = _screenText

        private val _notifications = MutableStateFlow<Map<String, String>>(emptyMap())
        val notifications: StateFlow<Map<String, String>> = _notifications

        private val _currentAction = MutableStateFlow<ScrapeAction?>(null)

        @Volatile
        private var instance: RideAccessibilityService? = null

        fun startScraping(action: ScrapeAction) {
            _currentAction.value = action
        }

        fun stopScraping() {
            _currentAction.value = null
        }

        /**
         * Runs the whole open→read→next loop INSIDE the service, which the OS keeps alive
         * even when RideSmart is in the background. On phones that freeze background apps
         * (Vivo/iQOO, Xiaomi, Oppo…) an Activity-driven loop would stall the moment a ride
         * app comes to the front; running it here survives that.
         */
        fun requestAutoScrape(pickup: LocationData, drop: LocationData) {
            instance?.startAutoScrape(pickup, drop)
        }

        /**
         * Option-1 flow: open ONE app on the route, read its price, and snap back to RideSmart
         * automatically. Triggered by the user tapping that app, so the launch is never blocked
         * by the OS. Reliable and smooth — no hands-off background cycling.
         */
        fun captureOne(pickup: LocationData, drop: LocationData, provider: ProviderName) {
            instance?.startSingle(pickup, drop, provider)
        }

        fun cancelAutoScrape() {
            instance?.let {
                it.autoScrapeJob?.cancel()
                it.singleJob?.cancel()
                _currentProvider.value = null
                _scrapeStatus.value = ScrapeStatus.DONE
            }
        }

        fun clearPricesForPackage(packageName: String) {
            _scrapedPrices.update { it - packageName }
        }

        fun clearAllPrices() {
            _scrapedPrices.value = emptyMap()
        }

        // Persist captured prices so they survive the process being killed (iQOO) while the
        // user is in another app; restored when RideSmart reopens.
        fun persistPrices(context: Context) {
            val json = org.json.JSONObject()
            _scrapedPrices.value.forEach { (pkg, sp) ->
                val arr = org.json.JSONArray()
                sp.prices.forEach {
                    arr.put(
                        org.json.JSONObject()
                            .put("n", it.vehicleName).put("t", it.rideType.name)
                            .put("f", it.fare).put("e", it.etaMinutes ?: -1)
                    )
                }
                json.put(pkg, arr)
            }
            pricePrefs(context).edit().putString("p", json.toString()).apply()
        }

        fun restorePrices(context: Context) {
            if (_scrapedPrices.value.isNotEmpty()) return
            val raw = pricePrefs(context).getString("p", null) ?: return
            runCatching {
                val json = org.json.JSONObject(raw)
                val map = mutableMapOf<String, ScrapedPrice>()
                json.keys().forEach { pkg ->
                    val arr = json.getJSONArray(pkg)
                    val list = (0 until arr.length()).map {
                        val o = arr.getJSONObject(it)
                        PriceEntry(
                            vehicleName = o.getString("n"),
                            rideType = RideType.valueOf(o.getString("t")),
                            fare = o.getInt("f"),
                            etaMinutes = o.getInt("e").takeIf { e -> e >= 0 }
                        )
                    }
                    if (list.isNotEmpty()) map[pkg] = ScrapedPrice(pkg, list)
                }
                if (map.isNotEmpty()) _scrapedPrices.value = map
            }
        }

        fun clearPersistedPrices(context: Context) {
            pricePrefs(context).edit().remove("p").apply()
        }

        private fun pricePrefs(context: Context) =
            context.getSharedPreferences("captured_prices", Context.MODE_PRIVATE)

        fun clearSignals(packageName: String) {
            _screenText.update { it - packageName }
            _notifications.update { it - packageName }
        }

        fun goHomeNow() {
            instance?.performGlobalAction(GLOBAL_ACTION_HOME)
        }

        /**
         * Clicks the first visible element in [packageName]'s window whose text matches
         * [predicate]. Falls back to the nearest clickable ancestor, then to a tap gesture.
         */
        fun clickMatching(packageName: String, predicate: (String) -> Boolean): Boolean {
            val svc = instance ?: return false
            val root = svc.rootInActiveWindow ?: return false
            if (root.packageName?.toString() != packageName) return false
            val target = svc.findNode(root) { node ->
                node.isVisibleToUser && nodeText(node)?.let { predicate(normalize(it)) } == true
            } ?: return false
            return svc.click(target)
        }

        /** Tries each candidate label in priority order (exact, case-insensitive match). */
        fun clickFirst(packageName: String, labels: List<String>): Boolean {
            for (label in labels) {
                val wanted = normalize(label)
                if (clickMatching(packageName) { it == wanted }) return true
            }
            return false
        }

        /** Clicks an element whose text *contains* any of [substrings] (e.g. "where to"). */
        fun clickContaining(packageName: String, substrings: List<String>): Boolean {
            val wanted = substrings.map { normalize(it) }
            return clickMatching(packageName) { text -> wanted.any { it in text } }
        }

        /** Package of the app currently in the foreground, or null. */
        fun foregroundPackage(): String? =
            instance?.rootInActiveWindow?.packageName?.toString()

        /** Scrolls the ride list down so off-screen options (Mini/Cab/Sedan) come into view. */
        fun scrollRideList(packageName: String): Boolean {
            val svc = instance ?: return false
            val root = svc.rootInActiveWindow ?: return false
            if (root.packageName?.toString() != packageName) return false
            // Prefer a real scrollable node's scroll action.
            val scrollable = svc.findNode(root) { it.isScrollable && it.isVisibleToUser }
            if (scrollable != null &&
                scrollable.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
            ) return true
            // Fallback: swipe up in the lower half of the screen.
            val screen = Rect().also { root.getBoundsInScreen(it) }
            if (screen.isEmpty) return false
            val x = screen.exactCenterX()
            val path = Path().apply {
                moveTo(x, screen.top + screen.height() * 0.72f)
                lineTo(x, screen.top + screen.height() * 0.40f)
            }
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, 250))
                .build()
            return svc.dispatchGesture(gesture, null, null)
        }

        /** True if the current window belongs to [packageName] and shows a ₹ fare. */
        fun showsPrices(packageName: String): Boolean {
            val svc = instance ?: return false
            val root = svc.rootInActiveWindow ?: return false
            if (root.packageName?.toString() != packageName) return false
            val texts = mutableListOf<String>()
            svc.collectTexts(root, texts, limit = 400)
            return texts.any { PRICE_REGEX.containsMatchIn(it) }
        }

        /**
         * Reads the current window right now (not waiting for an event) and stores any
         * vehicle rows found. Returns true if at least one price was captured. Safe to call
         * repeatedly — rows merge by vehicle name, so progressive rendering is handled.
         */
        fun capturePricesNow(packageName: String): Boolean {
            val svc = instance ?: return false
            val root = svc.rootInActiveWindow ?: return false
            if (root.packageName?.toString() != packageName) return false
            val found = runCatching { svc.extractVehicleRows(root) }.getOrDefault(emptyList())
            if (found.isEmpty()) return false
            _scrapedPrices.update { current ->
                val merged = (current[packageName]?.prices.orEmpty() + found)
                    .associateBy { normalize(it.vehicleName) }
                    .values.toList()
                current + (packageName to ScrapedPrice(packageName, merged))
            }
            return true
        }

        /** Types [text] into the currently focused field (falls back to the first editable). */
        fun fillFocusedField(packageName: String, text: String): Boolean {
            val svc = instance ?: return false
            val root = svc.rootInActiveWindow ?: return false
            if (root.packageName?.toString() != packageName) return false
            val field = svc.findNode(root) { it.isFocused && it.isEditable && it.isVisibleToUser }
                ?: svc.findNode(root) { it.isEditable && it.isVisibleToUser }
                ?: svc.findNode(root) {
                    it.isVisibleToUser && it.className?.toString()?.contains("EditText") == true
                }
                ?: return false
            field.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            val args = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
            }
            return field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        }

        /** Finds the destination search box, focuses it and types [destination]. */
        fun typeDestination(packageName: String, destination: String): Boolean {
            val svc = instance ?: return false
            val root = svc.rootInActiveWindow ?: return false
            if (root.packageName?.toString() != packageName) return false
            val field = svc.findNode(root) { it.isEditable && it.isVisibleToUser }
                ?: svc.findNode(root) {
                    it.isVisibleToUser && it.className?.toString()?.contains("EditText") == true
                }
                ?: return false
            field.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            val args = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, destination)
            }
            return field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        }

        /**
         * Taps the top autocomplete result: the first visible clickable row, below any
         * input field, that carries real text (not a price, not the typed query echoed back).
         */
        fun tapTopSuggestion(packageName: String, query: String): Boolean {
            val svc = instance ?: return false
            val root = svc.rootInActiveWindow ?: return false
            if (root.packageName?.toString() != packageName) return false

            val editField = svc.findNode(root) { it.isEditable && it.isVisibleToUser }
            val fieldBottom = editField
                ?.let { Rect().also { r -> it.getBoundsInScreen(r) }.bottom } ?: 0

            // The first suggestion row below the input. Do NOT exclude rows that match what we
            // typed — the matching suggestion IS the one to tap. Only exclude the input field
            // itself (editable) and price rows.
            val candidates = mutableListOf<AccessibilityNodeInfo>()
            svc.collectIf(root, candidates) { node ->
                if (!node.isVisibleToUser || node.isEditable) return@collectIf false
                val text = nodeText(node) ?: return@collectIf false
                if (text.length < 3 || PRICE_REGEX.containsMatchIn(text)) return@collectIf false
                val r = Rect().also { node.getBoundsInScreen(it) }
                r.top >= fieldBottom && !r.isEmpty
            }
            val top = candidates.minByOrNull {
                Rect().also { r -> it.getBoundsInScreen(r) }.top
            } ?: return false
            return svc.click(top)
        }

        /**
         * Taps the primary call-to-action: the widest clickable button in the bottom third
         * of the screen. Survives label changes ("Book", "Confirm", "Request Auto", …).
         */
        fun clickPrimaryButton(packageName: String): Boolean {
            val svc = instance ?: return false
            val root = svc.rootInActiveWindow ?: return false
            if (root.packageName?.toString() != packageName) return false
            val screen = Rect().also { root.getBoundsInScreen(it) }
            val bottomThird = screen.top + (screen.height() * 2 / 3)

            val buttons = mutableListOf<AccessibilityNodeInfo>()
            svc.collectIf(root, buttons) { node ->
                if (!node.isVisibleToUser || !node.isClickable) return@collectIf false
                val r = Rect().also { node.getBoundsInScreen(it) }
                !r.isEmpty && r.centerY() >= bottomThird && r.width() >= screen.width() / 2
            }
            val cta = buttons.maxByOrNull {
                Rect().also { r -> it.getBoundsInScreen(r) }.width()
            } ?: return false
            return svc.click(cta)
        }

        fun normalize(text: String): String =
            text.lowercase().replace(Regex("\\s+"), " ").trim()

        private fun nodeText(node: AccessibilityNodeInfo): String? {
            val text = node.text?.toString()
            if (!text.isNullOrBlank()) return text
            val desc = node.contentDescription?.toString()
            return if (desc.isNullOrBlank()) null else desc
        }
    }

    data class ScrapedPrice(
        val packageName: String,
        val prices: List<PriceEntry>,
        val timestamp: Long = System.currentTimeMillis()
    )

    data class PriceEntry(
        val vehicleName: String,
        val rideType: RideType,
        val fare: Int,
        val etaMinutes: Int?,
        val rawText: String = ""
    )

    data class ScrapeAction(val targetPackage: String)

    private var heartbeatJob: Job? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        _isRunning.value = true
        // Heartbeat: every 1.2s, read whatever ride app is on screen. Event-driven capture
        // misses prices that are already static when you look; this catches them reliably.
        // Run screen-reading OFF the main thread, or fetching another window's content every
        // 1.2s stalls RideSmart's own UI (janky scroll, and iQOO bouncing you out of the app).
        heartbeatJob = scope.launch(Dispatchers.Default) {
            while (isActive) {
                delay(1200)
                runCatching { scanActiveWindow() }
            }
        }
        Log.d(TAG, "Accessibility Service connected")
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        _isRunning.value = false
        heartbeatJob?.cancel()
        scope.cancel()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val pkg = event?.packageName?.toString() ?: return
        if (pkg !in RIDE_PACKAGES) return

        if (event.eventType == AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED) {
            val notification = event.parcelableData as? Notification
            val extras = notification?.extras
            val text = listOfNotNull(
                event.text.joinToString(" "),
                extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString(),
                extras?.getCharSequence(Notification.EXTRA_TEXT)?.toString()
            ).joinToString(" ").trim()
            if (text.isNotEmpty()) _notifications.update { it + (pkg to text) }
            return
        }

        // Content-changed events fire many times per second; scan at most every 250ms, and on
        // a background thread so a heavy window read never stalls RideSmart's UI.
        if (scanJob?.isActive == true) return
        scanJob = scope.launch(Dispatchers.Default) {
            delay(250)
            runCatching { scanActiveWindow() }  // never let a scan error crash the app
        }
    }

    private fun startSingle(pickup: LocationData, drop: LocationData, provider: ProviderName) {
        singleJob?.cancel()
        autoScrapeJob?.cancel()
        singleJob = scope.launch { runSingle(provider, pickup, drop) }
    }

    private suspend fun runSingle(provider: ProviderName, pickup: LocationData, drop: LocationData) {
        _scrapeStatus.value = ScrapeStatus.SCRAPING
        _currentProvider.value = provider
        ScrapeKeepAliveService.start(this)
        try {
            withTimeoutOrNull(MAX_PER_APP_MS) { scrapeOne(provider, pickup, drop) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "capture ${provider.displayName} failed: ${e.message}")
        } finally {
            _currentAction.value = null
            _currentProvider.value = null
            _scrapeStatus.value = ScrapeStatus.DONE
            runCatching { DeepLinks.returnToRideSmart(this) } // snap back to RideSmart
            ScrapeKeepAliveService.stop(this)
        }
    }

    private fun startAutoScrape(pickup: LocationData, drop: LocationData) {
        autoScrapeJob?.cancel()
        autoScrapeJob = scope.launch { runAutoScrape(pickup, drop) }
    }

    private suspend fun runAutoScrape(pickup: LocationData, drop: LocationData) {
        _scrapeStatus.value = ScrapeStatus.SCRAPING
        ScrapeKeepAliveService.start(this)
        try {
            // Attempt every app. A hard per-app watchdog guarantees no single app can ever
            // hang the loop — if it overruns, we move on and still return to RideSmart.
            for (provider in ProviderName.entries) {
                _currentProvider.value = provider
                try {
                    withTimeoutOrNull(MAX_PER_APP_MS) { scrapeOne(provider, pickup, drop) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "scrape ${provider.displayName} failed: ${e.message}")
                }
            }
        } finally {
            _currentAction.value = null
            _currentProvider.value = null
            runCatching { DeepLinks.returnToRideSmart(this) }
            ScrapeKeepAliveService.stop(this)
            _scrapeStatus.value = ScrapeStatus.DONE
        }
    }

    /**
     * Opens one app on the route and reads its price list. Only taps a button that literally
     * says "Confirm pickup", and only while THAT app is in front with no price yet — so it
     * can never wander off a home screen or press Book. The next app's deep link then
     * switches straight to it, with no disruptive trip to the home screen in between.
     */
    private suspend fun scrapeOne(provider: ProviderName, pickup: LocationData, drop: LocationData) {
        val pkg = provider.packageName
        // Don't wipe this app's price here — a new Compare already cleared everything up front,
        // and wiping mid-run would erase a price the user captured manually by tapping the app.
        _currentAction.value = ScrapeAction(pkg)

        DeepLinks.openWithRoute(this, provider, pickup, drop)
        var captured = dwell(pkg, if (DeepLinks.routeLinkWorks(provider)) PER_APP_MS else 4000L)

        // Rapido / Namma Yatri don't carry the route in a link, so set it in-app like a
        // person: type the pickup, then the drop, then read the fares.
        if (!captured && !DeepLinks.routeLinkWorks(provider)) {
            typeRoute(provider, pickup, drop)
            captured = dwell(pkg, DRIVE_WAIT_MS)
        }

        if (captured) {
            // Read the visible rides, then scroll the list down a few times so Mini/Cab/Sedan
            // options below the fold (which aren't in the screen data until scrolled) also
            // get read. Prices merge by name, so scrolling only adds, never loses.
            val settleEnd = SystemClock.elapsedRealtime() + SETTLE_MS
            while (SystemClock.elapsedRealtime() < settleEnd) {
                capturePricesNow(pkg)
                delay(400)
            }
            repeat(4) {
                scrollRideList(pkg)
                delay(700)
                capturePricesNow(pkg)
            }
        }
    }

    /**
     * Types a full custom route into an app that has no route link. Sets the pickup field
     * first (so prices aren't tied to GPS), then the drop. Each step is bounded; if a field
     * or suggestion can't be found it simply bails (no wrong-pickup price, no hang).
     */
    private suspend fun typeRoute(provider: ProviderName, pickup: LocationData, drop: LocationData) {
        val pkg = provider.packageName
        val pickupText = pickup.name.ifBlank { pickup.address }
        val dropText = drop.name.ifBlank { drop.address }
        if (pickupText.isBlank() || dropText.isBlank()) return
        if (foregroundPackage() != pkg) return

        // Pickup — open the pickup/"from" field, replace it with our pickup.
        if (clickContaining(pkg, PICKUP_FIELD_LABELS)) {
            delay(1200)
            if (fillFocusedField(pkg, pickupText)) {
                delay(1800)
                waitAndTap(pkg, pickupText)
                delay(1200)
            }
        }
        if (foregroundPackage() != pkg) return

        // Drop — open the "where to"/drop field, type our drop.
        if (clickContaining(pkg, DROP_FIELD_LABELS)) {
            delay(1200)
            if (fillFocusedField(pkg, dropText)) {
                delay(1800)
                waitAndTap(pkg, dropText)
                delay(1500)
            }
        }
    }

    private suspend fun waitAndTap(pkg: String, query: String) {
        val end = SystemClock.elapsedRealtime() + 6000
        while (SystemClock.elapsedRealtime() < end) {
            if (tapTopSuggestion(pkg, query)) return
            delay(500)
        }
    }

    /** Reads the app every 800ms until a price is captured or [totalMs] elapses. */
    private suspend fun dwell(pkg: String, totalMs: Long): Boolean {
        val end = SystemClock.elapsedRealtime() + totalMs
        while (SystemClock.elapsedRealtime() < end) {
            if (capturePricesNow(pkg)) return true
            if (foregroundPackage() == pkg && !showsPrices(pkg)) {
                clickContaining(pkg, PICKUP_CONFIRM_LABELS)
            }
            delay(800)
        }
        return capturePricesNow(pkg)
    }

    private fun scanActiveWindow() {
        val root = rootInActiveWindow ?: return
        val pkg = root.packageName?.toString() ?: return
        if (pkg !in RIDE_PACKAGES) return

        val texts = mutableListOf<String>()
        runCatching { collectTexts(root, texts, limit = 400) }
        _screenText.update { it + (pkg to texts.joinToString("\n")) }

        // Passive capture: read prices from ANY ride app whose price screen is visible,
        // whether RideSmart auto-opened it or the user did. Auto-drive just brings the
        // screen to the front; the reading happens here either way.
        val found = runCatching { extractVehicleRows(root) }.getOrDefault(emptyList())
        if (found.isEmpty()) return

        // Union with earlier scans of this session so a half-rendered frame can't drop rows.
        _scrapedPrices.update { current ->
            val merged = (current[pkg]?.prices.orEmpty() + found)
                .associateBy { normalize(it.vehicleName) }
                .values.toList()
            current + (pkg to ScrapedPrice(pkg, merged))
        }
        Log.d(TAG, "$pkg vehicles: ${found.joinToString { "${it.vehicleName}=₹${it.fare}" }}")
    }

    private class Label(val text: String, val bounds: Rect)

    /**
     * Ride lists show the vehicle name and its fare on the same horizontal row. Rather than
     * guessing the view hierarchy (which differs per app), collect every visible text with
     * its screen position, then pair each vehicle name with the ₹ fare sitting on the same
     * row (closest vertical centre). Robust across Uber/Ola/Rapido/Namma Yatri layouts.
     */
    private fun extractVehicleRows(root: AccessibilityNodeInfo): List<PriceEntry> {
        val labels = mutableListOf<Label>()
        collectLabels(root, labels)
        if (labels.isEmpty()) return emptyList()

        val byName = linkedMapOf<String, PriceEntry>()
        fun record(name: String, type: RideType, fare: Int, eta: Int?) {
            val key = normalize(name)
            val existing = byName[key]
            if (existing == null || fare < existing.fare) {
                byName[key] = PriceEntry(name, type, fare, eta ?: existing?.etaMinutes, "$name ₹$fare")
            }
        }

        data class NameHit(val name: String, val type: RideType, val bounds: Rect)
        val names = mutableListOf<NameHit>()
        val prices = mutableListOf<Pair<Int, Rect>>()

        for (label in labels) {
            val text = label.text
            if (PROMO_REGEX.containsMatchIn(text)) continue

            // Scan for every "<name> … [Fare] ₹<price>" pair in this node. Handles a single
            // ride per node, Uber/Rapido's "<name>,Fare ₹…" lines, AND a whole list packed into
            // one node — for each price, the vehicle name is the last classifiable chunk before
            // it (since the previous price).
            val priceHits = FARE_REGEX.findAll(text).toList()
            var anyNamed = false
            var cursor = 0
            for (ph in priceHits) {
                val fare = ph.groupValues[1].replace(",", "").toIntOrNull()
                val before = text.substring(cursor, ph.range.first)
                cursor = ph.range.last + 1
                if (fare == null || fare !in 25..9000) continue
                val name = before.split(Regex("[,·•|.\n]"))
                    .map { cleanName(it) }
                    .lastOrNull { it.isNotBlank() && it.length <= 24 && VehicleClassifier.classify(it) != null }
                if (name != null) {
                    record(name, VehicleClassifier.classify(name)!!, fare, etaOf(text))
                    anyNamed = true
                }
            }
            if (anyNamed) continue

            // No name sat with the price in this node (Ola-style separate labels) → pair by
            // screen position. Needs real coordinates, so skip bounds-less (not-visible) nodes.
            if (label.bounds.isEmpty) continue
            priceHits.forEach { ph ->
                ph.groupValues[1].replace(",", "").toIntOrNull()?.takeIf { it in 25..9000 }
                    ?.let { prices.add(it to label.bounds) }
            }
            val cleaned = cleanName(text)
            if (cleaned.isNotBlank() && cleaned.length <= 24) {
                VehicleClassifier.classify(cleaned)?.let { names.add(NameHit(cleaned, it, label.bounds)) }
            }
        }

        for (hit in names) {
            val fare = prices.filter { (_, r) -> sameRow(hit.bounds, r) }.minOfOrNull { it.first } ?: continue
            val eta = labels.firstNotNullOfOrNull { l ->
                if (sameRow(hit.bounds, l.bounds)) etaOf(l.text) else null
            }
            record(hit.name, hit.type, fare, eta)
        }
        return byName.values.toList()
    }

    private fun etaOf(text: String): Int? =
        ETA_REGEX.find(text)?.groupValues?.get(1)?.toIntOrNull()

    /** Two labels are on the same row if their vertical centres are within half a row. */
    private fun sameRow(a: Rect, b: Rect): Boolean {
        val tol = maxOf(a.height(), b.height()) / 2 + 14
        return kotlin.math.abs(a.centerY() - b.centerY()) <= tol
    }

    private fun cleanName(s: String): String =
        s.replace(PRICE_REGEX, " ")
            .replace(Regex("""\d+\s*min\w*""", RegexOption.IGNORE_CASE), " ")
            .replace(
                Regex("""\b(fare|estimated|selected|faster|ride|screen|not|drop-?off|capacity|away|in)\b""",
                    RegexOption.IGNORE_CASE), " "
            )
            .replace(Regex("[·•|,().]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

    private fun parseFare(match: MatchResult): Int? =
        match.groupValues[1].replace(",", "").toIntOrNull()

    // Collects visible short texts with screen bounds. Node budget bounds the work so a heavy
    // webview app (e.g. Namma Yatri) can't freeze the loop walking thousands of nodes.
    private fun collectLabels(
        node: AccessibilityNodeInfo,
        out: MutableList<Label>,
        budget: IntArray = intArrayOf(6000)
    ) {
        if (budget[0] <= 0 || out.size >= 800) return
        budget[0]--
        nodeText(node)?.let { t ->
            // Collect ALL text nodes, even ones flagged not-visible — Uber/Rapido keep their
            // "Auto,Fare ₹283" ride info in a container's accessibility description that reports
            // not-visible. Bounds may be empty for those; the combined-line parser ignores
            // bounds, and position-pairing (Ola) filters to real bounds itself. Big limit so a
            // ride list that sits below a long map/header tree still gets reached.
            if (t.length in 1..400) {
                val r = Rect().also { node.getBoundsInScreen(it) }
                out.add(Label(t, r))
            }
        }
        for (i in 0 until node.childCount) {
            if (budget[0] <= 0 || out.size >= 800) return
            node.getChild(i)?.let { collectLabels(it, out, budget) }
        }
    }

    private fun collectTexts(node: AccessibilityNodeInfo, out: MutableList<String>, limit: Int) {
        if (out.size >= limit) return
        nodeText(node)?.let { out.add(it) }
        for (i in 0 until node.childCount) {
            if (out.size >= limit) return
            node.getChild(i)?.let { collectTexts(it, out, limit) }
        }
    }

    private fun collectIf(
        node: AccessibilityNodeInfo,
        out: MutableList<AccessibilityNodeInfo>,
        predicate: (AccessibilityNodeInfo) -> Boolean
    ) {
        if (out.size >= 200) return
        if (predicate(node)) out.add(node)
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { collectIf(it, out, predicate) }
        }
    }

    private fun findNode(
        node: AccessibilityNodeInfo,
        predicate: (AccessibilityNodeInfo) -> Boolean
    ): AccessibilityNodeInfo? {
        if (predicate(node)) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            findNode(child, predicate)?.let { return it }
        }
        return null
    }

    private fun click(target: AccessibilityNodeInfo): Boolean {
        var node: AccessibilityNodeInfo? = target
        var depth = 0
        while (node != null && depth <= 5) {
            if (node.isClickable && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
            node = node.parent
            depth++
        }
        val bounds = Rect().also { target.getBoundsInScreen(it) }
        if (bounds.isEmpty) return false
        val path = Path().apply { moveTo(bounds.exactCenterX(), bounds.exactCenterY()) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 100))
            .build()
        return dispatchGesture(gesture, null, null)
    }

    override fun onInterrupt() {
        Log.d(TAG, "Service interrupted")
    }
}
