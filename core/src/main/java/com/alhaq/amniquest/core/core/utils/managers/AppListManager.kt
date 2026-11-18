package com.alhaq.amniquest.core.core.utils.managers

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.view.inputmethod.InputMethodManager
import androidx.core.content.edit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import com.alhaq.amniquest.data.json
import com.alhaq.amniquest.data.AppInfo


// Cache the app list in SharedPreferences
fun cacheApps(context: Context, apps: List<AppInfo>) {
    val sharedPreferences = context.getSharedPreferences("app_cache", Context.MODE_PRIVATE)
    sharedPreferences.edit {
        val jsonStr = json.encodeToString(apps)
        putString("apps", jsonStr)
    }
}

// Retrieve the cached app list from SharedPreferences
fun getCachedApps(context: Context): List<AppInfo> {
    val sharedPreferences = context.getSharedPreferences("app_cache", Context.MODE_PRIVATE)
    val jsonStr = sharedPreferences.getString("apps", null)
    return if (jsonStr != null) {
        try {
            json.decodeFromString<List<AppInfo>>(jsonStr).sortedBy { it.name.lowercase() }
        } catch (_: Exception) {
            emptyList()
        }
    } else {
        emptyList()
    }
}

suspend fun reloadApps(
    packageManager: PackageManager,
    context: Context
): Result<List<AppInfo>> {
    return withContext(Dispatchers.IO) {
        try {
            // Fetch the latest app list from the PackageManager
            val mainIntent = Intent(Intent.ACTION_MAIN, null).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
            }

            val apps = packageManager.queryIntentActivities(mainIntent, PackageManager.MATCH_ALL)
                .mapNotNull { resolveInfo ->
                    val appInfo = resolveInfo.activityInfo?.applicationInfo ?: return@mapNotNull null
                    val pkgName = appInfo.packageName
                    val name = appInfo.loadLabel(packageManager).toString()
                    val pkgInfo = try {
                        packageManager.getPackageInfo(pkgName, 0)
                    } catch (_: Exception) { null }

                    AppInfo(
                        name = name,
                        packageName = pkgName,
                        installTime = pkgInfo?.firstInstallTime ?: 0L,
                        updateTime = pkgInfo?.lastUpdateTime ?: 0L,
                        category = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                            appInfo.category
                        } else -1
                    )
                }
                .distinctBy { it.packageName }
                .sortedBy { it.name.lowercase() }

            // Cache the app list in SharedPreferences
            cacheApps(context, apps)

            Result.success(apps)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
suspend fun getBackgroundSystemApps(context: Context): List<ApplicationInfo> {
    return withContext(Dispatchers.IO) {
        val pm = context.packageManager
        val allApps = pm.getInstalledApplications(PackageManager.GET_META_DATA)

        val launcherIntent = Intent(Intent.ACTION_MAIN, null).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }
        val launchableApps = pm.queryIntentActivities(launcherIntent, 0)
            .map { it.activityInfo.applicationInfo.packageName }
            .toSet()

        allApps.filter { app ->
            (app.flags and ApplicationInfo.FLAG_SYSTEM) != 0 && // system app
                    app.packageName !in launchableApps                  // not launchable
        }
    }
}

fun getKeyboards(context: Context): List<String> {
    val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
    val enabledMethods = imm.enabledInputMethodList
    return enabledMethods.map { it.packageName }
}

fun formatAppList(packageNames: List<String>, context: Context): String {
    val pm = context.packageManager
    val appNames = packageNames.mapNotNull { packageName ->
        try {
            val appInfo = pm.getApplicationInfo(packageName, 0)
            pm.getApplicationLabel(appInfo).toString()
        } catch (e: PackageManager.NameNotFoundException) {
            null // skip if not found
        }
    }
    return appNames.joinToString(", ")
}