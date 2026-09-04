package com.mvrk.vrka

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.Image
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
import androidx.compose.ui.layout.ContentScale
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
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        // Screen Header
        Text(
            text = "Settings",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = VrkaTokens.TextPrimary,
        )

        // 1. DOWNLOAD LOCATION
        VrkaSection(title = "Download Location") {
            Text(
                text = if (settings.outputTreeUri.isBlank()) {
                    "Downloads/VRKA (recommended default)"
                } else {
                    "Custom folder selected"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = VrkaTokens.TextSecondary,
            )
            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Button(
                    onClick = { folderPicker.launch(null) },
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = VrkaTokens.Accent),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
                ) {
                    Text("Choose folder", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                }
                if (settings.outputTreeUri.isNotBlank()) {
                    OutlinedButton(
                        onClick = { scope.launch { repository.setOutputTree("") } },
                        shape = RoundedCornerShape(12.dp),
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
                    ) {
                        Text("Reset to default", fontSize = 13.sp, color = VrkaTokens.TextSecondary)
                    }
                }
            }
        }

        // 2. BROWSER SUBSYSTEM
        VrkaSection(title = "Browser Subsystem") {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f, fill = false).padding(end = 8.dp)) {
                    Text(
                        "Browser Engine",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = VrkaTokens.TextPrimary,
                    )
                    Text(
                        "Mozilla GeckoView 153.0 (arm64-v8a)",
                        style = MaterialTheme.typography.bodySmall,
                        color = VrkaTokens.TextSecondary,
                    )
                }
                VrkaStatusBadge(label = "Bundled", color = VrkaTokens.AccentLight)
            }

            HorizontalDivider(color = VrkaTokens.BorderSubtle, modifier = Modifier.padding(vertical = 12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Ad & Tracker Blocking",
                    style = MaterialTheme.typography.bodyMedium,
                    color = VrkaTokens.TextPrimary,
                    modifier = Modifier.weight(1f, fill = false).padding(end = 8.dp),
                )
                VrkaStatusBadge(label = "uBlock Origin Active", color = VrkaTokens.Success)
            }

            Spacer(Modifier.height(10.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Media Detection",
                    style = MaterialTheme.typography.bodyMedium,
                    color = VrkaTokens.TextPrimary,
                    modifier = Modifier.weight(1f, fill = false).padding(end = 8.dp),
                )
                VrkaStatusBadge(label = "Puemos HLS Active", color = VrkaTokens.Success)
            }
        }

        // 3. COMPONENTS & UPDATES (Consolidated)
        VrkaSection(
            title = "Components & Runtime",
            subtitle = "Independently managed extraction engine and filter components.",
        ) {
            val components = componentMap.values.toList()
            components.forEachIndexed { index, comp ->
                ComponentRow(
                    component = comp,
                    onUpdate = { updateManager.applyUpdate(comp.id) },
                )
                if (index < components.size - 1) {
                    HorizontalDivider(color = VrkaTokens.BorderSubtle, modifier = Modifier.padding(vertical = 10.dp))
                }
            }

            HorizontalDivider(color = VrkaTokens.BorderSubtle, modifier = Modifier.padding(vertical = 12.dp))

            val isAnyCheckingOrUpdating = componentMap.values.any { it.isChecking || it.isUpdating }

            Button(
                onClick = { updateManager.checkAllUpdates() },
                enabled = !isAnyCheckingOrUpdating,
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = VrkaTokens.AccentContainer,
                    contentColor = VrkaTokens.AccentLight,
                ),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    if (isAnyCheckingOrUpdating) "Checking updates..." else "Check all updates",
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 13.sp,
                )
            }

            Spacer(Modifier.height(12.dp))

            // yt-dlp release channel selector row
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    "yt-dlp Channel",
                    style = MaterialTheme.typography.bodyMedium,
                    color = VrkaTokens.TextPrimary,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    UpdatePreference.entries.forEach { item ->
                        val isSelected = settings.updatePreference == item
                        Surface(
                            onClick = { scope.launch { repository.setUpdatePreference(item) } },
                            shape = RoundedCornerShape(8.dp),
                            color = if (isSelected) VrkaTokens.Accent else VrkaTokens.SurfaceInset,
                            border = androidx.compose.foundation.BorderStroke(
                                1.dp,
                                if (isSelected) VrkaTokens.BorderActive else VrkaTokens.BorderSubtle,
                            ),
                        ) {
                            Text(
                                text = item.label,
                                style = MaterialTheme.typography.labelSmall,
                                fontFamily = VrkaMonoFamily,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                color = if (isSelected) Color.White else VrkaTokens.TextSecondary,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            )
                        }
                    }
                }
            }
        }

        // 4. APPEARANCE
        VrkaSection(title = "Appearance") {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Theme Mode",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = VrkaTokens.TextPrimary,
                )
                ThemeModeControl(
                    selected = settings.themeMode,
                    onSelected = { mode -> scope.launch { repository.setThemeMode(mode) } },
                )
            }

            HorizontalDivider(color = VrkaTokens.BorderSubtle, modifier = Modifier.padding(vertical = 12.dp))

            SettingSwitch(
                label = "AMOLED Black",
                description = "Use pure #000000 black background when Dark mode is active.",
                checked = settings.amoled,
                enabled = settings.themeMode == ThemeMode.DARK,
                onCheckedChange = { scope.launch { repository.setAmoled(it) } },
            )
        }

        // 5. CONCURRENCY
        VrkaSection(title = "Concurrency") {
            Text(
                text = "One active download at a time; additional jobs wait in queue to limit device temperature, battery draw, and memory pressure.",
                style = MaterialTheme.typography.bodySmall,
                color = VrkaTokens.TextSecondary,
            )
        }

        // 6. ABOUT
        VrkaSection(title = "About") {
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
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontFamily = VrkaMonoFamily,
                            fontWeight = FontWeight.Bold,
                            fontSize = 18.sp,
                        ),
                        color = VrkaTokens.TextPrimary,
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "By MVRK",
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontFamily = VrkaMonoFamily,
                            fontSize = 14.sp,
                        ),
                        color = VrkaTokens.TextSecondary,
                    )
                }

                Image(
                    painter = painterResource(R.drawable.vrka_logo_512),
                    contentDescription = "VRKA Logo",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.size(72.dp),
                )
            }
        }

        // Balanced footer spacing - terminates naturally above floating navigation bar
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun ComponentRow(
    component: ComponentStatus,
    onUpdate: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
            Text(
                text = component.name,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = VrkaTokens.TextPrimary,
            )
            Text(
                text = "v${component.installedVersion}",
                fontFamily = VrkaMonoFamily,
                fontSize = 12.sp,
                color = VrkaTokens.TextTertiary,
                modifier = Modifier.padding(top = 2.dp),
            )
            if (component.message.isNotBlank() && component.error != null) {
                Text(
                    text = component.message,
                    style = MaterialTheme.typography.labelSmall,
                    color = VrkaTokens.Error,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }

        val hasUpdate = component.latestVersion != null && component.latestVersion != component.installedVersion
        when {
            component.isUpdating -> {
                VrkaStatusBadge(label = "Updating...", color = VrkaTokens.AccentLight)
            }
            component.isChecking -> {
                VrkaStatusBadge(label = "Checking...", color = VrkaTokens.TextSecondary)
            }
            hasUpdate -> {
                Button(
                    onClick = onUpdate,
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = VrkaTokens.Accent),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                ) {
                    Text("Update v${component.latestVersion}", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }
            component.error != null -> {
                VrkaStatusBadge(label = "Check failed", color = VrkaTokens.Error)
            }
            else -> {
                VrkaStatusBadge(
                    label = if (component.message.isNotBlank()) component.message else "Ready",
                    color = VrkaTokens.Success,
                )
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
        color = VrkaTokens.SurfaceInset,
        shape = RoundedCornerShape(12.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, VrkaTokens.BorderSubtle),
        modifier = Modifier
            .width(200.dp)
            .height(42.dp)
            .selectableGroup(),
    ) {
        Row(Modifier.padding(3.dp)) {
            ThemeMode.entries.forEach { mode ->
                val isSelected = selected == mode
                val bg by animateColorAsState(
                    if (isSelected) VrkaTokens.AccentContainer else Color.Transparent,
                    label = "theme segment",
                )
                Surface(
                    color = bg,
                    shape = RoundedCornerShape(9.dp),
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxSize()
                        .selectable(
                            selected = isSelected,
                            role = Role.RadioButton,
                            onClick = { onSelected(mode) },
                        ),
                ) {
                    Row(
                        modifier = Modifier.fillMaxSize(),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            painter = painterResource(
                                if (mode == ThemeMode.LIGHT) R.drawable.ic_sun
                                else R.drawable.ic_moon,
                            ),
                            contentDescription = null,
                            tint = if (isSelected) VrkaTokens.AccentLight else VrkaTokens.TextSecondary,
                            modifier = Modifier.size(15.dp),
                        )
                        Text(
                            text = mode.label,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                            color = if (isSelected) VrkaTokens.AccentLight else VrkaTokens.TextSecondary,
                            modifier = Modifier.padding(start = 6.dp),
                        )
                    }
                }
            }
        }
    }
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
            .alpha(if (enabled) 1f else 0.45f),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(
                label,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = VrkaTokens.TextPrimary,
            )
            Text(
                description,
                style = MaterialTheme.typography.bodySmall,
                color = VrkaTokens.TextSecondary,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        Switch(
            checked = checked,
            enabled = enabled,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = VrkaTokens.Accent,
                uncheckedTrackColor = VrkaTokens.SurfaceInset,
                uncheckedThumbColor = VrkaTokens.TextSecondary,
            ),
        )
    }
}

