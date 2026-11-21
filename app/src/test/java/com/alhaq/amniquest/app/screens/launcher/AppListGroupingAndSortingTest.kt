package com.alhaq.amniquest.app.screens.launcher

import com.alhaq.amniquest.data.AppInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppListGroupingAndSortingTest {

    private val sampleApps = listOf(
        AppInfo(name = "Zeta", packageName = "com.zeta.app", installTime = 1000L, category = 0), // Games
        AppInfo(name = "Beta", packageName = "com.beta.app", installTime = 5000L, category = 7), // Productivity
        AppInfo(name = "Alpha", packageName = "com.alpha.app", installTime = 2000L, category = 4), // Social
        AppInfo(name = "123 Calculator", packageName = "com.calc.app", installTime = 3000L, category = -1), // Other
        AppInfo(name = "Gamma", packageName = "com.gamma.app", installTime = 4000L, category = 1) // Audio
    )

    @Test
    fun buildGroupedApps_defaultAlphabeticalSort_ordersCorrectly() {
        val result = buildGroupedApps(
            apps = sampleApps,
            searchQuery = "",
            distractions = emptySet(),
            screentimeMap = emptyMap(),
            sortOrder = "Alphabetical",
            groupBy = "None"
        )

        assertEquals(1, result.size)
        assertEquals("", result[0].title)
        val names = result[0].apps.map { it.name }
        assertEquals(listOf("123 Calculator", "Alpha", "Beta", "Gamma", "Zeta"), names)
    }

    @Test
    fun buildGroupedApps_reverseAlphabeticalSort_ordersDescending() {
        val result = buildGroupedApps(
            apps = sampleApps,
            searchQuery = "",
            distractions = emptySet(),
            screentimeMap = emptyMap(),
            sortOrder = "ReverseAlphabetical",
            groupBy = "None"
        )

        val names = result[0].apps.map { it.name }
        assertEquals(listOf("Zeta", "Gamma", "Beta", "Alpha", "123 Calculator"), names)
    }

    @Test
    fun buildGroupedApps_mostUsedSort_ordersByScreentimeDescending() {
        val screentimes = mapOf(
            "com.alpha.app" to 3000L,
            "com.zeta.app" to 50000L,
            "com.beta.app" to 10000L
        )

        val result = buildGroupedApps(
            apps = sampleApps,
            searchQuery = "",
            distractions = emptySet(),
            screentimeMap = screentimes,
            sortOrder = "MostUsed",
            groupBy = "None"
        )

        val names = result[0].apps.map { it.name }
        // Zeta (50000) > Beta (10000) > Alpha (3000) > others (0)
        assertEquals("Zeta", names[0])
        assertEquals("Beta", names[1])
        assertEquals("Alpha", names[2])
    }

    @Test
    fun buildGroupedApps_installDateSort_ordersNewestFirst() {
        val result = buildGroupedApps(
            apps = sampleApps,
            searchQuery = "",
            distractions = emptySet(),
            screentimeMap = emptyMap(),
            sortOrder = "InstallDate",
            groupBy = "None"
        )

        val names = result[0].apps.map { it.name }
        // 5000L (Beta) > 4000L (Gamma) > 3000L (123 Calc) > 2000L (Alpha) > 1000L (Zeta)
        assertEquals(listOf("Beta", "Gamma", "123 Calculator", "Alpha", "Zeta"), names)
    }

    @Test
    fun buildGroupedApps_alphabetGrouping_groupsLettersWithNumbersAtEnd() {
        val result = buildGroupedApps(
            apps = sampleApps,
            searchQuery = "",
            distractions = emptySet(),
            screentimeMap = emptyMap(),
            sortOrder = "Alphabetical",
            groupBy = "Alphabet"
        )

        val titles = result.map { it.title }
        assertEquals(listOf("A", "B", "G", "Z", "#"), titles)
        assertEquals(listOf("Alpha"), result[0].apps.map { it.name })
        assertEquals(listOf("Beta"), result[1].apps.map { it.name })
        assertEquals(listOf("Gamma"), result[2].apps.map { it.name })
        assertEquals(listOf("Zeta"), result[3].apps.map { it.name })
        assertEquals(listOf("123 Calculator"), result[4].apps.map { it.name })
    }

    @Test
    fun buildGroupedApps_categoryGrouping_groupsByCategoryWithOtherAtEnd() {
        val result = buildGroupedApps(
            apps = sampleApps,
            searchQuery = "",
            distractions = emptySet(),
            screentimeMap = emptyMap(),
            sortOrder = "Alphabetical",
            groupBy = "Category"
        )

        val titles = result.map { it.title }
        // Categories: Audio & Music, Games, Productivity, Social & Communication, Other / System
        assertEquals(listOf("Audio & Music", "Games", "Productivity", "Social & Communication", "Other / System"), titles)
    }

    @Test
    fun buildGroupedApps_installDateGrouping_bucketsCorrectly() {
        val now = 100_000_000L
        val apps = listOf(
            AppInfo(name = "Today App", packageName = "p1", installTime = now - 1000L),
            AppInfo(name = "Week App", packageName = "p2", installTime = now - (2 * 86_400_000L)),
            AppInfo(name = "Month App", packageName = "p3", installTime = now - (15 * 86_400_000L)),
            AppInfo(name = "Old App", packageName = "p4", installTime = now - (50 * 86_400_000L))
        )

        val result = buildGroupedApps(
            apps = apps,
            searchQuery = "",
            distractions = emptySet(),
            screentimeMap = emptyMap(),
            sortOrder = "Alphabetical",
            groupBy = "InstallDate",
            currentTimeMs = now
        )

        val titles = result.map { it.title }
        assertEquals(listOf("Today", "This Week", "This Month", "Older"), titles)
    }

    @Test
    fun buildGroupedApps_statusGrouping_partitionsDistractionsAndAllowed() {
        val distractions = setOf("com.zeta.app", "com.alpha.app")
        val result = buildGroupedApps(
            apps = sampleApps,
            searchQuery = "",
            distractions = distractions,
            screentimeMap = emptyMap(),
            sortOrder = "Alphabetical",
            groupBy = "Status"
        )

        assertEquals(2, result.size)
        assertEquals("Distractions", result[0].title)
        assertEquals(listOf("Alpha", "Zeta"), result[0].apps.map { it.name })

        assertEquals("Allowed Apps", result[1].title)
        assertEquals(listOf("123 Calculator", "Beta", "Gamma"), result[1].apps.map { it.name })
    }

    @Test
    fun buildGroupedApps_searchQueryFiltering_filtersBeforeGrouping() {
        val result = buildGroupedApps(
            apps = sampleApps,
            searchQuery = "al",
            distractions = emptySet(),
            screentimeMap = emptyMap(),
            sortOrder = "Alphabetical",
            groupBy = "Alphabet"
        )

        // Matches: "123 Calculator" (contains "al"), "Alpha" (contains "al")
        val names = result.flatMap { it.apps }.map { it.name }
        assertTrue(names.contains("Alpha"))
        assertTrue(names.contains("123 Calculator"))
        assertEquals(2, names.size)
    }

    @Test
    fun buildGroupedApps_emptyList_returnsSingleEmptyGroup() {
        val result = buildGroupedApps(
            apps = emptyList(),
            searchQuery = "",
            distractions = emptySet(),
            screentimeMap = emptyMap(),
            sortOrder = "Alphabetical",
            groupBy = "None"
        )

        assertEquals(1, result.size)
        assertEquals("", result[0].title)
        assertTrue(result[0].apps.isEmpty())
    }

    @Test
    fun buildGroupedApps_alphabetGrouping_handlesLeadingWhitespace() {
        val appsWithSpaces = listOf(
            AppInfo(name = "  Twitter", packageName = "com.twitter.app"),
            AppInfo(name = "\tFirefox", packageName = "org.mozilla.firefox"),
            AppInfo(name = "1Password", packageName = "com.onepassword")
        )

        val result = buildGroupedApps(
            apps = appsWithSpaces,
            searchQuery = "",
            distractions = emptySet(),
            screentimeMap = emptyMap(),
            sortOrder = "Alphabetical",
            groupBy = "Alphabet"
        )

        val titles = result.map { it.title }
        assertEquals(listOf("F", "T", "#"), titles)
        assertEquals("Firefox", result[0].apps[0].name.trim())
        assertEquals("Twitter", result[1].apps[0].name.trim())
        assertEquals("1Password", result[2].apps[0].name)
    }

    @Test
    fun buildGroupedApps_mostUsedSort_tiesBreakAlphabetically() {
        val screentimes = mapOf(
            "com.zeta.app" to 5000L,
            "com.alpha.app" to 5000L,
            "com.beta.app" to 1000L
        )

        val result = buildGroupedApps(
            apps = sampleApps,
            searchQuery = "",
            distractions = emptySet(),
            screentimeMap = screentimes,
            sortOrder = "MostUsed",
            groupBy = "None"
        )

        val names = result[0].apps.map { it.name }
        // Alpha and Zeta both have 5000L -> Alpha before Zeta alphabetically!
        assertEquals("Alpha", names[0])
        assertEquals("Zeta", names[1])
        assertEquals("Beta", names[2])
    }

    @Test
    fun buildGroupedApps_installDateSort_tiesBreakAlphabetically() {
        val tiedApps = listOf(
            AppInfo(name = "Zeta", packageName = "com.zeta", installTime = 1000L),
            AppInfo(name = "Alpha", packageName = "com.alpha", installTime = 1000L)
        )

        val result = buildGroupedApps(
            apps = tiedApps,
            searchQuery = "",
            distractions = emptySet(),
            screentimeMap = emptyMap(),
            sortOrder = "InstallDate",
            groupBy = "None"
        )

        val names = result[0].apps.map { it.name }
        assertEquals(listOf("Alpha", "Zeta"), names)
    }

    @Test
    fun buildGroupedApps_duplicatePackages_deduplicatesSafely() {
        val duplicateApps = listOf(
            AppInfo(name = "Alpha Copy 1", packageName = "com.alpha.dup"),
            AppInfo(name = "Alpha Copy 2", packageName = "com.alpha.dup"),
            AppInfo(name = "Beta", packageName = "com.beta")
        )

        val result = buildGroupedApps(
            apps = duplicateApps,
            searchQuery = "",
            distractions = emptySet(),
            screentimeMap = emptyMap(),
            sortOrder = "Alphabetical",
            groupBy = "None"
        )

        assertEquals(2, result[0].apps.size)
        assertEquals(listOf("com.alpha.dup", "com.beta"), result[0].apps.map { it.packageName })
    }
}
