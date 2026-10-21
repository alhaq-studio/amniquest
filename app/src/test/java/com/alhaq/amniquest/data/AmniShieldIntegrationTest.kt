package com.alhaq.amniquest.data

import com.alhaq.amniquest.data.quest.shield.AmniShieldQuestConfig
import com.alhaq.amniquest.data.quest.shield.AmniShieldQuestType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AmniShieldIntegrationTest {

    @Test
    fun amniShieldQuestConfig_serializationRoundTrip_preservesAllFields() {
        val original = AmniShieldQuestConfig(
            questType = AmniShieldQuestType.FOCUS_SESSION,
            targetMinutes = 45,
            maxAllowedReels = 10,
            targetStreakDays = 14,
            targetScore = 90
        )

        val serialized = json.encodeToString(AmniShieldQuestConfig.serializer(), original)
        val deserialized = json.decodeFromString(AmniShieldQuestConfig.serializer(), serialized)

        assertEquals(original, deserialized)
        assertEquals(AmniShieldQuestType.FOCUS_SESSION, deserialized.questType)
        assertEquals(45, deserialized.targetMinutes)
        assertEquals(10, deserialized.maxAllowedReels)
        assertEquals(14, deserialized.targetStreakDays)
        assertEquals(90, deserialized.targetScore)
    }

    @Test
    fun allQuestsWrapper_getQuestJsonForAmniShield_returnsValidConfigJson() {
        val customConfig = AmniShieldQuestConfig(
            questType = AmniShieldQuestType.REELS_LIMIT,
            targetMinutes = 30,
            maxAllowedReels = 5,
            targetStreakDays = 7,
            targetScore = 85
        )
        val wrapper = AllQuestsWrapper(AmniShield = customConfig)
        val questJson = wrapper.getQuestJson(IntegrationId.AMNI_SHIELD)

        assertNotNull(questJson)
        val decoded = json.decodeFromString<AmniShieldQuestConfig>(questJson)
        assertEquals(AmniShieldQuestType.REELS_LIMIT, decoded.questType)
        assertEquals(5, decoded.maxAllowedReels)
    }

    @Test
    fun baseIntegrationId_amniShield_convertsToAdvancedIntegrationId() {
        val base = BaseIntegrationId.AMNI_SHIELD
        val adv = base.toAdv()

        assertEquals(IntegrationId.AMNI_SHIELD, adv)
        assertEquals("AmniShield Focus", adv.label)
        assertEquals(10, adv.rewardCoins)
        assertTrue(adv.description.contains("AmniShield"))
    }

    @Test
    fun amniShieldQuestType_allEnumValuesAvailable() {
        val types = AmniShieldQuestType.entries
        assertEquals(4, types.size)
        assertTrue(types.contains(AmniShieldQuestType.FOCUS_SESSION))
        assertTrue(types.contains(AmniShieldQuestType.REELS_LIMIT))
        assertTrue(types.contains(AmniShieldQuestType.SHIELD_STREAK))
        assertTrue(types.contains(AmniShieldQuestType.SHIELD_SCORE))
    }

    @Test
    fun guardianContract_constantsMatchAmniShieldSpecification() {
        assertEquals("com.alhaq.deenshield.guardian.provider", com.alhaq.amniquest.core.contract.GuardianContract.AUTHORITY)
        assertEquals("start_focus", com.alhaq.amniquest.core.contract.GuardianContract.METHOD_START_FOCUS)
        assertEquals("stop_focus", com.alhaq.amniquest.core.contract.GuardianContract.METHOD_STOP_FOCUS)
        assertEquals("get_snapshot", com.alhaq.amniquest.core.contract.GuardianContract.METHOD_GET_SNAPSHOT)
        assertEquals("minutes", com.alhaq.amniquest.core.contract.GuardianContract.ARG_MINUTES)
        assertEquals("com.alhaq.amnishield.ACTION_FOCUS_COMPLETED", com.alhaq.amniquest.core.contract.GuardianContract.ACTION_FOCUS_COMPLETED)
        assertEquals("com.alhaq.amnishield.ACTION_FOCUS_ABORTED", com.alhaq.amniquest.core.contract.GuardianContract.ACTION_FOCUS_ABORTED)
        assertEquals("duration_minutes", com.alhaq.amniquest.core.contract.GuardianContract.EXTRA_DURATION_MINUTES)
        assertEquals("com.alhaq.deenshield.permission.GUARDIAN_IPC", com.alhaq.amniquest.core.contract.GuardianContract.PERMISSION_GUARDIAN_IPC)
    }
}
