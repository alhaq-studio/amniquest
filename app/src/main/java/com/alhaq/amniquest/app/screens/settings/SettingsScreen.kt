package com.alhaq.amniquest.app.screens.settings

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsIgnoringVisibility
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import com.alhaq.amniquest.BuildConfig
import com.alhaq.amniquest.OnboardActivity
import com.alhaq.amniquest.app.navigation.RootRoute
import com.alhaq.amniquest.app.screens.account.BackupPasswordDialog
import com.alhaq.amniquest.app.screens.account.GeminiKeyDialog
import com.alhaq.amniquest.app.screens.account.ValidationEngineDialog
import com.alhaq.amniquest.app.screens.game.InventoryItemInfoDialog
import com.alhaq.amniquest.app.screens.quest.setup.ai_snap.model.ModelDownloadDialog
import com.alhaq.amniquest.data.game.InventoryItem
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    navController: NavController,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val scrollState = rememberScrollState()

    val userInfo by viewModel.userInfoState.collectAsState()
    val customizationInfo by viewModel.customizationInfo.collectAsState()
    val adderPrice by viewModel.adderPrice.collectAsState()
    val removerPrice by viewModel.removerPrice.collectAsState()
    val inventory by viewModel.inventory.collectAsState()

    var selectedInventoryItem by remember { mutableStateOf<InventoryItem?>(null) }
    var isEditionInfoDialogVisible by remember { mutableStateOf(false) }
    var isExportPasswordDialogVisible by remember { mutableStateOf(false) }
    var isImportPasswordDialogVisible by remember { mutableStateOf(false) }
    var pendingExportUri by remember { mutableStateOf<android.net.Uri?>(null) }
    var pendingImportContent by remember { mutableStateOf<String?>(null) }

    var isGeminiKeyDialogVisible by remember { mutableStateOf(false) }
    var isValidationEngineDialogVisible by remember { mutableStateOf(false) }
    val isModelDownloadDialogVisible = remember { mutableStateOf(false) }
    var isLogoutDialogVisible by remember { mutableStateOf(false) }
    var isConnectAccountDialogVisible by remember { mutableStateOf(false) }

    val backupLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json"),
        onResult = { uri ->
            uri?.let {
                pendingExportUri = it
                isExportPasswordDialogVisible = true
            }
        }
    )

    val restoreLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
        onResult = { uri ->
            uri?.let {
                try {
                    val jsonStr = context.contentResolver.openInputStream(it)?.use { stream ->
                        stream.bufferedReader().use { reader -> reader.readText() }
                    }
                    if (jsonStr != null) {
                        pendingImportContent = jsonStr
                        isImportPasswordDialogVisible = true
                    } else {
                        Toast.makeText(context, "Could not read backup file", Toast.LENGTH_SHORT).show()
                    }
                } catch (e: Exception) {
                    Toast.makeText(context, "Error reading file: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    )

    ModelDownloadDialog(
        allowSkipping = true,
        modelDownloadDialogVisible = isModelDownloadDialogVisible
    )

    if (isConnectAccountDialogVisible) {
        com.alhaq.amniquest.app.screens.account.ConnectAccountDialog(
            userRepository = viewModel.userRepository,
            onDismiss = { isConnectAccountDialogVisible = false },
            onSuccess = {
                isConnectAccountDialogVisible = false
            }
        )
    }

    if (isGeminiKeyDialogVisible) {
        GeminiKeyDialog(
            currentKey = viewModel.getGeminiApiKey(),
            onSave = { viewModel.saveGeminiApiKey(it) },
            onDismiss = { isGeminiKeyDialogVisible = false }
        )
    }

    if (isValidationEngineDialogVisible) {
        ValidationEngineDialog(
            currentEngine = viewModel.getValidationEngine(),
            onSave = { engine ->
                viewModel.saveValidationEngine(engine)
                if (engine == "gemini_api" && viewModel.getGeminiApiKey().isEmpty()) {
                    isGeminiKeyDialogVisible = true
                } else if (engine == "local") {
                    isModelDownloadDialogVisible.value = true
                }
            },
            onDismiss = { isValidationEngineDialogVisible = false }
        )
    }

    if (isExportPasswordDialogVisible) {
        BackupPasswordDialog(
            title = "Export Encrypted Backup",
            confirmLabel = "Export",
            onConfirm = { pwd ->
                pendingExportUri?.let { uri ->
                    viewModel.backupData(pwd.ifEmpty { null }) { jsonStr ->
                        if (jsonStr != null) {
                            try {
                                context.contentResolver.openOutputStream(uri)?.use { stream ->
                                    stream.write(jsonStr.toByteArray())
                                }
                                Toast.makeText(context, "Backup exported successfully!", Toast.LENGTH_SHORT).show()
                            } catch (e: Exception) {
                                Toast.makeText(context, "Failed to write backup: ${e.message}", Toast.LENGTH_SHORT).show()
                            }
                        } else {
                            Toast.makeText(context, "Backup creation failed", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            },
            onDismiss = { isExportPasswordDialogVisible = false }
        )
    }

    if (isImportPasswordDialogVisible) {
        BackupPasswordDialog(
            title = "Decrypt & Restore Backup",
            confirmLabel = "Restore",
            onConfirm = { pwd ->
                pendingImportContent?.let { content ->
                    viewModel.restoreData(content, pwd.ifEmpty { null }) { success ->
                        if (success) {
                            Toast.makeText(context, "Backup restored successfully!", Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(context, "Failed to decrypt/restore backup", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            },
            onDismiss = { isImportPasswordDialogVisible = false }
        )
    }

    if (isLogoutDialogVisible) {
        AlertDialog(
            onDismissRequest = { isLogoutDialogVisible = false },
            title = { Text("Log Out & Reset") },
            text = { Text("Are you sure you want to log out? Local data will be cleared and you will be returned to onboarding.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        isLogoutDialogVisible = false
                        viewModel.logOut {
                            val intent = Intent(context, OnboardActivity::class.java)
                            context.startActivity(intent)
                            (context as Activity).finish()
                        }
                    }
                ) {
                    Text("Log Out", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { isLogoutDialogVisible = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    if (selectedInventoryItem != null) {
        val count = inventory[selectedInventoryItem!!] ?: 0
        InventoryItemInfoDialog(
            reward = selectedInventoryItem!!,
            isBoosterActive = viewModel.isBoosterActive(selectedInventoryItem!!),
            itemCount = count,
            onUseRequest = {
                viewModel.useInventoryItem(selectedInventoryItem!!, navController)
            },
            onOpenStoreRequest = {
                navController.navigate(RootRoute.Store.route)
            },
            onDismissRequest = {
                selectedInventoryItem = null
            }
        )
    }

    if (isEditionInfoDialogVisible) {
        AlertDialog(
            onDismissRequest = { isEditionInfoDialogVisible = false },
            title = { Text("App Edition & Licensing") },
            text = {
                val isFdroid = BuildConfig.FLAVOR.contains("fdroid", ignoreCase = true)
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        if (isFdroid)
                            "You are running the F-Droid FOSS Edition of AmniQuest. This build is strictly free of proprietary tracking libraries and Play Services dependencies."
                        else
                            "You are running the Google Play Edition of AmniQuest with support for Google Play billing."
                    )
                    Text(
                        "Zero-Knowledge Guarantee: Habit tracking and photo habit verification execute 100% locally on your device. Captures and personal data are never transmitted to external servers.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = {
                        isEditionInfoDialogVisible = false
                        navController.navigate(RootRoute.Store.route)
                    }) {
                        Text("Supporter Pass")
                    }
                    TextButton(onClick = {
                        isEditionInfoDialogVisible = false
                        navController.navigate(RootRoute.TermsScreen.route)
                    }) {
                        Text("Terms & Privacy")
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = { isEditionInfoDialogVisible = false }) {
                    Text("Close")
                }
            }
        )
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0)
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp)
                .verticalScroll(scrollState)
        ) {
            // Top Bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(WindowInsets.statusBarsIgnoringVisibility.asPaddingValues())
                    .padding(vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = { navController.popBackStack() }) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back"
                    )
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "Settings & Controls",
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            // 1. Profile & Account Controls
            SettingsCard(
                title = "Profile & Account",
                icon = Icons.Default.Lock
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { navController.navigate(RootRoute.SetupProfile.route) }
                        .padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = userInfo.username.ifEmpty { "Local User" },
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = if (userInfo.isAnonymous) "Offline / Local Account" else "Cloud-Synced Account",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    OutlinedButton(
                        onClick = { navController.navigate(RootRoute.SetupProfile.route) }
                    ) {
                        Icon(
                            imageVector = Icons.Default.Edit,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(Modifier.width(4.dp))
                        Text("Edit")
                    }
                }

                if (userInfo.isAnonymous) {
                    Spacer(Modifier.height(8.dp))
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer
                        ),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { isConnectAccountDialogVisible = true }
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Connect Cloud Account",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                                Text(
                                    text = "Back up quests, streaks, and progress securely to your account",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                                )
                            }
                            Button(
                                onClick = { isConnectAccountDialogVisible = true },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.primary,
                                    contentColor = MaterialTheme.colorScheme.onPrimary
                                )
                            ) {
                                Text("Connect")
                            }
                        }
                    }
                } else {
                    Spacer(Modifier.height(8.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Cloud Sync Status", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                            Text(
                                text = if (userInfo.needsSync) "Unsynced changes waiting for sync" else "Synced with Cloud",
                                style = MaterialTheme.typography.bodySmall,
                                color = if (userInfo.needsSync) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary
                            )
                        }
                        OutlinedButton(
                            onClick = {
                                viewModel.syncCloudData { success ->
                                    Toast.makeText(
                                        context,
                                        if (success) "Cloud sync completed" else "Sync failed",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }
                            }
                        ) {
                            Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Force Cloud Sync")
                        }
                    }
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Habit Privacy Mode", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                        Text("Obfuscate habit names on home and widgets", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(
                        checked = customizationInfo.habitPrivacyMode,
                        onCheckedChange = {
                            viewModel.setHabitPrivacyMode(it)
                        }
                    )
                }

                Spacer(Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = { backupLauncher.launch("questphone_backup.json") },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Backup Data")
                    }
                    OutlinedButton(
                        onClick = { restoreLauncher.launch(arrayOf("application/json")) },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Restore Data")
                    }
                }

                Spacer(Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (userInfo.isAnonymous) {
                        Button(
                            onClick = { isConnectAccountDialogVisible = true },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Connect Account")
                        }
                    } else {
                        OutlinedButton(
                            onClick = {
                                viewModel.syncCloudData { success ->
                                    Toast.makeText(
                                        context,
                                        if (success) "Cloud sync completed" else "Sync failed",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Force Cloud Sync")
                        }
                    }

                    Button(
                        onClick = { isLogoutDialogVisible = true },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.errorContainer, contentColor = MaterialTheme.colorScheme.onErrorContainer),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Log Out")
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            // 2. Launcher & App Drawer Controls
            SettingsCard(
                title = "Launcher & App Drawer",
                icon = Icons.Default.Settings
            ) {
                Text("Drawer Layout View", style = MaterialTheme.typography.labelMedium)
                Spacer(Modifier.height(6.dp))
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    listOf("List" to "List (Default)", "Compact" to "Compact", "Grid2" to "Grid 2x", "Grid3" to "Grid 3x", "Grid4" to "Grid 4x").forEach { (key, label) ->
                        FilterChip(
                            selected = customizationInfo.appListLayout == key,
                            onClick = { viewModel.setAppListLayout(key) },
                            label = { Text(label) }
                        )
                    }
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 10.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Show App Icons", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                    Switch(
                        checked = customizationInfo.showAppIcons,
                        onCheckedChange = { viewModel.setShowAppIcons(it) }
                    )
                }

                if (customizationInfo.showAppIcons) {
                    Spacer(Modifier.height(8.dp))
                    Text("App Icon Size", style = MaterialTheme.typography.labelMedium)
                    Spacer(Modifier.height(4.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        listOf("Small" to "Small (28dp)", "Medium" to "Medium (40dp)", "Large" to "Large (52dp)").forEach { (key, label) ->
                            FilterChip(
                                selected = customizationInfo.appIconSize == key,
                                onClick = { viewModel.setAppIconSize(key) },
                                label = { Text(label) }
                            )
                        }
                    }
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 10.dp))

                Text("Default Sort Order", style = MaterialTheme.typography.labelMedium)
                Spacer(Modifier.height(6.dp))
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    listOf(
                        "Alphabetical" to "Alphabetical (A-Z)",
                        "ReverseAlphabetical" to "Reverse (Z-A)",
                        "MostUsed" to "Most Used",
                        "InstallDate" to "Install Date"
                    ).forEach { (key, label) ->
                        FilterChip(
                            selected = customizationInfo.appSortOrder == key,
                            onClick = { viewModel.setAppSortOrder(key) },
                            label = { Text(label) }
                        )
                    }
                }

                Spacer(Modifier.height(10.dp))

                Text("Default Group By", style = MaterialTheme.typography.labelMedium)
                Spacer(Modifier.height(6.dp))
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    listOf(
                        "None" to "None",
                        "Alphabet" to "Alphabet (A-Z)",
                        "Category" to "Category",
                        "InstallDate" to "Install Date",
                        "Status" to "Habit Status"
                    ).forEach { (key, label) ->
                        FilterChip(
                            selected = customizationInfo.appGroupBy == key,
                            onClick = { viewModel.setAppGroupBy(key) },
                            label = { Text(label) }
                        )
                    }
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 10.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Auto-Open Keyboard", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                        Text("Pop up soft keyboard immediately upon opening drawer", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(
                        checked = customizationInfo.autoFocusSearch,
                        onCheckedChange = { viewModel.setAutoFocusSearch(it) }
                    )
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 10.dp))

                Text("Clock Typography Style", style = MaterialTheme.typography.labelMedium)
                Spacer(Modifier.height(6.dp))
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    listOf("Light" to "Light", "Regular" to "Regular", "Bold" to "Bold", "Black" to "Black").forEach { (key, label) ->
                        FilterChip(
                            selected = customizationInfo.clockStyle == key,
                            onClick = { viewModel.setClockStyle(key) },
                            label = { Text(label) }
                        )
                    }
                }

                Spacer(Modifier.height(10.dp))

                Text(
                    text = "Clock Size: ${customizationInfo.clockSizeSp.toInt()}sp",
                    style = MaterialTheme.typography.labelMedium
                )
                Spacer(Modifier.height(4.dp))
                var clockSizeSlider by remember(customizationInfo.clockSizeSp) {
                    mutableFloatStateOf(customizationInfo.clockSizeSp)
                }
                Slider(
                    value = clockSizeSlider,
                    onValueChange = { clockSizeSlider = it },
                    onValueChangeFinished = {
                        viewModel.setClockSizeSp(clockSizeSlider)
                    },
                    valueRange = 24f..64f
                )

                Spacer(Modifier.height(10.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("24-Hour Clock", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                    Switch(
                        checked = customizationInfo.is24Hr,
                        onCheckedChange = { viewModel.setIs24Hr(it) }
                    )
                }
            }

            Spacer(Modifier.height(16.dp))

            // 3. Store & Inventory Controls
            SettingsCard(
                title = "Store & Inventory",
                icon = Icons.Default.ShoppingCart
            ) {
                Text(
                    text = "Distraction Tool Pricing Rules",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(8.dp))

                // Distraction Adder Price Slider
                Text(
                    text = "Adder Tool Price: $adderPrice coins",
                    style = MaterialTheme.typography.bodyMedium
                )
                var adderSlider by remember(adderPrice) { mutableFloatStateOf(adderPrice.toFloat()) }
                Slider(
                    value = adderSlider,
                    onValueChange = { adderSlider = it },
                    onValueChangeFinished = {
                        viewModel.updateDistractionToolPrices(adderSlider.roundToInt(), removerPrice)
                    },
                    valueRange = 0f..10f,
                    steps = 9
                )

                Spacer(Modifier.height(8.dp))

                // Distraction Remover Price Slider
                Text(
                    text = "Remover Tool Price: $removerPrice coins",
                    style = MaterialTheme.typography.bodyMedium
                )
                var removerSlider by remember(removerPrice) { mutableFloatStateOf(removerPrice.toFloat()) }
                Slider(
                    value = removerSlider,
                    onValueChange = { removerSlider = it },
                    onValueChangeFinished = {
                        viewModel.updateDistractionToolPrices(adderPrice, removerSlider.roundToInt())
                    },
                    valueRange = 20f..500f
                )

                HorizontalDivider(modifier = Modifier.padding(vertical = 10.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = { navController.navigate(RootRoute.SetCoinRewardRatio.route) },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Reward Ratio")
                    }
                    Button(
                        onClick = { navController.navigate(RootRoute.Store.route) },
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.ShoppingCart, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Open Store")
                    }
                }

                Spacer(Modifier.height(12.dp))

                Text(
                    text = "Inventory Stock Overview",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(6.dp))
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    listOf(
                        InventoryItem.XP_BOOSTER to "XP Booster",
                        InventoryItem.STREAK_FREEZER to "Streak Freezer",
                        InventoryItem.QUEST_SKIPPER to "Quest Skipper",
                        InventoryItem.REWARD_TIME_EDITOR to "Time Editor",
                        InventoryItem.DISTRACTION_ADDER to "Adder Tool",
                        InventoryItem.DISTRACTION_REMOVER to "Remover Tool"
                    ).forEach { (item, label) ->
                        val count = inventory[item] ?: 0
                        SuggestionChip(
                            onClick = { selectedInventoryItem = item },
                            label = { Text("$label: $count") }
                        )
                    }
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 10.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Confirm Instant Buy & Use", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                        Text("Prompt confirmation dialog before consuming coins on instant unlock tools", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(
                        checked = customizationInfo.confirmInstantBuyAndUse,
                        onCheckedChange = { viewModel.setConfirmInstantBuyAndUse(it) }
                    )
                }
            }

            Spacer(Modifier.height(16.dp))

            // 4. On-Device AI & Models
            SettingsCard(
                title = "On-Device AI & Models",
                icon = Icons.Default.Info
            ) {
                val currentEngine = viewModel.getValidationEngine()
                val engineLabel = when (currentEngine) {
                    "local" -> "Local On-Device AI (SigLIP 2 ONNX)"
                    "gemini_api" -> "Private Gemini API Key"
                    else -> "AmniQuest Cloud Server"
                }

                Text(
                    text = "Active Validation Engine: $engineLabel",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium
                )

                Spacer(Modifier.height(8.dp))

                OutlinedButton(
                    onClick = { isValidationEngineDialogVisible = true },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Change Validation Engine")
                }

                Spacer(Modifier.height(12.dp))

                Text(
                    text = "Local Model Weights Disk Usage: ${viewModel.getLocalModelDiskUsage()}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(Modifier.height(6.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = { isModelDownloadDialogVisible.value = true },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Manage Weights")
                    }
                    OutlinedButton(
                        onClick = { isGeminiKeyDialogVisible = true },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Gemini API Key")
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            // 5. System & About
            SettingsCard(
                title = "System & About",
                icon = Icons.Default.Share
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Edition & Flavor", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                    SuggestionChip(
                        onClick = { isEditionInfoDialogVisible = true },
                        label = {
                            Text(
                                if (BuildConfig.FLAVOR.contains("fdroid", ignoreCase = true)) "F-Droid FOSS Edition" else "Google Play Edition"
                            )
                        }
                    )
                }

                Spacer(Modifier.height(8.dp))

                Text(
                    text = "Zero-Knowledge Architecture: Habit tracking and photo verification execute 100% locally on your device. Captures and personal data are never transmitted to external servers.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(Modifier.height(12.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = { viewModel.onShareCrashLog() },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Diagnostics Log")
                    }
                    OutlinedButton(
                        onClick = { navController.navigate(RootRoute.TermsScreen.route) },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Terms & Privacy")
                    }
                }
            }

            Spacer(Modifier.height(24.dp))
            Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.systemBars))
        }
    }
}

@Composable
fun SettingsCard(
    title: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(bottom = 12.dp)
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }
            content()
        }
    }
}
