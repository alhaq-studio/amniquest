package com.alhaq.amniquest.core.utils

import android.content.Context
import android.util.Log
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.BadPaddingException
import javax.crypto.Cipher
import javax.crypto.IllegalBlockSizeException
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import com.alhaq.amniquest.backed.repositories.AppWidgetConfig
import com.alhaq.amniquest.backed.repositories.AppWidgetConfigDao
import com.alhaq.amniquest.backed.repositories.QuestRepository
import com.alhaq.amniquest.backed.repositories.StatsRepository
import com.alhaq.amniquest.data.CommonQuestInfo
import com.alhaq.amniquest.data.StatsInfo
import com.alhaq.amniquest.data.UserInfo

@Serializable
data class AppWidgetConfigBackup(
    val id: String,
    val widgetId: Int,
    val height: Int,
    val width: Int? = null,
    val borderless: Boolean = false,
    val background: Boolean = true,
    val themeColors: Boolean = true,
    val order: Int
)

@Serializable
data class BackupData(
    val version: Int = 1,
    val userInfo: UserInfo? = null,
    val quests: List<CommonQuestInfo> = emptyList(),
    val stats: List<StatsInfo> = emptyList(),
    val widgets: List<AppWidgetConfigBackup> = emptyList()
)

sealed class RestoreResult {
    data object Success : RestoreResult()
    data class Failure(val reason: String) : RestoreResult()
}

object BackupManager {
    private const val TAG = "BackupManager"
    private const val CURRENT_BACKUP_VERSION = 1
    private val EXPECTED_BACKUP_KEYS = setOf("version", "userInfo", "quests", "stats", "widgets")

    internal val jsonSerializer = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
        isLenient = true
        explicitNulls = false
    }

    internal fun normalizeBackupInput(raw: String): String =
        raw.trimStart { it == '\uFEFF' || it.isWhitespace() }.trimEnd()

    internal fun isEncryptedBackupPayload(normalized: String): Boolean =
        !normalized.startsWith("{")

    internal fun parseAndValidateBackupData(backupJson: String): Result<BackupData> {
        val normalized = normalizeBackupInput(backupJson)
        if (normalized.isEmpty()) {
            return Result.failure(IllegalArgumentException("Backup file is empty"))
        }
        return try {
            val element = jsonSerializer.parseToJsonElement(normalized)
            val jsonObject = element as? JsonObject
                ?: return Result.failure(IllegalArgumentException("Unsupported or invalid backup format"))
            if (jsonObject.keys.none { it in EXPECTED_BACKUP_KEYS }) {
                return Result.failure(IllegalArgumentException("Unsupported or invalid backup format"))
            }
            val decoded = jsonSerializer.decodeFromString<BackupData>(normalized)
            if (decoded.version !in 1..CURRENT_BACKUP_VERSION) {
                return Result.failure(IllegalArgumentException("Unsupported backup version: ${decoded.version}"))
            }
            Result.success(decoded)
        } catch (e: SerializationException) {
            Result.failure(IllegalArgumentException("Invalid backup JSON format", e))
        } catch (e: IllegalArgumentException) {
            Result.failure(e)
        }
    }

    internal fun encrypt(plainText: String, password: CharArray): String {
        val salt = ByteArray(16).apply { SecureRandom().nextBytes(this) }
        val iv = ByteArray(16).apply { SecureRandom().nextBytes(this) }

        val spec = PBEKeySpec(password, salt, 10000, 256)
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val secretKey = factory.generateSecret(spec)
        val secretKeySpec = SecretKeySpec(secretKey.encoded, "AES")

        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKeySpec, IvParameterSpec(iv))
        val cipherText = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))

        // Format: salt (16 bytes) + iv (16 bytes) + ciphertext
        val combined = ByteArray(salt.size + iv.size + cipherText.size)
        System.arraycopy(salt, 0, combined, 0, salt.size)
        System.arraycopy(iv, 0, combined, salt.size, iv.size)
        System.arraycopy(cipherText, 0, combined, salt.size + iv.size, cipherText.size)

        return Base64.getEncoder().encodeToString(combined)
    }

    internal fun decrypt(combinedBase64: String, password: CharArray): String {
        val compact = combinedBase64.replace(Regex("\\s+"), "")
        val combined = Base64.getDecoder().decode(compact)
        require(combined.size >= 48 && (combined.size - 32) % 16 == 0) {
            "Invalid encrypted backup payload"
        }
        val salt = ByteArray(16)
        val iv = ByteArray(16)
        val cipherText = ByteArray(combined.size - salt.size - iv.size)

        System.arraycopy(combined, 0, salt, 0, salt.size)
        System.arraycopy(combined, salt.size, iv, 0, iv.size)
        System.arraycopy(combined, salt.size + iv.size, cipherText, 0, cipherText.size)

        val spec = PBEKeySpec(password, salt, 10000, 256)
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val secretKey = factory.generateSecret(spec)
        val secretKeySpec = SecretKeySpec(secretKey.encoded, "AES")

        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, secretKeySpec, IvParameterSpec(iv))
        val decryptedBytes = cipher.doFinal(cipherText)

        return String(decryptedBytes, Charsets.UTF_8)
    }

    suspend fun createBackup(
        context: Context,
        userRepository: com.alhaq.amniquest.backed.repositories.UserRepository,
        questRepository: QuestRepository,
        statsRepository: StatsRepository,
        widgetConfigDao: AppWidgetConfigDao,
        password: String? = null
    ): String {
        val userInfo = userRepository.userInfo
        val quests = questRepository.getAllQuests().first()
        val stats = statsRepository.getAllStatsForUser().first()
        val widgets = widgetConfigDao.getAllConfigs().map {
            AppWidgetConfigBackup(
                id = it.id,
                widgetId = it.widgetId,
                height = it.height,
                width = it.width,
                borderless = it.borderless,
                background = it.background,
                themeColors = it.themeColors,
                order = it.order
            )
        }

        val backupData = BackupData(
            userInfo = userInfo,
            quests = quests,
            stats = stats,
            widgets = widgets
        )

        val plainJson = jsonSerializer.encodeToString(backupData)
        return if (!password.isNullOrEmpty()) {
            encrypt(plainJson, password.toCharArray())
        } else {
            plainJson
        }
    }

    suspend fun restoreBackup(
        context: Context,
        backupJsonOrCiphertext: String,
        userRepository: com.alhaq.amniquest.backed.repositories.UserRepository,
        questRepository: QuestRepository,
        statsRepository: StatsRepository,
        widgetConfigDao: AppWidgetConfigDao,
        password: String? = null
    ): Boolean {
        return restoreBackupDetailed(
            context = context,
            backupJsonOrCiphertext = backupJsonOrCiphertext,
            userRepository = userRepository,
            questRepository = questRepository,
            statsRepository = statsRepository,
            widgetConfigDao = widgetConfigDao,
            password = password
        ) is RestoreResult.Success
    }

    suspend fun restoreBackupDetailed(
        context: Context,
        backupJsonOrCiphertext: String,
        userRepository: com.alhaq.amniquest.backed.repositories.UserRepository,
        questRepository: QuestRepository,
        statsRepository: StatsRepository,
        widgetConfigDao: AppWidgetConfigDao,
        password: String? = null
    ): RestoreResult {
        val normalized = normalizeBackupInput(backupJsonOrCiphertext)
        if (normalized.isEmpty()) {
            return RestoreResult.Failure("Backup file is empty")
        }

        val backupJson = if (isEncryptedBackupPayload(normalized)) {
            if (password.isNullOrEmpty()) {
                Log.e(TAG, "Backup is encrypted but no password was provided.")
                return RestoreResult.Failure("Backup is encrypted; password is required")
            }
            try {
                normalizeBackupInput(decrypt(normalized, password.toCharArray()))
            } catch (e: BadPaddingException) {
                Log.e(TAG, "Failed to decrypt backup: wrong password or corrupted payload", e)
                return RestoreResult.Failure("Wrong password or corrupted encrypted backup")
            } catch (e: IllegalBlockSizeException) {
                Log.e(TAG, "Invalid encrypted backup block size", e)
                return RestoreResult.Failure("Invalid encrypted backup format")
            } catch (e: IllegalArgumentException) {
                Log.e(TAG, "Invalid encrypted backup payload", e)
                return RestoreResult.Failure("Invalid encrypted backup format")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to decrypt backup", e)
                return RestoreResult.Failure("Failed to decrypt backup: ${e.message ?: "unknown error"}")
            }
        } else {
            normalized
        }

        val parsedResult = parseAndValidateBackupData(backupJson)
        val backupData = parsedResult.getOrElse { error ->
            Log.e(TAG, "Backup validation/parsing failed: ${error.message}", error)
            return RestoreResult.Failure(error.message ?: "Unsupported or invalid backup format")
        }

        return try {
            // 1. Clear current databases
            questRepository.clearAll()
            statsRepository.deleteAll()
            widgetConfigDao.deleteAllConfigs()

            // 2. Restore User Info
            backupData.userInfo?.let {
                val wasAnonymous = userRepository.userInfo.isAnonymous
                it.isAnonymous = wasAnonymous
                userRepository.userInfo = it
                userRepository.coinsState.value = it.coins
                userRepository.currentStreakState.value = it.streak.currentStreak
                userRepository.activeBoostsState.value = it.active_boosts
                userRepository.saveUserInfo(isSetLastUpdated = false)
            }

            // 3. Restore Quests
            questRepository.upsertAll(backupData.quests)

            // 4. Restore Stats
            backupData.stats.forEach { stat ->
                statsRepository.upsertStats(stat)
            }

            // 5. Restore Widgets
            backupData.widgets.forEach { widget ->
                widgetConfigDao.insertConfig(
                    AppWidgetConfig(
                        id = widget.id,
                        widgetId = widget.widgetId,
                        height = widget.height,
                        width = widget.width,
                        borderless = widget.borderless,
                        background = widget.background,
                        themeColors = widget.themeColors,
                        order = widget.order
                    )
                )
            }

            RestoreResult.Success
        } catch (e: Exception) {
            Log.e(TAG, "Database error while restoring backup", e)
            RestoreResult.Failure("Database error while restoring backup: ${e.message ?: "unknown error"}")
        }
    }
}
