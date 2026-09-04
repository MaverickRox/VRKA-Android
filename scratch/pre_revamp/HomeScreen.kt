package com.mvrk.vrka

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
internal fun HomeScreen(
    settings: AppSettings,
    runtime: RuntimeStatus,
    modifier: Modifier = Modifier,
    onEnqueue: (DownloadRequest) -> Unit,
) {
    val context = LocalContext.current
    var url by remember { mutableStateOf("") }
    var mode by remember { mutableStateOf(settings.defaultMode) }
    var quality by remember { mutableStateOf(settings.defaultQuality) }
    var prefer60Fps by remember { mutableStateOf(false) }
    var audioFormat by remember { mutableStateOf(settings.defaultAudioFormat) }
    var bitrate by remember { mutableStateOf(settings.defaultMp3Bitrate) }
    var advanced by remember { mutableStateOf(false) }
    var playlist by remember { mutableStateOf(false) }
    var playlistStart by remember { mutableStateOf("") }
    var playlistEnd by remember { mutableStateOf("") }
    var subtitles by remember { mutableStateOf(false) }
    var automaticCaptions by remember { mutableStateOf(true) }
    var embedSubtitles by remember { mutableStateOf(true) }
    var subtitleLanguages by remember { mutableStateOf("en.*") }
    var embedMetadata by remember { mutableStateOf(true) }
    var embedThumbnail by remember { mutableStateOf(true) }
    var sponsorBlock by remember { mutableStateOf(false) }
    var sponsorCategories by remember { mutableStateOf("sponsor,selfpromo,interaction") }
    var trimStart by remember { mutableStateOf("") }
    var trimEnd by remember { mutableStateOf("") }
    var validation by remember { mutableStateOf("") }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text(
                    "VRKA",
                    style = MaterialTheme.typography.headlineLarge,
                    color = VrkaPurpleLight,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    "High-fidelity media acquisition engine",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Spacer(Modifier.height(20.dp))

        // Hero URL Input Card with Integrated Paste / Clear Affordance
        Surface(
            color = VrkaSurfaceCard,
            contentColor = MaterialTheme.colorScheme.onSurface,
            shape = androidx.compose.foundation.shape.RoundedCornerShape(20.dp),
            border = BorderStroke(
                1.dp,
                if (validation.isNotBlank()) MaterialTheme.colorScheme.error else VrkaCardBorder,
            ),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_download),
                        contentDescription = null,
                        tint = VrkaPurpleLight,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(Modifier.width(12.dp))
                    androidx.compose.foundation.text.BasicTextField(
                        value = url,
                        onValueChange = {
                            url = it.trim()
                            validation = ""
                        },
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodyLarge.copy(
                            color = MaterialTheme.colorScheme.onSurface,
                        ),
                        modifier = Modifier.weight(1f),
                        decorationBox = { innerTextField ->
                            if (url.isEmpty()) {
                                Text(
                                    "Enter or paste media URL...",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f),
                                )
                            }
                            innerTextField()
                        },
                    )
                    Spacer(Modifier.width(8.dp))
                    if (url.isNotEmpty()) {
                        Surface(
                            onClick = {
                                url = ""
                                validation = ""
                            },
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            modifier = Modifier.size(28.dp),
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    painter = painterResource(R.drawable.ic_close),
                                    contentDescription = "Clear",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(14.dp),
                                )
                            }
                        }
                    } else {
                        Surface(
                            onClick = {
                                val clipboard = context.getSystemService(
                                    Context.CLIPBOARD_SERVICE,
                                ) as ClipboardManager
                                url = clipboard.primaryClip
                                    ?.getItemAt(0)
                                    ?.coerceToText(context)
                                    ?.toString()
                                    ?.trim()
                                    .orEmpty()
                                validation = ""
                            },
                            shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
                            color = VrkaPurple.copy(alpha = 0.18f),
                        ) {
                            Text(
                                "Paste",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = VrkaPurpleLight,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                            )
                        }
                    }
                }
                if (validation.isNotBlank()) {
                    Text(
                        validation,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
        }

        SectionTitle("Output Mode")
        ChoiceRow {
            MediaMode.entries.forEach { item ->
                FilterChip(
                    selected = mode == item,
                    onClick = { mode = item },
                    label = { Text(item.label) },
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp),
                    colors = androidx.compose.material3.FilterChipDefaults.filterChipColors(
                        selectedContainerColor = VrkaPurple,
                        selectedLabelColor = androidx.compose.ui.graphics.Color.White,
                    ),
                )
            }
        }

        if (mode == MediaMode.VIDEO) {
            SectionTitle("Video Quality")
            ChoiceRow {
                VideoQuality.entries.forEach { item ->
                    FilterChip(
                        selected = quality == item,
                        onClick = { quality = item },
                        label = { Text(item.label) },
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp),
                        colors = androidx.compose.material3.FilterChipDefaults.filterChipColors(
                            selectedContainerColor = VrkaPurple,
                            selectedLabelColor = androidx.compose.ui.graphics.Color.White,
                        ),
                    )
                }
            }
        } else {
            SectionTitle("Audio Format")
            ChoiceRow {
                AudioFormat.entries.forEach { item ->
                    FilterChip(
                        selected = audioFormat == item,
                        onClick = { audioFormat = item },
                        label = { Text(item.label.substringBefore(" (")) },
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp),
                        colors = androidx.compose.material3.FilterChipDefaults.filterChipColors(
                            selectedContainerColor = VrkaPurple,
                            selectedLabelColor = androidx.compose.ui.graphics.Color.White,
                        ),
                    )
                }
            }
            if (audioFormat == AudioFormat.MP3) {
                SectionTitle("MP3 Bitrate")
                ChoiceRow {
                    listOf(320, 256, 192, 128).forEach { item ->
                        FilterChip(
                            selected = bitrate == item,
                            onClick = { bitrate = item },
                            label = { Text("$item kbps", fontFamily = VrkaMonoFamily) },
                            shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp),
                            colors = androidx.compose.material3.FilterChipDefaults.filterChipColors(
                                selectedContainerColor = VrkaPurple,
                                selectedLabelColor = androidx.compose.ui.graphics.Color.White,
                            ),
                        )
                    }
                }
            }
            Text(
                when (audioFormat) {
                    AudioFormat.MP3 -> "Compressed audio. 320 kbps recommended."
                    AudioFormat.WAV -> "Uncompressed source stream with large file sizes."
                    AudioFormat.FLAC -> "Lossless container preservation."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp),
            )
        }

        Spacer(Modifier.height(14.dp))

        // Advanced Options Toggle Card
        Surface(
            onClick = { advanced = !advanced },
            shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
            color = VrkaSurfaceCard,
            contentColor = MaterialTheme.colorScheme.onSurface,
            border = BorderStroke(1.dp, VrkaCardBorder),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    if (advanced) "Hide advanced options" else "Show advanced options",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    if (advanced) "▲" else "▼",
                    style = MaterialTheme.typography.labelMedium,
                    color = VrkaPurpleLight,
                )
            }
        }

        androidx.compose.animation.AnimatedVisibility(
            visible = advanced,
            enter = androidx.compose.animation.expandVertically(
                animationSpec = androidx.compose.animation.core.tween(220),
            ) + androidx.compose.animation.fadeIn(
                animationSpec = androidx.compose.animation.core.tween(200),
            ),
            exit = androidx.compose.animation.shrinkVertically(
                animationSpec = androidx.compose.animation.core.tween(180),
            ) + androidx.compose.animation.fadeOut(
                animationSpec = androidx.compose.animation.core.tween(160),
            ),
        ) {
            Column(modifier = Modifier.padding(top = 10.dp)) {
                if (mode == MediaMode.VIDEO) {
                    OptionToggle("Prefer 60 FPS when available", prefer60Fps) { prefer60Fps = it }
                }
                OptionToggle("Playlist or range", playlist) { playlist = it }
                if (playlist) {
                    Row(
                        modifier = Modifier.padding(top = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        CompactNumberField(
                            value = playlistStart,
                            label = "Start",
                            modifier = Modifier.weight(1f),
                            onValueChange = { playlistStart = digitsOnly(it) },
                        )
                        CompactNumberField(
                            value = playlistEnd,
                            label = "End",
                            modifier = Modifier.weight(1f),
                            onValueChange = { playlistEnd = digitsOnly(it) },
                        )
                    }
                }
                OptionToggle("Download subtitles", subtitles) { subtitles = it }
                if (subtitles) {
                    OptionToggle("Include auto-generated captions", automaticCaptions) {
                        automaticCaptions = it
                    }
                    if (mode == MediaMode.VIDEO) {
                        OptionToggle("Embed subtitles in video", embedSubtitles) {
                            embedSubtitles = it
                        }
                    }
                    OutlinedTextField(
                        value = subtitleLanguages,
                        onValueChange = { subtitleLanguages = it.take(80) },
                        label = { Text("Subtitle language pattern") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                    )
                }
                OptionToggle("Embed title and media metadata", embedMetadata) {
                    embedMetadata = it
                }
                if (mode == MediaMode.AUDIO) {
                    OptionToggle("Embed thumbnail in audio", embedThumbnail) {
                        embedThumbnail = it
                    }
                }
                OptionToggle("Remove SponsorBlock segments", sponsorBlock) {
                    sponsorBlock = it
                }
                if (sponsorBlock) {
                    OutlinedTextField(
                        value = sponsorCategories,
                        onValueChange = { sponsorCategories = it.take(120) },
                        label = { Text("SponsorBlock categories") },
                        supportingText = {
                            Text("Comma-separated category names")
                        },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                    )
                }
                Text(
                    "Optional trim (HH:MM:SS or seconds)",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(top = 10.dp),
                )
                Row(
                    modifier = Modifier.padding(top = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    OutlinedTextField(
                        value = trimStart,
                        onValueChange = { trimStart = it.take(16) },
                        label = { Text("Start") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = trimEnd,
                        onValueChange = { trimEnd = it.take(16) },
                        label = { Text("End") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }

        ConfigurationSummary(
            mode = mode,
            quality = quality,
            prefer60Fps = prefer60Fps,
            audioFormat = audioFormat,
            bitrate = bitrate,
            playlist = playlist,
            playlistStart = playlistStart,
            playlistEnd = playlistEnd,
            subtitles = subtitles,
            trimStart = trimStart,
            trimEnd = trimEnd,
            sponsorBlock = sponsorBlock,
        )

        Spacer(Modifier.height(18.dp))

        // Hero Action Button
        Button(
            onClick = {
                val issue = validateRequest(
                    url = url,
                    playlist = playlist,
                    playlistStart = playlistStart,
                    playlistEnd = playlistEnd,
                    trimStart = trimStart,
                    trimEnd = trimEnd,
                )
                if (issue != null) {
                    validation = issue
                } else {
                    onEnqueue(
                        DownloadRequest(
                            url = url,
                            mode = mode,
                            quality = quality,
                            prefer60Fps = prefer60Fps,
                            audioFormat = audioFormat,
                            mp3Bitrate = bitrate,
                            isPlaylist = playlist,
                            playlistStart = playlistStart.toIntOrNull(),
                            playlistEnd = playlistEnd.toIntOrNull(),
                            downloadSubtitles = subtitles,
                            automaticCaptions = automaticCaptions,
                            embedSubtitles = embedSubtitles,
                            subtitleLanguages = subtitleLanguages,
                            embedMetadata = embedMetadata,
                            embedThumbnail = embedThumbnail,
                            sponsorBlock = sponsorBlock,
                            sponsorCategories = sponsorCategories,
                            trimStart = trimStart,
                            trimEnd = trimEnd,
                        ),
                    )
                    url = ""
                    validation = ""
                }
            },
            enabled = url.isNotBlank(),
            shape = androidx.compose.foundation.shape.RoundedCornerShape(20.dp),
            colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                containerColor = VrkaPurple,
                contentColor = androidx.compose.ui.graphics.Color.White,
                disabledContainerColor = VrkaPurple.copy(alpha = 0.25f),
                disabledContentColor = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.45f),
            ),
            modifier = Modifier
                .fillMaxWidth()
                .height(54.dp),
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_download),
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                "Add to queue",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
        }

        if (runtime.message.isNotBlank()) {
            Text(
                runtime.message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 10.dp),
            )
        }

        Spacer(Modifier.height(100.dp))
    }
}

@Composable
private fun SectionTitle(value: String) {
    Text(
        value,
        style = MaterialTheme.typography.titleSmall,
        modifier = Modifier.padding(top = 18.dp, bottom = 7.dp),
    )
}

@Composable
private fun ChoiceRow(content: @Composable () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        content = { content() },
    )
}

@Composable
private fun OptionToggle(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f).padding(top = 12.dp),
        )
        Checkbox(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun CompactNumberField(
    value: String,
    label: String,
    modifier: Modifier,
    onValueChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        modifier = modifier,
    )
}

private fun digitsOnly(value: String): String = value.filter(Char::isDigit).take(5)

private fun validateRequest(
    url: String,
    playlist: Boolean,
    playlistStart: String,
    playlistEnd: String,
    trimStart: String,
    trimEnd: String,
): String? {
    if (!url.startsWith("http://") && !url.startsWith("https://")) {
        return "Enter a complete http or https URL."
    }
    if (playlist) {
        val start = playlistStart.toIntOrNull()
        val end = playlistEnd.toIntOrNull()
        if (playlistStart.isNotBlank() && (start == null || start < 1)) {
            return "Playlist start must be 1 or higher."
        }
        if (playlistEnd.isNotBlank() && (end == null || end < 1)) {
            return "Playlist end must be 1 or higher."
        }
        if (start != null && end != null && end < start) {
            return "Playlist end must be the same as or higher than start."
        }
    }
    val parsedStart = parseTimestamp(trimStart)
    val parsedEnd = parseTimestamp(trimEnd)
    if (trimStart.isNotBlank() && parsedStart == null) {
        return "Trim start must be seconds or HH:MM:SS."
    }
    if (trimEnd.isNotBlank() && parsedEnd == null) {
        return "Trim end must be seconds or HH:MM:SS."
    }
    if (parsedStart != null && parsedEnd != null && parsedEnd <= parsedStart) {
        return "Trim end must be after trim start."
    }
    return null
}

private fun parseTimestamp(value: String): Double? {
    if (value.isBlank()) return null
    val parts = value.trim().split(':')
    if (parts.size !in 1..3) return null
    val numbers = parts.map { it.toDoubleOrNull() ?: return null }
    if (numbers.any { it < 0 || !it.isFinite() }) return null
    if (parts.size > 1 && numbers.last() >= 60) return null
    if (parts.size == 3 && numbers[1] >= 60) return null
    return numbers.fold(0.0) { total, part -> total * 60 + part }
}
