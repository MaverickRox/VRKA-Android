package com.mvrk.vrka

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
    var destination by remember { mutableStateOf(VrkaDestination.DOWNLOAD) }
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
        destination = VrkaDestination.QUEUE
    }

    val activeJob = jobs.firstOrNull { !it.state.isTerminal }

    androidx.compose.runtime.SideEffect {
        android.util.Log.d("VrkaNavPerf", "VrkaRoot destination committed: ${destination.name}")
    }

    VrkaTheme(themeMode = settings.themeMode, amoled = settings.amoled) {
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

                    // Clean single-destination composition: Zero background screen measurement or overdraw
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .nestedScroll(scrollBackdropConnection)
                            .layerBackdrop(screenBackdrop),
                    ) {
                        when (destination) {
                            VrkaDestination.DOWNLOAD -> {
                                HomeScreen(
                                    settings = settings,
                                    runtime = runtime,
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .statusBarsPadding(),
                                    onEnqueue = handleEnqueue,
                                )
                            }
                            VrkaDestination.QUEUE -> {
                                JobsScreen(
                                    jobs = activeJobs,
                                    emptyMessage = "Your active queue is empty.",
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .statusBarsPadding(),
                                    onCancel = manager::cancel,
                                    onRetry = manager::retry,
                                    onOpen = manager::openOutput,
                                    onShare = manager::shareOutput,
                                    onDelete = manager::deleteJob,
                                    onShowFallback = manager::showFallbackView,
                                )
                            }
                            VrkaDestination.HISTORY -> {
                                JobsScreen(
                                    jobs = historyJobs,
                                    emptyMessage = "Completed and failed downloads appear here.",
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .statusBarsPadding(),
                                    onCancel = manager::cancel,
                                    onRetry = manager::retry,
                                    onOpen = manager::openOutput,
                                    onShare = manager::shareOutput,
                                    onDelete = manager::deleteJob,
                                    onClear = manager::clearFinished,
                                )
                            }
                            VrkaDestination.SETTINGS -> {
                                SettingsScreen(
                                    settings = settings,
                                    runtime = runtime,
                                    repository = manager.settingsRepository,
                                    onUpdateRuntime = manager::updateRuntime,
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
                        .padding(bottom = 104.dp),
                )

                Column(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(bottom = 20.dp),
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
                                onClick = { destination = VrkaDestination.QUEUE },
                            )
                        }
                    }

                    VrkaFloatingNavBar(
                        selectedDestination = destination,
                        onDestinationSelected = { destination = it },
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
            }
        }
    }
    }

    LaunchedEffect(openQueueToken) {
        if (openQueueToken > 0L) destination = VrkaDestination.QUEUE
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
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Surface(
                                shape = androidx.compose.foundation.shape.RoundedCornerShape(4.dp),
                                color = VrkaWarning.copy(alpha = 0.2f),
                            ) {
                                Text(
                                    "BROWSER FALLBACK",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                                    color = VrkaWarning,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                            Text(
                                fallbackState.job.title.ifBlank {
                                    runCatching { Uri.parse(fallbackState.engine.targetUrl).host }.getOrNull().orEmpty()
                                },
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                                maxLines = 1,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                            )
                        }
                        Text(
                            when {
                                candidateCount > 0 -> "Capturing media ($candidateCount stream(s) observed)"
                                else -> "Select server / Press Play to initiate media stream"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 2.dp)
                        )
                    }

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedButton(
                            onClick = onDismiss,
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                                horizontal = 12.dp,
                                vertical = 4.dp
                            )
                        ) {
                            Text("Minimize", style = MaterialTheme.typography.labelMedium)
                        }
                        Button(
                            onClick = onCancel,
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                                horizontal = 12.dp,
                                vertical = 4.dp
                            )
                        ) {
                            Text("Cancel", style = MaterialTheme.typography.labelMedium)
                        }
                    }
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
                            "Initializing fallback browser session...",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

