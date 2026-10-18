package com.leakproof.app.ui.screens

import androidx.compose.ui.Alignment
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.leakproof.app.ui.theme.BrandPrimary
import com.leakproof.app.ui.viewmodel.LeakproofViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ManualAddEditScreen(
    subscriptionId: Long? = null,
    viewModel: LeakproofViewModel,
    onNavigateBack: () -> Unit
) {
    val subscriptions by viewModel.allSubscriptions.collectAsState()
    val existing = if (subscriptionId != null && subscriptionId != 0L) {
        subscriptions.find { it.id == subscriptionId }
    } else null

    var merchant by remember { mutableStateOf(existing?.merchant ?: "") }
    var amountText by remember { mutableStateOf(existing?.amount?.let { if (it % 1.0 == 0.0) it.toLong().toString() else it.toString() } ?: "") }
    var frequency by remember { mutableStateOf(existing?.frequency ?: "MONTHLY") }
    var rail by remember { mutableStateOf(existing?.rail ?: "UPI") }
    var nextDate by remember {
        mutableStateOf(
            existing?.nextDate ?: java.text.SimpleDateFormat("dd MMM yyyy", java.util.Locale.ENGLISH)
                .format(java.util.Date(System.currentTimeMillis() + 30L * 24 * 3600 * 1000))
        )
    }
    var last4 by remember { mutableStateOf(existing?.last4 ?: "") }
    var notes by remember { mutableStateOf(existing?.notes ?: "") }

    val dateFormat = remember { java.text.SimpleDateFormat("dd MMM yyyy", java.util.Locale.ENGLISH).apply { isLenient = false } }
    var isTrial by remember { mutableStateOf(existing?.trialEndEpochMs != null) }
    var trialEndText by remember {
        mutableStateOf(existing?.trialEndEpochMs?.let { dateFormat.format(java.util.Date(it)) } ?: "")
    }

    var frequencyExpanded by remember { mutableStateOf(false) }
    var railExpanded by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    val frequencies = listOf("MONTHLY", "YEARLY", "WEEKLY", "QUARTERLY", "AS_PRESENTED")
    val rails = listOf("UPI", "NACH", "SI", "CARD", "OTHER")

    val popularMerchants = listOf(
        "Netflix", "Spotify", "Disney+ Hotstar", "Amazon Prime",
        "Zerodha SIP", "Groww SIP", "Cult.fit", "Airtel Postpaid", "Swiggy One"
    )

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = if (existing != null) "Edit Mandate" else "Add Mandate Manually",
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack, modifier = Modifier.testTag("add_edit_back_button")) {
                        Icon(imageVector = Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 20.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Spacer(modifier = Modifier.height(10.dp))

            // Quick merchant suggestion chips
            if (existing == null) {
                Text(
                    text = "QUICK SUGGESTIONS",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    letterSpacing = 0.8.sp
                )
                Spacer(modifier = Modifier.height(6.dp))
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    items(popularMerchants) { name ->
                        SuggestionChip(
                            onClick = { merchant = name },
                            label = { Text(name, fontSize = 12.sp) }
                        )
                    }
                }
                Spacer(modifier = Modifier.height(14.dp))
            }

            // Merchant Name
            OutlinedTextField(
                value = merchant,
                onValueChange = {
                    merchant = it
                    errorMessage = null
                },
                label = { Text("Merchant / Service Name *") },
                placeholder = { Text("e.g. Netflix, Zerodha Coin, BESCOM") },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("merchant_input_field")
            )

            Spacer(modifier = Modifier.height(14.dp))

            // Amount (₹)
            OutlinedTextField(
                value = amountText,
                onValueChange = {
                    amountText = it
                    errorMessage = null
                },
                label = { Text("Recurring Amount (₹) *") },
                placeholder = { Text("e.g. 649") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("amount_input_field")
            )

            Spacer(modifier = Modifier.height(14.dp))

            // Payment Rail Dropdown
            ExposedDropdownMenuBox(
                expanded = railExpanded,
                onExpandedChange = { railExpanded = !railExpanded }
            ) {
                OutlinedTextField(
                    value = when (rail) {
                        "UPI" -> "UPI Autopay"
                        "NACH" -> "e-NACH / ACH Debit"
                        "SI" -> "Standing Instruction"
                        "CARD" -> "Card Recurring"
                        else -> "Other Mandate"
                    },
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("Payment Rail") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = railExpanded) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                        .testTag("rail_dropdown")
                )
                ExposedDropdownMenu(
                    expanded = railExpanded,
                    onDismissRequest = { railExpanded = false }
                ) {
                    rails.forEach { item ->
                        val label = when (item) {
                            "UPI" -> "UPI Autopay"
                            "NACH" -> "e-NACH / ACH Debit"
                            "SI" -> "Standing Instruction"
                            "CARD" -> "Card Recurring"
                            else -> "Other Mandate"
                        }
                        DropdownMenuItem(
                            text = { Text(label) },
                            onClick = {
                                rail = item
                                railExpanded = false
                            }
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Frequency Dropdown
            ExposedDropdownMenuBox(
                expanded = frequencyExpanded,
                onExpandedChange = { frequencyExpanded = !frequencyExpanded }
            ) {
                OutlinedTextField(
                    value = frequency.replace("_", " ").lowercase().replaceFirstChar { it.uppercase() },
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("Frequency") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = frequencyExpanded) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                        .testTag("frequency_dropdown")
                )
                ExposedDropdownMenu(
                    expanded = frequencyExpanded,
                    onDismissRequest = { frequencyExpanded = false }
                ) {
                    frequencies.forEach { item ->
                        DropdownMenuItem(
                            text = { Text(item.replace("_", " ").lowercase().replaceFirstChar { it.uppercase() }) },
                            onClick = {
                                frequency = item
                                frequencyExpanded = false
                            }
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Next Debit Date
            OutlinedTextField(
                value = nextDate,
                onValueChange = { nextDate = it },
                label = { Text("Next Renewal Date") },
                placeholder = { Text("e.g. 24 Oct 2026") },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("next_date_input_field")
            )

            Spacer(modifier = Modifier.height(14.dp))

            // Free trial
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("This is a free trial", fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                    Text(
                        "We'll remind you before it converts into a paid plan.",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                androidx.compose.material3.Switch(
                    checked = isTrial,
                    onCheckedChange = { isTrial = it; errorMessage = null },
                    modifier = Modifier.testTag("trial_switch")
                )
            }
            if (isTrial) {
                Spacer(modifier = Modifier.height(10.dp))
                OutlinedTextField(
                    value = trialEndText,
                    onValueChange = { trialEndText = it; errorMessage = null },
                    label = { Text("Trial ends on") },
                    placeholder = { Text("e.g. 25 Oct 2026") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("trial_end_input_field")
                )
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Last 4 Digits of Account
            OutlinedTextField(
                value = last4,
                onValueChange = {
                    val digits = it.filter { ch -> ch.isDigit() }
                    if (digits.length <= 4) {
                        last4 = digits
                    }
                },
                label = { Text("Account Last 4 Digits (Optional)") },
                placeholder = { Text("e.g. 4821") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("last4_input_field")
            )

            Spacer(modifier = Modifier.height(14.dp))

            // Notes
            OutlinedTextField(
                value = notes,
                onValueChange = { notes = it },
                label = { Text("Notes (Optional)") },
                placeholder = { Text("e.g. 4K Family plan, debited from salary account") },
                modifier = Modifier.fillMaxWidth()
            )

            if (errorMessage != null) {
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = errorMessage ?: "",
                    color = MaterialTheme.colorScheme.error,
                    fontSize = 13.sp
                )
            }

            Spacer(modifier = Modifier.height(28.dp))

            Button(
                onClick = {
                    if (merchant.isBlank()) {
                        errorMessage = "Please enter merchant name"
                        return@Button
                    }
                    val amountVal = amountText.toDoubleOrNull()
                    if (amountVal == null || amountVal <= 0) {
                        errorMessage = "Please enter a valid positive amount"
                        return@Button
                    }

                    val trialEndMs: Long? = if (isTrial) {
                        val parsed = runCatching { dateFormat.parse(trialEndText.trim())?.time }.getOrNull()
                        if (parsed == null) {
                            errorMessage = "Enter the trial end date like 25 Oct 2026"
                            return@Button
                        }
                        parsed
                    } else null
                    val nextDateMs = runCatching { dateFormat.parse(nextDate.trim())?.time }.getOrNull()

                    viewModel.addOrUpdateSubscription(
                        id = existing?.id ?: 0L,
                        merchant = merchant.trim(),
                        amount = amountVal,
                        frequency = frequency,
                        nextDate = nextDate.trim().ifBlank { "Upcoming" },
                        nextDateEpochMs = nextDateMs,
                        trialEndEpochMs = trialEndMs,
                        rail = rail,
                        source = existing?.source ?: "MANUAL",
                        last4 = last4.ifBlank { null },
                        notes = notes.ifBlank { null },
                        onComplete = onNavigateBack
                    )
                },
                colors = ButtonDefaults.buttonColors(containerColor = BrandPrimary),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp)
                    .testTag("save_subscription_button")
            ) {
                Icon(imageVector = Icons.Default.Check, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = if (existing != null) "Save Changes" else "Add Subscription",
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp
                )
            }

            Spacer(modifier = Modifier.height(40.dp))
        }
    }
}
