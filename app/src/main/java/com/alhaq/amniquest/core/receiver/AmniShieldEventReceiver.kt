package com.alhaq.amniquest.core.receiver

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import com.alhaq.amniquest.MainActivity
import com.alhaq.amniquest.R
import com.alhaq.amniquest.backed.repositories.QuestRepositoryEntryPoint
import com.alhaq.amniquest.backed.repositories.StatsRepositoryEntryPoint
import com.alhaq.amniquest.backed.repositories.UserRepositoryEntryPoint
import com.alhaq.amniquest.core.contract.GuardianContract
import com.alhaq.amniquest.core.core.utils.getCurrentDate
import com.alhaq.amniquest.core.utils.reminder.NotificationScheduler
import com.alhaq.amniquest.data.BaseIntegrationId
import com.alhaq.amniquest.data.StatsInfo
import com.alhaq.amniquest.data.json
import com.alhaq.amniquest.data.quest.shield.AmniShieldQuestConfig
import com.alhaq.amniquest.data.quest.shield.AmniShieldQuestType
import com.alhaq.amniquest.data.xpToRewardForQuest
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import java.util.UUID

class AmniShieldEventReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent == null) return

        val action = intent.action ?: return
        Log.d(TAG, "Received AmniShield broadcast action: $action")

        val pendingResult = goAsync()

        when (action) {
            GuardianContract.ACTION_FOCUS_COMPLETED -> {
                val durationMinutes = intent.getIntExtra(GuardianContract.EXTRA_DURATION_MINUTES, 0)
                Log.i(TAG, "Focus completed in AmniShield: $durationMinutes minutes")
                handleFocusCompleted(context.applicationContext, durationMinutes, pendingResult)
            }

            GuardianContract.ACTION_FOCUS_ABORTED -> {
                Log.i(TAG, "Focus session was aborted in AmniShield")
                handleFocusAborted(context.applicationContext, pendingResult)
            }

            GuardianContract.ACTION_REELS_THRESHOLD -> {
                Log.i(TAG, "Reels threshold reached in AmniShield")
                pendingResult.finish()
            }

            else -> {
                pendingResult.finish()
            }
        }
    }

    private fun handleFocusCompleted(appContext: Context, durationMinutes: Int, pendingResult: PendingResult) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val questEntryPoint = EntryPointAccessors.fromApplication(
                    appContext,
                    QuestRepositoryEntryPoint::class.java
                )
                val userEntryPoint = EntryPointAccessors.fromApplication(
                    appContext,
                    UserRepositoryEntryPoint::class.java
                )
                val statsEntryPoint = EntryPointAccessors.fromApplication(
                    appContext,
                    StatsRepositoryEntryPoint::class.java
                )

                val questRepository = questEntryPoint.questRepository()
                val userRepository = userEntryPoint.userRepository()
                val statsRepository = statsEntryPoint.statsRepository()

                val allQuests = questRepository.getAllQuests().firstOrNull() ?: return@launch
                val today = getCurrentDate()
                val shieldQuests = allQuests.filter {
                    it.integration_id == BaseIntegrationId.AMNI_SHIELD && !it.is_destroyed && it.last_completed_on != today
                }

                for (quest in shieldQuests) {
                    val config = try {
                        json.decodeFromString<AmniShieldQuestConfig>(quest.quest_json)
                    } catch (e: Exception) {
                        null
                    }

                    if (config?.questType == AmniShieldQuestType.FOCUS_SESSION) {
                        if (durationMinutes >= config.targetMinutes) {
                            Log.i(TAG, "Completing AmniShield quest: ${quest.id} (${quest.title})")
                            quest.last_completed_on = today
                            quest.synced = false
                            quest.last_updated = System.currentTimeMillis()
                            questRepository.upsertQuest(quest)

                            statsRepository.upsertStats(
                                StatsInfo(
                                    id = UUID.randomUUID().toString(),
                                    quest_id = quest.id,
                                    user_id = userRepository.getUserId()
                                )
                            )

                            userRepository.addCoins(quest.reward)
                            val xp = xpToRewardForQuest(userRepository.userInfo.level)
                            userRepository.addXp(xp)

                            showCompletionNotification(appContext, quest.title, quest.reward, xp)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error handling AmniShield focus completed event", e)
            } finally {
                pendingResult.finish()
            }
        }
    }

    private fun handleFocusAborted(appContext: Context, pendingResult: PendingResult) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val questEntryPoint = EntryPointAccessors.fromApplication(
                    appContext,
                    QuestRepositoryEntryPoint::class.java
                )
                val questRepository = questEntryPoint.questRepository()
                val allQuests = questRepository.getAllQuests().firstOrNull() ?: return@launch
                val today = getCurrentDate()
                val hardLockedShieldQuests = allQuests.filter {
                    it.integration_id == BaseIntegrationId.AMNI_SHIELD && !it.is_destroyed && it.isHardLock && it.last_completed_on != today
                }

                if (hardLockedShieldQuests.isNotEmpty()) {
                    Log.w(TAG, "AmniShield focus aborted with ${hardLockedShieldQuests.size} hard-locked quest(s) active")
                    showAbortNotification(appContext, hardLockedShieldQuests.first().title)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error handling AmniShield focus aborted event", e)
            } finally {
                pendingResult.finish()
            }
        }
    }

    private fun showCompletionNotification(context: Context, title: String, coins: Int, xp: Int) {
        try {
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val mainActivityIntent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            }
            val pendingIntent = PendingIntent.getActivity(
                context,
                System.currentTimeMillis().toInt(),
                mainActivityIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val notification = NotificationCompat.Builder(context, NotificationScheduler.REMINDER_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentTitle("Focus Quest Completed: $title")
                .setContentText("Earned +$coins Coins and +$xp XP.")
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_REMINDER)
                .setContentIntent(pendingIntent)
                .setAutoCancel(true)
                .build()

            notificationManager.notify(NotificationScheduler.REMINDER_NOTIFICATION_ID_PREFIX + title.hashCode(), notification)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to display completion notification", e)
        }
    }

    private fun showAbortNotification(context: Context, title: String) {
        try {
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val mainActivityIntent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            }
            val pendingIntent = PendingIntent.getActivity(
                context,
                System.currentTimeMillis().toInt(),
                mainActivityIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val notification = NotificationCompat.Builder(context, NotificationScheduler.REMINDER_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentTitle("Focus Session Aborted")
                .setContentText("Commitment broken for hard-locked quest: $title.")
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_REMINDER)
                .setContentIntent(pendingIntent)
                .setAutoCancel(true)
                .build()

            notificationManager.notify(NotificationScheduler.REMINDER_NOTIFICATION_ID_PREFIX + title.hashCode(), notification)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to display abort notification", e)
        }
    }

    companion object {
        private const val TAG = "AmniShieldEventReceiver"
    }
}
