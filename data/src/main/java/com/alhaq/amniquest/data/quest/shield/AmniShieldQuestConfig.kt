package com.alhaq.amniquest.data.quest.shield

import kotlinx.serialization.Serializable

@Serializable
enum class AmniShieldQuestType {
    FOCUS_SESSION,
    REELS_LIMIT,
    SHIELD_STREAK,
    SHIELD_SCORE
}

@Serializable
data class AmniShieldQuestConfig(
    val questType: AmniShieldQuestType = AmniShieldQuestType.FOCUS_SESSION,
    val targetMinutes: Int = 25,
    val maxAllowedReels: Int = 0,
    val targetStreakDays: Int = 7,
    val targetScore: Int = 80
)
