package com.alhaq.amniquest.core.utils

import com.alhaq.amniquest.data.BaseIntegrationId
import com.alhaq.amniquest.data.CommonQuestInfo
import com.alhaq.amniquest.data.DayOfWeek
import com.alhaq.amniquest.data.StatsInfo
import com.alhaq.amniquest.data.UserInfo
import kotlinx.datetime.LocalDate
import kotlinx.serialization.encodeToString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import javax.crypto.BadPaddingException

class BackupCryptoAndSerializationTest {

    @Test
    fun encryptAndDecrypt_roundTripWithPassword_restoresExactJson() {
        val sampleJson = """{"version":1,"quests":[{"title":"Morning Study"}]}"""
        val password = "StrongBackupPassword!2026".toCharArray()

        val cipherText1 = BackupManager.encrypt(sampleJson, password)
        val cipherText2 = BackupManager.encrypt(sampleJson, password)

        // Random salt + IV ensures semantic security (distinct ciphertexts for identical plaintexts)
        assertNotEquals(cipherText1, cipherText2)
        assertTrue(!cipherText1.trim().startsWith("{"))

        val decrypted1 = BackupManager.decrypt(cipherText1, password)
        val decrypted2 = BackupManager.decrypt(cipherText2, password)

        assertEquals(sampleJson, decrypted1)
        assertEquals(sampleJson, decrypted2)
    }

    @Test(expected = BadPaddingException::class)
    fun decrypt_wrongPassword_throwsBadPaddingException() {
        val sampleJson = """{"version":1,"quests":[]}"""
        val cipherText = BackupManager.encrypt(sampleJson, "correct-password".toCharArray())
        BackupManager.decrypt(cipherText, "wrong-password".toCharArray())
    }

    @Test(expected = IllegalArgumentException::class)
    fun decrypt_truncatedPayloadShorterThanSaltAndIv_throwsIllegalArgumentException() {
        // Less than 33 bytes decoded
        BackupManager.decrypt("c2hvcnQ=", "any-password".toCharArray())
    }

    @Test
    fun backupData_fullSerializationAndEncryptedRoundTrip_preservesAllEntities() {
        val user = UserInfo(
            username = "amniquest_user",
            full_name = "Amni Quest",
            coins = 250,
            level = 4,
            xp = 900
        )
        val quest = CommonQuestInfo(
            id = "quest-001",
            user_id = "user-001",
            title = "Deep Focus Coding",
            reward = 15,
            integration_id = BaseIntegrationId.DEEP_FOCUS,
            selected_days = setOf(DayOfWeek.MON, DayOfWeek.WED, DayOfWeek.FRI),
            time_range = listOf(8, 18),
            isHardLock = true
        )
        val stat = StatsInfo(
            id = "stat-001",
            quest_id = "quest-001",
            user_id = "user-001",
            date = LocalDate(2026, 10, 17)
        )
        val widget = AppWidgetConfigBackup(
            id = "widget-001",
            widgetId = 42,
            height = 200,
            width = 300,
            borderless = true,
            background = false,
            themeColors = true,
            order = 0
        )

        val backup = BackupData(
            version = 1,
            userInfo = user,
            quests = listOf(quest),
            stats = listOf(stat),
            widgets = listOf(widget)
        )

        val rawJson = BackupManager.jsonSerializer.encodeToString(backup)
        val encrypted = BackupManager.encrypt(rawJson, "vault-key-99".toCharArray())
        val decryptedJson = BackupManager.decrypt(encrypted, "vault-key-99".toCharArray())
        val restored = BackupManager.jsonSerializer.decodeFromString<BackupData>(decryptedJson)

        assertEquals(1, restored.version)
        assertEquals("amniquest_user", restored.userInfo?.username)
        assertEquals(250, restored.userInfo?.coins)
        assertEquals(1, restored.quests.size)
        assertEquals("Deep Focus Coding", restored.quests[0].title)
        assertEquals(setOf(DayOfWeek.MON, DayOfWeek.WED, DayOfWeek.FRI), restored.quests[0].selected_days)
        assertTrue(restored.quests[0].isHardLock)
        assertEquals(1, restored.stats.size)
        assertEquals(LocalDate(2026, 10, 17), restored.stats[0].date)
        assertEquals(1, restored.widgets.size)
        assertEquals(42, restored.widgets[0].widgetId)
        assertTrue(restored.widgets[0].borderless)
    }

    @Test
    fun bomPrefixedJson_isNormalizedAndDetectedAsUnencrypted() {
        val bomJson = "\uFEFF  {\"version\":1,\"quests\":[]}  "
        val normalized = BackupManager.normalizeBackupInput(bomJson)

        assertTrue(normalized.startsWith("{"))
        assertFalse(BackupManager.isEncryptedBackupPayload(normalized))

        val parsed = BackupManager.parseAndValidateBackupData(bomJson)
        assertTrue(parsed.isSuccess)
        assertEquals(1, parsed.getOrThrow().version)
        assertTrue(parsed.getOrThrow().quests.isEmpty())
    }

    @Test
    fun partialBackupDataJson_withMissingOptionalFields_deserializesWithDefaults() {
        val partialJson = """{"version":1}"""
        val parsed = BackupManager.parseAndValidateBackupData(partialJson)

        assertTrue(parsed.isSuccess)
        val data = parsed.getOrThrow()
        assertEquals(1, data.version)
        assertNull(data.userInfo)
        assertTrue(data.quests.isEmpty())
        assertTrue(data.stats.isEmpty())
        assertTrue(data.widgets.isEmpty())

        // Verify explicitNulls = false omits null userInfo when serializing
        val encoded = BackupManager.jsonSerializer.encodeToString(BackupData(version = 1, userInfo = null))
        assertFalse(encoded.contains("\"userInfo\": null"))
    }

    @Test
    fun emptyOrUnrelatedJson_isRejectedWithoutCrashing() {
        val emptyResult = BackupManager.parseAndValidateBackupData("  \uFEFF  ")
        assertTrue(emptyResult.isFailure)

        val unrelatedResult = BackupManager.parseAndValidateBackupData("""{"foo":"bar","count":42}""")
        assertTrue(unrelatedResult.isFailure)
        assertEquals("Unsupported or invalid backup format", unrelatedResult.exceptionOrNull()?.message)

        val arrayResult = BackupManager.parseAndValidateBackupData("""[1, 2, 3]""")
        assertTrue(arrayResult.isFailure)
        assertEquals("Unsupported or invalid backup format", arrayResult.exceptionOrNull()?.message)

        val futureVersionResult = BackupManager.parseAndValidateBackupData("""{"version":99}""")
        assertTrue(futureVersionResult.isFailure)
        assertEquals("Unsupported backup version: 99", futureVersionResult.exceptionOrNull()?.message)
    }

    @Test
    fun multiBomPrefixedAndLineWrappedEncryptedPayloads_normalizeAndDecryptCleanly() {
        val multiBomJson = "\uFEFF\uFEFF  \n  {\"version\":1,\"quests\":[]}  \r\n"
        val normalized = BackupManager.normalizeBackupInput(multiBomJson)
        assertTrue(normalized.startsWith("{"))
        assertTrue(normalized.endsWith("}"))
        assertFalse(BackupManager.isEncryptedBackupPayload(normalized))

        val parsed = BackupManager.parseAndValidateBackupData(multiBomJson)
        assertTrue(parsed.isSuccess)
        assertEquals(1, parsed.getOrThrow().version)

        val cipherText = BackupManager.encrypt("""{"version":1,"quests":[]}""", "wrap-pass".toCharArray())
        val wrapped = cipherText.chunked(24).joinToString("\r\n  ")
        assertTrue(BackupManager.isEncryptedBackupPayload(BackupManager.normalizeBackupInput(wrapped)))
        val decrypted = BackupManager.decrypt(wrapped, "wrap-pass".toCharArray())
        assertEquals("""{"version":1,"quests":[]}""", decrypted)
    }

    @Test(expected = IllegalArgumentException::class)
    fun decrypt_nonBase64HtmlPayload_throwsIllegalArgumentException() {
        BackupManager.decrypt("<html><body>Not a backup</body></html>", "any-pass".toCharArray())
    }

    @Test(expected = IllegalArgumentException::class)
    fun decrypt_unalignedCiphertextLength_throwsIllegalArgumentException() {
        // 32 bytes salt+iv + 5 bytes ciphertext (not multiple of 16 bytes AES block size)
        val unaligned = java.util.Base64.getEncoder().encodeToString(ByteArray(37) { 1 })
        BackupManager.decrypt(unaligned, "any-pass".toCharArray())
    }

    @Test
    fun parseAndValidateBackupData_handlesInvalidTypesVersionsSyntaxAndExtraKeys() {
        val primitiveResult = BackupManager.parseAndValidateBackupData("\"just a string\"")
        assertTrue(primitiveResult.isFailure)
        assertEquals("Unsupported or invalid backup format", primitiveResult.exceptionOrNull()?.message)

        val invalidJsonResult = BackupManager.parseAndValidateBackupData("{invalid-json")
        assertTrue(invalidJsonResult.isFailure)
        assertEquals("Invalid backup JSON format", invalidJsonResult.exceptionOrNull()?.message)

        val wrongTypeResult = BackupManager.parseAndValidateBackupData("""{"version":"abc"}""")
        assertTrue(wrongTypeResult.isFailure)
        assertEquals("Invalid backup JSON format", wrongTypeResult.exceptionOrNull()?.message)

        val wrongQuestsType = BackupManager.parseAndValidateBackupData("""{"version":1,"quests":"bad"}""")
        assertTrue(wrongQuestsType.isFailure)
        assertEquals("Invalid backup JSON format", wrongQuestsType.exceptionOrNull()?.message)

        val versionZero = BackupManager.parseAndValidateBackupData("""{"version":0}""")
        assertTrue(versionZero.isFailure)
        assertEquals("Unsupported backup version: 0", versionZero.exceptionOrNull()?.message)

        val negativeVersion = BackupManager.parseAndValidateBackupData("""{"version":-5}""")
        assertTrue(negativeVersion.isFailure)
        assertEquals("Unsupported backup version: -5", negativeVersion.exceptionOrNull()?.message)

        val minimalQuestsOnly = BackupManager.parseAndValidateBackupData("""{"quests":[],"futureField":"ignored"}""")
        assertTrue(minimalQuestsOnly.isSuccess)
        assertEquals(1, minimalQuestsOnly.getOrThrow().version)
    }
}

