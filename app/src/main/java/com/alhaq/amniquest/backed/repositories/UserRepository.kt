package com.alhaq.amniquest.backed.repositories

import android.content.Context
import android.util.Log
import androidx.core.content.edit
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import com.alhaq.amniquest.backed.triggerProfileSync
import com.alhaq.amniquest.backed.triggerQuestSync
import com.alhaq.amniquest.backed.triggerStatsSync
import com.alhaq.amniquest.core.Supabase
import io.github.jan.supabase.auth.auth
import com.alhaq.amniquest.core.core.utils.getCurrentDate
import com.alhaq.amniquest.core.core.utils.getFullTimeAfter
import com.alhaq.amniquest.core.core.utils.isTimeOver
import com.alhaq.amniquest.data.CustomizationInfo
import com.alhaq.amniquest.data.UserInfo
import com.alhaq.amniquest.data.game.InventoryItem
import com.alhaq.amniquest.data.game.StreakFreezerReturn
import com.alhaq.amniquest.data.json
import com.alhaq.amniquest.data.xpToLevelUp
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.ExperimentalTime

@EntryPoint
@InstallIn(SingletonComponent::class)
interface UserRepositoryEntryPoint {
    fun userRepository(): UserRepository
}

@Singleton
class UserRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val statsRepository: StatsRepository,
    private val questRepository: QuestRepository
) {
    var userInfo: UserInfo = loadUserInfo()
    var userInfoState = MutableStateFlow(snapshotUserInfo())
    var coinsState = MutableStateFlow(userInfo.coins)
    var currentStreakState = MutableStateFlow(userInfo.streak.currentStreak)

    var activeBoostsState = MutableStateFlow<Map<InventoryItem, String>>(HashMap(userInfo.active_boosts))
    var inventoryState = MutableStateFlow<Map<InventoryItem, Int>>(userInfo.inventory.toMap())
    var customizationInfoState = MutableStateFlow(snapshotCustomization())

    private fun snapshotCustomization(
        src: CustomizationInfo = userInfo.customization_info
    ): CustomizationInfo = src.deepCopy()

    private fun snapshotUserInfo(src: UserInfo = userInfo): UserInfo = src.copy(
        customization_info = src.customization_info.deepCopy(),
        inventory = HashMap(src.inventory),
        active_boosts = HashMap(src.active_boosts),
        streak = src.streak.copy(),
        unlockedAndroidPackages = src.unlockedAndroidPackages?.toMutableMap(),
        blockedAndroidPackages = src.blockedAndroidPackages?.toSet(),
        fcm_tokens = src.fcm_tokens.toList(),
        achievements = src.achievements.toList()
    )

    // the below variables act as a trigger for launching the reward dialog declared in the MainActivity from a
    // different SubScreen.
    fun getUserId(): String {
        val sessionUserId = Supabase.currentUserIdOrNull()
        if (!sessionUserId.isNullOrBlank()) {
            val wasAnonymous = userInfo.isAnonymous
            if (userInfo.id != sessionUserId || wasAnonymous) {
                userInfo.id = sessionUserId
                userInfo.isAnonymous = false
                val sp = context.getSharedPreferences("authtoken", Context.MODE_PRIVATE)
                sp.edit { putString("key", sessionUserId) }
                saveUserInfo(isSetLastUpdated = false)
                if (wasAnonymous) {
                    CoroutineScope(Dispatchers.IO).launch {
                        questRepository.rebindUserQuests(sessionUserId)
                        statsRepository.rebindUserStats(sessionUserId)
                    }
                }
            }
            return sessionUserId
        }

        if (userInfo.isAnonymous) {
            return ""
        }

        val sp = context.getSharedPreferences("authtoken", Context.MODE_PRIVATE)
        val cachedId = sp.getString("key", null)
        if (!cachedId.isNullOrBlank()) {
            if (userInfo.id.isBlank()) {
                userInfo.id = cachedId
            }
            return cachedId
        }
        return userInfo.id
    }

    suspend fun onAuthenticationSuccess(userId: String, email: String) {
        val sp = context.getSharedPreferences("authtoken", Context.MODE_PRIVATE)
        sp.edit { putString("key", userId) }
        userInfo.id = userId
        userInfo.isAnonymous = false
        if (userInfo.username.isBlank() && email.isNotBlank()) {
            userInfo.username = email.substringBefore("@")
        }
        questRepository.rebindUserQuests(userId)
        statsRepository.rebindUserStats(userId)
        saveUserInfo(isSetLastUpdated = false)
        triggerProfileSync(context, isFirstLoginSync = true)
        triggerQuestSync(context, isFirstSync = true)
        triggerStatsSync(context, isFirstSync = true)
    }

    @Synchronized
    fun signInAnonymously() {
        userInfo.isAnonymous = true
        userInfo.id = ""
        val sp = context.getSharedPreferences("authtoken", Context.MODE_PRIVATE)
        sp.edit { remove("key") }
        saveUserInfo(isSetLastUpdated = false)
    }

    fun addXp(xp: Int) {
        removeInactiveBooster()
        val multiplier = if (isBoosterActive(InventoryItem.XP_BOOSTER)) 2 else 1
        userInfo.xp += xp * multiplier
        while (userInfo.xp >= xpToLevelUp(userInfo.level)) {
            userInfo.xp -= xpToLevelUp(userInfo.level)
            userInfo.level++
        }
        saveUserInfo()
    }

    fun removeInactiveBooster() {
        userInfo.active_boosts.entries.removeIf { isTimeOver(it.value) }
        activeBoostsState.value = HashMap(userInfo.active_boosts)
        saveUserInfo()
    }

    fun activateBoost(item: InventoryItem, hoursToAdd: Long, minsToAdd: Long) {
        userInfo.active_boosts[item] = getFullTimeAfter(hoursToAdd, minsToAdd)
        saveUserInfo()
        activeBoostsState.value = HashMap(userInfo.active_boosts)
    }

    fun isBoosterActive(reward: InventoryItem): Boolean {
        if (userInfo.active_boosts.contains(reward)) {
            val isActive = !isTimeOver(userInfo.active_boosts.getOrDefault(reward, "0069-69-69-69-69"))
            if (!isActive) removeInactiveBooster()
            return isActive
        }
        return false
    }

    @Synchronized
    fun addItemsToInventory(items: HashMap<InventoryItem, Int>) {
        items.forEach {
            userInfo.inventory[it.key] = it.value + getInventoryItemCount(it.key)
        }
        saveUserInfo()
    }

    @Synchronized
    fun setUserInfoFromRemote(remoteInfo: UserInfo) {
        userInfo = remoteInfo
        saveUserInfo(isSetLastUpdated = false)
    }

    @Synchronized
    fun saveUserInfo(isSetLastUpdated: Boolean = true) {
        if (isSetLastUpdated && !userInfo.isAnonymous) {
            userInfo.last_updated = System.currentTimeMillis()
            userInfo.needsSync = true
            triggerProfileSync(context)
        }
        context.getSharedPreferences("user_info", Context.MODE_PRIVATE)
            .edit { putString("user_info", json.encodeToString(UserInfo.serializer(), userInfo)) }
        val clonedCustomization = snapshotCustomization()
        userInfoState.value = snapshotUserInfo()
        coinsState.value = userInfo.coins
        currentStreakState.value = userInfo.streak.currentStreak
        activeBoostsState.value = HashMap(userInfo.active_boosts)
        inventoryState.value = userInfo.inventory.toMap()
        customizationInfoState.value = clonedCustomization
        context.getSharedPreferences("private_settings", Context.MODE_PRIVATE)
            .edit { putBoolean("habit_privacy_mode", userInfo.customization_info.habitPrivacyMode) }
    }

    @Synchronized
    fun setHabitPrivacyMode(enabled: Boolean) {
        userInfo.customization_info.habitPrivacyMode = enabled
        saveUserInfo()
    }

    @Synchronized
    fun setClockStyle(style: String) {
        userInfo.customization_info.clockStyle = style
        saveUserInfo()
    }

    @Synchronized
    fun setIs24Hr(is24Hr: Boolean) {
        userInfo.customization_info.is24Hr = is24Hr
        saveUserInfo()
    }

    @Synchronized
    fun setClockSizeSp(sizeSp: Float) {
        userInfo.customization_info.clockSizeSp = sizeSp
        saveUserInfo()
    }

    @Synchronized
    fun updateAppListCustomization(
        showAppIcons: Boolean = userInfo.customization_info.showAppIcons,
        appListLayout: String = userInfo.customization_info.appListLayout,
        appSortOrder: String = userInfo.customization_info.appSortOrder,
        appGroupBy: String = userInfo.customization_info.appGroupBy,
        appIconSize: String = userInfo.customization_info.appIconSize,
        autoFocusSearch: Boolean = userInfo.customization_info.autoFocusSearch
    ) {
        userInfo.customization_info.showAppIcons = showAppIcons
        userInfo.customization_info.appListLayout = appListLayout
        userInfo.customization_info.appSortOrder = appSortOrder
        userInfo.customization_info.appGroupBy = appGroupBy
        userInfo.customization_info.appIconSize = appIconSize
        userInfo.customization_info.autoFocusSearch = autoFocusSearch
        saveUserInfo()
    }

    @Synchronized
    fun setAppSortOrder(order: String) {
        userInfo.customization_info.appSortOrder = order
        saveUserInfo()
    }

    @Synchronized
    fun setAppListLayout(layout: String) {
        userInfo.customization_info.appListLayout = layout
        saveUserInfo()
    }

    @Synchronized
    fun setAppGroupBy(groupBy: String) {
        userInfo.customization_info.appGroupBy = groupBy
        saveUserInfo()
    }

    @Synchronized
    fun setShowAppIcons(show: Boolean) {
        userInfo.customization_info.showAppIcons = show
        saveUserInfo()
    }

    @Synchronized
    fun setAppIconSize(size: String) {
        userInfo.customization_info.appIconSize = size
        saveUserInfo()
    }

    @Synchronized
    fun setAutoFocusSearch(autoFocus: Boolean) {
        userInfo.customization_info.autoFocusSearch = autoFocus
        saveUserInfo()
    }

    fun getInventoryItemCount(item: InventoryItem): Int {
        return userInfo.inventory.getOrDefault(item, 0)
    }

    fun updateBlockedAppsSet(set: Set<String>) {
        userInfo.blockedAndroidPackages = set
        saveUserInfo()
    }

    fun updateUnlockedAppsSet(set: Map<String, Long>) {
        userInfo.unlockedAndroidPackages = set.toMutableMap()
        saveUserInfo()
    }

    @Synchronized
    fun deductFromInventory(item: InventoryItem, count: Int = 1) {
        if (count <= 0) return
        val current = getInventoryItemCount(item)
        if (current > 0) {
            val remaining = calculateSafeInventoryDeduction(current, count)
            if (remaining == null) {
                userInfo.inventory.remove(item)
            } else {
                userInfo.inventory[item] = remaining
            }
            saveUserInfo()
        }
    }

    fun getBlockedPackages(): Set<String> {
        return userInfo.blockedAndroidPackages ?: emptySet()
    }

    fun getUnlockedPackages(): MutableMap<String, Long> {
        return userInfo.unlockedAndroidPackages ?: mutableMapOf()
    }

    @Synchronized
    fun useCoins(number: Int) {
        if (number <= 0) return
        userInfo.coins = calculateSafeCoinDeduction(userInfo.coins, number)
        saveUserInfo()
    }

    @Synchronized
    fun addCoins(addedCoins: Int) {
        if (addedCoins <= 0) return
        userInfo.coins += addedCoins
        saveUserInfo()
    }

    /**
     * @return failing for how many days or null if not failing
     */
    fun checkIfStreakFailed(): Int? {
        val today = LocalDate.now()
        val streakData = userInfo.streak
        val lastCompleted = LocalDate.parse(streakData.lastCompletedDate)
        val daysSince = ChronoUnit.DAYS.between(lastCompleted, today)
        Log.d("streak day since", daysSince.toString())

        if (daysSince > 1) {
            return daysSince.toInt()
        }
        return null
    }

    fun tryUsingStreakFreezers(daysSince: Int): StreakFreezerReturn {
        val requiredFreezers = (daysSince - 1).toInt()
        val today = LocalDate.now()
        if (getInventoryItemCount(InventoryItem.STREAK_FREEZER) >= requiredFreezers) {
            deductFromInventory(InventoryItem.STREAK_FREEZER, requiredFreezers)

            val oldStreak = userInfo.streak.currentStreak
            userInfo.streak.currentStreak += requiredFreezers
            userInfo.streak.lastCompletedDate = today.minusDays(1).format(DateTimeFormatter.ofPattern("yyyy-MM-dd"))
            currentStreakState.value = userInfo.streak.currentStreak
            saveUserInfo()
            return StreakFreezerReturn(isOngoing = true, streakFreezersUsed = requiredFreezers, lastStreak = oldStreak)
        } else {
            // User failed streak
            val oldStreak = userInfo.streak.currentStreak
            userInfo.streak.longestStreak = maxOf(userInfo.streak.currentStreak, userInfo.streak.longestStreak)
            userInfo.streak.currentStreak = 0
            currentStreakState.value = userInfo.streak.currentStreak
            updateStreakHistory(oldStreak)
            saveUserInfo()
            return StreakFreezerReturn(isOngoing = false, streakDaysLost = oldStreak)
        }
    }

    private fun updateStreakHistory(oldStreak: Int) {
        val streakHistory = userInfo.streak.streakFailureHistory.toMutableMap()
        streakHistory[getCurrentDate()] = oldStreak
        userInfo.streak.streakFailureHistory = streakHistory
    }

    fun continueStreak(): Boolean {
        val today = LocalDate.now()
        val lastCompleted = LocalDate.parse(userInfo.streak.lastCompletedDate)
        val daysSince = ChronoUnit.DAYS.between(lastCompleted, today)

        Log.d("daysSince", daysSince.toString())
        if (daysSince != 0L) {
            userInfo.streak.currentStreak += 1
            userInfo.streak.longestStreak = maxOf(userInfo.streak.currentStreak, userInfo.streak.longestStreak)
            userInfo.streak.lastCompletedDate = getCurrentDate()
            currentStreakState.value = userInfo.streak.currentStreak

            saveUserInfo()
            return true
        }
        return false
    }

    fun calculateLevelUpInvRewards(): HashMap<InventoryItem, Int> {
        val rewards = hashMapOf<InventoryItem, Int>()
        rewards[InventoryItem.QUEST_SKIPPER] = 1
        if (userInfo.level == 2) rewards[InventoryItem.REWARD_TIME_EDITOR] = 1
        if (userInfo.level % 2 == 0) rewards[InventoryItem.XP_BOOSTER] = 1
        if (userInfo.level % 5 == 0) rewards[InventoryItem.STREAK_FREEZER] = 1
        return rewards
    }

    fun calculateLevelUpCoinsRewards(): Int {
        return maxOf(userInfo.level.times(userInfo.level), 50)
    }

    @OptIn(ExperimentalTime::class)
    fun loadUserInfo(): UserInfo {
        val sharedPreferences = context.getSharedPreferences("user_info", Context.MODE_PRIVATE)
        val userInfoJson = sharedPreferences.getString("user_info", null)
        val info = userInfoJson?.let {
            json.decodeFromString<UserInfo>(it)
        } ?: UserInfo()
        val privateSp = context.getSharedPreferences("private_settings", Context.MODE_PRIVATE)
        if (privateSp.contains("habit_privacy_mode")) {
            info.customization_info.habitPrivacyMode = privateSp.getBoolean("habit_privacy_mode", false)
        }
        return info
    }

    fun updateUser() {
        saveUserInfo()
    }

    private fun deleteLocalUserInfoCache() {
        val sharedPreferences = context.getSharedPreferences("user_info", Context.MODE_PRIVATE)
        sharedPreferences.edit { remove("user_info") }
    }

    suspend fun signOut() {
        context.deleteSharedPreferences("crnt_pg_onboard")
        context.deleteSharedPreferences("onboard")
        deleteLocalUserInfoCache()

        questRepository.deleteAll()
        statsRepository.deleteAll()

        Supabase.supabase.auth.signOut()
    }

    fun saveFcmToken(string: String) {
        val tokens = userInfo.fcm_tokens.toMutableList()
        tokens.add(string)
        userInfo.fcm_tokens = tokens
        saveUserInfo()
        Log.d("saved Fcm token", tokens.toString())
    }

    companion object {
        fun calculateSafeCoinDeduction(currentCoins: Int, number: Int): Int {
            if (number <= 0) return currentCoins
            return (currentCoins - number).coerceAtLeast(0)
        }

        fun calculateSafeInventoryDeduction(currentCount: Int, countToDeduct: Int): Int? {
            if (countToDeduct <= 0) return currentCount
            val remaining = (currentCount - countToDeduct).coerceAtLeast(0)
            return if (remaining == 0) null else remaining
        }
    }
}
