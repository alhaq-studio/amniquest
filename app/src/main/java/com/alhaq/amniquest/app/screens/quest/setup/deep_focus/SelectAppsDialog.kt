package com.alhaq.amniquest.app.screens.quest.setup.deep_focus

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.alhaq.amniquest.core.core.utils.managers.getCachedApps
import com.alhaq.amniquest.core.core.utils.managers.reloadApps
import com.alhaq.amniquest.data.AppInfo

@Composable
fun SelectAppsDialog(
    selectedApps: MutableList<String>,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit = {}
) {
    val context = LocalContext.current
    val apps = remember { mutableStateOf(getCachedApps(context)) }
    LaunchedEffect(Unit) {
        val reloaded = reloadApps(context.packageManager, context).getOrNull()
        if (!reloaded.isNullOrEmpty()) {
            apps.value = reloaded
        }
    }
    var searchQuery by remember { mutableStateOf("") }

    // Filtered app list
    val filteredApps = apps.value.filter { it.name.contains(searchQuery, ignoreCase = true) }

    AlertDialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(dismissOnClickOutside = false),
        title = { Text("Select Apps") },
        text = {
            Column {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    label = { Text("Search apps") },
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(8.dp))

                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 400.dp)
                ) {
                    items(filteredApps, key = { it.packageName }) { app ->
                        val appName = app.name
                        val packageName = app.packageName
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    if (selectedApps.contains(packageName)) {
                                        selectedApps.remove(packageName)
                                    } else {
                                        selectedApps.add(packageName)
                                    }
                                }
                                .padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(
                                checked = selectedApps.contains(packageName),
                                onCheckedChange = {
                                    if (it) selectedApps.add(packageName) else selectedApps.remove(packageName)
                                }
                            )

                            com.alhaq.amniquest.app.screens.launcher.AppIconView(
                                packageName = packageName,
                                size = 32.dp,
                                modifier = Modifier.padding(horizontal = 8.dp)
                            )

                            Text(
                                text = appName,
                                modifier = Modifier.padding(start = 4.dp)
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onConfirm()
                onDismiss()}) {
                Text("OK")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}
