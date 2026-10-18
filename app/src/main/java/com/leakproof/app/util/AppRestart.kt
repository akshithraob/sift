package com.leakproof.app.util

import android.content.Context
import android.content.Intent
import android.os.Process

/**
 * Starts the app again in a fresh process. After "Delete all my data" the old process still holds
 * the closed database, its cached lists and decrypted state, so a restart is the clean way to begin
 * again (and leaves no sensitive data in memory).
 */
fun restartApp(context: Context) {
    val launch = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return
    launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
    context.startActivity(launch)
    Process.killProcess(Process.myPid())
}
