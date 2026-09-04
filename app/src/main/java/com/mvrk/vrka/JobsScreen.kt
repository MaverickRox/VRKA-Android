package com.mvrk.vrka

import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.DateFormat
import java.util.Date

@Composable
internal fun JobsScreen(
    jobs: List<DownloadJob>,
    emptyMessage: String,
    modifier: Modifier = Modifier,
    onCancel: (String) -> Unit,
    onRetry: (String) -> Unit,
    onOpen: (String) -> Unit,
    onShare: (String) -> Unit,
    onDelete: (String) -> Unit,
    onClear: (() -> Unit)? = null,
    onShowFallback: ((String) -> Unit)? = null,
) {
    Column(modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = if (onClear == null) "Queue" else "History",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = VrkaTokens.TextPrimary,
            )
            if (onClear != null && jobs.isNotEmpty()) {
                VrkaTextButton(
                    text = "Clear history",
                    onClick = onClear,
                    color = VrkaTokens.TextSecondary,
                )
            }
        }
        if (jobs.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(bottom = 60.dp),
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.padding(horizontal = 36.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .size(64.dp)
                            .clip(CircleShape)
                            .background(VrkaTokens.AccentContainer)
                            .border(1.dp, VrkaTokens.BorderActive, CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        androidx.compose.material3.Icon(
                            painter = androidx.compose.ui.res.painterResource(
                                if (onClear == null) R.drawable.ic_queue else R.drawable.ic_history,
                            ),
                            contentDescription = null,
                            tint = VrkaTokens.AccentLight,
                            modifier = Modifier.size(28.dp),
                        )
                    }
                    Text(
                        text = if (onClear == null) "Queue is empty" else "No download history",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = VrkaTokens.TextPrimary,
                    )
                    Text(
                        text = if (onClear == null)
                            "Media you enqueue will appear here while downloading."
                        else
                            "Completed and failed downloads will be recorded here.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = VrkaTokens.TextSecondary,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    start = 16.dp,
                    end = 16.dp,
                    bottom = 16.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(jobs, key = DownloadJob::id) { job ->
                    JobCard(
                        job = job,
                        onCancel = { onCancel(job.id) },
                        onRetry = { onRetry(job.id) },
                        onOpen = onOpen,
                        onShare = onShare,
                        onDelete = { onDelete(job.id) },
                        onShowFallback = onShowFallback?.let { fn -> { fn(job.id) } },
                    )
                }
            }
        }
    }
}

@Composable
private fun JobCard(
    job: DownloadJob,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
    onOpen: (String) -> Unit,
    onShare: (String) -> Unit,
    onDelete: () -> Unit,
    onShowFallback: (() -> Unit)? = null,
) {
    val animatedProgress by androidx.compose.animation.core.animateFloatAsState(
        targetValue = (job.progress.coerceIn(0f, 100f) / 100f),
        label = "job_card_progress",
    )

    VrkaCard(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = 16.dp,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top,
        ) {
            Text(
                text = job.title.ifBlank { sourceLabel(job.request.url) },
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = VrkaTokens.TextPrimary,
                modifier = Modifier.weight(1f),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            VrkaStatusBadge(
                label = jobStatusLabel(job),
                color = stateColor(job.state),
                modifier = Modifier.padding(start = 10.dp),
            )
        }

        Text(
            text = requestSummary(job.request),
            style = MaterialTheme.typography.labelMedium.copy(fontFamily = VrkaMonoFamily),
            color = VrkaTokens.AccentLight,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(top = 4.dp),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )

        if (job.state == JobState.FAILED) {
            Text(
                text = friendlyFailureTitle(job),
                style = MaterialTheme.typography.bodySmall,
                color = VrkaTokens.Error,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(top = 4.dp),
            )
            Text(
                text = friendlyFailureDetail(job),
                style = MaterialTheme.typography.bodySmall,
                color = VrkaTokens.TextSecondary,
                modifier = Modifier.padding(top = 2.dp),
            )
        } else if (job.detail.isNotBlank()) {
            Text(
                text = job.detail,
                style = MaterialTheme.typography.bodySmall,
                color = VrkaTokens.TextSecondary,
                modifier = Modifier.padding(top = 4.dp),
            )
        }

        if (job.state == JobState.DOWNLOADING || job.state == JobState.POSTPROCESSING) {
            LinearProgressIndicator(
                progress = { animatedProgress },
                strokeCap = androidx.compose.ui.graphics.StrokeCap.Round,
                color = VrkaTokens.Accent,
                trackColor = VrkaTokens.SurfaceInset,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp)
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp)),
            )
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "${job.progress.toInt()}%",
                    fontFamily = VrkaMonoFamily,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = VrkaTokens.AccentLight,
                )
                Text(
                    text = listOfNotNull(
                        job.speed.takeIf(String::isNotBlank),
                        job.etaSeconds?.let(::formatEtaCompact),
                    ).joinToString(" • "),
                    fontFamily = VrkaMonoFamily,
                    fontSize = 11.sp,
                    color = VrkaTokens.TextSecondary,
                )
            }
        }

        Text(
            text = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
                .format(Date(job.createdAt)),
            style = MaterialTheme.typography.labelSmall,
            color = VrkaTokens.TextTertiary,
            modifier = Modifier.padding(top = 8.dp),
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(top = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            when {
                !job.state.isTerminal -> {
                    if (job.state == JobState.BROWSER_FALLBACK && onShowFallback != null) {
                        VrkaOutlinedButton(
                            text = "Interact with Page",
                            onClick = onShowFallback,
                        )
                    }
                    VrkaOutlinedButton(
                        text = "Cancel",
                        onClick = onCancel,
                    )
                }
                job.state == JobState.DONE && job.outputUris.isNotEmpty() -> {
                    VrkaOutlinedButton(
                        text = "Open",
                        onClick = { onOpen(job.outputUris.first()) },
                    )
                    VrkaOutlinedButton(
                        text = "Share",
                        onClick = { onShare(job.outputUris.first()) },
                    )
                    VrkaTextButton(
                        text = "Delete",
                        onClick = onDelete,
                        color = VrkaTokens.TextTertiary,
                    )
                }
                else -> {
                    VrkaOutlinedButton(
                        text = "Retry",
                        onClick = onRetry,
                    )
                    VrkaTextButton(
                        text = "Remove",
                        onClick = onDelete,
                        color = VrkaTokens.TextTertiary,
                    )
                }
            }
        }
    }
}

@Composable
private fun stateColor(state: JobState): Color = when (state) {
    JobState.DONE -> VrkaTokens.Success
    JobState.FAILED -> VrkaTokens.Error
    JobState.CANCELLED -> VrkaTokens.TextTertiary
    JobState.WAITING_FOR_USER, JobState.BROWSER_FALLBACK -> VrkaTokens.Warning
    else -> VrkaTokens.AccentLight
}

private fun sourceLabel(url: String): String =
    runCatching { Uri.parse(url).host }.getOrNull().orEmpty().ifBlank { "Untitled download" }

