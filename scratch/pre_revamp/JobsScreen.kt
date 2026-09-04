package com.mvrk.vrka

import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
            )
            if (onClear != null && jobs.isNotEmpty()) {
                TextButton(
                    onClick = onClear,
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
                ) {
                    Text("Clear list", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
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
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.padding(horizontal = 36.dp),
                ) {
                    Surface(
                        shape = androidx.compose.foundation.shape.CircleShape,
                        color = VrkaPurple.copy(alpha = 0.14f),
                        modifier = Modifier.size(60.dp),
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            androidx.compose.material3.Icon(
                                painter = androidx.compose.ui.res.painterResource(
                                    if (onClear == null) R.drawable.ic_queue else R.drawable.ic_history,
                                ),
                                contentDescription = null,
                                tint = VrkaPurpleLight,
                                modifier = Modifier.size(28.dp),
                            )
                        }
                    }
                    Text(
                        text = if (onClear == null) "Queue is empty" else "No download history",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = if (onClear == null)
                            "Media you enqueue will appear here while downloading."
                        else
                            "Completed and failed downloads will be recorded here.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                    bottom = 100.dp,
                ),
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
    val animatedProgress by androidx.compose.animation.core.animateFloatAsState(
        targetValue = (job.progress.coerceIn(0f, 100f) / 100f),
        label = "job_card_progress",
    )

    Surface(
        shape = androidx.compose.foundation.shape.RoundedCornerShape(18.dp),
        color = VrkaSurfaceCard,
        contentColor = MaterialTheme.colorScheme.onSurface,
        border = BorderStroke(1.dp, VrkaCardBorder),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top,
            ) {
                Text(
                    text = job.title.ifBlank { sourceLabel(job.request.url) },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                StatusBadge(job, Modifier.padding(start = 10.dp))
            }
            Text(
                text = requestSummary(job.request),
                style = MaterialTheme.typography.labelMedium,
                color = VrkaPurpleLight,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(top = 4.dp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = if (job.state == JobState.FAILED) friendlyFailureTitle(job) else job.detail,
                style = MaterialTheme.typography.bodySmall,
                color = if (job.state == JobState.FAILED) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                fontWeight = if (job.state == JobState.FAILED) FontWeight.Bold else FontWeight.Normal,
                modifier = Modifier.padding(top = 4.dp),
            )
            if (job.state == JobState.FAILED) {
                Text(
                    text = friendlyFailureDetail(job),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 3.dp),
                )
            }

            if (job.state == JobState.DOWNLOADING || job.state == JobState.POSTPROCESSING) {
                LinearProgressIndicator(
                    progress = { animatedProgress },
                    strokeCap = androidx.compose.ui.graphics.StrokeCap.Round,
                    color = VrkaPurple,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant,
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
                        color = VrkaPurpleLight,
                    )
                    Text(
                        text = listOfNotNull(
                            job.speed.takeIf(String::isNotBlank),
                            job.etaSeconds?.let(::formatEtaCompact),
                        ).joinToString(" • "),
                        fontFamily = VrkaMonoFamily,
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Text(
                text = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
                    .format(Date(job.createdAt)),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f),
                modifier = Modifier.padding(top = 8.dp),
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(top = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                when {
                    !job.state.isTerminal -> {
                        if (job.state == JobState.BROWSER_FALLBACK && onShowFallback != null) {
                            Button(
                                onClick = onShowFallback,
                                shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
                                colors = androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = VrkaPurple),
                            ) { Text("Interact with Page") }
                        }
                        OutlinedButton(
                            onClick = onCancel,
                            shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
                        ) { Text("Cancel") }
                    }
                    job.state == JobState.DONE && job.outputUris.isNotEmpty() -> {
                        Button(
                            onClick = { onOpen(job.outputUris.first()) },
                            shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
                            colors = androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = VrkaPurple),
                        ) {
                            Text("Open")
                        }
                        OutlinedButton(
                            onClick = { onShare(job.outputUris.first()) },
                            shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
                        ) {
                            Text("Share")
                        }
                        TextButton(
                            onClick = onDelete,
                            shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
                        ) { Text("Delete") }
                    }
                    else -> {
                        Button(
                            onClick = onRetry,
                            shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
                            colors = androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = VrkaPurple),
                        ) { Text("Retry") }
                        TextButton(
                            onClick = onDelete,
                            shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
                        ) { Text("Remove") }
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusBadge(job: DownloadJob, modifier: Modifier = Modifier) {
    val color = stateColor(job.state)
    Surface(
        color = color.copy(alpha = 0.14f),
        contentColor = color,
        shape = androidx.compose.foundation.shape.RoundedCornerShape(20.dp),
        border = BorderStroke(1.dp, color.copy(alpha = 0.28f)),
        modifier = modifier,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(5.dp)
                    .clip(CircleShape)
                    .background(color),
            )
            Text(
                text = jobStatusLabel(job),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun stateColor(state: JobState): Color = when (state) {
    JobState.DONE -> VrkaSuccess
    JobState.FAILED -> MaterialTheme.colorScheme.error
    JobState.CANCELLED -> MaterialTheme.colorScheme.onSurfaceVariant
    JobState.WAITING_FOR_USER, JobState.BROWSER_FALLBACK -> VrkaWarning
    else -> VrkaPurpleLight
}

private fun sourceLabel(url: String): String =
    runCatching { Uri.parse(url).host }.getOrNull().orEmpty().ifBlank { "Untitled download" }

private fun formatEta(seconds: Long): String {
    val minutes = seconds / 60
    val remainder = seconds % 60
    return if (minutes > 0) "$minutes:${remainder.toString().padStart(2, '0')} left"
    else "${remainder}s left"
}
