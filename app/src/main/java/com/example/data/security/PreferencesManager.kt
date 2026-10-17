package com.example.data.security

import android.content.Context
import android.content.SharedPreferences

class PreferencesManager(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("subtrack_user_settings", Context.MODE_PRIVATE)

    companion object {
        private const val KEY_ONBOARDING_DONE = "key_onboarding_done"
        private const val KEY_APP_LOCK_ENABLED = "key_app_lock_enabled"
        private const val KEY_NOTIFICATIONS_ENABLED = "key_notifications_enabled"
        private const val KEY_FIRST_RUN_SEEDED = "key_first_run_seeded"
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

    var isFirstRunSeeded: Boolean
        get() = prefs.getBoolean(KEY_FIRST_RUN_SEEDED, false)
        set(value) = prefs.edit().putBoolean(KEY_FIRST_RUN_SEEDED, value).apply()

    fun clearAll() {
        prefs.edit().clear().apply()
    }
}
