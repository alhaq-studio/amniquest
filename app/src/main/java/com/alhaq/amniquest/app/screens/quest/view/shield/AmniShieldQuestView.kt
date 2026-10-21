package com.alhaq.amniquest.app.screens.quest.view.shield

import android.app.Application
import android.database.ContentObserver
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.alhaq.amniquest.app.screens.components.TopBarActions
import com.alhaq.amniquest.app.screens.quest.view.ViewQuestVM
import com.alhaq.amniquest.app.screens.quest.view.components.MdPad
import com.alhaq.amniquest.app.screens.quest.view.dialogs.QuestSkipperDialog
import com.alhaq.amniquest.app.theme.LocalCustomTheme
import com.alhaq.amniquest.app.theme.smoothYellow
import com.alhaq.amniquest.backed.repositories.QuestRepository
import com.alhaq.amniquest.backed.repositories.StatsRepository
import com.alhaq.amniquest.backed.repositories.UserRepository
import com.alhaq.amniquest.core.contract.GuardianContract
import com.alhaq.amniquest.core.core.utils.VibrationHelper
import com.alhaq.amniquest.core.core.utils.formatHour
import com.alhaq.amniquest.data.CommonQuestInfo
import com.alhaq.amniquest.data.game.InventoryItem
import com.alhaq.amniquest.data.json
import com.alhaq.amniquest.data.quest.shield.AmniShieldQuestConfig
import com.alhaq.amniquest.data.quest.shield.AmniShieldQuestType
import com.alhaq.amniquest.data.xpToRewardForQuest
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@HiltViewModel
class AmniShieldQuestViewViewModel @Inject constructor(
    questRepository: QuestRepository,
    userRepository: UserRepository,
    statsRepository: StatsRepository,
    application: Application
) : ViewQuestVM(questRepository, userRepository, statsRepository, application)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AmniShieldQuestView(
    commonQuestInfo: CommonQuestInfo,
    viewModel: AmniShieldQuestViewViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val isQuestComplete by viewModel.isQuestComplete.collectAsState()
    val isInTimeRange by viewModel.isInTimeRange.collectAsState()
    val isTimeOver by viewModel.isTimeOver.collectAsState()
    val coins by viewModel.coins.collectAsState()

    val config = remember(commonQuestInfo.quest_json) {
        try {
            json.decodeFromString<AmniShieldQuestConfig>(commonQuestInfo.quest_json)
        } catch (e: Exception) {
            AmniShieldQuestConfig()
        }
    }

    var snapshot by remember { mutableStateOf<Bundle?>(null) }

    fun refreshSnapshot() {
        try {
            snapshot = context.contentResolver.call(
                GuardianContract.BASE_URI,
                GuardianContract.METHOD_GET_SNAPSHOT,
                null,
                null
            )
        } catch (e: Exception) {
            // Service not bound or uninstalled
        }
    }

    LaunchedEffect(Unit) {
        viewModel.setCommonQuest(commonQuestInfo)
        refreshSnapshot()
    }

    DisposableEffect(Unit) {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                refreshSnapshot()
            }
        }
        try {
            context.contentResolver.registerContentObserver(GuardianContract.URI_FOCUS, true, observer)
            context.contentResolver.registerContentObserver(GuardianContract.URI_REELS, true, observer)
        } catch (e: Exception) {
        }
        onDispose {
            try {
                context.contentResolver.unregisterContentObserver(observer)
            } catch (e: Exception) {
            }
        }
    }

    val scrollState = rememberScrollState()

    // Snapshot extracted values
    val isFocusActive = snapshot?.getBoolean(GuardianContract.KEY_FOCUS_ACTIVE, false) ?: false
    val focusRemainingMinutes = snapshot?.getInt(GuardianContract.KEY_FOCUS_REMAINING_MINUTES, 0) ?: 0
    val focusTodayMinutes = snapshot?.getInt(GuardianContract.KEY_FOCUS_TODAY_MINUTES, 0) ?: 0
    val reelsScrolledToday = snapshot?.getInt(GuardianContract.KEY_REELS_SCROLLED_TODAY, 0) ?: 0
    val streakDays = snapshot?.getInt(GuardianContract.KEY_STREAK_DAYS, 0) ?: 0
    val shieldScore = snapshot?.getInt(GuardianContract.KEY_SHIELD_SCORE, 0) ?: 0

    // Evaluate verified eligibility
    val isVerifiedEligible = remember(snapshot, isQuestComplete, config) {
        if (isQuestComplete) return@remember false
        when (config.questType) {
            AmniShieldQuestType.FOCUS_SESSION -> focusTodayMinutes >= config.targetMinutes
            AmniShieldQuestType.REELS_LIMIT -> (isTimeOver || !isInTimeRange) && reelsScrolledToday <= config.maxAllowedReels
            AmniShieldQuestType.SHIELD_STREAK -> streakDays >= config.targetStreakDays
            AmniShieldQuestType.SHIELD_SCORE -> shieldScore >= config.targetScore
        }
    }

    Scaffold(
        modifier = Modifier.safeDrawingPadding(),
        containerColor = LocalCustomTheme.current.getRootColorScheme().surface,
        topBar = {
            TopAppBar(
                title = {},
                actions = {
                    TopBarActions(coins, 0, isCoinsVisible = true)
                }
            )
        },
        floatingActionButton = {
            Row(
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (!isQuestComplete && viewModel.getInventoryItemCount(InventoryItem.QUEST_SKIPPER) > 0) {
                    Image(
                        painter = painterResource(com.alhaq.amniquest.data.R.drawable.quest_skipper),
                        contentDescription = "Use quest skipper",
                        modifier = Modifier
                            .size(30.dp)
                            .clickable {
                                VibrationHelper.vibrate(50)
                                viewModel.isQuestSkippedDialogVisible.value = true
                            }
                    )
                }

                if (!isQuestComplete && isVerifiedEligible) {
                    Spacer(modifier = Modifier.width(15.dp))
                    Button(
                        onClick = {
                            VibrationHelper.vibrate(100)
                            viewModel.saveMarkedQuestToDb()
                        }
                    ) {
                        Text(text = "Claim Verified Reward")
                    }
                }
            }
        }
    ) { innerPadding ->
        QuestSkipperDialog(viewModel)

        Column(
            modifier = Modifier
                .padding(innerPadding)
                .padding(16.dp)
                .verticalScroll(scrollState),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = commonQuestInfo.title,
                style = MaterialTheme.typography.headlineLarge.copy(fontWeight = FontWeight.Bold),
                textDecoration = if (isQuestComplete) TextDecoration.LineThrough else TextDecoration.None
            )

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = (if (!isQuestComplete) "Reward" else "Next Reward") +
                            ": ${commonQuestInfo.reward} coins + ${xpToRewardForQuest(viewModel.level)} xp",
                    style = MaterialTheme.typography.bodyLarge
                )
                if (!isQuestComplete && viewModel.isBoosterActive(InventoryItem.XP_BOOSTER)) {
                    Text(
                        text = " + ",
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Black,
                        color = smoothYellow
                    )
                    Image(
                        painter = painterResource(InventoryItem.XP_BOOSTER.icon),
                        contentDescription = InventoryItem.XP_BOOSTER.simpleName,
                        modifier = Modifier.size(20.dp)
                    )
                    Text(
                        text = " ${xpToRewardForQuest(viewModel.level)} xp",
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Black,
                        color = smoothYellow
                    )
                }
            }

            Text(
                text = "Time: ${formatHour(commonQuestInfo.time_range[0])} to ${formatHour(commonQuestInfo.time_range[1])}",
                style = MaterialTheme.typography.bodyLarge
            )

            // Dynamic Live AmniShield Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    when (config.questType) {
                        AmniShieldQuestType.FOCUS_SESSION -> {
                            Text(
                                text = "Focus Mode Defense",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "Target: ${config.targetMinutes} minutes in AmniShield",
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Spacer(modifier = Modifier.height(8.dp))

                            if (isFocusActive) {
                                Text(
                                    text = "Focus Active: $focusRemainingMinutes min remaining",
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Spacer(modifier = Modifier.height(6.dp))
                                LinearProgressIndicator(
                                    modifier = Modifier.fillMaxWidth()
                                )
                                if (!commonQuestInfo.isHardLock) {
                                    Spacer(modifier = Modifier.height(12.dp))
                                    OutlinedButton(
                                        onClick = {
                                            try {
                                                context.contentResolver.call(
                                                    GuardianContract.BASE_URI,
                                                    GuardianContract.METHOD_STOP_FOCUS,
                                                    null,
                                                    null
                                                )
                                                refreshSnapshot()
                                                Toast.makeText(context, "Focus Session stopped in AmniShield", Toast.LENGTH_SHORT).show()
                                            } catch (e: Exception) {
                                                Toast.makeText(context, "Could not stop focus session: ${e.message}", Toast.LENGTH_SHORT).show()
                                            }
                                        },
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Text("Stop Focus Session")
                                    }
                                }
                            } else {
                                Text(
                                    text = "Today's Focus: $focusTodayMinutes / ${config.targetMinutes} min",
                                    style = MaterialTheme.typography.bodyMedium
                                )
                                val progressFraction = (focusTodayMinutes.toFloat() / config.targetMinutes.toFloat()).coerceIn(0f, 1f)
                                Spacer(modifier = Modifier.height(6.dp))
                                LinearProgressIndicator(
                                    progress = { progressFraction },
                                    modifier = Modifier.fillMaxWidth()
                                )

                                if (!isQuestComplete && focusTodayMinutes < config.targetMinutes) {
                                    Spacer(modifier = Modifier.height(12.dp))
                                    Button(
                                        onClick = {
                                            val extras = Bundle().apply {
                                                putInt(GuardianContract.ARG_MINUTES, config.targetMinutes)
                                            }
                                            try {
                                                context.contentResolver.call(
                                                    GuardianContract.BASE_URI,
                                                    GuardianContract.METHOD_START_FOCUS,
                                                    null,
                                                    extras
                                                )
                                                refreshSnapshot()
                                                Toast.makeText(context, "Focus Session started in AmniShield", Toast.LENGTH_SHORT).show()
                                            } catch (e: Exception) {
                                                Toast.makeText(context, "Could not start focus session: ${e.message}", Toast.LENGTH_SHORT).show()
                                            }
                                        },
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Text("Start Focus in AmniShield")
                                    }
                                }
                            }
                        }

                        AmniShieldQuestType.REELS_LIMIT -> {
                            Text(
                                text = "Short Video Scrolls Defense",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "Allowed Limit: <= ${config.maxAllowedReels} reels today",
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "Reels Scrolled Today: $reelsScrolledToday",
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Bold,
                                color = if (reelsScrolledToday <= config.maxAllowedReels) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.error
                                }
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = if (reelsScrolledToday <= config.maxAllowedReels) {
                                    "Defense Status: Within Allowed Threshold"
                                } else {
                                    "Defense Status: Limit Exceeded"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = if (reelsScrolledToday <= config.maxAllowedReels) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.error
                                }
                            )
                        }

                        AmniShieldQuestType.SHIELD_STREAK -> {
                            Text(
                                text = "Shield Defense Streak",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "Target: ${config.targetStreakDays} consecutive defense days",
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "Current Streak: $streakDays days",
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                            val streakProgress = (streakDays.toFloat() / config.targetStreakDays.toFloat()).coerceIn(0f, 1f)
                            Spacer(modifier = Modifier.height(6.dp))
                            LinearProgressIndicator(
                                progress = { streakProgress },
                                modifier = Modifier.fillMaxWidth()
                            )
                        }

                        AmniShieldQuestType.SHIELD_SCORE -> {
                            Text(
                                text = "Daily Shield Protection Score",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "Target: >= ${config.targetScore}% defense score",
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "Current Score: $shieldScore%",
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Bold,
                                color = if (shieldScore >= config.targetScore) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurface
                                }
                            )
                            val scoreProgress = (shieldScore.toFloat() / 100f).coerceIn(0f, 1f)
                            Spacer(modifier = Modifier.height(6.dp))
                            LinearProgressIndicator(
                                progress = { scoreProgress },
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }
            }

            MdPad(commonQuestInfo)
        }
    }
}
