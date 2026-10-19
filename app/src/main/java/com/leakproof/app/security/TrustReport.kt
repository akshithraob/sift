package com.leakproof.app.security

import java.security.MessageDigest

/** One self-check shown in the Trust Center. [passed] false is shown as a note, not hidden. */
data class TrustCheck(val title: String, val detail: String, val passed: Boolean)

/**
 * Builds the Trust Center checklist from facts read off the installed app (its own permission
 * list and flags), so the screen shows what is really true of this build rather than a claim.
 * Pure Kotlin so it can be unit tested.
 */
object TrustReport {
    const val INTERNET = "android.permission.INTERNET"

    /** Permissions that would let an app read messages, contacts, location, media or sensors. */
    val SENSITIVE = setOf(
        "android.permission.READ_SMS", "android.permission.RECEIVE_SMS", "android.permission.SEND_SMS",
        "android.permission.READ_CONTACTS", "android.permission.READ_CALL_LOG",
        "android.permission.ACCESS_FINE_LOCATION", "android.permission.ACCESS_COARSE_LOCATION",
        "android.permission.READ_EXTERNAL_STORAGE", "android.permission.MANAGE_EXTERNAL_STORAGE",
        "android.permission.READ_MEDIA_IMAGES", "android.permission.READ_MEDIA_VIDEO",
        "android.permission.CAMERA", "android.permission.RECORD_AUDIO"
    )

    fun build(
        requestedPermissions: List<String>,
        allowBackup: Boolean,
        debuggable: Boolean,
        screenCaptureAllowed: Boolean
    ): List<TrustCheck> {
        val sensitive = requestedPermissions.filter { it in SENSITIVE }
        return listOf(
            TrustCheck(
                "No internet permission",
                if (INTERNET in requestedPermissions) "This build asks for the INTERNET permission."
                else "Android will not let this app open a network connection, so your data cannot be sent anywhere.",
                INTERNET !in requestedPermissions
            ),
            TrustCheck(
                "No access to messages, contacts, location or media",
                if (sensitive.isEmpty()) "You choose each file yourself; the app never browses your storage."
                else "Requested: " + sensitive.joinToString { friendly(it) },
                sensitive.isEmpty()
            ),
            TrustCheck(
                "Excluded from cloud backup",
                if (allowBackup) "Backups are allowed for this build." else "Android backup and device transfer are switched off for this app.",
                !allowBackup
            ),
            TrustCheck(
                "Not a debuggable build",
                if (debuggable) "This is a debuggable build." else "Debugging hooks are off.",
                !debuggable
            ),
            TrustCheck(
                "Screenshots and screen recording blocked",
                if (screenCaptureAllowed) "Allowed in this demo build so the app can be shown; the release build blocks it."
                else "Android blocks screenshots, screen recording and the recent-apps preview of this app.",
                !screenCaptureAllowed
            )
        )
    }

    /** SHA-256 of a signing certificate, as colon-separated upper-case hex. */
    fun fingerprint(certificate: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(certificate)
            .joinToString(":") { "%02X".format(it.toInt() and 0xFF) }

    fun friendly(permission: String): String =
        permission.substringAfterLast('.').lowercase().replace('_', ' ')

    /** What is stored, and what never is. Shown as-is in the Trust Center. */
    val STORED = listOf(
        "Merchant name", "Amount", "How often it renews", "Next renewal date",
        "Payment type (UPI, e-NACH, card…)", "Last 4 digits of the account", "Your notes and status"
    )
    val NEVER_STORED = listOf(
        "Full account or card numbers", "UPI IDs", "Screenshot images or statement text",
        "Your statement password", "Transactions that are not subscriptions"
    )
}
