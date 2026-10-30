package com.mvrk.vrka

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.ui.res.stringResource
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.horizontalScroll
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
    runtimeManager: com.mvrk.vrka.runtime.YtDlpRuntimeManager,
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
                        context.resources.getString(R.string.msg_latest_version, BuildConfig.VERSION_NAME),
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
            stringResource(R.string.settings_title),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = VrkaTokens.TextPrimary,
        )

        SettingsHeading(stringResource(R.string.settings_heading_download_location))
        VrkaSectionContainer {
            VrkaSettingRow(
                title = stringResource(R.string.pref_destination_title),
                subtitle = OutputPublisher.formatDisplayPath(settings.outputTreeUri),
                isSubtitleMono = true,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    VrkaOutlinedButton(
                        text = stringResource(R.string.btn_change),
                        onClick = { folderPicker.launch(null) },
                        height = 34.dp,
                    )
                    if (settings.outputTreeUri.isNotBlank()) {
                        VrkaTextButton(
                            text = stringResource(R.string.btn_reset),
                            onClick = { scope.launch { repository.setOutputTree("") } },
                        )
                    }
                }
            }

            Spacer(Modifier.height(10.dp))
            VrkaDivider()
            Spacer(Modifier.height(10.dp))

            val useSelectedLabel = stringResource(R.string.save_location_use_selected)
            val askEveryTimeLabel = stringResource(R.string.save_location_ask_every_time)
            VrkaSegmentedControl(
                items = SaveLocationMode.entries,
                selectedItem = settings.saveLocationMode,
                onItemSelected = { mode -> scope.launch { repository.setSaveLocationMode(mode) } },
                label = { mode ->
                    when (mode) {
                        SaveLocationMode.REMEMBER_LOCATION -> useSelectedLabel
                        SaveLocationMode.ASK_EVERY_TIME -> askEveryTimeLabel
                    }
                },
                isMonospace = true,
            )

            Text(
                text = if (settings.saveLocationMode == SaveLocationMode.REMEMBER_LOCATION) {
                    stringResource(R.string.desc_location_selected)
                } else {
                    stringResource(R.string.desc_location_prompt)
                },
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = VrkaMonoFamily),
                color = VrkaTokens.TextTertiary,
                modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
            )
        }

        SettingsHeading(stringResource(R.string.settings_heading_download_behavior))
        VrkaSectionContainer {
            VrkaSettingRow(
                title = stringResource(R.string.pref_size_confirmation),
                subtitle = if (settings.showDownloadSizeConfirmation) {
                    stringResource(R.string.desc_size_confirmation_enabled)
                } else {
                    stringResource(R.string.desc_size_confirmation_disabled)
                },
            ) {
                VrkaSwitch(
                    checked = settings.showDownloadSizeConfirmation,
                    onCheckedChange = { enabled ->
                        scope.launch { repository.setShowDownloadSizeConfirmation(enabled) }
                    },
                )
            }
            Text(
                stringResource(R.string.desc_size_confirmation_helper),
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = VrkaMonoFamily),
                color = VrkaTokens.TextTertiary,
                modifier = Modifier.padding(top = 4.dp, bottom = 8.dp),
            )
        }

        SettingsHeading(stringResource(R.string.settings_heading_concurrency))
        var showConcurrentWarningDialog by remember { mutableStateOf(false) }
        VrkaSectionContainer {
            VrkaSettingRow(
                title = stringResource(R.string.pref_concurrent_downloads),
                subtitle = if (settings.concurrentDownloadsEnabled) {
                    stringResource(R.string.desc_concurrent_enabled, settings.maxConcurrentDownloads)
                } else {
                    stringResource(R.string.desc_concurrent_disabled)
                },
            ) {
                VrkaSwitch(
                    checked = settings.concurrentDownloadsEnabled,
                    onCheckedChange = { enable ->
                        if (enable) {
                            showConcurrentWarningDialog = true
                        } else {
                            scope.launch { repository.setConcurrentDownloadsEnabled(false) }
                        }
                    },
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
                            text = stringResource(R.string.label_max_simultaneous),
                            style = MaterialTheme.typography.bodyMedium.copy(fontFamily = VrkaMonoFamily),
                            fontWeight = FontWeight.SemiBold,
                            color = VrkaTokens.TextPrimary,
                        )
                        Text(
                            text = stringResource(R.string.label_parallel_workers),
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
                text = stringResource(R.string.desc_concurrency_warning),
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
                        text = stringResource(R.string.dialog_concurrency_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                },
                text = {
                    Text(
                        text = stringResource(R.string.dialog_concurrency_desc),
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
                        Text(stringResource(R.string.btn_enable))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showConcurrentWarningDialog = false }) {
                        Text(stringResource(R.string.fallback_cancel))
                    }
                },
            )
        }

        SettingsHeading(stringResource(R.string.settings_heading_compatibility))
        VrkaSectionContainer {
            VrkaSettingRow(
                title = stringResource(R.string.pref_h264_title),
                subtitle = if (settings.h264CompatibilityMode) {
                    stringResource(R.string.desc_h264_enabled)
                } else {
                    stringResource(R.string.desc_h264_disabled)
                },
            ) {
                VrkaSwitch(
                    checked = settings.h264CompatibilityMode,
                    onCheckedChange = { scope.launch { repository.setH264CompatibilityMode(it) } },
                )
            }
            Text(
                stringResource(R.string.desc_h264_helper),
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = VrkaMonoFamily),
                color = VrkaTokens.TextTertiary,
                modifier = Modifier.padding(top = 4.dp, bottom = 8.dp),
            )

            VrkaDivider()
            Spacer(Modifier.height(8.dp))
            VrkaSettingRow(
                title = stringResource(R.string.pref_custom_commands),
                subtitle = if (settings.customCommandMode) {
                    stringResource(R.string.desc_custom_commands_enabled)
                } else {
                    stringResource(R.string.desc_custom_commands_disabled)
                },
            ) {
                VrkaSwitch(
                    checked = settings.customCommandMode,
                    onCheckedChange = { scope.launch { repository.setCustomCommandMode(it) } },
                )
            }
            Text(
                stringResource(R.string.desc_custom_commands_helper),
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
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                VrkaOutlinedButton(
                    text = stringResource(R.string.btn_add_template),
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
                    text = stringResource(R.string.btn_edit),
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
                    text = stringResource(R.string.action_delete),
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
                title = { Text(if (editingTemplateId == null) stringResource(R.string.dialog_new_template_title) else stringResource(R.string.dialog_edit_template_title)) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedTextField(
                            value = templateName,
                            onValueChange = { templateName = it.take(80) },
                            label = { Text(stringResource(R.string.label_template_name)) },
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
                    }) { Text(stringResource(R.string.btn_save)) }
                },
                dismissButton = {
                    TextButton(onClick = { showTemplateEditor = false }) { Text(stringResource(R.string.fallback_cancel)) }
                },
            )
        }

        SettingsHeading(stringResource(R.string.settings_heading_appearance))
        VrkaSectionContainer {
            Text(
                stringResource(R.string.pref_theme_mode),
                style = MaterialTheme.typography.bodyMedium.copy(fontFamily = VrkaMonoFamily),
                fontWeight = FontWeight.SemiBold,
                color = VrkaTokens.TextPrimary,
                modifier = Modifier.padding(top = 8.dp, bottom = 10.dp),
            )
            val lightLabel = stringResource(R.string.theme_light)
            val darkLabel = stringResource(R.string.theme_dark)
            VrkaSegmentedControl(
                items = ThemeMode.entries,
                selectedItem = settings.themeMode,
                onItemSelected = { mode -> scope.launch { repository.setThemeMode(mode) } },
                label = { mode -> if (mode == ThemeMode.LIGHT) lightLabel else darkLabel },
                isMonospace = true,
            )

            Spacer(Modifier.height(10.dp))
            VrkaDivider()

            VrkaSettingRow(
                title = stringResource(R.string.pref_amoled),
                subtitle = stringResource(R.string.desc_amoled),
            ) {
                VrkaSwitch(
                    checked = settings.amoled,
                    enabled = settings.themeMode == ThemeMode.DARK,
                    onCheckedChange = { scope.launch { repository.setAmoled(it) } },
                )
            }

            Spacer(Modifier.height(10.dp))
            VrkaDivider()

            Text(
                stringResource(R.string.pref_font),
                style = MaterialTheme.typography.bodyMedium.copy(fontFamily = VrkaMonoFamily),
                fontWeight = FontWeight.SemiBold,
                color = VrkaTokens.TextPrimary,
                modifier = Modifier.padding(top = 8.dp, bottom = 10.dp),
            )
            val vrkaFontLabel = stringResource(R.string.font_vrka)
            val systemFontLabel = stringResource(R.string.font_system)
            VrkaSegmentedControl(
                items = FontPreference.entries,
                selectedItem = settings.fontPreference,
                onItemSelected = { font -> scope.launch { repository.setFontPreference(font) } },
                label = { font -> if (font == FontPreference.VRKA_FONT) vrkaFontLabel else systemFontLabel },
                isMonospace = true,
            )

            Spacer(Modifier.height(10.dp))
            VrkaDivider()

            var showLanguageDialog by remember { mutableStateOf(false) }
            val currentLanguage = remember(context) { LocaleHelper.getActiveLanguage(context) }
            val currentLanguageDisplay = if (currentLanguage.code.isEmpty()) {
                stringResource(R.string.pref_system_default)
            } else {
                currentLanguage.displayLabel
            }

            VrkaSettingRow(
                title = stringResource(R.string.pref_app_language),
                subtitle = currentLanguageDisplay,
            ) {
                VrkaOutlinedButton(
                    text = stringResource(R.string.btn_change),
                    onClick = { showLanguageDialog = true },
                    height = 36.dp,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                )
            }

            if (showLanguageDialog) {
                val currentLang = remember(context) { LocaleHelper.getActiveLanguage(context) }
                AlertDialog(
                    onDismissRequest = { showLanguageDialog = false },
                    shape = RoundedCornerShape(20.dp),
                    containerColor = VrkaTokens.SurfaceCard,
                    title = {
                        Text(
                            text = stringResource(R.string.pref_app_language),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = VrkaTokens.TextPrimary,
                        )
                    },
                    text = {
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 400.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            // System default placed in a separate row at the top
                            item {
                                val isSystemSelected = currentLang.code.isEmpty()
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(10.dp))
                                        .selectable(
                                            selected = isSystemSelected,
                                            onClick = {
                                                showLanguageDialog = false
                                                LocaleHelper.setAppLanguage(context, "")
                                                context.findActivity()?.recreate()
                                            },
                                            role = Role.RadioButton,
                                        )
                                        .padding(horizontal = 10.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        text = stringResource(R.string.pref_system_default),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = if (isSystemSelected) VrkaTokens.AccentLight else VrkaTokens.TextPrimary,
                                        fontWeight = if (isSystemSelected) FontWeight.Bold else FontWeight.Normal,
                                        modifier = Modifier
                                            .weight(1f)
                                            .widthIn(min = 0.dp)
                                            .padding(end = 12.dp),
                                    )
                                    Box(
                                        modifier = Modifier.size(24.dp),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        RadioButton(
                                            selected = isSystemSelected,
                                            onClick = null,
                                            colors = RadioButtonDefaults.colors(
                                                selectedColor = VrkaTokens.Accent,
                                                unselectedColor = VrkaTokens.TextTertiary,
                                            ),
                                        )
                                    }
                                }
                            }

                            item {
                                VrkaDivider()
                            }

                            // 14 application languages strictly ordered by English name:
                            // 1. Arabic, 2. Bengali, 3. Chinese (Simplified), 4. English, 5. French, 6. German,
                            // 7. Gujarati, 8. Hindi, 9. Kannada, 10. Portuguese (Brazil), 11. Portuguese (Portugal),
                            // 12. Spanish, 13. Tamil, 14. Telugu
                            items(LocaleHelper.sortedLanguages) { lang ->
                                val isSelected = currentLang.code.equals(lang.code, ignoreCase = true)
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(10.dp))
                                        .selectable(
                                            selected = isSelected,
                                            onClick = {
                                                showLanguageDialog = false
                                                LocaleHelper.setAppLanguage(context, lang.code)
                                                context.findActivity()?.recreate()
                                            },
                                            role = Role.RadioButton,
                                        )
                                        .padding(horizontal = 10.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        text = lang.displayLabel,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = if (isSelected) VrkaTokens.AccentLight else VrkaTokens.TextPrimary,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                        modifier = Modifier
                                            .weight(1f)
                                            .widthIn(min = 0.dp)
                                            .padding(end = 12.dp),
                                    )
                                    Box(
                                        modifier = Modifier.size(24.dp),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        RadioButton(
                                            selected = isSelected,
                                            onClick = null,
                                            colors = RadioButtonDefaults.colors(
                                                selectedColor = VrkaTokens.Accent,
                                                unselectedColor = VrkaTokens.TextTertiary,
                                            ),
                                        )
                                    }
                                }
                            }
                        }
                    },
                    confirmButton = {},
                    dismissButton = {
                        TextButton(
                            onClick = { showLanguageDialog = false },
                            shape = RoundedCornerShape(10.dp),
                        ) {
                            Text(
                                text = stringResource(R.string.fallback_cancel),
                                color = VrkaTokens.TextSecondary,
                            )
                        }
                    },
                )
            }
        }

        SettingsHeading(stringResource(R.string.settings_heading_components))
        VrkaSectionContainer {
            componentMap.values.forEachIndexed { index, comp ->
                if (index > 0) VrkaDivider()
                val componentStatus = ComponentStatusPresentation.from(context, comp)
                val statusColor = when (componentStatus.tone) {
                    ComponentStatusTone.SUCCESS -> VrkaTokens.Success
                    ComponentStatusTone.WARNING -> VrkaTokens.Warning
                    ComponentStatusTone.ERROR -> VrkaTokens.Error
                    ComponentStatusTone.ACCENT -> VrkaTokens.AccentLight
                    ComponentStatusTone.NEUTRAL -> VrkaTokens.TextTertiary
                }
                val displayInstalledText = when {
                    comp.installedVersion.isBlank() && comp.isChecking -> stringResource(R.string.status_resolving)
                    comp.installedVersion.isBlank() || comp.installedVersion.equals("Unknown", ignoreCase = true) ->
                        stringResource(R.string.status_unavailable)
                    else -> stringResource(R.string.status_installed, ComponentStatusPresentation.versionLabel(comp.installedVersion))
                }

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 12.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.Top,
                    ) {
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .padding(end = 12.dp),
                        ) {
                            Text(
                                text = comp.name,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = VrkaTokens.TextPrimary,
                            )
                            Text(
                                text = displayInstalledText,
                                style = MaterialTheme.typography.bodySmall.copy(fontFamily = VrkaMonoFamily),
                                color = VrkaTokens.TextSecondary,
                                modifier = Modifier.padding(top = 2.dp),
                            )
                            componentStatus.secondaryText?.let { resultText ->
                                Text(
                                    text = resultText,
                                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = VrkaMonoFamily),
                                    color = if (comp.error != null) VrkaTokens.Error else VrkaTokens.TextSecondary,
                                    modifier = Modifier.padding(top = 2.dp),
                                )
                            }
                        }
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.padding(top = 2.dp),
                        ) {
                            VrkaStatusBadge(componentStatus.label, statusColor, isMonospace = true)
                            when {
                                comp.updateState == ComponentUpdateState.UPDATE_FAILED ||
                                    comp.checkState == ComponentCheckState.CHECK_FAILED -> {
                                    VrkaOutlinedButton(
                                        text = stringResource(R.string.action_retry),
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
                                        text = stringResource(R.string.btn_update_now),
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
                }

            }

            VrkaDivider()

            val isBusy = batchState.isInProgress ||
                componentMap.values.any { it.isChecking || it.isUpdating }

            val checkButtonText = when {
                batchState == BatchOperationState.UPDATING || componentMap.values.any { it.isUpdating } -> stringResource(R.string.btn_updating_components)
                batchState == BatchOperationState.CHECKING || componentMap.values.any { it.isChecking } -> stringResource(R.string.btn_checking_updates)
                else -> stringResource(R.string.btn_check_all_updates)
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

            Spacer(Modifier.height(12.dp))
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
                val channelStableLabel = stringResource(R.string.channel_stable)
                val channelNightlyLabel = stringResource(R.string.channel_nightly)
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
                            label = when (item) {
                                UpdatePreference.STABLE -> channelStableLabel
                                UpdatePreference.NIGHTLY -> channelNightlyLabel
                            },
                            isMonospace = true,
                        )
                    }
                }
            }


        }

        SettingsHeading(stringResource(R.string.settings_heading_runtime))
        val runtimeState by runtimeManager.state.collectAsStateWithLifecycle()
        var showAddRuntimeDialog by remember { mutableStateOf(false) }
        var runtimeRepoInput by remember { mutableStateOf("") }
        var runtimeDialogError by remember { mutableStateOf("") }
        VrkaSectionContainer {
            VrkaSettingRow(
                title = stringResource(R.string.runtime_active_title),
                subtitle = runtimeState.message.ifBlank {
                    if (runtimeManager.isCustomActive()) {
                        runtimeManager.activeDescription()
                    } else {
                        stringResource(R.string.runtime_stock_desc)
                    }
                },
            ) {
                VrkaStatusBadge(
                    if (runtimeManager.isCustomActive()) {
                        stringResource(R.string.runtime_badge_custom)
                    } else {
                        stringResource(R.string.runtime_badge_stock)
                    },
                    if (runtimeManager.isCustomActive()) VrkaTokens.AccentLight else VrkaTokens.Success,
                    isMonospace = true,
                )
            }

            VrkaDivider()

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(end = 12.dp),
                ) {
                    Text(
                        text = stringResource(R.string.runtime_stock_name),
                        style = MaterialTheme.typography.bodyMedium.copy(fontFamily = VrkaMonoFamily),
                        fontWeight = FontWeight.SemiBold,
                        color = VrkaTokens.TextPrimary,
                    )
                    Text(
                        text = stringResource(
                            R.string.runtime_stock_version,
                            runtimeManager.stockVersion().ifBlank { stringResource(R.string.status_unavailable) },
                        ),
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = VrkaMonoFamily),
                        color = VrkaTokens.TextSecondary,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
                if (runtimeManager.isCustomActive()) {
                    VrkaOutlinedButton(
                        text = stringResource(R.string.runtime_use_stock),
                        onClick = {
                            scope.launch {
                                runtimeManager.activateStock()
                                    .onFailure { Toast.makeText(context, it.message, Toast.LENGTH_LONG).show() }
                            }
                        },
                        enabled = !runtimeState.working,
                        height = 32.dp,
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                    )
                } else {
                    VrkaStatusBadge(
                        stringResource(R.string.status_active),
                        VrkaTokens.Success,
                        isMonospace = true,
                    )
                }
            }

            runtimeState.snapshot.runtimes.forEach { runtime ->
                VrkaDivider()
                val isActive = runtimeManager.isCustomActive() &&
                    runtimeState.snapshot.activeId == runtime.id
                val updateTag = runtimeState.updateTags[runtime.id]
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .padding(end = 12.dp),
                    ) {
                        Text(
                            text = runtime.repo,
                            style = MaterialTheme.typography.bodyMedium.copy(fontFamily = VrkaMonoFamily),
                            fontWeight = FontWeight.SemiBold,
                            color = VrkaTokens.TextPrimary,
                        )
                        Text(
                            text = stringResource(R.string.runtime_version, runtime.version.ifBlank { runtime.tag }),
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = VrkaMonoFamily),
                            color = VrkaTokens.TextSecondary,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier.padding(top = 6.dp),
                        ) {
                            VrkaStatusBadge(
                                stringResource(R.string.runtime_badge_verified),
                                VrkaTokens.Success,
                                isMonospace = true,
                            )
                            if (isActive) {
                                VrkaStatusBadge(
                                    stringResource(R.string.status_active),
                                    VrkaTokens.AccentLight,
                                    isMonospace = true,
                                )
                            }
                            if (updateTag != null) {
                                VrkaStatusBadge(
                                    stringResource(R.string.runtime_badge_update),
                                    VrkaTokens.Warning,
                                    isMonospace = true,
                                )
                            }
                        }
                    }
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (!isActive) {
                            VrkaOutlinedButton(
                                text = stringResource(R.string.runtime_use),
                                onClick = {
                                    scope.launch {
                                        runtimeManager.activateCustom(runtime.id)
                                            .onFailure { Toast.makeText(context, it.message, Toast.LENGTH_LONG).show() }
                                    }
                                },
                                enabled = !runtimeState.working,
                                height = 32.dp,
                                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                            )
                        }
                        VrkaOutlinedButton(
                            text = stringResource(R.string.runtime_update),
                            onClick = {
                                scope.launch {
                                    runtimeManager.update(runtime.id)
                                        .onFailure { Toast.makeText(context, it.message, Toast.LENGTH_LONG).show() }
                                }
                            },
                            enabled = !runtimeState.working,
                            height = 32.dp,
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                        )
                        VrkaOutlinedButton(
                            text = stringResource(R.string.runtime_remove),
                            onClick = {
                                scope.launch {
                                    runtimeManager.remove(runtime.id)
                                        .onFailure { Toast.makeText(context, it.message, Toast.LENGTH_LONG).show() }
                                }
                            },
                            enabled = !runtimeState.working && !isActive,
                            height = 32.dp,
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                        )
                    }
                }
            }

            VrkaDivider()

            VrkaOutlinedButton(
                text = stringResource(R.string.runtime_add),
                onClick = {
                    runtimeRepoInput = ""
                    runtimeDialogError = ""
                    showAddRuntimeDialog = true
                },
                enabled = !runtimeState.working,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp),
                height = 40.dp,
            )

            if (runtimeState.working) {
                Text(
                    text = runtimeState.message.ifBlank { stringResource(R.string.runtime_busy) },
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = VrkaMonoFamily),
                    color = VrkaTokens.AccentLight,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }

            Text(
                text = stringResource(R.string.runtime_custom_warning),
                style = MaterialTheme.typography.bodySmall,
                color = VrkaTokens.TextTertiary,
                modifier = Modifier.padding(top = 10.dp),
            )
        }

        if (showAddRuntimeDialog) {
            // Resolved here so the dialog callbacks read composition-scoped values rather
            // than querying resources from LocalContext outside composition.
            val repoRequiredMessage = stringResource(R.string.runtime_repo_required)
            val installFailedMessage = stringResource(R.string.runtime_install_failed)
            AlertDialog(
                onDismissRequest = { if (!runtimeState.working) showAddRuntimeDialog = false },
                title = {
                    Text(
                        stringResource(R.string.runtime_add_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = VrkaTokens.TextPrimary,
                    )
                },
                text = {
                    Column {
                        Text(
                            stringResource(R.string.runtime_add_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = VrkaTokens.TextSecondary,
                        )
                        Spacer(Modifier.height(12.dp))
                        OutlinedTextField(
                            value = runtimeRepoInput,
                            onValueChange = {
                                runtimeRepoInput = it
                                runtimeDialogError = ""
                            },
                            singleLine = true,
                            enabled = !runtimeState.working,
                            label = { Text(stringResource(R.string.runtime_repo_hint)) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        if (runtimeDialogError.isNotBlank()) {
                            Text(
                                text = runtimeDialogError,
                                style = MaterialTheme.typography.bodySmall,
                                color = VrkaTokens.Error,
                                modifier = Modifier.padding(top = 8.dp),
                            )
                        }
                        if (runtimeState.working) {
                            Text(
                                text = runtimeState.message.ifBlank { stringResource(R.string.runtime_busy) },
                                style = MaterialTheme.typography.bodySmall.copy(fontFamily = VrkaMonoFamily),
                                color = VrkaTokens.AccentLight,
                                modifier = Modifier.padding(top = 8.dp),
                            )
                        }
                    }
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            val repo = runtimeRepoInput.trim()
                            if (repo.isBlank()) {
                                runtimeDialogError = repoRequiredMessage
                                return@TextButton
                            }
                            scope.launch {
                                runtimeManager.install(repo)
                                    .onSuccess { message ->
                                        showAddRuntimeDialog = false
                                        runtimeRepoInput = ""
                                        Toast.makeText(context, message, Toast.LENGTH_LONG).show()
                                    }
                                    .onFailure { error ->
                                        runtimeDialogError = error.message
                                            ?: installFailedMessage
                                    }
                            }
                        },
                        enabled = !runtimeState.working,
                    ) {
                        Text(stringResource(R.string.runtime_install), color = VrkaTokens.Accent)
                    }
                },
                dismissButton = {
                    TextButton(
                        onClick = { showAddRuntimeDialog = false },
                        enabled = !runtimeState.working,
                    ) {
                        Text(stringResource(R.string.fallback_cancel), color = VrkaTokens.TextSecondary)
                    }
                },
                containerColor = VrkaTokens.SurfaceCard,
            )
        }

        SettingsHeading(stringResource(R.string.settings_heading_browser))
        VrkaSectionContainer {
            VrkaSettingRow(
                title = stringResource(R.string.pref_browser_engine),
                subtitle = "GeckoView ${BuildConfig.GECKOVIEW_VERSION} • arm64-v8a",
            ) {
                VrkaStatusBadge(
                    if (geckoReady) stringResource(R.string.status_initialized) else stringResource(R.string.status_bundled),
                    if (geckoReady) VrkaTokens.Success else VrkaTokens.AccentLight,
                    isMonospace = true,
                )
            }

            VrkaDivider()

            VrkaSettingRow(
                title = stringResource(R.string.pref_ad_blocking),
                subtitle = stringResource(R.string.desc_ad_blocking),
            ) {
                VrkaStatusBadge(
                    if (uBlockActive) stringResource(R.string.status_active) else stringResource(R.string.status_not_started),
                    if (uBlockActive) VrkaTokens.Success else VrkaTokens.TextTertiary,
                    isMonospace = true,
                )
            }

            VrkaDivider()

            VrkaSettingRow(
                title = stringResource(R.string.pref_media_detection),
                subtitle = stringResource(R.string.desc_media_detection),
            ) {
                VrkaStatusBadge(
                    if (mediaDetectorActive) stringResource(R.string.status_active) else stringResource(R.string.status_not_started),
                    if (mediaDetectorActive) VrkaTokens.Success else VrkaTokens.TextTertiary,
                    isMonospace = true,
                )
            }

            VrkaDivider()

            var showClearDialog by remember { mutableStateOf(false) }
            var isClearingSession by remember { mutableStateOf(false) }
            var clearSessionMessage by remember { mutableStateOf<String?>(null) }

            VrkaSettingRow(
                title = stringResource(R.string.pref_browser_session),
                subtitle = clearSessionMessage ?: stringResource(R.string.desc_browser_session_default),
            ) {
                VrkaOutlinedButton(
                    text = if (isClearingSession) stringResource(R.string.btn_clearing) else stringResource(R.string.btn_clear_session),
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
                            stringResource(R.string.dialog_clear_browser_title),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = VrkaTokens.TextPrimary,
                        )
                    },
                    text = {
                        Text(
                            stringResource(R.string.dialog_clear_browser_desc),
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
                                    clearSessionMessage = if (success) context.resources.getString(R.string.msg_session_cleared) else context.resources.getString(R.string.msg_session_clear_failed)
                                }
                            }
                        ) {
                            Text(stringResource(R.string.btn_clear), color = VrkaTokens.Accent)
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { showClearDialog = false }) {
                            Text(stringResource(R.string.fallback_cancel), color = VrkaTokens.TextSecondary)
                        }
                    },
                    containerColor = VrkaTokens.SurfaceCard,
                )
            }
        }

        SettingsHeading(stringResource(R.string.settings_heading_diagnostics))
        val clipboardManager = androidx.compose.ui.platform.LocalClipboardManager.current
        var expandedDiagnosticId by remember { mutableStateOf<String?>(null) }

        VrkaSectionContainer {
            if (diagnostics.isEmpty()) {
                Text(
                    stringResource(R.string.diagnostics_empty),
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
                        text = stringResource(R.string.btn_clear_all),
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
                                text = if (isExpanded) stringResource(R.string.btn_hide_details) else stringResource(R.string.btn_view_details),
                                onClick = {
                                    expandedDiagnosticId = if (isExpanded) null else entry.id
                                },
                                height = 30.dp,
                            )

                            VrkaOutlinedButton(
                                text = stringResource(R.string.btn_copy_details),
                                onClick = {
                                    clipboardManager.setText(androidx.compose.ui.text.AnnotatedString(entry.toFormattedString()))
                                    Toast.makeText(
                                        context,
                                        context.resources.getString(R.string.msg_details_copied),
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
                                            text = stringResource(R.string.label_log_tail),
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

        SettingsHeading(stringResource(R.string.settings_heading_about))
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
                        text = stringResource(R.string.about_author),
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontFamily = VrkaMonoFamily,
                        ),
                        color = VrkaTokens.TextSecondary,
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = stringResource(R.string.about_github),
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
                    contentDescription = stringResource(R.string.about_logo_desc),
                    contentScale = androidx.compose.ui.layout.ContentScale.Fit,
                    modifier = Modifier.size(72.dp),
                )
            }

            Spacer(modifier = Modifier.height(8.dp))
            VrkaDivider()
            Spacer(modifier = Modifier.height(4.dp))

            VrkaSettingRow(
                title = stringResource(R.string.pref_app_updates),
                subtitle = when (val state = appUpdateState) {
                    is com.mvrk.vrka.update.AppUpdateCheckState.Checking -> stringResource(R.string.desc_checking_updates)
                    is com.mvrk.vrka.update.AppUpdateCheckState.UpdateAvailable -> stringResource(R.string.desc_update_available, state.release.version)
                    is com.mvrk.vrka.update.AppUpdateCheckState.UpToDate -> stringResource(R.string.desc_up_to_date)
                    is com.mvrk.vrka.update.AppUpdateCheckState.Error -> state.message
                    else -> stringResource(R.string.desc_check_releases)
                },
                isSubtitleMono = true,
            ) {
                VrkaOutlinedButton(
                    text = if (appUpdateState is com.mvrk.vrka.update.AppUpdateCheckState.Checking) stringResource(R.string.btn_checking) else stringResource(R.string.btn_check_for_updates),
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

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is android.content.ContextWrapper -> baseContext.findActivity()
    else -> null
}
