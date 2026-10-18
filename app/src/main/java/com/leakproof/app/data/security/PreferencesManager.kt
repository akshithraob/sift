package com.leakproof.app.data.security

import android.content.Context
import android.content.SharedPreferences

class PreferencesManager(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("leakproof_user_settings", Context.MODE_PRIVATE)

    companion object {
        private const val KEY_ONBOARDING_DONE = "key_onboarding_done"
        private const val KEY_APP_LOCK_ENABLED = "key_app_lock_enabled"
        private const val KEY_NOTIFICATIONS_ENABLED = "key_notifications_enabled"
        private const val KEY_CONSENT_VERSION = "key_consent_version"
        private const val KEY_CONSENT_AT_MS = "key_consent_at_ms"
        private const val KEY_MONTHLY_BUDGET = "key_monthly_budget"
        private const val KEY_LAST_IMPORT_MS = "key_last_import_ms"
        private const val KEY_LAST_REIMPORT_NUDGE_MS = "key_last_reimport_nudge_ms"
    }

    var isOnboardingCompleted: Boolean
        get() = prefs.getBoolean(KEY_ONBOARDING_DONE, false)
        set(value) = prefs.edit().putBoolean(KEY_ONBOARDING_DONE, value).apply()

    var isAppLockEnabled: Boolean
        get() = prefs.getBoolean(KEY_APP_LOCK_ENABLED, true) // ON by default as requested
        set(value) = prefs.edit().putBoolean(KEY_APP_LOCK_ENABLED, value).apply()

    var isNotificationsEnabled: Boolean
        get() = prefs.getBoolean(KEY_NOTIFICATIONS_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_NOTIFICATIONS_ENABLED, value).apply()

    /** Version of the privacy policy the user consented to; 0 means no consent yet. */
    var consentVersion: Int
        get() = prefs.getInt(KEY_CONSENT_VERSION, 0)
        set(value) = prefs.edit().putInt(KEY_CONSENT_VERSION, value).apply()

    var consentAtMs: Long
        get() = prefs.getLong(KEY_CONSENT_AT_MS, 0L)
        set(value) = prefs.edit().putLong(KEY_CONSENT_AT_MS, value).apply()

    /** Monthly subscription budget in rupees; 0 means no budget set. */
    var monthlyBudget: Double
        get() = java.lang.Double.longBitsToDouble(prefs.getLong(KEY_MONTHLY_BUDGET, 0L))
        set(value) = prefs.edit().putLong(KEY_MONTHLY_BUDGET, java.lang.Double.doubleToRawLongBits(value)).apply()

    var lastImportAtMs: Long
        get() = prefs.getLong(KEY_LAST_IMPORT_MS, 0L)
        set(value) = prefs.edit().putLong(KEY_LAST_IMPORT_MS, value).apply()

    var lastReimportNudgeAtMs: Long
        get() = prefs.getLong(KEY_LAST_REIMPORT_NUDGE_MS, 0L)
        set(value) = prefs.edit().putLong(KEY_LAST_REIMPORT_NUDGE_MS, value).apply()

    fun clearAll() {
        prefs.edit().clear().apply()
    }
}
