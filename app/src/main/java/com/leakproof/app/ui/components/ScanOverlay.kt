package com.leakproof.app.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.leakproof.app.ui.theme.BrandPrimary

/**
 * Shown while a screenshot or statement is being read. A scan line sweeps a document that fills in
 * line by line, with the reassurance that nothing leaves the phone. Blocks taps behind it.
 */
@Composable
fun ScanOverlay(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "scan")
    val sweep by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1500, easing = LinearEasing), RepeatMode.Reverse),
        label = "sweep"
    )
    val lineColor = BrandPrimary
    val ink = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.18f)
    val paper = MaterialTheme.colorScheme.surface

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.55f))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
            .semantics { contentDescription = "Reading your file on this phone" }
            .testTag("scan_overlay"),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .padding(32.dp)
                .background(paper, RoundedCornerShape(28.dp))
                .padding(horizontal = 36.dp, vertical = 32.dp)
        ) {
            Canvas(modifier = Modifier.size(width = 120.dp, height = 150.dp)) {
                val radius = CornerRadius(14.dp.toPx())
                drawRoundRect(color = ink.copy(alpha = 0.08f), cornerRadius = radius)
                drawRoundRect(color = ink, cornerRadius = radius, style = Stroke(width = 2.dp.toPx()))

                // Text lines that light up as the scan line passes over them
                val rows = 7
                val top = 24.dp.toPx()
                val gap = (size.height - top * 2) / (rows - 1)
                val scanY = top + (size.height - top * 2) * sweep
                for (i in 0 until rows) {
                    val y = top + gap * i
                    val reached = y <= scanY
                    val width = size.width * (if (i % 3 == 2) 0.45f else 0.72f)
                    drawRoundRect(
                        color = if (reached) lineColor else ink,
                        topLeft = Offset(16.dp.toPx(), y),
                        size = Size(width, 6.dp.toPx()),
                        cornerRadius = CornerRadius(3.dp.toPx())
                    )
                }

                // The scan line and its glow
                drawRect(
                    brush = Brush.verticalGradient(
                        listOf(Color.Transparent, lineColor.copy(alpha = 0.35f), Color.Transparent),
                        startY = scanY - 26.dp.toPx(),
                        endY = scanY + 26.dp.toPx()
                    ),
                    topLeft = Offset(0f, scanY - 26.dp.toPx()),
                    size = Size(size.width, 52.dp.toPx())
                )
                drawRoundRect(
                    color = lineColor,
                    topLeft = Offset(4.dp.toPx(), scanY - 1.5.dp.toPx()),
                    size = Size(size.width - 8.dp.toPx(), 3.dp.toPx()),
                    cornerRadius = CornerRadius(2.dp.toPx())
                )
            }
            Spacer(modifier = Modifier.height(22.dp))
            Text(
                "Reading on your phone",
                fontWeight = FontWeight.ExtraBold,
                fontSize = 18.sp,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                "Nothing leaves this device.",
                fontSize = 13.sp,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
