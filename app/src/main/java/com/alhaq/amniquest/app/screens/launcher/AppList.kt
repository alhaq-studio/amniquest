package com.alhaq.amniquest.app.screens.launcher

import android.content.pm.PackageManager
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import androidx.navigation.NavController
import com.alhaq.amniquest.R
import com.alhaq.amniquest.app.navigation.LauncherDialogRoutes
import com.alhaq.amniquest.app.navigation.RootRoute
import com.alhaq.amniquest.app.screens.launcher.dialogs.LauncherDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object AppIconCache {
    private val cache = androidx.collection.LruCache<String, ImageBitmap>(200)

    fun get(packageName: String): ImageBitmap? = cache.get(packageName)

    fun load(pm: PackageManager, packageName: String): ImageBitmap? {
        val existing = cache.get(packageName)
        if (existing != null) return existing
        return runCatching {
            val drawable = pm.getApplicationIcon(packageName)
            val bmp = drawable.toBitmap().asImageBitmap()
            cache.put(packageName, bmp)
            bmp
        }.getOrNull()
    }
}

@Composable
fun AppIconView(
    packageName: String,
    size: Dp,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var bitmap by remember(packageName) { mutableStateOf<ImageBitmap?>(AppIconCache.get(packageName)) }

    LaunchedEffect(packageName) {
        if (bitmap == null) {
            withContext(Dispatchers.IO) {
                bitmap = AppIconCache.load(context.packageManager, packageName)
            }
        }
    }

    bitmap?.let {
        Image(
            bitmap = it,
            contentDescription = null,
            modifier = modifier.size(size)
        )
    } ?: Box(modifier = modifier.size(size))
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun AppList(navController: NavController, viewModel: AppListViewModel) {
    val groups by viewModel.groupedApps.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val showDialog by viewModel.showCoinDialog.collectAsState()
    val selectedPackage by viewModel.selectedPackage.collectAsState()
    val searchQuery by viewModel.searchQuery.collectAsState()
    val customizationInfo by viewModel.customizationInfo.collectAsState()

    val focusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current
    var textFieldLoaded by remember { mutableStateOf(false) }
    val minutesPer5Coins by viewModel.minutesPerFiveCoins.collectAsState()
    val areHardLockedQuestsAvailable by viewModel.isHardLockedQuestsToday.collectAsState()

    val coins by viewModel.coins.collectAsState()
    val remainingFreePasses by viewModel.remainingFreePassesToday.collectAsState()
    val listState = rememberLazyListState()

    var showSortMenu by remember { mutableStateOf(false) }
    var showViewMenu by remember { mutableStateOf(false) }
    var showGroupMenu by remember { mutableStateOf(false) }
    var showBottomSheet by remember { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    val showIcons = customizationInfo.showAppIcons
    val iconSizeDp = when (customizationInfo.appIconSize) {
        "Small" -> 28.dp
        "Large" -> 52.dp
        else -> 40.dp
    }
    val layout = customizationInfo.appListLayout

    var pulledDownHard by remember { mutableStateOf(false) }
    val nestedScrollConnection = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(
                available: Offset,
                source: NestedScrollSource
            ): Offset {
                if (available.y > 0 && listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset == 0) {
                    if (!pulledDownHard) {
                        pulledDownHard = true
                        viewModel.onSearchQueryChange("")
                        if (textFieldLoaded) {
                            keyboardController?.hide()
                        }
                        if (!navController.popBackStack()) {
                            navController.navigate(RootRoute.HomeScreen.route) {
                                launchSingleTop = true
                                popUpTo(RootRoute.HomeScreen.route) { inclusive = true }
                            }
                        }
                    }
                }
                if (available.y != 0f || available.x != 0f) {
                    if (textFieldLoaded) {
                        keyboardController?.hide()
                    }
                }
                return Offset.Zero
            }
        }
    }

    LaunchedEffect(Unit) { viewModel.loadHardLockedQuests() }

    Scaffold { innerPadding ->
        if (showDialog) {
            LauncherDialog(
                coins = coins,
                onDismiss = { viewModel.dismissDialog() },
                pkgName = selectedPackage,
                rootNavController = navController,
                minutesPerFiveCoins = minutesPer5Coins,
                unlockApp = {
                    viewModel.onConfirmUnlockApp(it)
                },
                startDestination = if (areHardLockedQuestsAvailable)
                    LauncherDialogRoutes.ShowAllQuest.route
                else if (coins >= 5) {
                    LauncherDialogRoutes.UnlockAppDialog.route
                } else {
                    LauncherDialogRoutes.LowCoins.route
                },
                remainingFreePasses = remainingFreePasses,
                onFreePassUsed = { viewModel.useFreePass() },
                areHardLockQuestsPresent = areHardLockedQuestsAvailable
            )
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .nestedScroll(nestedScrollConnection)
        ) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .consumeWindowInsets(innerPadding)
                    .padding(horizontal = 12.dp),
                contentPadding = innerPadding,
                state = listState,
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                // Search Input Item
                item(key = "search_input") {
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = viewModel::onSearchQueryChange,
                        label = { Text("Search Apps") },
                        placeholder = { Text("Type app name...") },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Default.Search,
                                contentDescription = "Search"
                            )
                        },
                        trailingIcon = {
                            if (searchQuery.isNotEmpty()) {
                                IconButton(onClick = { viewModel.onSearchQueryChange("") }) {
                                    Icon(
                                        imageVector = Icons.Default.Clear,
                                        contentDescription = "Clear search"
                                    )
                                }
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(focusRequester)
                            .onGloballyPositioned {
                                if (!textFieldLoaded) {
                                    textFieldLoaded = true
                                    if (customizationInfo.autoFocusSearch) {
                                        focusRequester.requestFocus()
                                    }
                                }
                            },
                        singleLine = true
                    )
                    Spacer(Modifier.height(4.dp))
                }

                // Quick Controls Header Bar
                item(key = "controls_bar") {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Quick Sort Button
                        Box {
                            OutlinedButton(
                                onClick = { showSortMenu = true },
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                            ) {
                                Icon(
                                    painter = painterResource(id = R.drawable.ic_sort),
                                    contentDescription = "Sort By",
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(Modifier.width(4.dp))
                                Text(
                                    text = when (customizationInfo.appSortOrder) {
                                        "Alphabetical" -> "A-Z"
                                        "ReverseAlphabetical" -> "Z-A"
                                        "MostUsed" -> "Used"
                                        "InstallDate" -> "Date"
                                        else -> "Sort"
                                    },
                                    style = MaterialTheme.typography.labelMedium
                                )
                            }
                            DropdownMenu(
                                expanded = showSortMenu,
                                onDismissRequest = { showSortMenu = false }
                            ) {
                                val sortOptions = listOf(
                                    "Alphabetical" to "Alphabetical (A-Z)",
                                    "ReverseAlphabetical" to "Reverse (Z-A)",
                                    "MostUsed" to "Most Used / Screentime",
                                    "InstallDate" to "Install Date (Newest)"
                                )
                                sortOptions.forEach { (key, label) ->
                                    DropdownMenuItem(
                                        text = { Text(label) },
                                        trailingIcon = {
                                            if (customizationInfo.appSortOrder == key) {
                                                Icon(Icons.Default.Check, contentDescription = "Active")
                                            }
                                        },
                                        onClick = {
                                            viewModel.setSortOrder(key)
                                            showSortMenu = false
                                        }
                                    )
                                }
                            }
                        }

                        // Quick View Mode Button
                        Box {
                            OutlinedButton(
                                onClick = { showViewMenu = true },
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                            ) {
                                Icon(
                                    painter = painterResource(id = R.drawable.ic_view_mode),
                                    contentDescription = "View Mode",
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(Modifier.width(4.dp))
                                Text(
                                    text = customizationInfo.appListLayout,
                                    style = MaterialTheme.typography.labelMedium
                                )
                            }
                            DropdownMenu(
                                expanded = showViewMenu,
                                onDismissRequest = { showViewMenu = false }
                            ) {
                                val layoutOptions = listOf(
                                    "List" to "List (Default)",
                                    "Compact" to "Compact List",
                                    "Grid2" to "Grid 2x",
                                    "Grid3" to "Grid 3x",
                                    "Grid4" to "Grid 4x"
                                )
                                layoutOptions.forEach { (key, label) ->
                                    DropdownMenuItem(
                                        text = { Text(label) },
                                        trailingIcon = {
                                            if (customizationInfo.appListLayout == key) {
                                                Icon(Icons.Default.Check, contentDescription = "Active")
                                            }
                                        },
                                        onClick = {
                                            viewModel.setViewMode(key)
                                            showViewMenu = false
                                        }
                                    )
                                }
                                HorizontalDivider()
                                DropdownMenuItem(
                                    text = { Text(if (customizationInfo.showAppIcons) "Hide App Icons" else "Show App Icons") },
                                    onClick = {
                                        viewModel.toggleShowIcons(!customizationInfo.showAppIcons)
                                        showViewMenu = false
                                    }
                                )
                            }
                        }

                        // Group By Button
                        Box {
                            OutlinedButton(
                                onClick = { showGroupMenu = true },
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                            ) {
                                Icon(
                                    painter = painterResource(id = R.drawable.ic_group_by),
                                    contentDescription = "Group By",
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(Modifier.width(4.dp))
                                Text(
                                    text = if (customizationInfo.appGroupBy == "None") "Group" else customizationInfo.appGroupBy,
                                    style = MaterialTheme.typography.labelMedium
                                )
                            }
                            DropdownMenu(
                                expanded = showGroupMenu,
                                onDismissRequest = { showGroupMenu = false }
                            ) {
                                val groupOptions = listOf(
                                    "None" to "None",
                                    "Alphabet" to "Alphabet (A-Z)",
                                    "Category" to "Category",
                                    "InstallDate" to "Install Date",
                                    "Status" to "Habit Status"
                                )
                                groupOptions.forEach { (key, label) ->
                                    DropdownMenuItem(
                                        text = { Text(label) },
                                        trailingIcon = {
                                            if (customizationInfo.appGroupBy == key) {
                                                Icon(Icons.Default.Check, contentDescription = "Active")
                                            }
                                        },
                                        onClick = {
                                            viewModel.setGroupBy(key)
                                            showGroupMenu = false
                                        }
                                    )
                                }
                            }
                        }

                        // Direct Edit / Pen Button
                        IconButton(onClick = { showBottomSheet = true }) {
                            Icon(
                                imageVector = Icons.Default.Edit,
                                contentDescription = "Drawer Settings",
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }

                // Loading or Empty State
                if (groups.isEmpty() || groups.all { it.apps.isEmpty() }) {
                    item(key = "empty_or_loading") {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 32.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            if (isLoading) {
                                CircularProgressIndicator()
                            } else {
                                Text(
                                    text = "No apps found",
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }

                // Render Groups (Windows Explorer-style headers)
                groups.forEach { group ->
                    if (group.apps.isNotEmpty()) {
                        if (group.title.isNotEmpty()) {
                            item(key = "header_${group.title}") {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(top = 12.dp, bottom = 4.dp)
                                ) {
                                    Text(
                                        text = "${group.title} (${group.apps.size})",
                                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                    HorizontalDivider(
                                        modifier = Modifier.padding(top = 4.dp),
                                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                                    )
                                }
                            }
                        }

                        when (layout) {
                            "Compact" -> {
                                items(group.apps, key = { "${group.title}_compact_${it.packageName}" }) { app ->
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .combinedClickable(
                                                onClick = { viewModel.onAppClick(app.packageName) },
                                                onLongClick = { viewModel.onLongAppClick(app.packageName) }
                                            )
                                            .padding(vertical = 4.dp, horizontal = 4.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        if (showIcons) {
                                            AppIconView(
                                                packageName = app.packageName,
                                                size = (iconSizeDp * 0.75f).coerceAtLeast(24.dp)
                                            )
                                            Spacer(modifier = Modifier.width(8.dp))
                                        }
                                        Text(
                                            text = app.name,
                                            style = MaterialTheme.typography.bodyLarge,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.weight(1f)
                                        )
                                    }
                                }
                            }

                            "Grid2", "Grid3", "Grid4" -> {
                                val columns = when (layout) {
                                    "Grid2" -> 2
                                    "Grid4" -> 4
                                    else -> 3
                                }
                                val chunks = group.apps.chunked(columns)
                                items(chunks, key = { chunk -> "${group.title}_chunk_${chunk.first().packageName}" }) { rowApps ->
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 4.dp),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        for (app in rowApps) {
                                            Column(
                                                modifier = Modifier
                                                    .weight(1f)
                                                    .combinedClickable(
                                                        onClick = { viewModel.onAppClick(app.packageName) },
                                                        onLongClick = { viewModel.onLongAppClick(app.packageName) }
                                                    )
                                                    .padding(vertical = 6.dp, horizontal = 2.dp),
                                                horizontalAlignment = Alignment.CenterHorizontally
                                            ) {
                                                if (showIcons) {
                                                    AppIconView(packageName = app.packageName, size = iconSizeDp)
                                                    Spacer(modifier = Modifier.height(4.dp))
                                                } else {
                                                    Surface(
                                                        shape = RoundedCornerShape(8.dp),
                                                        color = MaterialTheme.colorScheme.surfaceVariant,
                                                        modifier = Modifier.size(iconSizeDp)
                                                    ) {
                                                        Box(contentAlignment = Alignment.Center) {
                                                            Text(
                                                                text = app.name.firstOrNull()?.uppercase() ?: "?",
                                                                style = MaterialTheme.typography.titleMedium,
                                                                fontWeight = FontWeight.Bold,
                                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                                            )
                                                        }
                                                    }
                                                    Spacer(modifier = Modifier.height(4.dp))
                                                }
                                                Text(
                                                    text = app.name,
                                                    style = MaterialTheme.typography.bodySmall,
                                                    textAlign = TextAlign.Center,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                            }
                                        }
                                        val emptySlots = columns - rowApps.size
                                        if (emptySlots > 0) {
                                            repeat(emptySlots) {
                                                Spacer(modifier = Modifier.weight(1f))
                                            }
                                        }
                                    }
                                }
                            }

                            else -> {
                                // Default "List" layout
                                items(group.apps, key = { "${group.title}_list_${it.packageName}" }) { app ->
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .combinedClickable(
                                                onClick = { viewModel.onAppClick(app.packageName) },
                                                onLongClick = { viewModel.onLongAppClick(app.packageName) }
                                            )
                                            .padding(vertical = 6.dp, horizontal = 4.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        if (showIcons) {
                                            AppIconView(packageName = app.packageName, size = iconSizeDp)
                                            Spacer(modifier = Modifier.width(12.dp))
                                        }
                                        Text(
                                            text = app.name,
                                            style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Normal),
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.weight(1f)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                item(key = "bottom_spacer") {
                    Spacer(
                        Modifier.windowInsetsBottomHeight(
                            WindowInsets.systemBars
                        )
                    )
                }
            }
        }

        // Quick Drawer Customization Bottom Sheet
        if (showBottomSheet) {
            ModalBottomSheet(
                onDismissRequest = { showBottomSheet = false },
                sheetState = sheetState
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 12.dp)
                ) {
                    Text(
                        text = "Drawer Customization",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(16.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Show App Icons", style = MaterialTheme.typography.bodyLarge)
                        Switch(
                            checked = customizationInfo.showAppIcons,
                            onCheckedChange = { viewModel.toggleShowIcons(it) }
                        )
                    }

                    Spacer(Modifier.height(12.dp))

                    if (customizationInfo.showAppIcons) {
                        Text("App Icon Size", style = MaterialTheme.typography.labelLarge)
                        Spacer(Modifier.height(4.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            val sizes = listOf("Small" to "Small (28dp)", "Medium" to "Medium (40dp)", "Large" to "Large (52dp)")
                            sizes.forEach { (key, label) ->
                                FilterChip(
                                    selected = customizationInfo.appIconSize == key,
                                    onClick = { viewModel.setIconSize(key) },
                                    label = { Text(label) }
                                )
                            }
                        }
                        Spacer(Modifier.height(12.dp))
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text("Auto-Open Keyboard", style = MaterialTheme.typography.bodyLarge)
                            Text(
                                "Focus search input when drawer opens",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = customizationInfo.autoFocusSearch,
                            onCheckedChange = { viewModel.setAutoFocusSearch(it) }
                        )
                    }

                    Spacer(Modifier.height(20.dp))

                    Button(
                        onClick = {
                            showBottomSheet = false
                            navController.navigate(RootRoute.SettingsScreen.route)
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Settings, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Open All Settings & Controls")
                    }

                    Spacer(Modifier.height(24.dp))
                }
            }
        }
    }
}