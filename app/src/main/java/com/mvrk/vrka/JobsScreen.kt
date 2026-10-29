package com.mvrk.vrka

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
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
import androidx.compose.ui.res.stringResource
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
    onDeleteRecord: ((String) -> Unit)? = null,
    onDeleteWithFile: ((String) -> Unit)? = null,
    onDeleteRecords: ((Collection<String>) -> Unit)? = null,
    onDeleteJobsWithFiles: ((Collection<String>) -> Unit)? = null,
    onClearAll: ((Boolean) -> Unit)? = null,
    onClear: (() -> Unit)? = null,
    onShowFallback: ((String) -> Unit)? = null,
    onSetDestination: ((String, String) -> Unit)? = null,
) {
    val context = LocalContext.current
    val isHistory = onClear != null || onClearAll != null
    var pendingFolderJobId by remember { mutableStateOf<String?>(null) }
    var isSelectionMode by remember { mutableStateOf(false) }
    var selectedJobIds by remember { mutableStateOf<Set<String>>(emptySet()) }

    var singleDeleteTarget by remember { mutableStateOf<DownloadJob?>(null) }
    var showMultiDeleteRecordsDialog by remember { mutableStateOf(false) }
    var showMultiDeleteFilesDialog by remember { mutableStateOf(false) }
    var showClearAllDialog by remember { mutableStateOf(false) }
    var clearAllDeleteFilesChecked by remember { mutableStateOf(false) }

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
            if (isSelectionMode) {
                Text(
                    text = stringResource(R.string.toolbar_selected_count, selectedJobIds.size),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = VrkaTokens.TextPrimary,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val allSelected = jobs.isNotEmpty() && selectedJobIds.size == jobs.size
                    VrkaTextButton(
                        text = if (allSelected) stringResource(R.string.btn_deselect_all) else stringResource(R.string.btn_select_all),
                        onClick = {
                            selectedJobIds = if (allSelected) emptySet() else jobs.map { it.id }.toSet()
                        },
                        color = VrkaTokens.AccentLight,
                    )
                    VrkaTextButton(
                        text = stringResource(R.string.btn_done),
                        onClick = {
                            isSelectionMode = false
                            selectedJobIds = emptySet()
                        },
                        color = VrkaTokens.TextSecondary,
                    )
                }
            } else {
                Text(
                    text = if (isHistory) stringResource(R.string.nav_history) else stringResource(R.string.nav_queue),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = VrkaTokens.TextPrimary,
                )
                if (isHistory && jobs.isNotEmpty()) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        VrkaTextButton(
                            text = stringResource(R.string.btn_select),
                            onClick = {
                                isSelectionMode = true
                                selectedJobIds = emptySet()
                            },
                            color = VrkaTokens.AccentLight,
                        )
                        VrkaTextButton(
                            text = stringResource(R.string.btn_clear_history),
                            onClick = { showClearAllDialog = true },
                            color = VrkaTokens.TextSecondary,
                        )
                    }
                }
            }
        }

        if (isSelectionMode && selectedJobIds.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                VrkaOutlinedButton(
                    text = "${stringResource(R.string.btn_bulk_remove_records)} (${selectedJobIds.size})",
                    onClick = { showMultiDeleteRecordsDialog = true },
                    modifier = Modifier.weight(1f),
                )
                VrkaOutlinedButton(
                    text = "${stringResource(R.string.btn_bulk_delete_files)} (${selectedJobIds.size})",
                    onClick = { showMultiDeleteFilesDialog = true },
                    modifier = Modifier.weight(1f),
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
                        text = if (!isHistory) stringResource(R.string.queue_empty_title) else stringResource(R.string.history_empty_title),
                        style = MaterialTheme.typography.titleLarge.copy(fontFamily = VrkaMonoFamily),
                        fontWeight = FontWeight.Bold,
                        color = VrkaTokens.TextPrimary,
                    )
                    Text(
                        text = if (!isHistory)
                            stringResource(R.string.queue_empty_desc)
                        else
                            stringResource(R.string.history_empty_desc),
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
                        isSelectionMode = isSelectionMode,
                        isSelected = job.id in selectedJobIds,
                        onToggleSelect = {
                            selectedJobIds = if (job.id in selectedJobIds) {
                                selectedJobIds - job.id
                            } else {
                                selectedJobIds + job.id
                            }
                        },
                        onCancel = { onCancel(job.id) },
                        onRetry = { onRetry(job.id) },
                        onOpen = onOpen,
                        onShare = onShare,
                        onDelete = { singleDeleteTarget = job },
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

    if (singleDeleteTarget != null) {
        val target = singleDeleteTarget!!
        val hasFiles = target.outputUris.isNotEmpty() && target.state == JobState.DONE
        AlertDialog(
            onDismissRequest = { singleDeleteTarget = null },
            shape = RoundedCornerShape(24.dp),
            containerColor = VrkaTokens.SurfaceCard,
            title = {
                Text(
                    text = if (hasFiles) stringResource(R.string.btn_delete_download) else stringResource(R.string.btn_remove_from_history),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = VrkaTokens.TextPrimary,
                )
            },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Text(
                        text = if (hasFiles) {
                            stringResource(R.string.dialog_delete_single_with_file_desc)
                        } else {
                            stringResource(
                                R.string.dialog_delete_single_no_file_desc,
                                target.title.ifBlank { stringResource(R.string.default_download_title) },
                            )
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = VrkaTokens.TextSecondary,
                    )

                    if (hasFiles) {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Button(
                                onClick = {
                                    val id = target.id
                                    singleDeleteTarget = null
                                    (onDeleteWithFile ?: onDelete)(id)
                                },
                                shape = RoundedCornerShape(12.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = VrkaTokens.Destructive),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(44.dp),
                            ) {
                                Text(
                                    text = stringResource(R.string.btn_delete_file_and_record),
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    softWrap = false,
                                )
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                OutlinedButton(
                                    onClick = {
                                        val id = target.id
                                        singleDeleteTarget = null
                                        (onDeleteRecord ?: onDelete)(id)
                                    },
                                    shape = RoundedCornerShape(12.dp),
                                    border = BorderStroke(1.dp, VrkaTokens.BorderSubtle),
                                    colors = ButtonDefaults.outlinedButtonColors(contentColor = VrkaTokens.TextPrimary),
                                    modifier = Modifier
                                        .weight(1.35f)
                                        .height(42.dp),
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                                ) {
                                    Text(
                                        text = stringResource(R.string.btn_remove_record_only),
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 1,
                                        softWrap = false,
                                    )
                                }

                                TextButton(
                                    onClick = { singleDeleteTarget = null },
                                    shape = RoundedCornerShape(12.dp),
                                    colors = ButtonDefaults.textButtonColors(contentColor = VrkaTokens.TextSecondary),
                                    modifier = Modifier
                                        .weight(0.65f)
                                        .height(42.dp),
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                                ) {
                                    Text(
                                        text = stringResource(R.string.btn_cancel),
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 1,
                                        softWrap = false,
                                    )
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                if (!hasFiles) {
                    Button(
                        onClick = {
                            val id = target.id
                            singleDeleteTarget = null
                            (onDeleteRecord ?: onDelete)(id)
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = VrkaTokens.Destructive),
                        shape = RoundedCornerShape(12.dp),
                    ) {
                        Text(stringResource(R.string.action_remove))
                    }
                }
            },
            dismissButton = {
                if (!hasFiles) {
                    TextButton(
                        onClick = { singleDeleteTarget = null },
                        shape = RoundedCornerShape(12.dp),
                    ) {
                        Text(stringResource(R.string.btn_cancel))
                    }
                }
            },
        )
    }

    if (showMultiDeleteRecordsDialog) {
        AlertDialog(
            onDismissRequest = { showMultiDeleteRecordsDialog = false },
            title = {
                Text(
                    text = stringResource(R.string.dialog_bulk_remove_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
            },
            text = {
                Text(
                    text = stringResource(R.string.dialog_bulk_remove_desc, selectedJobIds.size),
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        val ids = selectedJobIds.toList()
                        showMultiDeleteRecordsDialog = false
                        selectedJobIds = emptySet()
                        isSelectionMode = false
                        (onDeleteRecords ?: { for (id in ids) onDelete(id) })(ids)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = VrkaTokens.Destructive),
                ) {
                    Text(stringResource(R.string.btn_bulk_remove_records))
                }
            },
            dismissButton = {
                TextButton(onClick = { showMultiDeleteRecordsDialog = false }) {
                    Text(stringResource(R.string.btn_cancel))
                }
            },
        )
    }

    if (showMultiDeleteFilesDialog) {
        AlertDialog(
            onDismissRequest = { showMultiDeleteFilesDialog = false },
            title = {
                Text(
                    text = stringResource(R.string.dialog_bulk_delete_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
            },
            text = {
                Text(
                    text = stringResource(R.string.dialog_bulk_delete_desc, selectedJobIds.size),
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        val ids = selectedJobIds.toList()
                        showMultiDeleteFilesDialog = false
                        selectedJobIds = emptySet()
                        isSelectionMode = false
                        (onDeleteJobsWithFiles ?: onDeleteRecords ?: { for (id in ids) onDelete(id) })(ids)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = VrkaTokens.Destructive),
                ) {
                    Text(stringResource(R.string.btn_bulk_delete_files))
                }
            },
            dismissButton = {
                TextButton(onClick = { showMultiDeleteFilesDialog = false }) {
                    Text(stringResource(R.string.btn_cancel))
                }
            },
        )
    }

    if (showClearAllDialog) {
        AlertDialog(
            onDismissRequest = { showClearAllDialog = false },
            title = {
                Text(
                    text = stringResource(R.string.dialog_clear_all_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        text = stringResource(R.string.dialog_clear_all_desc, jobs.size),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { clearAllDeleteFilesChecked = !clearAllDeleteFilesChecked },
                    ) {
                        Checkbox(
                            checked = clearAllDeleteFilesChecked,
                            onCheckedChange = { clearAllDeleteFilesChecked = it },
                            colors = CheckboxDefaults.colors(checkedColor = VrkaTokens.Destructive),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = stringResource(R.string.opt_clear_all_delete_files),
                            style = MaterialTheme.typography.bodySmall,
                            color = if (clearAllDeleteFilesChecked) VrkaTokens.Destructive else VrkaTokens.TextPrimary,
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val deleteFiles = clearAllDeleteFilesChecked
                        showClearAllDialog = false
                        clearAllDeleteFilesChecked = false
                        if (onClearAll != null) {
                            onClearAll(deleteFiles)
                        } else {
                            onClear?.invoke()
                        }
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (clearAllDeleteFilesChecked) VrkaTokens.Destructive else VrkaTokens.Accent,
                    ),
                ) {
                    Text(if (clearAllDeleteFilesChecked) stringResource(R.string.btn_delete_files_and_history) else stringResource(R.string.btn_clear_history))
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearAllDialog = false }) {
                    Text(stringResource(R.string.btn_cancel))
                }
            },
        )
    }
}

@Composable
private fun JobCard(
    job: DownloadJob,
    isSelectionMode: Boolean = false,
    isSelected: Boolean = false,
    onToggleSelect: (() -> Unit)? = null,
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
        color = if (isSelected) VrkaTokens.SurfaceInset else VrkaTokens.SurfaceCard,
        border = BorderStroke(1.dp, if (isSelected) VrkaTokens.BorderActive else VrkaTokens.BorderSubtle),
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (isSelectionMode && onToggleSelect != null) {
                    Modifier.clickable(onClick = onToggleSelect)
                } else Modifier
            ),
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.Top,
        ) {
            if (isSelectionMode && onToggleSelect != null) {
                Checkbox(
                    checked = isSelected,
                    onCheckedChange = { onToggleSelect() },
                    colors = CheckboxDefaults.colors(checkedColor = VrkaTokens.Accent),
                    modifier = Modifier.padding(end = 8.dp),
                )
            }
            Column(modifier = Modifier.weight(1f)) {
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
                            text = jobStatusLabel(LocalContext.current, job),
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
                    text = jobMediaSummary(LocalContext.current, job),
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
                        text = friendlyFailureTitle(LocalContext.current, job),
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = VrkaMonoFamily),
                        color = VrkaTokens.Error,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                    Text(
                        text = friendlyFailureDetail(LocalContext.current, job),
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

                if (!isSelectionMode) {
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
                                            text = stringResource(R.string.action_set_location),
                                            onClick = onChooseFolder,
                                            height = 32.dp,
                                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                                        )
                                    }
                                    if (job.state == JobState.BROWSER_FALLBACK && onShowFallback != null) {
                                        VrkaOutlinedButton(
                                            text = stringResource(R.string.action_interact),
                                            onClick = onShowFallback,
                                            height = 32.dp,
                                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                                        )
                                    }
                                    VrkaOutlinedButton(
                                        text = stringResource(R.string.btn_cancel),
                                        onClick = onCancel,
                                        height = 32.dp,
                                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                                    )
                                }
                                job.state == JobState.DONE && job.outputUris.isNotEmpty() -> {
                                    VrkaOutlinedButton(
                                        text = stringResource(R.string.action_open),
                                        onClick = { onOpen(job.outputUris.first()) },
                                        height = 32.dp,
                                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                                    )
                                    VrkaOutlinedButton(
                                        text = stringResource(R.string.action_share),
                                        onClick = { onShare(job.outputUris.first()) },
                                        height = 32.dp,
                                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                                    )
                                    VrkaTextButton(
                                        text = stringResource(R.string.action_delete),
                                        onClick = onDelete,
                                        color = VrkaTokens.Destructive,
                                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp),
                                    )
                                }
                                else -> {
                                    VrkaOutlinedButton(
                                        text = stringResource(R.string.action_retry),
                                        onClick = onRetry,
                                        height = 32.dp,
                                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                                    )
                                    VrkaTextButton(
                                        text = stringResource(R.string.action_remove),
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

@Composable
private fun sourceLabel(url: String): String =
    runCatching { Uri.parse(url).host }.getOrNull().orEmpty().ifBlank { stringResource(R.string.default_download_title) }
