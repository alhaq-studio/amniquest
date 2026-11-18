package com.alhaq.amniquest.core.workers

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import dagger.hilt.android.EntryPointAccessors
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.postgrest
import com.alhaq.amniquest.backed.repositories.UserRepositoryEntryPoint
import com.alhaq.amniquest.core.Supabase
import com.alhaq.amniquest.data.SyncStatus
import com.alhaq.amniquest.data.UserInfo

class ProfileSyncWorker(val appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext,
    params
) {
    override suspend fun doWork(): Result {
        val isFirstTimeSync = inputData.getBoolean(EXTRA_IS_FIRST_TIME, false)

        val entryPoint = EntryPointAccessors.fromApplication(applicationContext, UserRepositoryEntryPoint::class.java)
        val userRepository = entryPoint.userRepository()
        val userId = userRepository.getUserId()

        if (userRepository.userInfo.isAnonymous || userId.isBlank()) {
            Log.d("ProfileSyncWorker", "Skipping remote sync for anonymous user or empty id")
            sendSyncBroadcast(SyncStatus.OVER)
            return Result.success()
        }

        setForegroundAsync(createForegroundInfo(appContext))

        return try {
            performSync(isFirstTimeSync, userRepository, userId)
            Log.d("ProfileSyncWorker", "Sync completed successfully")
            Result.success()
        } catch (e: Exception) {
            Log.e("ProfileSyncWorker", "Sync failed", e)
            sendSyncBroadcast(SyncStatus.OVER)
            Result.failure()
        }
    }

    override suspend fun getForegroundInfo(): ForegroundInfo {
        return createForegroundInfo(appContext)
    }

    private suspend fun performSync(
        isFirstTimeSync: Boolean,
        userRepository: com.alhaq.amniquest.backed.repositories.UserRepository,
        userId: String
    ) {
        Log.d("ProfileSyncWorker", "Starting sync for $userId")

        sendSyncBroadcast(SyncStatus.ONGOING)

        if (userRepository.userInfo.needsSync || isFirstTimeSync) {
            val profileRemote = Supabase.supabase.from("profiles")
                .select {
                    filter {
                        eq("id", userId)
                    }
                }
                .decodeSingleOrNull<UserInfo>()

            if (profileRemote != null) {
                val localIsDefault = userRepository.userInfo.xp == 0 && userRepository.userInfo.level == 1
                val remoteHasProgress = profileRemote.xp > 0 || profileRemote.level > 1 || profileRemote.coins != 90 || profileRemote.has_profile
                val shouldPullRemote = profileRemote.last_updated > userRepository.userInfo.last_updated ||
                        (isFirstTimeSync && (remoteHasProgress || localIsDefault))

                if (shouldPullRemote) {
                    val mergedInfo = profileRemote.copy(
                        id = userId,
                        isAnonymous = false,
                        needsSync = false,
                        xp = maxOf(profileRemote.xp, userRepository.userInfo.xp),
                        level = maxOf(profileRemote.level, userRepository.userInfo.level),
                        coins = maxOf(profileRemote.coins, userRepository.userInfo.coins)
                    )
                    userRepository.userInfo = mergedInfo
                    userRepository.saveUserInfo(isSetLastUpdated = false)
                } else {
                    userRepository.userInfo.id = userId
                    userRepository.userInfo.isAnonymous = false
                    if (profileRemote.xp > userRepository.userInfo.xp) {
                        userRepository.userInfo.xp = profileRemote.xp
                    }
                    if (profileRemote.level > userRepository.userInfo.level) {
                        userRepository.userInfo.level = profileRemote.level
                    }
                    if (profileRemote.coins > userRepository.userInfo.coins) {
                        userRepository.userInfo.coins = profileRemote.coins
                    }
                    Supabase.supabase.postgrest["profiles"].upsert(userRepository.userInfo)
                    userRepository.userInfo.needsSync = false
                    userRepository.saveUserInfo(isSetLastUpdated = false)
                }
            } else {
                userRepository.userInfo.id = userId
                userRepository.userInfo.isAnonymous = false
                Supabase.supabase.postgrest["profiles"].upsert(userRepository.userInfo)
                userRepository.userInfo.needsSync = false
                userRepository.saveUserInfo(isSetLastUpdated = false)
            }
        }

        sendSyncBroadcast(SyncStatus.OVER)
    }

    private fun sendSyncBroadcast(status: SyncStatus) {
        val intent = Intent("launcher.launcher.profile_sync")
        intent.putExtra("status", status.ordinal)
        applicationContext.sendBroadcast(intent)
    }

    companion object {
        const val EXTRA_IS_FIRST_TIME = "is_first_time"

        fun buildInputData(isFirstLoginPull: Boolean): Data {
            return Data.Builder()
                .putBoolean(EXTRA_IS_FIRST_TIME, isFirstLoginPull)
                .build()
        }
    }

}

private fun createForegroundInfo(context: Context): ForegroundInfo {
    val channelId = "profile_sync_channel"
    val channelName = "Profile Sync"

    val channel = NotificationChannel(
        channelId,
        channelName,
        NotificationManager.IMPORTANCE_LOW
    )
    val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    manager.createNotificationChannel(channel)

    val notification = NotificationCompat.Builder(context, channelId)
        .setContentTitle("Syncing profile")
        .setContentText("Profile data is being synced with server…")
        .setSmallIcon(android.R.drawable.stat_notify_sync)
        .setOngoing(true)
        .setPriority(NotificationCompat.PRIORITY_HIGH)
        .build()

    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        ForegroundInfo(69, notification, FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    } else {
        ForegroundInfo(69, notification)
    }
}

