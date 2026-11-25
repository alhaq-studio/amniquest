package com.alhaq.amniquest

import ai.onnxruntime.OnnxJavaType
import ai.onnxruntime.platform.Fp16Conversions
import com.alhaq.amniquest.app.screens.quest.setup.ai_snap.model.DEFAULT_MODEL_CATALOG
import com.alhaq.amniquest.app.screens.quest.setup.ai_snap.model.DownloadableModel
import com.alhaq.amniquest.app.screens.quest.setup.ai_snap.model.formatLocalModelDisplayLabel
import com.alhaq.amniquest.app.screens.quest.setup.ai_snap.model.needsTokenizerDownload
import com.alhaq.amniquest.app.screens.quest.setup.ai_snap.model.resolveActiveLocalModelId
import com.alhaq.amniquest.app.screens.quest.setup.ai_snap.model.resolveModelCatalog
import com.alhaq.amniquest.core.utils.AiMismatchReport
import com.alhaq.amniquest.core.utils.FP16_MODEL_ID
import com.alhaq.amniquest.core.utils.MINIMUM_ZERO_SHOT_THRESHOLD
import com.alhaq.amniquest.core.utils.TIP_ALREADY_ON_FP16_MODEL
import com.alhaq.amniquest.core.utils.TIP_SWITCH_TO_FP16_MODEL
import com.alhaq.amniquest.core.utils.evaluateZeroShotFeatures
import com.alhaq.amniquest.core.utils.extractLogitsFromBuffers
import com.alhaq.amniquest.core.utils.formatAiMismatchShareText
import com.alhaq.amniquest.core.utils.formatConfidenceScoresSummary
import com.alhaq.amniquest.core.utils.isFp16ModelId
import com.alhaq.amniquest.core.utils.parseNanoValidationResponse
import com.alhaq.amniquest.core.utils.resolveLocalFailureGuidanceTip
import com.alhaq.amniquest.core.utils.sanitizeZeroShotQueries
import com.alhaq.amniquest.core.utils.shouldAttemptLocalFp16Fallback
import com.alhaq.amniquest.core.utils.sigmoidProbability
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.DoubleBuffer
import java.nio.FloatBuffer
import java.nio.file.Files
import java.util.Locale

class OnDeviceAiPipelineTest {

    @Test
    fun defaultModelCatalog_hasOnlineAndAllQuantizedSiglip2Variants() {
        assertTrue(DEFAULT_MODEL_CATALOG.size >= 6)
        assertEquals("online", DEFAULT_MODEL_CATALOG.first().id)

        val offlineModels = DEFAULT_MODEL_CATALOG.filter { it.id != "online" }
        val expectedIds = setOf(
            "SIGLIP2_FP16",
            "SIGLIP2_INT8",
            "SIGLIP2_FULL",
            "SIGLIP2_Q4F16",
            "SIGLIP2_UINT8"
        )
        assertEquals(expectedIds, offlineModels.map { it.id }.toSet())
        assertEquals("SIGLIP2_FP16", offlineModels.first().id)
        assertTrue(offlineModels.first().isRecommended)
        assertEquals("FP16 High-Precision (~715 MB)", offlineModels.first().displayName)
        assertEquals("Recommended (90%+ Accuracy)", offlineModels.first().accuracy)

        for (model in offlineModels) {
            assertNotNull("URL must not be null for ${model.id}", model.url)
            assertTrue(
                "URL for ${model.id} must use https and end with .onnx",
                model.url.startsWith("https://huggingface.co/onnx-community/siglip2-base-patch16-224-ONNX/resolve/main/onnx/") &&
                    model.url.endsWith(".onnx")
            )
            assertTrue("Version must not be blank for ${model.id}", model.version.isNotBlank())
            assertTrue("Accuracy must not be blank for ${model.id}", model.accuracy.isNotBlank())
            assertTrue("Display name must not be blank for ${model.id}", model.displayName.isNotBlank())
            assertTrue("Description must not be blank for ${model.id}", model.description.isNotBlank())
        }
    }

    @Test
    fun resolveModelCatalog_truncatedUpstreamJson_fallsBackToDefaultCatalog() {
        val truncatedRemoteJson = """
            [
              {
                "id": "online",
                "url": "",
                "sizeMB": 0,
                "accuracy": "HIGHEST",
                "version": "22/04/2025"
              },
              {
                "id": "SIGLIP2_Q4F16",
                "url": "https://huggingface.co/onnx-community/siglip2-base-patch16-224-ONNX/resolve/main/onnx/model_q4f16.onnx",
                "sizeMB": 497,
                "accuracy": "Balanced",
                "version": "22/04/2025"
              },
              {
                "id": "SIGLIP2_FP16",
                "url": "https://huggingface.co/onnx-community/siglip2-base-patch16-224-ONNX/resolve/main/onnx/model_f
        """.trimIndent()

        val catalog = resolveModelCatalog(truncatedRemoteJson)
        assertEquals(DEFAULT_MODEL_CATALOG, catalog)
    }

    @Test
    fun resolveModelCatalog_nullOrBlankOrEmptyArray_fallsBackToDefaultCatalog() {
        assertEquals(DEFAULT_MODEL_CATALOG, resolveModelCatalog(null))
        assertEquals(DEFAULT_MODEL_CATALOG, resolveModelCatalog(""))
        assertEquals(DEFAULT_MODEL_CATALOG, resolveModelCatalog("   "))
        assertEquals(DEFAULT_MODEL_CATALOG, resolveModelCatalog("[]"))
    }

    @Test
    fun resolveModelCatalog_validPartialRemoteJson_mergesWithDefaultCatalog() {
        val remoteJson = """
            [
              {
                "id": "online",
                "url": "",
                "sizeMB": 0,
                "accuracy": "HIGHEST",
                "version": "01/05/2025"
              },
              {
                "id": "CUSTOM_SIGLIP2",
                "url": "https://huggingface.co/onnx-community/siglip2-base-patch16-224-ONNX/resolve/main/onnx/model_q4.onnx",
                "sizeMB": 300,
                "accuracy": "Fast",
                "version": "01/05/2025"
              }
            ]
        """.trimIndent()

        val catalog = resolveModelCatalog(remoteJson)
        assertEquals("online", catalog.first().id)
        assertEquals("01/05/2025", catalog.first().version)
        assertTrue(catalog.any { it.id == "CUSTOM_SIGLIP2" })
        assertTrue(catalog.any { it.id == "SIGLIP2_UINT8" })
        assertTrue(catalog.any { it.id == "SIGLIP2_Q4F16" })
    }

    @Test
    fun resolveActiveLocalModelId_handlesOnlineSelectionAndDownloadingAndDiskModels() {
        assertNull(resolveActiveLocalModelId("online", null, emptySet()))
        assertNull(resolveActiveLocalModelId(null, null, emptySet()))

        assertEquals(
            "SIGLIP2_UINT8",
            resolveActiveLocalModelId("online", null, setOf("SIGLIP2_UINT8"))
        )
        assertEquals(
            "SIGLIP2_Q4F16",
            resolveActiveLocalModelId("SIGLIP2_Q4F16", null, setOf("SIGLIP2_UINT8", "SIGLIP2_Q4F16"))
        )
        assertEquals(
            "SIGLIP2_INT8",
            resolveActiveLocalModelId("online", "SIGLIP2_INT8", emptySet())
        )
        assertEquals(
            "SIGLIP2_FP16",
            resolveActiveLocalModelId("online", null, setOf("SIGLIP2_UINT8", "SIGLIP2_FP16"))
        )
    }

    @Test
    fun formatLocalModelDisplayLabel_handlesMissingDownloadingNotDownloadedAndOnDiskModels() {
        assertEquals(
            "None (Select Model)",
            formatLocalModelDisplayLabel(
                activeModelId = null,
                downloadingModelId = null,
                isOnDisk = false
            )
        )
        assertEquals(
            "SIGLIP2_FP16 (Downloading...)",
            formatLocalModelDisplayLabel(
                activeModelId = "SIGLIP2_FP16",
                downloadingModelId = "SIGLIP2_FP16",
                isOnDisk = false
            )
        )
        assertEquals(
            "SIGLIP2_FP16 (Not Downloaded)",
            formatLocalModelDisplayLabel(
                activeModelId = "SIGLIP2_FP16",
                downloadingModelId = null,
                isOnDisk = false
            )
        )
        assertEquals(
            "FP16 High-Precision (~715 MB) (SIGLIP2_FP16)",
            formatLocalModelDisplayLabel(
                activeModelId = "SIGLIP2_FP16",
                downloadingModelId = null,
                isOnDisk = true
            )
        )
    }

    @Test
    fun resolveModelCatalog_preservesFp16RecommendedLabelWhenRemoteManifestUsesLegacyAccuracy() {
        val legacyRemoteJson = """
            [
              {
                "id": "online",
                "url": "",
                "sizeMB": 0,
                "accuracy": "HIGHEST",
                "version": "1.0.1",
                "isRecommended": true,
                "isOnline": true
              },
              {
                "id": "SIGLIP2_FP16",
                "url": "https://huggingface.co/onnx-community/siglip2-base-patch16-224-ONNX/resolve/main/onnx/model_fp16.onnx",
                "sizeMB": 751,
                "accuracy": "Medium",
                "version": "1.0.2",
                "isRecommended": false
              },
              {
                "id": "SIGLIP2_UINT8",
                "url": "https://huggingface.co/onnx-community/siglip2-base-patch16-224-ONNX/resolve/main/onnx/model_uint8.onnx",
                "sizeMB": 378,
                "accuracy": "LOWEST",
                "version": "1.0.0",
                "isRecommended": false
              }
            ]
        """.trimIndent()

        val catalog = resolveModelCatalog(legacyRemoteJson)
        val fp16 = catalog.first { it.id == FP16_MODEL_ID }
        assertTrue(fp16.isRecommended)
        assertEquals("Recommended (90%+ Accuracy)", fp16.accuracy)
        assertEquals("FP16 High-Precision (~715 MB)", fp16.displayName)
        assertEquals("1.0.2", fp16.version)
        assertEquals(FP16_MODEL_ID, catalog[1].id)
    }

    @Test
    fun localFp16FallbackAndGuidanceHelpers_behaveDeterministically() {
        assertTrue(isFp16ModelId("SIGLIP2_FP16"))
        assertTrue(isFp16ModelId("siglip2_fp16"))
        assertFalse(isFp16ModelId("SIGLIP2_Q4F16"))
        assertFalse(isFp16ModelId("SIGLIP2_INT8"))
        assertFalse(isFp16ModelId("SIGLIP2_FULL"))
        assertFalse(isFp16ModelId(null))

        assertEquals(TIP_ALREADY_ON_FP16_MODEL, resolveLocalFailureGuidanceTip("SIGLIP2_FP16"))
        assertEquals(TIP_SWITCH_TO_FP16_MODEL, resolveLocalFailureGuidanceTip("SIGLIP2_INT8"))
        assertEquals(TIP_SWITCH_TO_FP16_MODEL, resolveLocalFailureGuidanceTip("SIGLIP2_UINT8"))
        assertEquals(TIP_SWITCH_TO_FP16_MODEL, resolveLocalFailureGuidanceTip(null))

        assertTrue(
            shouldAttemptLocalFp16Fallback(
                activeModelId = "SIGLIP2_INT8",
                isCurrentResultValid = false,
                downloadedModelIdsOnDisk = setOf("SIGLIP2_INT8", "SIGLIP2_FP16")
            )
        )
        assertFalse(
            shouldAttemptLocalFp16Fallback(
                activeModelId = "SIGLIP2_INT8",
                isCurrentResultValid = false,
                downloadedModelIdsOnDisk = setOf("SIGLIP2_INT8")
            )
        )
        assertFalse(
            shouldAttemptLocalFp16Fallback(
                activeModelId = "SIGLIP2_FP16",
                isCurrentResultValid = false,
                downloadedModelIdsOnDisk = setOf("SIGLIP2_FP16")
            )
        )
        assertFalse(
            shouldAttemptLocalFp16Fallback(
                activeModelId = "SIGLIP2_INT8",
                isCurrentResultValid = true,
                downloadedModelIdsOnDisk = setOf("SIGLIP2_INT8", "SIGLIP2_FP16")
            )
        )
    }

    @Test
    fun confidenceSummaryAndMismatchShareText_formatCleanly() {
        val summary = formatConfidenceScoresSummary(
            queries = listOf("grass", "hand"),
            logitsArray = floatArrayOf(-3.8918f, 2.1972f)
        )
        assertEquals("grass: 2.0%, hand: 90.0%", summary)

        val report = AiMismatchReport(
            quest_title = "Touch Grass",
            task_description = "Touch grass outside",
            required_features = listOf("grass", "hand"),
            validation_engine = "local",
            model_id = "SIGLIP2_INT8",
            confidence_scores = summary,
            failure_reason = "Below threshold",
            user_note = "hand and feet on grass",
            photo_attached = true,
            device_model = "Google Pixel 8",
            android_api_level = 35,
            app_version = "0.0.1-fdroid"
        )
        val formatted = formatAiMismatchShareText(report)
        assertTrue(formatted.contains("Quest Title: Touch Grass"))
        assertTrue(formatted.contains("Selected Model ID: SIGLIP2_INT8"))
        assertTrue(formatted.contains("Confidence Scores: grass: 2.0%, hand: 90.0%"))
        assertTrue(formatted.contains("User Note: hand and feet on grass"))
        assertTrue(formatted.contains("Device: Google Pixel 8 (Android API 35)"))
    }

    @Test
    fun sanitizeZeroShotQueries_filtersBlanksAndFallsBackToTaskDescription() {
        assertEquals(
            listOf("a clean desk", "a laptop"),
            sanitizeZeroShotQueries(listOf("  a clean desk ", "", "   ", "a laptop"), "Organize desk")
        )
        assertEquals(
            listOf("Organize desk"),
            sanitizeZeroShotQueries(listOf("", "   "), "  Organize desk  ")
        )
        assertTrue(sanitizeZeroShotQueries(emptyList(), "   ").isEmpty())
    }

    @Test
    fun sigmoidProbability_computesExpectedValues() {
        assertEquals(0.5f, sigmoidProbability(0f), 1e-5f)
        assertTrue(sigmoidProbability(5f) > 0.99f)
        assertTrue(sigmoidProbability(-5f) < 0.01f)
        assertEquals(0f, sigmoidProbability(-100f), 1e-6f)
        assertEquals(1f, sigmoidProbability(100f), 1e-6f)
    }

    @Test
    fun evaluateZeroShotFeatures_explicitFeaturesAllAboveThreshold_returnsCleanDetectedListAndDetailedReason() {
        val queries = listOf("a clean desk", "a laptop")
        // Logits of 0.0 -> probability 0.50 >= 0.08 threshold
        val logits = floatArrayOf(0.0f, 1.2f)

        val (detected, result) = evaluateZeroShotFeatures(
            queries = queries,
            hasExplicitFeatures = true,
            taskDescription = "Organize workspace",
            logitsArray = logits
        )

        assertEquals(listOf("a clean desk", "a laptop"), detected)
        assertTrue(result.isValid)
        assertTrue(result.reason.contains("[OK] a clean desk (Confidence: 50.0%)"))
        assertTrue(result.reason.contains("[OK] a laptop"))
    }

    @Test
    fun evaluateZeroShotFeatures_explicitFeaturesOneBelowThreshold_returnsInvalidWithScores() {
        val queries = listOf("a clean desk", "a green plant")
        // -5.0f -> prob ~0.0067 < 0.08 threshold
        val logits = floatArrayOf(0.5f, -5.0f)

        val (detected, result) = evaluateZeroShotFeatures(
            queries = queries,
            hasExplicitFeatures = true,
            taskDescription = "Organize workspace with plant",
            logitsArray = logits
        )

        assertEquals(listOf("a clean desk"), detected)
        assertFalse(result.isValid)
        assertTrue(result.reason.contains("Could not confidently verify all required features"))
        assertTrue(result.reason.contains("a green plant: 0.7%"))
    }

    @Test
    fun evaluateZeroShotFeatures_emptyLogitsOrQueries_handlesSafelyWithoutCrash() {
        val (detected1, result1) = evaluateZeroShotFeatures(
            queries = listOf("a clean desk"),
            hasExplicitFeatures = false,
            taskDescription = "a clean desk",
            logitsArray = floatArrayOf()
        )
        assertTrue(detected1.isEmpty())
        assertFalse(result1.isValid)

        val (detected2, result2) = evaluateZeroShotFeatures(
            queries = emptyList(),
            hasExplicitFeatures = true,
            taskDescription = "a clean desk",
            logitsArray = floatArrayOf(1.0f)
        )
        assertTrue(detected2.isEmpty())
        assertFalse(result2.isValid)
    }

    @Test
    fun extractLogitsFromBuffers_handlesFloatDirectBufferAndFp16AndBf16AndDouble() {
        val expected = floatArrayOf(-2.5f, 0.0f, 1.25f)

        // 1. Direct FloatBuffer (simulating JNI OnnxTensor.floatBuffer without backing array)
        val directFloatBuf = ByteBuffer.allocateDirect(expected.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
        directFloatBuf.put(expected)
        directFloatBuf.flip()
        val fromFloat = extractLogitsFromBuffers(
            type = OnnxJavaType.FLOAT,
            floatBuffer = directFloatBuf,
            shortBuffer = null
        )
        assertArrayEquals(expected, fromFloat, 1e-5f)

        // 2. FLOAT16 output tensor (SIGLIP2_Q4F16 and SIGLIP2_FP16 return floatBuffer = null, shortBuffer != null)
        val fp16ShortBuf = Fp16Conversions.convertFloatBufferToFp16Buffer(FloatBuffer.wrap(expected))
        val fromFp16 = extractLogitsFromBuffers(
            type = OnnxJavaType.FLOAT16,
            floatBuffer = null,
            shortBuffer = fp16ShortBuf
        )
        assertArrayEquals(expected, fromFp16, 1e-3f)

        // 3. BFLOAT16 output tensor (floatBuffer = null, shortBuffer != null)
        val bf16ShortBuf = Fp16Conversions.convertFloatBufferToBf16Buffer(FloatBuffer.wrap(expected))
        val fromBf16 = extractLogitsFromBuffers(
            type = OnnxJavaType.BFLOAT16,
            floatBuffer = null,
            shortBuffer = bf16ShortBuf
        )
        assertArrayEquals(expected, fromBf16, 1e-2f)

        // 4. DOUBLE output tensor
        val doubleBuf = DoubleBuffer.wrap(doubleArrayOf(-2.5, 0.0, 1.25))
        val fromDouble = extractLogitsFromBuffers(
            type = OnnxJavaType.DOUBLE,
            floatBuffer = null,
            shortBuffer = null,
            doubleBuffer = doubleBuf
        )
        assertArrayEquals(expected, fromDouble, 1e-5f)

        // 5. Missing buffer throws IllegalStateException
        assertThrows(IllegalStateException::class.java) {
            extractLogitsFromBuffers(
                type = OnnxJavaType.FLOAT16,
                floatBuffer = null,
                shortBuffer = null
            )
        }
    }

    @Test
    fun parseNanoValidationResponse_handlesStandardAndReversedKeysAndEscapedStringsAndMalformed() {
        val validJson = """
            {
               "is_valid": true,
               "reason": "Great job cleaning your desk!"
            }
        """.trimIndent()
        val resValid = parseNanoValidationResponse(validJson)
        assertTrue(resValid.isValid)
        assertEquals("Great job cleaning your desk!", resValid.reason)

        // Reversed key order ("reason" before "is_valid")
        val reversedJson = """
            {
               "reason": "Your bedroom looks spotless!",
               "is_valid": true
            }
        """.trimIndent()
        val resReversed = parseNanoValidationResponse(reversedJson)
        assertTrue(resReversed.isValid)
        assertEquals("Your bedroom looks spotless!", resReversed.reason)

        // Escaped quotes and braces inside reason
        val escapedJson = """
            ```json
            {
               "reason": "Could not find \"a laptop\" inside {workspace}.",
               "is_valid": false
            }
            ```
        """.trimIndent()
        val resEscaped = parseNanoValidationResponse(escapedJson)
        assertFalse(resEscaped.isValid)
        assertEquals("Could not find \"a laptop\" inside {workspace}.", resEscaped.reason)

        val invalidWithoutReason = """{"is_valid": false}"""
        val resInvalidNoReason = parseNanoValidationResponse(invalidWithoutReason)
        assertFalse(resInvalidNoReason.isValid)
        assertEquals("Could not verify goal from detected environment features.", resInvalidNoReason.reason)

        val malformedResponse = "I am not sure how to format JSON"
        val resMalformed = parseNanoValidationResponse(malformedResponse)
        assertFalse(resMalformed.isValid)
        assertTrue(resMalformed.reason.startsWith("On-device AI reasoning error:"))
    }

    @Test
    fun resolveModelCatalog_preservesDefaultsOnBlankOverridesAndHandlesDuplicates() {
        val json = """
            [
              {
                "id": "SIGLIP2_INT8",
                "url": "",
                "sizeMB": 123,
                "accuracy": "Ignored",
                "version": "   "
              },
              {
                "id": "CUSTOM_MODEL",
                "url": "https://example.com/v1.onnx",
                "sizeMB": 200,
                "accuracy": "Custom",
                "version": "1.0.0"
              },
              {
                "id": "CUSTOM_MODEL",
                "url": "https://example.com/v2.onnx",
                "sizeMB": 200,
                "accuracy": "Custom",
                "version": "2.0.0"
              }
            ]
        """.trimIndent()

        val catalog = resolveModelCatalog(json)
        val int8 = catalog.first { it.id == "SIGLIP2_INT8" }
        val defaultInt8 = DEFAULT_MODEL_CATALOG.first { it.id == "SIGLIP2_INT8" }
        assertEquals(defaultInt8.url, int8.url)
        assertEquals(defaultInt8.version, int8.version)
        assertEquals(defaultInt8.displayName, int8.displayName)
        assertEquals(1, catalog.count { it.id == "CUSTOM_MODEL" })
        assertEquals("2.0.0", catalog.first { it.id == "CUSTOM_MODEL" }.version)
    }

    @Test
    fun resolveActiveLocalModelId_usesDeterministicCatalogPriorityAndHandlesEdgeCases() {
        // Order of non-FP16 models follows catalog order (INT8 > UINT8) regardless of Set iteration order
        assertEquals(
            "SIGLIP2_INT8",
            resolveActiveLocalModelId(
                selectedModel = "online",
                downloadingModel = null,
                downloadedModelIdsOnDisk = linkedSetOf("SIGLIP2_UINT8", "SIGLIP2_INT8")
            )
        )
        // Unknown custom models fall back in sorted order
        assertEquals(
            "ALPHA_MODEL",
            resolveActiveLocalModelId(
                selectedModel = null,
                downloadingModel = null,
                downloadedModelIdsOnDisk = linkedSetOf("ZETA_MODEL", "ALPHA_MODEL")
            )
        )
        // Selected model missing from disk falls back to available on-disk model
        assertEquals(
            "SIGLIP2_FP16",
            resolveActiveLocalModelId(
                selectedModel = "SIGLIP2_INT8",
                downloadingModel = null,
                downloadedModelIdsOnDisk = setOf("SIGLIP2_FP16")
            )
        )
        // Selected model missing from disk and nothing on disk retains selectedModel
        assertEquals(
            "SIGLIP2_INT8",
            resolveActiveLocalModelId(
                selectedModel = "SIGLIP2_INT8",
                downloadingModel = null,
                downloadedModelIdsOnDisk = emptySet()
            )
        )
        // "online" as downloadingModel is ignored
        assertNull(
            resolveActiveLocalModelId(
                selectedModel = "online",
                downloadingModel = "online",
                downloadedModelIdsOnDisk = emptySet()
            )
        )
    }

    @Test
    fun formatLocalModelDisplayLabel_coversAllStatesAndCustomCatalogs() {
        assertEquals(
            "FP16 High-Precision (~715 MB) (SIGLIP2_FP16)",
            formatLocalModelDisplayLabel(
                activeModelId = "SIGLIP2_FP16",
                downloadingModelId = "SIGLIP2_FP16",
                isOnDisk = true
            )
        )
        assertEquals(
            "CUSTOM_LOCAL",
            formatLocalModelDisplayLabel(
                activeModelId = "CUSTOM_LOCAL",
                downloadingModelId = null,
                isOnDisk = true
            )
        )
        val blankNameCatalog = listOf(
            DownloadableModel(
                sizeMB = 100,
                accuracy = "Test",
                id = "CUSTOM_LOCAL",
                url = "https://example.com/a.onnx",
                version = "1.0.0",
                displayName = "   "
            )
        )
        assertEquals(
            "CUSTOM_LOCAL",
            formatLocalModelDisplayLabel(
                activeModelId = "CUSTOM_LOCAL",
                downloadingModelId = null,
                isOnDisk = true,
                catalog = blankNameCatalog
            )
        )
    }

    @Test
    fun needsTokenizerDownload_detectsMissingDirectoryZeroByteAndValidFiles() {
        val tempDir = Files.createTempDirectory("tokenizer_check").toFile()
        try {
            val missing = File(tempDir, "tokenizer.model")
            assertTrue(needsTokenizerDownload(isMarkedDownloaded = true, tokenizerFile = missing))

            val dirAsFile = File(tempDir, "dir_tokenizer").apply { mkdirs() }
            assertTrue(needsTokenizerDownload(isMarkedDownloaded = true, tokenizerFile = dirAsFile))

            val emptyFile = File(tempDir, "empty.model").apply { createNewFile() }
            assertEquals(0L, emptyFile.length())
            assertTrue(needsTokenizerDownload(isMarkedDownloaded = true, tokenizerFile = emptyFile))

            val validFile = File(tempDir, "valid.model").apply { writeBytes(byteArrayOf(1, 2, 3, 4)) }
            assertTrue(needsTokenizerDownload(isMarkedDownloaded = false, tokenizerFile = validFile))
            assertFalse(needsTokenizerDownload(isMarkedDownloaded = true, tokenizerFile = validFile))
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun sigmoidProbability_handlesNanInfinitiesAndExactClampBounds() {
        assertEquals(0.0f, sigmoidProbability(Float.NaN), 1e-6f)
        assertEquals(0.0f, sigmoidProbability(Float.NEGATIVE_INFINITY), 1e-6f)
        assertEquals(1.0f, sigmoidProbability(Float.POSITIVE_INFINITY), 1e-6f)
        assertEquals(0.0f, sigmoidProbability(-88.01f), 1e-6f)
        assertEquals(1.0f, sigmoidProbability(88.01f), 1e-6f)
        assertTrue(sigmoidProbability(-88.0f) >= 0.0f)
        assertTrue(sigmoidProbability(88.0f) <= 1.0f)
    }

    @Test
    fun evaluateZeroShotFeatures_handlesImplicitFeaturesMismatchLengthsNanAndLocaleInvariance() {
        val originalLocale = Locale.getDefault()
        try {
            Locale.setDefault(Locale.FRANCE)

            // Implicit features (taskDescription fallback): passes if ANY query matches
            val (detectedImplicit, resImplicitPass) = evaluateZeroShotFeatures(
                queries = listOf("clean desk", "bed made"),
                hasExplicitFeatures = false,
                taskDescription = "clean desk",
                logitsArray = floatArrayOf(-5.0f, 1.0f)
            )
            assertEquals(listOf("bed made"), detectedImplicit)
            assertTrue(resImplicitPass.isValid)

            val (detectedImplicitNone, resImplicitFail) = evaluateZeroShotFeatures(
                queries = listOf("clean desk"),
                hasExplicitFeatures = false,
                taskDescription = "clean desk",
                logitsArray = floatArrayOf(-5.0f)
            )
            assertTrue(detectedImplicitNone.isEmpty())
            assertFalse(resImplicitFail.isValid)

            // Shorter logitsArray defaults missing logits to -100f (0.0%), NaN is clamped to 0.0%
            val (detectedShort, resShort) = evaluateZeroShotFeatures(
                queries = listOf("a", "b", "c"),
                hasExplicitFeatures = true,
                taskDescription = "test",
                logitsArray = floatArrayOf(Float.NaN, 1.0f)
            )
            assertEquals(listOf("b"), detectedShort)
            assertFalse(resShort.isValid)
            assertFalse(resShort.reason.contains("NaN%"))
            // Verify US dot decimal separator even under Locale.FRANCE
            assertTrue(resShort.reason.contains("a: 0.0%"))
            assertTrue(resShort.reason.contains("c: 0.0%"))

            // Custom threshold boundary
            val logitAtHalf = 0.0f // sigmoid = 0.5f
            assertTrue(
                evaluateZeroShotFeatures(
                    queries = listOf("desk"),
                    hasExplicitFeatures = true,
                    taskDescription = "desk",
                    logitsArray = floatArrayOf(logitAtHalf, 5.0f), // extra logit ignored
                    threshold = 0.5f
                ).second.isValid
            )
            assertFalse(
                evaluateZeroShotFeatures(
                    queries = listOf("desk"),
                    hasExplicitFeatures = true,
                    taskDescription = "desk",
                    logitsArray = floatArrayOf(logitAtHalf),
                    threshold = 0.51f
                ).second.isValid
            )

            // Format summary uses dot decimal separator under Locale.FRANCE and handles empty/NaN
            assertEquals("", formatConfidenceScoresSummary(emptyList(), floatArrayOf(1.0f)))
            assertEquals("", formatConfidenceScoresSummary(listOf("a"), floatArrayOf()))
            assertEquals(
                "a: 0.0%, b: 0.0%",
                formatConfidenceScoresSummary(listOf("a", "b"), floatArrayOf(Float.NaN))
            )
        } finally {
            Locale.setDefault(originalLocale)
        }
    }

    @Test
    fun formatAiMismatchShareText_andReportSerialization_handleBlankDefaultsAndOptionalFields() {
        val blankReport = AiMismatchReport(
            quest_title = "",
            task_description = "",
            required_features = emptyList(),
            validation_engine = "cloud",
            model_id = "",
            confidence_scores = "",
            failure_reason = "",
            user_note = "",
            photo_attached = false,
            photo_storage_path = "anon/photo.jpg",
            device_model = "Pixel 9",
            android_api_level = 36,
            app_version = "0.0.1",
            user_id = "user-abc"
        )
        val formatted = formatAiMismatchShareText(blankReport)
        assertTrue(formatted.contains("Quest Title: (Untitled Quest)"))
        assertTrue(formatted.contains("Task Description: N/A"))
        assertTrue(formatted.contains("Required Features: (None - task description only)"))
        assertTrue(formatted.contains("Selected Model ID: N/A"))
        assertTrue(formatted.contains("Confidence Scores: N/A"))
        assertTrue(formatted.contains("Failure Reason: N/A"))
        assertTrue(formatted.contains("User Note: N/A"))
        assertTrue(formatted.contains("Photo Attached: No"))

        val encoded = Json.encodeToString(blankReport)
        val decoded = Json.decodeFromString<AiMismatchReport>(encoded)
        assertEquals(blankReport, decoded)
    }

    @Test
    fun extractLogitsFromBuffers_preservesCallerPositionAndRejectsMissingOrUnsupportedBuffers() {
        val buf = FloatBuffer.wrap(floatArrayOf(1.25f, -0.5f))
        assertEquals(0, buf.position())
        val extracted = extractLogitsFromBuffers(
            type = OnnxJavaType.FLOAT,
            floatBuffer = buf,
            shortBuffer = null
        )
        assertArrayEquals(floatArrayOf(1.25f, -0.5f), extracted, 1e-5f)
        assertEquals(0, buf.position())

        assertThrows(IllegalStateException::class.java) {
            extractLogitsFromBuffers(type = OnnxJavaType.FLOAT, floatBuffer = null, shortBuffer = null)
        }
        assertThrows(IllegalStateException::class.java) {
            extractLogitsFromBuffers(type = OnnxJavaType.BFLOAT16, floatBuffer = null, shortBuffer = null)
        }
        assertThrows(IllegalStateException::class.java) {
            extractLogitsFromBuffers(type = OnnxJavaType.DOUBLE, floatBuffer = null, shortBuffer = null, doubleBuffer = null)
        }
        assertThrows(IllegalStateException::class.java) {
            extractLogitsFromBuffers(type = OnnxJavaType.INT64, floatBuffer = null, shortBuffer = null)
        }
    }

    @Test
    fun parseNanoValidationResponse_handlesUppercaseBlankReasonAndUnquotedReason() {
        val validNoReason = parseNanoValidationResponse("""{"is_valid": TRUE}""")
        assertTrue(validNoReason.isValid)
        assertEquals("Goal validated successfully!", validNoReason.reason)

        val validBlankReason = parseNanoValidationResponse("""{"is_valid": true, "reason": "   "}""")
        assertTrue(validBlankReason.isValid)
        assertEquals("Goal validated successfully!", validBlankReason.reason)

        val escapedControls = parseNanoValidationResponse("""{"is_valid": false, "reason": "Line1\nLine2\\end"}""")
        assertFalse(escapedControls.isValid)
        assertEquals("Line1\nLine2\\end", escapedControls.reason)

        val unquotedFallback = parseNanoValidationResponse("""{"is_valid": false, "reason": missing_desk}""")
        assertFalse(unquotedFallback.isValid)
        assertEquals("missing_desk", unquotedFallback.reason)
    }
}
