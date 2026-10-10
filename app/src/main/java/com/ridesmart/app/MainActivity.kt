package com.ridesmart.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.ridesmart.app.data.LocationData
import com.ridesmart.app.data.ProviderLoadState
import com.ridesmart.app.data.ProviderName
import com.ridesmart.app.data.ProviderStatus
import com.ridesmart.app.data.RideType
import com.ridesmart.app.service.DeepLinks
import com.ridesmart.app.service.PriceScraper
import com.ridesmart.app.service.RaceBooker
import com.ridesmart.app.service.RideAccessibilityService
import com.ridesmart.app.service.SessionStore
import com.ridesmart.app.ui.screens.HomeScreen
import com.ridesmart.app.ui.screens.ResultsScreen
import com.ridesmart.app.ui.theme.Background
import com.ridesmart.app.ui.theme.RideSmartTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            RideSmartTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = Background
                ) {
                    RideSmartApp()
                }
            }
        }
    }
}

enum class Screen {
    HOME,
    RESULTS
}

@Composable
fun RideSmartApp() {
    val context = LocalContext.current
    val raceBooker = remember { RaceBooker(context) }

    // rememberSaveable so an entered route and the results screen survive the Activity being
    // restarted (e.g. an aggressive OEM killing RideSmart while a ride app is in front).
    var currentScreen by rememberSaveable { mutableStateOf(Screen.HOME) }
    var pickup by rememberSaveable { mutableStateOf<LocationData?>(null) }
    var drop by rememberSaveable { mutableStateOf<LocationData?>(null) }
    var selectedRideTypes by rememberSaveable { mutableStateOf<Set<RideType>>(setOf(RideType.AUTO)) }

    // Belt-and-suspenders: if the process was hard-killed and in-memory state was lost,
    // restore the last route AND the captured prices from disk, so reopening RideSmart
    // comes right back to the results with prices intact.
    LaunchedEffect(Unit) {
        runCatching {
            RideAccessibilityService.restorePrices(context)
            if (pickup == null && drop == null) {
                SessionStore.load(context)?.let { s ->
                    pickup = s.pickup
                    drop = s.drop
                    selectedRideTypes = s.rideTypes
                    currentScreen = Screen.RESULTS
                }
            }
        }
    }

    // When the user brings RideSmart back to the front during a scrape, stop the scrape so it
    // doesn't keep yanking them into ride apps while they read results or scroll. (During a
    // scrape RideSmart is only foregrounded by the user — the scrape itself returns only at the
    // very end — so an ON_RESUME here means the user came back.)
    val lifecycleOwner = context as? androidx.lifecycle.LifecycleOwner
    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME &&
                RideAccessibilityService.scrapeStatus.value ==
                RideAccessibilityService.ScrapeStatus.SCRAPING
            ) {
                RideAccessibilityService.cancelAutoScrape()
            }
        }
        lifecycleOwner?.lifecycle?.addObserver(observer)
        onDispose { lifecycleOwner?.lifecycle?.removeObserver(observer) }
    }

    val scrapeStatus by RideAccessibilityService.scrapeStatus.collectAsState()
    val currentProvider by RideAccessibilityService.currentProvider.collectAsState()
    val isAccessibilityRunning by RideAccessibilityService.isRunning.collectAsState()
    val rawScraped by RideAccessibilityService.scrapedPrices.collectAsState()

    // Show every ride app in the status list — never hide one over a flaky install check.
    val installedProviders = ProviderName.entries

    // Only real, captured prices are ever shown — no estimates anywhere.
    val liveQuotes = remember(rawScraped, pickup, drop, selectedRideTypes) {
        val p = pickup
        val d = drop
        if (p != null && d != null)
            PriceScraper.quotesFrom(rawScraped, p, d).filter { it.rideType in selectedRideTypes }
        else emptyList()
    }

    val rawScreenText by RideAccessibilityService.screenText.collectAsState()

    // Save captured prices shortly after they change (debounced) so they persist a kill.
    LaunchedEffect(rawScraped) {
        if (rawScraped.isNotEmpty()) {
            kotlinx.coroutines.delay(1200)
            runCatching { RideAccessibilityService.persistPrices(context) }
        }
    }

    // Diagnostics: captured prices, plus the raw on-screen text for any app that captured
    // nothing — so we can see whether that app even exposes its prices as readable text.
    val diagnostics = remember(rawScraped, rawScreenText) {
        buildList {
            rawScraped.forEach { (pkg, sp) ->
                val name = ProviderName.fromPackage(pkg)?.displayName ?: pkg
                sp.prices.forEach { add("$name · ${it.vehicleName} = ₹${it.fare} (${it.rideType.label})") }
            }
            rawScreenText.forEach { (pkg, text) ->
                val name = ProviderName.fromPackage(pkg)?.displayName ?: pkg
                if (rawScraped[pkg]?.prices.isNullOrEmpty() && text.isNotBlank()) {
                    val hasRupee = if ("₹" in text) "has ₹" else "NO ₹"
                    add("$name RAW ($hasRupee): " + text.replace("\n", " · ").take(400))
                }
            }
        }
    }

    val providerStatuses = remember(rawScraped, scrapeStatus, currentProvider, selectedRideTypes) {
        installedProviders.map { provider ->
            val hasLive = rawScraped[provider.packageName]?.prices
                ?.any { it.rideType in selectedRideTypes } == true
            val state = when {
                hasLive -> ProviderLoadState.OK
                scrapeStatus == RideAccessibilityService.ScrapeStatus.SCRAPING &&
                    currentProvider == provider -> ProviderLoadState.READING
                scrapeStatus == RideAccessibilityService.ScrapeStatus.SCRAPING -> ProviderLoadState.PENDING
                else -> ProviderLoadState.MISSING
            }
            ProviderStatus(provider, state)
        }
    }

    when (currentScreen) {
        Screen.HOME -> {
            HomeScreen(
                onCompare = { p, d, rideTypes ->
                    pickup = p
                    drop = d
                    selectedRideTypes = rideTypes
                    currentScreen = Screen.RESULTS
                    SessionStore.save(context, p, d, rideTypes)

                    RideAccessibilityService.cancelAutoScrape()
                    RideAccessibilityService.clearAllPrices()
                    RideAccessibilityService.clearPersistedPrices(context)
                    if (isAccessibilityRunning) {
                        RideAccessibilityService.requestAutoScrape(p, d)
                    }
                }
            )
        }
        Screen.RESULTS -> {
            val p = pickup ?: return
            val d = drop ?: return
            ResultsScreen(
                pickup = p,
                drop = d,
                quotes = liveQuotes,
                providerStatuses = providerStatuses,
                diagnostics = diagnostics,
                accessibilityOn = isAccessibilityRunning,
                scrapeStatus = scrapeStatus,
                currentProvider = currentProvider,
                raceBooker = raceBooker,
                onBack = {
                    currentScreen = Screen.HOME
                    RideAccessibilityService.cancelAutoScrape()
                    SessionStore.clear(context)
                    RideAccessibilityService.clearPersistedPrices(context)
                },
                onRetryAll = {
                    RideAccessibilityService.cancelAutoScrape()
                    RideAccessibilityService.requestAutoScrape(p, d)
                },
                onOpenProvider = { provider ->
                    // One-tap retry for a stubborn app — reuses the same pickup/drop.
                    RideAccessibilityService.startScraping(
                        RideAccessibilityService.ScrapeAction(provider.packageName)
                    )
                    DeepLinks.openWithRoute(context, provider, p, d)
                },
                onBookSingle = { quote ->
                    raceBooker.startRaceBooking(listOf(quote), p, d)
                }
            )
        }
    }
}
