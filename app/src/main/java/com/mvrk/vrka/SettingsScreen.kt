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
    val batchState by updateManager.batchState.collectAsStateWithLifecycle()
    val geckoManager = remember { GeckoRuntimeManager.getInstance(context) }
    val geckoReady by geckoManager.isReady.collectAsStateWithLifecycle()
    val uBlockActive by geckoManager.uBlockActive.collectAsStateWithLifecycle()
    val mediaDetectorActive by geckoManager.mediaDetectorActive.collectAsStateWithLifecycle()
    val appUpdateManager = remember { com.mvrk.vrka.update.AppUpdateManager.getInstance(context, repository) }
    val appUpdateState by appUpdateManager.checkState.collectAsStateWithLifecycle()
    var manualCheckRequested by remember { mutableStateOf(false) }
    var showTemplateEditor by remember { mutableStateOf(false) }
    var editingTemplateId by remember { mutableStateOf<String?>(null) }
    var templateName by remember { mutableStateOf("") }
    var templateArgumentText by remember { mutableStateOf("") }
    var templateError by remember { mutableStateOf("") }

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

        SettingsHeading("Downloads & Concurrency")
        var showConcurrentWarningDialog by remember { mutableStateOf(false) }
        VrkaSectionContainer {
            VrkaSettingRow(
                title = "Concurrent Downloads",
                subtitle = if (settings.concurrentDownloadsEnabled) {
                    "Enabled (up to ${settings.maxConcurrentDownloads} downloads processed in parallel)"
                } else {
                    "Disabled (downloads processed sequentially one by one)"
                },
            ) {
                Switch(
                    checked = settings.concurrentDownloadsEnabled,
                    onCheckedChange = { enable ->
                        if (enable) {
                            showConcurrentWarningDialog = true
                        } else {
                            scope.launch { repository.setConcurrentDownloadsEnabled(false) }
                        }
                    },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color.White,
                        checkedTrackColor = VrkaTokens.Accent,
                    ),
                )
            }

            if (settings.concurrentDownloadsEnabled) {
                Spacer(Modifier.height(8.dp))
                VrkaDivider()
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Maximum simultaneous downloads",
                            style = MaterialTheme.typography.bodyMedium.copy(fontFamily = VrkaMonoFamily),
                            fontWeight = FontWeight.SemiBold,
                            color = VrkaTokens.TextPrimary,
                        )
                        Text(
                            text = "Active parallel workers (2 to 4)",
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = VrkaMonoFamily),
                            color = VrkaTokens.TextTertiary,
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(2, 3, 4).forEach { count ->
                            VrkaChip(
                                selected = settings.maxConcurrentDownloads == count,
                                onClick = {
                                    scope.launch { repository.setMaxConcurrentDownloads(count) }
                                },
                                label = count.toString(),
                                isMonospace = true,
                            )
                        }
                    }
                }
            }

            Text(
                text = "Running multiple downloads simultaneously uses more CPU, memory, network bandwidth, storage I/O, and battery power. Disabled by default for stability.",
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = VrkaMonoFamily),
                color = VrkaTokens.TextTertiary,
                modifier = Modifier.padding(top = 4.dp, bottom = 4.dp),
            )
        }

        if (showConcurrentWarningDialog) {
            AlertDialog(
                onDismissRequest = { showConcurrentWarningDialog = false },
                title = {
                    Text(
                        text = "Enable Concurrent Downloads?",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                },
                text = {
                    Text(
                        text = "Running multiple downloads simultaneously uses more CPU, memory, network bandwidth, storage I/O, battery power, and device thermal capacity. Higher concurrency can increase overall resource usage.\n\nAre you sure you want to enable concurrent downloads?",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                },
                confirmButton = {
                    Button(
                        onClick = {
                            showConcurrentWarningDialog = false
                            scope.launch { repository.setConcurrentDownloadsEnabled(true) }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = VrkaTokens.Accent),
                    ) {
                        Text("Enable")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showConcurrentWarningDialog = false }) {
                        Text("Cancel")
                    }
                },
            )
        }

        SettingsHeading("Compatibility & Custom Commands")
        VrkaSectionContainer {
            VrkaSettingRow(
                title = "H.264 Compatibility Mode",
                subtitle = if (settings.h264CompatibilityMode) {
                    "Convert non-H.264 video after download; existing H.264 is copied"
                } else {
                    "Off by default; preserve the source codec"
                },
            ) {
                Switch(
                    checked = settings.h264CompatibilityMode,
                    onCheckedChange = { scope.launch { repository.setH264CompatibilityMode(it) } },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color.White,
                        checkedTrackColor = VrkaTokens.Accent,
                    ),
                )
            }
            Text(
                "H.264 conversion can take significantly more time and use extra battery, CPU, and temporary storage. VRKA checks the downloaded codec and skips conversion when it is already H.264.",
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = VrkaMonoFamily),
                color = VrkaTokens.TextTertiary,
                modifier = Modifier.padding(top = 4.dp, bottom = 8.dp),
            )

            VrkaDivider()
            Spacer(Modifier.height(8.dp))
            VrkaSettingRow(
                title = "Custom yt-dlp Commands",
                subtitle = if (settings.customCommandMode) {
                    "Enabled • new downloads use the selected template"
                } else {
                    "Disabled • normal VRKA download options are used"
                },
            ) {
                Switch(
                    checked = settings.customCommandMode,
                    onCheckedChange = { scope.launch { repository.setCustomCommandMode(it) } },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color.White,
                        checkedTrackColor = VrkaTokens.Accent,
                    ),
                )
            }
            Text(
                "Templates are argument lists, not shell commands. VRKA controls the staging path. A failed eligible custom attempt can still use Browser Fallback, which uses its standard yt-dlp handoff and ignores the template. Unsafe output paths, shell hooks, and credential-bearing headers are blocked.",
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = VrkaMonoFamily),
                color = VrkaTokens.TextTertiary,
                modifier = Modifier.padding(top = 4.dp, bottom = 8.dp),
            )

            settings.customCommandTemplates.forEach { template ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { scope.launch { repository.selectCustomCommandTemplate(template.id) } }
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(
                        selected = template.id == settings.selectedCustomCommandTemplateId,
                        onClick = { scope.launch { repository.selectCustomCommandTemplate(template.id) } },
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(template.name, style = MaterialTheme.typography.bodyMedium, color = VrkaTokens.TextPrimary)
                        Text(
                            template.arguments.joinToString(" "),
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = VrkaMonoFamily),
                            color = VrkaTokens.TextTertiary,
                        )
                    }
                }
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                VrkaOutlinedButton(
                    text = "Add Template",
                    onClick = {
                        editingTemplateId = null
                        templateName = ""
                        templateArgumentText = ""
                        templateError = ""
                        showTemplateEditor = true
                    },
                    height = 32.dp,
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        horizontal = 8.dp,
                        vertical = 0.dp,
                    ),
                )
                val selectedTemplate = settings.customCommandTemplates.firstOrNull {
                    it.id == settings.selectedCustomCommandTemplateId
                }
                VrkaOutlinedButton(
                    text = "Edit",
                    onClick = {
                        selectedTemplate?.let { template ->
                            editingTemplateId = template.id
                            templateName = template.name
                            templateArgumentText = template.arguments.joinToString(" ") { argument ->
                                if (argument.any { it.isWhitespace() }) "\"${argument.replace("\\", "\\\\").replace("\"", "\\\"")}\"" else argument
                            }
                            templateError = ""
                            showTemplateEditor = true
                        }
                    },
                    enabled = selectedTemplate != null,
                    height = 32.dp,
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        horizontal = 8.dp,
                        vertical = 0.dp,
                    ),
                )
                VrkaTextButton(
                    text = "Delete",
                    onClick = {
                        selectedTemplate?.let { template ->
                            scope.launch { repository.deleteCustomCommandTemplate(template.id) }
                        }
                    },
                    enabled = selectedTemplate != null,
                    color = VrkaTokens.Destructive,
                )
            }
        }

        if (showTemplateEditor) {
            AlertDialog(
                onDismissRequest = { showTemplateEditor = false },
                title = { Text(if (editingTemplateId == null) "New yt-dlp Template" else "Edit yt-dlp Template") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedTextField(
                            value = templateName,
                            onValueChange = { templateName = it.take(80) },
                            label = { Text("Template name") },
                            singleLine = true,
                        )
                        OutlinedTextField(
                            value = templateArgumentText,
                            onValueChange = { templateArgumentText = it.take(CustomCommandPolicy.MAX_TEMPLATE_CHARS) },
                            label = { Text("yt-dlp arguments") },
                            placeholder = { Text("--no-playlist --embed-metadata") },
                            minLines = 3,
                            maxLines = 7,
                        )
                        if (templateError.isNotBlank()) {
                            Text(templateError, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        val parsed = runCatching { CustomCommandPolicy.parseArgumentLine(templateArgumentText) }
                        if (parsed.isFailure) {
                            templateError = parsed.exceptionOrNull()?.message.orEmpty()
                        } else {
                            val template = YtDlpCommandTemplate(
                                id = editingTemplateId ?: java.util.UUID.randomUUID().toString(),
                                name = templateName.trim(),
                                arguments = parsed.getOrThrow(),
                            )
                            templateError = CustomCommandPolicy.validateTemplate(template.name, template.arguments).orEmpty()
                            if (templateError.isBlank()) {
                                scope.launch {
                                    repository.saveCustomCommandTemplate(template)
                                    showTemplateEditor = false
                                }
                            }
                        }
                    }) { Text("Save") }
                },
                dismissButton = {
                    TextButton(onClick = { showTemplateEditor = false }) { Text("Cancel") }
                },
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
        VrkaSectionContainer {
            componentMap.values.forEachIndexed { index, comp ->
                if (index > 0) VrkaDivider()
                val displayVer = ComponentStatusPresentation.versionLabel(comp.installedVersion)
                val componentStatus = ComponentStatusPresentation.from(comp)
                VrkaSettingRow(
                    title = comp.name,
                    subtitle = "Installed $displayVer",
                    isSubtitleMono = true,
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        val statusColor = when (componentStatus.tone) {
                            ComponentStatusTone.SUCCESS -> VrkaTokens.Success
                            ComponentStatusTone.WARNING -> VrkaTokens.Warning
                            ComponentStatusTone.ERROR -> VrkaTokens.Error
                            ComponentStatusTone.ACCENT -> VrkaTokens.AccentLight
                            ComponentStatusTone.NEUTRAL -> VrkaTokens.TextTertiary
                        }
                        VrkaStatusBadge(componentStatus.label, statusColor, isMonospace = true)
                        when {
                            comp.updateState == ComponentUpdateState.UPDATE_FAILED ||
                                comp.checkState == ComponentCheckState.CHECK_FAILED -> {
                                VrkaOutlinedButton(
                                    text = "Retry",
                                    onClick = {
                                        if (comp.updateState == ComponentUpdateState.UPDATE_FAILED) {
                                            updateManager.applyUpdate(comp.id, settings.updatePreference)
                                        } else {
                                            updateManager.checkUpdate(comp.id, settings.updatePreference)
                                        }
                                    },
                                    height = 32.dp,
                                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                                        horizontal = 8.dp,
                                        vertical = 0.dp,
                                    ),
                                )
                            }
                            comp.checkState == ComponentCheckState.UPDATE_AVAILABLE -> {
                                VrkaOutlinedButton(
                                    text = "Update",
                                    onClick = { updateManager.applyUpdate(comp.id, settings.updatePreference) },
                                    height = 32.dp,
                                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                                        horizontal = 8.dp,
                                        vertical = 0.dp,
                                    ),
                                )
                            }
                        }
                    }
                }
                if (!comp.error.isNullOrBlank()) {
                    Text(
                        comp.error,
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = VrkaMonoFamily),
                        color = VrkaTokens.Error,
                        modifier = Modifier.padding(start = 4.dp, bottom = 8.dp),
                    )
                }
            }

            VrkaDivider()

            val isBusy = batchState in setOf(BatchOperationState.CHECKING, BatchOperationState.UPDATING) ||
                componentMap.values.any { it.isChecking || it.isUpdating }

            val checkButtonText = when {
                batchState == BatchOperationState.UPDATING || componentMap.values.any { it.isUpdating } -> "Updating components..."
                batchState == BatchOperationState.CHECKING || componentMap.values.any { it.isChecking } -> "Checking updates..."
                else -> "Check All Updates"
            }

            VrkaOutlinedButton(
                text = checkButtonText,
                onClick = { updateManager.checkAllUpdates(settings.updatePreference) },
                enabled = !isBusy,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp),
                height = 40.dp,
            )

            VrkaDivider()

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    "yt-dlp channel",
                    style = MaterialTheme.typography.labelMedium.copy(fontFamily = VrkaMonoFamily),
                    fontWeight = FontWeight.SemiBold,
                    color = VrkaTokens.TextSecondary,
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
                subtitle = "GeckoView ${BuildConfig.GECKOVIEW_VERSION} • arm64-v8a",
            ) {
                VrkaStatusBadge(
                    if (geckoReady) "Initialized" else "Bundled",
                    if (geckoReady) VrkaTokens.Success else VrkaTokens.AccentLight,
                    isMonospace = true,
                )
            }

            VrkaDivider()

            VrkaSettingRow(
                title = "Ad & Tracker Blocking",
                subtitle = "uBlock Origin • Browser Fallback privacy filter",
            ) {
                VrkaStatusBadge(
                    if (uBlockActive) "Active" else "Not started",
                    if (uBlockActive) VrkaTokens.Success else VrkaTokens.TextTertiary,
                    isMonospace = true,
                )
            }

            VrkaDivider()

            VrkaSettingRow(
                title = "Media Detection",
                subtitle = "WebExtension • direct media, HLS and DASH discovery",
            ) {
                VrkaStatusBadge(
                    if (mediaDetectorActive) "Active" else "Not started",
                    if (mediaDetectorActive) VrkaTokens.Success else VrkaTokens.TextTertiary,
                    isMonospace = true,
                )
            }

            VrkaDivider()

            var showClearDialog by remember { mutableStateOf(false) }
            var isClearingSession by remember { mutableStateOf(false) }
            var clearSessionMessage by remember { mutableStateOf<String?>(null) }

            VrkaSettingRow(
                title = "Browser Session",
                subtitle = clearSessionMessage ?: "Task-scoped private fallback session. Clear GeckoView cookies, site storage, auth sessions, caches and discovered candidates.",
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
                            "Clear Browser Data?",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = VrkaTokens.TextPrimary,
                        )
                    },
                    text = {
                        Text(
                            "Clears GeckoView cookies, DOM storage, auth sessions, site data, all caches, and in-memory discovered candidates. It does not cancel an active fallback. Download history, queue and app settings are not affected.",
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
