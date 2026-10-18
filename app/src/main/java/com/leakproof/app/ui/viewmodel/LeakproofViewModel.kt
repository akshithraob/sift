package com.leakproof.app.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.leakproof.app.LeakproofApplication
import com.leakproof.app.data.local.AppDatabase
import com.leakproof.app.data.local.SubscriptionEntity
import com.leakproof.app.data.model.MerchantGuide
import com.leakproof.app.parser.Confidence
import com.leakproof.app.parser.Frequency
import com.leakproof.app.parser.ParsedMandateCandidate
import com.leakproof.app.parser.PaymentRail
import com.leakproof.app.parser.SourceType
import com.leakproof.app.backup.BackupCodec
import com.leakproof.app.backup.BackupCrypto
import com.leakproof.app.backup.CsvExport
import com.leakproof.app.backup.InvalidBackupException
import com.leakproof.app.backup.WrongPassphraseException
import com.leakproof.app.backup.readAtMost
import com.leakproof.app.insights.ImportDiff
import com.leakproof.app.insights.ImportDiffResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class LeakproofViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as LeakproofApplication
    private val repository = app.subscriptionRepository
    private val merchantRepo = app.merchantRepository
    private val prefs = app.preferencesManager

    // Subscriptions list from encrypted Room DB
    val allSubscriptions: StateFlow<List<SubscriptionEntity>> = repository.getAllSubscriptions()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // App Lock state
    private val _isAppLocked = MutableStateFlow(prefs.isAppLockEnabled)
    val isAppLocked: StateFlow<Boolean> = _isAppLocked.asStateFlow()

    // Onboarding state
    private val _isOnboardingCompleted = MutableStateFlow(prefs.isOnboardingCompleted)
    val isOnboardingCompleted: StateFlow<Boolean> = _isOnboardingCompleted.asStateFlow()

    // Candidates pending review from OCR or Statement import
    private val _pendingCandidates = MutableStateFlow<List<ParsedMandateCandidate>>(emptyList())
    val pendingCandidates: StateFlow<List<ParsedMandateCandidate>> = _pendingCandidates.asStateFlow()

    // Selected subscription for detail view
    private val _selectedSubscriptionId = MutableStateFlow<Long?>(null)
    val selectedSubscriptionId: StateFlow<Long?> = _selectedSubscriptionId.asStateFlow()

    // Search and filter state
    val searchQuery = MutableStateFlow("")
    val selectedRailFilter = MutableStateFlow<String?>("ALL") // ALL, UPI, NACH, SI, CARD

    // App preferences
    val isAppLockSettingEnabled = MutableStateFlow(prefs.isAppLockEnabled)
    val isNotificationsEnabled = MutableStateFlow(prefs.isNotificationsEnabled)

    fun unlockApp() {
        _isAppLocked.value = false
    }

    fun lockApp() {
        if (prefs.isAppLockEnabled) {
            _isAppLocked.value = true
        }
    }

    private var backgroundedAtMs = 0L

    private var externalPickerOpen = false

    /** Call right before launching a system picker so coming back from it does not trigger the re-lock. */
    fun beginExternalPicker() {
        externalPickerOpen = true
    }

    fun onAppBackgrounded() {
        backgroundedAtMs = android.os.SystemClock.elapsedRealtime()
    }

    // Re-lock after a short grace period so system pickers (photos, files) don't force a re-auth
    fun onAppForegrounded() {
        if (externalPickerOpen) {
            externalPickerOpen = false
            backgroundedAtMs = 0L
            return
        }
        if (backgroundedAtMs != 0L &&
            android.os.SystemClock.elapsedRealtime() - backgroundedAtMs > RELOCK_GRACE_MS
        ) {
            lockApp()
        }
        backgroundedAtMs = 0L
    }

    fun completeOnboarding(enableAppLock: Boolean) {
        prefs.isOnboardingCompleted = true
        prefs.isAppLockEnabled = enableAppLock
        _isOnboardingCompleted.value = true
        _isAppLocked.value = false
        isAppLockSettingEnabled.value = enableAppLock
    }

    fun setAppLockEnabled(enabled: Boolean) {
        prefs.isAppLockEnabled = enabled
        isAppLockSettingEnabled.value = enabled
        if (!enabled) {
            _isAppLocked.value = false
        }
    }

    fun setNotificationsEnabled(enabled: Boolean) {
        prefs.isNotificationsEnabled = enabled
        isNotificationsEnabled.value = enabled
        com.leakproof.app.reminder.RenewalReminderWorker.schedule(getApplication(), enabled)
    }

    fun selectSubscription(id: Long) {
        _selectedSubscriptionId.value = id
    }

    fun setPendingCandidates(candidates: List<ParsedMandateCandidate>) {
        _pendingCandidates.value = candidates
    }

    fun removePendingCandidate(id: String) {
        _pendingCandidates.value = _pendingCandidates.value.filter { it.id != id }
    }

    fun updatePendingCandidate(updated: ParsedMandateCandidate) {
        _pendingCandidates.value = _pendingCandidates.value.map {
            if (it.id == updated.id) updated else it
        }
    }

    // Review-screen insight: what this import adds, changes, or no longer shows
    val importDiff: StateFlow<ImportDiffResult?> =
        combine(_pendingCandidates, allSubscriptions) { pending, existing ->
            if (pending.isEmpty()) null else ImportDiff.compare(existing, pending)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    // Mandates the user ticked as "cancelled" because the import no longer shows them
    private val _missingToCancel = MutableStateFlow<Set<Long>>(emptySet())
    val missingToCancel: StateFlow<Set<Long>> = _missingToCancel.asStateFlow()

    fun toggleMissingToCancel(id: Long) {
        _missingToCancel.value = _missingToCancel.value.let { if (id in it) it - id else it + id }
    }

    /** [applyCancellations] is true only for "Confirm All", which finishes the whole import. */
    fun confirmCandidates(
        candidatesToSave: List<ParsedMandateCandidate>,
        applyCancellations: Boolean = false,
        onComplete: () -> Unit
    ) {
        viewModelScope.launch {
            val cancelIds = if (applyCancellations) {
                val missingIds = importDiff.value?.missing?.map { it.id }?.toSet().orEmpty()
                _missingToCancel.value.filter { it in missingIds }
            } else emptyList()
            repository.saveConfirmedCandidates(candidatesToSave, cancelIds)
            prefs.lastImportAtMs = System.currentTimeMillis()
            val savedIds = candidatesToSave.map { it.id }.toSet()
            _pendingCandidates.value = _pendingCandidates.value.filter { it.id !in savedIds }
            if (applyCancellations || _pendingCandidates.value.isEmpty()) _missingToCancel.value = emptySet()
            onComplete()
        }
    }

    // Backup, restore and export (files are picked by the user through the system file picker)
    data class BackupResult(val message: String, val isError: Boolean)

    private val _backupResult = MutableStateFlow<BackupResult?>(null)
    val backupResult: StateFlow<BackupResult?> = _backupResult.asStateFlow()

    fun clearBackupResult() {
        _backupResult.value = null
    }

    private val contentResolver get() = getApplication<Application>().contentResolver

    fun createBackup(uri: android.net.Uri, passphrase: CharArray) {
        viewModelScope.launch {
            _backupResult.value = try {
                val subs = repository.getAllOnce()
                val blob = BackupCrypto.encrypt(BackupCodec.encode(subs, prefs.monthlyBudget), passphrase)
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    contentResolver.openOutputStream(uri, "wt")?.use { it.write(blob) }
                        ?: throw java.io.IOException("no stream")
                }
                BackupResult("Encrypted backup saved (${subs.size} subscriptions). Keep your passphrase safe: it can't be recovered.", false)
            } catch (_: Exception) {
                BackupResult("Couldn't save the backup file.", true)
            } finally {
                passphrase.fill('\u0000')
            }
        }
    }

    fun restoreBackup(uri: android.net.Uri, passphrase: CharArray) {
        viewModelScope.launch {
            _backupResult.value = try {
                val blob = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    contentResolver.openInputStream(uri)?.use { readAtMost(it, MAX_BACKUP_BYTES) }
                        ?: throw java.io.IOException("no stream")
                }
                if (blob.size > MAX_BACKUP_BYTES) throw InvalidBackupException("This file is too large to be a Leakproof backup")
                val contents = BackupCodec.decode(BackupCrypto.decrypt(blob, passphrase))
                val plan = repository.restoreMerging(contents.subscriptions)
                if (prefs.monthlyBudget == 0.0 && contents.monthlyBudget > 0.0) setMonthlyBudget(contents.monthlyBudget)
                BackupResult(
                    "Restored ${plan.toInsert.size} subscriptions" +
                        if (plan.skipped > 0) " (${plan.skipped} already tracked, skipped)." else ".",
                    false
                )
            } catch (_: WrongPassphraseException) {
                BackupResult("Wrong passphrase, or the file was changed.", true)
            } catch (e: InvalidBackupException) {
                BackupResult(e.message ?: "This is not a valid backup.", true)
            } catch (_: Exception) {
                BackupResult("Couldn't read that file.", true)
            } finally {
                passphrase.fill('\u0000')
            }
        }
    }

    fun exportCsv(uri: android.net.Uri) {
        viewModelScope.launch {
            _backupResult.value = try {
                val csv = CsvExport.toCsv(repository.getAllOnce())
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    contentResolver.openOutputStream(uri, "wt")?.use { it.write(csv.toByteArray(Charsets.UTF_8)) }
                        ?: throw java.io.IOException("no stream")
                }
                BackupResult("Exported to CSV. The file is not encrypted: store or share it carefully.", false)
            } catch (_: Exception) {
                BackupResult("Couldn't save the CSV file.", true)
            }
        }
    }

    // Insights for the dashboard
    val monthlyBudget = MutableStateFlow(prefs.monthlyBudget)

    fun setMonthlyBudget(amount: Double) {
        val value = amount.coerceAtLeast(0.0)
        prefs.monthlyBudget = value
        monthlyBudget.value = value
    }

    fun addOrUpdateSubscription(
        id: Long = 0,
        merchant: String,
        amount: Double,
        frequency: String,
        nextDate: String,
        nextDateEpochMs: Long? = null,
        trialEndEpochMs: Long? = null,
        rail: String,
        source: String = "MANUAL",
        last4: String?,
        notes: String?,
        onComplete: () -> Unit
    ) {
        viewModelScope.launch {
            // Editing keeps what the form does not show (status, mandate limit, last-seen time)
            val old = if (id != 0L) repository.getSubscriptionById(id) else null
            val entity = SubscriptionEntity(
                id = id,
                merchant = merchant,
                amount = amount,
                frequency = frequency,
                nextDate = nextDate,
                nextDateEpochMs = nextDateEpochMs ?: old?.nextDateEpochMs
                    ?: (System.currentTimeMillis() + (14L * 24 * 3600 * 1000)),
                rail = rail,
                source = source,
                last4 = last4?.filter { it.isDigit() }?.takeLast(4),
                status = old?.status ?: "ACTIVE",
                userConfirmed = true,
                notes = notes,
                maxAmount = old?.maxAmount,
                trialEndEpochMs = trialEndEpochMs,
                lastSeenAtMs = old?.lastSeenAtMs ?: 0L
            )
            if (id == 0L) {
                repository.insertSubscription(entity)
            } else {
                repository.updateSubscription(entity)
            }
            onComplete()
        }
    }

    fun updateSubscriptionStatus(id: Long, newStatus: String) {
        viewModelScope.launch {
            repository.updateStatus(id, newStatus)
        }
    }

    fun deleteSubscription(id: Long, onComplete: () -> Unit) {
        viewModelScope.launch {
            repository.deleteById(id)
            onComplete()
        }
    }

    fun getMerchantGuide(merchantName: String): MerchantGuide? {
        return merchantRepo.findGuideForMerchant(merchantName)
    }

    private val _isProcessingOcr = MutableStateFlow(false)
    val isProcessingOcr: StateFlow<Boolean> = _isProcessingOcr.asStateFlow()

    private val _importError = MutableStateFlow<String?>(null)
    val importError: StateFlow<String?> = _importError.asStateFlow()

    fun clearImportError() {
        _importError.value = null
    }

    // Real ML Kit Screenshot processing. onComplete(true) only when real candidates were found.
    fun processScreenshotUris(uris: List<android.net.Uri>, onComplete: (Boolean) -> Unit) {
        viewModelScope.launch {
            _isProcessingOcr.value = true
            _importError.value = null
            var success = false
            try {
                val candidates = app.mlKitOcrScanner.processScreenshots(uris)
                if (candidates.isNotEmpty()) {
                    _pendingCandidates.value = candidates
                    success = true
                } else {
                    _importError.value = "No autopay mandates were found in the selected screenshots. Try a clearer screenshot of your UPI app's Autopay list."
                }
            } catch (_: Exception) {
                _importError.value = "Could not read the selected screenshots."
            } finally {
                _isProcessingOcr.value = false
                onComplete(success)
            }
        }
    }

    // Real Statement processing (PDF/CSV). onComplete(true) only when real candidates were found.
    fun processStatementUri(uri: android.net.Uri, password: String? = null, onComplete: (Boolean) -> Unit) {
        viewModelScope.launch {
            _isProcessingOcr.value = true
            _importError.value = null
            var success = false
            try {
                val candidates = app.statementReader.processStatementFile(uri, password)
                if (candidates.isNotEmpty()) {
                    _pendingCandidates.value = candidates
                    success = true
                } else {
                    _importError.value = "No recurring payments were detected in this statement."
                }
            } catch (_: Exception) {
                _importError.value = "Could not read this statement. Check the file and password."
            } finally {
                _isProcessingOcr.value = false
                onComplete(success)
            }
        }
    }

    // Parser actions
    fun parseScreenshotOcr(ocrText: String, sourceAppHint: String? = null) {
        val candidates = app.screenshotParser.parseOcrText(ocrText, sourceAppHint)
        _pendingCandidates.value = candidates
    }

    fun parseStatementText(lines: List<String>) {
        val rawTx = app.statementParser.parseStatementLines(lines)
        val candidates = app.recurringDetector.detectRecurring(rawTx)
        _pendingCandidates.value = candidates
    }

    fun runSampleScreenshotImport() {
        val candidates = app.screenshotParser.getSampleCandidates()
        _pendingCandidates.value = candidates
    }

    fun runSampleStatementImport() {
        val sampleLines = app.statementParser.getSampleStatementLines()
        val tx = app.statementParser.parseStatementLines(sampleLines)
        val candidates = app.recurringDetector.detectRecurring(tx)
        _pendingCandidates.value = candidates
    }

    // Wipe all data & cryptographic keys
    fun wipeAllData(onComplete: () -> Unit) {
        viewModelScope.launch {
            AppDatabase.wipeDatabase(getApplication())
            prefs.clearAll()
            com.leakproof.app.reminder.RenewalReminderWorker.schedule(getApplication(), false)
            isNotificationsEnabled.value = false
            _isOnboardingCompleted.value = false
            _isAppLocked.value = false
            _pendingCandidates.value = emptyList()
            onComplete()
        }
    }

    private companion object {
        const val RELOCK_GRACE_MS = 30_000L
        const val MAX_BACKUP_BYTES = 5 * 1024 * 1024
    }
}
