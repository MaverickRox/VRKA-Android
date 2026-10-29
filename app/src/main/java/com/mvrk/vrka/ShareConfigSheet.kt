package com.mvrk.vrka

import android.content.Intent
import android.text.format.Formatter as AndroidFormatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ShareConfigSheet(
    link: SharedLinkRequest,
    settings: AppSettings,
    repository: SettingsRepository,
    onEstimateSize: suspend (DownloadRequest) -> SizeEstimate,
    onEnqueue: (DownloadRequest) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val initial = settings.shareSheetPreferences ?: ShareSheetPreferences(
        mode = settings.defaultMode,
        quality = settings.defaultQuality,
        audioFormat = settings.defaultAudioFormat,
        mp3Bitrate = settings.defaultMp3Bitrate,
    )

    var type by remember(link.id) {
        mutableStateOf(
            when {
                initial.useCommands && settings.customCommandMode -> ShareMediaType.COMMANDS
                initial.mode == MediaMode.AUDIO -> ShareMediaType.AUDIO
                else -> ShareMediaType.VIDEO
            },
        )
    }
    var quality by remember(link.id) { mutableStateOf(initial.quality) }
    var audioFormat by remember(link.id) { mutableStateOf(initial.audioFormat) }
    var bitrate by remember(link.id) { mutableStateOf(initial.mp3Bitrate) }
    var subtitles by remember(link.id) { mutableStateOf(initial.downloadSubtitles) }
    var embedThumbnail by remember(link.id) { mutableStateOf(initial.embedThumbnail) }
    var rememberChoices by remember(link.id) { mutableStateOf(false) }
    var submitted by remember(link.id) { mutableStateOf(false) }

    val treeInvalid = settings.outputTreeUri.isNotBlank() &&
        !OutputPublisher.isTreePermissionValid(context, settings.outputTreeUri)
    val needsLocation = treeInvalid || settings.saveLocationMode == SaveLocationMode.ASK_EVERY_TIME
    var locationUri by remember(link.id) {
        mutableStateOf(if (needsLocation && !treeInvalid) settings.outputTreeUri else "")
    }
    var rememberLocation by remember(link.id) { mutableStateOf(true) }

    val folderPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            }
            locationUri = uri.toString()
        }
    }

    val preferences = ShareSheetPreferences(
        mode = if (type == ShareMediaType.AUDIO) MediaMode.AUDIO else MediaMode.VIDEO,
        quality = quality,
        audioFormat = audioFormat,
        mp3Bitrate = bitrate,
        downloadSubtitles = subtitles,
        embedThumbnail = embedThumbnail,
        useCommands = type == ShareMediaType.COMMANDS,
    )
    val template = if (type == ShareMediaType.COMMANDS) {
        settings.customCommandTemplates.firstOrNull { it.id == settings.selectedCustomCommandTemplateId }
    } else {
        null
    }
    val previewRequest = ShareRequestBuilder.build(link.url, preferences, type, template)

    val estimateGate = remember(link.id) { SizeEstimateGate() }
    var estimate by remember(link.id) { mutableStateOf<SizeEstimate?>(null) }
    var estimating by remember(link.id) { mutableStateOf(settings.showDownloadSizeConfirmation) }

    LaunchedEffect(previewRequest, settings.showDownloadSizeConfirmation) {
        estimateGate.invalidate()
        if (!settings.showDownloadSizeConfirmation) {
            estimate = null
            estimating = false
            return@LaunchedEffect
        }
        estimating = true
        estimate = null
        val token = estimateGate.begin()
        delay(ESTIMATE_DEBOUNCE_MS)
        val result = runCatching { onEstimateSize(previewRequest) }
            .getOrElse { SizeEstimate.unavailable("error") }
        if (!estimateGate.accepts(token)) return@LaunchedEffect
        estimate = result
        estimating = false
    }
    DisposableEffect(link.id) {
        onDispose { estimateGate.invalidate() }
    }

    val locationReady = !needsLocation || locationUri.isNotBlank()
    val estimateReady = !settings.showDownloadSizeConfirmation || (!estimating && estimate != null)
    val canDownload = locationReady && estimateReady && !submitted

    fun submit() {
        if (!canDownload) return
        submitted = true
        val destination = if (needsLocation) locationUri else settings.outputTreeUri
        if (needsLocation) {
            scope.launch {
                repository.setDownloadLocation(
                    uri = locationUri,
                    mode = if (rememberLocation) {
                        SaveLocationMode.REMEMBER_LOCATION
                    } else {
                        SaveLocationMode.ASK_EVERY_TIME
                    },
                    configured = true,
                )
            }
        }
        if (rememberChoices) {
            scope.launch { repository.setShareSheetPreferences(preferences) }
        }
        onEnqueue(
            ShareRequestBuilder.build(link.url, preferences, type, template).copy(
                destinationTreeUri = destination,
            ),
        )
        onDismiss()
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = VrkaTokens.SurfaceElevated,
        contentColor = VrkaTokens.TextPrimary,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .navigationBarsPadding()
                .padding(bottom = 20.dp),
        ) {
            Text(
                stringResource(R.string.share_sheet_title),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = VrkaTokens.TextPrimary,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                stringResource(R.string.share_sheet_source),
                style = MaterialTheme.typography.labelSmall.copy(
                    fontFamily = VrkaMonoFamily,
                    letterSpacing = 1.1.sp,
                ),
                color = VrkaTokens.TextTertiary,
            )
            Spacer(Modifier.height(3.dp))
            Text(
                link.url.hostLabel(),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = VrkaTokens.TextPrimary,
            )
            Text(
                link.url,
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = VrkaMonoFamily),
                color = VrkaTokens.TextSecondary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )

            Spacer(Modifier.height(18.dp))
            ShareSectionLabel(stringResource(R.string.share_section_type))
            ShareChoiceRow {
                ShareMediaType.entries.forEach { item ->
                    if (item == ShareMediaType.COMMANDS && !settings.customCommandMode) return@forEach
                    VrkaChip(
                        selected = type == item,
                        onClick = { type = item },
                        label = when (item) {
                            ShareMediaType.VIDEO -> stringResource(R.string.media_mode_video)
                            ShareMediaType.AUDIO -> stringResource(R.string.media_mode_audio)
                            ShareMediaType.COMMANDS -> stringResource(R.string.share_type_commands)
                        },
                        isMonospace = true,
                    )
                }
            }

            when (type) {
                ShareMediaType.VIDEO -> {
                    Spacer(Modifier.height(16.dp))
                    ShareSectionLabel(stringResource(R.string.section_video_resolution))
                    ShareChoiceRow {
                        VideoQuality.entries.forEach { item ->
                            VrkaChip(
                                selected = quality == item,
                                onClick = { quality = item },
                                label = when (item) {
                                    VideoQuality.BEST -> stringResource(R.string.video_quality_best)
                                    VideoQuality.P2160 -> "2160p 4K"
                                    VideoQuality.P1440 -> "1440p 2K"
                                    VideoQuality.P1080 -> "1080p FHD"
                                    VideoQuality.P720 -> "720p HD"
                                    VideoQuality.P480 -> "480p"
                                    VideoQuality.P360 -> "360p"
                                },
                                isMonospace = true,
                            )
                        }
                    }
                }
                ShareMediaType.AUDIO -> {
                    Spacer(Modifier.height(16.dp))
                    ShareSectionLabel(stringResource(R.string.section_audio_format))
                    ShareChoiceRow {
                        AudioFormat.entries.forEach { item ->
                            VrkaChip(
                                selected = audioFormat == item,
                                onClick = { audioFormat = item },
                                label = item.label.substringBefore(" ("),
                                isMonospace = true,
                            )
                        }
                    }
                    if (audioFormat == AudioFormat.MP3) {
                        Spacer(Modifier.height(14.dp))
                        ShareSectionLabel(stringResource(R.string.section_mp3_bitrate))
                        ShareChoiceRow {
                            listOf(320, 256, 224, 192, 160, 128).forEach { item ->
                                VrkaChip(
                                    selected = bitrate == item,
                                    onClick = { bitrate = item },
                                    label = "$item kbps",
                                    isMonospace = true,
                                )
                            }
                        }
                    }
                }
                ShareMediaType.COMMANDS -> {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        stringResource(
                            R.string.share_commands_template,
                            template?.name ?: stringResource(R.string.share_commands_none),
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = VrkaTokens.TextSecondary,
                    )
                }
            }

            if (type != ShareMediaType.COMMANDS) {
                Spacer(Modifier.height(10.dp))
                ShareOptionToggle(
                    label = stringResource(R.string.opt_download_subtitles),
                    checked = subtitles,
                    onCheckedChange = { subtitles = it },
                )
            }
            if (type == ShareMediaType.AUDIO && audioFormat != AudioFormat.WAV) {
                ShareOptionToggle(
                    label = stringResource(R.string.opt_embed_thumbnail),
                    checked = embedThumbnail,
                    onCheckedChange = { embedThumbnail = it },
                )
            }

            if (settings.showDownloadSizeConfirmation) {
                Spacer(Modifier.height(14.dp))
                ShareEstimateCard(
                    estimate = estimate,
                    pending = estimating,
                )
            }

            Spacer(Modifier.height(12.dp))
            ShareOptionToggle(
                label = stringResource(R.string.share_remember_choices),
                checked = rememberChoices,
                onCheckedChange = { rememberChoices = it },
            )

            if (needsLocation) {
                Spacer(Modifier.height(10.dp))
                Text(
                    stringResource(R.string.dialog_download_location_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = VrkaTokens.TextSecondary,
                )
                Spacer(Modifier.height(8.dp))
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = VrkaTokens.SurfaceCard,
                    border = BorderStroke(1.dp, VrkaTokens.BorderSubtle),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Column {
                            Text(
                                stringResource(R.string.label_destination),
                                style = MaterialTheme.typography.labelSmall,
                                color = VrkaTokens.TextTertiary,
                            )
                            Spacer(Modifier.height(2.dp))
                            Text(
                                OutputPublisher.formatDisplayPath(locationUri),
                                style = MaterialTheme.typography.bodyMedium.copy(fontFamily = VrkaMonoFamily),
                                fontWeight = FontWeight.SemiBold,
                                color = VrkaTokens.TextPrimary,
                            )
                        }
                        VrkaOutlinedButton(
                            text = stringResource(R.string.btn_choose_location),
                            onClick = { folderPicker.launch(null) },
                            modifier = Modifier.fillMaxWidth(),
                            height = 36.dp,
                        )
                    }
                }
                ShareOptionToggle(
                    label = stringResource(R.string.opt_remember_location),
                    checked = rememberLocation,
                    onCheckedChange = { rememberLocation = it },
                )
            }

            Spacer(Modifier.height(20.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                VrkaOutlinedButton(
                    text = stringResource(R.string.btn_cancel),
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f),
                    height = 50.dp,
                )
                VrkaPrimaryButton(
                    text = stringResource(R.string.btn_download),
                    onClick = { submit() },
                    modifier = Modifier.weight(1.4f),
                    enabled = canDownload,
                    height = 50.dp,
                )
            }
        }
    }
}

@Composable
private fun ShareSectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall.copy(
            fontFamily = VrkaMonoFamily,
            letterSpacing = 1.1.sp,
            fontWeight = FontWeight.Bold,
        ),
        color = VrkaTokens.TextTertiary,
        modifier = Modifier.padding(start = 2.dp, bottom = 8.dp),
    )
}

@Composable
private fun ShareChoiceRow(content: @Composable () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        content = { content() },
    )
}

@Composable
private fun ShareOptionToggle(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = androidx.compose.material3.CheckboxDefaults.colors(
                checkedColor = VrkaTokens.Accent,
                checkmarkColor = androidx.compose.ui.graphics.Color.White,
            ),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = VrkaTokens.TextPrimary,
        )
    }
}

@Composable
private fun ShareEstimateCard(
    estimate: SizeEstimate?,
    pending: Boolean,
) {
    val context = LocalContext.current
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = VrkaTokens.SurfaceCard,
        border = BorderStroke(1.dp, VrkaTokens.BorderSubtle),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            when {
                pending -> Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = VrkaTokens.AccentLight,
                    )
                    Text(
                        stringResource(R.string.estimate_checking),
                        style = MaterialTheme.typography.bodySmall,
                        color = VrkaTokens.TextSecondary,
                    )
                }
                estimate?.isAvailable == true -> {
                    Text(
                        stringResource(
                            if (estimate.kind == SizeEstimateKind.EXACT) {
                                R.string.estimate_exact
                            } else {
                                R.string.estimate_approximate
                            },
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = VrkaTokens.TextTertiary,
                    )
                    Text(
                        AndroidFormatter.formatFileSize(context, estimate.bytes ?: 0L),
                        style = MaterialTheme.typography.titleLarge.copy(fontFamily = VrkaMonoFamily),
                        fontWeight = FontWeight.Bold,
                        color = VrkaTokens.TextPrimary,
                    )
                }
                else -> Text(
                    stringResource(R.string.estimate_unavailable),
                    style = MaterialTheme.typography.bodyMedium,
                    color = VrkaTokens.TextSecondary,
                )
            }
        }
    }
}

private const val ESTIMATE_DEBOUNCE_MS = 300L
