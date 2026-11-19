package com.alhaq.amniquest.backed.repositories

import com.alhaq.amniquest.data.UserInfo
import com.alhaq.amniquest.data.game.InventoryItem
import com.alhaq.amniquest.data.game.StreakData
import com.alhaq.amniquest.testutil.FakeApplication
import com.alhaq.amniquest.testutil.FakeQuestDao
import com.alhaq.amniquest.testutil.FakeStatsInfoDao
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.time.LocalDate
import java.time.format.DateTimeFormatter

class UserRepositoryTest {

    private lateinit var tempDir: File
    private lateinit var app: FakeApplication
    private lateinit var repo: UserRepository

    @Before
    fun setUp() {
        tempDir = Files.createTempDirectory("user_repo_test").toFile()
        app = FakeApplication(tempDir)
        val questRepo = QuestRepository(FakeQuestDao())
        val statsRepo = StatsRepository(FakeStatsInfoDao())
        repo = UserRepository(app, statsRepo, questRepo)
        repo.userInfo.isAnonymous = true
    }

    @After
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    @Test
    fun addXp_handlesNormalAndBoostedMultipliersAndMultiLevelRollover() {
        // Level 1 needs 100 XP, Level 2 needs 400 XP -> 550 XP brings user to Level 3 with 50 XP
        repo.addXp(550)
        assertEquals(3, repo.userInfo.level)
        assertEquals(50, repo.userInfo.xp)

        // Activate 2x XP booster -> 450 XP becomes 900 XP; Level 3 needs 900 XP -> Level 4 with 50 XP
        repo.activateBoost(InventoryItem.XP_BOOSTER, hoursToAdd = 2, minsToAdd = 0)
        assertTrue(repo.isBoosterActive(InventoryItem.XP_BOOSTER))

        repo.addXp(450)
        assertEquals(4, repo.userInfo.level)
        assertEquals(50, repo.userInfo.xp)
    }

    @Test
    fun isBoosterActive_autoRemovesExpiredBoosters() {
        repo.userInfo.active_boosts[InventoryItem.XP_BOOSTER] = "2020-01-01-00-00"
        assertFalse(repo.isBoosterActive(InventoryItem.XP_BOOSTER))
        assertFalse(repo.userInfo.active_boosts.containsKey(InventoryItem.XP_BOOSTER))
        assertFalse(repo.activeBoostsState.value.containsKey(InventoryItem.XP_BOOSTER))
    }

    @Test
    fun coinsAndInventoryOperations_updateUserInfoAndStateFlows() {
        val startingCoins = repo.userInfo.coins // 90
        repo.addCoins(60)
        assertEquals(startingCoins + 60, repo.userInfo.coins)
        assertEquals(startingCoins + 60, repo.coinsState.value)

        repo.useCoins(40)
        assertEquals(startingCoins + 20, repo.userInfo.coins)
        assertEquals(startingCoins + 20, repo.coinsState.value)

        // Inventory starts with 2 STREAK_FREEZER
        assertEquals(2, repo.getInventoryItemCount(InventoryItem.STREAK_FREEZER))
        repo.addItemsToInventory(hashMapOf(InventoryItem.QUEST_SKIPPER to 3))
        assertEquals(3, repo.getInventoryItemCount(InventoryItem.QUEST_SKIPPER))

        repo.deductFromInventory(InventoryItem.QUEST_SKIPPER, 3)
        assertEquals(0, repo.getInventoryItemCount(InventoryItem.QUEST_SKIPPER))
        assertFalse(repo.userInfo.inventory.containsKey(InventoryItem.QUEST_SKIPPER))

        // Deducting absent item is a safe no-op
        repo.deductFromInventory(InventoryItem.XP_BOOSTER, 1)
        assertEquals(0, repo.getInventoryItemCount(InventoryItem.XP_BOOSTER))
    }

    @Test
    fun continueStreak_and_checkIfStreakFailed_and_tryUsingStreakFreezers_enforceStreakRules() {
        val fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd")
        val today = LocalDate.now()

        // Yesterday completed -> not failed yet
        repo.userInfo.streak = StreakData(
            currentStreak = 4,
            longestStreak = 4,
            lastCompletedDate = today.minusDays(1).format(fmt)
        )
        assertNull(repo.checkIfStreakFailed())

        // Continue today increments streak and updates longestStreak
        assertTrue(repo.continueStreak())
        assertEquals(5, repo.userInfo.streak.currentStreak)
        assertEquals(5, repo.userInfo.streak.longestStreak)
        assertEquals(5, repo.currentStreakState.value)

        // Calling continueStreak again on same day returns false
        assertFalse(repo.continueStreak())
        assertEquals(5, repo.userInfo.streak.currentStreak)

        // 3 days missed -> daysSince = 3 -> needs 2 streak freezers (user has 2 by default)
        repo.userInfo.streak = StreakData(
            currentStreak = 10,
            longestStreak = 10,
            lastCompletedDate = today.minusDays(3).format(fmt)
        )
        val daysSince = repo.checkIfStreakFailed()
        assertEquals(3, daysSince)

        val freezeRes = repo.tryUsingStreakFreezers(daysSince!!)
        assertTrue(freezeRes.isOngoing)
        assertEquals(2, freezeRes.streakFreezersUsed)
        assertEquals(10, freezeRes.lastStreak)
        assertEquals(12, repo.userInfo.streak.currentStreak)
        assertEquals(0, repo.getInventoryItemCount(InventoryItem.STREAK_FREEZER))

        // Missed 2 days with 0 freezers -> fails streak and records history
        repo.userInfo.streak = StreakData(
            currentStreak = 12,
            longestStreak = 8,
            lastCompletedDate = today.minusDays(2).format(fmt)
        )
        val failRes = repo.tryUsingStreakFreezers(2)
        assertFalse(failRes.isOngoing)
        assertEquals(12, failRes.streakDaysLost)
        assertEquals(0, repo.userInfo.streak.currentStreak)
        assertEquals(12, repo.userInfo.streak.longestStreak)
        assertEquals(0, repo.currentStreakState.value)
        assertEquals(12, repo.userInfo.streak.streakFailureHistory[today.format(fmt)])
    }

    @Test
    fun levelUpRewards_matchMilestoneFormulasAndPersistAcrossReload() {
        repo.userInfo.level = 1
        assertEquals(50, repo.calculateLevelUpCoinsRewards())
        assertEquals(mapOf(InventoryItem.QUEST_SKIPPER to 1), repo.calculateLevelUpInvRewards())

        repo.userInfo.level = 2
        assertEquals(50, repo.calculateLevelUpCoinsRewards())
        assertEquals(
            mapOf(
                InventoryItem.QUEST_SKIPPER to 1,
                InventoryItem.REWARD_TIME_EDITOR to 1,
                InventoryItem.XP_BOOSTER to 1
            ),
            repo.calculateLevelUpInvRewards()
        )

        repo.userInfo.level = 5
        assertEquals(50, repo.calculateLevelUpCoinsRewards())
        assertEquals(
            mapOf(
                InventoryItem.QUEST_SKIPPER to 1,
                InventoryItem.STREAK_FREEZER to 1
            ),
            repo.calculateLevelUpInvRewards()
        )

        repo.userInfo.level = 10
        assertEquals(100, repo.calculateLevelUpCoinsRewards())
        assertEquals(
            mapOf(
                InventoryItem.QUEST_SKIPPER to 1,
                InventoryItem.XP_BOOSTER to 1,
                InventoryItem.STREAK_FREEZER to 1
            ),
            repo.calculateLevelUpInvRewards()
        )

        repo.userInfo.username = "persisted_hero"
        repo.saveUserInfo()
        val reloaded: UserInfo = repo.loadUserInfo()
        assertNotNull(reloaded)
        assertEquals("persisted_hero", reloaded.username)
        assertEquals(10, reloaded.level)
    }
}
