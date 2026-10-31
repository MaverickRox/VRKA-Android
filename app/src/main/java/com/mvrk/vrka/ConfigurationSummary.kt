package com.mvrk.vrka

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
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
    val fpsStatus = if (prefer60Fps) stringResource(R.string.readout_fps_on) else stringResource(R.string.readout_fps_off)
    val qualityLabel = if (quality == VideoQuality.BEST) {
        stringResource(R.string.video_quality_best)
    } else {
        quality.label
    }
    val primary = when (mode) {
        MediaMode.VIDEO ->
            stringResource(R.string.readout_video, qualityLabel, fpsStatus)
        MediaMode.AUDIO -> when (audioFormat) {
            AudioFormat.MP3 -> stringResource(R.string.readout_mp3, bitrate)
            AudioFormat.OPUS -> stringResource(R.string.readout_opus)
            AudioFormat.WAV -> stringResource(R.string.readout_wav)
        }
    }
    val extras = buildList {
        if (playlist) {
            val range = listOf(playlistStart, playlistEnd)
                .filter(String::isNotBlank)
                .joinToString("–")
            add(if (range.isBlank()) stringResource(R.string.readout_playlist) else stringResource(R.string.readout_playlist_range, range))
        }
        if (subtitles) add(stringResource(R.string.readout_subtitles))
        if (trimStart.isNotBlank() || trimEnd.isNotBlank()) add(stringResource(R.string.readout_trim))
        if (sponsorBlock) add(stringResource(R.string.readout_sponsorblock))
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 2.dp),
    ) {
        Text(
            stringResource(R.string.readout_title),
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
