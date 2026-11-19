package com.alhaq.amniquest

import ai.onnxruntime.OnnxJavaType
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.OrtSession.SessionOptions
import ai.onnxruntime.TensorInfo
import ai.onnxruntime.platform.Fp16Conversions
import android.annotation.SuppressLint
import android.app.Activity
import android.app.Application
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.core.content.edit
import androidx.core.graphics.scale
import androidx.lifecycle.viewModelScope
import com.alhaq.amniquest.ai.padTokenIds
import com.alhaq.amniquest.ai.preprocessBitmapToFloatBuffer
import com.alhaq.amniquest.ai.tokenizeText
import com.alhaq.amniquest.app.screens.quest.setup.ai_snap.model.resolveActiveLocalModelId
import com.alhaq.amniquest.app.screens.quest.view.ViewQuestVM
import com.alhaq.amniquest.app.screens.quest.view.ai_snap.AI_SNAP_PIC
import com.alhaq.amniquest.app.screens.quest.view.ai_snap.getBitmapFromPath
import com.alhaq.amniquest.backed.isOnline
import com.alhaq.amniquest.backed.repositories.QuestRepository
import com.alhaq.amniquest.backed.repositories.StatsRepository
import com.alhaq.amniquest.backed.repositories.UserRepository
import com.alhaq.amniquest.backend.GeminiValidator
import com.alhaq.amniquest.backend.TaskValidationClient
import com.alhaq.amniquest.core.Supabase
import com.alhaq.amniquest.core.utils.AiMismatchReport
import com.alhaq.amniquest.core.utils.FP16_MODEL_ID
import com.alhaq.amniquest.core.utils.LocalGeminiNanoValidator
import com.alhaq.amniquest.core.utils.MINIMUM_ZERO_SHOT_THRESHOLD
import com.alhaq.amniquest.core.utils.evaluateZeroShotFeatures
import com.alhaq.amniquest.core.utils.extractLogitsFloatArray
import com.alhaq.amniquest.core.utils.formatAiMismatchShareText
import com.alhaq.amniquest.core.utils.formatConfidenceScoresSummary
import com.alhaq.amniquest.core.utils.sanitizeZeroShotQueries
import com.alhaq.amniquest.core.utils.shouldAttemptLocalFp16Fallback
import com.alhaq.amniquest.core.workers.FileDownloadWorker
import com.alhaq.amniquest.data.EvaluationStep
import com.alhaq.amniquest.data.json
import com.alhaq.amniquest.data.quest.ai.snap.AiSnap
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.storage.storage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.nio.FloatBuffer
import java.nio.IntBuffer
import java.nio.LongBuffer
import java.util.UUID
import javax.inject.Inject
import kotlin.random.Random

@HiltViewModel
class AiSnapQuestViewVM @Inject constructor(
    questRepository: QuestRepository,
    userRepository: UserRepository,
    statsRepository: StatsRepository,
    private val context: Application,
) : ViewQuestVM(
    questRepository, userRepository, statsRepository, context,
) {
    val isAiEvaluating = MutableStateFlow(false)
    val isCameraScreen = MutableStateFlow(false)
    var aiQuest = AiSnap()

    val currentStep = MutableStateFlow(EvaluationStep.INITIALIZING)
    val error = MutableStateFlow<String?>(null)
    val results = MutableStateFlow<TaskValidationClient.ValidationResult?>(null)
    val isModelDownloaded = MutableStateFlow(true)
    val lastEvaluatedEngine = MutableStateFlow("local")
    val lastEvaluatedModelId = MutableStateFlow<String?>(null)
    val lastConfidenceSummary = MutableStateFlow<String?>(null)
    val isSubmittingFeedback = MutableStateFlow(false)

    private var modelSession: OrtSession? = null
    private var env: OrtEnvironment? = null
    private var isModelLoaded = false
    private var isSessionUsingNnapi = false
    private val sessionMutex = Mutex()
    @Volatile
    private var isCleared = false

    private var modelId: String? = null
    private var isOnlineInferencing = false

    private val client = TaskValidationClient()
    private val localNano = LocalGeminiNanoValidator(context)

    init {
        viewModelScope.launch(Dispatchers.IO) {
            loadModel(forEvaluation = false)
        }
    }

    fun setAiSnap() {
        aiQuest = json.decodeFromString<AiSnap>(commonQuestInfo.quest_json)
    }

    fun onAiSnapQuestDone() {
        saveMarkedQuestToDb()
        isCameraScreen.value = false
    }

    suspend fun loadModel(forEvaluation: Boolean = false, overrideModelId: String? = null): Boolean {
        return sessionMutex.withLock {
            try {
                loadModelInternal(forEvaluation = forEvaluation, overrideModelId = overrideModelId)
            } finally {
                if (isCleared) {
                    closeSessionResources()
                }
            }
        }
    }

    private fun loadModelInternal(forEvaluation: Boolean = false, overrideModelId: String? = null): Boolean {
        if (isCleared) return false
        return try {
            val settingsSp = context.getSharedPreferences("private_settings", Context.MODE_PRIVATE)
            val engine = settingsSp.getString("validation_engine", "cloud") ?: "cloud"

            if (overrideModelId == null && engine != "local") {
                isOnlineInferencing = true
                lastEvaluatedEngine.value = engine
                lastEvaluatedModelId.value = if (engine == "gemini_api") "gemini-api" else "cloud-server"
                return true
            }

            val sp = context.getSharedPreferences("models", Context.MODE_PRIVATE)
            val currentSelectedModel = sp.getString("selected_one_shot_model", "online") ?: "online"

            val tokenizerFile = File(context.filesDir, "tokenizer.model")
            if (!tokenizerFile.exists() || tokenizerFile.length() <= 0L) {
                FileDownloadWorker.enqueueTokenizerDownload(context)
            }

            val downloadedOnDisk = context.filesDir.listFiles()
                ?.filter { it.name.endsWith(".onnx") && it.length() > 0L }
                ?.map { it.nameWithoutExtension }
                ?.toSet()
                ?: emptySet()

            val modelIdToLoad = if (!overrideModelId.isNullOrBlank()) {
                overrideModelId
            } else {
                val resolved = resolveActiveLocalModelId(
                    selectedModel = currentSelectedModel,
                    downloadingModel = sp.getString("downloading", null),
                    downloadedModelIdsOnDisk = downloadedOnDisk
                )
                if (resolved == null) {
                    isModelDownloaded.value = false
                    lastEvaluatedEngine.value = "local"
                    lastEvaluatedModelId.value = null
                    if (forEvaluation) {
                        val reasonMsg = if (sp.contains("downloading")) {
                            "Please wait until the model fully downloads"
                        } else {
                            "Model not found. Please click the model icon in the top right to download it"
                        }
                        results.value = TaskValidationClient.ValidationResult(
                            isValid = false,
                            reason = reasonMsg
                        )
                        currentStep.value = EvaluationStep.COMPLETED
                    }
                    isModelLoaded = false
                    return false
                }
                if (currentSelectedModel != resolved && downloadedOnDisk.contains(resolved)) {
                    sp.edit(commit = true) {
                        putString("selected_one_shot_model", resolved)
                    }
                }
                resolved
            }

            val modelFile = File(context.filesDir, "$modelIdToLoad.onnx")
            if (!modelFile.exists() || modelFile.length() <= 0L) {
                isModelDownloaded.value = false
                lastEvaluatedEngine.value = "local"
                lastEvaluatedModelId.value = modelIdToLoad
                if (forEvaluation) {
                    val reasonMsg = if (sp.contains("downloading")) {
                        "Please wait until the model fully downloads"
                    } else {
                        "Model not found. Please click the model icon in the top right to download it"
                    }
                    results.value = TaskValidationClient.ValidationResult(
                        isValid = false,
                        reason = reasonMsg
                    )
                    currentStep.value = EvaluationStep.COMPLETED
                }
                isModelLoaded = false
                return false
            }

            if (!tokenizerFile.exists() || tokenizerFile.length() <= 0L) {
                lastEvaluatedEngine.value = "local"
                lastEvaluatedModelId.value = modelIdToLoad
                if (forEvaluation) {
                    results.value = TaskValidationClient.ValidationResult(
                        isValid = false,
                        reason = "Tokenizer model is downloading in the background. Please retry in a few seconds."
                    )
                    currentStep.value = EvaluationStep.COMPLETED
                }
                isModelLoaded = false
                return false
            }

            if (isModelLoaded && modelSession != null && modelId == modelIdToLoad) {
                isOnlineInferencing = false
                lastEvaluatedEngine.value = "local"
                lastEvaluatedModelId.value = modelIdToLoad
                return true
            }

            if (forEvaluation) {
                currentStep.value = EvaluationStep.CHECKING_MODEL
            }
            if (env == null) {
                env = OrtEnvironment.getEnvironment()
            }
            modelId = modelIdToLoad
            isOnlineInferencing = false
            lastEvaluatedEngine.value = "local"
            lastEvaluatedModelId.value = modelIdToLoad

            Log.d("AiSnapQuestViewVM", "Starting to load model $modelId")
            if (forEvaluation) {
                currentStep.value = EvaluationStep.LOADING_MODEL
            }

            try {
                modelSession?.close()
            } catch (_: Throwable) {}
            modelSession = null

            var createdSession: OrtSession? = null
            var usingNnapi = false
            SessionOptions().use { nnapiOpts ->
                val nnapiAdded = try {
                    nnapiOpts.addNnapi()
                    true
                } catch (e: Throwable) {
                    Log.w("AiSnapQuestViewVM", "NNAPI not available: ${e.message}")
                    false
                }
                nnapiOpts.setExecutionMode(SessionOptions.ExecutionMode.SEQUENTIAL)
                nnapiOpts.setOptimizationLevel(SessionOptions.OptLevel.ALL_OPT)
                if (nnapiAdded) {
                    try {
                        createdSession = env!!.createSession(modelFile.absolutePath, nnapiOpts)
                        usingNnapi = true
                    } catch (e: Throwable) {
                        Log.w("AiSnapQuestViewVM", "Failed to create session with NNAPI, falling back to CPU: ${e.message}")
                    }
                }
            }

            if (createdSession == null) {
                SessionOptions().use { cpuOpts ->
                    cpuOpts.setExecutionMode(SessionOptions.ExecutionMode.SEQUENTIAL)
                    cpuOpts.setOptimizationLevel(SessionOptions.OptLevel.ALL_OPT)
                    createdSession = env!!.createSession(modelFile.absolutePath, cpuOpts)
                    usingNnapi = false
                }
            }

            modelSession = createdSession
            isSessionUsingNnapi = usingNnapi
            isModelDownloaded.value = true
            isModelLoaded = true
            return true
        } catch (e: Throwable) {
            if (e is CancellationException) throw e
            if (forEvaluation) {
                results.value = TaskValidationClient.ValidationResult(
                    isValid = false,
                    reason = "Failed to load model: ${e.message}"
                )
                currentStep.value = EvaluationStep.COMPLETED
            }
            isModelLoaded = false
            false
        }
    }

    private fun recreateCpuSession(environment: OrtEnvironment): OrtSession {
        try {
            modelSession?.close()
        } catch (_: Throwable) {}
        val modelFile = File(context.filesDir, "$modelId.onnx")
        val cpuSession = SessionOptions().use { cpuOpts ->
            cpuOpts.setExecutionMode(SessionOptions.ExecutionMode.SEQUENTIAL)
            cpuOpts.setOptimizationLevel(SessionOptions.OptLevel.ALL_OPT)
            environment.createSession(modelFile.absolutePath, cpuOpts)
        }
        modelSession = cpuSession
        isSessionUsingNnapi = false
        return cpuSession
    }

    fun evaluateQuest(onEvaluationComplete: () -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            resetResults()
            val settingsSp = context.getSharedPreferences("private_settings", Context.MODE_PRIVATE)
            val engine = settingsSp.getString("validation_engine", "cloud") ?: "cloud"

            if (engine == "local") {
                runOfflineInference(onEvaluationComplete)
            } else {
                runOnlineInference(onEvaluationComplete)
            }
        }
    }

    private suspend fun runOnlineInference(onEvaluationComplete: () -> Unit) {
        try {
            currentStep.value = EvaluationStep.INITIALIZING
            currentStep.value = EvaluationStep.LOADING_MODEL
            val photoFile = File(context.filesDir, AI_SNAP_PIC)
            if (!photoFile.exists()) {
                results.value = TaskValidationClient.ValidationResult(
                    isValid = false,
                    reason = "Image file not found."
                )
                currentStep.value = EvaluationStep.COMPLETED
                return
            }

            val compressedFile = resizeAndCompressImage(photoFile, 1080, 50)

            val settingsSp = context.getSharedPreferences("private_settings", Context.MODE_PRIVATE)
            val engine = settingsSp.getString("validation_engine", "cloud") ?: "cloud"
            lastEvaluatedEngine.value = engine
            lastEvaluatedModelId.value = if (engine == "gemini_api") "gemini-api" else "cloud-server"
            lastConfidenceSummary.value = null

            if (engine == "gemini_api") {
                val geminiKey = settingsSp.getString("gemini_api_key", null)
                if (geminiKey.isNullOrBlank()) {
                    results.value = TaskValidationClient.ValidationResult(
                        isValid = false,
                        reason = "Private Gemini API key is missing. Please configure it in your Profile settings."
                    )
                    currentStep.value = EvaluationStep.COMPLETED
                    return
                }
                val geminiValidator = GeminiValidator()
                geminiValidator.validateTask(
                    compressedFile,
                    aiQuest.taskDescription,
                    aiQuest.features.joinToString(","),
                    geminiKey
                ) { result ->
                    results.value = result.getOrNull() ?: TaskValidationClient.ValidationResult(
                        isValid = false,
                        reason = "Gemini validation failed: ${result.exceptionOrNull()?.message ?: "Unknown error"}"
                    )
                    currentStep.value = EvaluationStep.COMPLETED
                    if (results.value?.isValid == true) {
                        onEvaluationComplete()
                    }
                }
            } else { // "cloud"
                val token = if (userRepository.userInfo.isAnonymous) {
                    ""
                } else {
                    Supabase.supabase.auth.currentAccessTokenOrNull()?.toString() ?: ""
                }

                if (token.isEmpty()) {
                    results.value = TaskValidationClient.ValidationResult(
                        isValid = false,
                        reason = "Authentication required. Please log in or configure a private Gemini API Key / Local AI."
                    )
                    currentStep.value = EvaluationStep.COMPLETED
                    return
                }

                client.validateTask(
                    compressedFile,
                    aiQuest.taskDescription,
                    aiQuest.features.joinToString(","),
                    token
                ) { result ->
                    results.value = result.getOrNull() ?: TaskValidationClient.ValidationResult(
                        isValid = false,
                        reason = "Online validation failed: ${result.exceptionOrNull()?.message ?: "Unknown error"}"
                    )
                    currentStep.value = EvaluationStep.COMPLETED
                    if (results.value?.isValid == true) {
                        onEvaluationComplete()
                    }
                }
            }

            advanceProgressStepsUntilResult()
        } catch (e: Throwable) {
            if (e is CancellationException) throw e
            results.value = TaskValidationClient.ValidationResult(
                isValid = false,
                reason = "Online evaluation failed: ${e.message}"
            )
            currentStep.value = EvaluationStep.COMPLETED
        }
    }

    internal suspend fun advanceProgressStepsUntilResult(
        stepDelayProvider: () -> Long = { Random.nextInt(500, 2000).toLong() }
    ) {
        val allSteps = EvaluationStep.entries
        var currentStepInt = 0
        while (results.value == null) {
            delay(stepDelayProvider())
            if (results.value != null) break
            currentStep.value = allSteps[currentStepInt]
            if (currentStepInt != EvaluationStep.EVALUATING.ordinal) currentStepInt++
        }
        if (results.value != null) {
            currentStep.value = EvaluationStep.COMPLETED
        }
    }

    fun resetResults() {
        isAiEvaluating.value = true
        results.value = null
        error.value = null
        lastConfidenceSummary.value = null
        currentStep.value = EvaluationStep.INITIALIZING
    }

    private fun runSessionZeroShotPass(
        session: OrtSession,
        environment: OrtEnvironment,
        floatBuffer: FloatBuffer,
        paddedTokenIdsList: List<LongArray>,
        flatTokenIds: LongArray,
        maxLength: Int,
        padTokenId: Long
    ): FloatArray {
        var imageTensor: OnnxTensor? = null
        var textTensor: OnnxTensor? = null
        var maskTensor: OnnxTensor? = null

        return try {
            val imageShape = longArrayOf(1, 3, 224, 224)
            val pixelBuf = floatBuffer.duplicate().apply { rewind() }
            val pixelValuesType = (session.inputInfo["pixel_values"]?.info as? TensorInfo)?.type
            imageTensor = when (pixelValuesType) {
                OnnxJavaType.FLOAT16 -> {
                    val fp16Buf = Fp16Conversions.convertFloatBufferToFp16Buffer(pixelBuf)
                    OnnxTensor.createTensor(environment, fp16Buf, imageShape, OnnxJavaType.FLOAT16)
                }
                OnnxJavaType.BFLOAT16 -> {
                    val bf16Buf = Fp16Conversions.convertFloatBufferToBf16Buffer(pixelBuf)
                    OnnxTensor.createTensor(environment, bf16Buf, imageShape, OnnxJavaType.BFLOAT16)
                }
                else -> {
                    OnnxTensor.createTensor(environment, pixelBuf, imageShape)
                }
            }

            val textShape = longArrayOf(paddedTokenIdsList.size.toLong(), maxLength.toLong())
            val inputIdsType = (session.inputInfo["input_ids"]?.info as? TensorInfo)?.type
            textTensor = if (inputIdsType == OnnxJavaType.INT32) {
                val intIds = IntArray(flatTokenIds.size) { flatTokenIds[it].toInt() }
                OnnxTensor.createTensor(environment, IntBuffer.wrap(intIds), textShape)
            } else {
                OnnxTensor.createTensor(environment, LongBuffer.wrap(flatTokenIds), textShape)
            }

            maskTensor = if (session.inputNames.contains("attention_mask")) {
                val maskType = (session.inputInfo["attention_mask"]?.info as? TensorInfo)?.type
                if (maskType == OnnxJavaType.INT32) {
                    val maskInts = IntArray(flatTokenIds.size) { if (flatTokenIds[it] != padTokenId) 1 else 0 }
                    OnnxTensor.createTensor(environment, IntBuffer.wrap(maskInts), textShape)
                } else {
                    val maskLongs = LongArray(flatTokenIds.size) { if (flatTokenIds[it] != padTokenId) 1L else 0L }
                    OnnxTensor.createTensor(environment, LongBuffer.wrap(maskLongs), textShape)
                }
            } else {
                null
            }

            val inputs = mutableMapOf<String, OnnxTensor>(
                "pixel_values" to imageTensor,
                "input_ids" to textTensor
            )
            if (maskTensor != null) {
                inputs["attention_mask"] = maskTensor
            }

            val resultOutput = try {
                session.run(inputs)
            } catch (runError: Throwable) {
                if (isSessionUsingNnapi) {
                    Log.w("AiSnapQuestViewVM", "NNAPI execution failed at runtime, recreating session on CPU: ${runError.message}")
                    val cpuSession = recreateCpuSession(environment)
                    cpuSession.run(inputs)
                } else {
                    throw runError
                }
            }

            resultOutput.use { output ->
                val opt = output.get("logits_per_image")
                val namedTensor = if (opt.isPresent) opt.get() as? OnnxTensor else null
                val logitsTensor = namedTensor ?: (output.get(0) as? OnnxTensor)
                    ?: throw IllegalStateException("Model output logits tensor is null")
                extractLogitsFloatArray(logitsTensor)
            }
        } finally {
            imageTensor?.close()
            textTensor?.close()
            maskTensor?.close()
        }
    }

    private suspend fun runOfflineInference(onEvaluationComplete: () -> Unit) {
        try {
            var evalOutcome: Pair<List<String>, TaskValidationClient.ValidationResult>? = null

            sessionMutex.withLock {
                try {
                    if (isCleared || !loadModelInternal(forEvaluation = true)) {
                        return@withLock
                    }

                    lastEvaluatedEngine.value = "local"
                    lastEvaluatedModelId.value = modelId
                    currentStep.value = EvaluationStep.INITIALIZING

                    val photoFile = File(context.filesDir, AI_SNAP_PIC)
                    currentStep.value = EvaluationStep.LOADING_IMAGE

                    if (!photoFile.exists()) {
                        val msg = "Image file not found at ${photoFile.absolutePath}"
                        Log.d("AiSnapQuestViewVM", msg)
                        results.value = TaskValidationClient.ValidationResult(
                            isValid = false,
                            reason = "Image file not found."
                        )
                        currentStep.value = EvaluationStep.COMPLETED
                        return@withLock
                    }

                    val bitmap = getBitmapFromPath(photoFile.absolutePath)
                    if (bitmap == null) {
                        results.value = TaskValidationClient.ValidationResult(
                            isValid = false,
                            reason = "Failed to load bitmap from ${photoFile.absolutePath}"
                        )
                        currentStep.value = EvaluationStep.COMPLETED
                        return@withLock
                    }

                    val preparedInputs = try {
                        currentStep.value = EvaluationStep.PREPROCESSING

                        val queries = sanitizeZeroShotQueries(aiQuest.features, aiQuest.taskDescription)
                        if (queries.isEmpty()) {
                            results.value = TaskValidationClient.ValidationResult(
                                isValid = false,
                                reason = "Quest task description and features are empty."
                            )
                            currentStep.value = EvaluationStep.COMPLETED
                            return@withLock
                        }

                        currentStep.value = EvaluationStep.TOKENIZING

                        val tokenIdsList = try {
                            tokenizeText(context, queries)
                        } catch (e: Throwable) {
                            if (e is CancellationException) throw e
                            results.value = TaskValidationClient.ValidationResult(
                                isValid = false,
                                reason = "Tokenization failed: ${e.message}"
                            )
                            currentStep.value = EvaluationStep.COMPLETED
                            return@withLock
                        }

                        val maxLength = 64
                        val padTokenId = 0L
                        val eosTokenId = 1L
                        val paddedTokenIdsList = tokenIdsList.map { padTokenIds(it, maxLength, padTokenId, eosTokenId) }
                        val flatTokenIds = paddedTokenIdsList.flatMap { it.asList() }.toLongArray()
                        val floatBuffer = preprocessBitmapToFloatBuffer(bitmap)
                        Triple(queries, paddedTokenIdsList, flatTokenIds) to floatBuffer
                    } finally {
                        if (!bitmap.isRecycled) {
                            bitmap.recycle()
                        }
                    }

                    val (queryData, floatBuffer) = preparedInputs
                    val (queries, paddedTokenIdsList, flatTokenIds) = queryData
                    val maxLength = 64
                    val padTokenId = 0L

                    val initialSession = modelSession
                    val environment = env
                    if (initialSession == null || environment == null) {
                        results.value = TaskValidationClient.ValidationResult(
                            isValid = false,
                            reason = "Model session is not initialized"
                        )
                        currentStep.value = EvaluationStep.COMPLETED
                        return@withLock
                    }

                    currentStep.value = EvaluationStep.EVALUATING

                    val logitsArray = runSessionZeroShotPass(
                        session = initialSession,
                        environment = environment,
                        floatBuffer = floatBuffer,
                        paddedTokenIdsList = paddedTokenIdsList,
                        flatTokenIds = flatTokenIds,
                        maxLength = maxLength,
                        padTokenId = padTokenId
                    )

                    val explicitFeaturesCount = aiQuest.features.count { it.isNotBlank() }
                    val (initialDetected, initialResult) = evaluateZeroShotFeatures(
                        queries = queries,
                        hasExplicitFeatures = explicitFeaturesCount > 0,
                        taskDescription = aiQuest.taskDescription,
                        logitsArray = logitsArray,
                        threshold = MINIMUM_ZERO_SHOT_THRESHOLD
                    )

                    var detectedFeatures = initialDetected
                    var fallbackResult = initialResult
                    lastEvaluatedEngine.value = "local"
                    lastEvaluatedModelId.value = modelId
                    lastConfidenceSummary.value = formatConfidenceScoresSummary(queries, logitsArray)

                    // Zero-download local fallback: if a lower-precision model failed and FP16 is already on disk,
                    // automatically re-evaluate the photo using FP16 before returning a failure.
                    val downloadedOnDisk = context.filesDir.listFiles()
                        ?.filter { it.name.endsWith(".onnx") && it.length() > 0L }
                        ?.map { it.nameWithoutExtension }
                        ?.toSet()
                        ?: emptySet()

                    if (!isCleared && shouldAttemptLocalFp16Fallback(
                            activeModelId = modelId,
                            isCurrentResultValid = fallbackResult.isValid,
                            downloadedModelIdsOnDisk = downloadedOnDisk
                        )
                    ) {
                        val previousModelId = modelId
                        val previousConfidence = lastConfidenceSummary.value
                        Log.i(
                            "AiSnapQuestViewVM",
                            "Local verification failed on $previousModelId ($previousConfidence). " +
                                "Found $FP16_MODEL_ID already on disk; re-evaluating locally."
                        )
                        currentStep.value = EvaluationStep.LOADING_MODEL
                        if (loadModelInternal(forEvaluation = false, overrideModelId = FP16_MODEL_ID)) {
                            val fp16Session = modelSession
                            val fp16Env = env
                            if (fp16Session != null && fp16Env != null) {
                                try {
                                    currentStep.value = EvaluationStep.EVALUATING
                                    val fp16Logits = runSessionZeroShotPass(
                                        session = fp16Session,
                                        environment = fp16Env,
                                        floatBuffer = floatBuffer,
                                        paddedTokenIdsList = paddedTokenIdsList,
                                        flatTokenIds = flatTokenIds,
                                        maxLength = maxLength,
                                        padTokenId = padTokenId
                                    )
                                    context.getSharedPreferences("models", Context.MODE_PRIVATE).edit(commit = true) {
                                        putString("selected_one_shot_model", FP16_MODEL_ID)
                                        putBoolean("is_downloaded_$FP16_MODEL_ID", true)
                                    }
                                    val (fp16Detected, fp16Result) = evaluateZeroShotFeatures(
                                        queries = queries,
                                        hasExplicitFeatures = explicitFeaturesCount > 0,
                                        taskDescription = aiQuest.taskDescription,
                                        logitsArray = fp16Logits,
                                        threshold = MINIMUM_ZERO_SHOT_THRESHOLD
                                    )
                                    detectedFeatures = fp16Detected
                                    fallbackResult = fp16Result
                                    lastEvaluatedModelId.value = FP16_MODEL_ID
                                    lastConfidenceSummary.value = formatConfidenceScoresSummary(queries, fp16Logits)
                                } catch (fallbackError: Throwable) {
                                    if (fallbackError is CancellationException) throw fallbackError
                                    Log.w(
                                        "AiSnapQuestViewVM",
                                        "On-disk $FP16_MODEL_ID fallback failed: ${fallbackError.message}"
                                    )
                                    lastEvaluatedModelId.value = previousModelId
                                    lastConfidenceSummary.value = previousConfidence
                                }
                            }
                        } else {
                            lastEvaluatedModelId.value = previousModelId
                            lastConfidenceSummary.value = previousConfidence
                        }
                    }

                    evalOutcome = detectedFeatures to fallbackResult
                } finally {
                    if (isCleared) {
                        closeSessionResources()
                    }
                }
            }

            val outcome = evalOutcome ?: return
            val (detectedFeatures, fallbackResult) = outcome

            // Local Gemini Nano reasoning pipeline integration (when required visual features are detected)
            if (fallbackResult.isValid && detectedFeatures.isNotEmpty() && localNano.isAvailable()) {
                val nanoResult = localNano.validateTaskLocally(aiQuest.taskDescription, detectedFeatures)
                if (nanoResult.isValid || !nanoResult.reason.startsWith("On-device AI reasoning error")) {
                    results.value = nanoResult
                    currentStep.value = EvaluationStep.COMPLETED
                    if (nanoResult.isValid) {
                        onEvaluationComplete()
                    }
                    return
                }
                Log.w("AiSnapQuestViewVM", "Gemini Nano execution failed, falling back to SigLIP 2 zero-shot verification: ${nanoResult.reason}")
            }

            // Fallback: Default SigLIP verification logic
            results.value = fallbackResult
            currentStep.value = EvaluationStep.COMPLETED

            if (fallbackResult.isValid) {
                onEvaluationComplete()
            }

        } catch (e: Throwable) {
            if (e is CancellationException) throw e
            error.value = "Evaluation failed: ${e.message}"
            results.value = TaskValidationClient.ValidationResult(
                isValid = false,
                reason = "Evaluation failed: ${e.message}"
            )
            currentStep.value = EvaluationStep.COMPLETED
        }
    }

    fun buildAiMismatchDiagnosticPreview(
        userNote: String = "",
        attachPhoto: Boolean = true,
        includeUserId: Boolean = false
    ): AiMismatchReport {
        val settingsSp = context.getSharedPreferences("private_settings", Context.MODE_PRIVATE)
        val modelsSp = context.getSharedPreferences("models", Context.MODE_PRIVATE)
        val activeEngine = lastEvaluatedEngine.value.ifBlank {
            settingsSp.getString("validation_engine", "cloud") ?: "cloud"
        }
        val activeModelId = lastEvaluatedModelId.value ?: when (activeEngine) {
            "local" -> modelsSp.getString("selected_one_shot_model", "none") ?: "none"
            "gemini_api" -> "gemini-api"
            else -> "cloud-server"
        }
        val photoExists = File(context.filesDir, AI_SNAP_PIC).exists()
        val questTitle = try {
            commonQuestInfo.title
        } catch (_: UninitializedPropertyAccessException) {
            ""
        }
        return AiMismatchReport(
            quest_title = questTitle,
            task_description = aiQuest.taskDescription,
            required_features = aiQuest.features.filter { it.isNotBlank() },
            validation_engine = activeEngine,
            model_id = activeModelId,
            confidence_scores = lastConfidenceSummary.value ?: "",
            failure_reason = results.value?.reason ?: error.value ?: "",
            user_note = userNote.trim(),
            photo_attached = attachPhoto && photoExists,
            device_model = "${Build.MANUFACTURER} ${Build.MODEL}".trim(),
            android_api_level = Build.VERSION.SDK_INT,
            app_version = BuildConfig.VERSION_NAME,
            user_id = if (includeUserId) Supabase.currentUserIdOrNull() else null
        )
    }

    @SuppressLint("MissingPermission")
    private fun isDeviceOnline(): Boolean {
        return try {
            context.isOnline()
        } catch (_: Throwable) {
            false
        }
    }

    fun submitAiMismatchFeedback(
        userNote: String,
        attachPhoto: Boolean,
        callerContext: Context = context,
        onComplete: (submittedViaCloud: Boolean) -> Unit
    ) {
        if (isSubmittingFeedback.value) return
        viewModelScope.launch(Dispatchers.IO) {
            isSubmittingFeedback.value = true
            val report = buildAiMismatchDiagnosticPreview(userNote, attachPhoto, includeUserId = true)
            var submittedViaCloud = false
            try {
                if (isDeviceOnline() && Supabase.isInitialized()) {
                    var uploadedPhotoPath: String? = null
                    val photoFile = File(context.filesDir, AI_SNAP_PIC)
                    if (attachPhoto && photoFile.exists()) {
                        val compressedFile = resizeAndCompressImage(photoFile, maxSize = 1080, quality = 70)
                        val imageBytes = compressedFile.readBytes()
                        val ownerSegment = Supabase.currentUserIdOrNull() ?: "anonymous"
                        val objectPath = "$ownerSegment/${UUID.randomUUID()}.jpg"
                        Supabase.supabase.storage
                            .from("ai_feedback_images")
                            .upload(
                                path = objectPath,
                                data = imageBytes,
                                options = {
                                    upsert = false
                                }
                            )
                        uploadedPhotoPath = objectPath
                    }
                    val finalReport = report.copy(photo_storage_path = uploadedPhotoPath)
                    Supabase.supabase.postgrest["ai_feedback"].insert(finalReport)
                    submittedViaCloud = true
                }
            } catch (e: Throwable) {
                if (e is CancellationException) {
                    isSubmittingFeedback.value = false
                    throw e
                }
                Log.w("AiSnapQuestViewVM", "Supabase AI feedback submission failed, using share sheet fallback: ${e.message}")
            }

            withContext(Dispatchers.Main) {
                isSubmittingFeedback.value = false
                if (submittedViaCloud) {
                    Toast.makeText(
                        callerContext,
                        "AI mismatch report submitted. Thank you!",
                        Toast.LENGTH_SHORT
                    ).show()
                    onComplete(true)
                } else {
                    shareAiMismatchReportViaIntent(
                        report = report,
                        attachPhoto = attachPhoto,
                        callerContext = callerContext
                    )
                    onComplete(false)
                }
            }
        }
    }

    fun shareAiMismatchReportViaIntent(
        report: AiMismatchReport,
        attachPhoto: Boolean,
        callerContext: Context = context
    ) {
        val shareText = formatAiMismatchShareText(report)
        val photoFile = File(context.filesDir, AI_SNAP_PIC)
        val shouldAttach = attachPhoto && photoFile.exists()

        val sendIntent = if (shouldAttach) {
            val photoUri = FileProvider.getUriForFile(
                callerContext,
                "${callerContext.packageName}.provider",
                photoFile
            )
            Intent(Intent.ACTION_SEND).apply {
                type = "image/jpeg"
                putExtra(Intent.EXTRA_SUBJECT, "AmniQuest AI Mismatch Report: ${report.quest_title.ifBlank { "AI Snap" }}")
                putExtra(Intent.EXTRA_TEXT, shareText)
                putExtra(Intent.EXTRA_STREAM, photoUri)
                clipData = ClipData.newRawUri("Quest Photo", photoUri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        } else {
            Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, "AmniQuest AI Mismatch Report: ${report.quest_title.ifBlank { "AI Snap" }}")
                putExtra(Intent.EXTRA_TEXT, shareText)
            }
        }

        val chooser = Intent.createChooser(sendIntent, "Send AI Mismatch Report").apply {
            if (callerContext !is Activity) {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (shouldAttach) {
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }

        try {
            callerContext.startActivity(chooser)
        } catch (e: Throwable) {
            Log.e("AiSnapQuestViewVM", "Failed to open share sheet for AI mismatch report", e)
            Toast.makeText(callerContext, "No app available to share report", Toast.LENGTH_SHORT).show()
        }
    }

    private fun closeSessionResources() {
        try {
            try {
                modelSession?.close()
            } catch (e: Throwable) {
                Log.e("AiEvaluation", "Failed to close modelSession", e)
            }
            try {
                env?.close()
            } catch (e: Throwable) {
                Log.e("AiEvaluation", "Failed to close OrtEnvironment", e)
            }
        } finally {
            modelSession = null
            env = null
            modelId = null
            isModelLoaded = false
            isSessionUsingNnapi = false
        }
    }

    override fun onCleared() {
        super.onCleared()
        isCleared = true
        if (sessionMutex.tryLock()) {
            try {
                closeSessionResources()
            } finally {
                sessionMutex.unlock()
            }
        }
    }
}

fun resizeAndCompressImage(file: File, maxSize: Int = 1080, quality: Int = 70): File {
    val bitmap = android.graphics.BitmapFactory.decodeFile(file.absolutePath)
        ?: return file
    if (bitmap.width <= 0 || bitmap.height <= 0) {
        bitmap.recycle()
        return file
    }

    val ratio = bitmap.width.toFloat() / bitmap.height.toFloat()
    val width: Int
    val height: Int
    if (ratio > 1) {
        width = maxSize
        height = (maxSize / ratio).toInt().coerceAtLeast(1)
    } else {
        height = maxSize
        width = (maxSize * ratio).toInt().coerceAtLeast(1)
    }

    val scaledBitmap = bitmap.scale(width, height)
    val compressedFile = File(file.parent, "compressed_upload.jpg")
    FileOutputStream(compressedFile).use { out ->
        scaledBitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, quality, out)
        out.flush()
    }
    if (scaledBitmap != bitmap) {
        scaledBitmap.recycle()
    }
    bitmap.recycle()

    return compressedFile
}
