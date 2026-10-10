package com.ridesmart.app.ui.screens

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import com.ridesmart.app.data.LocationData
import com.ridesmart.app.data.RideType
import com.ridesmart.app.service.GeocodingService
import com.ridesmart.app.service.RecentPlaces
import com.ridesmart.app.service.RideAccessibilityService
import com.ridesmart.app.ui.theme.*
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onCompare: (LocationData, LocationData, Set<RideType>) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var pickupText by remember { mutableStateOf("") }
    var dropText by remember { mutableStateOf("") }
    var pickup by remember { mutableStateOf<LocationData?>(null) }
    var drop by remember { mutableStateOf<LocationData?>(null) }

    var pickupSuggestions by remember { mutableStateOf<List<GeocodingService.PlaceSuggestion>>(emptyList()) }
    var dropSuggestions by remember { mutableStateOf<List<GeocodingService.PlaceSuggestion>>(emptyList()) }
    var pickupRecents by remember { mutableStateOf<List<LocationData>>(emptyList()) }
    var dropRecents by remember { mutableStateOf<List<LocationData>>(emptyList()) }
    var showPickupSuggestions by remember { mutableStateOf(false) }
    var showDropSuggestions by remember { mutableStateOf(false) }
    var pickupStatus by remember { mutableStateOf("") }
    var dropStatus by remember { mutableStateOf("") }
    var pickupStatusError by remember { mutableStateOf(false) }
    var dropStatusError by remember { mutableStateOf(false) }

    var pickupSearchJob by remember { mutableStateOf<Job?>(null) }
    var dropSearchJob by remember { mutableStateOf<Job?>(null) }

    // Ride type selection — Auto selected by default
    var selectedRideTypes by remember { mutableStateOf<Set<RideType>>(setOf(RideType.AUTO)) }

    val isServiceRunning by RideAccessibilityService.isRunning.collectAsState()
    val fusedLocation = remember { LocationServices.getFusedLocationProviderClient(context) }

    @SuppressLint("MissingPermission")
    fun loadCurrentLocation() {
        pickupStatus = "Getting current location..."
        pickupStatusError = false
        fusedLocation.lastLocation
            .addOnSuccessListener { loc ->
                if (loc != null) {
                    pickup = LocationData(loc.latitude, loc.longitude, "Current Location", "Current Location")
                    pickupText = "Current Location"
                    showPickupSuggestions = false
                    pickupSuggestions = emptyList()
                    pickupStatus = ""
                } else {
                    fusedLocation.getCurrentLocation(
                        Priority.PRIORITY_BALANCED_POWER_ACCURACY,
                        CancellationTokenSource().token
                    ).addOnSuccessListener { current ->
                        if (current != null) {
                            pickup = LocationData(
                                current.latitude,
                                current.longitude,
                                "Current Location",
                                "Current Location"
                            )
                            pickupText = "Current Location"
                            showPickupSuggestions = false
                            pickupSuggestions = emptyList()
                            pickupStatus = ""
                        } else {
                            pickupStatus = "Couldn't read your location. Turn on GPS and try again."
                            pickupStatusError = true
                        }
                    }.addOnFailureListener {
                        pickupStatus = "Couldn't read your location. Turn on GPS and try again."
                        pickupStatusError = true
                    }
                }
            }
            .addOnFailureListener {
                pickupStatus = "Couldn't read your location. Turn on GPS and try again."
                pickupStatusError = true
            }
    }

    val locationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        val granted = grants[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            grants[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        if (granted) {
            loadCurrentLocation()
        } else {
            pickupStatus = "Allow location access to use your current pickup."
            pickupStatusError = true
        }
    }

    fun useCurrentLocation() {
        val fine = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        if (fine || coarse) {
            loadCurrentLocation()
        } else {
            locationPermissionLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Background)
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            "Where to?",
            fontSize = 28.sp,
            fontWeight = FontWeight.ExtraBold,
            color = TextPrimary
        )
        Text(
            "Compare real prices across all ride apps",
            fontSize = 13.sp,
            color = TextSecondary,
            modifier = Modifier.padding(top = 4.dp)
        )

        Spacer(modifier = Modifier.height(24.dp))

        // Accessibility Service status
        if (!isServiceRunning) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF3E0)),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        "Setup Required",
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp,
                        color = Color(0xFFE65100)
                    )
                    Text(
                        "Enable RideSmart accessibility service to read real prices from ride apps.",
                        fontSize = 13.sp,
                        color = TextSecondary,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(
                        onClick = {
                            context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            })
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE65100)),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text("Enable Now", fontWeight = FontWeight.Bold)
                    }
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
        }

        // Pickup input with geocoding
        LocationField(
            label = "Pickup",
            value = pickupText,
            onValueChange = { text ->
                if (pickup != null && text == pickup?.name) {
                    pickupText = text
                } else {
                    pickupText = text
                    pickup = null
                    pickupSearchJob?.cancel()
                    showPickupSuggestions = false
                    pickupSuggestions = emptyList()
                    pickupRecents = RecentPlaces.match(context, text)
                    if (text.length >= 3) {
                        pickupStatus = "Searching..."
                        pickupStatusError = false
                        pickupSearchJob = scope.launch {
                            delay(400)
                            val outcome = GeocodingService.search(context, text)
                            if (!isActive) return@launch
                            pickupSuggestions = outcome.suggestions
                            showPickupSuggestions = outcome.suggestions.isNotEmpty()
                            when {
                                outcome.error != null -> {
                                    pickupStatus = outcome.error
                                    pickupStatusError = true
                                }
                                outcome.suggestions.isEmpty() && pickupRecents.isEmpty() -> {
                                    pickupStatus = "No places found"
                                    pickupStatusError = true
                                }
                                else -> {
                                    pickupStatus = ""
                                    pickupStatusError = false
                                }
                            }
                        }
                    } else {
                        pickupStatus = ""
                        pickupStatusError = false
                    }
                }
            },
            dotColor = Secondary,
            placeholder = "Search pickup location...",
            suggestions = if (showPickupSuggestions) pickupSuggestions else emptyList(),
            recents = if (pickup == null) pickupRecents else emptyList(),
            onRecentSelected = { loc ->
                pickupSearchJob?.cancel()
                pickup = loc
                pickupText = loc.name.ifBlank { loc.address }
                pickupRecents = emptyList()
                pickupSuggestions = emptyList()
                showPickupSuggestions = false
                pickupStatus = ""
                pickupStatusError = false
            },
            onSuggestionSelected = { suggestion ->
                pickupSearchJob?.cancel()
                showPickupSuggestions = false
                pickupSuggestions = emptyList()
                pickupRecents = emptyList()
                pickupStatus = "Loading place..."
                pickupStatusError = false
                pickupSearchJob = scope.launch {
                    val outcome = GeocodingService.resolve(context, suggestion)
                    if (!isActive) return@launch
                    val location = outcome.location
                    if (location != null) {
                        pickup = location
                        pickupText = location.name
                        pickupStatus = ""
                        RecentPlaces.add(context, location)
                    } else {
                        pickupStatus = outcome.error ?: "Couldn't load that place"
                        pickupStatusError = true
                    }
                }
            }
        )
        if (pickupStatus.isNotEmpty()) {
            Text(
                pickupStatus,
                fontSize = 12.sp,
                color = if (pickupStatusError) Danger else TextSecondary,
                modifier = Modifier.padding(top = 4.dp, start = 4.dp)
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Drop input with geocoding
        LocationField(
            label = "Drop",
            value = dropText,
            onValueChange = { text ->
                if (drop != null && text == drop?.name) {
                    dropText = text
                } else {
                    dropText = text
                    drop = null
                    dropSearchJob?.cancel()
                    showDropSuggestions = false
                    dropSuggestions = emptyList()
                    dropRecents = RecentPlaces.match(context, text)
                    if (text.length >= 3) {
                        dropStatus = "Searching..."
                        dropStatusError = false
                        dropSearchJob = scope.launch {
                            delay(400)
                            val outcome = GeocodingService.search(context, text)
                            if (!isActive) return@launch
                            dropSuggestions = outcome.suggestions
                            showDropSuggestions = outcome.suggestions.isNotEmpty()
                            when {
                                outcome.error != null -> {
                                    dropStatus = outcome.error
                                    dropStatusError = true
                                }
                                outcome.suggestions.isEmpty() && dropRecents.isEmpty() -> {
                                    dropStatus = "No places found"
                                    dropStatusError = true
                                }
                                else -> {
                                    dropStatus = ""
                                    dropStatusError = false
                                }
                            }
                        }
                    } else {
                        dropStatus = ""
                        dropStatusError = false
                    }
                }
            },
            dotColor = Danger,
            placeholder = "Search drop location...",
            suggestions = if (showDropSuggestions) dropSuggestions else emptyList(),
            recents = if (drop == null) dropRecents else emptyList(),
            onRecentSelected = { loc ->
                dropSearchJob?.cancel()
                drop = loc
                dropText = loc.name.ifBlank { loc.address }
                dropRecents = emptyList()
                dropSuggestions = emptyList()
                showDropSuggestions = false
                dropStatus = ""
                dropStatusError = false
            },
            onSuggestionSelected = { suggestion ->
                dropSearchJob?.cancel()
                showDropSuggestions = false
                dropSuggestions = emptyList()
                dropRecents = emptyList()
                dropStatus = "Loading place..."
                dropStatusError = false
                dropSearchJob = scope.launch {
                    val outcome = GeocodingService.resolve(context, suggestion)
                    if (!isActive) return@launch
                    val location = outcome.location
                    if (location != null) {
                        drop = location
                        dropText = location.name
                        dropStatus = ""
                        RecentPlaces.add(context, location)
                    } else {
                        dropStatus = outcome.error ?: "Couldn't load that place"
                        dropStatusError = true
                    }
                }
            }
        )
        if (dropStatus.isNotEmpty()) {
            Text(
                dropStatus,
                fontSize = 12.sp,
                color = if (dropStatusError) Danger else TextSecondary,
                modifier = Modifier.padding(top = 4.dp, start = 4.dp)
            )
        }

        // Use current location
        TextButton(onClick = { useCurrentLocation() }) {
            Icon(Icons.Default.MyLocation, contentDescription = null, tint = Primary, modifier = Modifier.size(16.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text("Use current location as pickup", color = Primary, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Ride type selection
        Text("Select ride types", fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = TextPrimary)
        Text("Choose one or more to compare", fontSize = 11.sp, color = TextTertiary)
        Spacer(modifier = Modifier.height(8.dp))

        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            data class RideChip(val type: RideType, val icon: String, val label: String)
            listOf(
                RideChip(RideType.AUTO, "🛺", "Auto"),
                RideChip(RideType.BIKE, "🏍", "Bike"),
                RideChip(RideType.MINI, "🚗", "Mini/Cab"),
                RideChip(RideType.SEDAN, "🚘", "Sedan"),
                RideChip(RideType.SUV, "🚙", "SUV"),
            ).forEach { chip ->
                val selected = selectedRideTypes.contains(chip.type)
                FilterChip(
                    selected = selected,
                    onClick = {
                        selectedRideTypes = if (selected) {
                            if (selectedRideTypes.size > 1) selectedRideTypes - chip.type else selectedRideTypes
                        } else {
                            selectedRideTypes + chip.type
                        }
                    },
                    label = {
                        Text(
                            "${chip.icon} ${chip.label}",
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                            fontSize = 12.sp
                        )
                    },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = Primary,
                        selectedLabelColor = Color.White
                    )
                )
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        // Compare button
        Button(
            onClick = {
                val p = pickup
                val d = drop
                if (p != null && d != null) {
                    onCompare(p, d, selectedRideTypes.toSet())
                }
            },
            modifier = Modifier.fillMaxWidth().height(56.dp),
            enabled = pickup != null && drop != null && selectedRideTypes.isNotEmpty(),

            colors = ButtonDefaults.buttonColors(containerColor = Primary),
            shape = RoundedCornerShape(14.dp),
            elevation = ButtonDefaults.buttonElevation(defaultElevation = 4.dp)
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Compare Rides", fontWeight = FontWeight.Bold, fontSize = 17.sp)
                Text(
                    if (isServiceRunning) "Live prices from Uber, Ola, Rapido & more" else "Enable accessibility for live prices",
                    fontSize = 11.sp,
                    color = Color.White.copy(alpha = 0.7f)
                )
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Supported apps info
        Text("Supported Apps", fontWeight = FontWeight.Bold, fontSize = 15.sp, color = TextPrimary)
        Spacer(modifier = Modifier.height(8.dp))

        listOf(
            Triple("Uber", UberColor, "Uber Auto, Moto, Uber Go, Go Sedan, Uber XL"),
            Triple("Ola", OlaColor, "Auto, Bike, Mini, Prime Sedan, Prime SUV"),
            Triple("Rapido", RapidoColor, "Bike, Auto, Cab Economy"),
            Triple("Namma Yatri", NammaYatriColor, "Auto, AC Cab"),
        ).forEach { (name, color, types) ->
            ProviderRow(name, color, types)
        }
    }
}

@Composable
private fun LocationField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    dotColor: Color,
    placeholder: String,
    suggestions: List<GeocodingService.PlaceSuggestion> = emptyList(),
    recents: List<LocationData> = emptyList(),
    onRecentSelected: (LocationData) -> Unit = {},
    onSuggestionSelected: (GeocodingService.PlaceSuggestion) -> Unit = {}
) {
    val dropdownOpen = suggestions.isNotEmpty() || recents.isNotEmpty()
    Column {
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = if (dropdownOpen)
                RoundedCornerShape(12.dp, 12.dp, 0.dp, 0.dp)
            else
                RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = Surface),
            elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
        ) {
            Row(
                modifier = Modifier.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(dotColor),
                    contentAlignment = Alignment.Center
                ) {
                    Text(label.first().toString(), color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(label, fontSize = 11.sp, color = TextTertiary)
                    OutlinedTextField(
                        value = value,
                        onValueChange = onValueChange,
                        placeholder = { Text(placeholder, fontSize = 14.sp, color = TextTertiary) },
                        modifier = Modifier.fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Color.Transparent,
                            unfocusedBorderColor = Color.Transparent,
                        ),
                        singleLine = true,
                        textStyle = LocalTextStyle.current.copy(fontSize = 15.sp)
                    )
                }
            }
        }

        // Location suggestions dropdown (recent places first, then Google results)
        if (dropdownOpen) {
            Card(
                modifier = Modifier
                    .fillMaxWidth(),
                shape = RoundedCornerShape(0.dp, 0.dp, 12.dp, 12.dp),
                colors = CardDefaults.cardColors(containerColor = Surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
            ) {
                Column {
                    recents.forEach { loc ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onRecentSelected(loc) }
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("🕘", fontSize = 14.sp)
                            Spacer(modifier = Modifier.width(10.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    loc.name.ifBlank { loc.address },
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 14.sp,
                                    color = TextPrimary
                                )
                                Text(
                                    "Recent",
                                    fontSize = 11.sp,
                                    color = TextTertiary
                                )
                            }
                        }
                        HorizontalDivider(
                            modifier = Modifier.padding(horizontal = 16.dp),
                            color = Border
                        )
                    }
                    suggestions.forEachIndexed { index, location ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onSuggestionSelected(location) }
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("📍", fontSize = 14.sp)
                            Spacer(modifier = Modifier.width(10.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    location.name,
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 14.sp,
                                    color = TextPrimary
                                )
                                Text(
                                    location.address.take(70) + if (location.address.length > 70) "..." else "",
                                    fontSize = 11.sp,
                                    color = TextTertiary,
                                    maxLines = 2
                                )
                            }
                        }
                        if (index < suggestions.lastIndex) {
                            HorizontalDivider(
                                modifier = Modifier.padding(horizontal = 16.dp),
                                color = Border
                            )
                        }
                    }
                    if (suggestions.isNotEmpty()) {
                        Text(
                            "Powered by Google",
                            fontSize = 11.sp,
                            color = TextTertiary,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ProviderRow(name: String, color: Color, types: String) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(containerColor = Surface)
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(12.dp)
                    .clip(CircleShape)
                    .background(color)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column {
                Text(name, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = TextPrimary)
                Text(types, fontSize = 11.sp, color = TextTertiary)
            }
        }
    }
}
