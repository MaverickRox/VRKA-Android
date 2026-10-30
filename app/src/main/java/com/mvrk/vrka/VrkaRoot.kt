package com.mvrk.vrka

import androidx.compose.ui.res.stringResource

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.DrawableRes
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.graphicsLayer
import com.mvrk.vrka.ui.backdrop.backdrops.layerBackdrop
import com.mvrk.vrka.ui.backdrop.backdrops.rememberLayerBackdrop
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mvrk.vrka.ui.AppUpdateDialog
import com.mvrk.vrka.update.AppUpdateCheckState
import com.mvrk.vrka.update.AppReleaseInfo
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

@Composable
fun VrkaRoot(
    manager: VrkaDownloadManager,
    openQueueRequests: StateFlow<Long>,
) {
    val context = LocalContext.current
    val jobs by manager.jobs.collectAsStateWithLifecycle()
    val openQueueToken by openQueueRequests.collectAsStateWithLifecycle()
    val settings by manager.settingsRepository.settings.collectAsStateWithLifecycle()
    val runtime by manager.runtime.collectAsStateWithLifecycle()
    val diagnostics by manager.diagnostics.collectAsStateWithLifecycle()
    val appUpdateCheckState by manager.appUpdateManager.checkState.collectAsStateWithLifecycle()
    val appUpdateDownloadState by manager.appUpdateManager.downloadState.collectAsStateWithLifecycle()
    val startupUpdateDialogData by manager.componentUpdateManager.startupUpdateDialogData.collectAsStateWithLifecycle()
    val destinations = remember { VrkaDestination.entries }
    val pagerState = rememberPagerState(
        initialPage = 0,
        pageCount = { destinations.size },
    )
    val destination = destinations[pagerState.currentPage]
    var pendingRequest by remember { mutableStateOf<DownloadRequest?>(null) }
    var pendingAppUpdate by remember { mutableStateOf<AppReleaseInfo?>(null) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        manager.appUpdateManager.checkForUpdate(isManual = false)
        manager.componentUpdateManager.performStartupCheckIfNeeded(settings.updatePreference)
    }

    val notificationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) {
        pendingRequest?.let(manager::enqueue)
        pendingRequest = null
        pendingAppUpdate?.let(manager.appUpdateManager::downloadAndInstall)
        pendingAppUpdate = null
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
        scope.launch {
            pagerState.animateScrollToPage(destinations.indexOf(VrkaDestination.QUEUE))
        }
    }

    val enqueueFailedMessage = stringResource(R.string.snackbar_enqueue_failed)

    fun downloadAppUpdate(release: AppReleaseInfo) {
        val needsPermission = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) != PackageManager.PERMISSION_GRANTED
        if (needsPermission) {
            pendingAppUpdate = release
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            manager.appUpdateManager.downloadAndInstall(release)
        }
    }

    val activeJob = jobs.firstOrNull { !it.state.isTerminal }

    androidx.compose.runtime.SideEffect {
        android.util.Log.d("VrkaNavPerf", "VrkaRoot destination committed: ${destination.name}")
    }

    VrkaTheme(
        themeMode = settings.themeMode,
        amoled = settings.amoled,
        fontPreference = settings.fontPreference,
    ) {
        val backgroundColor = MaterialTheme.colorScheme.background
        val screenBackdrop = rememberLayerBackdrop(
            onDraw = remember(backgroundColor) {
                {
                    drawRect(backgroundColor)
                    drawContent()
                }
            },
        )
        LaunchedEffect(destination) {
            screenBackdrop.forceInvalidate()
        }
        CompositionLocalProvider(LocalAppBackdrop provides screenBackdrop) {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = MaterialTheme.colorScheme.background,
                contentColor = MaterialTheme.colorScheme.onBackground,
            ) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                ) {
                    // Memoized job lists: Prevents allocating new ArrayLists on destination changes
                    val activeJobs = remember(jobs) { jobs.filterNot { it.state.isTerminal } }
                    val historyJobs = remember(jobs) { jobs.filter { it.state.isTerminal } }

                    val handleEnqueue = remember(scope, snackbar) {
                        { request: DownloadRequest ->
                            runCatching { enqueue(request) }.onFailure { error ->
                                pendingRequest = null
                                val message = error.message ?: "Could not add download"
                                scope.launch { snackbar.showSnackbar(message) }
                            }
                            Unit
                        }
                    }

                    val scrollBackdropConnection = remember(screenBackdrop) {
                        object : androidx.compose.ui.input.nestedscroll.NestedScrollConnection {
                            override fun onPreScroll(
                                available: androidx.compose.ui.geometry.Offset,
                                source: androidx.compose.ui.input.nestedscroll.NestedScrollSource,
                            ): androidx.compose.ui.geometry.Offset {
                                screenBackdrop.invalidate()
                                return androidx.compose.ui.geometry.Offset.Zero
                            }

                            override fun onPostScroll(
                                consumed: androidx.compose.ui.geometry.Offset,
                                available: androidx.compose.ui.geometry.Offset,
                                source: androidx.compose.ui.input.nestedscroll.NestedScrollSource,
                            ): androidx.compose.ui.geometry.Offset {
                                screenBackdrop.invalidate()
                                return androidx.compose.ui.geometry.Offset.Zero
                            }

                            override suspend fun onPreFling(
                                available: androidx.compose.ui.unit.Velocity,
                            ): androidx.compose.ui.unit.Velocity {
                                screenBackdrop.forceInvalidate()
                                return androidx.compose.ui.unit.Velocity.Zero
                            }

                            override suspend fun onPostFling(
                                consumed: androidx.compose.ui.unit.Velocity,
                                available: androidx.compose.ui.unit.Velocity,
                            ): androidx.compose.ui.unit.Velocity {
                                screenBackdrop.forceInvalidate()
                                return androidx.compose.ui.unit.Velocity.Zero
                            }
                        }
                    }

                    // Responsive swipeable pager: synchronized with bottom nav and indicator puck
                    HorizontalPager(
                        state = pagerState,
                        modifier = Modifier
                            .fillMaxSize()
                            .nestedScroll(scrollBackdropConnection)
                            .layerBackdrop(screenBackdrop),
                        beyondViewportPageCount = 1,
                    ) { page ->
                        when (destinations[page]) {
                            VrkaDestination.DOWNLOAD -> {
                                HomeScreen(
                                    settings = settings,
                                    repository = manager.settingsRepository,
                                    runtime = runtime,
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .statusBarsPadding(),
                                    onEnqueue = handleEnqueue,
                                    onEstimateSize = { request -> manager.estimateSize(request) },
                                    onUpdateDownloadLocation = { uri, mode, configured ->
                                        scope.launch {
                                            manager.settingsRepository.setDownloadLocation(uri, mode, configured)
                                        }
                                    },
                                )
                            }
                            VrkaDestination.QUEUE -> {
                                JobsScreen(
                                    jobs = activeJobs,
                                    emptyMessage = stringResource(R.string.queue_empty_message),
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .statusBarsPadding(),
                                    onCancel = manager::cancel,
                                    onRetry = manager::retry,
                                    onOpen = manager::openOutput,
                                    onShare = manager::shareOutput,
                                    onDelete = manager::deleteJob,
                                    onShowFallback = manager::showFallbackView,
                                    onSetDestination = manager::setJobDestination,
                                )
                            }
                            VrkaDestination.HISTORY -> {
                                JobsScreen(
                                    jobs = historyJobs,
                                    emptyMessage = stringResource(R.string.history_empty_message),
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .statusBarsPadding(),
                                    onCancel = manager::cancel,
                                    onRetry = manager::retry,
                                    onOpen = manager::openOutput,
                                    onShare = manager::shareOutput,
                                    onDelete = manager::deleteJob,
                                    onDeleteRecord = manager::deleteHistoryRecord,
                                    onDeleteWithFile = { scope.launch { manager.deleteJobWithFiles(it) } },
                                    onDeleteRecords = manager::deleteHistoryRecords,
                                    onDeleteJobsWithFiles = { scope.launch { manager.deleteJobsWithFiles(it) } },
                                    onClearAll = { deleteFiles -> scope.launch { manager.clearAllHistory(deleteFiles) } },
                                    onClear = manager::clearFinished,
                                )
                            }
                            VrkaDestination.SETTINGS -> {
                                SettingsScreen(
                                    settings = settings,
                                    runtime = runtime,
                                    repository = manager.settingsRepository,
                                    onUpdateRuntime = manager::updateRuntime,
                                    runtimeManager = manager.ytDlpRuntimeManager,
                                    diagnostics = diagnostics,
                                    onClearDiagnostics = manager::clearDiagnostics,
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .statusBarsPadding(),
                                )
                            }
                        }
                    }

                SnackbarHost(
                    hostState = snackbar,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 96.dp),
                )

                Column(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(bottom = 12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    AnimatedVisibility(
                        visible = activeJob != null && destination != VrkaDestination.QUEUE,
                        enter = slideInVertically(
                            initialOffsetY = { it },
                            animationSpec = tween(220, easing = FastOutSlowInEasing),
                        ) + fadeIn(animationSpec = tween(180)),
                        exit = slideOutVertically(
                            targetOffsetY = { it },
                            animationSpec = tween(180, easing = FastOutSlowInEasing),
                        ) + fadeOut(animationSpec = tween(140)),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 24.dp, vertical = 6.dp),
                    ) {
                        if (activeJob != null) {
                            ActiveDownloadStrip(
                                job = activeJob,
                                onClick = {
                                    scope.launch {
                                        pagerState.animateScrollToPage(destinations.indexOf(VrkaDestination.QUEUE))
                                    }
                                },
                            )
                        }
                    }

                    VrkaFloatingNavBar(
                        selectedDestination = destination,
                        pagerPosition = pagerState.currentPage + pagerState.currentPageOffsetFraction,
                        onDestinationSelected = { dest ->
                            scope.launch {
                                pagerState.animateScrollToPage(destinations.indexOf(dest))
                            }
                        },
                    )
                }

                val activeFallback by manager.activeFallback.collectAsStateWithLifecycle()
                if (activeFallback != null && activeFallback!!.isVisible) {
                    FallbackInteractionOverlay(
                        fallbackState = activeFallback!!,
                        onDismiss = manager::dismissFallbackView,
                        onCancel = { manager.cancel(activeFallback!!.jobId) },
                    )
                }

                if (appUpdateCheckState is AppUpdateCheckState.UpdateAvailable) {
                    val release = (appUpdateCheckState as AppUpdateCheckState.UpdateAvailable).release
                    AppUpdateDialog(
                        release = release,
                        downloadState = appUpdateDownloadState,
                        onDownloadClick = { downloadAppUpdate(release) },
                        onDismissClick = manager.appUpdateManager::dismissUpdate,
                        onInstallClick = {
                            if (appUpdateDownloadState is com.mvrk.vrka.update.AppUpdateDownloadState.ReadyToInstall) {
                                manager.appUpdateManager.installApk((appUpdateDownloadState as com.mvrk.vrka.update.AppUpdateDownloadState.ReadyToInstall).file)
                            } else {
                                downloadAppUpdate(release)
                            }
                        },
                    )
                }

                startupUpdateDialogData?.let { dialogData ->
                    com.mvrk.vrka.ui.ComponentStartupUpdateDialog(
                        dialogData = dialogData,
                        onUpdateNow = { manager.componentUpdateManager.applyAllUpdates(settings.updatePreference) },
                        onDismiss = manager.componentUpdateManager::dismissStartupDialog,
                    )
                }

            }
        }
    }
    }

    LaunchedEffect(openQueueToken) {
        if (openQueueToken > 0L) {
            pagerState.animateScrollToPage(destinations.indexOf(VrkaDestination.QUEUE))
        }
    }
}

@Composable
private fun FallbackInteractionOverlay(
    fallbackState: ActiveFallbackState,
    onDismiss: () -> Unit,
    onCancel: () -> Unit,
) {
    val session by fallbackState.engine.activeSession.collectAsStateWithLifecycle()
    val candidateCount by fallbackState.engine.candidateCount.collectAsStateWithLifecycle()

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Surface(
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 4.dp,
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Row(
                            modifier = Modifier.weight(1f, fill = false),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Surface(
                                shape = androidx.compose.foundation.shape.RoundedCornerShape(6.dp),
                                color = VrkaWarning.copy(alpha = 0.2f),
                            ) {
                                Text(
                                    stringResource(R.string.fallback_badge),
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                                    color = VrkaWarning,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp),
                                )
                            }
                            Text(
                                text = fallbackState.job.title.ifBlank {
                                    runCatching { Uri.parse(fallbackState.engine.targetUrl).host }.getOrNull().orEmpty()
                                },
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                                maxLines = 1,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                            )
                        }

                        Spacer(Modifier.width(8.dp))

                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            OutlinedButton(
                                onClick = onDismiss,
                                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                                    horizontal = 12.dp,
                                    vertical = 4.dp,
                                ),
                                shape = androidx.compose.foundation.shape.RoundedCornerShape(10.dp),
                                modifier = Modifier.height(34.dp),
                            ) {
                                Text(stringResource(R.string.fallback_minimize), style = MaterialTheme.typography.labelMedium)
                            }
                            Button(
                                onClick = onCancel,
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                                    horizontal = 12.dp,
                                    vertical = 4.dp,
                                ),
                                shape = androidx.compose.foundation.shape.RoundedCornerShape(10.dp),
                                modifier = Modifier.height(34.dp),
                            ) {
                                Text(stringResource(R.string.fallback_cancel), style = MaterialTheme.typography.labelMedium)
                            }
                        }
                    }

                    val statusMessage = when {
                        candidateCount > 0 -> "Capturing media ($candidateCount stream(s) observed)"
                        else -> "Select server / Press Play to initiate media stream"
                    }
                    Text(
                        text = statusMessage,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Clip,
                    )
                }
            }

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                if (session != null) {
                    androidx.compose.ui.viewinterop.AndroidView(
                        factory = { ctx ->
                            org.mozilla.geckoview.GeckoView(ctx).apply {
                                setSession(session!!)
                            }
                        },
                        update = { view ->
                            val s = session
                            if (s != null) {
                                view.setSession(s)
                            }
                        },
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            stringResource(R.string.fallback_initializing),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

