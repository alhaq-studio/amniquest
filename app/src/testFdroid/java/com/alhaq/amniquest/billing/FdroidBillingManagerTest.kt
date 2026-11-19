package com.alhaq.amniquest.billing

import com.alhaq.amniquest.backed.repositories.QuestRepository
import com.alhaq.amniquest.backed.repositories.StatsRepository
import com.alhaq.amniquest.backed.repositories.UserRepository
import com.alhaq.amniquest.data.CustomizationInfo
import com.alhaq.amniquest.testutil.FakeApplication
import com.alhaq.amniquest.testutil.FakeQuestDao
import com.alhaq.amniquest.testutil.FakeStatsInfoDao
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

class FdroidBillingManagerTest {

    private lateinit var tempDir: File
    private lateinit var app: FakeApplication
    private lateinit var userRepo: UserRepository

    @Before
    fun setUp() {
        tempDir = Files.createTempDirectory("fdroid_billing_test").toFile()
        app = FakeApplication(tempDir)
        userRepo = UserRepository(
            context = app,
            statsRepository = StatsRepository(FakeStatsInfoDao()),
            questRepository = QuestRepository(FakeQuestDao())
        )
    }

    @After
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    @Test
    fun initialState_reflectsUserRepositorySupporterMetadata() {
        userRepo.userInfo.customization_info = CustomizationInfo(
            isSupporter = true,
            supporterTier = "annual",
            offlineLicenseKey = "invalid.key"
        )
        val manager = FdroidBillingManager(userRepo)

        assertTrue(manager.isReady.value)
        assertTrue(manager.isSupporter.value)
        assertEquals("annual", manager.supporterTier.value)

        // refreshPurchases invalidates forged/corrupted stored offline key
        manager.refreshPurchases()
        assertFalse(manager.isSupporter.value)
        assertNull(manager.supporterTier.value)
    }

    @Test
    fun activateOfflineLicense_rejectsInvalidOrMalformedKeys() {
        val manager = FdroidBillingManager(userRepo)

        var resultSuccess = true
        var resultMessage: String? = null
        manager.activateOfflineLicense("not-a-valid-ecdsa-license") { success, message ->
            resultSuccess = success
            resultMessage = message
        }

        assertFalse(resultSuccess)
        assertEquals("Invalid or expired cryptographic license key.", resultMessage)
        assertFalse(manager.isSupporter.value)
        assertNull(manager.supporterTier.value)
    }
}
