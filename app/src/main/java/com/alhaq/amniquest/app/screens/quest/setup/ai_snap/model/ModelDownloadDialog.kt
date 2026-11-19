package com.alhaq.amniquest.app.screens.quest.setup.ai_snap.model

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.core.content.edit
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.alhaq.amniquest.BuildConfig
import com.alhaq.amniquest.backed.fetchUrlContent
import com.alhaq.amniquest.core.workers.FileDownloadWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File

@kotlinx.serialization.Serializable
data class DownloadableModel(
    val sizeMB: Int,
    val accuracy: String,
    val id: String,
    val url: String,
    val version: String,
    val isRecommended: Boolean = false,
    val isOnline: Boolean = false,
    val displayName: String = "",
    val description: String = ""
)

val DEFAULT_MODEL_CATALOG = listOf(
    DownloadableModel(
        id = "online",
        url = "",
        sizeMB = 0,
        accuracy = "HIGHEST",
        version = "1.0.0",
        isRecommended = true,
        isOnline = true,
        displayName = "Cloud / Private Gemini API (Online)",
        description = "Requires online access and cloud authentication / Gemini API Key. Best validation accuracy."
    ),
    DownloadableModel(
        id = "SIGLIP2_FP16",
        url = "https://huggingface.co/onnx-community/siglip2-base-patch16-224-ONNX/resolve/main/onnx/model_fp16.onnx",
        sizeMB = 715,
        accuracy = "Recommended (90%+ Accuracy)",
        version = "1.0.0",
        isRecommended = true,
        isOnline = false,
        displayName = "FP16 High-Precision (~715 MB)",
        description = "16-bit precision handles complex angles and multiple objects in frame best. Completely offline."
    ),
    DownloadableModel(
        id = "SIGLIP2_INT8",
        url = "https://huggingface.co/onnx-community/siglip2-base-patch16-224-ONNX/resolve/main/onnx/model_int8.onnx",
        sizeMB = 350,
        accuracy = "Medium Accuracy (Compact)",
        version = "1.0.0",
        isRecommended = false,
        isOnline = false,
        displayName = "Standard 8-Bit (~350 MB)",
        description = "Compact 8-bit quantized model. Requires close-up, uncluttered framing; may miss targets when multiple objects are in frame."
    ),
    DownloadableModel(
        id = "SIGLIP2_FULL",
        url = "https://huggingface.co/onnx-community/siglip2-base-patch16-224-ONNX/resolve/main/onnx/model.onnx",
        sizeMB = 1500,
        accuracy = "High Accuracy (Heavy Storage / RAM)",
        version = "1.0.0",
        isRecommended = false,
        isOnline = false,
        displayName = "FP32 Uncompressed (~1500 MB)",
        description = "Uncompressed 32-bit weights (~1.5 GB). High memory footprint; FP16 High-Precision (~715 MB) is recommended instead."
    ),
    DownloadableModel(
        id = "SIGLIP2_Q4F16",
        url = "https://huggingface.co/onnx-community/siglip2-base-patch16-224-ONNX/resolve/main/onnx/model_q4f16.onnx",
        sizeMB = 497,
        accuracy = "Experimental / Low Accuracy (Simple close-ups only)",
        version = "1.0.0",
        isRecommended = false,
        isOnline = false,
        displayName = "4-Bit Quantized (~497 MB)",
        description = "Experimental 4-bit weight quantization. Simple single-object close-ups only; complex tasks may fail due to quantization loss."
    ),
    DownloadableModel(
        id = "SIGLIP2_UINT8",
        url = "https://huggingface.co/onnx-community/siglip2-base-patch16-224-ONNX/resolve/main/onnx/model_uint8.onnx",
        sizeMB = 350,
        accuracy = "Experimental / Low Accuracy (Simple close-ups only)",
        version = "1.0.0",
        isRecommended = false,
        isOnline = false,
        displayName = "Unsigned 8-Bit (~350 MB)",
        description = "Unsigned 8-bit quantization. Simple single-object close-ups only; complex tasks or multiple objects in frame may fail."
    )
)

private val catalogJson = Json {
    ignoreUnknownKeys = true
}

internal fun resolveModelCatalog(remoteJson: String?): List<DownloadableModel> {
    if (remoteJson.isNullOrBlank()) return DEFAULT_MODEL_CATALOG
    return try {
        val parsed = catalogJson.decodeFromString(
            ListSerializer(DownloadableModel.serializer()),
            remoteJson
        )
        if (parsed.isEmpty()) return DEFAULT_MODEL_CATALOG

        val remoteById = LinkedHashMap<String, DownloadableModel>()
        for (model in parsed) {
            remoteById[model.id] = model
        }

        val resolved = ArrayList<DownloadableModel>(DEFAULT_MODEL_CATALOG.size + parsed.size)
        val knownIds = HashSet<String>(DEFAULT_MODEL_CATALOG.size)

        // Preserve canonical ordering, labels, and precision metadata for built-in models
        // while adopting updated versions/URLs from the remote manifest.
        for (defaultModel in DEFAULT_MODEL_CATALOG) {
            knownIds.add(defaultModel.id)
            val remoteModel = remoteById[defaultModel.id]
            if (remoteModel != null) {
                resolved.add(
                    defaultModel.copy(
                        version = remoteModel.version.ifBlank { defaultModel.version },
                        url = remoteModel.url.ifBlank { defaultModel.url }
                    )
                )
            } else {
                resolved.add(defaultModel)
            }
        }

        for (remoteModel in remoteById.values) {
            if (!knownIds.contains(remoteModel.id)) {
                resolved.add(remoteModel)
            }
        }

        resolved
    } catch (e: Exception) {
        try {
            Log.w("ModelDownloadDialog", "Failed to parse remote catalog, using offline fallback: ${e.message}")
        } catch (_: Throwable) {}
        DEFAULT_MODEL_CATALOG
    }
}

internal fun resolveActiveLocalModelId(
    selectedModel: String?,
    downloadingModel: String?,
    downloadedModelIdsOnDisk: Set<String>
): String? {
    val preferredFallback = DEFAULT_MODEL_CATALOG
        .asSequence()
        .filter { !it.isOnline }
        .map { it.id }
        .firstOrNull { downloadedModelIdsOnDisk.contains(it) }
        ?: downloadedModelIdsOnDisk.sorted().firstOrNull()
    if (!selectedModel.isNullOrBlank() && selectedModel != "online" && downloadedModelIdsOnDisk.contains(selectedModel)) {
        return selectedModel
    }
    if (!downloadingModel.isNullOrBlank() && downloadingModel != "online") {
        return downloadingModel
    }
    if (!selectedModel.isNullOrBlank() && selectedModel != "online") {
        return preferredFallback ?: selectedModel
    }
    return preferredFallback
}

internal fun formatLocalModelDisplayLabel(
    activeModelId: String?,
    downloadingModelId: String?,
    isOnDisk: Boolean,
    catalog: List<DownloadableModel> = DEFAULT_MODEL_CATALOG
): String {
    return when {
        activeModelId == null -> "None (Select Model)"
        !downloadingModelId.isNullOrBlank() && activeModelId == downloadingModelId && !isOnDisk ->
            "$activeModelId (Downloading...)"
        !isOnDisk -> "$activeModelId (Not Downloaded)"
        else -> {
            val friendlyName = catalog
                .firstOrNull { it.id == activeModelId }
                ?.displayName
                ?.ifBlank { null }
            if (friendlyName != null) "$friendlyName ($activeModelId)" else activeModelId
        }
    }
}

internal fun needsTokenizerDownload(isMarkedDownloaded: Boolean, tokenizerFile: File): Boolean =
    !isMarkedDownloaded || !tokenizerFile.exists() || !tokenizerFile.isFile || tokenizerFile.length() <= 0L

private fun ensureTokenizerDownloaded(context: Context) {
    val modelSp = context.getSharedPreferences("models", Context.MODE_PRIVATE)
    val isTokenizerDownloaded = modelSp.getBoolean("is_downloaded_tokenizer", false)
    val tokenizerFile = File(context.filesDir, "tokenizer.model")
    if (needsTokenizerDownload(isTokenizerDownloaded, tokenizerFile)) {
        FileDownloadWorker.enqueueTokenizerDownload(context)
    }
}

@Composable
fun ModelDownloadDialog(
    allowSkipping: Boolean = true,
    modelDownloadDialogVisible: MutableState<Boolean>,
) {
    val context = LocalContext.current
    val sp = remember { context.getSharedPreferences("models", Context.MODE_PRIVATE) }
    val privateSp = remember { context.getSharedPreferences("private_settings", Context.MODE_PRIVATE) }
    var currentModel by remember { mutableStateOf(sp.getString("selected_one_shot_model", null)) }
    var isModelDownloading by remember { mutableStateOf(sp.contains("downloading")) }
    var prefsRevision by remember { mutableIntStateOf(0) }
    var modelStates by remember {
        mutableStateOf(DEFAULT_MODEL_CATALOG.map { mutableStateOf(it) })
    }

    DisposableEffect(sp) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { prefs, _ ->
            currentModel = prefs.getString("selected_one_shot_model", null)
            isModelDownloading = prefs.contains("downloading")
            prefsRevision++
        }
        sp.registerOnSharedPreferenceChangeListener(listener)
        onDispose {
            sp.unregisterOnSharedPreferenceChangeListener(listener)
        }
    }

    LaunchedEffect(Unit) {
        val existingModel = sp.getString("selected_one_shot_model", null)
        if (existingModel == null && !BuildConfig.IS_FDROID) {
            currentModel = "online"
            sp.edit(commit = true) {
                putString("selected_one_shot_model", "online")
            }
        }
        val engine = privateSp.getString("validation_engine", "cloud") ?: "cloud"
        if (!allowSkipping && currentModel == null && engine == "local") {
            modelDownloadDialogVisible.value = true
        }
    }

    LaunchedEffect(modelDownloadDialogVisible.value) {
        val engine = privateSp.getString("validation_engine", "cloud") ?: "cloud"
        val downloadedOnDisk = context.filesDir.listFiles()
            ?.filter { it.name.endsWith(".onnx") && it.length() > 0L }
            ?.map { it.nameWithoutExtension }
            ?.toSet()
            ?: emptySet()

        if (engine == "local") {
            val resolvedLocal = resolveActiveLocalModelId(
                selectedModel = sp.getString("selected_one_shot_model", null),
                downloadingModel = sp.getString("downloading", null),
                downloadedModelIdsOnDisk = downloadedOnDisk
            )
            if (resolvedLocal != null && sp.getString("selected_one_shot_model", null) == "online") {
                sp.edit(commit = true) {
                    putString("selected_one_shot_model", resolvedLocal)
                }
            }
            currentModel = resolvedLocal
        } else {
            currentModel = sp.getString("selected_one_shot_model", "online") ?: "online"
        }

        isModelDownloading = sp.contains("downloading")
        prefsRevision++

        if (!modelDownloadDialogVisible.value) return@LaunchedEffect

        // Reconcile stale "downloading" flag if WorkManager job is no longer active
        val downloadingId = sp.getString("downloading", null)
        if (!downloadingId.isNullOrBlank()) {
            val isStillActive = withContext(Dispatchers.IO) {
                try {
                    val infos = WorkManager.getInstance(context)
                        .getWorkInfosForUniqueWork("download_${downloadingId}.onnx")
                        .get()
                    infos.any { info ->
                        info.state == WorkInfo.State.RUNNING ||
                            info.state == WorkInfo.State.ENQUEUED ||
                            info.state == WorkInfo.State.BLOCKED
                    }
                } catch (_: Throwable) {
                    false
                }
            }
            if (!isStillActive) {
                val targetFile = File(context.filesDir, "${downloadingId}.onnx")
                val targetExists = targetFile.exists() && targetFile.length() > 0L
                sp.edit(commit = true) {
                    remove("downloading")
                    if (targetExists) {
                        putBoolean("is_downloaded_$downloadingId", true)
                    }
                }
                isModelDownloading = false
                prefsRevision++
            }
        }

        val remoteJson = try {
            fetchUrlContent("https://raw.githubusercontent.com/QuestPhone/models/refs/heads/main/model_data.json")
        } catch (e: Exception) {
            Log.w("ModelDownloadDialog", "Failed to fetch remote catalog: ${e.message}")
            null
        }

        val parsedCatalog = resolveModelCatalog(remoteJson)
        modelStates = parsedCatalog.map { mutableStateOf(it) }

        // Check if any downloaded model has a version mismatch
        for (modelState in modelStates) {
            val model = modelState.value
            val localVersion = sp.getString("version_${model.id}", null)
            val isDownloaded = sp.getBoolean("is_downloaded_${model.id}", false)
            if (!model.isOnline && isDownloaded && localVersion != null && localVersion != model.version) {
                context.deleteFile("${model.id}.onnx")
                context.deleteFile("${model.id}.onnx.part")
                sp.edit(commit = true) {
                    remove("is_downloaded_${model.id}")
                    remove("version_${model.id}")
                    putString("downloading", model.id)
                }
                Toast.makeText(context, "Model ${model.id} is outdated. Re-downloading...", Toast.LENGTH_SHORT).show()

                isModelDownloading = true
                val inputData = Data.Builder()
                    .putString(FileDownloadWorker.KEY_URL, model.url)
                    .putString(FileDownloadWorker.KEY_FILE_NAME, "${model.id}.onnx")
                    .putString(FileDownloadWorker.KEY_MODEL_ID, model.id)
                    .putString(FileDownloadWorker.KEY_MODEL_VERSION, model.version)
                    .putBoolean(FileDownloadWorker.KEY_IS_ONE_SHOT, true)
                    .build()

                val downloadWork = OneTimeWorkRequestBuilder<FileDownloadWorker>()
                    .setInputData(inputData)
                    .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                    .build()

                WorkManager.getInstance(context).enqueueUniqueWork(
                    "download_${model.id}.onnx",
                    ExistingWorkPolicy.KEEP,
                    downloadWork
                )
                ensureTokenizerDownloaded(context)
            }
        }
    }

    fun onDismiss() {
        if (currentModel == null && !allowSkipping && !isModelDownloading) {
            Toast.makeText(context, "Please select a model to perform this quest", Toast.LENGTH_SHORT).show()
        }
        modelDownloadDialogVisible.value = false
    }

    if (modelDownloadDialogVisible.value) {
        // Read prefsRevision so Compose recomposes items when SharedPreferences changes
        val revision = prefsRevision
        Dialog(onDismissRequest = { onDismiss() }) {
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
                modifier = Modifier.fillMaxWidth().padding(16.dp)
            ) {
                Column(modifier = Modifier.padding(24.dp)) {
                    Text("Download Model", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(bottom = 8.dp))
                    Text(
                        "On-device AI Snap uses SigLIP 2 ONNX vision models paired with local Gemini Nano reasoning. FP16 High-Precision (~715 MB) is recommended for reliable offline detection.",
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(bottom = 20.dp)
                    )
                    if (isModelDownloading) {
                        val downloadingModelId = sp.getString("downloading", "") ?: ""
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                if (downloadingModelId.isNotEmpty()) {
                                    "Please wait while $downloadingModelId downloads."
                                } else {
                                    "Please wait while the model downloads."
                                },
                                style = MaterialTheme.typography.bodyMedium,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.padding(bottom = 8.dp)
                            )
                            TextButton(
                                onClick = {
                                    if (downloadingModelId.isNotEmpty()) {
                                        WorkManager.getInstance(context)
                                            .cancelUniqueWork("download_${downloadingModelId}.onnx")
                                    }
                                    val downloadedOnDisk = context.filesDir.listFiles()
                                        ?.filter { it.name.endsWith(".onnx") && it.length() > 0L }
                                        ?.map { it.nameWithoutExtension }
                                        ?.toSet()
                                        ?: emptySet()
                                    val fallbackModel = resolveActiveLocalModelId(
                                        selectedModel = null,
                                        downloadingModel = null,
                                        downloadedModelIdsOnDisk = downloadedOnDisk
                                    ) ?: if (!BuildConfig.IS_FDROID) "online" else null
                                    val downloadingFile = File(context.filesDir, "${downloadingModelId}.onnx")
                                    val cancelledNotOnDisk = downloadingModelId.isNotBlank() &&
                                        (!downloadingFile.exists() || downloadingFile.length() <= 0L)
                                    val wasCancelledSelected = currentModel == downloadingModelId && cancelledNotOnDisk

                                    sp.edit(commit = true) {
                                        remove("downloading")
                                        if (wasCancelledSelected) {
                                            if (fallbackModel != null) {
                                                putString("selected_one_shot_model", fallbackModel)
                                            } else {
                                                remove("selected_one_shot_model")
                                            }
                                        }
                                    }
                                    if (wasCancelledSelected) {
                                        currentModel = fallbackModel
                                        if (fallbackModel == "online") {
                                            privateSp.edit()
                                                .putString("validation_engine", "cloud")
                                                .apply()
                                        }
                                    }
                                    isModelDownloading = false
                                    prefsRevision++
                                    Toast.makeText(context, "Download cancelled", Toast.LENGTH_SHORT).show()
                                }
                            ) {
                                Text("Cancel Download", color = MaterialTheme.colorScheme.error)
                            }
                        }
                    } else {
                        if (modelStates.isEmpty()) {
                            Text("Loading Available Models")
                        }
                        LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.weight(1f, false)) {
                            items(modelStates) { modelState ->
                                val model = modelState.value
                                val modelFile = File(context.filesDir, "${model.id}.onnx")
                                val isFileDownloaded = !model.isOnline &&
                                    revision >= 0 &&
                                    sp.getBoolean("is_downloaded_${model.id}", false) &&
                                    modelFile.exists() &&
                                    modelFile.length() > 0L
                                val isAvailableToSelect = isFileDownloaded || model.isOnline
                                val localVersion = sp.getString("version_${model.id}", null)
                                val isSelected = currentModel == model.id && (isFileDownloaded || model.isOnline)
                                val needsUpdate = (!model.isOnline && isFileDownloaded && localVersion != null && localVersion != model.version)

                                Card(
                                    shape = RoundedCornerShape(12.dp),
                                    colors = CardDefaults.cardColors(
                                        containerColor = when {
                                            isSelected -> MaterialTheme.colorScheme.primaryContainer
                                            isAvailableToSelect -> MaterialTheme.colorScheme.surfaceVariant
                                            else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
                                        }
                                    ),
                                    elevation = CardDefaults.cardElevation(defaultElevation = if (isSelected) 4.dp else 2.dp),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable(enabled = !isModelDownloading) {
                                            if ((isFileDownloaded && !needsUpdate) || model.isOnline) {
                                                sp.edit(commit = true) {
                                                    putString("selected_one_shot_model", model.id)
                                                }
                                                currentModel = model.id

                                                privateSp.edit().putString(
                                                    "validation_engine",
                                                    if (model.isOnline) "cloud" else "local"
                                                ).apply()

                                                if (!model.isOnline) {
                                                    ensureTokenizerDownloaded(context)
                                                }
                                                Toast.makeText(context, "AI engine switched to ${if (model.isOnline) "Cloud Server" else "Local AI (${model.id})"}", Toast.LENGTH_SHORT).show()
                                            } else {
                                                sp.edit(commit = true) {
                                                    putString("downloading", model.id)
                                                    putString("selected_one_shot_model", model.id)
                                                }
                                                currentModel = model.id
                                                privateSp.edit().putString("validation_engine", "local").apply()

                                                isModelDownloading = true
                                                val inputData = Data.Builder()
                                                    .putString(FileDownloadWorker.KEY_URL, model.url)
                                                    .putString(FileDownloadWorker.KEY_FILE_NAME, "${model.id}.onnx")
                                                    .putString(FileDownloadWorker.KEY_MODEL_ID, model.id)
                                                    .putString(FileDownloadWorker.KEY_MODEL_VERSION, model.version)
                                                    .putBoolean(FileDownloadWorker.KEY_IS_ONE_SHOT, true)
                                                    .build()

                                                val workRequest = OneTimeWorkRequestBuilder<FileDownloadWorker>()
                                                    .setInputData(inputData)
                                                    .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                                                    .build()

                                                WorkManager.getInstance(context).enqueueUniqueWork(
                                                    "download_${model.id}.onnx",
                                                    ExistingWorkPolicy.KEEP,
                                                    workRequest
                                                )
                                                ensureTokenizerDownloaded(context)

                                                Toast.makeText(context, "Download Started", Toast.LENGTH_SHORT).show()
                                            }
                                        }
                                ) {
                                    Column(modifier = Modifier.padding(16.dp)) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Column(modifier = Modifier.weight(1f)) {
                                                if (model.isRecommended) {
                                                    Text(
                                                        "Recommended",
                                                        style = MaterialTheme.typography.labelMedium,
                                                        fontWeight = FontWeight.SemiBold,
                                                        color = MaterialTheme.colorScheme.primary
                                                    )
                                                }
                                                Text(
                                                    text = model.displayName.ifBlank { model.id },
                                                    style = MaterialTheme.typography.bodyLarge,
                                                    fontWeight = FontWeight.SemiBold,
                                                    color = if (isSelected)
                                                        MaterialTheme.colorScheme.onPrimaryContainer
                                                    else MaterialTheme.colorScheme.onSurface
                                                )
                                                if (!model.isOnline && model.displayName.isNotBlank() && model.displayName != model.id) {
                                                    Text(
                                                        text = "ID: ${model.id}",
                                                        style = MaterialTheme.typography.labelSmall,
                                                        color = if (isSelected)
                                                            MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.65f)
                                                        else MaterialTheme.colorScheme.outline
                                                    )
                                                }
                                                Text(
                                                    "Accuracy: ${model.accuracy}",
                                                    style = MaterialTheme.typography.bodySmall,
                                                    fontWeight = FontWeight.Medium,
                                                    color = if (isSelected)
                                                        MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.85f)
                                                    else MaterialTheme.colorScheme.onSurfaceVariant,
                                                    modifier = Modifier.padding(top = 2.dp)
                                                )
                                                Text(
                                                    text = model.description.ifBlank {
                                                        if (model.isOnline) {
                                                            "Requires online access and cloud authentication / Gemini API Key. Best validation accuracy."
                                                        } else {
                                                            "Completely offline. Uses on-device SigLIP 2 ONNX vision model with local Gemini Nano reasoning."
                                                        }
                                                    },
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = if (isSelected)
                                                        MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                                                    else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                                                    modifier = Modifier.padding(top = 4.dp)
                                                )
                                                Text(
                                                    "Version: ${model.version}" + if (needsUpdate) " (Update Available)" else "",
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.error.takeIf { needsUpdate }
                                                        ?: MaterialTheme.colorScheme.outline,
                                                    modifier = Modifier.padding(top = 2.dp)
                                                )
                                            }

                                            Column(horizontalAlignment = Alignment.End) {
                                                when {
                                                    model.isOnline -> {
                                                        Text(
                                                            "Cloud",
                                                            style = MaterialTheme.typography.bodySmall,
                                                            color = if (isSelected)
                                                                MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                                                            else MaterialTheme.colorScheme.onSurfaceVariant
                                                        )
                                                    }
                                                    isFileDownloaded -> {
                                                        TextButton(
                                                            onClick = {
                                                                context.deleteFile("${model.id}.onnx")
                                                                context.deleteFile("${model.id}.onnx.part")
                                                                val fallbackModel = modelStates
                                                                    .map { it.value }
                                                                    .firstOrNull { other ->
                                                                        val otherFile = File(context.filesDir, "${other.id}.onnx")
                                                                        !other.isOnline &&
                                                                            other.id != model.id &&
                                                                            sp.getBoolean("is_downloaded_${other.id}", false) &&
                                                                            otherFile.exists() &&
                                                                            otherFile.length() > 0L
                                                                    }?.id
                                                                    ?: if (!BuildConfig.IS_FDROID) "online" else null
                                                                val wasDeletedModelSelected = currentModel == model.id

                                                                sp.edit(commit = true) {
                                                                    remove("is_downloaded_${model.id}")
                                                                    remove("version_${model.id}")
                                                                    if (wasDeletedModelSelected) {
                                                                        if (fallbackModel != null) {
                                                                            putString("selected_one_shot_model", fallbackModel)
                                                                        } else {
                                                                            remove("selected_one_shot_model")
                                                                        }
                                                                    }
                                                                }
                                                                if (wasDeletedModelSelected) {
                                                                    currentModel = fallbackModel
                                                                    if (fallbackModel == "online") {
                                                                        privateSp.edit()
                                                                            .putString("validation_engine", "cloud")
                                                                            .apply()
                                                                    }
                                                                }
                                                                prefsRevision++
                                                                Toast.makeText(context, "Model deleted", Toast.LENGTH_SHORT).show()
                                                            },
                                                            modifier = Modifier.padding(top = 4.dp)
                                                        ) {
                                                            Text("Delete", style = MaterialTheme.typography.labelSmall)
                                                        }
                                                    }
                                                    else -> {
                                                        Text(
                                                            "${model.sizeMB} MB",
                                                            style = MaterialTheme.typography.bodySmall,
                                                            color = if (isSelected)
                                                                MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                                                            else MaterialTheme.colorScheme.onSurfaceVariant
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(20.dp))

                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = { onDismiss() }) {
                            Text("Close")
                        }
                    }
                }
            }
        }
    }
}
