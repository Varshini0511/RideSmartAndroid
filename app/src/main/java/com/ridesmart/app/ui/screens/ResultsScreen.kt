package com.ridesmart.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ridesmart.app.data.*
import com.ridesmart.app.service.RaceBooker
import com.ridesmart.app.service.RideAccessibilityService
import com.ridesmart.app.ui.components.RideCard
import com.ridesmart.app.ui.theme.*
import com.ridesmart.app.util.SmartComparator

private const val MAX_RACE = 3

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ResultsScreen(
    pickup: LocationData,
    drop: LocationData,
    quotes: List<RideQuote>,
    providerStatuses: List<ProviderStatus>,
    diagnostics: List<String>,
    accessibilityOn: Boolean,
    scrapeStatus: RideAccessibilityService.ScrapeStatus,
    currentProvider: ProviderName?,
    raceBooker: RaceBooker,
    onBack: () -> Unit,
    onRetryAll: () -> Unit,
    onOpenProvider: (ProviderName) -> Unit,
    onBookSingle: (RideQuote) -> Unit
) {
    val selectedForRace = remember { mutableStateListOf<RideQuote>() }
    var showRaceDialog by remember { mutableStateOf(false) }
    val racingState by raceBooker.racingState.collectAsState()
    val isScraping = scrapeStatus == RideAccessibilityService.ScrapeStatus.SCRAPING

    val categorySuggestions = remember(quotes) { SmartComparator.suggestByCategory(quotes) }
    val suggestedQuotes = remember(categorySuggestions) {
        categorySuggestions.flatMap { c -> c.suggestions.map { it.quote } }
    }
    val savings = remember(quotes) { SmartComparator.calculateSavings(quotes) }
    val groupedQuotes = remember(quotes) {
        quotes.groupBy { it.rideType }.toSortedMap(compareBy { it.ordinal })
    }

    LaunchedEffect(suggestedQuotes) {
        selectedForRace.retainAll { it in suggestedQuotes }
    }

    fun toggleRace(quote: RideQuote) {
        when {
            quote in selectedForRace -> selectedForRace.remove(quote)
            selectedForRace.any { it.provider == quote.provider } -> {
                selectedForRace.removeAll { it.provider == quote.provider }
                selectedForRace.add(quote)
            }
            selectedForRace.size < MAX_RACE -> selectedForRace.add(quote)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Compare Rides", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (!isScraping && accessibilityOn) {
                        TextButton(onClick = onRetryAll) { Text("Refresh") }
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
        ) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = Surface)
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        RoutePoint(pickup.name.ifBlank { pickup.address }, Secondary)
                        Box(
                            modifier = Modifier
                                .padding(start = 5.dp)
                                .width(2.dp)
                                .height(14.dp)
                                .background(Border)
                        )
                        RoutePoint(drop.name.ifBlank { drop.address }, Danger)
                    }
                }
            }

            // Per-app live status. Tap a failed app to retry it (same pickup/drop).
            if (accessibilityOn) {
                item {
                    ProviderStatusRow(
                        statuses = providerStatuses,
                        onTap = onOpenProvider
                    )
                }
            } else {
                item { AccessibilityOffCard() }
            }

            if (savings > 0) {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        shape = RoundedCornerShape(10.dp),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFFE6F4EA))
                    ) {
                        Text(
                            "You save up to ₹$savings by comparing!",
                            modifier = Modifier.padding(12.dp).fillMaxWidth(),
                            textAlign = TextAlign.Center,
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp,
                            color = Color(0xFF137333)
                        )
                    }
                }
            }

            if (quotes.isEmpty()) {
                item { EmptyState(isScraping, currentProvider) }
            }

            if (categorySuggestions.isNotEmpty()) {
                item {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("Smart Suggestions", fontWeight = FontWeight.Bold, fontSize = 16.sp, color = TextPrimary)
                    Text(
                        "Tap up to $MAX_RACE (one per app) to race-book — first driver wins, others get cancelled",
                        fontSize = 11.sp,
                        color = TextTertiary
                    )
                }

                categorySuggestions.forEach { category ->
                    item(key = "sugg-header-${category.rideType}") {
                        CategoryHeader(category.rideType, subtitle = null)
                    }
                    items(
                        category.suggestions,
                        key = { "sugg-${it.quote.provider}-${it.quote.rideType}" }
                    ) { suggestion ->
                        RideCard(
                            quote = suggestion.quote,
                            badge = suggestion.badge,
                            badgeReason = suggestion.reason,
                            isSelected = suggestion.quote in selectedForRace,
                            onSelect = { toggleRace(suggestion.quote) },
                            onBook = { onBookSingle(suggestion.quote) }
                        )
                    }
                }
            }

            if (selectedForRace.size >= 2) {
                item {
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(
                        onClick = { showRaceDialog = true },
                        modifier = Modifier.fillMaxWidth().height(52.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Danger),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text(
                            "Race Book ${selectedForRace.size} Rides — First Driver Wins",
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                }
            }

            if (groupedQuotes.isNotEmpty()) {
                item {
                    Spacer(modifier = Modifier.height(12.dp))
                    HorizontalDivider(color = Border)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("All live prices", fontWeight = FontWeight.Bold, fontSize = 16.sp, color = TextPrimary)
                    Text("Sorted by price, then wait time", fontSize = 11.sp, color = TextTertiary)
                }

                groupedQuotes.forEach { (type, options) ->
                    item(key = "all-header-$type") {
                        CategoryHeader(type, subtitle = "${options.size} option${if (options.size == 1) "" else "s"}")
                    }
                    items(options, key = { "all-${it.provider}-${it.rideType}" }) { quote ->
                        RideCard(
                            quote = quote,
                            onBook = { onBookSingle(quote) }
                        )
                    }
                }
            }

            item {
                Spacer(modifier = Modifier.height(16.dp))
                DiagnosticsPanel(diagnostics)
            }

            item { Spacer(modifier = Modifier.height(80.dp)) }
        }
    }

    if (showRaceDialog) {
        AlertDialog(
            onDismissRequest = { showRaceDialog = false },
            title = { Text("Race Booking", fontWeight = FontWeight.Bold) },
            text = {
                Column {
                    Text("RideSmart will request these rides one by one:")
                    Spacer(modifier = Modifier.height(8.dp))
                    selectedForRace.forEach { q ->
                        Text(
                            "• ${q.provider.displayName} ${q.vehicleName} — ₹${q.fare}",
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        "The first app to assign a driver wins. The other requests are cancelled " +
                            "automatically. Keep notifications on for these apps.",
                        fontSize = 13.sp,
                        color = TextSecondary
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        showRaceDialog = false
                        raceBooker.startRaceBooking(selectedForRace.toList(), pickup, drop)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Danger)
                ) {
                    Text("Start Race!", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showRaceDialog = false }) { Text("Cancel") }
            }
        )
    }

    racingState?.let { state ->
        RaceBookingOverlay(
            state = state,
            onAbort = { raceBooker.abortRace() },
            onDone = {
                raceBooker.dismiss()
                selectedForRace.clear()
            }
        )
    }
}

@Composable
private fun ProviderStatusRow(statuses: List<ProviderStatus>, onTap: (ProviderName) -> Unit) {
    Column(modifier = Modifier.padding(vertical = 8.dp)) {
        Text("Live price status", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = TextSecondary)
        Spacer(modifier = Modifier.height(6.dp))
        statuses.forEach { status ->
            val (icon, label, tappable) = when (status.state) {
                ProviderLoadState.OK -> Triple("✅", "live price captured", false)
                ProviderLoadState.READING -> Triple("🔄", "reading now…", false)
                ProviderLoadState.PENDING -> Triple("⏳", "waiting…", false)
                ProviderLoadState.MISSING -> Triple("⚠️", "no live price — tap to open", true)
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .then(if (tappable) Modifier.clickable { onTap(status.provider) } else Modifier)
                    .padding(vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(icon, fontSize = 14.sp)
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    status.provider.displayName,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = TextPrimary
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    label,
                    fontSize = 12.sp,
                    color = if (status.state == ProviderLoadState.MISSING) Primary else TextTertiary,
                    fontWeight = if (tappable) FontWeight.SemiBold else FontWeight.Normal
                )
            }
        }
    }
}

@Composable
private fun EmptyState(isScraping: Boolean, currentProvider: ProviderName?) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        if (isScraping) {
            CircularProgressIndicator(color = Primary)
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                currentProvider?.let { "Reading live price from ${it.displayName}…" }
                    ?: "Reading live prices…",
                fontSize = 14.sp,
                color = TextSecondary
            )
        } else {
            Text("No live prices yet", fontWeight = FontWeight.Bold, fontSize = 16.sp, color = TextPrimary)
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                "Tap an app marked \"no live price\" above to open it — the price locks in automatically.",
                fontSize = 13.sp,
                color = TextSecondary,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun AccessibilityOffCard() {
    Card(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF3E0))
    ) {
        Text(
            "Live prices need the RideSmart accessibility service. Enable it in Settings → " +
                "Accessibility → RideSmart, then come back and refresh.",
            modifier = Modifier.padding(12.dp),
            fontSize = 12.sp,
            color = Color(0xFFE65100)
        )
    }
}

@Composable
private fun DiagnosticsPanel(diagnostics: List<String>) {
    var expanded by remember { mutableStateOf(false) }
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(containerColor = Surface)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Diagnostics — what RideSmart read (${diagnostics.size})",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = TextSecondary,
                    modifier = Modifier.weight(1f)
                )
                Text(if (expanded) "Hide" else "Show", fontSize = 12.sp, color = Primary)
            }
            if (expanded) {
                Spacer(modifier = Modifier.height(8.dp))
                if (diagnostics.isEmpty()) {
                    Text(
                        "Nothing captured yet. Open an app's ride-list screen; this fills in live.",
                        fontSize = 11.sp,
                        color = TextTertiary
                    )
                } else {
                    diagnostics.forEach {
                        Text("• $it", fontSize = 11.sp, color = TextPrimary, modifier = Modifier.padding(vertical = 1.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun CategoryHeader(type: RideType, subtitle: String?) {
    Row(
        modifier = Modifier.padding(top = 12.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("${type.icon} ${type.label}", fontWeight = FontWeight.Bold, fontSize = 14.sp, color = Primary)
        if (subtitle != null) {
            Text("  ·  $subtitle", fontSize = 12.sp, color = TextTertiary)
        }
    }
}

@Composable
private fun RoutePoint(text: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(color)
        )
        Spacer(modifier = Modifier.width(10.dp))
        Text(text, fontSize = 14.sp, color = TextPrimary, maxLines = 1)
    }
}

@Composable
private fun RaceBookingOverlay(state: RacingState, onAbort: () -> Unit, onDone: () -> Unit) {
    AlertDialog(
        onDismissRequest = { if (state.finished) onDone() },
        title = {
            Text(
                when {
                    state.winner != null -> "Ride Booked!"
                    state.finished -> "Race Ended"
                    else -> "Race Booking..."
                },
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column {
                state.selectedQuotes.forEach { quote ->
                    val status = state.bookingStatus[quote.provider] ?: BookingStatus.WAITING
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            when (status) {
                                BookingStatus.WAITING -> "⏳"
                                BookingStatus.BOOKING -> "🔄"
                                BookingStatus.SEARCHING -> "🔍"
                                BookingStatus.CONFIRMED -> "✅"
                                BookingStatus.CANCELLING -> "🔄"
                                BookingStatus.CANCELLED -> "❌"
                                BookingStatus.FAILED -> "⚠️"
                            },
                            fontSize = 18.sp
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                "${quote.provider.displayName} ${quote.vehicleName}",
                                fontWeight = FontWeight.SemiBold
                            )
                            Text("₹${quote.fare}", fontSize = 13.sp, color = TextSecondary)
                            state.notes[quote.provider]?.let {
                                Text(it, fontSize = 11.sp, color = Danger)
                            }
                        }
                        Text(
                            when (status) {
                                BookingStatus.WAITING -> "Queued"
                                BookingStatus.BOOKING -> "Requesting..."
                                BookingStatus.SEARCHING -> "Finding driver"
                                BookingStatus.CONFIRMED -> "CONFIRMED"
                                BookingStatus.CANCELLING -> "Cancelling..."
                                BookingStatus.CANCELLED -> "Cancelled"
                                BookingStatus.FAILED -> "Failed"
                            },
                            fontSize = 12.sp,
                            fontWeight = if (status == BookingStatus.CONFIRMED) FontWeight.Bold else FontWeight.Normal,
                            color = when (status) {
                                BookingStatus.CONFIRMED -> Secondary
                                BookingStatus.CANCELLED, BookingStatus.FAILED -> Danger
                                else -> TextSecondary
                            }
                        )
                    }
                }

                state.winner?.let { winner ->
                    Spacer(modifier = Modifier.height(12.dp))
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFFE6F4EA)),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text(
                            "${winner.provider.displayName} ${winner.vehicleName} confirmed — ₹${winner.fare}",
                            modifier = Modifier.padding(12.dp).fillMaxWidth(),
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF137333),
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }
        },
        confirmButton = {
            if (state.finished) {
                Button(onClick = onDone) { Text("Done", fontWeight = FontWeight.Bold) }
            }
        },
        dismissButton = {
            if (!state.finished) {
                TextButton(onClick = onAbort) { Text("Cancel All") }
            }
        }
    )
}
