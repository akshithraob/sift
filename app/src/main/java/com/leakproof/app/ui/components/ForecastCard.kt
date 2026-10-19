package com.leakproof.app.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.leakproof.app.insights.MonthForecast
import com.leakproof.app.parser.RecurringKind
import com.leakproof.app.ui.theme.KindEmi
import com.leakproof.app.ui.theme.KindInsurance
import com.leakproof.app.ui.theme.KindSip
import com.leakproof.app.ui.theme.KindSubscription
import com.leakproof.app.util.CurrencyFormatter
import java.text.DateFormatSymbols
import java.util.Locale

private fun kindFill(kind: RecurringKind): Color = when (kind) {
    RecurringKind.SUBSCRIPTION -> KindSubscription
    RecurringKind.EMI -> KindEmi
    RecurringKind.SIP -> KindSip
    RecurringKind.INSURANCE -> KindInsurance
}

private fun monthShort(month: Int): String = DateFormatSymbols(Locale.ENGLISH).shortMonths[month]

/**
 * Twelve bars, one per month, stacked by what the money is for. Tap a bar (or drag across them)
 * to read that month's total. Bars grow in when the card first appears.
 */
@Composable
fun ForecastCard(months: List<MonthForecast>, modifier: Modifier = Modifier) {
    if (months.isEmpty() || months.all { it.total <= 0 }) return

    var selected by remember { mutableIntStateOf(0) }
    val grow = remember { Animatable(0f) }
    LaunchedEffect(months) { grow.snapTo(0f); grow.animateTo(1f, tween(900, easing = FastOutSlowInEasing)) }
    val haptics = rememberHaptics()
    val maxTotal = months.maxOf { it.total }.coerceAtLeast(1.0)
    val chosen = months[selected.coerceIn(0, months.lastIndex)]
    val gridColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.14f)
    val dim = MaterialTheme.colorScheme.onSurfaceVariant

    AppCard(modifier = modifier.testTag("forecast_card")) {
        Column(modifier = Modifier.padding(Spacing.card)) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "${monthShort(chosen.month)} ${chosen.year}",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = dim
                    )
                    Text(
                        CurrencyFormatter.format(chosen.total),
                        fontSize = 26.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.testTag("forecast_selected_total")
                    )
                }
                Text(
                    "next 12 months: ${CurrencyFormatter.format(months.sumOf { it.total })}",
                    fontSize = 12.sp,
                    color = dim
                )
            }

            Spacer(modifier = Modifier.height(14.dp))

            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(140.dp)
                    .pointerInput(months) {
                        fun pick(x: Float) {
                            val index = (x / (size.width / months.size)).toInt().coerceIn(0, months.lastIndex)
                            if (index != selected) { selected = index; haptics.tap() }
                        }
                        detectTapGestures { offset -> pick(offset.x) }
                    }
                    .pointerInput(months) {
                        detectHorizontalDragGestures { change, _ ->
                            val index = (change.position.x / (size.width / months.size)).toInt().coerceIn(0, months.lastIndex)
                            if (index != selected) { selected = index; haptics.tap() }
                        }
                    }
            ) {
                val slot = size.width / months.size
                val barWidth = slot * 0.56f
                val top = 8.dp.toPx()
                val chartHeight = size.height - top

                // Light guide lines
                for (i in 0..2) {
                    val y = top + chartHeight * i / 2f
                    drawLine(gridColor, Offset(0f, y), Offset(size.width, y), strokeWidth = 1.dp.toPx())
                }

                months.forEachIndexed { i, month ->
                    val x = slot * i + (slot - barWidth) / 2f
                    val fullHeight = (month.total / maxTotal).toFloat() * chartHeight * grow.value
                    var yCursor = size.height
                    val isChosen = i == selected
                    month.byKind.entries.sortedBy { it.key.ordinal }.forEach { (kind, amount) ->
                        val segment = (amount / month.total).toFloat() * fullHeight
                        if (segment > 0f) {
                            drawRoundRect(
                                color = kindFill(kind).copy(alpha = if (isChosen) 1f else 0.78f),
                                topLeft = Offset(x, yCursor - segment),
                                size = Size(barWidth, segment),
                                cornerRadius = CornerRadius(4.dp.toPx())
                            )
                            yCursor -= segment
                        }
                    }
                    if (month.total <= 0) {
                        drawRoundRect(
                            color = gridColor,
                            topLeft = Offset(x, size.height - 3.dp.toPx()),
                            size = Size(barWidth, 3.dp.toPx()),
                            cornerRadius = CornerRadius(2.dp.toPx())
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(6.dp))
            Row(modifier = Modifier.fillMaxWidth()) {
                months.forEachIndexed { i, month ->
                    Text(
                        monthShort(month.month).take(1),
                        fontSize = 10.sp,
                        fontWeight = if (i == selected) FontWeight.ExtraBold else FontWeight.Medium,
                        color = if (i == selected) MaterialTheme.colorScheme.onSurface else dim,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            val kinds = months.flatMap { it.byKind.keys }.distinct().sortedBy { it.ordinal }
            if (kinds.size > 1) {
                Spacer(modifier = Modifier.height(10.dp))
                Row(modifier = Modifier.fillMaxWidth()) {
                    kinds.forEach { kind ->
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(end = 14.dp)) {
                            Canvas(modifier = Modifier.width(8.dp).height(8.dp)) { drawCircle(kindFill(kind)) }
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(kind.plural, fontSize = 11.sp, color = dim)
                        }
                    }
                }
            }
        }
    }
}
