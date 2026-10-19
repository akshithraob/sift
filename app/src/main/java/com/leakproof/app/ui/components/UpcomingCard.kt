package com.leakproof.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.leakproof.app.data.local.SubscriptionEntity
import com.leakproof.app.insights.Schedule
import com.leakproof.app.insights.Trials
import com.leakproof.app.ui.theme.BrandPrimary
import com.leakproof.app.util.CurrencyFormatter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** The next few charges for one payment, as a small timeline. */
@Composable
fun UpcomingPaymentsCard(sub: SubscriptionEntity, count: Int = 6, nowMs: Long = System.currentTimeMillis()) {
    val dates = remember(sub, nowMs) {
        Schedule.occurrences(sub, nowMs - 12L * 3600 * 1000, nowMs + 3L * 366 * 24 * 3600 * 1000).take(count)
    }
    if (dates.isEmpty()) return
    val format = remember { SimpleDateFormat("EEE, d MMM yyyy", Locale.ENGLISH) }

    AppCard(modifier = Modifier.testTag("upcoming_payments_card")) {
        Column(modifier = Modifier.padding(20.dp)) {
            Text("Next payments", fontWeight = FontWeight.ExtraBold, fontSize = 17.sp, color = MaterialTheme.colorScheme.onSurface)
            Spacer(modifier = Modifier.height(12.dp))
            dates.forEachIndexed { index, occ ->
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                    // The timeline rail: a dot per payment joined by a line
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(18.dp)) {
                        Box(
                            modifier = Modifier
                                .padding(top = 5.dp)
                                .size(if (index == 0) 12.dp else 9.dp)
                                .clip(CircleShape)
                                .background(
                                    if (index == 0) BrandPrimary else MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
                                )
                        )
                    }
                    Column(modifier = Modifier.weight(1f).padding(bottom = 14.dp)) {
                        val days = Trials.daysUntil(occ.dateMs, nowMs)
                        Text(
                            format.format(Date(occ.dateMs)),
                            fontWeight = if (index == 0) FontWeight.Bold else FontWeight.Medium,
                            fontSize = 14.sp,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            when {
                                days <= 0 -> "Today"
                                days == 1 -> "Tomorrow"
                                days < 60 -> "In $days days"
                                else -> "In ${days / 30} months"
                            },
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Text(
                        CurrencyFormatter.format(occ.subscription.amount),
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp,
                        color = if (index == 0) BrandPrimary else MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }
    }
}
