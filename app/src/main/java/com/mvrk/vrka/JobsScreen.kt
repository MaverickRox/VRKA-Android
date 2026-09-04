package com.mvrk.vrka

import android.net.Uri
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
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
    val isHistory = onClear != null

    Column(modifier = modifier.fillMaxSize()) {
        // Screen Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = if (isHistory) "History" else "Queue",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = VrkaTokens.TextPrimary,
            )
            if (isHistory && jobs.isNotEmpty()) {
                TextButton(
                    onClick = onClear,
                    shape = RoundedCornerShape(10.dp),
                ) {
                    Text(
                        "Clear all",
                        color = VrkaTokens.TextSecondary,
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            }
        }

        if (jobs.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 32.dp, vertical = 40.dp),
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Surface(
                        shape = CircleShape,
                        color = VrkaTokens.AccentContainer,
                        modifier = Modifier.size(64.dp),
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                painter = painterResource(
                                    if (isHistory) R.drawable.ic_history else R.drawable.ic_queue,
                                ),
                                contentDescription = null,
                                tint = VrkaTokens.AccentLight,
                                modifier = Modifier.size(28.dp),
                            )
                        }
                    }
                    Text(
                        text = if (isHistory) "No download history" else "Queue is empty",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = VrkaTokens.TextPrimary,
                    )
                    Text(
                        text = if (isHistory)
                            "Completed and archived downloads will appear here."
                        else
                            "Media you enqueue will be processed here.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = VrkaTokens.TextSecondary,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
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
    val animatedProgress by animateFloatAsState(
        targetValue = (job.progress.coerceIn(0f, 100f) / 100f),
        label = "job_card_progress",
    )

    VrkaCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
        ) {
            // Title & Status Badge
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top,
            ) {
                Text(
                    text = job.title.ifBlank { sourceLabel(job.request.url) },
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = VrkaTokens.TextPrimary,
                    modifier = Modifier.weight(1f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                VrkaStatusBadge(
                    label = jobStatusLabel(job),
                    color = stateColor(job.state),
                    modifier = Modifier.padding(start = 8.dp),
                )
            }

            // Media plan/specs
            Text(
                text = requestSummary(job.request),
                style = MaterialTheme.typography.labelSmall,
                color = VrkaTokens.AccentLight,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(top = 4.dp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )

            // Detail / Failure info
            val isFailed = job.state == JobState.FAILED
            Text(
                text = if (isFailed) friendlyFailureTitle(job) else job.detail,
                style = MaterialTheme.typography.bodySmall,
                color = if (isFailed) VrkaTokens.Error else VrkaTokens.TextSecondary,
                fontWeight = if (isFailed) FontWeight.Bold else FontWeight.Normal,
                modifier = Modifier.padding(top = 4.dp),
            )
            if (isFailed) {
                Text(
                    text = friendlyFailureDetail(job),
                    style = MaterialTheme.typography.bodySmall,
                    color = VrkaTokens.TextSecondary,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }

            // Progress bar & telemetry
            if (job.state == JobState.DOWNLOADING || job.state == JobState.POSTPROCESSING) {
                LinearProgressIndicator(
                    progress = { animatedProgress },
                    strokeCap = StrokeCap.Round,
                    color = VrkaTokens.Accent,
                    trackColor = VrkaTokens.SurfaceInset,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp)
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp)),
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 6.dp),
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
                        ).joinToString("  •  "),
                        fontFamily = VrkaMonoFamily,
                        fontSize = 11.sp,
                        color = VrkaTokens.TextTertiary,
                    )
                }
            }

            // Timestamp
            Text(
                text = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
                    .format(Date(job.createdAt)),
                style = MaterialTheme.typography.labelSmall,
                fontFamily = VrkaMonoFamily,
                fontSize = 10.sp,
                color = VrkaTokens.TextTertiary,
                modifier = Modifier.padding(top = 8.dp),
            )

            // Action Buttons Row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(top = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                when {
                    !job.state.isTerminal -> {
                        if (job.state == JobState.BROWSER_FALLBACK && onShowFallback != null) {
                            Button(
                                onClick = onShowFallback,
                                shape = RoundedCornerShape(10.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = VrkaTokens.Accent),
                                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                            ) {
                                Text("Interact with Page", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                            }
                        }
                        OutlinedButton(
                            onClick = onCancel,
                            shape = RoundedCornerShape(10.dp),
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                        ) {
                            Text("Cancel", fontSize = 12.sp, color = VrkaTokens.TextSecondary)
                        }
                    }
                    job.state == JobState.DONE && job.outputUris.isNotEmpty() -> {
                        Button(
                            onClick = { onOpen(job.outputUris.first()) },
                            shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = VrkaTokens.Accent),
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                        ) {
                            Text("Open", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        }
                        OutlinedButton(
                            onClick = { onShare(job.outputUris.first()) },
                            shape = RoundedCornerShape(10.dp),
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                        ) {
                            Text("Share", fontSize = 12.sp, color = VrkaTokens.TextSecondary)
                        }
                        TextButton(
                            onClick = onDelete,
                            shape = RoundedCornerShape(10.dp),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                        ) {
                            Text("Delete", fontSize = 12.sp, color = VrkaTokens.TextTertiary)
                        }
                    }
                    else -> {
                        Button(
                            onClick = onRetry,
                            shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = VrkaTokens.Accent),
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                        ) {
                            Text("Retry", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        }
                        TextButton(
                            onClick = onDelete,
                            shape = RoundedCornerShape(10.dp),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                        ) {
                            Text("Remove", fontSize = 12.sp, color = VrkaTokens.TextTertiary)
                        }
                    }
                }
            }
        }
    }
}

private fun stateColor(state: JobState): Color = when (state) {
    JobState.DONE -> VrkaTokens.Success
    JobState.FAILED -> VrkaTokens.Error
    JobState.CANCELLED -> VrkaTokens.TextTertiary
    JobState.WAITING_FOR_USER, JobState.BROWSER_FALLBACK -> VrkaTokens.Warning
    else -> VrkaTokens.AccentLight
}

private fun sourceLabel(url: String): String =
    runCatching { Uri.parse(url).host }.getOrNull().orEmpty().ifBlank { "Untitled download" }
