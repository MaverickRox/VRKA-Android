package com.mvrk.vrka

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
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
    onSetDestination: ((String, String) -> Unit)? = null,
) {
    val context = LocalContext.current
    var pendingFolderJobId by remember { mutableStateOf<String?>(null) }
    val folderPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        val id = pendingFolderJobId
        if (uri != null && id != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            }
            onSetDestination?.invoke(id, uri.toString())
        }
        pendingFolderJobId = null
    }

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
                    .padding(horizontal = 24.dp)
                    .padding(bottom = 72.dp),
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    androidx.compose.foundation.Image(
                        painter = androidx.compose.ui.res.painterResource(R.drawable.vrka_logo_512),
                        contentDescription = "VRKA",
                        contentScale = androidx.compose.ui.layout.ContentScale.Fit,
                        modifier = Modifier.size(68.dp),
                    )
                    Text(
                        text = if (onClear == null) "Queue is empty" else "No download history",
                        style = MaterialTheme.typography.titleLarge.copy(fontFamily = VrkaMonoFamily),
                        fontWeight = FontWeight.Bold,
                        color = VrkaTokens.TextPrimary,
                    )
                    Text(
                        text = if (onClear == null)
                            "Media you enqueue will appear here while downloading."
                        else
                            "Completed and archived downloads will be listed here.",
                        style = MaterialTheme.typography.bodyMedium.copy(fontFamily = VrkaMonoFamily),
                        color = VrkaTokens.TextSecondary,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        lineHeight = 20.sp,
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    start = 16.dp,
                    end = 16.dp,
                    top = 8.dp,
                    bottom = 110.dp,
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
                        onChooseFolder = {
                            pendingFolderJobId = job.id
                            folderPicker.launch(null)
                        },
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
    onChooseFolder: (() -> Unit)? = null,
) {
    val animatedProgress by androidx.compose.animation.core.animateFloatAsState(
        targetValue = (job.progress.coerceIn(0f, 100f) / 100f),
        label = "job_card_progress",
    )

    Surface(
        shape = RoundedCornerShape(14.dp),
        color = VrkaTokens.SurfaceCard,
        border = BorderStroke(1.dp, VrkaTokens.BorderSubtle),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = job.title.ifBlank { sourceLabel(job.request.url) },
                    style = MaterialTheme.typography.titleSmall.copy(
                        fontFamily = VrkaMonoFamily,
                        fontWeight = FontWeight.Bold,
                    ),
                    color = VrkaTokens.TextPrimary,
                    modifier = Modifier.weight(1f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                    modifier = Modifier.padding(start = 10.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .size(6.dp)
                            .clip(CircleShape)
                            .background(stateColor(job.state)),
                    )
                    Text(
                        text = jobStatusLabel(job),
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontFamily = VrkaMonoFamily,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                        ),
                        color = stateColor(job.state),
                        maxLines = 1,
                    )
                }
            }

            Text(
                text = requestSummary(job.request),
                style = MaterialTheme.typography.bodySmall.copy(
                    fontFamily = VrkaMonoFamily,
                    fontSize = 11.sp,
                ),
                color = VrkaTokens.AccentLight,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(top = 4.dp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )

            if (job.state == JobState.FAILED) {
                Text(
                    text = friendlyFailureTitle(job),
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = VrkaMonoFamily),
                    color = VrkaTokens.Error,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 4.dp),
                )
                Text(
                    text = friendlyFailureDetail(job),
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = VrkaMonoFamily),
                    color = VrkaTokens.TextSecondary,
                    modifier = Modifier.padding(top = 2.dp),
                )
            } else if (job.detail.isNotBlank()) {
                Text(
                    text = job.detail,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = VrkaMonoFamily),
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

            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
                        .format(Date(job.createdAt)),
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontFamily = VrkaMonoFamily,
                        fontSize = 10.sp,
                    ),
                    color = VrkaTokens.TextTertiary,
                )

                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    when {
                        !job.state.isTerminal -> {
                            if (job.state == JobState.WAITING_FOR_USER && onChooseFolder != null) {
                                VrkaOutlinedButton(
                                    text = "Choose Folder",
                                    onClick = onChooseFolder,
                                    height = 32.dp,
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                                )
                            }
                            if (job.state == JobState.BROWSER_FALLBACK && onShowFallback != null) {
                                VrkaOutlinedButton(
                                    text = "Interact",
                                    onClick = onShowFallback,
                                    height = 32.dp,
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                                )
                            }
                            VrkaOutlinedButton(
                                text = "Cancel",
                                onClick = onCancel,
                                height = 32.dp,
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                            )
                        }
                        job.state == JobState.DONE && job.outputUris.isNotEmpty() -> {
                            VrkaOutlinedButton(
                                text = "Open",
                                onClick = { onOpen(job.outputUris.first()) },
                                height = 32.dp,
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                            )
                            VrkaOutlinedButton(
                                text = "Share",
                                onClick = { onShare(job.outputUris.first()) },
                                height = 32.dp,
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                            )
                            VrkaTextButton(
                                text = "Delete",
                                onClick = onDelete,
                                color = VrkaTokens.Destructive,
                                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp),
                            )
                        }
                        else -> {
                            VrkaOutlinedButton(
                                text = "Retry",
                                onClick = onRetry,
                                height = 32.dp,
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                            )
                            VrkaTextButton(
                                text = "Remove",
                                onClick = onDelete,
                                color = VrkaTokens.Destructive,
                                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp),
                            )
                        }
                    }
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

