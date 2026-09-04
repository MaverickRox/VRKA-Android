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
            color = VrkaTokens.TextPrimary,
        )

        // 1. STORAGE
        SettingsHeading("Storage")
        VrkaCard {
            VrkaSettingRow(
                title = "Download Directory",
                subtitle = if (settings.outputTreeUri.isBlank()) {
                    "Downloads/VRKA (recommended)"
                } else {
                    "Custom folder selected"
                },
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    VrkaOutlinedButton(
                        text = "Choose",
                        onClick = { folderPicker.launch(null) },
                    )
                    if (settings.outputTreeUri.isNotBlank()) {
                        VrkaTextButton(
                            text = "Reset",
                            onClick = { scope.launch { repository.setOutputTree("") } },
                        )
                    }
                }
            }
        }

        // 2. BROWSER SUBSYSTEM
        SettingsHeading("Browser Subsystem")
        VrkaCard {
            VrkaSettingRow(
                title = "Browser Engine",
                subtitle = "Mozilla GeckoView 153.0 (arm64-v8a)",
            ) {
                VrkaStatusBadge("Bundled", VrkaTokens.AccentLight)
            }

            VrkaDivider()

            VrkaSettingRow(
                title = "Ad & Tracker Blocking",
                subtitle = "uBlock Origin",
            ) {
                VrkaStatusBadge("Active", VrkaTokens.Success)
            }

            VrkaDivider()

            VrkaSettingRow(
                title = "Media Detection",
                subtitle = "Puemos HLS Discovery",
            ) {
                VrkaStatusBadge("Active", VrkaTokens.Success)
            }
        }

        // 3. COMPONENTS & UPDATES
        SettingsHeading("Components & Updates")
        Text(
            "Independently managed runtime and filter components.",
            style = MaterialTheme.typography.bodySmall,
            color = VrkaTokens.TextSecondary,
            modifier = Modifier.padding(start = 4.dp, bottom = 8.dp),
        )

        val isAnyCheckingOrUpdating = componentMap.values.any { it.isChecking || it.isUpdating }

        VrkaCard {
            componentMap.values.forEachIndexed { index, comp ->
                if (index > 0) VrkaDivider()
                VrkaSettingRow(
                    title = comp.name,
                    subtitle = "Installed: v${comp.installedVersion}",
                    isSubtitleMono = true,
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        when {
                            comp.isUpdating -> {
                                VrkaStatusBadge("Updating", VrkaTokens.Warning)
                            }
                            comp.isChecking -> {
                                VrkaStatusBadge("Checking", VrkaTokens.AccentLight)
                            }
                            comp.latestVersion != null && comp.latestVersion != comp.installedVersion -> {
                                VrkaStatusBadge("v${comp.latestVersion}", VrkaTokens.AccentLight, isMonospace = true)
                                VrkaOutlinedButton(
                                    text = "Update",
                                    onClick = { updateManager.applyUpdate(comp.id) },
                                    height = 32.dp,
                                )
                            }
                            comp.error != null -> {
                                VrkaStatusBadge("Error", VrkaTokens.Error)
                            }
                            else -> {
                                VrkaStatusBadge("Ready", VrkaTokens.Success)
                            }
                        }
                    }
                }
                if (comp.message.isNotBlank()) {
                    Text(
                        comp.message,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (comp.error != null) VrkaTokens.Error else VrkaTokens.TextSecondary,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                }
            }

            VrkaDivider()

            VrkaOutlinedButton(
                text = if (isAnyCheckingOrUpdating) "Checking updates..." else "Check All Updates",
                onClick = { updateManager.checkAllUpdates() },
                enabled = !isAnyCheckingOrUpdating,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp),
                height = 42.dp,
            )

            Spacer(Modifier.height(10.dp))
            VrkaDivider()

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "yt-dlp Channel",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = VrkaTokens.TextPrimary,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    UpdatePreference.entries.forEach { item ->
                        VrkaChip(
                            selected = settings.updatePreference == item,
                            onClick = { scope.launch { repository.setUpdatePreference(item) } },
                            label = item.label,
                        )
                    }
                }
            }
        }

        // 4. APPEARANCE
        SettingsHeading("Appearance")
        VrkaCard {
            Text(
                "Theme Mode",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = VrkaTokens.TextPrimary,
                modifier = Modifier.padding(bottom = 10.dp),
            )
            VrkaSegmentedControl(
                items = ThemeMode.entries,
                selectedItem = settings.themeMode,
                onItemSelected = { mode -> scope.launch { repository.setThemeMode(mode) } },
                label = { it.label },
            )

            Spacer(Modifier.height(12.dp))
            VrkaDivider()

            VrkaSettingRow(
                title = "AMOLED Black",
                subtitle = "Use pure black backgrounds when Dark mode is active",
            ) {
                Switch(
                    checked = settings.amoled,
                    enabled = settings.themeMode == ThemeMode.DARK,
                    onCheckedChange = { scope.launch { repository.setAmoled(it) } },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color.White,
                        checkedTrackColor = VrkaTokens.Accent,
                    ),
                )
            }
        }

        // 5. CONCURRENCY
        SettingsHeading("Concurrency")
        VrkaCard {
            Text(
                "One active job at a time; additional jobs wait in the queue to optimize performance and prevent thermal throttling.",
                style = MaterialTheme.typography.bodySmall,
                color = VrkaTokens.TextSecondary,
            )
        }

        // 6. ABOUT
        SettingsHeading("About")
        VrkaCard(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = 20.dp,
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(
                        text = "VRKA v4.0",
                        style = MaterialTheme.typography.titleLarge.copy(
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.sp,
                        ),
                        color = VrkaTokens.TextPrimary,
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "By MVRK",
                        style = MaterialTheme.typography.bodyMedium,
                        color = VrkaTokens.TextSecondary,
                    )
                }

                androidx.compose.foundation.Image(
                    painter = painterResource(R.drawable.vrka_logo_512),
                    contentDescription = "VRKA Logo",
                    contentScale = androidx.compose.ui.layout.ContentScale.Fit,
                    modifier = Modifier.size(76.dp),
                )
            }
        }
    }
}

@Composable
private fun SettingsHeading(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
        color = VrkaTokens.TextPrimary,
        modifier = Modifier.padding(top = 20.dp, bottom = 8.dp, start = 4.dp),
    )
}
