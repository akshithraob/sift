package com.leakproof.app.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.composed
import androidx.compose.ui.unit.dp
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import com.leakproof.app.util.CurrencyFormatter
import kotlinx.coroutines.delay

/** Small, consistent motion: every animation in the app uses these durations and curves. */
object Motion {
    const val ENTER_MS = 420
    const val COUNT_MS = 800
    const val STAGGER_MS = 45L
    const val MAX_STAGGERED_ITEMS = 8
}

/** Fades and lifts an item in, a little later for each position in the list. Plays once per item. */
fun Modifier.entrance(index: Int): Modifier = composed {
    val progress = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        delay(minOf(index, Motion.MAX_STAGGERED_ITEMS) * Motion.STAGGER_MS)
        progress.animateTo(1f, tween(Motion.ENTER_MS, easing = FastOutSlowInEasing))
    }
    this
        .alpha(progress.value)
        .graphicsLayer { translationY = (1f - progress.value) * 24.dp.toPx() }
}

/** Presses in slightly while a finger is down, like a real button. */
fun Modifier.pressScale(source: MutableInteractionSource, pressedScale: Float = 0.97f): Modifier = composed {
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) pressedScale else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
        label = "pressScale"
    )
    this.graphicsLayer { scaleX = scale; scaleY = scale }
}

/** A rupee amount that counts up (or down) to its value instead of jumping. */
@Composable
fun AnimatedAmount(
    amount: Double,
    modifier: Modifier = Modifier,
    style: TextStyle = LocalTextStyle.current,
    color: Color = Color.Unspecified,
    fontSize: TextUnit = TextUnit.Unspecified,
    includeDecimals: Boolean = false
) {
    val shown = remember { Animatable(0f) }
    LaunchedEffect(amount) {
        shown.animateTo(amount.toFloat(), tween(Motion.COUNT_MS, easing = FastOutSlowInEasing))
    }
    Text(
        text = CurrencyFormatter.format(shown.value.toDouble(), includeDecimals),
        modifier = modifier,
        style = style,
        color = color,
        fontSize = fontSize,
        maxLines = 1,
        overflow = TextOverflow.Clip,
        textAlign = TextAlign.Start
    )
}

/** Short, quiet haptic taps for the moments that deserve one. */
class Haptics(private val feedback: HapticFeedback) {
    fun tap() = feedback.performHapticFeedback(HapticFeedbackType.TextHandleMove)
    fun confirm() = feedback.performHapticFeedback(HapticFeedbackType.LongPress)
    fun toggle() = feedback.performHapticFeedback(HapticFeedbackType.TextHandleMove)
}

@Composable
fun rememberHaptics(): Haptics {
    val feedback = LocalHapticFeedback.current
    return remember(feedback) { Haptics(feedback) }
}
