package com.mvrk.vrka

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

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
            "Video · ${quality.label} · 60 FPS ${if (prefer60Fps) "on" else "off"}"
        MediaMode.AUDIO -> when (audioFormat) {
            AudioFormat.MP3 -> "MP3 · $bitrate kbps"
            AudioFormat.WAV -> "WAV · source-dependent"
            AudioFormat.FLAC -> "FLAC · source-dependent"
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
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 2.dp),
    ) {
        Text(
            "DOWNLOAD READOUT",
            style = MaterialTheme.typography.labelSmall.copy(
                fontFamily = VrkaMonoFamily,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.2.sp,
            ),
            color = VrkaTokens.TextTertiary,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            primary,
            style = MaterialTheme.typography.bodyMedium.copy(
                fontFamily = VrkaMonoFamily,
                fontWeight = FontWeight.Bold,
                fontSize = 13.sp,
            ),
            color = VrkaTokens.TextPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (extras.isNotEmpty()) {
            Text(
                extras.joinToString(" · "),
                style = MaterialTheme.typography.bodySmall.copy(
                    fontFamily = VrkaMonoFamily,
                    fontSize = 12.sp,
                ),
                color = VrkaTokens.TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}
