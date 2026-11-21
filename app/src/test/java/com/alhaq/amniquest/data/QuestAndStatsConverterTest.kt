package com.alhaq.amniquest.data

import com.alhaq.amniquest.core.utils.managers.QuestHelper
import com.alhaq.amniquest.data.quest.ai.snap.AiSnap
import com.alhaq.amniquest.data.quest.focus.DeepFocus
import kotlinx.datetime.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QuestAndStatsConverterTest {

    @Test
    fun baseQuestConverter_dayOfWeekSetRoundTrip_preservesSet() {
        val days = setOf(DayOfWeek.MON, DayOfWeek.THU, DayOfWeek.SUN)
        val serialized = BaseQuestConverter.fromDayOfWeekSet(days)
        val deserialized = BaseQuestConverter.toDayOfWeekSet(serialized)
        assertEquals(days, deserialized)

        val empty = emptySet<DayOfWeek>()
        assertEquals(empty, BaseQuestConverter.toDayOfWeekSet(BaseQuestConverter.fromDayOfWeekSet(empty)))
    }

    @Test
    fun baseQuestConverter_timeRangeRoundTrip_preservesBounds() {
        val range = listOf(6, 22)
        val serialized = BaseQuestConverter.fromTimeRange(range)
        val deserialized = BaseQuestConverter.toTimeRange(serialized)
        assertEquals(range, deserialized)
    }

    @Test
    fun baseQuestConverter_integrationIdRoundTrip_preservesAllIds() {
        for (id in BaseIntegrationId.entries) {
            val str = BaseQuestConverter.fromIntegrationId(id)
            assertEquals(id, BaseQuestConverter.toIntegrationId(str))
            assertEquals(id.name, id.toAdv().name)
        }
    }

    @Test
    fun statsConverter_localDateRoundTrip_preservesExactDate() {
        val date = LocalDate(2026, 10, 18)
        val str = StatsConverter.fromDate(date)
        assertEquals("2026-10-18", str)
        assertEquals(date, StatsConverter.toDate(str))
    }

    @Test
    fun questHelper_isNeedAutoDestruction_evaluatesPastTodayAndFutureDates() {
        val futureQuest = CommonQuestInfo(
            id = "q-future",
            user_id = "u-1",
            auto_destruct = "9999-12-31"
        )
        assertFalse(QuestHelper.isNeedAutoDestruction(futureQuest))

        val pastQuest = CommonQuestInfo(
            id = "q-past",
            user_id = "u-1",
            auto_destruct = "2020-01-01"
        )
        assertTrue(QuestHelper.isNeedAutoDestruction(pastQuest))
    }

    @Test
    fun templateVariableAndAllQuestsWrapper_generateExpectedDefaultsAndJson() {
        val timeRangeVar = convertToTemplate(VariableName.time_range)
        assertEquals("[0,24]", timeRangeVar.getDefaultValue())

        val fallbackTextVar = TemplateVariable(
            name = "custom_text",
            type = VariableType.text,
            label = "Enter Goal",
            default = null
        )
        assertEquals("Enter Goal", fallbackTextVar.getDefaultValue())

        val wrapper = AllQuestsWrapper(
            AiSnap = AiSnap(taskDescription = "Make bed", features = mutableListOf("pillow", "blanket")),
            DeepFocus = DeepFocus(unrestrictedApps = setOf("com.android.calculator2"))
        )

        assertEquals("{}", wrapper.getQuestJson(IntegrationId.SWIFT_MARK))

        val snapJson = wrapper.getQuestJson(IntegrationId.AI_SNAP)
        val decodedSnap = json.decodeFromString<AiSnap>(snapJson)
        assertEquals("Make bed", decodedSnap.taskDescription)

        val focusJson = wrapper.getQuestJson(IntegrationId.DEEP_FOCUS)
        val decodedFocus = json.decodeFromString<DeepFocus>(focusJson)
        assertEquals(setOf("com.android.calculator2"), decodedFocus.unrestrictedApps)
    }

    @Test
    fun variableNameSetters_applyFocusAndHealthGoalsWithBoundsChecking() {
        val wrapper = AllQuestsWrapper()
        val values = mapOf(
            "initialFocusGoal" to "45",
            "incrementFocusGoalBy" to "15",
            "finalFocusGoal" to "2",
            "unrestrictedApps" to "[\"com.example.docs\"]",
            "initialHealthGoal" to "5000",
            "incrementHealthGoalBy" to "500",
            "finalHealthGoal" to "3000" // Smaller than initial -> should clamp to initial (5000)
        )

        VariableName.initialFocusGoal.setter(wrapper, values, "initialFocusGoal")
        VariableName.incrementFocusGoalBy.setter(wrapper, values, "incrementFocusGoalBy")
        VariableName.finalFocusGoal.setter(wrapper, values, "finalFocusGoal")
        VariableName.unrestrictedApps.setter(wrapper, values, "unrestrictedApps")

        assertEquals("45", wrapper.DeepFocus.focusTimeConfig.initialTime)
        assertEquals(45L * 60 * 1000, wrapper.DeepFocus.nextFocusDurationInMillis)
        assertEquals("15", wrapper.DeepFocus.focusTimeConfig.incrementTime)
        assertEquals("45", wrapper.DeepFocus.focusTimeConfig.finalTime) // Clamped to initial (45) since 2 < 45
        assertEquals(setOf("com.example.docs"), wrapper.DeepFocus.unrestrictedApps)

        VariableName.initialHealthGoal.setter(wrapper, values, "initialHealthGoal")
        VariableName.incrementHealthGoalBy.setter(wrapper, values, "incrementHealthGoalBy")
        VariableName.finalHealthGoal.setter(wrapper, values, "finalHealthGoal")

        assertEquals(5000, wrapper.HealthConnect.healthGoalConfig.initial)
        assertEquals(5000, wrapper.HealthConnect.nextGoal)
        assertEquals(500, wrapper.HealthConnect.healthGoalConfig.increment)
        assertEquals(5000, wrapper.HealthConnect.healthGoalConfig.final)
    }

    @Test
    fun variableNameAiSnapFeatureSetters_replaceIndependentIndicesSafely() {
        val wrapper = AllQuestsWrapper(
            AiSnap = AiSnap(
                taskDescription = "Clean desk",
                features = mutableListOf("a feature1 surface", "a feature2 lamp")
            )
        )
        val values = mapOf("feature1" to "wooden", "feature2" to "bright")

        VariableName.feature1.setter(wrapper, values, "feature1")
        VariableName.feature2.setter(wrapper, values, "feature2")

        assertEquals("a wooden surface", wrapper.AiSnap.features[0])
        assertEquals("a bright lamp", wrapper.AiSnap.features[1])

        // Empty features list should not crash when setters are invoked
        val emptyWrapper = AllQuestsWrapper(AiSnap = AiSnap(features = mutableListOf()))
        VariableName.feature1.setter(emptyWrapper, values, "feature1")
        VariableName.feature2.setter(emptyWrapper, values, "feature2")
        assertTrue(emptyWrapper.AiSnap.features.isEmpty())
    }

    @Test
    fun templateVariableAndAllQuestsWrapper_coverAllVariableTypesAndIntegrations() {
        assertEquals(
            json.encodeToString(DayOfWeek.entries.toSet()),
            TemplateVariable("d", VariableType.daysOfWeek, "Days").getDefaultValue()
        )
        assertEquals("9999-06-21", TemplateVariable("e", VariableType.date, "End").getDefaultValue())
        assertEquals("[0,24]", TemplateVariable("t", VariableType.timeRange, "Time").getDefaultValue())
        assertEquals("Hint", TemplateVariable("s", VariableType.text, "Hint").getDefaultValue())
        assertEquals("0", TemplateVariable("n", VariableType.number, "Count").getDefaultValue())
        assertEquals("[]", TemplateVariable("a", VariableType.appSelector, "Apps").getDefaultValue())

        val wrapper = AllQuestsWrapper()
        for (integration in IntegrationId.entries) {
            val encoded = wrapper.getQuestJson(integration)
            assertTrue("Expected JSON object for ${integration.name}", encoded.trim().startsWith("{"))
        }
    }

    @Test
    fun commonQuestInfo_defaultUserId_isEmptyStringNotLiteralNull() {
        val quest = CommonQuestInfo()
        assertEquals("", quest.user_id)
        assertFalse(quest.user_id == "null")
    }

    @Test
    fun commonQuestInfo_withExplicitUserId_serializesAndDeserializesCorrectly() {
        val quest = CommonQuestInfo(
            id = "test-quest-id",
            user_id = "user-uuid-12345",
            title = "Morning Run",
            reward = 10
        )
        val encoded = json.encodeToString(quest)
        assertTrue(encoded.contains("\"user_id\":\"user-uuid-12345\""))
        assertTrue(encoded.contains("\"title\":\"Morning Run\""))
        val decoded = json.decodeFromString<CommonQuestInfo>(encoded)
        assertEquals("test-quest-id", decoded.id)
        assertEquals("user-uuid-12345", decoded.user_id)
        assertEquals("Morning Run", decoded.title)
        assertEquals(10, decoded.reward)
    }

    @Test
    fun statsInfo_serializesAndDeserializesWithUserIdAndDate() {
        val stat = StatsInfo(
            id = "stat-123",
            quest_id = "quest-456",
            user_id = "user-uuid-789",
            date = LocalDate(2026, 10, 20)
        )
        val encoded = json.encodeToString(stat)
        assertTrue(encoded.contains("\"user_id\":\"user-uuid-789\""))
        assertTrue(encoded.contains("\"date\":\"2026-10-20\""))
        val decoded = json.decodeFromString<StatsInfo>(encoded)
        assertEquals("stat-123", decoded.id)
        assertEquals("quest-456", decoded.quest_id)
        assertEquals("user-uuid-789", decoded.user_id)
        assertEquals(LocalDate(2026, 10, 20), decoded.date)
    }
}

