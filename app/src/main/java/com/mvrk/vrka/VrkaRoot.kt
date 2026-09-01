package com.mvrk.vrka

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.annotation.DrawableRes
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

private enum class Destination(val label: String, @DrawableRes val iconRes: Int) {
    HOME("Download", R.drawable.ic_download),
    QUEUE("Queue", R.drawable.ic_queue),
    HISTORY("History", R.drawable.ic_history),
    SETTINGS("Settings", R.drawable.ic_settings),
}

@Composable
fun VrkaRoot(
    manager: VrkaDownloadManager,
    openQueueRequests: StateFlow<Long>,
) {
    val context = LocalContext.current
    val jobs by manager.jobs.collectAsStateWithLifecycle()
    val openQueueToken by openQueueRequests.collectAsStateWithLifecycle()
    val browserJobId by manager.browserJobId.collectAsStateWithLifecycle()
    val settings by manager.settingsRepository.settings.collectAsStateWithLifecycle()
    val runtime by manager.runtime.collectAsStateWithLifecycle()
    var destination by remember { mutableStateOf(Destination.HOME) }
    var pendingRequest by remember { mutableStateOf<DownloadRequest?>(null) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    val notificationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) {
        pendingRequest?.let(manager::enqueue)
        pendingRequest = null
    }

    fun enqueue(request: DownloadRequest) {
        val needsPermission = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) != PackageManager.PERMISSION_GRANTED
        if (needsPermission) {
            pendingRequest = request
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            manager.enqueue(request)
        }
        destination = Destination.QUEUE
    }

    val browserJob = browserJobId
        ?.let { id -> jobs.firstOrNull { it.id == id } }
        ?.takeIf {
            it.state == JobState.WAITING_FOR_USER || it.state == JobState.BROWSER_FALLBACK
        }
    val activeJob = jobs.firstOrNull { !it.state.isTerminal }
    VrkaTheme(themeMode = settings.themeMode, amoled = settings.amoled) {
        if (browserJob != null) {
            BrowserFallbackScreen(
                job = browserJob,
                adBlocking = settings.adBlocking,
                onHandoff = { manager.acceptBrowserHandoff(browserJob.id, it) },
                onClose = { manager.closeBrowser(browserJob.id) },
            )
            return@VrkaTheme
        }

        Scaffold(
            snackbarHost = { SnackbarHost(snackbar) },
            bottomBar = {
                Column {
                    if (activeJob != null) {
                        ActiveDownloadStrip(
                            job = activeJob,
                            onClick = { destination = Destination.QUEUE },
                        )
                    }
                    NavigationBar(
                        containerColor = MaterialTheme.colorScheme.surface,
                        tonalElevation = 0.dp,
                    ) {
                        Destination.entries.forEach { item ->
                            NavigationBarItem(
                                selected = destination == item,
                                onClick = { destination = item },
                                icon = {
                                    Icon(
                                        painter = painterResource(item.iconRes),
                                        contentDescription = item.label,
                                    )
                                },
                                label = { Text(item.label) },
                                colors = NavigationBarItemDefaults.colors(
                                    selectedIconColor = MaterialTheme.colorScheme.primary,
                                    selectedTextColor = MaterialTheme.colorScheme.primary,
                                    indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                                ),
                            )
                        }
                    }
                }
            },
        ) { padding ->
            when (destination) {
                Destination.HOME -> HomeScreen(
                    settings = settings,
                    runtime = runtime,
                    modifier = Modifier.padding(padding),
                    onEnqueue = { request ->
                        runCatching { enqueue(request) }.onFailure { error ->
                            pendingRequest = null
                            val message = error.message ?: "Could not add download"
                            scope.launch { snackbar.showSnackbar(message) }
                        }
                    },
                )
                Destination.QUEUE -> JobsScreen(
                    jobs = jobs.filterNot { it.state.isTerminal },
                    emptyMessage = "Your active queue is empty.",
                    modifier = Modifier.padding(padding),
                    onCancel = manager::cancel,
                    onRetry = manager::retry,
                    onOpen = manager::openOutput,
                    onShare = manager::shareOutput,
                    onDelete = manager::deleteJob,
                )
                Destination.HISTORY -> JobsScreen(
                    jobs = jobs.filter { it.state.isTerminal },
                    emptyMessage = "Completed and failed downloads appear here.",
                    modifier = Modifier.padding(padding),
                    onCancel = manager::cancel,
                    onRetry = manager::retry,
                    onOpen = manager::openOutput,
                    onShare = manager::shareOutput,
                    onDelete = manager::deleteJob,
                    onClear = manager::clearFinished,
                )
                Destination.SETTINGS -> SettingsScreen(
                    settings = settings,
                    runtime = runtime,
                    repository = manager.settingsRepository,
                    onUpdateRuntime = manager::updateRuntime,
                    modifier = Modifier.padding(padding),
                )
            }
        }
    }

    LaunchedEffect(browserJobId) {
        if (browserJobId != null) destination = Destination.QUEUE
    }
}
