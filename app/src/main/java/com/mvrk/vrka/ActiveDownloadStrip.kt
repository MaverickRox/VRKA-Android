package com.mvrk.vrka

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color

@Composable
internal fun ActiveDownloadStrip(
    job: DownloadJob,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val animatedProgress by animateFloatAsState(
        targetValue = (job.progress.coerceIn(0f, 100f) / 100f),
        label = "strip progress",
    )

    val colors = LocalVrkaColors.current
    val stripGlassTint = when {
        colors.isLight -> Color(0xEEFFFFFF)
        colors.background == Color.Black -> Color(0xF2000000)
        else -> Color(0xDC14141E)
    }
    val stripBorder = if (colors.isLight) Color(0x356F30D5) else VrkaTokens.BorderActive.copy(alpha = 0.5f)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .shadow(if (colors.isLight) 8.dp else 12.dp, RoundedCornerShape(20.dp))
            .vrkaGlass(
                shape = RoundedCornerShape(20.dp),
                blurRadius = 16.dp,
                tintColor = stripGlassTint,
                highlightAlpha = if (colors.isLight) 0.40f else 0.28f,
                borderColor = stripBorder,
            )
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Surface(
                    shape = CircleShape,
                    color = VrkaTokens.AccentContainer,
                    border = BorderStroke(1.dp, VrkaTokens.BorderActive),
                    modifier = Modifier.size(34.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            painter = painterResource(R.drawable.ic_download),
                            contentDescription = null,
                            tint = VrkaTokens.AccentLight,
                            modifier = Modifier.size(17.dp),
                        )
                    }
                }

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = job.title.ifBlank { "Active download" },
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = VrkaTokens.TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Row(
                        modifier = Modifier.padding(top = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(
                            text = jobStatusLabel(job),
                            style = MaterialTheme.typography.labelSmall,
                            color = VrkaTokens.AccentLight,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = "•",
                            style = MaterialTheme.typography.labelSmall,
                            color = VrkaTokens.TextTertiary,
                        )
                        Text(
                            text = if (job.speed.isNotBlank()) "${job.progress.toInt()}%  (${job.speed})" else "${job.progress.toInt()}%",
                            fontFamily = VrkaMonoFamily,
                            fontSize = 11.sp,
                            color = VrkaTokens.TextSecondary,
                            maxLines = 1,
                        )
                    }
                }
            }

            if (job.state == JobState.DOWNLOADING || job.state == JobState.POSTPROCESSING) {
                LinearProgressIndicator(
                    progress = { animatedProgress },
                    strokeCap = StrokeCap.Round,
                    trackColor = VrkaTokens.SurfaceInset,
                    color = VrkaTokens.Accent,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp)
                        .height(3.dp)
                        .clip(RoundedCornerShape(2.dp)),
                )
            }
        }
    }
}


