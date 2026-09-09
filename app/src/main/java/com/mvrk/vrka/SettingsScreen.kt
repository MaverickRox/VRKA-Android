package com.mvrk.vrka

import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.platform.LocalUriHandler
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
    diagnostics: List<DiagnosticEntry> = emptyList(),
    onClearDiagnostics: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val updateManager = remember { ComponentUpdateManager.getInstance(context) }
    val componentMap by updateManager.components.collectAsStateWithLifecycle()
    val appUpdateManager = remember { com.mvrk.vrka.update.AppUpdateManager.getInstance(context, repository) }
    val appUpdateState by appUpdateManager.checkState.collectAsStateWithLifecycle()
    var manualCheckRequested by remember { mutableStateOf(false) }

    LaunchedEffect(appUpdateState) {
        if (manualCheckRequested) {
            when (val s = appUpdateState) {
                is com.mvrk.vrka.update.AppUpdateCheckState.UpToDate -> {
                    Toast.makeText(
                        context,
                        "You're on the latest version (v${BuildConfig.VERSION_NAME})",
                        Toast.LENGTH_SHORT,
                    ).show()
                    manualCheckRequested = false
                }
                is com.mvrk.vrka.update.AppUpdateCheckState.Error -> {
                    Toast.makeText(context, s.message, Toast.LENGTH_LONG).show()
                    manualCheckRequested = false
                }
                is com.mvrk.vrka.update.AppUpdateCheckState.UpdateAvailable -> {
                    manualCheckRequested = false
                }
                else -> Unit
            }
        }
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
            color = VrkaTokens.TextPrimary,
        )

        SettingsHeading("Download Location")
        VrkaSectionContainer {
            VrkaSettingRow(
                title = "Destination",
                subtitle = OutputPublisher.formatDisplayPath(settings.outputTreeUri),
                isSubtitleMono = true,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    VrkaOutlinedButton(
                        text = "Change",
                        onClick = { folderPicker.launch(null) },
                        height = 34.dp,
                    )
                    if (settings.outputTreeUri.isNotBlank()) {
                        VrkaTextButton(
                            text = "Reset",
                            onClick = { scope.launch { repository.setOutputTree("") } },
                        )
                    }
                }
            }

            Spacer(Modifier.height(10.dp))
            VrkaDivider()
            Spacer(Modifier.height(10.dp))

            VrkaSegmentedControl(
                items = SaveLocationMode.entries,
                selectedItem = settings.saveLocationMode,
                onItemSelected = { mode -> scope.launch { repository.setSaveLocationMode(mode) } },
                label = { it.label },
                isMonospace = true,
            )

            Text(
                text = if (settings.saveLocationMode == SaveLocationMode.REMEMBER_LOCATION) {
                    "Downloads will be saved directly to the selected location."
                } else {
                    "You will be prompted to choose a location for each download."
                },
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = VrkaMonoFamily),
                color = VrkaTokens.TextTertiary,
                modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
            )
        }

        SettingsHeading("Appearance")
        VrkaSectionContainer {
            Text(
                "Theme Mode",
                style = MaterialTheme.typography.bodyMedium.copy(fontFamily = VrkaMonoFamily),
                fontWeight = FontWeight.SemiBold,
                color = VrkaTokens.TextPrimary,
                modifier = Modifier.padding(top = 8.dp, bottom = 10.dp),
            )
            VrkaSegmentedControl(
                items = ThemeMode.entries,
                selectedItem = settings.themeMode,
                onItemSelected = { mode -> scope.launch { repository.setThemeMode(mode) } },
                label = { it.label },
                isMonospace = true,
            )

            Spacer(Modifier.height(10.dp))
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

            Spacer(Modifier.height(10.dp))
            VrkaDivider()

            Text(
                "Font",
                style = MaterialTheme.typography.bodyMedium.copy(fontFamily = VrkaMonoFamily),
                fontWeight = FontWeight.SemiBold,
                color = VrkaTokens.TextPrimary,
                modifier = Modifier.padding(top = 8.dp, bottom = 10.dp),
            )
            VrkaSegmentedControl(
                items = FontPreference.entries,
                selectedItem = settings.fontPreference,
                onItemSelected = { font -> scope.launch { repository.setFontPreference(font) } },
                label = { it.label },
                isMonospace = true,
            )
        }

        SettingsHeading("Components & Updates")
        Text(
            "Independently managed runtime and filter components.",
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = VrkaMonoFamily),
            color = VrkaTokens.TextSecondary,
            modifier = Modifier.padding(start = 4.dp, bottom = 8.dp),
        )

        val isAnyCheckingOrUpdating = componentMap.values.any { it.isChecking || it.isUpdating }

        VrkaSectionContainer {
            componentMap.values.forEachIndexed { index, comp ->
                if (index > 0) VrkaDivider()
                val isBundled = comp.id == ComponentUpdateManager.ID_UBLOCK || comp.id == ComponentUpdateManager.ID_PUEMOS
                val cleanInstalled = ComponentUpdateManager.cleanVersionString(comp.installedVersion)
                val displayVer = if (cleanInstalled.startsWith("v")) cleanInstalled else "v$cleanInstalled"
                VrkaSettingRow(
                    title = comp.name,
                    subtitle = if (isBundled) "Installed: $displayVer (Bundled)" else "Installed: $displayVer",
                    isSubtitleMono = true,
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        when {
                            isBundled -> {
                                VrkaStatusBadge("Bundled", VrkaTokens.AccentLight, isMonospace = true)
                            }
                            comp.isUpdating -> {
                                val updateLabel = when (comp.updateState) {
                                    ComponentUpdateState.DOWNLOADING -> "Downloading"
                                    ComponentUpdateState.VERIFYING -> "Verifying"
                                    ComponentUpdateState.INSTALLING -> "Installing"
                                    else -> "Updating"
                                }
                                VrkaStatusBadge(updateLabel, VrkaTokens.Warning, isMonospace = true)
                            }
                            comp.isChecking -> {
                                VrkaStatusBadge("Checking", VrkaTokens.AccentLight, isMonospace = true)
                            }
                            comp.updateState == ComponentUpdateState.UPDATE_SUCCESS -> {
                                VrkaStatusBadge("Updated", VrkaTokens.Success, isMonospace = true)
                            }
                            comp.updateState == ComponentUpdateState.UPDATE_FAILED -> {
                                VrkaStatusBadge("Failed", VrkaTokens.Error, isMonospace = true)
                                VrkaOutlinedButton(
                                    text = "Retry",
                                    onClick = { updateManager.applyUpdate(comp.id, settings.updatePreference) },
                                    height = 32.dp,
                                )
                            }
                            comp.checkState == ComponentCheckState.UPDATE_AVAILABLE -> {
                                VrkaStatusBadge("v${comp.latestVersion}", VrkaTokens.AccentLight, isMonospace = true)
                                VrkaOutlinedButton(
                                    text = "Update",
                                    onClick = { updateManager.applyUpdate(comp.id, settings.updatePreference) },
                                    height = 32.dp,
                                )
                            }
                            comp.checkState == ComponentCheckState.CHECK_FAILED -> {
                                VrkaStatusBadge("Error", VrkaTokens.Error, isMonospace = true)
                                VrkaOutlinedButton(
                                    text = "Retry",
                                    onClick = { updateManager.checkUpdate(comp.id, settings.updatePreference) },
                                    height = 32.dp,
                                )
                            }
                            comp.checkState == ComponentCheckState.UP_TO_DATE -> {
                                VrkaStatusBadge("Ready", VrkaTokens.Success, isMonospace = true)
                            }
                            else -> {
                                VrkaStatusBadge("Ready", VrkaTokens.Success, isMonospace = true)
                            }
                        }
                    }
                }
                if (comp.message.isNotBlank()) {
                    Text(
                        comp.message,
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = VrkaMonoFamily),
                        color = if (comp.error != null) VrkaTokens.Error else VrkaTokens.TextSecondary,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                }
            }

            VrkaDivider()

            val checkButtonText = when {
                componentMap.values.any { it.isUpdating } -> "Updating components..."
                componentMap.values.any { it.isChecking } -> "Checking updates..."
                else -> "Check All Updates"
            }

            VrkaOutlinedButton(
                text = checkButtonText,
                onClick = { updateManager.checkAllUpdates(settings.updatePreference) },
                enabled = !isAnyCheckingOrUpdating,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp),
                height = 40.dp,
            )

            Spacer(Modifier.height(10.dp))
            VrkaDivider()

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "yt-dlp",
                    style = MaterialTheme.typography.bodyMedium.copy(fontFamily = VrkaMonoFamily),
                    fontWeight = FontWeight.SemiBold,
                    color = VrkaTokens.TextPrimary,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    UpdatePreference.entries.forEach { item ->
                        VrkaChip(
                            selected = settings.updatePreference == item,
                            onClick = {
                                scope.launch {
                                    repository.setUpdatePreference(item)
                                    updateManager.onChannelChanged(item)
                                }
                            },
                            label = item.label,
                            isMonospace = true,
                        )
                    }
                }
            }
        }

        SettingsHeading("Browser Subsystems")
        VrkaSectionContainer {
            VrkaSettingRow(
                title = "Browser Engine",
                subtitle = "Mozilla GeckoView 153.0 (arm64-v8a)",
            ) {
                VrkaStatusBadge("Bundled", VrkaTokens.AccentLight, isMonospace = true)
            }

            VrkaDivider()

            VrkaSettingRow(
                title = "Ad & Tracker Blocking",
                subtitle = "uBlock Origin",
            ) {
                VrkaStatusBadge("Active", VrkaTokens.Success, isMonospace = true)
            }

            VrkaDivider()

            VrkaSettingRow(
                title = "Media Detection",
                subtitle = "Puemos HLS Discovery",
            ) {
                VrkaStatusBadge("Active", VrkaTokens.Success, isMonospace = true)
            }

            VrkaDivider()

            var showClearDialog by remember { mutableStateOf(false) }
            var isClearingSession by remember { mutableStateOf(false) }
            var clearSessionMessage by remember { mutableStateOf<String?>(null) }

            VrkaSettingRow(
                title = "Browser Session",
                subtitle = clearSessionMessage ?: "Cookies, cached storage, and active auth sessions",
            ) {
                VrkaOutlinedButton(
                    text = if (isClearingSession) "Clearing..." else "Clear Session",
                    onClick = { showClearDialog = true },
                    enabled = !isClearingSession,
                    height = 32.dp,
                )
            }

            if (showClearDialog) {
                AlertDialog(
                    onDismissRequest = { showClearDialog = false },
                    title = {
                        Text(
                            "Clear Browser Session?",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = VrkaTokens.TextPrimary,
                        )
                    },
                    text = {
                        Text(
                            "Removes cookies and site data used by the browser fallback. Download history and app settings are not affected.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = VrkaTokens.TextSecondary,
                        )
                    },
                    confirmButton = {
                        TextButton(
                            onClick = {
                                showClearDialog = false
                                isClearingSession = true
                                scope.launch {
                                    val success = GeckoRuntimeManager.getInstance(context).clearBrowserSession()
                                    isClearingSession = false
                                    clearSessionMessage = if (success) "Session cleared successfully" else "Failed to clear session"
                                }
                            }
                        ) {
                            Text("Clear", color = VrkaTokens.Accent)
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { showClearDialog = false }) {
                            Text("Cancel", color = VrkaTokens.TextSecondary)
                        }
                    },
                    containerColor = VrkaTokens.SurfaceCard,
                )
            }
        }

        SettingsHeading("Concurrency")
        VrkaSectionContainer {
            Text(
                "One active job at a time; additional jobs wait in the queue to optimize performance and prevent thermal throttling.",
                style = MaterialTheme.typography.bodySmall.copy(
                    fontFamily = VrkaMonoFamily,
                    lineHeight = 18.sp,
                ),
                color = VrkaTokens.TextSecondary,
                modifier = Modifier.padding(vertical = 8.dp),
            )
        }

        SettingsHeading("Diagnostics")
        val clipboardManager = androidx.compose.ui.platform.LocalClipboardManager.current
        var expandedDiagnosticId by remember { mutableStateOf<String?>(null) }

        VrkaSectionContainer {
            if (diagnostics.isEmpty()) {
                Text(
                    "No diagnostic entries recorded. Diagnostic logs are captured locally when a download encounters an error.",
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = VrkaMonoFamily,
                        lineHeight = 18.sp,
                    ),
                    color = VrkaTokens.TextSecondary,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
            } else {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "${diagnostics.size} recorded ${if (diagnostics.size == 1) "failure" else "failures"}",
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = VrkaMonoFamily),
                        color = VrkaTokens.TextSecondary,
                    )
                    VrkaOutlinedButton(
                        text = "Clear All",
                        onClick = onClearDiagnostics,
                        height = 30.dp,
                    )
                }

                diagnostics.forEachIndexed { index, entry ->
                    if (index > 0) VrkaDivider()
                    val isExpanded = expandedDiagnosticId == entry.id
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 10.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            val timeStr = java.text.SimpleDateFormat(
                                "yyyy-MM-dd HH:mm:ss",
                                java.util.Locale.US,
                            ).format(java.util.Date(entry.timestamp))
                            Text(
                                text = timeStr,
                                style = MaterialTheme.typography.labelSmall.copy(fontFamily = VrkaMonoFamily),
                                color = VrkaTokens.TextTertiary,
                            )
                            VrkaStatusBadge(entry.failureCategory, VrkaTokens.Error, isMonospace = true)
                        }

                        Spacer(modifier = Modifier.height(6.dp))

                        Text(
                            text = entry.title.ifBlank { entry.url },
                            style = MaterialTheme.typography.bodyMedium.copy(fontFamily = VrkaMonoFamily),
                            fontWeight = FontWeight.SemiBold,
                            color = VrkaTokens.TextPrimary,
                            maxLines = 2,
                        )

                        Spacer(modifier = Modifier.height(4.dp))

                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = "Stage: ${entry.stage}",
                                style = MaterialTheme.typography.labelSmall.copy(fontFamily = VrkaMonoFamily),
                                color = VrkaTokens.AccentLight,
                            )
                            if (entry.quality.isNotBlank()) {
                                Text(
                                    text = "• Quality: ${entry.quality}",
                                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = VrkaMonoFamily),
                                    color = VrkaTokens.TextSecondary,
                                )
                            }
                        }

                        if (entry.summary.isNotBlank()) {
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = entry.summary,
                                style = MaterialTheme.typography.bodySmall.copy(fontFamily = VrkaMonoFamily),
                                color = VrkaTokens.Warning,
                            )
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            VrkaOutlinedButton(
                                text = if (isExpanded) "Hide Details" else "View Details",
                                onClick = {
                                    expandedDiagnosticId = if (isExpanded) null else entry.id
                                },
                                height = 30.dp,
                            )

                            VrkaOutlinedButton(
                                text = "Copy Details",
                                onClick = {
                                    clipboardManager.setText(androidx.compose.ui.text.AnnotatedString(entry.toFormattedString()))
                                    Toast.makeText(
                                        context,
                                        "Diagnostic details copied to clipboard",
                                        Toast.LENGTH_SHORT,
                                    ).show()
                                },
                                height = 30.dp,
                            )
                        }

                        if (isExpanded) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = Color(0xFF141218),
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Column(
                                    modifier = Modifier.padding(10.dp),
                                ) {
                                    Text(
                                        text = "URL: ${entry.url}",
                                        style = MaterialTheme.typography.bodySmall.copy(
                                            fontFamily = VrkaMonoFamily,
                                            fontSize = 11.sp,
                                        ),
                                        color = VrkaTokens.TextSecondary,
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = "Method: ${entry.acquisitionMethod}",
                                        style = MaterialTheme.typography.bodySmall.copy(
                                            fontFamily = VrkaMonoFamily,
                                            fontSize = 11.sp,
                                        ),
                                        color = VrkaTokens.TextSecondary,
                                    )
                                    if (entry.detail.isNotBlank()) {
                                        Spacer(modifier = Modifier.height(6.dp))
                                        Text(
                                            text = "Log Tail:",
                                            style = MaterialTheme.typography.labelSmall.copy(fontFamily = VrkaMonoFamily),
                                            color = VrkaTokens.TextTertiary,
                                        )
                                        Spacer(modifier = Modifier.height(2.dp))
                                        Text(
                                            text = entry.detail,
                                            style = MaterialTheme.typography.bodySmall.copy(
                                                fontFamily = VrkaMonoFamily,
                                                fontSize = 11.sp,
                                                lineHeight = 15.sp,
                                            ),
                                            color = VrkaTokens.TextPrimary,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        SettingsHeading("About")
        val uriHandler = LocalUriHandler.current
        VrkaSectionContainer(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 12.dp, horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(
                        text = "VRKA v${BuildConfig.VERSION_NAME}",
                        style = MaterialTheme.typography.titleLarge.copy(
                            fontFamily = VrkaMonoFamily,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.sp,
                        ),
                        color = VrkaTokens.TextPrimary,
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "By MVRK",
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontFamily = VrkaMonoFamily,
                        ),
                        color = VrkaTokens.TextSecondary,
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "GitHub",
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontFamily = VrkaMonoFamily,
                            fontWeight = FontWeight.SemiBold,
                            textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline,
                        ),
                        color = VrkaTokens.Accent,
                        modifier = Modifier.clickable {
                            uriHandler.openUri("https://github.com/MaverickRox/VRKA-Android")
                        },
                    )
                }

                androidx.compose.foundation.Image(
                    painter = painterResource(R.drawable.vrka_logo_512),
                    contentDescription = "VRKA Logo",
                    contentScale = androidx.compose.ui.layout.ContentScale.Fit,
                    modifier = Modifier.size(72.dp),
                )
            }

            Spacer(modifier = Modifier.height(8.dp))
            VrkaDivider()
            Spacer(modifier = Modifier.height(4.dp))

            VrkaSettingRow(
                title = "App Updates",
                subtitle = when (val state = appUpdateState) {
                    is com.mvrk.vrka.update.AppUpdateCheckState.Checking -> "Checking for updates..."
                    is com.mvrk.vrka.update.AppUpdateCheckState.UpdateAvailable -> "v${state.release.version} available"
                    is com.mvrk.vrka.update.AppUpdateCheckState.UpToDate -> "VRKA is up to date"
                    is com.mvrk.vrka.update.AppUpdateCheckState.Error -> state.message
                    else -> "Check GitHub for new releases"
                },
                isSubtitleMono = true,
            ) {
                VrkaOutlinedButton(
                    text = if (appUpdateState is com.mvrk.vrka.update.AppUpdateCheckState.Checking) "Checking..." else "Check for Updates",
                    onClick = {
                        manualCheckRequested = true
                        appUpdateManager.checkForUpdate(isManual = true)
                    },
                    enabled = appUpdateState !is com.mvrk.vrka.update.AppUpdateCheckState.Checking,
                    height = 32.dp,
                )
            }
        }
        Spacer(Modifier.navigationBarsPadding().height(110.dp))
    }
}

@Composable
private fun SettingsHeading(text: String) {
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.labelSmall.copy(
            fontFamily = VrkaMonoFamily,
            letterSpacing = 1.2.sp,
        ),
        fontWeight = FontWeight.Bold,
        color = VrkaTokens.TextTertiary,
        modifier = Modifier.padding(top = 22.dp, bottom = 8.dp, start = 4.dp),
    )
}
