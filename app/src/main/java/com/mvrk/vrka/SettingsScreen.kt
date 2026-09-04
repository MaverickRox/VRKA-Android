package com.mvrk.vrka

import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
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
            .padding(horizontal = 20.dp, vertical = 16.dp),
    ) {
        Text(
            "Settings",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
        )

        // 1. DOWNLOAD LOCATION
        SettingsHeading("Download Location")
        Surface(
            shape = RoundedCornerShape(18.dp),
            color = VrkaSurfaceCard,
            contentColor = MaterialTheme.colorScheme.onSurface,
            border = BorderStroke(1.dp, VrkaCardBorder),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = if (settings.outputTreeUri.isBlank()) {
                        "Downloads/VRKA (recommended)"
                    } else {
                        "Custom folder selected"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Button(
                        onClick = { folderPicker.launch(null) },
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = VrkaPurple),
                    ) {
                        Text("Choose folder")
                    }
                    if (settings.outputTreeUri.isNotBlank()) {
                        OutlinedButton(
                            onClick = { scope.launch { repository.setOutputTree("") } },
                            shape = RoundedCornerShape(12.dp),
                        ) {
                            Text("Use Downloads")
                        }
                    }
                }
            }
        }

        // 2. BROWSER SUBSYSTEM
        SettingsHeading("Browser Subsystem")
        Surface(
            shape = RoundedCornerShape(18.dp),
            color = VrkaSurfaceCard,
            contentColor = MaterialTheme.colorScheme.onSurface,
            border = BorderStroke(1.dp, VrkaCardBorder),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f, fill = false).padding(end = 8.dp)) {
                        Text("Browser Engine", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        Text("Mozilla GeckoView 153.0 (arm64-v8a)", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = VrkaPurple.copy(alpha = 0.2f),
                    ) {
                        Text(
                            "Bundled",
                            fontSize = 11.sp,
                            maxLines = 1,
                            fontWeight = FontWeight.SemiBold,
                            color = VrkaPurpleLight,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                        )
                    }
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 2.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Ad & Tracker Blocking", fontSize = 13.sp, modifier = Modifier.weight(1f))
                    Text("uBlock Origin Active", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = VrkaSuccess)
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Media Detection", fontSize = 13.sp, modifier = Modifier.weight(1f))
                    Text("Puemos Discovery Active", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = VrkaSuccess)
                }
            }
        }

        // 3. COMPONENTS & UPDATES
        SettingsHeading("Components & Updates")
        Text(
            "Independently managed runtime and filter components.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 8.dp),
        )

        componentMap.values.forEach { comp ->
            ComponentUpdateCard(
                component = comp,
                onCheck = { updateManager.checkUpdate(comp.id) },
                onUpdate = { updateManager.applyUpdate(comp.id) },
            )
            Spacer(modifier = Modifier.height(8.dp))
        }

        val isAnyCheckingOrUpdating = componentMap.values.any { it.isChecking || it.isUpdating }

        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedButton(
                onClick = { updateManager.checkAllUpdates() },
                enabled = !isAnyCheckingOrUpdating,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (isAnyCheckingOrUpdating) "Checking updates..." else "Check All Updates")
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("yt-dlp Channel:", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            UpdatePreference.entries.forEach { item ->
                FilterChip(
                    selected = settings.updatePreference == item,
                    onClick = { scope.launch { repository.setUpdatePreference(item) } },
                    label = { Text(item.label) },
                    shape = RoundedCornerShape(14.dp),
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = VrkaPurple,
                        selectedLabelColor = Color.White,
                    ),
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
        Surface(
            shape = RoundedCornerShape(18.dp),
            color = VrkaSurfaceCard,
            contentColor = MaterialTheme.colorScheme.onSurface,
            border = BorderStroke(1.dp, VrkaCardBorder),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                "One active job at a time; additional jobs wait in the queue to limit device heat and memory pressure.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp),
            )
        }

        // 6. ABOUT
        SettingsHeading("About")
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
            color = VrkaSurfaceCard,
            contentColor = MaterialTheme.colorScheme.onSurface,
            border = BorderStroke(1.dp, VrkaCardBorder),
            shape = RoundedCornerShape(18.dp),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 20.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(
                        text = "VRKA v4.0",
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontFamily = VrkaMonoFamily,
                            fontWeight = FontWeight.Bold,
                            fontSize = 18.sp,
                        ),
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "By MVRK",
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontFamily = VrkaMonoFamily,
                            fontSize = 14.sp,
                        ),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                androidx.compose.foundation.Image(
                    painter = painterResource(R.drawable.vrka_logo_512),
                    contentDescription = "VRKA Logo",
                    contentScale = androidx.compose.ui.layout.ContentScale.Fit,
                    modifier = Modifier.size(80.dp),
                )
            }
        }

        Spacer(Modifier.height(100.dp))
    }
}

@Composable
private fun ComponentUpdateCard(
    component: ComponentStatus,
    onCheck: () -> Unit,
    onUpdate: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = VrkaSurfaceCard,
        contentColor = MaterialTheme.colorScheme.onSurface,
        border = BorderStroke(1.dp, VrkaCardBorder),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .size(7.dp)
                            .clip(CircleShape)
                            .background(if (component.error != null) MaterialTheme.colorScheme.error else VrkaSuccess),
                    )
                    Column {
                        Text(component.name, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                        Text(
                            text = "Installed: v${component.installedVersion}",
                            fontFamily = VrkaMonoFamily,
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (component.latestVersion != null && component.latestVersion != component.installedVersion) {
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = VrkaPurple.copy(alpha = 0.2f),
                    ) {
                        Text(
                            text = "Update: v${component.latestVersion}",
                            fontFamily = VrkaMonoFamily,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = VrkaPurpleLight,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                        )
                    }
                }
            }

            if (component.message.isNotBlank()) {
                Text(
                    component.message,
                    fontSize = 11.sp,
                    color = if (component.error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(
                    onClick = onCheck,
                    enabled = !component.isChecking && !component.isUpdating,
                    shape = RoundedCornerShape(10.dp),
                ) {
                    Text(if (component.isChecking) "Checking..." else "Check")
                }

                if (component.latestVersion != null && component.latestVersion != component.installedVersion) {
                    Button(
                        onClick = onUpdate,
                        enabled = !component.isUpdating,
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = VrkaPurple),
                        modifier = Modifier.padding(start = 8.dp),
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
        color = VrkaSurfaceCard,
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, VrkaCardBorder),
        modifier = Modifier.width(232.dp).height(50.dp).selectableGroup(),
    ) {
        Row(Modifier.padding(4.dp)) {
            ThemeMode.entries.forEach { mode ->
                val isSelected = selected == mode
                val color by animateColorAsState(
                    if (isSelected) VrkaPurple.copy(alpha = 0.22f)
                    else Color.Transparent,
                    label = "theme segment",
                )
                Surface(
                    color = color,
                    shape = RoundedCornerShape(12.dp),
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
                            tint = if (isSelected) VrkaPurpleLight
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(17.dp),
                        )
                        Text(
                            mode.label,
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                            color = if (isSelected) VrkaPurpleLight else MaterialTheme.colorScheme.onSurfaceVariant,
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
        color = MaterialTheme.colorScheme.onSurface,
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
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(label, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
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
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = VrkaPurple,
            ),
        )
    }
}

