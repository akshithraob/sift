package com.leakproof.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.leakproof.app.ui.theme.BrandPrimary

/**
 * DPDP Act notice and consent. Consent is a clear, separate action (no pre-ticked box), the
 * notice says what is processed, why, and how to withdraw, and declining simply closes the app.
 */
@Composable
fun ConsentScreen(
    onAgree: () -> Unit,
    onDecline: () -> Unit,
    onReadPolicy: () -> Unit
) {
    Scaffold(containerColor = MaterialTheme.colorScheme.background) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 24.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Spacer(modifier = Modifier.height(28.dp))
            Icon(
                imageVector = Icons.Default.Shield,
                contentDescription = null,
                tint = BrandPrimary,
                modifier = Modifier.size(44.dp)
            )
            Spacer(modifier = Modifier.height(14.dp))
            Text(
                text = "Your data stays on your phone",
                fontWeight = FontWeight.ExtraBold,
                fontSize = 24.sp,
                color = MaterialTheme.colorScheme.onBackground
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "Before you start, here is what Leakproof does with your information.",
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(18.dp))
            NoticeItem(
                "What it handles",
                "Screenshots and bank statements you choose, plus the subscription details you add: merchant, amount, renewal date, payment type, last 4 digits of the account and your notes."
            )
            NoticeItem(
                "Why",
                "Only to list your recurring payments, remind you before they renew, flag overlaps and high mandate limits, and track your budget."
            )
            NoticeItem(
                "Where it goes",
                "Nowhere. It is processed and stored encrypted on this phone. The app has no network permission, no ads and no analytics, and shares nothing with anyone."
            )
            NoticeItem(
                "Your control",
                "View, edit, export or delete your data at any time. You can withdraw this consent in Settings, which erases your data from the app."
            )

            TextButton(
                onClick = onReadPolicy,
                modifier = Modifier.testTag("read_policy_button")
            ) {
                Text("Read the full privacy policy", fontWeight = FontWeight.SemiBold)
            }

            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "By tapping \"I agree\", you consent to Leakproof processing your information on your device for the purposes above.",
                fontSize = 12.sp,
                lineHeight = 17.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(14.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedButton(
                    onClick = onDecline,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .weight(1f)
                        .height(50.dp)
                        .testTag("consent_decline_button")
                ) {
                    Text("No thanks")
                }
                Button(
                    onClick = onAgree,
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = BrandPrimary),
                    modifier = Modifier
                        .weight(1.6f)
                        .height(50.dp)
                        .testTag("consent_agree_button")
                ) {
                    Text("I agree", fontWeight = FontWeight.Bold)
                }
            }
            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

@Composable
private fun NoticeItem(title: String, body: String) {
    Column(modifier = Modifier.fillMaxWidth().padding(bottom = 14.dp)) {
        Text(
            text = title,
            fontWeight = FontWeight.Bold,
            fontSize = 15.sp,
            color = MaterialTheme.colorScheme.onBackground
        )
        Spacer(modifier = Modifier.width(1.dp))
        Text(
            text = body,
            fontSize = 13.sp,
            lineHeight = 19.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
