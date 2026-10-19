package com.leakproof.app.ui.screens

import androidx.compose.foundation.border
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.leakproof.app.insights.Occurrence
import com.leakproof.app.insights.Schedule
import com.leakproof.app.ui.components.AppCard
import com.leakproof.app.ui.components.InitialAvatar
import com.leakproof.app.ui.components.SectionTitle
import com.leakproof.app.ui.components.Spacing
import com.leakproof.app.ui.components.rememberHaptics
import com.leakproof.app.ui.theme.BrandPrimary
import com.leakproof.app.ui.viewmodel.LeakproofViewModel
import com.leakproof.app.util.CurrencyFormatter
import java.text.DateFormatSymbols
import java.util.Calendar
import java.util.Locale

private val weekdays = listOf("M", "T", "W", "T", "F", "S", "S")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CalendarScreen(
    viewModel: LeakproofViewModel,
    onNavigateBack: () -> Unit,
    onNavigateToDetail: (Long) -> Unit
) {
    val subscriptions by viewModel.allSubscriptions.collectAsState()
    val today = remember { Calendar.getInstance() }
    var monthOffset by remember { mutableIntStateOf(0) }
    var selectedDay by remember { mutableIntStateOf(today.get(Calendar.DAY_OF_MONTH)) }
    val haptics = rememberHaptics()

    val shown = Calendar.getInstance().apply { set(Calendar.DAY_OF_MONTH, 1); add(Calendar.MONTH, monthOffset) }
    val year = shown.get(Calendar.YEAR)
    val month = shown.get(Calendar.MONTH)
    val byDay = remember(subscriptions, year, month) { Schedule.dueByDay(subscriptions, year, month) }
    val dayTotals = byDay.mapValues { (_, list) -> list.sumOf { it.subscription.amount } }
    val monthTotal = dayTotals.values.sum()
    val maxDay = dayTotals.values.maxOrNull() ?: 0.0

    // When the month changes, land on today (this month) or the first busy day
    LaunchedEffect(year, month) {
        selectedDay = if (monthOffset == 0) today.get(Calendar.DAY_OF_MONTH) else (byDay.keys.minOrNull() ?: 1)
    }

    val daysInMonth = shown.getActualMaximum(Calendar.DAY_OF_MONTH)
    // Monday-first grid
    val firstWeekday = (shown.get(Calendar.DAY_OF_WEEK) + 5) % 7

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("Calendar", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack, modifier = Modifier.testTag("calendar_back_button")) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(horizontal = Spacing.screen, vertical = 8.dp)
        ) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    IconButton(onClick = { monthOffset--; haptics.tap() }, modifier = Modifier.testTag("calendar_prev")) {
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "Previous month")
                    }
                    AnimatedContent(
                        targetState = monthOffset,
                        transitionSpec = {
                            val forward = targetState > initialState
                            (slideInHorizontally { w -> if (forward) w / 3 else -w / 3 } + fadeIn()) togetherWith
                                (slideOutHorizontally { w -> if (forward) -w / 3 else w / 3 } + fadeOut()) using SizeTransform(clip = false)
                        },
                        label = "month"
                    ) {
                        val label = Calendar.getInstance().apply { set(Calendar.DAY_OF_MONTH, 1); add(Calendar.MONTH, it) }
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                "${DateFormatSymbols(Locale.ENGLISH).months[label.get(Calendar.MONTH)]} ${label.get(Calendar.YEAR)}",
                                fontWeight = FontWeight.ExtraBold,
                                fontSize = 20.sp,
                                color = MaterialTheme.colorScheme.onBackground
                            )
                        }
                    }
                    IconButton(onClick = { monthOffset++; haptics.tap() }, modifier = Modifier.testTag("calendar_next")) {
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "Next month")
                    }
                }
                Text(
                    text = if (monthTotal > 0) "${CurrencyFormatter.format(monthTotal)} going out this month" else "Nothing due this month",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().testTag("calendar_month_total")
                )
                Spacer(modifier = Modifier.height(14.dp))
            }

            item {
                AppCard(modifier = Modifier.testTag("calendar_grid")) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(modifier = Modifier.fillMaxWidth()) {
                            weekdays.forEach {
                                Text(
                                    it,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                        val cells = firstWeekday + daysInMonth
                        val rows = (cells + 6) / 7
                        for (r in 0 until rows) {
                            Row(modifier = Modifier.fillMaxWidth()) {
                                for (c in 0 until 7) {
                                    val day = r * 7 + c - firstWeekday + 1
                                    Box(modifier = Modifier.weight(1f).aspectRatio(1f).padding(2.dp), contentAlignment = Alignment.Center) {
                                        if (day in 1..daysInMonth) {
                                            DayCell(
                                                day = day,
                                                total = dayTotals[day] ?: 0.0,
                                                maxTotal = maxDay,
                                                isToday = monthOffset == 0 && day == today.get(Calendar.DAY_OF_MONTH),
                                                isSelected = day == selectedDay,
                                                onClick = { selectedDay = day; haptics.tap() }
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            val dueToday: List<Occurrence> = byDay[selectedDay].orEmpty()
            item {
                SectionTitle(
                    "${DateFormatSymbols(Locale.ENGLISH).shortMonths[month]} $selectedDay"
                ) {
                    if (dueToday.isNotEmpty()) {
                        Text(
                            CurrencyFormatter.format(dueToday.sumOf { it.subscription.amount }),
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }
            if (dueToday.isEmpty()) {
                item {
                    Text(
                        "No payments on this day.",
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 8.dp).testTag("calendar_empty_day")
                    )
                }
            } else {
                dueToday.forEach { occ ->
                    item(key = "occ_${occ.subscription.id}_${occ.dateMs}") {
                        AppCard(
                            modifier = Modifier
                                .padding(bottom = 8.dp)
                                .clickable { onNavigateToDetail(occ.subscription.id) }
                                .testTag("calendar_item_${occ.subscription.id}")
                        ) {
                            Row(modifier = Modifier.padding(Spacing.card), verticalAlignment = Alignment.CenterVertically) {
                                InitialAvatar(occ.subscription.merchant, size = 40.dp)
                                Spacer(modifier = Modifier.width(12.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(occ.subscription.merchant, fontWeight = FontWeight.Bold, fontSize = 15.sp, color = MaterialTheme.colorScheme.onSurface)
                                    Text(
                                        com.leakproof.app.parser.RecurringKind.fromName(occ.subscription.kind).label,
                                        fontSize = 12.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Text(
                                    CurrencyFormatter.format(occ.subscription.amount),
                                    fontWeight = FontWeight.ExtraBold,
                                    fontSize = 16.sp,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }
                    }
                }
            }
            item { Spacer(modifier = Modifier.height(24.dp)) }
        }
    }
}

/** One day: shaded by how much leaves the account, ringed if it is today. */
@Composable
private fun DayCell(day: Int, total: Double, maxTotal: Double, isToday: Boolean, isSelected: Boolean, onClick: () -> Unit) {
    val ratio = if (maxTotal > 0 && total > 0) (total / maxTotal).toFloat() else 0f
    val fill = when {
        total <= 0 -> Color.Transparent
        else -> BrandPrimary.copy(alpha = 0.16f + 0.74f * ratio)
    }
    val onFill = if (ratio > 0.55f) Color.White else MaterialTheme.colorScheme.onSurface
    val border = when {
        isSelected -> BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
        isToday -> BorderStroke(1.5.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
        else -> null
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(12.dp))
            .background(fill)
            .let { if (border != null) it.then(Modifier.background(Color.Transparent, RoundedCornerShape(12.dp))) else it }
            .clickable(onClick = onClick)
            .testTag("day_$day"),
        contentAlignment = Alignment.Center
    ) {
        if (border != null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .androidxBorder(border, RoundedCornerShape(12.dp))
            )
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                "$day",
                fontSize = 14.sp,
                fontWeight = if (isToday || total > 0) FontWeight.ExtraBold else FontWeight.Medium,
                color = if (total > 0) onFill else MaterialTheme.colorScheme.onSurface
            )
            if (total > 0) {
                Box(modifier = Modifier.size(4.dp).clip(CircleShape).background(onFill.copy(alpha = 0.8f)))
            }
        }
    }
}

private fun Modifier.androidxBorder(border: BorderStroke, shape: androidx.compose.ui.graphics.Shape): Modifier =
    this.then(Modifier.border(border, shape))
