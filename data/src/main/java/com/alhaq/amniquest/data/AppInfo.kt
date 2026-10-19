package com.alhaq.amniquest.data

import kotlinx.serialization.Serializable

/**
 * Represents information about an app.
 *
 * @property name The name of the app.
 * @property packageName The package name of the app.
 * @property installTime The timestamp when the app was first installed.
 * @property updateTime The timestamp when the app was last updated.
 * @property category The ApplicationInfo category ID of the app.
 */
@Serializable
data class AppInfo(
    val name: String,
    val packageName: String,
    val installTime: Long = 0L,
    val updateTime: Long = 0L,
    val category: Int = -1
)