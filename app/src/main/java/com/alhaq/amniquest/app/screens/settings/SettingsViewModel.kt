package com.alhaq.amniquest.app.screens.settings

import android.app.ActivityManager
import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.alhaq.amniquest.app.screens.account.shareCrashLog
import com.alhaq.amniquest.backed.repositories.AppWidgetConfigDao
import com.alhaq.amniquest.backed.repositories.QuestRepository
import com.alhaq.amniquest.backed.repositories.StatsRepository
import com.alhaq.amniquest.backed.repositories.UserRepository
import com.alhaq.amniquest.backed.triggerProfileSync
import com.alhaq.amniquest.backed.triggerQuestSync
import com.alhaq.amniquest.backed.triggerStatsSync
import com.alhaq.amniquest.core.utils.BackupManager
import com.alhaq.amniquest.core.utils.RestoreResult
import com.alhaq.amniquest.data.InventoryExecParams
import com.alhaq.amniquest.data.UserInfo
import com.alhaq.amniquest.data.game.InventoryItem
import com.alhaq.amniquest.core.utils.managers.executeItem
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    application: Application,
    val userRepository: UserRepository,
    private val questRepository: QuestRepository,
    private val statsRepository: StatsRepository,
    private val widgetConfigDao: AppWidgetConfigDao
) : AndroidViewModel(application) {

    val userInfo: UserInfo get() = userRepository.userInfo
    val userInfoState = userRepository.userInfoState
    val customizationInfo = userRepository.customizationInfoState
    val coins = userRepository.coinsState
    val inventory = userRepository.inventoryState

    // Section 1: Profile & Account Controls
    fun getPrivacyModeEnabled(): Boolean {
        return userRepository.userInfo.customization_info.habitPrivacyMode
    }

    fun savePrivacyModeEnabled(enabled: Boolean) {
        userRepository.setHabitPrivacyMode(enabled)
    }

    fun setHabitPrivacyMode(enabled: Boolean) {
        savePrivacyModeEnabled(enabled)
    }

    fun backupData(password: String? = null, onComplete: (String?) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val json = BackupManager.createBackup(
                    getApplication(),
                    userRepository,
                    questRepository,
                    statsRepository,
                    widgetConfigDao,
                    password
                )
                withContext(Dispatchers.Main) {
                    onComplete(json)
                }
            } catch (_: Exception) {
                withContext(Dispatchers.Main) {
                    onComplete(null)
                }
            }
        }
    }

    fun restoreData(backupJson: String, password: String? = null, onComplete: (RestoreResult) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            val result = BackupManager.restoreBackupDetailed(
                getApplication(),
                backupJson,
                userRepository,
                questRepository,
                statsRepository,
                widgetConfigDao,
                password
            )
            withContext(Dispatchers.Main) {
                onComplete(result)
            }
        }
    }

    fun syncCloudData(onComplete: (Boolean) -> Unit) {
        viewModelScope.launch {
            try {
                if (!userRepository.userInfo.isAnonymous) {
                    triggerProfileSync(getApplication(), true)
                    triggerQuestSync(getApplication(), true)
                    triggerStatsSync(getApplication(), true)
                    onComplete(true)
                } else {
                    onComplete(false)
                }
            } catch (_: Exception) {
                onComplete(false)
            }
        }
    }

    fun logOut(onLoggedOut: () -> Unit) {
        viewModelScope.launch {
            userRepository.signOut()
            val activityManager = getApplication<Application>().getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            activityManager.clearApplicationUserData()
            withContext(Dispatchers.Main) {
                onLoggedOut()
            }
        }
    }

    fun onShareCrashLog() {
        shareCrashLog(getApplication())
    }

    // Section 2: Launcher & App Drawer Controls
    fun setAppListLayout(layout: String) = userRepository.setAppListLayout(layout)
    fun setShowAppIcons(show: Boolean) = userRepository.setShowAppIcons(show)
    fun setAppIconSize(size: String) = userRepository.setAppIconSize(size)
    fun setAppSortOrder(order: String) = userRepository.setAppSortOrder(order)
    fun setAppGroupBy(groupBy: String) = userRepository.setAppGroupBy(groupBy)
    fun setAutoFocusSearch(autoFocus: Boolean) = userRepository.setAutoFocusSearch(autoFocus)
    fun setClockStyle(style: String) = userRepository.setClockStyle(style)
    fun setIs24Hr(is24Hr: Boolean) = userRepository.setIs24Hr(is24Hr)
    fun setClockSizeSp(sizeSp: Float) = userRepository.setClockSizeSp(sizeSp)

    // Section 3: Store & Inventory Controls
    fun isBoosterActive(item: InventoryItem): Boolean {
        return userRepository.isBoosterActive(item)
    }

    fun useInventoryItem(
        item: InventoryItem,
        navController: androidx.navigation.NavController
    ) {
        if (userRepository.getInventoryItemCount(item) <= 0) return
        userRepository.deductFromInventory(item)
        executeItem(
            item,
            InventoryExecParams(
                navController = navController,
                userRepository = userRepository,
                questRepository = questRepository,
                statsRepository = statsRepository
            )
        )
    }

    // Section 4: On-Device AI & Models
    fun getValidationEngine(): String {
        val sp = getApplication<Application>().getSharedPreferences("private_settings", Context.MODE_PRIVATE)
        return sp.getString("validation_engine", "cloud") ?: "cloud"
    }

    fun saveValidationEngine(engine: String) {
        val sp = getApplication<Application>().getSharedPreferences("private_settings", Context.MODE_PRIVATE)
        sp.edit().putString("validation_engine", engine).apply()
    }

    fun getGeminiApiKey(): String {
        val sp = getApplication<Application>().getSharedPreferences("private_settings", Context.MODE_PRIVATE)
        return sp.getString("gemini_api_key", "") ?: ""
    }

    fun saveGeminiApiKey(key: String) {
        val sp = getApplication<Application>().getSharedPreferences("private_settings", Context.MODE_PRIVATE)
        sp.edit().putString("gemini_api_key", key).apply()
    }

    fun getLocalModelDiskUsage(): String {
        val app = getApplication<Application>()
        val filesBytes = app.filesDir.listFiles()
            ?.filter { it.isFile && (it.name.endsWith(".onnx", ignoreCase = true) || it.name == "tokenizer.model") }
            ?.sumOf { it.length() } ?: 0L
        val legacyBaseDir = app.getExternalFilesDir(null) ?: app.filesDir
        val legacyDir = File(legacyBaseDir, "siglip2")
        val legacyBytes = if (legacyDir.exists()) {
            legacyDir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        } else {
            0L
        }
        val bytes = filesBytes + legacyBytes
        if (bytes <= 0L) return "Not downloaded (0 MB)"
        val mb = bytes / (1024 * 1024)
        return if (mb > 0) "$mb MB" else "Downloaded (< 1 MB)"
    }
}
