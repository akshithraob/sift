package com.leakproof.app.ui.screens

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.leakproof.app.insights.Budget
import com.leakproof.app.insights.LeakFinder
import com.leakproof.app.insights.YearReview
import com.leakproof.app.insights.monthlyCost
import com.leakproof.app.parser.RecurringKind
import com.leakproof.app.ui.components.AnimatedAmount
import com.leakproof.app.ui.components.AppCard
import com.leakproof.app.ui.components.Spacing
import com.leakproof.app.ui.theme.BrandPrimary
import com.leakproof.app.ui.viewmodel.LeakproofViewModel
import com.leakproof.app.util.CurrencyFormatter
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/** A plain-language summary of what the user's recurring payments add up to. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun YearInReviewScreen(viewModel: LeakproofViewModel, onNavigateBack: () -> Unit) {
    val context = LocalContext.current
    val subscriptions by viewModel.allSubscriptions.collectAsState()
    val monthlyBudget by viewModel.monthlyBudget.collectAsState()
    val review = remember(subscriptions, monthlyBudget) {
        val active = subscriptions.filter { it.status == "ACTIVE" }
        val budget = Budget.evaluate(active.sumOf { monthlyCost(it.amount, it.frequency) }, monthlyBudget)
        YearReview.compute(subscriptions, LeakFinder.find(subscriptions, budget))
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("Your year in review", fontWeight = FontWeight.Bold, fontSize = 18.sp) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack, modifier = Modifier.testTag("year_back_button")) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.screen)
                .testTag("year_in_review")
        ) {
            if (review.activeCount == 0) {
                Spacer(Modifier.height(40.dp))
                Text("Nothing to review yet", fontWeight = FontWeight.ExtraBold, fontSize = 22.sp, color = MaterialTheme.colorScheme.onBackground)
                Text(
                    "Add your recurring payments and this page will show what they cost you over a year.",
                    fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp)
                )
                return@Column
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(28.dp))
                    .background(Brush.linearGradient(listOf(Color(0xFF00BFA5), Color(0xFF00695C))))
            ) {
                Column(modifier = Modifier.padding(24.dp)) {
                    Text("A YEAR OF RECURRING PAYMENTS", fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp, color = Color.White.copy(alpha = 0.8f))
                    AnimatedAmount(amount = review.yearlyTotal, style = MaterialTheme.typography.displayLarge, color = Color.White)
                    Text(
                        "across ${review.activeCount} payment${if (review.activeCount == 1) "" else "s"}, about ${CurrencyFormatter.format(review.perDay)} a day",
                        fontSize = 13.sp, color = Color.White.copy(alpha = 0.9f)
                    )
                }
            }

            Spacer(Modifier.height(14.dp))
            AppCard {
                Column(modifier = Modifier.padding(Spacing.card)) {
                    Text("Where it goes", fontWeight = FontWeight.Bold, fontSize = 15.sp, color = MaterialTheme.colorScheme.onSurface)
                    Spacer(Modifier.height(10.dp))
                    val max = review.byKindYearly.values.maxOrNull() ?: 1.0
                    review.byKindYearly.entries.sortedByDescending { it.value }.forEach { (kind, value) ->
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 5.dp)) {
                            Text(kind.plural, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.width(112.dp))
                            Box(Modifier.weight(1f).height(8.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant)) {
                                Box(Modifier.fillMaxWidth((value / max).toFloat().coerceIn(0.04f, 1f)).height(8.dp).clip(CircleShape).background(BrandPrimary))
                            }
                            Text(
                                CurrencyFormatter.format(value), fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.padding(start = 10.dp)
                            )
                        }
                    }
                }
            }

            review.priciest?.let { sub ->
                Spacer(Modifier.height(12.dp))
                StatCard(
                    "Your priciest subscription",
                    sub.merchant,
                    "${CurrencyFormatter.format(review.priciestYearly)} a year. Worth it? Check how often you really use it."
                )
            }

            Spacer(Modifier.height(12.dp))
            if (review.cancelledCount > 0) {
                StatCard(
                    "What you cancelled",
                    CurrencyFormatter.format(review.cancelledYearlySaved) + " a year",
                    "${review.cancelledCount} subscription${if (review.cancelledCount == 1) "" else "s"} you stopped paying for."
                )
            } else {
                StatCard("What you cancelled", "Nothing yet", "Every leak on the home screen is a chance to save. Cancelled items count here.")
            }

            review.busiestMonth?.let { month ->
                val name = SimpleDateFormat("MMMM", Locale.ENGLISH).format(Calendar.getInstance().apply { set(month.year, month.month, 1) }.time)
                Spacer(Modifier.height(12.dp))
                StatCard(
                    "Your busiest month ahead",
                    "$name · ${CurrencyFormatter.format(month.total)}",
                    "${CurrencyFormatter.format(review.next12MonthsOut)} will go out over the next 12 months."
                )
            }

            Spacer(Modifier.height(12.dp))
            StatCard(
                "Leaks found",
                if (review.leaksFound == 0) "None" else review.leaksFound.toString(),
                if (review.leaksFound == 0) "No overlaps, risky limits or stale charges right now." else "Overlaps, risky limits and stale charges. See the home screen."
            )

            Spacer(Modifier.height(18.dp))
            Button(
                onClick = {
                    val send = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, summaryText(review))
                    }
                    context.startActivity(Intent.createChooser(send, "Share summary"))
                },
                colors = ButtonDefaults.buttonColors(containerColor = BrandPrimary, contentColor = Color.White),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth().height(52.dp).testTag("year_share_button")
            ) {
                Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.height(20.dp))
                Spacer(Modifier.width(10.dp))
                Text("Share a summary", fontWeight = FontWeight.ExtraBold)
            }
            Text(
                "The summary has totals only. No merchant names or account details.",
                fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp, bottom = 28.dp)
            )
        }
    }
}

@Composable
private fun StatCard(label: String, big: String, note: String) {
    AppCard {
        Column(modifier = Modifier.padding(Spacing.card)) {
            Text(label.uppercase(), fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.8.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(big, fontWeight = FontWeight.ExtraBold, fontSize = 22.sp, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.padding(top = 2.dp))
            Text(note, fontSize = 13.sp, lineHeight = 19.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 2.dp))
        }
    }
}

/** What leaves the phone when the user shares: totals only. */
internal fun summaryText(review: YearReview): String = buildString {
    append("My recurring payments add up to ${CurrencyFormatter.format(review.yearlyTotal)} a year ")
    append("across ${review.activeCount} payments, found with Leakproof. ")
    if (review.cancelledCount > 0) append("I've cancelled ${review.cancelledCount}, saving ${CurrencyFormatter.format(review.cancelledYearlySaved)} a year. ")
    if (review.leaksFound > 0) append("It flagged ${review.leaksFound} thing${if (review.leaksFound == 1) "" else "s"} worth a look. ")
    append("Everything stays on my phone.")
}
