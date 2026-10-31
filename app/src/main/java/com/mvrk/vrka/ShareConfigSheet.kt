package com.mvrk.vrka

import android.content.Intent
import android.text.format.Formatter as AndroidFormatter
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Geometry for the compact floating configuration card. It never covers the whole screen and
 * never exceeds a comfortable reading width, so the originating app stays visible around it.
 */
internal const val SHARE_CARD_WIDTH_FRACTION = 0.90f
internal const val SHARE_CARD_MAX_WIDTH_DP = 420
internal const val SHARE_CARD_MAX_HEIGHT_FRACTION = 0.85f
private const val SHARE_CARD_SCRIM_ALPHA = 0.55f

internal fun shareCardWidth(availableWidth: Dp): Dp =
    minOf(availableWidth * SHARE_CARD_WIDTH_FRACTION, SHARE_CARD_MAX_WIDTH_DP.dp)

internal fun shareCardMaxHeight(availableHeight: Dp): Dp =
    availableHeight * SHARE_CARD_MAX_HEIGHT_FRACTION

@Composable
internal fun ShareConfigSheet(
    link: SharedLinkRequest,
    settings: AppSettings,
    repository: SettingsRepository,
    onEstimateSize: suspend (DownloadRequest, Boolean) -> SizeEstimate,
    onEnqueue: (DownloadRequest, SizeEstimate?) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
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
    val selectionLabel = if (type == ShareMediaType.AUDIO) {
        if (audioFormat == AudioFormat.MP3) {
            "MP3 $bitrate kbps"
        } else {
            audioFormat.label.substringBefore(" (")
        }
    } else {
        videoQualityLabel(quality)
    }

    val estimateGate = remember(link.id) { SizeEstimateGate() }
    var estimate by remember(link.id) { mutableStateOf<SizeEstimate?>(null) }
    var estimating by remember(link.id) { mutableStateOf(settings.showDownloadSizeConfirmation) }
    var estimateRetryToken by remember(link.id) { mutableStateOf(0) }
    var firstPreflight by remember(link.id) { mutableStateOf(true) }

    LaunchedEffect(previewRequest, settings.showDownloadSizeConfirmation, estimateRetryToken) {
        estimateGate.invalidate()
        if (!settings.showDownloadSizeConfirmation) {
            estimate = null
            estimating = false
            return@LaunchedEffect
        }
        estimating = true
        estimate = null
        val token = estimateGate.begin()
        // The sheet appears immediately. Only selection changes are debounced; the first
        // preflight and explicit retries start at once.
        val immediate = estimateRetryToken > 0 || firstPreflight
        firstPreflight = false
        if (!immediate) delay(ESTIMATE_DEBOUNCE_MS)
        val result = runCatching { onEstimateSize(previewRequest, estimateRetryToken > 0) }
            .getOrElse { SizeEstimate.unavailable("error") }
        if (!estimateGate.accepts(token)) return@LaunchedEffect
        estimate = result
        estimating = false
    }
    DisposableEffect(link.id) {
        onDispose { estimateGate.invalidate() }
    }

    val locationReady = !needsLocation || locationUri.isNotBlank()
    // Size metadata is informational; a slow or failed preflight must not block the download.
    val canDownload = locationReady && !submitted

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
            previewRequest.copy(destinationTreeUri = destination),
            estimate,
        )
        onDismiss()
    }

    BackHandler { onDismiss() }

    val entryState = remember { MutableTransitionState(false).apply { targetState = true } }
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val cardWidth = shareCardWidth(maxWidth)
        val cardMaxHeight = shareCardMaxHeight(maxHeight)
        AnimatedVisibility(
            visibleState = entryState,
            enter = fadeIn(tween(160)) + slideInVertically(tween(220)) { height -> height / 12 },
            modifier = Modifier.fillMaxSize(),
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = SHARE_CARD_SCRIM_ALPHA))
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = onDismiss,
                        ),
                )
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .windowInsetsPadding(WindowInsets.navigationBars)
                        .padding(bottom = 16.dp),
                ) {
                    Surface(
                        shape = RoundedCornerShape(28.dp),
                        color = VrkaTokens.SurfaceElevated,
                        contentColor = VrkaTokens.TextPrimary,
                        shadowElevation = 24.dp,
                        modifier = Modifier
                            .width(cardWidth)
                            .heightIn(max = cardMaxHeight)
                            // Surface draws its background to the shape but does not clip
                            // children; without this the scrolling content escapes the card.
                            .clip(RoundedCornerShape(28.dp)),
                    ) {
                        Column(
                            modifier = Modifier
                                .verticalScroll(rememberScrollState())
                                .padding(horizontal = 20.dp, vertical = 20.dp),
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
                                                label = videoQualityLabel(item),
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
                                    selectionLabel = selectionLabel,
                                    onRetry = { estimateRetryToken++ },
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
            }
        }
    }
}

@Composable
private fun videoQualityLabel(item: VideoQuality): String = when (item) {
    VideoQuality.BEST -> stringResource(R.string.video_quality_best)
    VideoQuality.P2160 -> "2160p 4K"
    VideoQuality.P1440 -> "1440p 2K"
    VideoQuality.P1080 -> "1080p FHD"
    VideoQuality.P720 -> "720p HD"
    VideoQuality.P480 -> "480p"
    VideoQuality.P360 -> "360p"
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
    selectionLabel: String,
    onRetry: () -> Unit,
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
                        selectionLabel + " · " + stringResource(
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
                else -> {
                    Text(
                        stringResource(R.string.estimate_unavailable),
                        style = MaterialTheme.typography.bodyMedium,
                        color = VrkaTokens.TextSecondary,
                    )
                    Text(
                        stringResource(R.string.estimate_unavailable_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = VrkaTokens.TextTertiary,
                    )
                    TextButton(
                        onClick = onRetry,
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(
                            horizontal = 0.dp,
                            vertical = 0.dp,
                        ),
                    ) {
                        Text(stringResource(R.string.action_retry))
                    }
                }
            }
        }
    }
}

private const val ESTIMATE_DEBOUNCE_MS = 300L
