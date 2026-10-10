package com.ridesmart.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ridesmart.app.data.ProviderName
import com.ridesmart.app.data.RideQuote
import com.ridesmart.app.ui.theme.*

@Composable
fun RideCard(
    quote: RideQuote,
    badge: String? = null,
    badgeReason: String? = null,
    isSelected: Boolean = false,
    onSelect: () -> Unit = {},
    onBook: () -> Unit = {}
) {
    val providerColor = when (quote.provider) {
        ProviderName.UBER -> UberColor
        ProviderName.OLA -> OlaColor
        ProviderName.RAPIDO -> RapidoColor
        ProviderName.NAMMA_YATRI -> NammaYatriColor
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
            .then(
                if (isSelected) Modifier.border(2.dp, Primary, RoundedCornerShape(16.dp))
                else Modifier
            ),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        onClick = onSelect
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // Badge
            if (badge != null) {
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(Primary.copy(alpha = 0.1f))
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(badge, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Primary)
                    if (badgeReason != null) {
                        Text(
                            " — $badgeReason",
                            fontSize = 11.sp,
                            color = TextSecondary
                        )
                    }
                }
                Spacer(modifier = Modifier.height(10.dp))
            }

            // Provider + Price row
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Provider logo
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(providerColor),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = when (quote.provider) {
                            ProviderName.UBER -> "U"
                            ProviderName.OLA -> "O"
                            ProviderName.RAPIDO -> "R"
                            ProviderName.NAMMA_YATRI -> "NY"
                        },
                        color = if (quote.provider == ProviderName.RAPIDO) Color.Black else Color.White,
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 16.sp
                    )
                }

                Spacer(modifier = Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        quote.provider.displayName,
                        fontWeight = FontWeight.Bold,
                        fontSize = 17.sp,
                        color = TextPrimary
                    )
                    Text(
                        "${quote.rideType.icon} ${quote.vehicleName}",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = TextSecondary
                    )
                }

                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        "₹${quote.fare}",
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 22.sp,
                        color = TextPrimary
                    )
                    if (quote.isRealPrice) {
                        Text("Live price", fontSize = 10.sp, color = Secondary)
                    } else {
                        Text("Estimated", fontSize = 10.sp, color = TextTertiary)
                    }
                    if (isSelected) {
                        Text("✓ In race", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Primary)
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Details row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(Background)
                    .padding(10.dp),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                DetailItem("ETA", "${quote.etaMinutes} min")
                DetailItem("Distance", "${String.format("%.1f", quote.distance)} km")
                DetailItem("Duration", "${quote.duration} min")
                if (quote.surgeMultiplier > 1f) {
                    DetailItem("Surge", "${quote.surgeMultiplier}x", isAlert = true)
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Book button
            Button(
                onClick = onBook,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = providerColor),
                shape = RoundedCornerShape(10.dp)
            ) {
                Text(
                    "Book ${quote.provider.displayName}",
                    fontWeight = FontWeight.Bold,
                    color = if (quote.provider == ProviderName.RAPIDO) Color.Black else Color.White
                )
            }
        }
    }
}

@Composable
private fun DetailItem(label: String, value: String, isAlert: Boolean = false) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            label,
            fontSize = 11.sp,
            color = if (isAlert) Danger else TextTertiary
        )
        Text(
            value,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            color = if (isAlert) Danger else TextPrimary
        )
    }
}
