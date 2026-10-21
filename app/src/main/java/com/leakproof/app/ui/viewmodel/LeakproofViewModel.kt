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

    init {
        viewModelScope.launch {
            allSubscriptions.collect { com.leakproof.app.widget.LeakproofWidgetProvider.refresh(getApplication()) }
        }
    }

    // App Lock state
    private val _isAppLocked = MutableStateFlow(prefs.isAppLockEnabled)
    val isAppLocked: StateFlow<Boolean> = _isAppLocked.asStateFlow()

    // DPDP consent: valid only for the current policy version, so a changed policy asks again
    private val policyVersion = application.resources.getInteger(com.leakproof.app.R.integer.policy_version)
    private val _isConsentGiven = MutableStateFlow(prefs.consentVersion >= policyVersion)
    val isConsentGiven: StateFlow<Boolean> = _isConsentGiven.asStateFlow()
    private val _consentAtMs = MutableStateFlow(prefs.consentAtMs)
    val consentAtMs: StateFlow<Long> = _consentAtMs.asStateFlow()
    val consentVersion: Int get() = prefs.consentVersion

    fun giveConsent() {
        val now = System.currentTimeMillis()
        prefs.consentVersion = policyVersion
        prefs.consentAtMs = now
        _consentAtMs.value = now
        _isConsentGiven.value = true
    }

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
    private val _statementCoverageDays = MutableStateFlow<Int?>(null)

    val importDiff: StateFlow<ImportDiffResult?> =
        combine(_pendingCandidates, allSubscriptions, _statementCoverageDays) { pending, existing, coverage ->
            if (pending.isEmpty()) null else ImportDiff.compare(existing, pending, coverage)
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
                val diff = importDiff.value
                val missingIds = (diff?.missing.orEmpty() + diff?.unseen.orEmpty()).map { it.id }.toSet()
                _missingToCancel.value.filter { it in missingIds }
            } else emptyList()
            repository.saveConfirmedCandidates(candidatesToSave, cancelIds)
            prefs.lastImportAtMs = System.currentTimeMillis()
            val savedIds = candidatesToSave.map { it.id }.toSet()
            _pendingCandidates.value = _pendingCandidates.value.filter { it.id !in savedIds }
            if (applyCancellations || _pendingCandidates.value.isEmpty()) {
                _missingToCancel.value = emptySet()
                _statementCoverageDays.value = null
            }
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

    // Appearance
    // The "check the names" guide: shown the first time in each session unless switched off for good
    private var importGuideAcknowledged = false
    fun shouldShowImportGuide(): Boolean = !prefs.hideImportGuide && !importGuideAcknowledged
    fun acknowledgeImportGuide(dontShowAgain: Boolean) {
        importGuideAcknowledged = true
        if (dontShowAgain) prefs.hideImportGuide = true
    }

    // Home-screen widget: amounts stay hidden until the user turns them on
    val widgetShowDetails = MutableStateFlow(prefs.widgetShowDetails)
    fun setWidgetShowDetails(show: Boolean) {
        prefs.widgetShowDetails = show
        widgetShowDetails.value = show
        com.leakproof.app.widget.LeakproofWidgetProvider.refresh(getApplication())
    }

    // Files shared into the app from another app ("Share > Leakproof")
    enum class SharedKind { IMAGES, PDF, CSV }
    data class SharedImport(val uris: List<android.net.Uri>, val kind: SharedKind)

    private val _pendingShare = MutableStateFlow<SharedImport?>(null)
    val pendingShare: StateFlow<SharedImport?> = _pendingShare.asStateFlow()
    fun hasPendingShare(): Boolean = _pendingShare.value != null
    fun takePendingShare(): SharedImport? = _pendingShare.value.also { _pendingShare.value = null }

    /** Only content:// streams are accepted, at most five images; the first PDF or CSV wins. */
    fun receiveSharedFiles(uris: List<android.net.Uri>, mimeType: String?) {
        val resolver = getApplication<Application>().contentResolver
        val safe = uris.filter { it.scheme == "content" }.take(5)
        if (safe.isEmpty()) return
        fun typeOf(u: android.net.Uri) = (resolver.getType(u) ?: mimeType).orEmpty().lowercase()
        val first = typeOf(safe.first())
        val share = when {
            first.startsWith("image/") -> SharedImport(safe.filter { typeOf(it).startsWith("image/") }, SharedKind.IMAGES)
            first == "application/pdf" -> SharedImport(listOf(safe.first()), SharedKind.PDF)
            first.contains("csv") || safe.first().lastPathSegment.orEmpty().endsWith(".csv", ignoreCase = true) ->
                SharedImport(listOf(safe.first()), SharedKind.CSV)
            else -> null
        }
        _pendingShare.value = share
    }

    val themeMode = MutableStateFlow(prefs.themeMode)

    fun setThemeMode(mode: Int) {
        prefs.themeMode = mode
        themeMode.value = mode
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
        kind: String = "SUBSCRIPTION",
        installmentsTotal: Int? = null,
        installmentsPaid: Int? = null,
        interestRatePct: Double? = null,
        outstanding: Double? = null,
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
                lastSeenAtMs = old?.lastSeenAtMs ?: 0L,
                kind = kind,
                installmentsTotal = installmentsTotal,
                installmentsPaid = installmentsPaid,
                endEpochMs = old?.endEpochMs,
                interestRatePct = interestRatePct,
                outstanding = outstanding
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

    /** A note for the Review screen, e.g. when only some of the chosen screenshots could be read. */
    private val _reviewNotice = MutableStateFlow<String?>(null)
    val reviewNotice: StateFlow<String?> = _reviewNotice.asStateFlow()

    // Real ML Kit Screenshot processing. onComplete(true) only when real candidates were found.
    fun processScreenshotUris(uris: List<android.net.Uri>, onComplete: (Boolean) -> Unit) {
        viewModelScope.launch {
            _isProcessingOcr.value = true
            _importError.value = null
            _reviewNotice.value = null
            val startedAt = android.os.SystemClock.elapsedRealtime()
            var success = false
            try {
                val report = app.mlKitOcrScanner.processScreenshots(uris)
                if (report.candidates.isNotEmpty()) {
                    _statementCoverageDays.value = null
                    _pendingCandidates.value = report.candidates
                    success = true
                    // Found something, but not from every image: say so rather than hide it
                    val skipped = report.unreadable + report.noText
                    if (skipped > 0) {
                        _reviewNotice.value = "$skipped of ${report.images} screenshot${if (report.images == 1) "" else "s"} couldn't be read. " +
                            "Check the list on the next screen and import the missing one again if needed."
                    }
                } else {
                    _importError.value = when {
                        report.unreadable == report.images ->
                            "Couldn't open ${if (report.images == 1) "that image" else "those images"}. Pick the screenshot again, or add the payment manually."
                        report.unreadable + report.noText == report.images ->
                            "No text could be read in ${if (report.images == 1) "that image" else "those images"}. Use a sharp, full-screen screenshot, or add the payment manually."
                        else ->
                            "The text was read, but no Autopay list was recognised. Open Autopay or Mandates in your UPI app, " +
                                "make sure the rows with \"Up to ₹…\" amounts are on screen, and take the screenshot again. " +
                                "You can also import a bank statement or add the payment manually."
                    }
                }
            } catch (_: Exception) {
                _importError.value = "Could not read the selected screenshots."
            } finally {
                val elapsed = android.os.SystemClock.elapsedRealtime() - startedAt
                if (elapsed < MIN_SCAN_MS) kotlinx.coroutines.delay(MIN_SCAN_MS - elapsed)
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
            val startedAt = android.os.SystemClock.elapsedRealtime()
            var success = false
            try {
                val imported = app.statementReader.processStatementFile(uri, password)
                if (imported.candidates.isNotEmpty()) {
                    _statementCoverageDays.value = imported.coverageDays
                    _pendingCandidates.value = imported.candidates
                    success = true
                } else {
                    _importError.value = when {
                        imported.textLines == 0 ->
                            "No text could be read in this file. If it is a scan or a photo of a statement, import it as a screenshot instead."
                        imported.transactions == 0 ->
                            "The file was read, but no table of transactions was recognised. Try the CSV version of the statement, or send it to us as a sample."
                        else ->
                            "Read ${imported.transactions} transactions, but none repeat on a regular cycle. Try a longer period, at least two or three months."
                    }
                }
            } catch (e: com.leakproof.app.data.statement.StatementReader.StatementReadException) {
                _importError.value = when (e.reason) {
                    com.leakproof.app.data.statement.StatementReader.StatementReadException.Reason.PASSWORD_NEEDED ->
                        "This PDF is password-protected, or the password was wrong. Banks often use your date of birth or PAN."
                    com.leakproof.app.data.statement.StatementReader.StatementReadException.Reason.NOT_A_PDF ->
                        "That file could not be opened as a PDF. Download the statement again and retry."
                    com.leakproof.app.data.statement.StatementReader.StatementReadException.Reason.UNREADABLE ->
                        "Couldn't open that file. Pick it again, or share it from the Files app."
                }
            } catch (_: Exception) {
                _importError.value = "Could not read this statement. Check the file and try again."
            } finally {
                val elapsed = android.os.SystemClock.elapsedRealtime() - startedAt
                if (elapsed < MIN_SCAN_MS) kotlinx.coroutines.delay(MIN_SCAN_MS - elapsed)
                _isProcessingOcr.value = false
                onComplete(success)
            }
        }
    }

    // Wipe all data & cryptographic keys
    fun wipeAllData(onComplete: () -> Unit) {
        viewModelScope.launch {
            AppDatabase.wipeDatabase(getApplication())
            prefs.clearAll()
            com.leakproof.app.reminder.RenewalReminderWorker.schedule(getApplication(), false)
            isNotificationsEnabled.value = false
            _isOnboardingCompleted.value = false
            _isConsentGiven.value = false
            _consentAtMs.value = 0L
            _isAppLocked.value = false
            _pendingCandidates.value = emptyList()
            _missingToCancel.value = emptySet()
            _statementCoverageDays.value = null
            _backupResult.value = null
            monthlyBudget.value = 0.0
            themeMode.value = 0
            importGuideAcknowledged = false
            widgetShowDetails.value = false
            _pendingShare.value = null
            isAppLockSettingEnabled.value = true
            onComplete()
        }
    }

    private companion object {
        const val RELOCK_GRACE_MS = 30_000L
        const val MIN_SCAN_MS = 1_400L
        const val MAX_BACKUP_BYTES = 5 * 1024 * 1024
    }
}
