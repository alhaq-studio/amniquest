package com.alhaq.amniquest.app.screens.launcher

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import com.alhaq.amniquest.backed.repositories.QuestRepository
import com.alhaq.amniquest.backed.repositories.StatsRepository
import com.alhaq.amniquest.backed.repositories.UserRepository
import com.alhaq.amniquest.data.CommonQuestInfo
import com.alhaq.amniquest.data.CustomizationInfo
import com.alhaq.amniquest.data.QuestDao
import com.alhaq.amniquest.data.StatsInfo
import com.alhaq.amniquest.data.StatsInfoDao
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HomeScreenViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun homeScreenViewModel_initialization_doesNotThrowNpeOrSecurityException() {
        val app = TestApplication()
        val questDao = FakeQuestDao()
        val statsDao = FakeStatsInfoDao()
        val questRepo = QuestRepository(questDao)
        val statsRepo = StatsRepository(statsDao)
        val userRepo = UserRepository(app, statsRepo, questRepo)

        // Instantiating HomeScreenViewModel should succeed without throwing NPE
        // from lexical field initialization ordering of customizationInfo,
        // and without throwing SecurityException from unguarded exact alarms.
        val viewModel = HomeScreenViewModel(
            application = app,
            questRepository = questRepo,
            statsRepository = statsRepo,
            userRepository = userRepo
        )

        assertNotNull(viewModel)
        assertNotNull(viewModel.customizationInfo)
        assertNotNull(viewModel.customizationInfo.value)
        assertEquals(userRepo.userInfo.customization_info, viewModel.customizationInfo.value)
    }

    @Test
    fun homeScreenViewModel_customizationInfoUpdates_reflectsImmediately() {
        val app = TestApplication()
        val questDao = FakeQuestDao()
        val statsDao = FakeStatsInfoDao()
        val questRepo = QuestRepository(questDao)
        val statsRepo = StatsRepository(statsDao)
        val userRepo = UserRepository(app, statsRepo, questRepo)

        val viewModel = HomeScreenViewModel(
            application = app,
            questRepository = questRepo,
            statsRepository = statsRepo,
            userRepository = userRepo
        )

        val updated = CustomizationInfo(is24Hr = true, equippedWidget = "Pixel Familiar")
        viewModel.updateCustomizationInfo(updated)

        assertEquals(true, viewModel.customizationInfo.value.is24Hr)
        assertEquals("Pixel Familiar", viewModel.customizationInfo.value.equippedWidget)
    }

    @Test
    fun homeScreenViewModel_toggleTimeClock_persistsAndUpdatesClockMode() {
        val app = TestApplication()
        val questDao = FakeQuestDao()
        val statsDao = FakeStatsInfoDao()
        val questRepo = QuestRepository(questDao)
        val statsRepo = StatsRepository(statsDao)
        val userRepo = UserRepository(app, statsRepo, questRepo)

        val viewModel = HomeScreenViewModel(
            application = app,
            questRepository = questRepo,
            statsRepository = statsRepo,
            userRepository = userRepo
        )

        val initialIs24Hr = viewModel.customizationInfo.value.is24Hr
        viewModel.toggleTimeCLock()
        assertEquals(!initialIs24Hr, viewModel.customizationInfo.value.is24Hr)
    }

    @Test
    fun homeScreenViewModel_filterQuests_handlesEmptyAndCompletedQuests() = kotlinx.coroutines.runBlocking {
        val app = TestApplication()
        val questDao = FakeQuestDao()
        val statsDao = FakeStatsInfoDao()
        val questRepo = QuestRepository(questDao)
        val statsRepo = StatsRepository(statsDao)
        val userRepo = UserRepository(app, statsRepo, questRepo)

        val viewModel = HomeScreenViewModel(
            application = app,
            questRepository = questRepo,
            statsRepository = statsRepo,
            userRepository = userRepo
        )

        viewModel.filterQuests()
        assertEquals(emptyList<CommonQuestInfo>(), viewModel.questList.value)
        assertEquals(emptyList<String>(), viewModel.completedQuests.value)
    }

    private class TestApplication : Application() {
        private val prefs = mutableMapOf<String, SharedPreferences>()

        override fun getApplicationContext(): Context = this

        override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences {
            return prefs.getOrPut(name ?: "") { FakeSharedPreferences() }
        }

        override fun getSystemService(name: String): Any? = null
    }

    private class FakeSharedPreferences : SharedPreferences {
        private val map = mutableMapOf<String, Any?>()

        override fun getAll(): MutableMap<String, *> = map
        override fun getString(key: String?, defValue: String?): String? = map[key] as? String ?: defValue
        override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? =
            (map[key] as? Set<*>)?.filterIsInstance<String>()?.toMutableSet() ?: defValues
        override fun getInt(key: String?, defValue: Int): Int = map[key] as? Int ?: defValue
        override fun getLong(key: String?, defValue: Long): Long = map[key] as? Long ?: defValue
        override fun getFloat(key: String?, defValue: Float): Float = map[key] as? Float ?: defValue
        override fun getBoolean(key: String?, defValue: Boolean): Boolean = map[key] as? Boolean ?: defValue
        override fun contains(key: String?): Boolean = map.containsKey(key)
        override fun edit(): SharedPreferences.Editor = FakeEditor(map)
        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
    }

    private class FakeEditor(private val backingMap: MutableMap<String, Any?>) : SharedPreferences.Editor {
        private val pending = mutableMapOf<String, Any?>()
        private val removed = mutableSetOf<String>()
        private var shouldClear = false

        override fun putString(key: String?, value: String?): SharedPreferences.Editor {
            if (key != null) pending[key] = value
            return this
        }
        override fun putStringSet(key: String?, values: MutableSet<String>?): SharedPreferences.Editor {
            if (key != null) pending[key] = values?.toSet()
            return this
        }
        override fun putInt(key: String?, value: Int): SharedPreferences.Editor {
            if (key != null) pending[key] = value
            return this
        }
        override fun putLong(key: String?, value: Long): SharedPreferences.Editor {
            if (key != null) pending[key] = value
            return this
        }
        override fun putFloat(key: String?, value: Float): SharedPreferences.Editor {
            if (key != null) pending[key] = value
            return this
        }
        override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor {
            if (key != null) pending[key] = value
            return this
        }
        override fun remove(key: String?): SharedPreferences.Editor {
            if (key != null) removed.add(key)
            return this
        }
        override fun clear(): SharedPreferences.Editor {
            shouldClear = true
            return this
        }
        override fun commit(): Boolean {
            apply()
            return true
        }
        override fun apply() {
            if (shouldClear) backingMap.clear()
            removed.forEach { backingMap.remove(it) }
            backingMap.putAll(pending)
        }
    }

    private class FakeQuestDao : QuestDao {
        private val quests = mutableListOf<CommonQuestInfo>()
        override suspend fun upsertQuest(quest: CommonQuestInfo) { quests.add(quest) }
        override suspend fun upsertAll(quests: List<CommonQuestInfo>) { this.quests.addAll(quests) }
        override suspend fun deleteAll() { quests.clear() }
        override suspend fun clearAll() { quests.clear() }
        override suspend fun getQuest(title: String): CommonQuestInfo? = quests.find { it.title == title }
        override suspend fun getQuestById(id: String): CommonQuestInfo? = quests.find { it.id == id }
        override fun getAllQuests(): Flow<List<CommonQuestInfo>> = flowOf(quests)
        override fun getHardLockQuests(): Flow<List<CommonQuestInfo>> = flowOf(emptyList())
        override fun getUnSyncedQuests(): Flow<List<CommonQuestInfo>> = flowOf(emptyList())
        override suspend fun deleteQuest(quest: CommonQuestInfo) { quests.remove(quest) }
        override suspend fun deleteQuestByTitle(title: String) { quests.removeAll { it.title == title } }
        override suspend fun markAsSynced(id: String) {}
        override suspend fun getRowCount(): Int = quests.size
        override suspend fun rebindUserQuests(userId: String) {}
    }

    private class FakeStatsInfoDao : StatsInfoDao {
        override suspend fun upsertStats(statsInfo: StatsInfo) {}
        override suspend fun updateStats(statsInfo: StatsInfo) {}
        override suspend fun deleteAll() {}
        override fun getStatsByQuestId(id: String): Flow<List<StatsInfo>> = flowOf(emptyList())
        override suspend fun getStatsForUserOnDate(date: kotlinx.datetime.LocalDate): StatsInfo? = null
        override fun getAllStatsForUser(): Flow<List<StatsInfo>> = flowOf(emptyList())
        override suspend fun deleteStatsById(id: String) {}
        override suspend fun deleteAllStatsForUser(userId: String) {}
        override fun getAllUnSyncedStats(): Flow<List<StatsInfo>> = flowOf(emptyList())
        override suspend fun markAsSynced(id: String) {}
        override suspend fun rebindUserStats(userId: String) {}
    }
}
