package com.mvrk.vrka

import android.content.Intent
import androidx.compose.animation.animateColorAsState
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

@Composable
internal fun SettingsScreen(
    settings: AppSettings,
    runtime: RuntimeStatus,
    repository: SettingsRepository,
    onUpdateRuntime: (UpdatePreference) -> Unit,
    onLaunchGeckoShell: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
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
        Text("Settings", style = MaterialTheme.typography.headlineSmall)

        SettingsHeading("Download location")
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

        SettingsHeading("yt-dlp runtime")
        Text(
            runtime.message,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            UpdatePreference.entries.forEach { item ->
                FilterChip(
                    selected = settings.updatePreference == item,
                    onClick = {
                        scope.launch { repository.setUpdatePreference(item) }
                    },
                    label = { Text(item.label) },
                )
            }
        }
        Button(
            onClick = { onUpdateRuntime(settings.updatePreference) },
            enabled = !runtime.busy,
            modifier = Modifier.padding(top = 8.dp),
        ) {
            Text(if (runtime.busy) "Updating…" else "Check for yt-dlp update")
        }
        Text(
            "A failed update retains the bundled known-good runtime.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 6.dp),
        )

        SettingsHeading("Browser fallback")
        SettingSwitch(
            label = "Block confident ads and popups",
            description = "Uncertain player, challenge, and session traffic is allowed.",
            checked = settings.adBlocking,
            onCheckedChange = {
                scope.launch { repository.setAdBlocking(it) }
            },
        )

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

        SettingsHeading("Concurrency")
        Text(
            "One active job at a time; additional jobs wait in the queue to limit heat and memory use.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        SettingsHeading("GeckoView Engine")
        Text(
            "Mozilla GeckoView 153.0 (arm64-v8a)",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (onLaunchGeckoShell != null) {
            Button(
                onClick = onLaunchGeckoShell,
                modifier = Modifier.padding(top = 8.dp),
            ) {
                Text("Launch GeckoView Shell")
            }
        }

        SettingsHeading("About")
        Text("VRKA Android 1.0.0 • MVRK")
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
        modifier = Modifier.padding(top = 24.dp, bottom = 8.dp),
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
            Text(label)
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
