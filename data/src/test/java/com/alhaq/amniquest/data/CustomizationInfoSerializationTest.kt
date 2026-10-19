package com.alhaq.amniquest.data

import kotlinx.serialization.encodeToString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CustomizationInfoSerializationTest {

    @Test
    fun customizationInfo_defaultValues_matchMinimalistDefaults() {
        val info = CustomizationInfo()
        assertFalse("showAppIcons must default to false for minimalism", info.showAppIcons)
        assertEquals("List", info.appListLayout)
        assertEquals("Alphabetical", info.appSortOrder)
        assertEquals("None", info.appGroupBy)
        assertEquals("Medium", info.appIconSize)
        assertFalse("autoFocusSearch must default to false", info.autoFocusSearch)
        assertTrue("confirmInstantBuyAndUse must default to true", info.confirmInstantBuyAndUse)
    }

    @Test
    fun customizationInfo_roundTripSerialization_preservesAllDrawerSettings() {
        val original = CustomizationInfo(
            showAppIcons = true,
            appListLayout = "Grid3",
            appSortOrder = "ReverseAlphabetical",
            appGroupBy = "Category",
            appIconSize = "Large",
            autoFocusSearch = true,
            confirmInstantBuyAndUse = false
        )

        val jsonStr = json.encodeToString(original)
        val decoded = json.decodeFromString<CustomizationInfo>(jsonStr)

        assertTrue(decoded.showAppIcons)
        assertEquals("Grid3", decoded.appListLayout)
        assertEquals("ReverseAlphabetical", decoded.appSortOrder)
        assertEquals("Category", decoded.appGroupBy)
        assertEquals("Large", decoded.appIconSize)
        assertTrue(decoded.autoFocusSearch)
        assertFalse(decoded.confirmInstantBuyAndUse)
    }

    @Test
    fun customizationInfo_legacyJsonWithoutNewFields_deserializesWithDefaults() {
        // Legacy JSON from older versions without drawer / buy confirm fields
        val legacyJson = """
            {
                "purchasedThemes": ["Pitch Black"],
                "equippedTheme": "Pitch Black",
                "equippedWidget": "Heat Map",
                "distractionAdderPrice": 0,
                "distractionRemoverPrice": 50
            }
        """.trimIndent()

        val decoded = json.decodeFromString<CustomizationInfo>(legacyJson)
        assertFalse(decoded.showAppIcons)
        assertEquals("List", decoded.appListLayout)
        assertEquals("Alphabetical", decoded.appSortOrder)
        assertEquals("None", decoded.appGroupBy)
        assertEquals("Medium", decoded.appIconSize)
        assertFalse(decoded.autoFocusSearch)
        assertTrue(decoded.confirmInstantBuyAndUse)
    }

    @Test
    fun appInfo_metadataSerialization_preservesTimestampsAndCategory() {
        val app = AppInfo(
            name = "AmniQuest",
            packageName = "com.alhaq.amniquest",
            installTime = 1700000000000L,
            updateTime = 1700000050000L,
            category = 7 // ApplicationInfo.CATEGORY_PRODUCTIVITY
        )

        val jsonStr = json.encodeToString(app)
        val decoded = json.decodeFromString<AppInfo>(jsonStr)

        assertEquals("AmniQuest", decoded.name)
        assertEquals("com.alhaq.amniquest", decoded.packageName)
        assertEquals(1700000000000L, decoded.installTime)
        assertEquals(1700000050000L, decoded.updateTime)
        assertEquals(7, decoded.category)
    }

    @Test
    fun appInfo_legacyJsonWithoutNewFields_deserializesWithDefaults() {
        val legacyAppJson = """{"name":"Minimal App","packageName":"com.example.app"}"""
        val decoded = json.decodeFromString<AppInfo>(legacyAppJson)

        assertEquals("Minimal App", decoded.name)
        assertEquals("com.example.app", decoded.packageName)
        assertEquals(0L, decoded.installTime)
        assertEquals(0L, decoded.updateTime)
        assertEquals(-1, decoded.category)
    }
}
