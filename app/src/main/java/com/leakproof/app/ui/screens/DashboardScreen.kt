package com.leakproof.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.HourglassBottom
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.TrendingUp
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.leakproof.app.data.local.SubscriptionEntity
import com.leakproof.app.insights.Budget
import com.leakproof.app.insights.BudgetLevel
import com.leakproof.app.insights.BudgetStatus
import com.leakproof.app.insights.Leak
import com.leakproof.app.insights.LeakFinder
import com.leakproof.app.insights.LeakKind
import com.leakproof.app.insights.LeakSeverity
import com.leakproof.app.insights.Trials
import com.leakproof.app.insights.monthlyCost
import com.leakproof.app.ui.components.AppCard
import com.leakproof.app.ui.components.BrandMark
import com.leakproof.app.ui.components.CountPill
import com.leakproof.app.ui.components.InitialAvatar
import com.leakproof.app.ui.components.SectionTitle
import com.leakproof.app.ui.components.Spacing
import com.leakproof.app.ui.theme.BrandPrimary
import com.leakproof.app.ui.theme.StatusActive
import com.leakproof.app.ui.theme.StatusCancelled
import com.leakproof.app.ui.theme.StatusPaused
import com.leakproof.app.ui.viewmodel.LeakproofViewModel
import com.leakproof.app.util.CurrencyFormatter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val HeroTop = Color(0xFF00BFA5)
private val HeroBottom = Color(0xFF00695C)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    viewModel: LeakproofViewModel,
    onNavigateToImport: () -> Unit,
    onNavigateToManualAdd: () -> Unit,
    onNavigateToDetail: (Long) -> Unit,
    onNavigateToSettings: () -> Unit
) {
    val subscriptions by viewModel.allSubscriptions.collectAsState()
    val searchQuery by viewModel.searchQuery.collectAsState()
    val selectedFilter by viewModel.selectedRailFilter.collectAsState()
    val monthlyBudget by viewModel.monthlyBudget.collectAsState()

    val active = subscriptions.filter { it.status == "ACTIVE" }
    val totalMonthly = active.sumOf { monthlyCost(it.amount, it.frequency) }
    val budget = Budget.evaluate(totalMonthly, monthlyBudget)
    val leaks = remember(subscriptions, budget) { LeakFinder.find(subscriptions, budget) }

    val comingUp = active
        .filter { Trials.daysUntil(it.nextDateEpochMs) in 0..7 }
        .sortedBy { it.nextDateEpochMs }

    val shown = subscriptions
        .filter { sub ->
            val matchesSearch = sub.merchant.contains(searchQuery, ignoreCase = true) ||
                (sub.last4?.contains(searchQuery) == true)
            val matchesFilter = selectedFilter == "ALL" || sub.rail.equals(selectedFilter, ignoreCase = true)
            matchesSearch && matchesFilter
        }
        .sortedWith(compareBy({ it.status != "ACTIVE" }, { it.nextDateEpochMs }))

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { BrandMark() },
                actions = {
                    IconButton(onClick = onNavigateToImport, modifier = Modifier.testTag("import_action_button")) {
                        Icon(Icons.Default.FileDownload, contentDescription = "Import", tint = MaterialTheme.colorScheme.onBackground)
                    }
                    IconButton(onClick = onNavigateToSettings, modifier = Modifier.testTag("settings_button")) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings", tint = MaterialTheme.colorScheme.onBackground)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        },
        floatingActionButton = {
            if (subscriptions.isNotEmpty()) {
                FloatingActionButton(
                    onClick = onNavigateToImport,
                    containerColor = BrandPrimary,
                    contentColor = Color.White,
                    shape = RoundedCornerShape(18.dp),
                    modifier = Modifier.testTag("fab_import")
                ) {
                    Row(modifier = Modifier.padding(horizontal = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Add, contentDescription = null)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Add", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    ) { padding ->
        if (subscriptions.isEmpty()) {
            EmptyDashboard(
                onImport = onNavigateToImport,
                onAddManually = onNavigateToManualAdd,
                modifier = Modifier.padding(padding)
            )
            return@Scaffold
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(start = Spacing.screen, end = Spacing.screen, bottom = 96.dp)
        ) {
            item { Hero(totalMonthly = totalMonthly, activeCount = active.size, budget = budget) }

            item {
                SectionTitle("Leaks found") {
                    if (leaks.isNotEmpty()) {
                        CountPill(
                            leaks.size.toString(),
                            if (leaks.any { it.severity == LeakSeverity.HIGH }) StatusCancelled else StatusPaused
                        )
                    }
                }
                LeaksCard(leaks)
            }

            if (comingUp.isNotEmpty()) {
                item {
                    SectionTitle("Next 7 days") {
                        Text(
                            text = "${CurrencyFormatter.format(comingUp.sumOf { it.amount })} due",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    ComingUpCard(comingUp, onNavigateToDetail)
                }
            }

            item {
                SectionTitle("All subscriptions") {
                    Text(
                        text = "${subscriptions.size}",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { viewModel.searchQuery.value = it },
                    placeholder = { Text("Search by name or last 4 digits") },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    trailingIcon = {
                        if (searchQuery.isNotEmpty()) {
                            IconButton(onClick = { viewModel.searchQuery.value = "" }) {
                                Icon(Icons.Default.Clear, contentDescription = "Clear")
                            }
                        }
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = MaterialTheme.colorScheme.surface,
                        unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                        unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)
                    ),
                    modifier = Modifier.fillMaxWidth().testTag("search_field")
                )
                Spacer(modifier = Modifier.height(10.dp))
                val filters = listOf("ALL" to "All", "UPI" to "UPI Autopay", "NACH" to "e-NACH", "SI" to "Standing", "CARD" to "Card")
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(filters) { (key, label) ->
                        FilterChip(
                            selected = selectedFilter == key,
                            onClick = { viewModel.selectedRailFilter.value = key },
                            label = { Text(label, fontSize = 12.sp) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = BrandPrimary.copy(alpha = 0.18f),
                                selectedLabelColor = MaterialTheme.colorScheme.primary
                            ),
                            modifier = Modifier.testTag("filter_$key")
                        )
                    }
                }
                Spacer(modifier = Modifier.height(12.dp))
            }

            if (shown.isEmpty()) {
                item {
                    Text(
                        text = "Nothing matches your search.",
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 24.dp)
                    )
                }
            } else {
                items(shown, key = { it.id }) { sub ->
                    SubscriptionRow(sub) { onNavigateToDetail(sub.id) }
                    Spacer(modifier = Modifier.height(8.dp))
                }
            }
        }
    }
}

// ---------------------------------------------------------------- Hero

@Composable
private fun Hero(totalMonthly: Double, activeCount: Int, budget: BudgetStatus) {
    Box(
        modifier = Modifier
            .padding(top = 6.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(26.dp))
            .background(Brush.linearGradient(listOf(HeroTop, HeroBottom)))
            .testTag("monthly_spend_card")
    ) {
        Column(modifier = Modifier.padding(22.dp)) {
            Text(
                text = "YOU PAY EVERY MONTH",
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp,
                color = Color.White.copy(alpha = 0.78f)
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = CurrencyFormatter.format(totalMonthly),
                fontSize = 42.sp,
                fontWeight = FontWeight.ExtraBold,
                color = Color.White
            )
            Text(
                text = "${CurrencyFormatter.format(totalMonthly * 12)} a year · $activeCount active",
                fontSize = 13.sp,
                color = Color.White.copy(alpha = 0.85f),
                modifier = Modifier.testTag("yearly_spend_card")
            )

            if (budget.level != BudgetLevel.NONE) {
                Spacer(modifier = Modifier.height(16.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(8.dp)
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = 0.25f))
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(budget.ratio.coerceIn(0f, 1f))
                            .height(8.dp)
                            .clip(CircleShape)
                            .background(if (budget.level == BudgetLevel.OVER) Color(0xFFFFD54F) else Color.White)
                    )
                }
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = when (budget.level) {
                        BudgetLevel.OVER -> "${CurrencyFormatter.format(-budget.remaining)} over your ${CurrencyFormatter.format(budget.budget)} budget"
                        else -> "${CurrencyFormatter.format(budget.remaining)} left of your ${CurrencyFormatter.format(budget.budget)} budget"
                    },
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White,
                    modifier = Modifier.testTag("insight_budget")
                )
            }
        }
    }
}

// ---------------------------------------------------------------- Leaks

private fun Leak.icon(): ImageVector = when (kind) {
    LeakKind.OVER_BUDGET, LeakKind.NEAR_BUDGET -> Icons.Default.TrendingUp
    LeakKind.TRIAL -> Icons.Default.HourglassBottom
    LeakKind.DUPLICATE, LeakKind.OVERLAP -> Icons.Default.ContentCopy
    LeakKind.MANDATE_LIMIT -> Icons.Default.Warning
}

@Composable
private fun LeaksCard(leaks: List<Leak>) {
    AppCard(modifier = Modifier.testTag("leaks_card")) {
        if (leaks.isEmpty()) {
            Row(modifier = Modifier.padding(Spacing.card), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.CheckCircle, contentDescription = null, tint = StatusActive, modifier = Modifier.size(26.dp))
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text("All clear", fontWeight = FontWeight.Bold, fontSize = 15.sp, color = MaterialTheme.colorScheme.onSurface)
                    Text(
                        "No overlapping services, risky limits or trials ending soon.",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            return@AppCard
        }
        leaks.forEachIndexed { index, leak ->
            val tint = if (leak.severity == LeakSeverity.HIGH) StatusCancelled else StatusPaused
            Row(
                modifier = Modifier.fillMaxWidth().padding(Spacing.card).testTag("leak_${leak.kind.name.lowercase()}"),
                verticalAlignment = Alignment.Top
            ) {
                Box(
                    modifier = Modifier.size(36.dp).clip(CircleShape).background(tint.copy(alpha = 0.14f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(leak.icon(), contentDescription = null, tint = tint, modifier = Modifier.size(19.dp))
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(leak.title, fontWeight = FontWeight.Bold, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface)
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        leak.body,
                        fontSize = 12.sp,
                        lineHeight = 17.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            if (index < leaks.lastIndex) {
                Box(
                    modifier = Modifier
                        .padding(horizontal = Spacing.card)
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(MaterialTheme.colorScheme.outline.copy(alpha = 0.14f))
                )
            }
        }
    }
}

// ---------------------------------------------------------------- Coming up

@Composable
private fun ComingUpCard(items: List<SubscriptionEntity>, onClick: (Long) -> Unit) {
    val dayFormat = remember { SimpleDateFormat("d", Locale.ENGLISH) }
    val monthFormat = remember { SimpleDateFormat("MMM", Locale.ENGLISH) }
    val visible = items.take(5)

    AppCard(modifier = Modifier.testTag("coming_up_card")) {
        visible.forEachIndexed { index, sub ->
            val days = Trials.daysUntil(sub.nextDateEpochMs)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onClick(sub.id) }
                    .padding(horizontal = Spacing.card, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(
                    modifier = Modifier
                        .size(width = 46.dp, height = 50.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(BrandPrimary.copy(alpha = 0.12f)),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Text(
                        dayFormat.format(Date(sub.nextDateEpochMs)),
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 17.sp,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        monthFormat.format(Date(sub.nextDateEpochMs)).uppercase(),
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        sub.merchant,
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        when (days) {
                            0 -> "Today"
                            1 -> "Tomorrow"
                            else -> "In $days days"
                        },
                        fontSize = 12.sp,
                        color = if (days <= 1) StatusPaused else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Text(
                    CurrencyFormatter.format(sub.amount),
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 16.sp,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
            if (index < visible.lastIndex) {
                Box(
                    modifier = Modifier
                        .padding(horizontal = Spacing.card)
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(MaterialTheme.colorScheme.outline.copy(alpha = 0.14f))
                )
            }
        }
        if (items.size > visible.size) {
            Text(
                "+${items.size - visible.size} more this week",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = Spacing.card, vertical = 10.dp)
            )
        }
    }
}

// ---------------------------------------------------------------- List row

@Composable
fun SubscriptionItemCard(subscription: SubscriptionEntity, onClick: () -> Unit) = SubscriptionRow(subscription, onClick)

@Composable
private fun SubscriptionRow(subscription: SubscriptionEntity, onClick: () -> Unit) {
    val railLabel = when (subscription.rail.uppercase()) {
        "UPI" -> "UPI Autopay"
        "NACH" -> "e-NACH"
        "SI" -> "Standing instruction"
        "CARD" -> "Card"
        else -> "Other"
    }
    val trialEnd = subscription.trialEndEpochMs
    val inactive = subscription.status != "ACTIVE"

    AppCard(
        modifier = Modifier
            .clickable(onClick = onClick)
            .testTag("subscription_item_${subscription.id}")
    ) {
        Row(modifier = Modifier.padding(Spacing.card), verticalAlignment = Alignment.CenterVertically) {
            InitialAvatar(subscription.merchant)
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    subscription.merchant,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = if (inactive) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface
                )
                Text(
                    "$railLabel · ${subscription.frequency.lowercase().replace('_', ' ')}" +
                        (subscription.last4?.takeIf { it.isNotBlank() }?.let { " · ••$it" } ?: ""),
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                when {
                    inactive -> Text(
                        subscription.status.lowercase().replaceFirstChar { it.uppercase() },
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = if (subscription.status == "CANCELLED") StatusCancelled else StatusPaused
                    )
                    trialEnd != null && Trials.daysUntil(trialEnd) >= 0 -> Text(
                        Trials.label(Trials.daysUntil(trialEnd)),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = StatusPaused,
                        modifier = Modifier.testTag("trial_label_${subscription.id}")
                    )
                }
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    CurrencyFormatter.format(subscription.amount),
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 17.sp,
                    color = if (inactive) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface
                )
                Text(
                    subscription.nextDate,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

// ---------------------------------------------------------------- Empty state

@Composable
private fun EmptyDashboard(onImport: () -> Unit, onAddManually: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxSize().padding(horizontal = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            modifier = Modifier
                .size(96.dp)
                .clip(CircleShape)
                .background(Brush.linearGradient(listOf(HeroTop, HeroBottom))),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Default.WaterDrop, contentDescription = null, tint = Color.White, modifier = Modifier.size(48.dp))
        }
        Spacer(modifier = Modifier.height(22.dp))
        Text(
            "Find your leaks",
            fontWeight = FontWeight.ExtraBold,
            fontSize = 26.sp,
            color = MaterialTheme.colorScheme.onBackground
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            "Add a screenshot of your UPI app's Autopay list or a bank statement. Leakproof finds every recurring payment, " +
                "flags the ones you're paying twice, and keeps it all on your phone.",
            fontSize = 14.sp,
            lineHeight = 21.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(26.dp))
        Button(
            onClick = onImport,
            colors = ButtonDefaults.buttonColors(containerColor = BrandPrimary, contentColor = Color.White),
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.fillMaxWidth().height(52.dp).testTag("empty_import_button")
        ) { Text("Import screenshots or a statement", fontWeight = FontWeight.Bold) }
        Spacer(modifier = Modifier.height(10.dp))
        OutlinedButton(
            onClick = onAddManually,
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.fillMaxWidth().height(52.dp).testTag("empty_manual_button")
        ) { Text("Add one manually") }
    }
}
