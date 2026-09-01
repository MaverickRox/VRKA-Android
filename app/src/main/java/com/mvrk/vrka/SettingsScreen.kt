package com.mvrk.vrka

import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch

@Composable
internal fun SettingsScreen(
    settings: AppSettings,
    runtime: RuntimeStatus,
    repository: SettingsRepository,
    onUpdateRuntime: (UpdatePreference) -> Unit,
    onLaunchBrowser: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val updateManager = remember { ComponentUpdateManager.getInstance(context) }
    val componentMap by updateManager.components.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) {
        updateManager.refreshInstalledVersions()
    }

    val folderPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            }
            scope.launch { repository.setOutputTree(uri.toString()) }
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(18.dp),
    ) {
        Text("Settings", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)

        // 1. DOWNLOAD LOCATION
        SettingsHeading("Download Location")
        Text(
            if (settings.outputTreeUri.isBlank()) {
                "Downloads/VRKA (recommended)"
            } else {
                "Custom folder selected"
            },
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(onClick = { folderPicker.launch(null) }) { Text("Choose folder") }
            if (settings.outputTreeUri.isNotBlank()) {
                OutlinedButton(
                    onClick = { scope.launch { repository.setOutputTree("") } },
                ) { Text("Use Downloads") }
            }
        }

        // 2. BROWSER SUBSYSTEM
        SettingsHeading("Browser Subsystem")
        Card(
            modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
        ) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text("Browser Engine", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        Text("Mozilla GeckoView 153.0 (arm64-v8a)", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = MaterialTheme.colorScheme.primaryContainer
                    ) {
                        Text("Bundled", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
                    }
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Ad & Tracker Blocking", fontSize = 13.sp)
                    Text("uBlock Origin Active", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Media Detection", fontSize = 13.sp)
                    Text("Puemos Discovery Active", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                }

                if (onLaunchBrowser != null) {
                    Button(
                        onClick = onLaunchBrowser,
                        modifier = Modifier.fillMaxWidth().padding(top = 6.dp)
                    ) {
                        Text("Open VRKA Browser", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        // 3. COMPONENTS & UPDATES
        SettingsHeading("Components & Updates")
        Text(
            "Independently managed runtime and filter components.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 8.dp)
        )

        componentMap.values.forEach { comp ->
            ComponentUpdateCard(
                component = comp,
                onCheck = { updateManager.checkUpdate(comp.id) },
                onUpdate = { updateManager.applyUpdate(comp.id) }
            )
            Spacer(modifier = Modifier.height(8.dp))
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(
                onClick = { updateManager.checkAllUpdates() },
                modifier = Modifier.weight(1f)
            ) {
                Text("Check All Updates")
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("yt-dlp Channel:", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            UpdatePreference.entries.forEach { item ->
                FilterChip(
                    selected = settings.updatePreference == item,
                    onClick = { scope.launch { repository.setUpdatePreference(item) } },
                    label = { Text(item.label) },
                )
            }
        }

        // 4. APPEARANCE
        SettingsHeading("Appearance")
        ThemeModeControl(
            selected = settings.themeMode,
            onSelected = { mode -> scope.launch { repository.setThemeMode(mode) } },
        )
        SettingSwitch(
            label = "AMOLED black",
            description = "Use true black backgrounds when Dark mode is active.",
            checked = settings.amoled,
            enabled = settings.themeMode == ThemeMode.DARK,
            onCheckedChange = { scope.launch { repository.setAmoled(it) } },
        )

        // 5. CONCURRENCY
        SettingsHeading("Concurrency")
        Text(
            "One active job at a time; additional jobs wait in the queue to limit device heat and memory pressure.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        // 6. ABOUT
        SettingsHeading("About")
        Card(
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
        ) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("VRKA Android 4.0.1", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                Text("Build 017 Desktop Parity Edition", fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
                Text("Architecture: arm64-v8a", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("Mozilla GeckoView 153.0 • uBlock Origin 1.74.0 • Puemos Stream Discovery", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("© 2026 MVRK • Open Source & Licensed Components", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun ComponentUpdateCard(
    component: ComponentStatus,
    onCheck: () -> Unit,
    onUpdate: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(component.name, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                    Text("Installed: v${component.installedVersion}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (component.latestVersion != null && component.latestVersion != component.installedVersion) {
                    Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = MaterialTheme.colorScheme.tertiaryContainer
                    ) {
                        Text("Update: v${component.latestVersion}", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onTertiaryContainer, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
                    }
                }
            }

            if (component.message.isNotBlank()) {
                Text(component.message, fontSize = 11.sp, color = if (component.error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(
                    onClick = onCheck,
                    enabled = !component.isChecking && !component.isUpdating
                ) {
                    Text(if (component.isChecking) "Checking..." else "Check")
                }

                if (component.latestVersion != null && component.latestVersion != component.installedVersion) {
                    Button(
                        onClick = onUpdate,
                        enabled = !component.isUpdating,
                        modifier = Modifier.padding(start = 8.dp)
                    ) {
                        Text(if (component.isUpdating) "Updating..." else "Update")
                    }
                }
            }
        }
    }
}

@Composable
private fun ThemeModeControl(
    selected: ThemeMode,
    onSelected: (ThemeMode) -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.width(232.dp).height(52.dp).selectableGroup(),
    ) {
        Row(Modifier.padding(4.dp)) {
            ThemeMode.entries.forEach { mode ->
                val isSelected = selected == mode
                val color by animateColorAsState(
                    if (isSelected) MaterialTheme.colorScheme.primaryContainer
                    else Color.Transparent,
                    label = "theme segment",
                )
                Surface(
                    color = color,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.weight(1f).fillMaxSize(),
                ) {
                    Row(
                        modifier = Modifier
                            .selectable(
                                selected = isSelected,
                                role = Role.RadioButton,
                                onClick = { onSelected(mode) },
                            )
                            .padding(horizontal = 10.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            painter = painterResource(
                                if (mode == ThemeMode.LIGHT) R.drawable.ic_sun
                                else R.drawable.ic_moon,
                            ),
                            contentDescription = null,
                            tint = if (isSelected) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp),
                        )
                        Text(
                            mode.label,
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                            modifier = Modifier.padding(start = 7.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsHeading(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(top = 22.dp, bottom = 8.dp),
    )
}

@Composable
private fun SettingSwitch(
    label: String,
    description: String,
    checked: Boolean,
    enabled: Boolean = true,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .alpha(if (enabled) 1f else 0.5f)
            .padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(label, fontWeight = FontWeight.SemiBold)
            Text(
                description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(
            checked = checked,
            enabled = enabled,
            onCheckedChange = onCheckedChange,
        )
    }
}
