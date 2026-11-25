package com.alhaq.amniquest

import android.content.Context
import com.alhaq.amniquest.app.screens.quest.view.ai_snap.AI_SNAP_PIC
import com.alhaq.amniquest.backed.repositories.QuestRepository
import com.alhaq.amniquest.backed.repositories.StatsRepository
import com.alhaq.amniquest.backed.repositories.UserRepository
import com.alhaq.amniquest.backend.TaskValidationClient
import com.alhaq.amniquest.core.utils.FP16_MODEL_ID
import com.alhaq.amniquest.data.BaseIntegrationId
import com.alhaq.amniquest.data.CommonQuestInfo
import com.alhaq.amniquest.data.EvaluationStep
import com.alhaq.amniquest.data.json
import com.alhaq.amniquest.data.quest.ai.snap.AiSnap
import com.alhaq.amniquest.testutil.FakeApplication
import com.alhaq.amniquest.testutil.FakeQuestDao
import com.alhaq.amniquest.testutil.FakeStatsInfoDao
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

@OptIn(ExperimentalCoroutinesApi::class)
class AiSnapQuestViewVMTest {

    private lateinit var tempDir: File
    private lateinit var app: FakeApplication
    private lateinit var questDao: FakeQuestDao
    private lateinit var statsDao: FakeStatsInfoDao
    private lateinit var questRepo: QuestRepository
    private lateinit var statsRepo: StatsRepository
    private lateinit var userRepo: UserRepository

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        tempDir = Files.createTempDirectory("ai_snap_vm_test").toFile()
        app = FakeApplication(tempDir)
        questDao = FakeQuestDao()
        statsDao = FakeStatsInfoDao()
        questRepo = QuestRepository(questDao)
        statsRepo = StatsRepository(statsDao)
        userRepo = UserRepository(app, statsRepo, questRepo)
        userRepo.userInfo.isAnonymous = true
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        tempDir.deleteRecursively()
    }

    private fun createVm(): AiSnapQuestViewVM =
        AiSnapQuestViewVM(questRepo, userRepo, statsRepo, app)

    @Test
    fun setCommonQuest_and_setAiSnap_and_resetResults_manageStateCleanly() = runBlocking {
        val vm = createVm()
        val aiSnap = AiSnap(
            taskDescription = "Clean desk",
            features = mutableListOf("desk", "laptop")
        )
        val quest = CommonQuestInfo(
            id = "q-ai-1",
            user_id = "u-1",
            title = "Workspace Cleanup",
            integration_id = BaseIntegrationId.AI_SNAP,
            quest_json = json.encodeToString(aiSnap)
        )
        vm.setCommonQuest(quest)
        vm.setAiSnap()

        assertEquals("Clean desk", vm.aiQuest.taskDescription)
        assertEquals(listOf("desk", "laptop"), vm.aiQuest.features)

        vm.results.value = TaskValidationClient.ValidationResult(false, "Old error")
        vm.error.value = "Transient"
        vm.lastConfidenceSummary.value = "desk: 50.0%"
        vm.currentStep.value = EvaluationStep.COMPLETED
        vm.isAiEvaluating.value = false

        vm.resetResults()
        assertTrue(vm.isAiEvaluating.value)
        assertNull(vm.results.value)
        assertNull(vm.error.value)
        assertNull(vm.lastConfidenceSummary.value)
        assertEquals(EvaluationStep.INITIALIZING, vm.currentStep.value)
    }

    @Test
    fun loadModel_cloudAndGeminiEngines_returnTrueAndUpdateEngineMetadata() = runBlocking {
        val settingsSp = app.getSharedPreferences("private_settings", Context.MODE_PRIVATE)
        settingsSp.edit().putString("validation_engine", "cloud").commit()

        val vm = createVm()
        assertTrue(vm.loadModel(forEvaluation = true))
        assertEquals("cloud", vm.lastEvaluatedEngine.value)
        assertEquals("cloud-server", vm.lastEvaluatedModelId.value)

        settingsSp.edit().putString("validation_engine", "gemini_api").commit()
        assertTrue(vm.loadModel(forEvaluation = true))
        assertEquals("gemini_api", vm.lastEvaluatedEngine.value)
        assertEquals("gemini-api", vm.lastEvaluatedModelId.value)
    }

    @Test
    fun loadModel_localEngineMissingOrDownloadingOrZeroByteModel_reportsActionableReasons() = runBlocking {
        val settingsSp = app.getSharedPreferences("private_settings", Context.MODE_PRIVATE)
        val modelsSp = app.getSharedPreferences("models", Context.MODE_PRIVATE)
        settingsSp.edit().putString("validation_engine", "local").commit()

        val vm = createVm()
        // Wait for init coroutine before mutating SP assertions
        vm.loadModel(forEvaluation = false)

        // 1. No model on disk and not downloading
        modelsSp.edit().putString("selected_one_shot_model", "online").remove("downloading").commit()
        assertFalse(vm.loadModel(forEvaluation = true))
        assertFalse(vm.isModelDownloaded.value)
        assertEquals("local", vm.lastEvaluatedEngine.value)
        assertNull(vm.lastEvaluatedModelId.value)
        assertEquals(EvaluationStep.COMPLETED, vm.currentStep.value)
        assertEquals(
            "Model not found. Please click the model icon in the top right to download it",
            vm.results.value?.reason
        )

        // 2. Currently downloading
        vm.resetResults()
        modelsSp.edit().putString("downloading", "SIGLIP2_INT8").commit()
        assertFalse(vm.loadModel(forEvaluation = true))
        assertEquals("Please wait until the model fully downloads", vm.results.value?.reason)
        assertEquals("SIGLIP2_INT8", vm.lastEvaluatedModelId.value)

        // 3. Zero-byte .onnx file on disk must be treated as missing
        vm.resetResults()
        modelsSp.edit().remove("downloading").commit()
        val zeroOnnx = File(tempDir, "SIGLIP2_INT8.onnx").apply { createNewFile() }
        assertEquals(0L, zeroOnnx.length())
        assertFalse(vm.loadModel(forEvaluation = true))
        assertFalse(vm.isModelDownloaded.value)
        assertEquals(
            "Model not found. Please click the model icon in the top right to download it",
            vm.results.value?.reason
        )
    }

    @Test
    fun loadModel_localEnginePrefersFp16OnDiskChecksTokenizerAndCatchesJvmNativeFailure() = runBlocking {
        val settingsSp = app.getSharedPreferences("private_settings", Context.MODE_PRIVATE)
        val modelsSp = app.getSharedPreferences("models", Context.MODE_PRIVATE)
        settingsSp.edit().putString("validation_engine", "local").commit()
        modelsSp.edit().putString("selected_one_shot_model", "online").commit()

        File(tempDir, "SIGLIP2_INT8.onnx").writeBytes(byteArrayOf(1, 2, 3))
        File(tempDir, "$FP16_MODEL_ID.onnx").writeBytes(byteArrayOf(4, 5, 6))

        val vm = createVm()
        // Wait for init coroutine
        vm.loadModel(forEvaluation = false)

        // Auto-persists resolved model from disk when selected_one_shot_model was "online"
        assertEquals(FP16_MODEL_ID, modelsSp.getString("selected_one_shot_model", null))

        // Missing tokenizer.model -> fails with actionable download message
        assertFalse(vm.loadModel(forEvaluation = true))
        assertEquals(FP16_MODEL_ID, vm.lastEvaluatedModelId.value)
        assertEquals(
            "Tokenizer model is downloading in the background. Please retry in a few seconds.",
            vm.results.value?.reason
        )

        // 0-byte tokenizer.model -> also treated as downloading/incomplete
        val tokenizerFile = File(tempDir, "tokenizer.model").apply { createNewFile() }
        assertFalse(vm.loadModel(forEvaluation = true))
        assertEquals(
            "Tokenizer model is downloading in the background. Please retry in a few seconds.",
            vm.results.value?.reason
        )

        // Non-empty tokenizer.model -> reaches OrtEnvironment.getEnvironment() and catches UnsatisfiedLinkError cleanly
        tokenizerFile.writeBytes(byteArrayOf(10, 20, 30, 40))
        assertFalse(vm.loadModel(forEvaluation = true))
        assertEquals(EvaluationStep.COMPLETED, vm.currentStep.value)
        assertTrue(vm.results.value?.reason?.startsWith("Failed to load model:") == true)
    }

    @Test
    fun evaluateQuest_onlineFailurePaths_completeCleanlyWithExpectedMessages() = runBlocking {
        val settingsSp = app.getSharedPreferences("private_settings", Context.MODE_PRIVATE)
        val vm = createVm()
        vm.setCommonQuest(
            CommonQuestInfo(
                id = "q-online",
                user_id = "u-1",
                title = "Read Book",
                integration_id = BaseIntegrationId.AI_SNAP,
                quest_json = json.encodeToString(AiSnap(taskDescription = "A book", features = mutableListOf("book")))
            )
        )
        vm.setAiSnap()

        // 1. Missing photo
        settingsSp.edit().putString("validation_engine", "cloud").commit()
        vm.resetResults()
        vm.evaluateQuest {}
        withTimeout(5_000) {
            vm.currentStep.first { it == EvaluationStep.COMPLETED }
        }
        assertEquals(false, vm.results.value?.isValid)
        assertEquals("Image file not found.", vm.results.value?.reason)

        // 2. Photo present, gemini_api with blank key
        File(tempDir, AI_SNAP_PIC).writeBytes(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte()))
        settingsSp.edit()
            .putString("validation_engine", "gemini_api")
            .putString("gemini_api_key", "   ")
            .commit()
        vm.resetResults()
        vm.evaluateQuest {}
        withTimeout(5_000) {
            vm.currentStep.first { it == EvaluationStep.COMPLETED }
        }
        assertEquals(false, vm.results.value?.isValid)
        assertEquals(
            "Private Gemini API key is missing. Please configure it in your Profile settings.",
            vm.results.value?.reason
        )

        // 3. Photo present, cloud engine with anonymous user (empty token)
        settingsSp.edit().putString("validation_engine", "cloud").commit()
        userRepo.userInfo.isAnonymous = true
        vm.resetResults()
        vm.evaluateQuest {}
        withTimeout(5_000) {
            vm.currentStep.first { it == EvaluationStep.COMPLETED }
        }
        assertEquals(false, vm.results.value?.isValid)
        assertEquals(
            "Authentication required. Please log in or configure a private Gemini API Key / Local AI.",
            vm.results.value?.reason
        )
    }

    @Test
    fun advanceProgressStepsUntilResult_doesNotOverwriteCompletedWhenResultArrivesDuringDelay() = runBlocking {
        val vm = createVm()
        vm.resetResults()

        var ticks = 0
        val observedSteps = mutableListOf<EvaluationStep>()
        vm.advanceProgressStepsUntilResult(
            stepDelayProvider = {
                ticks++
                observedSteps.add(vm.currentStep.value)
                if (ticks == 3) {
                    vm.results.value = TaskValidationClient.ValidationResult(true, "Done")
                    vm.currentStep.value = EvaluationStep.COMPLETED
                }
                0L
            }
        )

        assertEquals(3, ticks)
        assertEquals(EvaluationStep.COMPLETED, vm.currentStep.value)
        assertTrue(observedSteps.none { it == EvaluationStep.COMPLETED })
    }

    @Test
    fun onCleared_and_concurrentLoadModelCalls_maintainMutexAndLifecycleSafety() = runBlocking {
        val settingsSp = app.getSharedPreferences("private_settings", Context.MODE_PRIVATE)
        settingsSp.edit().putString("validation_engine", "local").commit()

        val vm = createVm()
        // Concurrent loadModel invocations serialize cleanly via sessionMutex
        coroutineScope {
            val jobs = (1..5).map {
                async { vm.loadModel(forEvaluation = false) }
            }
            jobs.forEach { assertFalse(it.await()) }
        }

        // Invoke protected onCleared()
        val onClearedMethod = AiSnapQuestViewVM::class.java.getDeclaredMethod("onCleared")
        onClearedMethod.isAccessible = true
        onClearedMethod.invoke(vm)

        // Subsequent loadModel must return false immediately without mutating results
        vm.results.value = null
        assertFalse(vm.loadModel(forEvaluation = true))
        assertNull(vm.results.value)
    }

    @Test
    fun buildAiMismatchDiagnosticPreview_handlesUninitializedQuestAndFiltersBlankFeatures() = runBlocking {
        val settingsSp = app.getSharedPreferences("private_settings", Context.MODE_PRIVATE)
        val modelsSp = app.getSharedPreferences("models", Context.MODE_PRIVATE)
        settingsSp.edit().putString("validation_engine", "local").commit()
        modelsSp.edit().putString("selected_one_shot_model", "SIGLIP2_INT8").commit()

        val vm = createVm()
        vm.lastEvaluatedEngine.value = ""
        vm.lastEvaluatedModelId.value = null
        vm.aiQuest = AiSnap(
            taskDescription = "Touch grass",
            features = mutableListOf("grass", "  ", "", "hand")
        )
        vm.lastConfidenceSummary.value = "grass: 2.0%, hand: 90.0%"
        vm.results.value = TaskValidationClient.ValidationResult(false, "Below threshold")

        // No photo on disk -> photo_attached must be false even when attachPhoto = true
        val previewWithoutPhoto = vm.buildAiMismatchDiagnosticPreview(
            userNote = "  hand and feet on grass  ",
            attachPhoto = true,
            includeUserId = false
        )
        assertEquals("", previewWithoutPhoto.quest_title)
        assertEquals("Touch grass", previewWithoutPhoto.task_description)
        assertEquals(listOf("grass", "hand"), previewWithoutPhoto.required_features)
        assertEquals("local", previewWithoutPhoto.validation_engine)
        assertEquals("SIGLIP2_INT8", previewWithoutPhoto.model_id)
        assertEquals("grass: 2.0%, hand: 90.0%", previewWithoutPhoto.confidence_scores)
        assertEquals("Below threshold", previewWithoutPhoto.failure_reason)
        assertEquals("hand and feet on grass", previewWithoutPhoto.user_note)
        assertFalse(previewWithoutPhoto.photo_attached)
        assertNull(previewWithoutPhoto.user_id)

        // Create photo on disk + set commonQuestInfo
        File(tempDir, AI_SNAP_PIC).writeBytes(byteArrayOf(1, 2, 3))
        vm.setCommonQuest(
            CommonQuestInfo(
                id = "q-1",
                user_id = "u-1",
                title = "Morning Grass",
                integration_id = BaseIntegrationId.AI_SNAP,
                quest_json = json.encodeToString(vm.aiQuest)
            )
        )
        vm.lastEvaluatedEngine.value = "local"
        vm.lastEvaluatedModelId.value = FP16_MODEL_ID

        val previewWithPhoto = vm.buildAiMismatchDiagnosticPreview(
            userNote = "clear daylight",
            attachPhoto = true
        )
        assertEquals("Morning Grass", previewWithPhoto.quest_title)
        assertEquals(FP16_MODEL_ID, previewWithPhoto.model_id)
        assertTrue(previewWithPhoto.photo_attached)

        val previewOptOut = vm.buildAiMismatchDiagnosticPreview(attachPhoto = false)
        assertFalse(previewOptOut.photo_attached)
    }

    @Test
    fun resizeAndCompressImage_returnsOriginalFileSafelyWhenDecodeFails() {
        val corruptFile = File(tempDir, "corrupt_image.jpg").apply {
            writeText("not-a-valid-jpeg-stream")
        }
        val returned = resizeAndCompressImage(corruptFile)
        assertSame(corruptFile, returned)
    }
}
