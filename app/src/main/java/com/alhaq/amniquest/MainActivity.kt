package com.alhaq.amniquest

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.util.Log
import androidx.fragment.app.FragmentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.material3.Surface
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.navigation
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.work.Data
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.workDataOf
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import com.alhaq.amniquest.app.navigation.RootRoute
import com.alhaq.amniquest.app.screens.account.SetupProfileScreen
import com.alhaq.amniquest.app.screens.account.UserInfoScreen
import com.alhaq.amniquest.app.screens.etc.DocumentViewerScreen
import com.alhaq.amniquest.app.screens.etc.ScreentimeStatsScreen
import com.alhaq.amniquest.app.screens.etc.SetCoinRewardRatio
import com.alhaq.amniquest.app.screens.game.RewardDialogMaker
import com.alhaq.amniquest.app.screens.game.StoreScreen
import com.alhaq.amniquest.app.screens.launcher.AppList
import com.alhaq.amniquest.app.screens.launcher.AppListViewModel
import com.alhaq.amniquest.app.screens.launcher.CustomizeScreen
import com.alhaq.amniquest.app.screens.launcher.HomeScreen
import com.alhaq.amniquest.app.screens.launcher.HomeScreenViewModel
import com.alhaq.amniquest.app.screens.launcher.widget.WidgetScreen
import com.alhaq.amniquest.app.screens.settings.SettingsScreen
import com.alhaq.amniquest.app.screens.onboard.subscreens.SelectApps
import com.alhaq.amniquest.app.screens.onboard.subscreens.SelectAppsModes
import com.alhaq.amniquest.app.screens.onboard.subscreens.ShowSocialsScreen
import com.alhaq.amniquest.app.screens.onboard.subscreens.ShowTutorial
import com.alhaq.amniquest.app.screens.pet.TheSystemDialog
import com.alhaq.amniquest.app.screens.quest.ListAllQuests
import com.alhaq.amniquest.app.screens.quest.ViewQuest
import com.alhaq.amniquest.app.screens.quest.setup.SetIntegration
import com.alhaq.amniquest.app.screens.quest.stats.specific.BaseQuestStatsView
import com.alhaq.amniquest.app.screens.quest.templates.SelectFromTemplates
import com.alhaq.amniquest.app.screens.quest.templates.SetupTemplate
import com.alhaq.amniquest.app.screens.quest.templates.TemplatesViewModel
import com.alhaq.amniquest.app.theme.LauncherTheme
import com.alhaq.amniquest.app.theme.customThemes.PitchBlackTheme
import com.alhaq.amniquest.backed.isOnline
import com.alhaq.amniquest.backed.repositories.QuestRepository
import com.alhaq.amniquest.backed.repositories.StatsRepository
import com.alhaq.amniquest.backed.repositories.UserRepository
import com.alhaq.amniquest.backed.triggerQuestSync
import com.alhaq.amniquest.backed.triggerStatsSync
import com.alhaq.amniquest.core.Supabase
import io.github.jan.supabase.auth.handleDeeplinks
import com.alhaq.amniquest.core.Supabase.url
import com.alhaq.amniquest.core.services.AppBlockerService
import com.alhaq.amniquest.core.utils.FcmHandler
import com.alhaq.amniquest.core.utils.receiver.AppInstallReceiver
import com.alhaq.amniquest.core.utils.reminder.NotificationScheduler
import com.alhaq.amniquest.core.workers.FileDownloadWorker
import com.alhaq.amniquest.core.core.utils.fromHex
import com.alhaq.amniquest.core.core.utils.getCurrentDate
import com.alhaq.amniquest.core.utils.managers.QuestHelper
import com.alhaq.amniquest.data.BaseIntegrationId
import com.alhaq.amniquest.data.IntegrationId
import com.alhaq.amniquest.data.StatsInfo
import com.alhaq.amniquest.data.json
import com.alhaq.amniquest.data.quest.shield.AmniShieldQuestConfig
import com.alhaq.amniquest.data.quest.shield.AmniShieldQuestType
import com.alhaq.amniquest.data.xpToRewardForQuest
import kotlinx.coroutines.flow.firstOrNull
import java.io.File
import java.util.UUID
import javax.inject.Inject


@AndroidEntryPoint(FragmentActivity::class)
class MainActivity : FragmentActivity() {
    @Inject lateinit var userRepository: UserRepository
    @Inject lateinit var questRepository: QuestRepository
    @Inject lateinit var statRepository: StatsRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val data = getSharedPreferences("onboard", MODE_PRIVATE)
        if (!data.getBoolean("onboard", false)) {
            val intent = Intent(this, OnboardActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            startActivity(intent)
            finish()
            return
        }
        try {
            intent?.data?.let {
                Supabase.supabase.handleDeeplinks(intent)
            }
        } catch (e: Exception) {
            Log.e("MainActivity", "Failed to handle deeplink in onCreate: ${e.message}")
        }

        handleNotificationIntent(intent)
        val questId = intent.getStringExtra("quest_id")
        enableEdgeToEdge()
        val notificationScheduler = NotificationScheduler(applicationContext,questRepository)
        val modelSp = getSharedPreferences("models", Context.MODE_PRIVATE)

        val isTokenizerDownloaded = modelSp.getBoolean("is_downloaded_tokenizer", false)
        val tokenizer = File(filesDir, "tokenizer.model")

        if (!isTokenizerDownloaded || !tokenizer.exists() || tokenizer.length() <= 0L) {
            FileDownloadWorker.enqueueTokenizerDownload(applicationContext)
        }

        val currentTheme = themes[userRepository.userInfo.customization_info.equippedTheme] ?: PitchBlackTheme()
        setContent {
            val isUserOnboarded = remember {mutableStateOf(true)}
            var currentTheme = remember { mutableStateOf(currentTheme) }

            LaunchedEffect(Unit) {
                isUserOnboarded.value = data.getBoolean("onboard",false)
                Log.d("onboard", isUserOnboarded.value.toString())

                if (isUserOnboarded.value) {
                    try {
                        ContextCompat.startForegroundService(
                            this@MainActivity,
                            Intent(this@MainActivity, AppBlockerService::class.java)
                        )
                    } catch (e: Exception) {
                        Log.e("MainActivity", "Failed to start AppBlockerService: ${e.message}")
                    }
                }

                notificationScheduler.createNotificationChannel()
                notificationScheduler.reloadAllReminders()
            }
            LauncherTheme(currentTheme.value) {
                Surface {
                    val navController = rememberNavController()

                    val unSyncedQuestItems = remember { questRepository.getUnSyncedQuests() }
                    val unSyncedStatsItems = remember { statRepository.getAllUnSyncedStats() }
                    val context = LocalContext.current

                    RewardDialogMaker(userRepository)

                    TheSystemDialog()
                    LaunchedEffect(Unit) {
                        launch {
                            unSyncedQuestItems.collect {
                                notificationScheduler.reloadAllReminders()
                                if (context.isOnline() && !userRepository.userInfo.isAnonymous) {
                                    triggerQuestSync(applicationContext)
                                }
                            }
                        }
                        launch {
                            unSyncedStatsItems.collect {
                                if (context.isOnline() && !userRepository.userInfo.isAnonymous ) {
                                    triggerStatsSync(applicationContext)
                                }
                            }
                        }
                    }

                    val appListViewModel : AppListViewModel = hiltViewModel()
                    val homeScreenViewModel : HomeScreenViewModel = hiltViewModel()
                    val templatesViewModel: TemplatesViewModel = hiltViewModel()

                    val scope = rememberCoroutineScope()
                    DisposableEffect(Unit) {
                        val receiver = AppInstallReceiver { packageName ->
                            scope.launch(Dispatchers.IO) {
                                appListViewModel.loadApps()
                            }
                        }

                        val filter = IntentFilter(Intent.ACTION_PACKAGE_ADDED).apply {
                            addDataScheme("package")
                        }

                        ContextCompat.registerReceiver(
                            context,
                            receiver,
                            filter,
                            ContextCompat.RECEIVER_EXPORTED
                        )

                        onDispose {
                            runCatching {
                                context.unregisterReceiver(receiver)
                            }
                        }
                    }
                    NavHost(
                        navController = navController,
                        startDestination = if(questId!=null) "${RootRoute.ViewQuest.route}${questId}" else RootRoute.HomeScreen.route,
                        enterTransition = { fadeIn(animationSpec = tween(200)) },
                        exitTransition = { fadeOut(animationSpec = tween(200)) },
                        popEnterTransition = { fadeIn(animationSpec = tween(200)) },
                        popExitTransition = { fadeOut(animationSpec = tween(200)) },
                    ) {

                        composable(RootRoute.UserInfo.route) {
                            UserInfoScreen(navController = navController)
                        }
                        composable(RootRoute.SettingsScreen.route) {
                            SettingsScreen(navController = navController)
                        }
                        composable(
                            route = "${RootRoute.SelectApps.route}{mode}",
                            arguments = listOf(navArgument("mode") { type = NavType.IntType })
                        ) { backstack ->
                            val mode = backstack.arguments?.getInt("mode")
                            SelectApps(SelectAppsModes.entries[mode!!])
                        }
                        composable(RootRoute.HomeScreen.route) {
                            HomeScreen(navController,homeScreenViewModel)
                        }
                        composable(RootRoute.WidgetScreen.route) {
                            WidgetScreen(navController)
                        }


                        composable(RootRoute.Store.route) {
                            LauncherTheme(PitchBlackTheme()) {
                                StoreScreen(navController)
                            }
                        }

                        composable(RootRoute.Customize.route) {
                            LauncherTheme(PitchBlackTheme()) {
                                CustomizeScreen(navController, currentTheme = currentTheme)
                            }
                        }
                        composable(RootRoute.AppList.route) {
                            AppList(navController,appListViewModel)
                        }

                        composable(RootRoute.ListAllQuest.route) {
                            ListAllQuests(navController)
                        }
                        composable(
                            route = "${RootRoute.ViewQuest.route}{id}",
                            arguments = listOf(navArgument("id") { type = NavType.StringType })
                        ) { backStackEntry ->
                            val id = backStackEntry.arguments?.getString("id")

                            ViewQuest(navController, questRepository,id!!)
                        }

                        navigation(
                            startDestination = RootRoute.SetIntegration.route,
                            route = RootRoute.AddNewQuest.route
                        ) {
                            composable(RootRoute.SetIntegration.route) {
                                SetIntegration(
                                    navController
                                )
                            }
                            IntegrationId.entries.forEach { item ->
                                composable(
                                    route = item.name + "/{id}",
                                    arguments = listOf(navArgument("id") {
                                        type = NavType.StringType
                                    })
                                ) { backstack ->
                                    var id = backstack.arguments?.getString("id")
                                    if (id == "ntg") {
                                        id = null
                                    }
                                    item.setupScreen.invoke(id, navController)
                                }
                            }
                        }
                        composable("${RootRoute.QuestStats.route}{id}") { backStackEntry ->
                            val id = backStackEntry.arguments?.getString("id")

                            BaseQuestStatsView(id!!, navController)
                        }
                        composable(RootRoute.SelectTemplates.route) {
                            SelectFromTemplates(navController,templatesViewModel)
                        }
                        composable(RootRoute.SetupTemplate.route) {
                            SetupTemplate(navController,templatesViewModel)
                        }

                        composable(RootRoute.SetCoinRewardRatio.route){
                            SetCoinRewardRatio(navController = navController)
                        }
                        composable("${RootRoute.IntegrationDocs.route}{name}"){ backStackEntry ->
                            val id = backStackEntry.arguments?.getString("name")
                            val url = IntegrationId.valueOf(id.toString()).docLink
                            DocumentViewerScreen(url)
                        }
                        composable("${RootRoute.DocViewer.route}{url}"){ backStackEntry ->
                            val url = backStackEntry.arguments?.getString("url")
                            DocumentViewerScreen(String.fromHex(url.toString()))
                        }
                        composable(RootRoute.SetupProfile.route) {
                            SetupProfileScreen(navController = navController)
                        }
                        composable(RootRoute.ShowSocials.route) {
                            ShowSocialsScreen()
                        }

                        composable(RootRoute.ShowTutorials.route) {
                            ShowTutorial()
                        }
                        composable(RootRoute.ShowScreentimeStats.route) {
                            ScreentimeStatsScreen()
                        }
                    }

                }

            }
        }
    }

    override fun onResume() {
        handleNotificationIntent(intent)
        reconcileAmniShieldSnapshot()
        super.onResume()
    }

    private fun reconcileAmniShieldSnapshot() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val snapshot = contentResolver.call(
                    com.alhaq.amniquest.core.contract.GuardianContract.BASE_URI,
                    com.alhaq.amniquest.core.contract.GuardianContract.METHOD_GET_SNAPSHOT,
                    null,
                    null
                ) ?: return@launch

                val isFocusActive = snapshot.getBoolean(
                    com.alhaq.amniquest.core.contract.GuardianContract.KEY_FOCUS_ACTIVE,
                    false
                )
                val focusTodayMinutes = snapshot.getInt(
                    com.alhaq.amniquest.core.contract.GuardianContract.KEY_FOCUS_TODAY_MINUTES,
                    0
                )
                val reelsScrolledToday = snapshot.getInt(
                    com.alhaq.amniquest.core.contract.GuardianContract.KEY_REELS_SCROLLED_TODAY,
                    0
                )
                val streakDays = snapshot.getInt(
                    com.alhaq.amniquest.core.contract.GuardianContract.KEY_STREAK_DAYS,
                    0
                )
                val shieldScore = snapshot.getInt(
                    com.alhaq.amniquest.core.contract.GuardianContract.KEY_SHIELD_SCORE,
                    0
                )

                Log.d("MainActivity", "AmniShield snapshot: focusActive=$isFocusActive, focusToday=${focusTodayMinutes}m, reelsToday=$reelsScrolledToday")

                val allQuests = questRepository.getAllQuests().firstOrNull() ?: return@launch
                val today = getCurrentDate()
                val pendingShieldQuests = allQuests.filter {
                    it.integration_id == BaseIntegrationId.AMNI_SHIELD && !it.is_destroyed && it.last_completed_on != today
                }

                for (quest in pendingShieldQuests) {
                    val config = try {
                        json.decodeFromString<AmniShieldQuestConfig>(quest.quest_json)
                    } catch (e: Exception) {
                        null
                    } ?: continue

                    val isEligible = when (config.questType) {
                        AmniShieldQuestType.FOCUS_SESSION -> focusTodayMinutes >= config.targetMinutes
                        AmniShieldQuestType.REELS_LIMIT -> QuestHelper.isTimeOver(quest) && reelsScrolledToday <= config.maxAllowedReels
                        AmniShieldQuestType.SHIELD_STREAK -> streakDays >= config.targetStreakDays
                        AmniShieldQuestType.SHIELD_SCORE -> shieldScore >= config.targetScore
                    }

                    if (isEligible) {
                        Log.i("MainActivity", "Reconciliation completed AmniShield quest: ${quest.id} (${quest.title})")
                        quest.last_completed_on = today
                        quest.synced = false
                        quest.last_updated = System.currentTimeMillis()
                        questRepository.upsertQuest(quest)

                        statRepository.upsertStats(
                            StatsInfo(
                                id = UUID.randomUUID().toString(),
                                quest_id = quest.id,
                                user_id = userRepository.getUserId()
                            )
                        )

                        userRepository.addCoins(quest.reward)
                        val xp = xpToRewardForQuest(userRepository.userInfo.level)
                        userRepository.addXp(xp)
                    }
                }
            } catch (e: Exception) {
                // AmniShield not installed or IPC not permitted
            }
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        com.alhaq.amniquest.app.screens.launcher.widget.WidgetConfigureResultManager.onActivityResult(
            requestCode,
            resultCode,
            data
        )
    }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        try {
            intent.data?.let {
                Supabase.supabase.handleDeeplinks(intent)
            }
        } catch (e: Exception) {
            Log.e("MainActivity", "Failed to handle deeplink in onNewIntent: ${e.message}")
        }
        handleNotificationIntent(intent)
    }
    private fun handleNotificationIntent(intent: Intent?) {
        intent?.extras?.let { extras ->
            val data = mutableMapOf<String, String>()
            for (key in extras.keySet()) {
                val value = extras.get(key)?.toString() ?: ""
                data[key] = value
            }

            Log.d("notification Data", data.toString())

            FcmHandler.handleData(this, data,userRepository)
        }
    }


}


