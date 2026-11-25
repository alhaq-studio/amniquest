package com.alhaq.amniquest.app.screens.quest.setup.shield

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.navigation.NavHostController
import com.alhaq.amniquest.R
import com.alhaq.amniquest.app.navigation.RootRoute
import com.alhaq.amniquest.app.screens.etc.MarkdownComposer
import com.alhaq.amniquest.app.screens.etc.MdComponent
import com.alhaq.amniquest.app.screens.etc.parseMarkdown
import com.alhaq.amniquest.app.screens.quest.setup.CommonSetBaseQuest
import com.alhaq.amniquest.app.screens.quest.setup.QuestSetupViewModel
import com.alhaq.amniquest.app.screens.quest.setup.ReviewDialog
import com.alhaq.amniquest.backed.repositories.QuestRepository
import com.alhaq.amniquest.backed.repositories.UserRepository
import com.alhaq.amniquest.core.contract.GuardianContract
import com.alhaq.amniquest.data.BaseIntegrationId
import com.alhaq.amniquest.data.IntegrationId
import com.alhaq.amniquest.data.json
import com.alhaq.amniquest.data.quest.shield.AmniShieldQuestConfig
import com.alhaq.amniquest.data.quest.shield.AmniShieldQuestType
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.math.roundToInt

@HiltViewModel
class SetAmniShieldQuestViewModel @Inject constructor(
    questRepository: QuestRepository,
    userRepository: UserRepository
) : QuestSetupViewModel(questRepository, userRepository) {
    val shieldConfig = MutableStateFlow(AmniShieldQuestConfig())
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SetAmniShieldQuest(
    editQuestId: String? = null,
    navController: NavHostController,
    viewModel: SetAmniShieldQuestViewModel = hiltViewModel()
) {
    val questInfoState by viewModel.questInfoState.collectAsState()
    val shieldConfig by viewModel.shieldConfig.collectAsState()

    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var isShieldInstalled by remember { mutableStateOf(GuardianContract.isInstalled(context)) }
    var showUninstalledConfirmDialog by remember { mutableStateOf(false) }

    DisposableEffect(lifecycleOwner, context) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                isShieldInstalled = GuardianContract.isInstalled(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    var isMdEditorVisible by remember { mutableStateOf(false) }
    val scrollState = rememberScrollState()
    val isReviewDialogVisible by viewModel.isReviewDialogVisible.collectAsState()
    val parsedMarkdown = remember { mutableStateOf(listOf<MdComponent>()) }
    val cScope = rememberCoroutineScope()

    BackHandler(isMdEditorVisible) {
        isMdEditorVisible = false
    }

    LaunchedEffect(Unit) {
        viewModel.loadQuestUpperData(editQuestId, BaseIntegrationId.AMNI_SHIELD)
        if (editQuestId != null) {
            val quest = viewModel.loadQuestData(editQuestId)
            quest?.quest_json?.takeIf { it.isNotEmpty() }?.let { jsonStr ->
                try {
                    viewModel.shieldConfig.value = json.decodeFromString<AmniShieldQuestConfig>(jsonStr)
                } catch (e: Exception) {
                    // Fallback to default config
                }
            }
        } else {
            if (questInfoState.reward == 5) {
                questInfoState.reward = 10
            }
        }
    }

    if (isReviewDialogVisible) {
        val baseQuest = viewModel.getBaseQuestInfo()
        ReviewDialog(
            items = listOf(baseQuest),
            onConfirm = {
                val encodedConfig = json.encodeToString(viewModel.shieldConfig.value)
                viewModel.addQuestToDb(encodedConfig, reward = questInfoState.reward) {
                    navController.popBackStack()
                }
            },
            onDismiss = {
                viewModel.isReviewDialogVisible.value = false
            }
        )
    }

    if (showUninstalledConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showUninstalledConfirmDialog = false },
            icon = {
                Icon(
                    imageVector = Icons.Default.Info,
                    contentDescription = "Info",
                    tint = MaterialTheme.colorScheme.primary
                )
            },
            title = {
                Text(
                    text = "AmniShield Not Installed",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Text(
                    text = "You can save this quest now, but defense metrics (focus sessions and reels limits) will not track until AmniShield is installed on your device.",
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showUninstalledConfirmDialog = false
                        viewModel.isReviewDialogVisible.value = true
                    }
                ) {
                    Text("Create Anyway")
                }
            },
            dismissButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { showUninstalledConfirmDialog = false }) {
                        Text("Cancel")
                    }
                    TextButton(
                        onClick = {
                            GuardianContract.openPlayStore(context)
                        }
                    ) {
                        Text("Install App")
                    }
                }
            }
        )
    }

    if (isMdEditorVisible) {
        MarkdownComposer(
            list = parsedMarkdown.value,
            generatedMarkdown = questInfoState
        )
        return
    }

    Scaffold(
        modifier = Modifier.safeDrawingPadding(),
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 8.dp),
                        text = "AmniShield Blocker Quest",
                        style = MaterialTheme.typography.headlineLarge
                    )
                },
                actions = {
                    Icon(
                        painter = painterResource(R.drawable.outline_help_24),
                        contentDescription = "Help",
                        modifier = Modifier
                            .clickable {
                                navController.navigate("${RootRoute.IntegrationDocs.route}${IntegrationId.AMNI_SHIELD.name}")
                            }
                            .size(30.dp)
                    )
                }
            )
        }
    ) { paddingValues ->
        Box(Modifier.padding(paddingValues)) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(scrollState)
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp)
            ) {
                CommonSetBaseQuest(
                    createdOnDate = viewModel.userCreatedOn,
                    questInfoState = questInfoState,
                    onAdvancedMdEditor = {
                        parsedMarkdown.value = parseMarkdown(questInfoState.instructions)
                        isMdEditorVisible = true
                    }
                )

                // 1. Custom Coin Reward Picker (5 to 50 coins, default 10)
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                    )
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Coin Reward",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "${questInfoState.reward} Coins",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                        Text(
                            text = "Set the coin bounty awarded when this defense quest is verified.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Slider(
                            value = questInfoState.reward.toFloat(),
                            onValueChange = { questInfoState.reward = it.roundToInt() },
                            valueRange = 5f..50f,
                            steps = 44,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }

                // 2. Status Card: AmniShield Installation & IPC State
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = if (isShieldInstalled) {
                            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f)
                        }
                    ),
                    border = if (isShieldInstalled) {
                        BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.5f))
                    } else {
                        BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                    }
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = if (isShieldInstalled) Icons.Default.CheckCircle else Icons.Default.Info,
                                contentDescription = if (isShieldInstalled) "Connected" else "Attention",
                                tint = if (isShieldInstalled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(24.dp)
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = if (isShieldInstalled) "AmniShield Connected" else "AmniShield Required for Verification",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = if (isShieldInstalled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = if (isShieldInstalled) {
                                        "Guardian IPC active. Defense metrics will automatically sync with this quest."
                                    } else {
                                        "AmniShield is needed to automatically track your focus sessions, reels limits, and defense streaks."
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        if (isShieldInstalled) {
                            OutlinedButton(
                                onClick = { GuardianContract.launchAmniShield(context) },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("Open AmniShield")
                            }
                        } else {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Button(
                                    onClick = { GuardianContract.openPlayStore(context) },
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text("Install AmniShield")
                                }
                                OutlinedButton(
                                    onClick = {
                                        isShieldInstalled = GuardianContract.isInstalled(context)
                                    }
                                ) {
                                    Text("Refresh")
                                }
                            }
                        }
                    }
                }

                // 3. Quest Type Selection
                Text(
                    text = "Select Shield Quest Type",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )

                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    val types = listOf(
                        AmniShieldQuestType.FOCUS_SESSION to ("Focus Session" to "Complete a timed focus session in AmniShield."),
                        AmniShieldQuestType.REELS_LIMIT to ("Reels Limit" to "Keep daily short video scrolls below your threshold."),
                        AmniShieldQuestType.SHIELD_STREAK to ("Defense Streak" to "Maintain consecutive daily defense streaks."),
                        AmniShieldQuestType.SHIELD_SCORE to ("Shield Score" to "Achieve a high daily protection and focus score.")
                    )

                    types.forEach { (type, details) ->
                        val (label, description) = details
                        val isSelected = shieldConfig.questType == type
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    viewModel.shieldConfig.value = shieldConfig.copy(questType = type)
                                },
                            colors = CardDefaults.cardColors(
                                containerColor = if (isSelected) {
                                    MaterialTheme.colorScheme.primaryContainer
                                } else {
                                    MaterialTheme.colorScheme.surface
                                }
                            ),
                            border = if (isSelected) {
                                BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
                            } else {
                                BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                            }
                        ) {
                            Column(modifier = Modifier.padding(14.dp)) {
                                Text(
                                    text = label,
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = description,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f) else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }

                // 3. Target Parameter Controls
                when (shieldConfig.questType) {
                    AmniShieldQuestType.FOCUS_SESSION -> {
                        Text(
                            text = "Target Focus Duration",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            listOf(15, 25, 45, 60).forEach { mins ->
                                FilterChip(
                                    selected = shieldConfig.targetMinutes == mins,
                                    onClick = {
                                        viewModel.shieldConfig.value = shieldConfig.copy(targetMinutes = mins)
                                    },
                                    label = { Text("$mins Minutes") },
                                    colors = FilterChipDefaults.filterChipColors()
                                )
                            }
                        }
                    }

                    AmniShieldQuestType.REELS_LIMIT -> {
                        Text(
                            text = "Daily Reels Scroll Limit",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            listOf(0 to "0 Reels (Zero Tolerance)", 5 to "Max 5 Reels", 10 to "Max 10 Reels", 20 to "Max 20 Reels").forEach { (reels, text) ->
                                FilterChip(
                                    selected = shieldConfig.maxAllowedReels == reels,
                                    onClick = {
                                        viewModel.shieldConfig.value = shieldConfig.copy(maxAllowedReels = reels)
                                    },
                                    label = { Text(text) },
                                    colors = FilterChipDefaults.filterChipColors()
                                )
                            }
                        }
                    }

                    AmniShieldQuestType.SHIELD_STREAK -> {
                        Text(
                            text = "Target Streak Days",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            listOf(3, 7, 14, 30).forEach { days ->
                                FilterChip(
                                    selected = shieldConfig.targetStreakDays == days,
                                    onClick = {
                                        viewModel.shieldConfig.value = shieldConfig.copy(targetStreakDays = days)
                                    },
                                    label = { Text("$days Days") },
                                    colors = FilterChipDefaults.filterChipColors()
                                )
                            }
                        }
                    }

                    AmniShieldQuestType.SHIELD_SCORE -> {
                        Text(
                            text = "Minimum Shield Score",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            listOf(70, 80, 90).forEach { score ->
                                FilterChip(
                                    selected = shieldConfig.targetScore == score,
                                    onClick = {
                                        viewModel.shieldConfig.value = shieldConfig.copy(targetScore = score)
                                    },
                                    label = { Text("$score% Score") },
                                    colors = FilterChipDefaults.filterChipColors()
                                )
                            }
                        }
                    }
                }

                // 4. Submit Button
                Button(
                    enabled = questInfoState.selectedDays.isNotEmpty() && questInfoState.title.isNotBlank(),
                    onClick = {
                        if (!isShieldInstalled && editQuestId == null) {
                            showUninstalledConfirmDialog = true
                        } else {
                            viewModel.isReviewDialogVisible.value = true
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Done,
                            contentDescription = "Done"
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = if (editQuestId == null) "Create Quest" else "Save Changes",
                            style = MaterialTheme.typography.labelLarge
                        )
                    }
                }

                Spacer(Modifier.size(100.dp))
            }
        }
    }
}
