package com.alhaq.amniquest.core.utils

import com.alhaq.amniquest.backed.repositories.AppWidgetConfig
import com.alhaq.amniquest.backed.repositories.QuestRepository
import com.alhaq.amniquest.backed.repositories.StatsRepository
import com.alhaq.amniquest.backed.repositories.UserRepository
import com.alhaq.amniquest.data.BaseIntegrationId
import com.alhaq.amniquest.data.CommonQuestInfo
import com.alhaq.amniquest.data.DayOfWeek
import com.alhaq.amniquest.data.StatsInfo
import com.alhaq.amniquest.data.UserInfo
import com.alhaq.amniquest.data.game.InventoryItem
import com.alhaq.amniquest.data.game.StreakData
import com.alhaq.amniquest.testutil.FakeAppWidgetConfigDao
import com.alhaq.amniquest.testutil.FakeApplication
import com.alhaq.amniquest.testutil.FakeQuestDao
import com.alhaq.amniquest.testutil.FakeStatsInfoDao
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.Base64

class BackupManagerEndToEndTest {

    private lateinit var tempDir: File
    private lateinit var app: FakeApplication
    private lateinit var questDao: FakeQuestDao
    private lateinit var statsDao: FakeStatsInfoDao
    private lateinit var widgetDao: FakeAppWidgetConfigDao
    private lateinit var questRepo: QuestRepository
    private lateinit var statsRepo: StatsRepository
    private lateinit var userRepo: UserRepository

    @Before
    fun setUp() {
        tempDir = Files.createTempDirectory("backup_e2e_test").toFile()
        app = FakeApplication(tempDir)
        questDao = FakeQuestDao()
        statsDao = FakeStatsInfoDao()
        widgetDao = FakeAppWidgetConfigDao()
        questRepo = QuestRepository(questDao)
        statsRepo = StatsRepository(statsDao)
        userRepo = UserRepository(app, statsRepo, questRepo)
    }

    @After
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    private suspend fun seedSampleData(isAnonymous: Boolean = true) {
        userRepo.userInfo = UserInfo(
            username = "backup_tester",
            full_name = "Backup Tester",
            coins = 420,
            level = 5,
            xp = 75,
            streak = StreakData(currentStreak = 9, longestStreak = 14, lastCompletedDate = "2026-11-18"),
            active_boosts = hashMapOf(InventoryItem.XP_BOOSTER to "2099-01-01-12-00"),
            isAnonymous = isAnonymous
        )
        userRepo.saveUserInfo(isSetLastUpdated = false)

        questRepo.upsertAll(
            listOf(
                CommonQuestInfo(
                    id = "q-1",
                    user_id = "u-1",
                    title = "Morning Run",
                    reward = 25,
                    integration_id = BaseIntegrationId.AI_SNAP,
                    selected_days = setOf(DayOfWeek.MON, DayOfWeek.WED),
                    time_range = listOf(6, 10),
                    isHardLock = true
                ),
                CommonQuestInfo(
                    id = "q-2",
                    user_id = "u-1",
                    title = "Study Block",
                    reward = 15,
                    integration_id = BaseIntegrationId.DEEP_FOCUS,
                    selected_days = setOf(DayOfWeek.TUE, DayOfWeek.THU)
                )
            )
        )
        statsRepo.upsertStats(
            StatsInfo(
                id = "s-1",
                quest_id = "q-1",
                user_id = "u-1",
                date = LocalDate(2026, 11, 18)
            )
        )
        widgetDao.insertConfig(
            AppWidgetConfig(
                id = "w-1",
                widgetId = 101,
                height = 180,
                width = null,
                borderless = true,
                background = false,
                themeColors = true,
                order = 0
            )
        )
        widgetDao.insertConfig(
            AppWidgetConfig(
                id = "w-2",
                widgetId = 102,
                height = 240,
                width = 360,
                borderless = false,
                background = true,
                themeColors = false,
                order = 1
            )
        )
    }

    @Test
    fun createAndRestoreBackup_unencryptedAndEmptyPassword_roundTripsAllState() = runTest {
        seedSampleData(isAnonymous = true)

        val plainBackup = BackupManager.createBackup(
            context = app,
            userRepository = userRepo,
            questRepository = questRepo,
            statsRepository = statsRepo,
            widgetConfigDao = widgetDao,
            password = ""
        )
        assertTrue(plainBackup.trim().startsWith("{"))

        // Reset target repositories before restoring
        val targetApp = FakeApplication(tempDir)
        val targetQuestDao = FakeQuestDao()
        val targetStatsDao = FakeStatsInfoDao()
        val targetWidgetDao = FakeAppWidgetConfigDao()
        val targetQuestRepo = QuestRepository(targetQuestDao)
        val targetStatsRepo = StatsRepository(targetStatsDao)
        val targetUserRepo = UserRepository(targetApp, targetStatsRepo, targetQuestRepo).apply {
            userInfo.isAnonymous = true
        }

        val result = BackupManager.restoreBackupDetailed(
            context = targetApp,
            backupJsonOrCiphertext = "\uFEFF  $plainBackup  ",
            userRepository = targetUserRepo,
            questRepository = targetQuestRepo,
            statsRepository = targetStatsRepo,
            widgetConfigDao = targetWidgetDao,
            password = null
        )

        assertEquals(RestoreResult.Success, result)
        assertEquals("backup_tester", targetUserRepo.userInfo.username)
        assertEquals(420, targetUserRepo.coinsState.value)
        assertEquals(9, targetUserRepo.currentStreakState.value)
        assertTrue(targetUserRepo.activeBoostsState.value.containsKey(InventoryItem.XP_BOOSTER))
        assertTrue(targetUserRepo.userInfo.isAnonymous)

        val restoredQuests = targetQuestRepo.getAllQuests().first()
        assertEquals(2, restoredQuests.size)
        assertEquals(true, restoredQuests.first { it.id == "q-1" }.isHardLock)

        val restoredStats = targetStatsRepo.getAllStatsForUser().first()
        assertEquals(1, restoredStats.size)
        assertEquals(LocalDate(2026, 11, 18), restoredStats[0].date)

        val restoredWidgets = targetWidgetDao.getAllConfigs()
        assertEquals(2, restoredWidgets.size)
        assertEquals(null, restoredWidgets[0].width)
        assertTrue(restoredWidgets[0].borderless)
        assertEquals(360, restoredWidgets[1].width)
    }

    @Test
    fun createAndRestoreBackup_encryptedAndLineWrapped_restoresExactStateAndBooleanWrapper() = runTest {
        seedSampleData(isAnonymous = false)

        val encrypted = BackupManager.createBackup(
            context = app,
            userRepository = userRepo,
            questRepository = questRepo,
            statsRepository = statsRepo,
            widgetConfigDao = widgetDao,
            password = "VaultPassword#2026"
        )
        assertFalse(encrypted.trim().startsWith("{"))
        val wrappedEncrypted = "\uFEFF" + encrypted.chunked(32).joinToString("\n")

        val targetApp = FakeApplication(tempDir)
        val targetQuestRepo = QuestRepository(FakeQuestDao())
        val targetStatsRepo = StatsRepository(FakeStatsInfoDao())
        val targetWidgetDao = FakeAppWidgetConfigDao()
        val targetUserRepo = UserRepository(targetApp, targetStatsRepo, targetQuestRepo).apply {
            userInfo.isAnonymous = false
        }

        val ok = BackupManager.restoreBackup(
            context = targetApp,
            backupJsonOrCiphertext = wrappedEncrypted,
            userRepository = targetUserRepo,
            questRepository = targetQuestRepo,
            statsRepository = targetStatsRepo,
            widgetConfigDao = targetWidgetDao,
            password = "VaultPassword#2026"
        )

        assertTrue(ok)
        assertFalse(targetUserRepo.userInfo.isAnonymous)
        assertEquals("backup_tester", targetUserRepo.userInfo.username)
        assertEquals(2, targetQuestRepo.getAllQuests().first().size)
        assertEquals(1, targetStatsRepo.getAllStatsForUser().first().size)
        assertEquals(2, targetWidgetDao.getAllConfigs().size)
    }

    @Test
    fun restoreBackupDetailed_allFailureModes_preserveExistingDatabasesUntouched() = runTest {
        seedSampleData(isAnonymous = true)
        val validEncrypted = BackupManager.createBackup(
            context = app,
            userRepository = userRepo,
            questRepository = questRepo,
            statsRepository = statsRepo,
            widgetConfigDao = widgetDao,
            password = "secret"
        )

        val failureCases = listOf(
             Triple("  \uFEFF ", null, "Backup file is empty"),
             Triple(validEncrypted, null, "Backup is encrypted; password is required"),
             Triple(validEncrypted, "", "Backup is encrypted; password is required"),
             Triple(validEncrypted, "wrong-secret", "Wrong password or corrupted encrypted backup"),
             Triple("<html>Not base64!</html>", "secret", "Invalid encrypted backup format"),
             Triple(Base64.getEncoder().encodeToString(ByteArray(37) { 7 }), "secret", "Invalid encrypted backup format"),
             Triple("""{"unexpected":"schema"}""", null, "Unsupported or invalid backup format"),
             Triple("{malformed-json", null, "Invalid backup JSON format"),
             Triple("""{"version":99}""", null, "Unsupported backup version: 99")
        )

        for ((payload, pwd, expectedReason) in failureCases) {
            val res = BackupManager.restoreBackupDetailed(
                context = app,
                backupJsonOrCiphertext = payload,
                userRepository = userRepo,
                questRepository = questRepo,
                statsRepository = statsRepo,
                widgetConfigDao = widgetDao,
                password = pwd
            )
            assertTrue("Expected failure for reason '$expectedReason' but got $res", res is RestoreResult.Failure)
            assertEquals(expectedReason, (res as RestoreResult.Failure).reason)

            // Assert pre-existing data was NOT cleared on pre-validation/decryption failures
            assertEquals("backup_tester", userRepo.userInfo.username)
            assertEquals(2, questRepo.getRowCount())
            assertEquals(1, statsRepo.getAllStatsForUser().first().size)
            assertEquals(2, widgetDao.getAllConfigs().size)
        }

        assertFalse(
            BackupManager.restoreBackup(
                context = app,
                backupJsonOrCiphertext = validEncrypted,
                userRepository = userRepo,
                questRepository = questRepo,
                statsRepository = statsRepo,
                widgetConfigDao = widgetDao,
                password = "wrong"
            )
        )
    }

    @Test
    fun restoreBackupDetailed_databaseException_returnsDetailedDatabaseFailure() = runTest {
        seedSampleData(isAnonymous = true)
        val plain = BackupManager.createBackup(
            context = app,
            userRepository = userRepo,
            questRepository = questRepo,
            statsRepository = statsRepo,
            widgetConfigDao = widgetDao,
            password = null
        )

        questDao.failOnUpsert = true
        val res = BackupManager.restoreBackupDetailed(
            context = app,
            backupJsonOrCiphertext = plain,
            userRepository = userRepo,
            questRepository = questRepo,
            statsRepository = statsRepo,
            widgetConfigDao = widgetDao,
            password = null
        )

        assertTrue(res is RestoreResult.Failure)
        val reason = (res as RestoreResult.Failure).reason
        assertTrue("Expected DB failure prefix, got: $reason", reason.startsWith("Database error while restoring backup:"))
    }
}
