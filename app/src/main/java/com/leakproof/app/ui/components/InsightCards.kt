package com.leakproof.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.HourglassBottom
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.leakproof.app.data.local.SubscriptionEntity
import com.leakproof.app.insights.BudgetLevel
import com.leakproof.app.insights.BudgetStatus
import com.leakproof.app.insights.MandateRisk
import com.leakproof.app.insights.OverlapInsight
import com.leakproof.app.insights.Trials
import com.leakproof.app.ui.theme.BrandPrimary
import com.leakproof.app.ui.theme.StatusCancelled
import com.leakproof.app.ui.theme.StatusPaused
import com.leakproof.app.util.CurrencyFormatter

/** Dashboard "heads up" cards: budget, trials ending, overlapping services, risky mandate limits. */
fun LazyListScope.insightItems(
    budget: BudgetStatus,
    trials: List<SubscriptionEntity>,
    overlaps: List<OverlapInsight>,
    risks: List<MandateRisk>
) {
    if (budget.level != BudgetLevel.NONE) {
        item(key = "insight_budget") {
            Spacer(modifier = Modifier.height(12.dp))
            BudgetCard(budget)
        }
    }

    trials.filter { Trials.daysUntil(it.trialEndEpochMs ?: 0L) <= 14 }.forEach { sub ->
        item(key = "insight_trial_${sub.id}") {
            Spacer(modifier = Modifier.height(12.dp))
            val days = Trials.daysUntil(sub.trialEndEpochMs ?: 0L)
            InsightCard(
                icon = Icons.Default.HourglassBottom,
                tint = if (days <= 2) StatusCancelled else StatusPaused,
                title = "${sub.merchant}: ${Trials.label(days).lowercase().replaceFirstChar { it.uppercase() }}",
                body = "Cancel before it ends to avoid being charged ${CurrencyFormatter.format(sub.amount)}.",
                tag = "insight_trial_${sub.id}"
            )
        }
    }

    overlaps.forEach { insight ->
        item(key = "insight_overlap_${insight.subscriptionIds.joinToString("-")}") {
            Spacer(modifier = Modifier.height(12.dp))
            InsightCard(
                icon = Icons.Default.ContentCopy,
                tint = StatusPaused,
                title = insight.title,
                body = "${insight.merchants.joinToString(" + ")} together cost " +
                    "${CurrencyFormatter.format(insight.monthlyTotal)}/month. Keep the one you use most?",
                tag = "insight_overlap_${insight.label}"
            )
        }
    }

    risks.forEach { risk ->
        item(key = "insight_risk_${risk.subscriptionId}") {
            Spacer(modifier = Modifier.height(12.dp))
            InsightCard(
                icon = Icons.Default.Warning,
                tint = StatusCancelled,
                title = "Mandate limit: ${risk.merchant}",
                body = risk.message,
                tag = "insight_risk_${risk.subscriptionId}"
            )
        }
    }
}

@Composable
private fun InsightCard(icon: ImageVector, tint: Color, title: String, body: String, tag: String) {
    Card(
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = tint.copy(alpha = 0.10f)),
        modifier = Modifier.fillMaxWidth().testTag(tag)
    ) {
        Row(modifier = Modifier.padding(14.dp), verticalAlignment = Alignment.Top) {
            Icon(imageVector = icon, contentDescription = null, tint = tint, modifier = Modifier.size(22.dp))
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = body,
                    fontSize = 12.sp,
                    lineHeight = 17.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun BudgetCard(budget: BudgetStatus) {
    val tint = when (budget.level) {
        BudgetLevel.OVER -> StatusCancelled
        BudgetLevel.NEAR -> StatusPaused
        else -> BrandPrimary
    }
    Card(
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth().testTag("insight_budget")
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.AccountBalanceWallet,
                    contentDescription = null,
                    tint = tint,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Monthly budget",
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            LinearProgressIndicator(
                progress = { budget.ratio.coerceIn(0f, 1f) },
                color = tint,
                modifier = Modifier.fillMaxWidth().height(8.dp)
            )
            Spacer(modifier = Modifier.height(6.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(
                    text = "${CurrencyFormatter.format(budget.spent)} of ${CurrencyFormatter.format(budget.budget)}",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = if (budget.level == BudgetLevel.OVER) {
                        "${CurrencyFormatter.format(-budget.remaining)} over budget"
                    } else {
                        "${CurrencyFormatter.format(budget.remaining)} left"
                    },
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = tint
                )
            }
        }
    }
}
