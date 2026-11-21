package com.alhaq.amniquest.core.contract

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri

object GuardianContract {
    const val AUTHORITY = "com.alhaq.deenshield.guardian.provider"
    val BASE_URI: Uri by lazy { Uri.parse("content://$AUTHORITY") }
    val URI_FOCUS: Uri by lazy { Uri.parse("content://$AUTHORITY/focus") }
    val URI_REELS: Uri by lazy { Uri.parse("content://$AUTHORITY/reels") }
    val URI_STATS: Uri by lazy { Uri.parse("content://$AUTHORITY/stats") }

    // Package & Store IDs
    const val PACKAGE_AMNISHIELD = "com.alhaq.amnishield"
    const val PACKAGE_AMNISHIELD_DEBUG = "com.alhaq.amnishield.debug"
    const val PACKAGE_DEENSHIELD = "com.alhaq.deenshield"
    const val PLAY_STORE_URL = "https://play.google.com/store/apps/details?id=com.alhaq.deenshield"
    const val MARKET_URI = "market://details?id=com.alhaq.deenshield"

    // Call Methods
    const val METHOD_START_FOCUS = "start_focus"
    const val METHOD_STOP_FOCUS = "stop_focus"
    const val METHOD_GET_SNAPSHOT = "get_snapshot"

    // Call Argument Keys
    const val ARG_MINUTES = "minutes"

    // Snapshot Bundle Keys
    const val KEY_API_VERSION = "api_version"
    const val KEY_FOCUS_ACTIVE = "focus_active"
    const val KEY_FOCUS_REMAINING_MINUTES = "focus_remaining_minutes"
    const val KEY_FOCUS_TODAY_MINUTES = "focus_today_minutes"
    const val KEY_FOCUS_SESSIONS_COUNT = "focus_sessions_count"
    const val KEY_REELS_SCROLLED_TODAY = "reels_scrolled_today"
    const val KEY_REELS_WATCH_SECONDS_TODAY = "reels_watch_seconds_today"
    const val KEY_STREAK_DAYS = "streak_days"
    const val KEY_SHIELD_SCORE = "shield_score"
    const val KEY_TOTAL_THREATS_BLOCKED = "total_threats_blocked"
    const val KEY_TODAY_THREATS_BLOCKED = "today_threats_blocked"

    // Broadcast Actions & Extras
    const val ACTION_FOCUS_COMPLETED = "com.alhaq.amnishield.ACTION_FOCUS_COMPLETED"
    const val ACTION_FOCUS_ABORTED = "com.alhaq.amnishield.ACTION_FOCUS_ABORTED"
    const val ACTION_REELS_THRESHOLD = "com.alhaq.amnishield.ACTION_REELS_THRESHOLD"
    const val EXTRA_DURATION_MINUTES = "duration_minutes"
    const val PERMISSION_GUARDIAN_IPC = "com.alhaq.deenshield.permission.GUARDIAN_IPC"

    /**
     * Checks whether AmniShield (com.alhaq.amnishield, com.alhaq.amnishield.debug,
     * or com.alhaq.deenshield) is installed on the host device.
     */
    fun isInstalled(context: Context): Boolean {
        val pm = context.packageManager
        return listOf(PACKAGE_AMNISHIELD, PACKAGE_AMNISHIELD_DEBUG, PACKAGE_DEENSHIELD).any { pkg ->
            try {
                pm.getPackageInfo(pkg, 0)
                true
            } catch (e: PackageManager.NameNotFoundException) {
                false
            } catch (e: Exception) {
                false
            }
        }
    }

    /**
     * Probes whether GuardianContentProvider is active and responding to IPC calls.
     */
    fun isProviderResponsive(context: Context): Boolean {
        return try {
            val bundle = context.contentResolver.call(BASE_URI, METHOD_GET_SNAPSHOT, null, null)
            bundle != null
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Opens Google Play Store to install AmniShield, falling back to browser URL.
     */
    fun openPlayStore(context: Context) {
        val marketIntent = Intent(Intent.ACTION_VIEW, Uri.parse(MARKET_URI)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val webIntent = Intent(Intent.ACTION_VIEW, Uri.parse(PLAY_STORE_URL)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        try {
            context.startActivity(marketIntent)
        } catch (e: Exception) {
            try {
                context.startActivity(webIntent)
            } catch (ignored: Exception) {
            }
        }
    }

    /**
     * Launches AmniShield application if installed.
     * Returns true if launch succeeded, false otherwise.
     */
    fun launchAmniShield(context: Context): Boolean {
        val pm = context.packageManager
        val installedPkg = listOf(PACKAGE_AMNISHIELD, PACKAGE_AMNISHIELD_DEBUG, PACKAGE_DEENSHIELD).firstOrNull { pkg ->
            try {
                pm.getPackageInfo(pkg, 0)
                true
            } catch (e: Exception) {
                false
            }
        } ?: return false

        val launchIntent = pm.getLaunchIntentForPackage(installedPkg) ?: return false
        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(launchIntent)
            true
        } catch (e: Exception) {
            false
        }
    }
}
