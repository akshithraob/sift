package com.leakproof.app.ui.screens

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.leakproof.app.ui.components.AppCard
import com.leakproof.app.ui.components.BrandMark
import com.leakproof.app.ui.components.rememberHaptics
import com.leakproof.app.ui.theme.BrandPrimary
import kotlinx.coroutines.launch

private data class OnboardingPage(val title: String, val tagline: String, val description: String)

private val pages = listOf(
    OnboardingPage(
        "Zero cloud.\nEverything on-device.",
        "Private by design",
        "Leakproof has no network permission. No servers, no tracking, no external APIs. Your statements and screenshots never leave this phone."
    ),
    OnboardingPage(
        "An encrypted vault",
        "Locked on your phone",
        "Your records are encrypted with SQLCipher AES-256, and the key is protected by the Android Keystore."
    ),
    OnboardingPage(
        "Find every leak",
        "UPI, e-NACH, standing instructions, EMIs",
        "Import a Google Pay, PhonePe or Paytm Autopay screenshot, or a bank statement. Leakproof reads it on your phone and finds what you pay."
    )
)

@Composable
fun OnboardingScreen(
    onComplete: (enableAppLock: Boolean) -> Unit
) {
    val pagerState = rememberPagerState(pageCount = { pages.size })
    val scope = rememberCoroutineScope()
    val haptics = rememberHaptics()
    var enableAppLock by remember { mutableStateOf(true) }
    val last = pagerState.currentPage == pages.lastIndex

    Scaffold(containerColor = MaterialTheme.colorScheme.background) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .navigationBarsPadding()
                .padding(horizontal = 24.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                BrandMark(modifier = Modifier.weight(1f))
                if (!last) {
                    TextButton(
                        onClick = { scope.launch { pagerState.animateScrollToPage(pages.lastIndex) } },
                        modifier = Modifier.testTag("onboarding_skip")
                    ) { Text("Skip", fontWeight = FontWeight.SemiBold) }
                }
            }

            HorizontalPager(
                state = pagerState,
                modifier = Modifier.weight(1f).fillMaxWidth()
            ) { index ->
                val page = pages[index]
                Column(
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Illustration(index)
                    Spacer(modifier = Modifier.height(30.dp))
                    Text(
                        page.tagline.uppercase(),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.2.sp,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        page.title,
                        style = MaterialTheme.typography.headlineLarge,
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        page.description,
                        style = MaterialTheme.typography.bodyLarge,
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 8.dp)
                    )
                }
            }

            // Page dots
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                pages.indices.forEach { i ->
                    val selected = i == pagerState.currentPage
                    val width by animateDpAsState(if (selected) 26.dp else 8.dp, tween(250), label = "dot")
                    val color by animateColorAsState(
                        if (selected) BrandPrimary else MaterialTheme.colorScheme.outline.copy(alpha = 0.35f), tween(250), label = "dotColor"
                    )
                    Box(modifier = Modifier.height(8.dp).width(width).clip(CircleShape).background(color))
                }
            }
            Spacer(modifier = Modifier.height(20.dp))

            if (last) {
                AppCard {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Lock with fingerprint or screen lock", fontWeight = FontWeight.Bold, fontSize = 15.sp, color = MaterialTheme.colorScheme.onSurface)
                            Text("Recommended. Asked each time you open the app.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(
                            checked = enableAppLock,
                            onCheckedChange = { enableAppLock = it; haptics.toggle() },
                            colors = SwitchDefaults.colors(checkedTrackColor = BrandPrimary),
                            modifier = Modifier.testTag("app_lock_switch")
                        )
                    }
                }
                Spacer(modifier = Modifier.height(14.dp))
                Button(
                    onClick = { haptics.confirm(); onComplete(enableAppLock) },
                    colors = ButtonDefaults.buttonColors(containerColor = BrandPrimary, contentColor = Color.White),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth().height(54.dp).testTag("onboarding_finish")
                ) { Text("Get started", fontWeight = FontWeight.ExtraBold, fontSize = 16.sp) }
            } else {
                Button(
                    onClick = { scope.launch { pagerState.animateScrollToPage(pagerState.currentPage + 1) } },
                    colors = ButtonDefaults.buttonColors(containerColor = BrandPrimary, contentColor = Color.White),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth().height(54.dp).testTag("onboarding_continue")
                ) { Text("Continue", fontWeight = FontWeight.ExtraBold, fontSize = 16.sp) }
            }
        }
    }
}

/** Three small drawings, one per page, in the app's own colours. */
@Composable
private fun Illustration(page: Int) {
    val primary = BrandPrimary
    val ink = MaterialTheme.colorScheme.onBackground
    val soft = MaterialTheme.colorScheme.surfaceContainerHigh
    val transition = rememberInfiniteTransition(label = "illustration")
    val angle by transition.animateFloat(0f, 360f, infiniteRepeatable(tween(5200, easing = LinearEasing)), label = "angle")
    val pulse by transition.animateFloat(0f, 1f, infiniteRepeatable(tween(1800), RepeatMode.Reverse), label = "pulse")

    Canvas(modifier = Modifier.size(220.dp)) {
        val c = center
        drawCircle(Brush.radialGradient(listOf(primary.copy(alpha = 0.22f), Color.Transparent), center = c, radius = size.minDimension / 2f))
        when (page) {
            0 -> drawPhoneWithoutCloud(c, ink, soft, primary, pulse)
            1 -> drawVault(c, ink, soft, primary, pulse)
            else -> drawRadar(c, soft, primary, angle)
        }
    }
}

private fun DrawScope.drawPhoneWithoutCloud(c: Offset, ink: Color, soft: Color, primary: Color, pulse: Float) {
    val w = 84.dp.toPx()
    val h = 150.dp.toPx()
    drawRoundRect(soft, Offset(c.x - w / 2, c.y - h / 2), Size(w, h), CornerRadius(18.dp.toPx()))
    drawRoundRect(ink.copy(alpha = 0.8f), Offset(c.x - w / 2, c.y - h / 2), Size(w, h), CornerRadius(18.dp.toPx()), style = Stroke(3.dp.toPx()))
    drawRoundRect(ink.copy(alpha = 0.5f), Offset(c.x - 14.dp.toPx(), c.y - h / 2 + 8.dp.toPx()), Size(28.dp.toPx(), 4.dp.toPx()), CornerRadius(2.dp.toPx()))
    // The drop sits safely inside the phone
    val dropScale = 1f + 0.05f * pulse
    drawDrop(Offset(c.x, c.y + 4.dp.toPx()), 20.dp.toPx() * dropScale, primary)
    // A cloud, crossed out
    val cloud = Offset(c.x + w / 2 + 18.dp.toPx(), c.y - h / 2 + 18.dp.toPx())
    drawCircle(ink.copy(alpha = 0.25f), 11.dp.toPx(), cloud)
    drawCircle(ink.copy(alpha = 0.25f), 8.dp.toPx(), cloud + Offset(-14.dp.toPx(), 4.dp.toPx()))
    drawCircle(ink.copy(alpha = 0.25f), 8.dp.toPx(), cloud + Offset(14.dp.toPx(), 4.dp.toPx()))
    drawLine(Color(0xFFF0554D), cloud + Offset(-24.dp.toPx(), 18.dp.toPx()), cloud + Offset(24.dp.toPx(), -14.dp.toPx()), 4.dp.toPx(), StrokeCap.Round)
}

private fun DrawScope.drawVault(c: Offset, ink: Color, soft: Color, primary: Color, pulse: Float) {
    val w = 120.dp.toPx()
    val h = 132.dp.toPx()
    val top = c.y - h / 2 + 12.dp.toPx()
    // Shackle
    drawArc(ink.copy(alpha = 0.8f), 180f, 180f, false, Offset(c.x - 34.dp.toPx(), top - 44.dp.toPx()), Size(68.dp.toPx(), 80.dp.toPx()), style = Stroke(9.dp.toPx(), cap = StrokeCap.Round))
    // Body
    drawRoundRect(primary, Offset(c.x - w / 2, top + 10.dp.toPx()), Size(w, h - 22.dp.toPx()), CornerRadius(22.dp.toPx()))
    drawRoundRect(Color.White.copy(alpha = 0.18f + 0.1f * pulse), Offset(c.x - w / 2 + 8.dp.toPx(), top + 18.dp.toPx()), Size(w - 16.dp.toPx(), 18.dp.toPx()), CornerRadius(9.dp.toPx()))
    // Keyhole
    val kh = Offset(c.x, top + h / 2 + 8.dp.toPx())
    drawCircle(Color.White, 11.dp.toPx(), kh)
    val stem = Path().apply {
        moveTo(kh.x - 5.dp.toPx(), kh.y + 4.dp.toPx()); lineTo(kh.x + 5.dp.toPx(), kh.y + 4.dp.toPx())
        lineTo(kh.x + 8.dp.toPx(), kh.y + 30.dp.toPx()); lineTo(kh.x - 8.dp.toPx(), kh.y + 30.dp.toPx()); close()
    }
    drawPath(stem, Color.White)
    drawCircle(soft, 0f, c)
}

private fun DrawScope.drawRadar(c: Offset, soft: Color, primary: Color, angle: Float) {
    val r = 90.dp.toPx()
    for (i in 1..3) drawCircle(primary.copy(alpha = 0.22f), r * i / 3f, c, style = Stroke(1.5.dp.toPx()))
    drawLine(primary.copy(alpha = 0.18f), Offset(c.x - r, c.y), Offset(c.x + r, c.y), 1.dp.toPx())
    drawLine(primary.copy(alpha = 0.18f), Offset(c.x, c.y - r), Offset(c.x, c.y + r), 1.dp.toPx())
    // The sweeping beam
    rotate(angle, c) {
        drawArc(
            brush = Brush.sweepGradient(listOf(Color.Transparent, primary.copy(alpha = 0.55f)), center = c),
            startAngle = -60f, sweepAngle = 60f, useCenter = true,
            topLeft = Offset(c.x - r, c.y - r), size = Size(r * 2, r * 2)
        )
        drawLine(primary, c, Offset(c.x + r, c.y), 3.dp.toPx(), StrokeCap.Round)
    }
    // Things the radar found
    listOf(Offset(0.45f, -0.5f), Offset(-0.55f, 0.2f), Offset(0.2f, 0.62f), Offset(-0.2f, -0.65f)).forEach { p ->
        drawCircle(Color(0xFFF5A524), 6.dp.toPx(), Offset(c.x + p.x * r, c.y + p.y * r))
    }
    drawDrop(c, 15.dp.toPx(), primary)
    drawCircle(soft, 0f, c)
}

private fun DrawScope.drawDrop(center: Offset, size: Float, color: Color) {
    val path = Path().apply {
        moveTo(center.x, center.y - size * 1.1f)
        cubicTo(center.x, center.y - size * 1.1f, center.x - size, center.y - size * 0.15f, center.x - size, center.y + size * 0.3f)
        cubicTo(center.x - size, center.y + size * 0.85f, center.x - size * 0.55f, center.y + size * 1.1f, center.x, center.y + size * 1.1f)
        cubicTo(center.x + size * 0.55f, center.y + size * 1.1f, center.x + size, center.y + size * 0.85f, center.x + size, center.y + size * 0.3f)
        cubicTo(center.x + size, center.y - size * 0.15f, center.x, center.y - size * 1.1f, center.x, center.y - size * 1.1f)
        close()
    }
    drawPath(path, color)
}
