package com.alhaq.amniquest.testutil

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import com.alhaq.amniquest.backed.repositories.AppWidgetConfig
import com.alhaq.amniquest.backed.repositories.AppWidgetConfigDao
import com.alhaq.amniquest.data.CommonQuestInfo
import com.alhaq.amniquest.data.QuestDao
import com.alhaq.amniquest.data.StatsInfo
import com.alhaq.amniquest.data.StatsInfoDao
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArraySet

/**
 * Thread-safe in-memory [SharedPreferences] double supporting editor commits,
 * removals, clears, and change listener notifications for JVM unit tests.
 */
class InMemorySharedPreferences : SharedPreferences {
    private val data = ConcurrentHashMap<String, Any?>()
    private val listeners = CopyOnWriteArraySet<SharedPreferences.OnSharedPreferenceChangeListener>()

    override fun getAll(): MutableMap<String, *> = HashMap(data)

    override fun getString(key: String?, defValue: String?): String? {
        if (key == null) return defValue
        return (data[key] as? String) ?: defValue
    }

    @Suppress("UNCHECKED_CAST")
    override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? {
        if (key == null) return defValues
        val stored = data[key] as? Set<String> ?: return defValues
        return HashSet(stored)
    }

    override fun getInt(key: String?, defValue: Int): Int {
        if (key == null) return defValue
        return (data[key] as? Int) ?: defValue
    }

    override fun getLong(key: String?, defValue: Long): Long {
        if (key == null) return defValue
        return (data[key] as? Long) ?: defValue
    }

    override fun getFloat(key: String?, defValue: Float): Float {
        if (key == null) return defValue
        return (data[key] as? Float) ?: defValue
    }

    override fun getBoolean(key: String?, defValue: Boolean): Boolean {
        if (key == null) return defValue
        return (data[key] as? Boolean) ?: defValue
    }

    override fun contains(key: String?): Boolean = key != null && data.containsKey(key)

    override fun edit(): SharedPreferences.Editor = InMemoryEditor()

    override fun registerOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener?
    ) {
        if (listener != null) {
            listeners.add(listener)
        }
    }

    override fun unregisterOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener?
    ) {
        if (listener != null) {
            listeners.remove(listener)
        }
    }

    private inner class InMemoryEditor : SharedPreferences.Editor {
        private val pending = LinkedHashMap<String, Any?>()
        private val removals = HashSet<String>()
        private var clearRequested = false

        override fun putString(key: String?, value: String?): SharedPreferences.Editor = apply {
            if (key != null) {
                if (value == null) removals.add(key) else pending[key] = value
            }
        }

        override fun putStringSet(
            key: String?,
            values: MutableSet<String>?
        ): SharedPreferences.Editor = apply {
            if (key != null) {
                if (values == null) removals.add(key) else pending[key] = HashSet(values)
            }
        }

        override fun putInt(key: String?, value: Int): SharedPreferences.Editor = apply {
            if (key != null) pending[key] = value
        }

        override fun putLong(key: String?, value: Long): SharedPreferences.Editor = apply {
            if (key != null) pending[key] = value
        }

        override fun putFloat(key: String?, value: Float): SharedPreferences.Editor = apply {
            if (key != null) pending[key] = value
        }

        override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor = apply {
            if (key != null) pending[key] = value
        }

        override fun remove(key: String?): SharedPreferences.Editor = apply {
            if (key != null) {
                pending.remove(key)
                removals.add(key)
            }
        }

        override fun clear(): SharedPreferences.Editor = apply {
            pending.clear()
            removals.clear()
            clearRequested = true
        }

        override fun commit(): Boolean {
            val changedKeys = LinkedHashSet<String>()
            synchronized(data) {
                if (clearRequested) {
                    changedKeys.addAll(data.keys)
                    data.clear()
                }
                for (key in removals) {
                    if (data.remove(key) != null) {
                        changedKeys.add(key)
                    }
                }
                for ((key, value) in pending) {
                    if (value == null) {
                        data.remove(key)
                    } else {
                        data[key] = value
                    }
                    changedKeys.add(key)
                }
            }
            for (key in changedKeys) {
                for (listener in listeners) {
                    listener.onSharedPreferenceChanged(this@InMemorySharedPreferences, key)
                }
            }
            return true
        }

        override fun apply() {
            commit()
        }
    }
}

/**
 * Lightweight [Application] test double backed by a temporary [filesDir] and per-name
 * [InMemorySharedPreferences] stores.
 */
class FakeApplication(private val tempFilesDir: File) : Application() {
    private val prefsByName = ConcurrentHashMap<String, InMemorySharedPreferences>()

    override fun getApplicationContext(): Context = this

    override fun getFilesDir(): File = tempFilesDir

    override fun getPackageName(): String = "com.alhaq.amniquest"

    override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences {
        val storeName = name ?: "default"
        return prefsByName.getOrPut(storeName) { InMemorySharedPreferences() }
    }

    override fun deleteFile(name: String?): Boolean {
        if (name.isNullOrBlank()) return false
        return File(tempFilesDir, name).delete()
    }

    override fun deleteSharedPreferences(name: String?): Boolean {
        if (name == null) return false
        return prefsByName.remove(name) != null
    }
}

/**
 * In-memory [QuestDao] double supporting reactive Flow queries and optional error injection.
 */
class FakeQuestDao : QuestDao {
    private val store = MutableStateFlow<Map<String, CommonQuestInfo>>(emptyMap())

    var failOnClear: Boolean = false
    var failOnUpsert: Boolean = false

    override suspend fun upsertQuest(quest: CommonQuestInfo) {
        if (failOnUpsert) throw IllegalStateException("Injected QuestDao upsert failure")
        store.value = store.value + (quest.id to quest)
    }

    override suspend fun upsertAll(quests: List<CommonQuestInfo>) {
        if (failOnUpsert) throw IllegalStateException("Injected QuestDao upsertAll failure")
        val updated = store.value.toMutableMap()
        for (q in quests) {
            updated[q.id] = q
        }
        store.value = updated
    }

    override suspend fun deleteAll() {
        if (failOnClear) throw IllegalStateException("Injected QuestDao deleteAll failure")
        store.value = emptyMap()
    }

    override suspend fun clearAll() {
        if (failOnClear) throw IllegalStateException("Injected QuestDao clearAll failure")
        store.value = emptyMap()
    }

    override suspend fun getQuest(title: String): CommonQuestInfo? =
        store.value.values.firstOrNull { it.title == title }

    override suspend fun getQuestById(id: String): CommonQuestInfo? = store.value[id]

    override fun getAllQuests(): Flow<List<CommonQuestInfo>> =
        store.map { it.values.toList() }

    override fun getUnSyncedQuests(): Flow<List<CommonQuestInfo>> =
        store.map { map -> map.values.filter { !it.synced } }

    override fun getHardLockQuests(): Flow<List<CommonQuestInfo>> =
        store.map { map -> map.values.filter { it.isHardLock } }

    override suspend fun deleteQuest(quest: CommonQuestInfo) {
        store.value = store.value - quest.id
    }

    override suspend fun deleteQuestByTitle(title: String) {
        store.value = store.value.filterValues { it.title != title }
    }

    override suspend fun markAsSynced(id: String) {
        val existing = store.value[id] ?: return
        store.value = store.value + (id to existing.copy(synced = true))
    }

    override suspend fun getRowCount(): Int = store.value.size

    override suspend fun rebindUserQuests(newUserId: String) {
        store.value = store.value.mapValues { (_, quest) ->
            if (quest.user_id.isEmpty() || quest.user_id == "null") {
                quest.copy(user_id = newUserId, synced = false)
            } else {
                quest
            }
        }
    }
}

/**
 * In-memory [StatsInfoDao] double supporting reactive Flow queries and optional error injection.
 */
class FakeStatsInfoDao : StatsInfoDao {
    private val store = MutableStateFlow<Map<String, StatsInfo>>(emptyMap())

    var failOnUpsert: Boolean = false

    override suspend fun upsertStats(statsInfo: StatsInfo) {
        if (failOnUpsert) throw IllegalStateException("Injected StatsInfoDao upsert failure")
        store.value = store.value + (statsInfo.id to statsInfo)
    }

    override suspend fun updateStats(statsInfo: StatsInfo) {
        store.value = store.value + (statsInfo.id to statsInfo)
    }

    override suspend fun deleteAll() {
        store.value = emptyMap()
    }

    override fun getStatsByQuestId(id: String): Flow<List<StatsInfo>> =
        store.map { map -> map.values.filter { it.quest_id == id } }

    override suspend fun getStatsForUserOnDate(date: kotlinx.datetime.LocalDate): StatsInfo? =
        store.value.values.firstOrNull { it.date == date }

    override fun getAllStatsForUser(): Flow<List<StatsInfo>> =
        store.map { it.values.toList() }

    override suspend fun deleteStatsById(id: String) {
        store.value = store.value - id
    }

    override suspend fun deleteAllStatsForUser(userId: String) {
        store.value = store.value.filterValues { it.user_id != userId }
    }

    override fun getAllUnSyncedStats(): Flow<List<StatsInfo>> =
        store.map { map -> map.values.filter { !it.isSynced }.sortedByDescending { it.date } }

    override suspend fun markAsSynced(id: String) {
        val existing = store.value[id] ?: return
        store.value = store.value + (id to existing.copy(isSynced = true))
    }

    override suspend fun rebindUserStats(newUserId: String) {
        store.value = store.value.mapValues { (_, stat) ->
            if (stat.user_id.isEmpty() || stat.user_id == "null") {
                stat.copy(user_id = newUserId, isSynced = false)
            } else {
                stat
            }
        }
    }
}

/**
 * In-memory [AppWidgetConfigDao] double maintaining widget entries ordered by [AppWidgetConfig.order].
 */
class FakeAppWidgetConfigDao : AppWidgetConfigDao {
    private val configs = mutableMapOf<String, AppWidgetConfig>()

    override suspend fun insertConfig(config: AppWidgetConfig) {
        configs[config.id] = config
    }

    override suspend fun getConfigById(id: Int): AppWidgetConfig? =
        configs.values.firstOrNull { it.widgetId == id }

    override suspend fun getAllConfigs(): List<AppWidgetConfig> =
        configs.values.sortedBy { it.order }

    override suspend fun deleteConfig(config: AppWidgetConfig) {
        configs.remove(config.id)
    }

    override suspend fun deleteAllConfigs() {
        configs.clear()
    }
}
