package com.alhaq.amniquest.app.screens.launcher

import android.annotation.SuppressLint
import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.util.Log
import androidx.core.content.ContextCompat.startForegroundService
import androidx.core.content.edit
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.datetime.TimeZone
import kotlinx.datetime.daysUntil
import com.alhaq.amniquest.data.CustomizationInfo
import com.alhaq.amniquest.backed.repositories.QuestRepository
import com.alhaq.amniquest.backed.repositories.UserRepository
import com.alhaq.amniquest.core.services.AppBlockerService
import com.alhaq.amniquest.core.services.AppBlockerServiceInfo
import com.alhaq.amniquest.core.services.INTENT_ACTION_UNLOCK_APP
import com.alhaq.amniquest.core.utils.managers.QuestHelper
import com.alhaq.amniquest.core.core.utils.ScreenUsageStatsHelper
import com.alhaq.amniquest.core.core.utils.getCurrentDate
import com.alhaq.amniquest.core.core.utils.getCurrentDay
import com.alhaq.amniquest.core.core.utils.managers.getCachedApps
import com.alhaq.amniquest.core.core.utils.managers.reloadApps
import com.alhaq.amniquest.data.AppInfo
import java.time.LocalDate
import javax.inject.Inject
import kotlin.math.roundToInt
import kotlin.time.ExperimentalTime
import kotlin.time.toKotlinInstant

@HiltViewModel
class AppListViewModel @Inject constructor(
    application: Application,
    private val userRepository: UserRepository,
    private val questRepository: QuestRepository
) : AndroidViewModel(application) {

    @SuppressLint("StaticFieldLeak")
    private val context = application.applicationContext

    val coins = userRepository.coinsState

    private val _apps = MutableStateFlow<List<AppInfo>>(emptyList())
    val apps: StateFlow<List<AppInfo>> = _apps

    private val _filteredApps = MutableStateFlow<List<AppInfo>>(emptyList())
    val filteredApps: StateFlow<List<AppInfo>> = _filteredApps.asStateFlow()

    private val _isLoading = MutableStateFlow(true)
    val isLoading = _isLoading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error = _error.asStateFlow()

    private val _showCoinDialog = MutableStateFlow(false)
    val showCoinDialog = _showCoinDialog.asStateFlow()

    private val _selectedPackage = MutableStateFlow("")
    val selectedPackage = _selectedPackage.asStateFlow()

    private val _distractions = MutableStateFlow<Set<String>>(emptySet())
    val distractions = _distractions.asStateFlow()
    private val unlockedDistractions = MutableStateFlow<Map<String, Long>>(mapOf())


    private val _searchQuery = MutableStateFlow("")
    val searchQuery = _searchQuery.asStateFlow()

    var minutesPerFiveCoins = MutableStateFlow(10)
        private set

    var remainingFreePassesToday = MutableStateFlow(0)

    val isHardLockedQuestsToday = MutableStateFlow<Boolean>(false)

    val customizationInfo = userRepository.customizationInfoState

    private val _screentimeMap = MutableStateFlow<Map<String, Long>>(emptyMap())

    val groupedApps: StateFlow<List<AppGroup>> = combine(
        _apps,
        _searchQuery,
        _distractions,
        _screentimeMap,
        userRepository.customizationInfoState
    ) { apps, query, distractions, screentimes, customization ->
        buildGroupedApps(
            apps = apps,
            searchQuery = query,
            distractions = distractions,
            screentimeMap = screentimes,
            sortOrder = customization.appSortOrder,
            groupBy = customization.appGroupBy
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    init {
        viewModelScope.launch(Dispatchers.IO) {
            loadApps()
            runCatching { initFreePasses() }
            reloadDistractions()
            reloadUnlockedApps()
            loadHardLockedQuests()
            loadScreentimes()
        }
    }

    fun loadScreentimes() {
        val stats = runCatching { ScreenUsageStatsHelper(context).getStatsForLast7Days() }.getOrDefault(emptyList())
        val map = mutableMapOf<String, Long>()
        for (stat in stats) {
            map[stat.packageName] = (map[stat.packageName] ?: 0L) + stat.totalTime
        }
        _screentimeMap.value = map
    }

    fun setSortOrder(order: String) {
        userRepository.setAppSortOrder(order)
    }

    fun setViewMode(mode: String) {
        userRepository.setAppListLayout(mode)
    }

    fun setGroupBy(groupBy: String) {
        userRepository.setAppGroupBy(groupBy)
    }

    fun toggleShowIcons(show: Boolean) {
        userRepository.setShowAppIcons(show)
    }

    fun setIconSize(size: String) {
        userRepository.setAppIconSize(size)
    }

    fun setAutoFocusSearch(autoFocus: Boolean) {
        userRepository.setAutoFocusSearch(autoFocus)
    }

    suspend fun loadHardLockedQuests() {
        questRepository.getHardLockedQuests().collectLatest { unfiltered ->
            val todayDay = getCurrentDay()
            val filtered = unfiltered.filter {
                !it.is_destroyed && it.selected_days.contains(todayDay) && it.last_completed_on != getCurrentDate() &&
                !QuestHelper.isTimeOver(it)
            }
            isHardLockedQuestsToday.value = filtered.isNotEmpty()
        }

    }

    fun loadMinutePer5Coins(){
        val prefs = context.getSharedPreferences("minutes_per_5", Context.MODE_PRIVATE)
        minutesPerFiveCoins.value = prefs.getInt("minutes_per_5", 10)

    }

     suspend fun loadApps() {
         loadMinutePer5Coins()
        val cached = getCachedApps(context)
        if (cached.isNotEmpty()) {
            _apps.value = cached
            _filteredApps.value = cached
            _isLoading.value = false
        }

        withContext(Dispatchers.IO) {
            reloadApps(context.packageManager, context).onSuccess {
                _apps.value = it
                _filteredApps.value = it
                _isLoading.value = false
            }.onFailure {
                _error.value = it.message
                _isLoading.value = false
            }
        }

    }

    fun reloadDistractions(){
        _distractions.value = userRepository.getBlockedPackages()
    }
    fun reloadUnlockedApps(){
        unlockedDistractions.value = userRepository.getUnlockedPackages()
    }
    fun onSearchQueryChange(query: String) {
        _searchQuery.value = query
        _filteredApps.value = _apps.value.filter { it.name.contains(query, ignoreCase = true) }
    }

    fun onAppClick(packageName: String) {
        reloadDistractions()
        loadMinutePer5Coins()
        reloadUnlockedApps()
        val cooldownUntil = unlockedDistractions.value[packageName] ?: 0L
        val isDistraction = _distractions.value.contains(packageName)

        Log.d("Distracting apps", _distractions.value.toString())
        if (isDistraction && (cooldownUntil == -1L || System.currentTimeMillis() > cooldownUntil)) {
            _selectedPackage.value = packageName
            _showCoinDialog.value = true
        } else {
            launchApp(context, packageName)
            onSearchQueryChange("")
        }
    }

    fun onLongAppClick(packageName: String) {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", packageName, null)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    fun onConfirmUnlockApp(coins: Int) {
        Log.d("Unlocking app ${_selectedPackage.value}","duration ${minutesPerFiveCoins.value * coins}")

        requestAppUnlock(minutesPerFiveCoins.value * (coins/5))
        userRepository.useCoins(coins)
        launchApp(context, _selectedPackage.value)
        _showCoinDialog.value = false
    }

    fun requestAppUnlock(cooldownInMins:Int){
        if (
            AppBlockerServiceInfo.appBlockerService == null
        ) {
            try {
                startForegroundService(context, Intent(context, AppBlockerService::class.java))
            } catch (e: Exception) {
                Log.e("AppListViewModel", "Failed to start AppBlockerService: ${e.message}")
            }
        }

        val cooldownTime =  cooldownInMins * 60_000L
        val pkg = _selectedPackage.value
        val intent = Intent().apply {
            action = INTENT_ACTION_UNLOCK_APP
            putExtra("selected_time", cooldownTime)
            putExtra("package_name", pkg)
        }
        context.sendBroadcast(intent)

    }

    fun dismissDialog() {
        _showCoinDialog.value = false
    }


    @OptIn(ExperimentalTime::class)
    fun calculateAvailableFreePasses(screenTimes: List<Double>): Int {
        if (screenTimes.size < 7) return 3 // Fallback for partial data

        val now = kotlin.time.Clock.System.now()
        val questStreak = userRepository.userInfo.streak.currentStreak
        val daysSinceCreated = userRepository.userInfo.created_on.toKotlinInstant()
            .daysUntil(now, TimeZone.currentSystemDefault())
        val weeksSinceFirstUse = daysSinceCreated / 7.0
        val userLevel = userRepository.userInfo.level

        val weights = listOf(0.25, 0.2, 0.15, 0.15, 0.1, 0.1, 0.05)
        val weightedAvg = screenTimes.zip(weights).sumOf { (t, w) -> t * w }

        val today = screenTimes[0]
        val yesterday = screenTimes[1]
        val yesterdayAvg = screenTimes.drop(1).average()

        val isNewUser = daysSinceCreated < 7
        val isImproving = today < yesterdayAvg - 1
        val isConsistent = questStreak >= (2 + userLevel / 2)

        val generosityBoost = if (isNewUser) 2.0 else (1.5 - 0.1 * weeksSinceFirstUse).coerceAtLeast(0.5)
        val difficulty = 2.0 + (userLevel * 0.25)
        val baseUnlocks = ((weightedAvg / difficulty) * generosityBoost)

        val progressBonus = if (isImproving) 1 else 0
        val streakBonus = if (isConsistent) 1 else 0

        // [INFO] Dynamic max based on yesterday's screen time
        val baseCap = (yesterday * 60 / 10).roundToInt() // 10 min = 1 unlock
        val trendFactor = if (isImproving) 0.75 else 1.0
        val generosityDecay = (1.2 - 0.1 * weeksSinceFirstUse).coerceAtLeast(0.5)
        val dynamicMax = (baseCap * trendFactor * generosityDecay).roundToInt().coerceIn(2, 10)

        return (baseUnlocks + progressBonus + streakBonus).roundToInt().coerceIn(1, dynamicMax)
    }

    fun initFreePasses(){
        runCatching {
            val prefs = context.getSharedPreferences("user_prefs", Context.MODE_PRIVATE)
            val today = LocalDate.now().toString()
            val lastUsedDate = prefs.getString("last_freepass_date", null)

            if (lastUsedDate == today) {
                remainingFreePassesToday.value = prefs.getInt("freepass_count", 0)
            } else {
                val stats = runCatching { ScreenUsageStatsHelper(context).getStatsForLast7Days() }.getOrDefault(emptyList())
                val filteredTimes = stats.filter { it.packageName == selectedPackage.value }.map { it.totalTime.toDouble() }
                remainingFreePassesToday.value = calculateAvailableFreePasses(filteredTimes)
                prefs.edit {
                    putString("last_freepass_date", today)
                    putInt("freepass_count", remainingFreePassesToday.value)
                }
            }
        }.onFailure { e ->
            Log.e("AppListViewModel", "Failed to initFreePasses: ${e.message}", e)
        }
    }

    fun useFreePass() {

        val prefs = context.getSharedPreferences("user_prefs", Context.MODE_PRIVATE)
        val today = LocalDate.now().toString()
        val remainingFreePassesToday = prefs.getInt("freepass_count", 0) - 1

        prefs.edit {
            putString("ladistracting_appsst_freepass_date", today)
            putInt("freepass_count", remainingFreePassesToday)
        }

        requestAppUnlock(10)
        launchApp(context,_selectedPackage.value)
        onConfirmUnlockApp(0)
    }


}

fun launchApp(context: Context, packageName: String) {
    val intent = context.packageManager.getLaunchIntentForPackage(packageName)
    intent?.let { context.startActivity(it) }
}

data class AppGroup(
    val title: String,
    val apps: List<AppInfo>
)

fun buildGroupedApps(
    apps: List<AppInfo>,
    searchQuery: String,
    distractions: Set<String>,
    screentimeMap: Map<String, Long>,
    sortOrder: String,
    groupBy: String,
    currentTimeMs: Long = System.currentTimeMillis()
): List<AppGroup> {
    val deduplicated = apps.distinctBy { it.packageName }
    val filtered = if (searchQuery.isBlank()) {
        deduplicated
    } else {
        deduplicated.filter { it.name.contains(searchQuery.trim(), ignoreCase = true) }
    }

    val comparator = when (sortOrder) {
        "ReverseAlphabetical" -> Comparator<AppInfo> { a, b -> b.name.compareTo(a.name, ignoreCase = true) }
        "MostUsed" -> Comparator<AppInfo> { a, b ->
            val timeA = screentimeMap[a.packageName] ?: 0L
            val timeB = screentimeMap[b.packageName] ?: 0L
            val comp = timeB.compareTo(timeA)
            if (comp != 0) comp else a.name.compareTo(b.name, ignoreCase = true)
        }
        "InstallDate" -> Comparator<AppInfo> { a, b ->
            val comp = b.installTime.compareTo(a.installTime)
            if (comp != 0) comp else a.name.compareTo(b.name, ignoreCase = true)
        }
        else -> Comparator<AppInfo> { a, b -> a.name.compareTo(b.name, ignoreCase = true) }
    }

    return when (groupBy) {
        "Alphabet" -> {
            filtered
                .groupBy { app ->
                    val trimmed = app.name.trimStart()
                    val first = trimmed.firstOrNull()?.uppercaseChar()
                    if (first != null && first.isLetter()) first.toString() else "#"
                }
                .map { (key, groupApps) ->
                    AppGroup(key, groupApps.sortedWith(comparator))
                }
                .sortedWith { g1, g2 ->
                    if (g1.title == "#") 1
                    else if (g2.title == "#") -1
                    else g1.title.compareTo(g2.title)
                }
        }
        "Category" -> {
            filtered
                .groupBy { app -> getCategoryName(app.category) }
                .map { (key, groupApps) ->
                    AppGroup(key, groupApps.sortedWith(comparator))
                }
                .sortedWith { g1, g2 ->
                    if (g1.title == "Other / System") 1
                    else if (g2.title == "Other / System") -1
                    else g1.title.compareTo(g2.title)
                }
        }
        "InstallDate" -> {
            val buckets = linkedMapOf(
                "Today" to mutableListOf<AppInfo>(),
                "This Week" to mutableListOf<AppInfo>(),
                "This Month" to mutableListOf<AppInfo>(),
                "Older" to mutableListOf<AppInfo>()
            )
            for (app in filtered) {
                val diff = currentTimeMs - app.installTime
                when {
                    diff < 24 * 60 * 60 * 1000L -> buckets["Today"]?.add(app)
                    diff < 7 * 24 * 60 * 60 * 1000L -> buckets["This Week"]?.add(app)
                    diff < 30L * 24 * 60 * 60 * 1000L -> buckets["This Month"]?.add(app)
                    else -> buckets["Older"]?.add(app)
                }
            }
            buckets.filter { it.value.isNotEmpty() }
                .map { (key, list) -> AppGroup(key, list.sortedWith(comparator)) }
        }
        "Status" -> {
            val distractionsList = mutableListOf<AppInfo>()
            val allowedList = mutableListOf<AppInfo>()
            for (app in filtered) {
                if (distractions.contains(app.packageName)) {
                    distractionsList.add(app)
                } else {
                    allowedList.add(app)
                }
            }
            val result = mutableListOf<AppGroup>()
            if (distractionsList.isNotEmpty()) {
                result.add(AppGroup("Distractions", distractionsList.sortedWith(comparator)))
            }
            if (allowedList.isNotEmpty()) {
                result.add(AppGroup("Allowed Apps", allowedList.sortedWith(comparator)))
            }
            result
        }
        else -> {
            listOf(AppGroup("", filtered.sortedWith(comparator)))
        }
    }
}

fun getCategoryName(category: Int): String {
    return when (category) {
        0 -> "Games"
        1 -> "Audio & Music"
        2 -> "Video & Movies"
        3 -> "Photos & Images"
        4 -> "Social & Communication"
        5 -> "News & Magazines"
        6 -> "Maps & Navigation"
        7 -> "Productivity"
        8 -> "Accessibility"
        else -> "Other / System"
    }
}
