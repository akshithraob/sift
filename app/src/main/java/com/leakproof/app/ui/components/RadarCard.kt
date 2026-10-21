package com.leakproof.app.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.leakproof.app.insights.RadarItem
import com.leakproof.app.insights.RenewalRadar
import com.leakproof.app.ui.theme.BrandPrimary
import com.leakproof.app.util.CurrencyFormatter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Yearly payments coming up in the next 90 days: the ones people forget until the money is gone. */
@Composable
fun AnnualRadarCard(items: List<RadarItem>, onOpen: (Long) -> Unit) {
    val format = SimpleDateFormat("d MMM", Locale.ENGLISH)
    AppCard(modifier = Modifier.testTag("annual_radar_card")) {
        Column(modifier = Modifier.padding(Spacing.card)) {
            Text(
                "${CurrencyFormatter.format(RenewalRadar.total(items))} across ${items.size} yearly renewal${if (items.size == 1) "" else "s"}",
                fontWeight = FontWeight.Bold, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                "Yearly charges are easy to forget. Decide now whether to keep them.",
                fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 2.dp)
            )
            Spacer(Modifier.height(10.dp))
            items.take(4).forEach { item ->
                Row(
                    modifier = Modifier.fillMaxWidth().clickable { onOpen(item.subscription.id) }.padding(vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(item.subscription.merchant, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface, maxLines = 1)
                        Text(
                            "${format.format(Date(item.dateMs))} · " + when {
                                item.daysAway <= 0 -> "today"
                                item.daysAway == 1 -> "tomorrow"
                                else -> "in ${item.daysAway} days"
                            },
                            fontSize = 12.sp,
                            color = if (item.daysAway <= RenewalRadar.HEADS_UP_DAYS) BrandPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Text(CurrencyFormatter.format(item.subscription.amount), fontWeight = FontWeight.Bold, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface)
                }
            }
            if (items.size > 4) {
                Text("+ ${items.size - 4} more", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
            }
        }
    }
}
