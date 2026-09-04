package com.mvrk.vrka

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
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
    var isUrlFocused by remember { mutableStateOf(false) }
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
            .padding(horizontal = 20.dp, vertical = 12.dp),
    ) {
        // Brand Header: Disciplined, Iconic, Minimalist
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "VRKA",
                style = MaterialTheme.typography.headlineMedium.copy(
                    fontFamily = VrkaMonoFamily,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.5.sp,
                ),
                color = VrkaTokens.TextPrimary,
            )
            Spacer(Modifier.width(10.dp))
            Text(
                "·",
                color = VrkaTokens.TextTertiary,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.width(10.dp))
            Surface(
                shape = RoundedCornerShape(VrkaTokens.RadiusSmall),
                color = VrkaTokens.AccentContainer,
                border = BorderStroke(1.dp, VrkaTokens.BorderActive),
            ) {
                Text(
                    "MEDIA ENGINE",
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontFamily = VrkaMonoFamily,
                        letterSpacing = 1.sp,
                    ),
                    color = VrkaTokens.AccentLight,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                )
            }
        }

        // Hero URL Input Bar: Single-line, horizontal scrolling, integrated paste/clear
        val urlBorderColor = when {
            validation.isNotBlank() -> VrkaTokens.Error
            isUrlFocused -> VrkaTokens.AccentLight
            else -> VrkaTokens.BorderSubtle
        }

        Surface(
            shape = RoundedCornerShape(VrkaTokens.RadiusMedium),
            color = VrkaTokens.SurfaceCard,
            border = BorderStroke(1.dp, urlBorderColor),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_download),
                    contentDescription = null,
                    tint = if (url.isNotBlank()) VrkaTokens.AccentLight else VrkaTokens.TextTertiary,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(12.dp))
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .padding(end = 8.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    if (url.isEmpty()) {
                        Text(
                            text = "Paste or enter media link...",
                            style = MaterialTheme.typography.bodyMedium,
                            color = VrkaTokens.TextTertiary,
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    BasicTextField(
                        value = url,
                        onValueChange = {
                            url = it.trim()
                            validation = ""
                        },
                        singleLine = true,
                        maxLines = 1,
                        textStyle = MaterialTheme.typography.bodyMedium.copy(
                            color = VrkaTokens.TextPrimary,
                            fontFamily = VrkaSansFamily,
                        ),
                        cursorBrush = SolidColor(VrkaTokens.AccentLight),
                        modifier = Modifier
                            .fillMaxWidth()
                            .onFocusChanged { isUrlFocused = it.isFocused },
                    )
                }

                if (url.isNotBlank()) {
                    Surface(
                        onClick = {
                            url = ""
                            validation = ""
                        },
                        shape = CircleShape,
                        color = VrkaTokens.SurfaceInset,
                        modifier = Modifier.size(28.dp),
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                painter = painterResource(R.drawable.ic_close),
                                contentDescription = "Clear",
                                tint = VrkaTokens.TextSecondary,
                                modifier = Modifier.size(13.dp),
                            )
                        }
                    }
                } else {
                    Surface(
                        onClick = {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            val clip = clipboard.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString()?.trim().orEmpty()
                            if (clip.isNotBlank()) {
                                url = clip
                                validation = ""
                            }
                        },
                        shape = RoundedCornerShape(10.dp),
                        color = VrkaTokens.Accent.copy(alpha = 0.16f),
                        border = BorderStroke(1.dp, VrkaTokens.BorderActive),
                    ) {
                        Text(
                            "PASTE",
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontFamily = VrkaMonoFamily,
                                letterSpacing = 0.5.sp,
                            ),
                            fontWeight = FontWeight.Bold,
                            color = VrkaTokens.AccentLight,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        )
                    }
                }
            }
        }

        if (validation.isNotBlank()) {
            Text(
                validation,
                style = MaterialTheme.typography.bodySmall,
                color = VrkaTokens.Error,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(start = 4.dp, top = 6.dp),
            )
        }

        Spacer(Modifier.height(16.dp))

        // Unified Media Configuration Card (Format & Quality)
        VrkaCard {
            Text(
                "FORMAT & QUALITY",
                style = MaterialTheme.typography.labelSmall.copy(fontFamily = VrkaMonoFamily),
                fontWeight = FontWeight.Bold,
                color = VrkaTokens.TextSecondary,
                letterSpacing = 0.8.sp,
            )
            Spacer(Modifier.height(10.dp))

            // Sliding Segmented Mode Control
            VrkaSegmentedControl(
                items = MediaMode.entries,
                selectedItem = mode,
                onItemSelected = { mode = it },
                label = { it.label },
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(14.dp))

            if (mode == MediaMode.VIDEO) {
                Text(
                    "Resolution",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = VrkaTokens.TextPrimary,
                )
                Spacer(Modifier.height(6.dp))
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    VideoQuality.entries.forEach { item ->
                        val isSelected = quality == item
                        Surface(
                            onClick = { quality = item },
                            shape = RoundedCornerShape(VrkaTokens.RadiusSmall),
                            color = if (isSelected) VrkaTokens.AccentContainer else VrkaTokens.SurfaceInset,
                            border = BorderStroke(
                                1.dp,
                                if (isSelected) VrkaTokens.BorderActive else VrkaTokens.BorderSubtle,
                            ),
                        ) {
                            Text(
                                text = item.label,
                                style = MaterialTheme.typography.labelMedium.copy(
                                    fontFamily = if (item != VideoQuality.BEST) VrkaMonoFamily else VrkaSansFamily,
                                ),
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                color = if (isSelected) VrkaTokens.AccentLight else VrkaTokens.TextSecondary,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                            )
                        }
                    }
                }
            } else {
                Text(
                    "Audio Format",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = VrkaTokens.TextPrimary,
                )
                Spacer(Modifier.height(6.dp))
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    AudioFormat.entries.forEach { item ->
                        val isSelected = audioFormat == item
                        Surface(
                            onClick = { audioFormat = item },
                            shape = RoundedCornerShape(VrkaTokens.RadiusSmall),
                            color = if (isSelected) VrkaTokens.AccentContainer else VrkaTokens.SurfaceInset,
                            border = BorderStroke(
                                1.dp,
                                if (isSelected) VrkaTokens.BorderActive else VrkaTokens.BorderSubtle,
                            ),
                        ) {
                            Text(
                                text = item.label.substringBefore(" ("),
                                style = MaterialTheme.typography.labelMedium.copy(fontFamily = VrkaMonoFamily),
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                color = if (isSelected) VrkaTokens.AccentLight else VrkaTokens.TextSecondary,
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp),
                            )
                        }
                    }
                }

                if (audioFormat == AudioFormat.MP3) {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "Bitrate",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = VrkaTokens.TextPrimary,
                    )
                    Spacer(Modifier.height(6.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        listOf(320, 256, 192, 128).forEach { item ->
                            val isSelected = bitrate == item
                            Surface(
                                onClick = { bitrate = item },
                                shape = RoundedCornerShape(VrkaTokens.RadiusSmall),
                                color = if (isSelected) VrkaTokens.AccentContainer else VrkaTokens.SurfaceInset,
                                border = BorderStroke(
                                    1.dp,
                                    if (isSelected) VrkaTokens.BorderActive else VrkaTokens.BorderSubtle,
                                ),
                            ) {
                                Text(
                                    text = "$item kbps",
                                    style = MaterialTheme.typography.labelMedium.copy(fontFamily = VrkaMonoFamily),
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                    color = if (isSelected) VrkaTokens.AccentLight else VrkaTokens.TextSecondary,
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                                )
                            }
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        // Advanced Options Collapsible Card
        Surface(
            shape = RoundedCornerShape(VrkaTokens.RadiusCard),
            color = VrkaTokens.SurfaceCard,
            border = BorderStroke(1.dp, VrkaTokens.BorderSubtle),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.fillMaxWidth().padding(14.dp)) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { advanced = !advanced }
                        .padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "Advanced Options",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = VrkaTokens.TextPrimary,
                    )
                    Text(
                        text = if (advanced) "▲" else "▼",
                        style = MaterialTheme.typography.labelSmall,
                        color = VrkaTokens.AccentLight,
                    )
                }

                AnimatedVisibility(
                    visible = advanced,
                    enter = expandVertically(tween(180)) + fadeIn(tween(160)),
                    exit = shrinkVertically(tween(160)) + fadeOut(tween(140)),
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
                                    label = "Start index",
                                    modifier = Modifier.weight(1f),
                                    onValueChange = { playlistStart = digitsOnly(it) },
                                )
                                CompactNumberField(
                                    value = playlistEnd,
                                    label = "End index",
                                    modifier = Modifier.weight(1f),
                                    onValueChange = { playlistEnd = digitsOnly(it) },
                                )
                            }
                        }
                        OptionToggle("Download subtitles", subtitles) { subtitles = it }
                        if (subtitles) {
                            OptionToggle("Include auto-generated captions", automaticCaptions) { automaticCaptions = it }
                            if (mode == MediaMode.VIDEO) {
                                OptionToggle("Embed subtitles in video", embedSubtitles) { embedSubtitles = it }
                            }
                            OutlinedTextField(
                                value = subtitleLanguages,
                                onValueChange = { subtitleLanguages = it.take(80) },
                                label = { Text("Language pattern") },
                                singleLine = true,
                                colors = outlinedFieldColors(),
                                modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                            )
                        }
                        OptionToggle("Embed metadata", embedMetadata) { embedMetadata = it }
                        if (mode == MediaMode.AUDIO) {
                            OptionToggle("Embed thumbnail in audio", embedThumbnail) { embedThumbnail = it }
                        }
                        OptionToggle("Remove SponsorBlock segments", sponsorBlock) { sponsorBlock = it }
                        if (sponsorBlock) {
                            OutlinedTextField(
                                value = sponsorCategories,
                                onValueChange = { sponsorCategories = it.take(120) },
                                label = { Text("Categories (comma-separated)") },
                                singleLine = true,
                                colors = outlinedFieldColors(),
                                modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                            )
                        }
                        Text(
                            "Optional trim (HH:MM:SS or seconds)",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = VrkaTokens.TextSecondary,
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
                                colors = outlinedFieldColors(),
                                modifier = Modifier.weight(1f),
                            )
                            OutlinedTextField(
                                value = trimEnd,
                                onValueChange = { trimEnd = it.take(16) },
                                label = { Text("End") },
                                singleLine = true,
                                colors = outlinedFieldColors(),
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(14.dp))

        // Concise Download Plan Summary
        val primaryPlanText = when (mode) {
            MediaMode.VIDEO -> "Video  ·  ${quality.label}  ·  60 FPS ${if (prefer60Fps) "on" else "off"}"
            MediaMode.AUDIO -> when (audioFormat) {
                AudioFormat.MP3 -> "MP3  ·  $bitrate kbps"
                AudioFormat.WAV -> "WAV  ·  Source stream"
                AudioFormat.FLAC -> "FLAC  ·  Lossless"
            }
        }
        val extraPlanParts = buildList {
            if (playlist) add(if (playlistStart.isNotBlank() || playlistEnd.isNotBlank()) "Playlist $playlistStart–$playlistEnd" else "Playlist")
            if (subtitles) add("Subtitles")
            if (trimStart.isNotBlank() || trimEnd.isNotBlank()) add("Trim")
            if (sponsorBlock) add("SponsorBlock")
        }

        VrkaInsetSurface(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "DOWNLOAD PLAN",
                        style = MaterialTheme.typography.labelSmall.copy(fontFamily = VrkaMonoFamily),
                        color = VrkaTokens.AccentLight,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.5.sp,
                    )
                    Text(
                        text = primaryPlanText,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = VrkaTokens.TextPrimary,
                        modifier = Modifier.padding(top = 2.dp),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (extraPlanParts.isNotEmpty()) {
                    Text(
                        text = extraPlanParts.joinToString(" • "),
                        style = MaterialTheme.typography.labelSmall,
                        color = VrkaTokens.TextSecondary,
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        // Dominant Hero Action Button
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
            shape = RoundedCornerShape(VrkaTokens.RadiusMedium),
            colors = ButtonDefaults.buttonColors(
                containerColor = VrkaTokens.Accent,
                contentColor = Color.White,
                disabledContainerColor = VrkaTokens.Accent.copy(alpha = 0.22f),
                disabledContentColor = Color.White.copy(alpha = 0.38f),
            ),
            modifier = Modifier
                .fillMaxWidth()
                .height(54.dp),
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_download),
                contentDescription = null,
                modifier = Modifier.size(19.dp),
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
                color = VrkaTokens.TextSecondary,
                modifier = Modifier.padding(top = 10.dp),
            )
        }
    }
}

@Composable
private fun OptionToggle(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onChange(!checked) }
            .padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = VrkaTokens.TextPrimary,
            modifier = Modifier.weight(1f),
        )
        Checkbox(
            checked = checked,
            onCheckedChange = onChange,
            colors = CheckboxDefaults.colors(
                checkedColor = VrkaTokens.Accent,
                uncheckedColor = VrkaTokens.BorderSubtle,
                checkmarkColor = Color.White,
            ),
        )
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
        colors = outlinedFieldColors(),
        modifier = modifier,
    )
}

@Composable
private fun outlinedFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = VrkaTokens.AccentLight,
    unfocusedBorderColor = VrkaTokens.BorderSubtle,
    focusedLabelColor = VrkaTokens.AccentLight,
    unfocusedLabelColor = VrkaTokens.TextTertiary,
    cursorColor = VrkaTokens.AccentLight,
)

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
