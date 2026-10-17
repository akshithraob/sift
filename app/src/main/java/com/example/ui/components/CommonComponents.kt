package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalance
import androidx.compose.material.icons.filled.CreditCard
import androidx.compose.material.icons.filled.Payment
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.parser.Confidence
import com.example.ui.theme.ConfidenceHigh
import com.example.ui.theme.ConfidenceLow
import com.example.ui.theme.ConfidenceMedium
import com.example.ui.theme.RailCard
import com.example.ui.theme.RailNach
import com.example.ui.theme.RailSi
import com.example.ui.theme.RailUpi
import com.example.ui.theme.StatusActive
import com.example.ui.theme.StatusCancelled
import com.example.ui.theme.StatusPaused

@Composable
fun PaymentRailBadge(rail: String, modifier: Modifier = Modifier) {
    val (bgColor, textColor, icon, label) = when (rail.uppercase()) {
        "UPI" -> Quad(RailUpi.copy(alpha = 0.18f), RailUpi, Icons.Default.Payment, "UPI Autopay")
        "NACH" -> Quad(RailNach.copy(alpha = 0.18f), RailNach, Icons.Default.AccountBalance, "e-NACH Debit")
        "SI" -> Quad(RailSi.copy(alpha = 0.18f), RailSi, Icons.Default.Repeat, "Standing Inst.")
        "CARD" -> Quad(RailCard.copy(alpha = 0.18f), RailCard, Icons.Default.CreditCard, "Card Recurring")
        else -> Quad(Color.Gray.copy(alpha = 0.18f), Color.Gray, Icons.Default.Payment, rail)
    }

    Surface(
        shape = RoundedCornerShape(8.dp),
        color = bgColor,
        modifier = modifier
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = textColor,
                modifier = Modifier.size(13.dp)
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = label,
                color = textColor,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

private data class Quad<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)

@Composable
fun StatusBadge(status: String, modifier: Modifier = Modifier) {
    val (color, label) = when (status.uppercase()) {
        "ACTIVE" -> StatusActive to "Active"
        "PAUSED" -> StatusPaused to "Paused"
        "CANCELLED" -> StatusCancelled to "Cancelled"
        else -> Color.Gray to status
    }

    Surface(
        shape = RoundedCornerShape(20.dp),
        color = color.copy(alpha = 0.15f),
        modifier = modifier
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(color)
            )
            Spacer(modifier = Modifier.width(5.dp))
            Text(
                text = label,
                color = color,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

@Composable
fun ConfidenceBadge(confidence: Confidence, modifier: Modifier = Modifier) {
    val (color, label) = when (confidence) {
        Confidence.HIGH -> ConfidenceHigh to "High Confidence"
        Confidence.MEDIUM -> ConfidenceMedium to "Review Suggested"
        Confidence.LOW -> ConfidenceLow to "Low Confidence"
    }

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = color.copy(alpha = 0.16f),
        modifier = modifier
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(color)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = label,
                color = color,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}
