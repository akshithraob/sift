package com.leakproof.app.ui.screens

import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.leakproof.app.data.local.SubscriptionEntity
import com.leakproof.app.insights.MatchedCandidate
import com.leakproof.app.parser.Confidence
import com.leakproof.app.parser.Frequency
import com.leakproof.app.parser.ParsedMandateCandidate
import com.leakproof.app.ui.components.ConfidenceBadge
import com.leakproof.app.ui.components.PaymentRailBadge
import com.leakproof.app.ui.theme.BrandPrimary
import com.leakproof.app.ui.theme.StatusCancelled
import com.leakproof.app.ui.viewmodel.LeakproofViewModel
import com.leakproof.app.util.CurrencyFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReviewScreen(
    viewModel: LeakproofViewModel,
    onNavigateBack: () -> Unit,
    onSavedSuccessfully: () -> Unit
) {
    val candidates by viewModel.pendingCandidates.collectAsState()
    val diff by viewModel.importDiff.collectAsState()
    val missingToCancel by viewModel.missingToCancel.collectAsState()
    var editingCandidate by remember { mutableStateOf<ParsedMandateCandidate?>(null) }

    if (editingCandidate != null) {
        val current = editingCandidate!!
        var editMerchant by remember { mutableStateOf(current.merchant) }
        var editAmount by remember { mutableStateOf(current.amount.toString()) }
        var editDate by remember { mutableStateOf(current.nextDebitDate ?: "") }

        AlertDialog(
            onDismissRequest = { editingCandidate = null },
            title = { Text("Edit Detected Mandate", fontWeight = FontWeight.Bold) },
            text = {
                Column {
                    OutlinedTextField(
                        value = editMerchant,
                        onValueChange = { editMerchant = it },
                        label = { Text("Merchant") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    OutlinedTextField(
                        value = editAmount,
                        onValueChange = { editAmount = it },
                        label = { Text("Amount (₹)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    OutlinedTextField(
                        value = editDate,
                        onValueChange = { editDate = it },
                        label = { Text("Next Renewal Date") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val parsedAmt = editAmount.toDoubleOrNull() ?: current.amount
                        val updated = current.copy(
                            merchant = editMerchant,
                            amount = parsedAmt,
                            nextDebitDate = editDate
                        )
                        viewModel.updatePendingCandidate(updated)
                        editingCandidate = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = BrandPrimary)
                ) {
                    Text("Save Changes")
                }
            },
            dismissButton = {
                TextButton(onClick = { editingCandidate = null }) {
                    Text("Cancel")
                }
            }
        )
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("Review Mandates (${candidates.size})", fontWeight = FontWeight.Bold, fontSize = 18.sp) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack, modifier = Modifier.testTag("review_back_button")) {
                        Icon(imageVector = Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        },
        bottomBar = {
            if (candidates.isNotEmpty()) {
                Surface(
                    color = MaterialTheme.colorScheme.surface,
                    tonalElevation = 8.dp,
                    shadowElevation = 8.dp
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .navigationBarsPadding()
                            .padding(16.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        OutlinedButton(
                            onClick = onNavigateBack,
                            modifier = Modifier
                                .weight(1f)
                                .height(50.dp)
                                .testTag("discard_review_button")
                        ) {
                            Text("Discard")
                        }

                        Button(
                            onClick = {
                                viewModel.confirmCandidates(candidates, applyCancellations = true) {
                                    onSavedSuccessfully()
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = BrandPrimary),
                            modifier = Modifier
                                .weight(2f)
                                .height(50.dp)
                                .testTag("confirm_all_button")
                        ) {
                            Icon(imageVector = Icons.Default.Check, contentDescription = null)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Confirm All (${candidates.size})", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    ) { paddingValues ->
        if (candidates.isEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Shield,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(56.dp)
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "No Mandates to Review",
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "All items have been approved or dismissed.",
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(20.dp))
                Button(
                    onClick = onNavigateBack,
                    colors = ButtonDefaults.buttonColors(containerColor = BrandPrimary)
                ) {
                    Text("Return to Dashboard")
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                diff?.let { d ->
                    if (d.matches.isNotEmpty() || d.missing.isNotEmpty() || d.unseen.isNotEmpty()) {
                        item(key = "diff_summary") {
                            ImportDiffSummary(
                                newCount = d.newCandidates.size,
                                changedCount = d.matches.count { it.hasChanges },
                                alreadyTrackedCount = d.matches.count { !it.hasChanges }
                            )
                        }
                    }
                }
                item(key = "review_notice") {
                    val notice by viewModel.reviewNotice.collectAsState()
                    notice?.let {
                        Text(
                            text = it,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = androidx.compose.ui.graphics.Color(0xFFB26A00),
                            modifier = Modifier.padding(bottom = 6.dp).testTag("review_notice")
                        )
                    }
                }
                item(key = "swipe_hint") {
                    Text(
                        text = "Swipe right to confirm, left to dismiss, or use the buttons.",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.testTag("swipe_hint")
                    )
                }
                items(candidates, key = { it.id }) { candidate ->
                    val confirm = {
                        viewModel.confirmCandidates(listOf(candidate)) {
                            if (candidates.size <= 1) {
                                onSavedSuccessfully()
                            }
                        }
                    }
                    val dismiss = { viewModel.removePendingCandidate(candidate.id) }
                    SwipeableCandidate(
                        onConfirm = confirm,
                        onDismiss = dismiss,
                        modifier = Modifier.animateItem()
                    ) {
                        CandidateReviewCard(
                            diffNote = diff?.matchFor(candidate.id)?.let { diffNote(it) },
                            candidate = candidate,
                            onConfirm = confirm,
                            onDismiss = dismiss,
                            onEdit = {
                                editingCandidate = candidate
                            }
                        )
                    }
                }
                diff?.missing?.takeIf { it.isNotEmpty() }?.let { missing ->
                    item(key = "missing_section") {
                        MissingMandatesCard(
                            title = "No longer in this import",
                            body = "These were imported from a UPI app before but aren't in this screenshot. " +
                                "If you cancelled them, tick them to mark as cancelled. Leave unticked if your screenshot only covers part of the list.",
                            tag = "missing_mandates_card",
                            missing = missing,
                            selected = missingToCancel,
                            onToggle = { viewModel.toggleMissingToCancel(it) }
                        )
                    }
                }
                diff?.unseen?.takeIf { it.isNotEmpty() }?.let { unseen ->
                    item(key = "unseen_section") {
                        MissingMandatesCard(
                            title = "Not charged in this statement",
                            body = "These mandates are active in Leakproof, but this statement shows no debit for them. " +
                                "They may be unused: if so, revoke them in your UPI app, and tick them here to mark them cancelled.",
                            tag = "unseen_mandates_card",
                            missing = unseen,
                            selected = missingToCancel,
                            onToggle = { viewModel.toggleMissingToCancel(it) }
                        )
                    }
                }
            }
        }
    }
}

/** Swipe right to confirm, left to dismiss. The card's own buttons still work for everyone else. */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun SwipeableCandidate(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    val haptics = com.leakproof.app.ui.components.rememberHaptics()
    val state = androidx.compose.material3.rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            when (value) {
                androidx.compose.material3.SwipeToDismissBoxValue.StartToEnd -> { haptics.confirm(); onConfirm(); true }
                androidx.compose.material3.SwipeToDismissBoxValue.EndToStart -> { haptics.tap(); onDismiss(); true }
                else -> false
            }
        },
        positionalThreshold = { distance -> distance * 0.4f }
    )
    androidx.compose.material3.SwipeToDismissBox(
        state = state,
        modifier = modifier,
        backgroundContent = {
            val direction = state.dismissDirection
            val confirming = direction == androidx.compose.material3.SwipeToDismissBoxValue.StartToEnd
            val color = when (direction) {
                androidx.compose.material3.SwipeToDismissBoxValue.StartToEnd -> com.leakproof.app.ui.theme.StatusActive
                androidx.compose.material3.SwipeToDismissBoxValue.EndToStart -> StatusCancelled
                else -> Color.Transparent
            }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(20.dp))
                    .background(color.copy(alpha = 0.18f))
                    .padding(horizontal = 24.dp),
                contentAlignment = if (confirming) Alignment.CenterStart else Alignment.CenterEnd
            ) {
                if (direction != androidx.compose.material3.SwipeToDismissBoxValue.Settled) {
                    Icon(
                        imageVector = if (confirming) Icons.Default.Check else Icons.Default.Close,
                        contentDescription = if (confirming) "Confirm" else "Dismiss",
                        tint = color,
                        modifier = Modifier.size(28.dp)
                    )
                }
            }
        }
    ) { content() }
}

private fun diffNote(match: MatchedCandidate): String = when {
    match.amountChanged && match.dateChanged ->
        "Already tracked: price ${CurrencyFormatter.format(match.existing.amount)} → ${CurrencyFormatter.format(match.candidate.amount)}, date updated"
    match.amountChanged ->
        "Already tracked: price ${CurrencyFormatter.format(match.existing.amount)} → ${CurrencyFormatter.format(match.candidate.amount)}"
    match.dateChanged -> "Already tracked: renewal date updated"
    else -> "Already tracked, no changes"
}

@Composable
private fun ImportDiffSummary(newCount: Int, changedCount: Int, alreadyTrackedCount: Int) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = BrandPrimary.copy(alpha = 0.12f),
        modifier = Modifier.fillMaxWidth().testTag("import_diff_summary")
    ) {
        Text(
            text = "Compared with what you already track: $newCount new, $changedCount changed, " +
                "$alreadyTrackedCount already tracked. Existing entries are updated, not duplicated.",
            fontSize = 12.sp,
            lineHeight = 17.sp,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(12.dp)
        )
    }
}

@Composable
private fun MissingMandatesCard(
    title: String,
    body: String,
    tag: String,
    missing: List<SubscriptionEntity>,
    selected: Set<Long>,
    onToggle: (Long) -> Unit
) {
    Card(
        shape = RoundedCornerShape(20.dp),
        border = com.leakproof.app.ui.components.appCardBorder(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth().testTag(tag)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = title,
                fontWeight = FontWeight.Bold,
                fontSize = 15.sp,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = body,
                fontSize = 12.sp,
                lineHeight = 17.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(6.dp))
            missing.forEach { sub ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().testTag("missing_row_${sub.id}")
                ) {
                    androidx.compose.material3.Checkbox(checked = sub.id in selected, onCheckedChange = { onToggle(sub.id) })
                    Text(
                        text = "${sub.merchant}  ${CurrencyFormatter.format(sub.amount)}",
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }
    }
}

@Composable
fun CandidateReviewCard(
    candidate: ParsedMandateCandidate,
    diffNote: String? = null,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    onEdit: () -> Unit
) {
    Card(
        shape = RoundedCornerShape(20.dp),
        border = com.leakproof.app.ui.components.appCardBorder(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("candidate_card_${candidate.id}")
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                PaymentRailBadge(rail = candidate.rail.name)
                ConfidenceBadge(confidence = candidate.confidence)
            }

            if (diffNote != null) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = diffNote,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = BrandPrimary,
                    modifier = Modifier.testTag("diff_note_${candidate.id}")
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = candidate.merchant,
                        fontWeight = FontWeight.Bold,
                        fontSize = 17.sp,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    if (!candidate.sourceApp.isNullOrBlank()) {
                        Text(
                            text = "Detected in ${candidate.sourceApp}" +
                                if (candidate.kind != com.leakproof.app.parser.RecurringKind.SUBSCRIPTION) " · ${candidate.kind.label}" else "",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Text(
                    text = CurrencyFormatter.format(candidate.amount),
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 20.sp,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "Frequency: ${candidate.frequency.displayName}",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = "Next: ${candidate.nextDebitDate ?: "N/A"}",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }

            if (candidate.confidenceReason.isNotBlank()) {
                Spacer(modifier = Modifier.height(8.dp))
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = candidate.confidenceReason,
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(8.dp),
                        lineHeight = 15.sp
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = onDismiss,
                    shape = RoundedCornerShape(10.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 6.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = StatusCancelled),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(imageVector = Icons.Default.Close, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Dismiss", fontSize = 12.sp, maxLines = 1, softWrap = false)
                }

                OutlinedButton(
                    onClick = onEdit,
                    shape = RoundedCornerShape(10.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 6.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(imageVector = Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Edit", fontSize = 12.sp, maxLines = 1, softWrap = false)
                }

                Button(
                    onClick = onConfirm,
                    shape = RoundedCornerShape(10.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 6.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = BrandPrimary),
                    modifier = Modifier.weight(1.3f)
                ) {
                    Icon(imageVector = Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Confirm", fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false)
                }
            }
        }
    }
}
