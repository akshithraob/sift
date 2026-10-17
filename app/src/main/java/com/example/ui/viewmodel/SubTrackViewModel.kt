package com.example.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.SubTrackApplication
import com.example.data.local.AppDatabase
import com.example.data.local.SubscriptionEntity
import com.example.data.model.MerchantGuide
import com.example.parser.Confidence
import com.example.parser.Frequency
import com.example.parser.ParsedMandateCandidate
import com.example.parser.PaymentRail
import com.example.parser.SourceType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SubTrackViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as SubTrackApplication
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

    fun onAppBackgrounded() {
        backgroundedAtMs = android.os.SystemClock.elapsedRealtime()
    }

    // Re-lock after a short grace period so system pickers (photos, files) don't force a re-auth
    fun onAppForegrounded() {
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

    fun confirmCandidates(candidatesToSave: List<ParsedMandateCandidate>, onComplete: () -> Unit) {
        viewModelScope.launch {
            repository.saveConfirmedCandidates(candidatesToSave)
            _pendingCandidates.value = emptyList()
            onComplete()
        }
    }

    fun addOrUpdateSubscription(
        id: Long = 0,
        merchant: String,
        amount: Double,
        frequency: String,
        nextDate: String,
        rail: String,
        source: String = "MANUAL",
        last4: String?,
        notes: String?,
        onComplete: () -> Unit
    ) {
        viewModelScope.launch {
            val entity = SubscriptionEntity(
                id = id,
                merchant = merchant,
                amount = amount,
                frequency = frequency,
                nextDate = nextDate,
                nextDateEpochMs = System.currentTimeMillis() + (14L * 24 * 3600 * 1000),
                rail = rail,
                source = source,
                last4 = last4?.filter { it.isDigit() }?.takeLast(4),
                status = "ACTIVE",
                userConfirmed = true,
                notes = notes
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
            _isOnboardingCompleted.value = false
            _isAppLocked.value = false
            _pendingCandidates.value = emptyList()
            onComplete()
        }
    }

    private companion object {
        const val RELOCK_GRACE_MS = 30_000L
    }
}
