package com.leakproof.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.leakproof.app.data.local.SubscriptionEntity
import com.leakproof.app.insights.EmiMath
import com.leakproof.app.parser.RecurringKind
import com.leakproof.app.ui.theme.BrandPrimary
import com.leakproof.app.ui.theme.RailNach
import com.leakproof.app.ui.theme.RailSi
import com.leakproof.app.ui.theme.StatusPaused
import com.leakproof.app.util.CurrencyFormatter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.ceil

private const val MONTH_MS = 30.4375 * 24 * 3600 * 1000

fun kindColor(kind: RecurringKind): Color = when (kind) {
    RecurringKind.SUBSCRIPTION -> BrandPrimary
    RecurringKind.EMI -> RailSi
    RecurringKind.SIP -> RailNach
    RecurringKind.INSURANCE -> StatusPaused
}

private fun monthYear(ms: Long): String = SimpleDateFormat("MMM yyyy", Locale.ENGLISH).format(Date(ms))

/** Where the monthly money goes: subscriptions, EMIs, investments, insurance. */
@Composable
fun KindSplitCard(split: Map<RecurringKind, Double>) {
    val total = split.values.sum()
    if (total <= 0) return
    AppCard(modifier = Modifier.testTag("kind_split_card")) {
        Column(modifier = Modifier.padding(Spacing.card)) {
            Row(
                modifier = Modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(50)),
            ) {
                split.entries.sortedBy { it.key.ordinal }.forEach { (kind, amount) ->
                    Box(
                        modifier = Modifier
                            .weight((amount / total).toFloat().coerceAtLeast(0.02f))
                            .height(10.dp)
                            .background(kindColor(kind))
                    )
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
            split.entries.sortedBy { it.key.ordinal }.forEach { (kind, amount) ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(modifier = Modifier.size(10.dp).clip(CircleShape).background(kindColor(kind)))
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        kind.plural,
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        "${CurrencyFormatter.format(amount)} / month",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }
    }
}

/** Debt-free date, what is left to pay and when monthly outgo drops. */
@Composable
fun DebtFreeCard(subscriptions: List<SubscriptionEntity>, nowMs: Long = System.currentTimeMillis()) {
    val debtFree = EmiMath.debtFreeMs(subscriptions, nowMs)
    val freed = EmiMath.freedUp(subscriptions, nowMs).take(3)
    val burden = EmiMath.burden(subscriptions)
    val remaining = EmiMath.totalRemainingPayable(subscriptions)

    AppCard(modifier = Modifier.testTag("debt_free_card")) {
        Column(modifier = Modifier.padding(Spacing.card)) {
            if (debtFree != null) {
                val months = ceil((debtFree - nowMs) / MONTH_MS).toInt().coerceAtLeast(0)
                Text(
                    "Debt-free by ${monthYear(debtFree)}",
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 20.sp,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    "$months ${if (months == 1) "month" else "months"} to go · ${CurrencyFormatter.format(remaining)} still to pay",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                Text(
                    "Add how many instalments are left",
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 17.sp,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    "Open an EMI and add its instalments or end date to see your debt-free date.",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            burden?.let {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    "EMIs are ${(it * 100).toInt()}% of your recurring monthly outgo.",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (freed.isNotEmpty()) {
                Spacer(modifier = Modifier.height(12.dp))
                freed.forEach { f ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            monthYear(f.payoffMs),
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp,
                            color = RailSi,
                            modifier = Modifier.width(78.dp)
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                "${f.merchant} ends",
                                fontSize = 14.sp,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                "Frees ${CurrencyFormatter.format(f.monthlyAmount)} a month",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
}

/** EMI progress on the detail screen, with a what-if for an extra payment. */
@Composable
fun EmiProgressCard(sub: SubscriptionEntity) {
    val status = EmiMath.status(sub)
    var extraText by remember { mutableStateOf("") }
    val extra = extraText.toDoubleOrNull() ?: 0.0
    val result = if (extra > 0) EmiMath.prepayment(sub, extra) else null

    AppCard(modifier = Modifier.testTag("emi_progress_card")) {
        Column(modifier = Modifier.padding(20.dp)) {
            Text("Loan progress", fontWeight = FontWeight.ExtraBold, fontSize = 17.sp, color = MaterialTheme.colorScheme.onSurface)
            Spacer(modifier = Modifier.height(10.dp))

            status.progress?.let {
                LinearProgressIndicator(
                    progress = { it },
                    color = RailSi,
                    modifier = Modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(50))
                )
                Spacer(modifier = Modifier.height(6.dp))
            }
            val paidText = if (status.total != null && status.paid != null) {
                "${status.paid} of ${status.total} instalments paid"
            } else status.remaining?.let { "$it instalments left" } ?: "Instalments not set yet"
            Text(paidText, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)

            status.payoffMs?.let {
                Text("Last instalment: ${monthYear(it)}", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            status.remainingPayable?.takeIf { it > 0 }?.let {
                Text("${CurrencyFormatter.format(it)} still to pay", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            Spacer(modifier = Modifier.height(16.dp))
            Text("What if I pay extra now?", fontWeight = FontWeight.Bold, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface)
            Spacer(modifier = Modifier.height(6.dp))
            OutlinedTextField(
                value = extraText,
                onValueChange = { text -> extraText = text.filter { it.isDigit() }.take(9) },
                label = { Text("Extra payment (₹)") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
                modifier = Modifier.fillMaxWidth().testTag("prepay_input")
            )
            Spacer(modifier = Modifier.height(8.dp))
            when {
                sub.interestRatePct == null -> Text(
                    "Edit this loan and add its yearly interest rate to see what an extra payment would save.",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                result != null -> Text(
                    "About ${result.monthsSaved} instalments sooner and ${CurrencyFormatter.format(result.interestSaved)} less interest.",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = BrandPrimary,
                    modifier = Modifier.testTag("prepay_result")
                )
                else -> Unit
            }
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                "An estimate for a standard reducing-balance loan. Your lender's own schedule may differ, and this is not financial advice.",
                fontSize = 11.sp,
                lineHeight = 15.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
