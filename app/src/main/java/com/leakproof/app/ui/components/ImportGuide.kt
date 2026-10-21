package com.leakproof.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.leakproof.app.ui.theme.BrandPrimary

/**
 * Shown before the user adds anything. Names are read from messy bank text and screenshots, so the
 * parser sometimes gets them wrong; this asks the user to check and rename.
 * [onContinue] receives true when "Don't show this again" is ticked.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImportGuideSheet(onContinue: (dontShowAgain: Boolean) -> Unit) {
    var dontShowAgain by remember { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = { onContinue(dontShowAgain) },
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .navigationBarsPadding()
                .testTag("import_guide")
        ) {
          // The explanation scrolls; the checkbox and button stay pinned so "Got it" is always in reach
          Column(modifier = Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
            Box(
                modifier = Modifier.size(52.dp).clip(CircleShape).background(BrandPrimary.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.Edit, contentDescription = null, tint = BrandPrimary, modifier = Modifier.size(26.dp))
            }
            Spacer(Modifier.height(14.dp))
            Text(
                "Check the names after you add",
                fontWeight = FontWeight.ExtraBold, fontSize = 22.sp, color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "Leakproof finds your recurring payments from messy bank text and screenshots. It does a lot of the work, " +
                    "but it can get a name wrong or cut short. You know your own subscriptions best.",
                fontSize = 14.sp, lineHeight = 20.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.height(18.dp))
            GuideStep("1", "Add", "Import screenshots or a statement, or add one yourself.")
            GuideStep("2", "Review", "On the next screen, look at each item: the name, the amount, how often it renews and the next date.")
            GuideStep("3", "Rename", "Tap Edit to fix a name before you confirm. You can also rename it later from the subscription's page.")

            Spacer(Modifier.height(6.dp))
            AppCard {
                Column(Modifier.padding(16.dp)) {
                    Text("What a fix looks like", fontWeight = FontWeight.Bold, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface)
                    Spacer(Modifier.height(8.dp))
                    ExampleRow("Si Canva Pro", "Canva Pro")
                    ExampleRow("Subscription via Paytm", "Cult.fit")
                    ExampleRow("Nach Dr Term Insurance", "Term insurance")
                }
            }

          }
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { dontShowAgain = !dontShowAgain },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Checkbox(
                    checked = dontShowAgain,
                    onCheckedChange = { dontShowAgain = it },
                    colors = CheckboxDefaults.colors(checkedColor = BrandPrimary),
                    modifier = Modifier.testTag("guide_dont_show_again")
                )
                Text("Don't show this again", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface)
            }

            Spacer(Modifier.height(8.dp))
            Button(
                onClick = { onContinue(dontShowAgain) },
                colors = ButtonDefaults.buttonColors(containerColor = BrandPrimary, contentColor = androidx.compose.ui.graphics.Color.White),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth().height(54.dp).testTag("guide_continue_button")
            ) { Text("Got it, continue", fontWeight = FontWeight.ExtraBold, fontSize = 16.sp) }
            Spacer(Modifier.height(20.dp))
        }
    }
}

@Composable
private fun GuideStep(number: String, title: String, body: String) {
    Row(modifier = Modifier.padding(bottom = 14.dp), verticalAlignment = Alignment.Top) {
        Box(
            modifier = Modifier.size(30.dp).clip(CircleShape).background(BrandPrimary),
            contentAlignment = Alignment.Center
        ) {
            Text(number, color = androidx.compose.ui.graphics.Color.White, fontWeight = FontWeight.ExtraBold, fontSize = 14.sp)
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.Bold, fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurface)
            Text(body, fontSize = 13.sp, lineHeight = 19.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 2.dp))
        }
    }
}

@Composable
private fun ExampleRow(from: String, to: String) {
    Row(modifier = Modifier.padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(from, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        Icon(Icons.Default.ArrowForward, contentDescription = "becomes", tint = BrandPrimary, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(8.dp))
        Text(to, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
    }
}

/** A standing reminder at the top of the Review screen. */
@Composable
fun NameCheckTip(modifier: Modifier = Modifier) {
    AppCard(modifier = modifier.fillMaxWidth().testTag("name_check_tip")) {
        Row(modifier = Modifier.padding(14.dp), verticalAlignment = Alignment.Top) {
            Icon(Icons.Default.Edit, contentDescription = null, tint = BrandPrimary, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(12.dp))
            Column {
                Text("Check the names before you confirm", fontWeight = FontWeight.Bold, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface)
                Text(
                    "Names come from messy text and can be wrong or cut short. Tap Edit on a card to rename it.",
                    fontSize = 12.sp, lineHeight = 17.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
        }
    }
}
