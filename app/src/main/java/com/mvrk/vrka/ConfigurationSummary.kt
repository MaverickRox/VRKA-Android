package com.mvrk.vrka

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@Composable
internal fun ConfigurationSummary(
    mode: MediaMode,
    quality: VideoQuality,
    prefer60Fps: Boolean,
    audioFormat: AudioFormat,
    bitrate: Int,
    playlist: Boolean,
    playlistStart: String,
    playlistEnd: String,
    subtitles: Boolean,
    trimStart: String,
    trimEnd: String,
    sponsorBlock: Boolean,
) {
    val primary = when (mode) {
        MediaMode.VIDEO ->
            "Video  ·  ${quality.label}  ·  60 FPS ${if (prefer60Fps) "on" else "off"}"
        MediaMode.AUDIO -> when (audioFormat) {
            AudioFormat.MP3 -> "MP3  ·  $bitrate kbps"
            AudioFormat.WAV -> "WAV  ·  source-dependent"
            AudioFormat.FLAC -> "FLAC  ·  source-dependent"
        }
    }
    val extras = buildList {
        if (playlist) {
            val range = listOf(playlistStart, playlistEnd)
                .filter(String::isNotBlank)
                .joinToString("–")
            add(if (range.isBlank()) "Playlist" else "Playlist $range")
        }
        if (subtitles) add("Subtitles")
        if (trimStart.isNotBlank() || trimEnd.isNotBlank()) add("Trim enabled")
        if (sponsorBlock) add("SponsorBlock")
    }
    Surface(
        color = VrkaSurfaceCard,
        shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, VrkaCardBorder),
        modifier = Modifier.fillMaxWidth().padding(top = 18.dp),
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
            Text(
                "DOWNLOAD PLAN",
                style = MaterialTheme.typography.labelSmall,
                color = VrkaPurpleLight,
                fontWeight = FontWeight.Bold,
            )
            Text(
                primary,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.Bold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 3.dp),
            )
            if (extras.isNotEmpty()) {
                Text(
                    extras.joinToString("  ·  "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 3.dp),
                )
            }
        }
    }
}
