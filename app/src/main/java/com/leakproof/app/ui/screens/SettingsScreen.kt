package com.leakproof.app.ui.screens

import androidx.compose.material3.OutlinedTextField
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.PublicOff
import androidx.compose.material.icons.filled.Security
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
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.leakproof.app.ui.theme.BrandPrimary
import com.leakproof.app.ui.theme.StatusCancelled
import com.leakproof.app.ui.viewmodel.LeakproofViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: LeakproofViewModel,
    onNavigateBack: () -> Unit,
    onOpenPrivacyPolicy: () -> Unit,
    onDataWiped: () -> Unit
) {
    val context = LocalContext.current
    val isAppLockEnabled by viewModel.isAppLockSettingEnabled.collectAsState()
    val isNotificationsEnabled by viewModel.isNotificationsEnabled.collectAsState()
    val notificationPermissionLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { granted -> viewModel.setNotificationsEnabled(granted) }
    var showWipeConfirmDialog by remember { mutableStateOf(false) }

    fun openBrowserUrl(url: String) {
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            context.startActivity(intent)
        } catch (_: Exception) {
            Toast.makeText(context, "Cannot open link", Toast.LENGTH_SHORT).show()
        }
    }

    if (showWipeConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showWipeConfirmDialog = false },
            icon = {
                Icon(
                    imageVector = Icons.Default.DeleteForever,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(36.dp)
                )
            },
            title = {
                Text(
                    text = "Delete all data & withdraw consent?",
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.error
                )
            },
            text = {
                Text(
                    text = "This will permanently wipe your SQLCipher encrypted database, delete the AES master key from the Android Keystore, and reset all app preferences, including your consent. You will be asked to consent again to use the app. This action CANNOT be undone.",
                    lineHeight = 19.sp
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showWipeConfirmDialog = false
                        viewModel.wipeAllData {
                            Toast.makeText(context, "All data and cryptographic keys wiped", Toast.LENGTH_LONG).show()
                            onDataWiped()
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    modifier = Modifier.testTag("confirm_wipe_button")
                ) {
                    Text("Permanently Wipe All Data")
                }
            },
            dismissButton = {
                TextButton(onClick = { showWipeConfirmDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("Settings & Privacy", fontWeight = FontWeight.Bold, fontSize = 18.sp) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack, modifier = Modifier.testTag("settings_back_button")) {
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
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Spacer(modifier = Modifier.height(8.dp))

            // Security & Protection Section
            SettingsSectionHeader(title = "SECURITY & ACCESS")

            Card(
                shape = RoundedCornerShape(20.dp),
                border = com.leakproof.app.ui.components.appCardBorder(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    // App Lock Toggle
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Fingerprint,
                                contentDescription = null,
                                tint = BrandPrimary,
                                modifier = Modifier.size(24.dp)
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                Text(
                                    text = "Biometric & PIN Lock",
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 15.sp,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = "Require fingerprint, face, or PIN to open vault",
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        Switch(
                            checked = isAppLockEnabled,
                            onCheckedChange = { viewModel.setAppLockEnabled(it) },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = BrandPrimary,
                                checkedTrackColor = BrandPrimary.copy(alpha = 0.4f)
                            ),
                            modifier = Modifier.testTag("toggle_app_lock")
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // FLAG_SECURE status
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Security,
                            contentDescription = null,
                            tint = BrandPrimary,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text(
                                text = "Screen Capture Protection (FLAG_SECURE)",
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = if (com.leakproof.app.BuildConfig.DEBUG) {
                                    "Off in debug builds. Release builds block screenshots and screen recording across the entire app."
                                } else {
                                    "Active. Screenshots and screen recording are blocked across the entire app."
                                },
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // Notifications Section
            SettingsSectionHeader(title = "NOTIFICATIONS")

            Card(
                shape = RoundedCornerShape(20.dp),
                border = com.leakproof.app.ui.components.appCardBorder(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Notifications,
                            contentDescription = null,
                            tint = BrandPrimary,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(
                                text = "Renewal Reminders",
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 15.sp,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = "Get notified 2 days before renewals via local WorkManager (no network used)",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    Switch(
                        checked = isNotificationsEnabled,
                        onCheckedChange = { enable ->
                            if (enable && !com.leakproof.app.reminder.RenewalReminderWorker.canNotify(context)) {
                                notificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                            } else {
                                viewModel.setNotificationsEnabled(enable)
                            }
                        },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = BrandPrimary,
                            checkedTrackColor = BrandPrimary.copy(alpha = 0.4f)
                        ),
                        modifier = Modifier.testTag("toggle_notifications")
                    )
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // Budget Section
            SettingsSectionHeader(title = "BUDGET")

            val savedBudget by viewModel.monthlyBudget.collectAsState()
            var budgetText by remember(savedBudget) {
                mutableStateOf(if (savedBudget > 0) savedBudget.toLong().toString() else "")
            }
            Card(
                shape = RoundedCornerShape(20.dp),
                border = com.leakproof.app.ui.components.appCardBorder(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Monthly subscription budget",
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 15.sp,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "The dashboard shows how close your monthly spend is to this amount. Leave empty for no budget.",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = budgetText,
                            onValueChange = { text -> budgetText = text.filter { it.isDigit() }.take(7) },
                            label = { Text("Budget (\u20B9 per month)") },
                            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                                keyboardType = androidx.compose.ui.text.input.KeyboardType.Number
                            ),
                            singleLine = true,
                            modifier = Modifier.weight(1f).testTag("budget_input_field")
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Button(
                            onClick = { viewModel.setMonthlyBudget(budgetText.toDoubleOrNull() ?: 0.0) },
                            colors = ButtonDefaults.buttonColors(containerColor = BrandPrimary),
                            modifier = Modifier.testTag("save_budget_button")
                        ) {
                            Text("Save")
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // Privacy & consent Section
            SettingsSectionHeader(title = "PRIVACY & CONSENT")
            val consentAt by viewModel.consentAtMs.collectAsState()
            Card(
                shape = RoundedCornerShape(20.dp),
                border = com.leakproof.app.ui.components.appCardBorder(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = if (consentAt > 0) {
                            "You agreed to the privacy policy (version ${viewModel.consentVersion}) on " +
                                java.text.SimpleDateFormat("dd MMM yyyy", java.util.Locale.ENGLISH).format(java.util.Date(consentAt)) + "."
                        } else "Consent recorded.",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.testTag("consent_status_text")
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    OutlinedButton(
                        onClick = onOpenPrivacyPolicy,
                        modifier = Modifier.fillMaxWidth().testTag("open_privacy_policy_button")
                    ) { Text("Read the privacy policy") }
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "To withdraw consent, use \"Delete all my data & withdraw consent\" under Danger zone below.",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // Backup & export Section
            SettingsSectionHeader(title = "BACKUP & EXPORT")
            BackupSection(viewModel)

            Spacer(modifier = Modifier.height(20.dp))

            // Privacy Guarantee Section
            SettingsSectionHeader(title = "PRIVACY & ZERO-CLOUD ARCHITECTURE")

            Card(
                shape = RoundedCornerShape(20.dp),
                border = com.leakproof.app.ui.components.appCardBorder(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    PrivacyBulletItem(
                        icon = Icons.Default.PublicOff,
                        title = "Zero Network Guarantee",
                        description = "Leakproof does NOT request the android.permission.INTERNET permission. It is impossible for data to leave this device."
                    )
                    Spacer(modifier = Modifier.height(14.dp))
                    PrivacyBulletItem(
                        icon = Icons.Default.Shield,
                        title = "SQLCipher Database Encryption",
                        description = "All tables are encrypted at rest with 256-bit AES cipher. The key is protected by the Android Keystore."
                    )
                    Spacer(modifier = Modifier.height(14.dp))
                    PrivacyBulletItem(
                        icon = Icons.Default.Lock,
                        title = "No Cloud Backup & No Logs",
                        description = "Excluded from Google Drive backup and device transfers. Debug logging is stripped in release builds."
                    )
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // NPCI UPI Help Resource
            Card(
                shape = RoundedCornerShape(20.dp),
                border = com.leakproof.app.ui.components.appCardBorder(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { openBrowserUrl("https://upihelp.npci.org.in") }
                    .testTag("settings_npci_link")
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Shield,
                        contentDescription = null,
                        tint = BrandPrimary,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "NPCI UPI Help & Dispute Portal",
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "Official National Payments Corporation portal for Autopay complaints",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(28.dp))

            // Danger Zone
            SettingsSectionHeader(title = "DANGER ZONE")

            Card(
                shape = RoundedCornerShape(20.dp),
                border = com.leakproof.app.ui.components.appCardBorder(),
                colors = CardDefaults.cardColors(
                    containerColor = StatusCancelled.copy(alpha = 0.08f)
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Withdraw consent & wipe all data",
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp,
                        color = StatusCancelled
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Drops the encrypted database file, purges master keys from the Keystore, and restores Leakproof to its initial fresh state.",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        lineHeight = 17.sp
                    )
                    Spacer(modifier = Modifier.height(14.dp))
                    Button(
                        onClick = { showWipeConfirmDialog = true },
                        colors = ButtonDefaults.buttonColors(containerColor = StatusCancelled),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("delete_all_data_button")
                    ) {
                        Icon(imageVector = Icons.Default.DeleteForever, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Delete all my data & withdraw consent", fontWeight = FontWeight.Bold)
                    }
                }
            }

            Spacer(modifier = Modifier.height(40.dp))
        }
    }
}

@Composable
private fun SettingsSectionHeader(title: String) {
    Text(
        text = title,
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        letterSpacing = 1.sp,
        modifier = Modifier.padding(horizontal = 4.dp, vertical = 6.dp)
    )
}

@Composable
private fun PrivacyBulletItem(icon: ImageVector, title: String, description: String) {
    Row(verticalAlignment = Alignment.Top) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = BrandPrimary,
            modifier = Modifier
                .size(20.dp)
                .padding(top = 2.dp)
        )
        Spacer(modifier = Modifier.width(10.dp))
        Column {
            Text(
                text = title,
                fontWeight = FontWeight.SemiBold,
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = description,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                lineHeight = 16.sp
            )
        }
    }
}

private enum class BackupDialog { CREATE, RESTORE, CSV_WARNING }

@Composable
private fun BackupSection(viewModel: LeakproofViewModel) {
    val result by viewModel.backupResult.collectAsState()
    var dialog by remember { mutableStateOf<BackupDialog?>(null) }
    var passphrase by remember { mutableStateOf("") }
    var confirmation by remember { mutableStateOf("") }
    var restoreUri by remember { mutableStateOf<Uri?>(null) }

    fun closeDialog() {
        dialog = null
        passphrase = ""
        confirmation = ""
    }

    val stamp = remember { java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.ENGLISH).format(java.util.Date()) }

    val createLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri ->
        if (uri != null) viewModel.createBackup(uri, passphrase.toCharArray())
        closeDialog()
    }
    val restoreLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            restoreUri = uri
            dialog = BackupDialog.RESTORE
        }
    }
    val csvLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.CreateDocument("text/csv")
    ) { uri ->
        if (uri != null) viewModel.exportCsv(uri)
    }

    val passwordKeyboard = androidx.compose.foundation.text.KeyboardOptions(
        keyboardType = androidx.compose.ui.text.input.KeyboardType.Password
    )

    when (dialog) {
        BackupDialog.CREATE -> {
            val tooShort = passphrase.length < com.leakproof.app.backup.BackupCrypto.MIN_PASSPHRASE_LENGTH
            val mismatch = passphrase != confirmation
            AlertDialog(
                onDismissRequest = { closeDialog() },
                title = { Text("Create encrypted backup", fontWeight = FontWeight.Bold) },
                text = {
                    Column {
                        Text(
                            "Choose a passphrase of at least ${com.leakproof.app.backup.BackupCrypto.MIN_PASSPHRASE_LENGTH} characters. " +
                                "Leakproof never stores it and cannot recover it: if you forget it, the backup cannot be opened.",
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        OutlinedTextField(
                            value = passphrase,
                            onValueChange = { passphrase = it },
                            label = { Text("Passphrase") },
                            singleLine = true,
                            visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                            keyboardOptions = passwordKeyboard,
                            modifier = Modifier.fillMaxWidth().testTag("backup_passphrase_field")
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedTextField(
                            value = confirmation,
                            onValueChange = { confirmation = it },
                            label = { Text("Repeat passphrase") },
                            singleLine = true,
                            isError = confirmation.isNotEmpty() && mismatch,
                            visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                            keyboardOptions = passwordKeyboard,
                            modifier = Modifier.fillMaxWidth().testTag("backup_confirm_field")
                        )
                    }
                },
                confirmButton = {
                    Button(
                        enabled = !tooShort && !mismatch,
                        onClick = { viewModel.beginExternalPicker(); createLauncher.launch("leakproof-backup-$stamp.lpbackup") },
                        colors = ButtonDefaults.buttonColors(containerColor = BrandPrimary),
                        modifier = Modifier.testTag("backup_choose_location_button")
                    ) { Text("Choose where to save") }
                },
                dismissButton = { TextButton(onClick = { closeDialog() }) { Text("Cancel") } }
            )
        }

        BackupDialog.RESTORE -> {
            AlertDialog(
                onDismissRequest = { closeDialog() },
                title = { Text("Restore backup", fontWeight = FontWeight.Bold) },
                text = {
                    Column {
                        Text(
                            "Enter the passphrase you chose when creating this backup. Subscriptions you already track are kept; " +
                                "only missing ones are added.",
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        OutlinedTextField(
                            value = passphrase,
                            onValueChange = { passphrase = it },
                            label = { Text("Passphrase") },
                            singleLine = true,
                            visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                            keyboardOptions = passwordKeyboard,
                            modifier = Modifier.fillMaxWidth().testTag("restore_passphrase_field")
                        )
                    }
                },
                confirmButton = {
                    Button(
                        enabled = passphrase.isNotEmpty() && restoreUri != null,
                        onClick = {
                            restoreUri?.let { viewModel.restoreBackup(it, passphrase.toCharArray()) }
                            restoreUri = null
                            closeDialog()
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = BrandPrimary),
                        modifier = Modifier.testTag("restore_confirm_button")
                    ) { Text("Restore") }
                },
                dismissButton = { TextButton(onClick = { restoreUri = null; closeDialog() }) { Text("Cancel") } }
            )
        }

        BackupDialog.CSV_WARNING -> {
            AlertDialog(
                onDismissRequest = { closeDialog() },
                title = { Text("Export is not encrypted", fontWeight = FontWeight.Bold) },
                text = {
                    Text(
                        "The CSV file lists every subscription in plain text. Anyone who gets the file can read it. " +
                            "Use the encrypted backup if you only want to keep your data safe.",
                        fontSize = 13.sp
                    )
                },
                confirmButton = {
                    Button(
                        onClick = {
                            closeDialog()
                            viewModel.beginExternalPicker(); csvLauncher.launch("leakproof-subscriptions-$stamp.csv")
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = BrandPrimary),
                        modifier = Modifier.testTag("csv_export_anyway_button")
                    ) { Text("Export anyway") }
                },
                dismissButton = { TextButton(onClick = { closeDialog() }) { Text("Cancel") } }
            )
        }

        null -> Unit
    }

    Card(
        shape = RoundedCornerShape(20.dp),
        border = com.leakproof.app.ui.components.appCardBorder(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "Your data stays on this phone and isn't backed up by Google. Make an encrypted backup to move to a new phone or recover after a reset.",
                fontSize = 12.sp,
                lineHeight = 17.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(12.dp))
            Button(
                onClick = { dialog = BackupDialog.CREATE },
                colors = ButtonDefaults.buttonColors(containerColor = BrandPrimary),
                modifier = Modifier.fillMaxWidth().testTag("create_backup_button")
            ) { Text("Create encrypted backup") }
            Spacer(modifier = Modifier.height(8.dp))
            androidx.compose.material3.OutlinedButton(
                onClick = { viewModel.beginExternalPicker(); restoreLauncher.launch(arrayOf("*/*")) },
                modifier = Modifier.fillMaxWidth().testTag("restore_backup_button")
            ) { Text("Restore from backup") }
            Spacer(modifier = Modifier.height(8.dp))
            androidx.compose.material3.OutlinedButton(
                onClick = { dialog = BackupDialog.CSV_WARNING },
                modifier = Modifier.fillMaxWidth().testTag("export_csv_button")
            ) { Text("Export as CSV (not encrypted)") }

            result?.let { r ->
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = r.message,
                    fontSize = 12.sp,
                    color = if (r.isError) MaterialTheme.colorScheme.error else BrandPrimary,
                    modifier = Modifier.testTag("backup_result_text")
                )
            }
        }
    }
}
