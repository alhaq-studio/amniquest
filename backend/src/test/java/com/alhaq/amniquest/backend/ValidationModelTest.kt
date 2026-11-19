package com.alhaq.amniquest.backend

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException

class ValidationModelTest {

    @Test
    fun validationResult_equalityAndProperties_behaveAsExpected() {
        val valid = TaskValidationClient.ValidationResult(
            isValid = true,
            reason = "Desk is clean and organized."
        )
        val invalid = TaskValidationClient.ValidationResult(
            isValid = false,
            reason = "Coffee mug is still visible on the table."
        )

        assertTrue(valid.isValid)
        assertEquals("Desk is clean and organized.", valid.reason)
        assertFalse(invalid.isValid)
        assertEquals("Coffee mug is still visible on the table.", invalid.reason)
    }

    @Test
    fun buildConfig_hasNonBlankBackendConfiguration() {
        assertNotNull(BuildConfig.SUPABASE_URL)
        assertNotNull(BuildConfig.SUPABASE_API_KEY)
        assertNotNull(BuildConfig.API_URL)
        assertTrue(BuildConfig.SUPABASE_URL.isNotBlank())
    }

    @Test
    fun validationResult_copyEqualsAndHashCode_followDataClassContract() {
        val base = TaskValidationClient.ValidationResult(isValid = true, reason = "Clean desk")
        val same = base.copy()
        val modified = base.copy(isValid = false)

        assertEquals(base, same)
        assertEquals(base.hashCode(), same.hashCode())
        assertNotEquals(base, modified)
        assertTrue(base.toString().contains("Clean desk"))
    }

    @Test
    fun geminiValidator_missingImageFile_invokesCallbackWithFailureImmediately() {
        val missingFile = File("nonexistent_dir_xyz/missing_photo_${System.nanoTime()}.jpg")
        var receivedResult: Result<TaskValidationClient.ValidationResult>? = null

        GeminiValidator().validateTask(
            imageFile = missingFile,
            description = "Clean desk",
            features = "desk,laptop",
            apiKey = "dummy-key"
        ) { result ->
            receivedResult = result
        }

        assertNotNull(receivedResult)
        assertTrue(receivedResult!!.isFailure)
        val exception = receivedResult!!.exceptionOrNull()
        assertTrue(exception is IOException)
        assertTrue(exception?.message?.contains("Failed to read/encode image") == true)
    }
}

