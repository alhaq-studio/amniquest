package com.alhaq.amniquest.core.utils

import ai.onnxruntime.OnnxJavaType
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.platform.Fp16Conversions
import com.alhaq.amniquest.backend.TaskValidationClient
import kotlinx.serialization.Serializable
import java.nio.DoubleBuffer
import java.nio.FloatBuffer
import java.nio.ShortBuffer
import java.util.Locale
import kotlin.math.exp

const val MINIMUM_ZERO_SHOT_THRESHOLD = 0.08f
const val FP16_MODEL_ID = "SIGLIP2_FP16"
const val TIP_SWITCH_TO_FP16_MODEL =
    "Tip: If your photo is clear, try switching to the FP16 High-Accuracy Local Model (via Select Model), cropping closer to the main subject, or using Cloud / Private Gemini API."
const val TIP_ALREADY_ON_FP16_MODEL =
    "Tip: Try taking a closer photo with only the target object in frame, or switch to Cloud / Private Gemini API."

@Serializable
data class AiMismatchReport(
    val quest_title: String,
    val task_description: String,
    val required_features: List<String>,
    val validation_engine: String,
    val model_id: String,
    val confidence_scores: String,
    val failure_reason: String,
    val user_note: String,
    val photo_attached: Boolean,
    val photo_storage_path: String? = null,
    val device_model: String,
    val android_api_level: Int,
    val app_version: String,
    val user_id: String? = null
)

internal fun isFp16ModelId(modelId: String?): Boolean {
    if (modelId.isNullOrBlank()) return false
    val upper = modelId.uppercase(Locale.US)
    return upper.contains("FP16") && !upper.contains("Q4")
}

internal fun resolveLocalFailureGuidanceTip(activeModelId: String?): String {
    return if (isFp16ModelId(activeModelId)) {
        TIP_ALREADY_ON_FP16_MODEL
    } else {
        TIP_SWITCH_TO_FP16_MODEL
    }
}

internal fun shouldAttemptLocalFp16Fallback(
    activeModelId: String?,
    isCurrentResultValid: Boolean,
    downloadedModelIdsOnDisk: Set<String>
): Boolean {
    return !isCurrentResultValid &&
        !isFp16ModelId(activeModelId) &&
        downloadedModelIdsOnDisk.contains(FP16_MODEL_ID)
}

internal fun formatConfidenceScoresSummary(
    queries: List<String>,
    logitsArray: FloatArray
): String {
    if (queries.isEmpty() || logitsArray.isEmpty()) return ""
    return queries.indices.joinToString(", ") { i ->
        val logit = if (i < logitsArray.size) logitsArray[i] else -100f
        val prob = sigmoidProbability(logit)
        val percentage = "%.1f%%".format(Locale.US, prob * 100f)
        "${queries[i]}: $percentage"
    }
}

internal fun formatAiMismatchShareText(report: AiMismatchReport): String {
    val featuresText = if (report.required_features.isEmpty()) {
        "(None - task description only)"
    } else {
        report.required_features.joinToString(", ")
    }
    val confidenceText = report.confidence_scores.ifBlank { "N/A" }
    val noteText = report.user_note.ifBlank { "N/A" }
    return buildString {
        appendLine("AmniQuest AI Mismatch Report")
        appendLine("----------------------------")
        appendLine("Quest Title: ${report.quest_title.ifBlank { "(Untitled Quest)" }}")
        appendLine("Task Description: ${report.task_description.ifBlank { "N/A" }}")
        appendLine("Required Features: $featuresText")
        appendLine("Validation Engine: ${report.validation_engine}")
        appendLine("Selected Model ID: ${report.model_id.ifBlank { "N/A" }}")
        appendLine("Confidence Scores: $confidenceText")
        appendLine("Failure Reason: ${report.failure_reason.ifBlank { "N/A" }}")
        appendLine("User Note: $noteText")
        appendLine("Photo Attached: ${if (report.photo_attached) "Yes" else "No"}")
        appendLine("Device: ${report.device_model} (Android API ${report.android_api_level})")
        append("App Version: ${report.app_version}")
    }
}

/**
 * Computes numerically stable sigmoid probability for a given logit.
 */
internal fun sigmoidProbability(logit: Float): Float {
    if (logit < -88.0f) return 0.0f
    if (logit > 88.0f) return 1.0f
    return (1.0 / (1.0 + exp(-logit.toDouble()))).toFloat()
}

/**
 * Sanitizes zero-shot feature queries, falling back to [taskDescription] when explicit features are absent.
 */
internal fun sanitizeZeroShotQueries(features: List<String>, taskDescription: String): List<String> {
    val cleanedFeatures = features.map { it.trim() }.filter { it.isNotEmpty() }
    if (cleanedFeatures.isNotEmpty()) return cleanedFeatures
    val cleanedTask = taskDescription.trim()
    return if (cleanedTask.isNotEmpty()) listOf(cleanedTask) else emptyList()
}

/**
 * Extracts a [FloatArray] from an ONNX output logits tensor, supporting FLOAT, FLOAT16, BFLOAT16, and DOUBLE.
 */
internal fun extractLogitsFloatArray(logitsTensor: OnnxTensor): FloatArray {
    val type = logitsTensor.info.type
    return extractLogitsFromBuffers(
        type = type,
        floatBuffer = logitsTensor.floatBuffer,
        shortBuffer = logitsTensor.shortBuffer,
        doubleBuffer = logitsTensor.doubleBuffer
    )
}

/**
 * Buffer-level logits extraction helper supporting FLOAT, FLOAT16, BFLOAT16, and DOUBLE.
 */
internal fun extractLogitsFromBuffers(
    type: OnnxJavaType,
    floatBuffer: FloatBuffer?,
    shortBuffer: ShortBuffer?,
    doubleBuffer: DoubleBuffer? = null
): FloatArray {
    val resolvedFloatBuffer: FloatBuffer? = when (type) {
        OnnxJavaType.FLOAT -> floatBuffer
        OnnxJavaType.FLOAT16 -> shortBuffer?.let { Fp16Conversions.convertFp16BufferToFloatBuffer(it.duplicate()) }
        OnnxJavaType.BFLOAT16 -> shortBuffer?.let { Fp16Conversions.convertBf16BufferToFloatBuffer(it.duplicate()) }
        OnnxJavaType.DOUBLE -> {
            if (doubleBuffer == null) {
                throw IllegalStateException("Unsupported logits tensor type: $type (doubleBuffer is null)")
            }
            val copy = doubleBuffer.duplicate()
            val array = FloatArray(copy.remaining())
            for (i in array.indices) {
                array[i] = copy.get().toFloat()
            }
            return array
        }
        else -> floatBuffer
    }

    if (resolvedFloatBuffer == null) {
        throw IllegalStateException("Unsupported logits tensor type: $type")
    }

    val copy = resolvedFloatBuffer.duplicate()
    val array = FloatArray(copy.remaining())
    copy.get(array)
    return array
}

/**
 * Evaluates zero-shot detection logits against queries and confidence thresholds.
 * Returns the list of matched query strings and the formatted [TaskValidationClient.ValidationResult].
 */
internal fun evaluateZeroShotFeatures(
    queries: List<String>,
    hasExplicitFeatures: Boolean,
    taskDescription: String,
    logitsArray: FloatArray,
    threshold: Float = MINIMUM_ZERO_SHOT_THRESHOLD
): Pair<List<String>, TaskValidationClient.ValidationResult> {
    if (queries.isEmpty() || logitsArray.isEmpty()) {
        return emptyList<String>() to TaskValidationClient.ValidationResult(
            isValid = false,
            reason = "Empty logits produced during zero-shot evaluation."
        )
    }

    val detectedFeatures = mutableListOf<String>()
    val detectedWithConfidence = mutableListOf<String>()
    val allScores = StringBuilder()

    for (i in queries.indices) {
        val logit = if (i < logitsArray.size) logitsArray[i] else -100f
        val prob = sigmoidProbability(logit)
        val percentage = "%.1f%%".format(Locale.US, prob * 100f)
        if (prob >= threshold) {
            detectedFeatures.add(queries[i])
            detectedWithConfidence.add("${queries[i]} (Confidence: $percentage)")
        }
        allScores.append("\n- ${queries[i]}: $percentage (Logit: ${"%.2f".format(Locale.US, logit)})")
    }

    val isSuccess = if (hasExplicitFeatures) {
        detectedFeatures.size == queries.size && queries.isNotEmpty()
    } else {
        detectedFeatures.isNotEmpty()
    }

    val fallbackResult = TaskValidationClient.ValidationResult(
        isValid = isSuccess,
        reason = if (isSuccess) {
            "Verified locally via SigLIP 2!\nMatched features:\n${detectedWithConfidence.joinToString("\n") { "[OK] $it" }}"
        } else {
            "Could not confidently verify all required features for '$taskDescription'.\n\nDetected Scores:$allScores"
        }
    )

    return detectedFeatures to fallbackResult
}
