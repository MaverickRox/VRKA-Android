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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

private enum class Destination(val label: String, @param:DrawableRes val iconRes: Int) {
    DOWNLOAD("Download", R.drawable.ic_download),
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
    val settings by manager.settingsRepository.settings.collectAsStateWithLifecycle()
    val runtime by manager.runtime.collectAsStateWithLifecycle()
    var destination by remember { mutableStateOf(Destination.DOWNLOAD) }
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

    val activeJob = jobs.firstOrNull { !it.state.isTerminal }

    VrkaTheme(themeMode = settings.themeMode, amoled = settings.amoled) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background,
            contentColor = MaterialTheme.colorScheme.onBackground,
        ) {
            Box(
                modifier = Modifier.fillMaxSize(),
            ) {
            // Screen content with animated destination transitions
            AnimatedContent(
                targetState = destination,
                transitionSpec = {
                    val forward = targetState.ordinal >= initialState.ordinal
                    val slideOffset = if (forward) 1 else -1
                    (slideInHorizontally(
                        initialOffsetX = { width -> slideOffset * (width / 5) },
                        animationSpec = tween(230, easing = FastOutSlowInEasing),
                    ) + fadeIn(animationSpec = tween(210))) togetherWith
                        (slideOutHorizontally(
                            targetOffsetX = { width -> -slideOffset * (width / 5) },
                            animationSpec = tween(200, easing = FastOutSlowInEasing),
                        ) + fadeOut(animationSpec = tween(180)))
                },
                label = "destination_transition",
                modifier = Modifier.fillMaxSize(),
            ) { targetDest ->
                when (targetDest) {
                    Destination.DOWNLOAD -> HomeScreen(
                        settings = settings,
                        runtime = runtime,
                        modifier = Modifier
                            .fillMaxSize()
                            .statusBarsPadding()
                            .padding(bottom = 96.dp),
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
                        modifier = Modifier
                            .fillMaxSize()
                            .statusBarsPadding()
                            .padding(bottom = 96.dp),
                        onCancel = manager::cancel,
                        onRetry = manager::retry,
                        onOpen = manager::openOutput,
                        onShare = manager::shareOutput,
                        onDelete = manager::deleteJob,
                        onShowFallback = manager::showFallbackView,
                    )
                    Destination.HISTORY -> JobsScreen(
                        jobs = jobs.filter { it.state.isTerminal },
                        emptyMessage = "Completed and failed downloads appear here.",
                        modifier = Modifier
                            .fillMaxSize()
                            .statusBarsPadding()
                            .padding(bottom = 96.dp),
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
                        modifier = Modifier
                            .fillMaxSize()
                            .statusBarsPadding()
                            .padding(bottom = 96.dp),
                    )
                }
            }

            // Snackbar Host
            SnackbarHost(
                hostState = snackbar,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 100.dp),
            )

            // Floating Bottom Section: Active Download Capsule + Floating Glass Pill Navigation Bar
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .navigationBarsPadding(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                // Floating Active Download Strip
                AnimatedVisibility(
                    visible = activeJob != null && destination != Destination.QUEUE,
                    enter = slideInVertically(
                        initialOffsetY = { it },
                        animationSpec = tween(240, easing = FastOutSlowInEasing),
                    ) + fadeIn(animationSpec = tween(200)),
                    exit = slideOutVertically(
                        targetOffsetY = { it },
                        animationSpec = tween(200, easing = FastOutSlowInEasing),
                    ) + fadeOut(animationSpec = tween(160)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 22.dp, vertical = 6.dp),
                ) {
                    if (activeJob != null) {
                        ActiveDownloadStrip(
                            job = activeJob,
                            onClick = { destination = Destination.QUEUE },
                        )
                    }
                }

                // Floating Glass Pill Navigation Bar
                Surface(
                    shape = RoundedCornerShape(32.dp),
                    color = VrkaGlassSurface,
                    border = BorderStroke(1.dp, VrkaGlassBorder),
                    modifier = Modifier
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                        .shadow(16.dp, RoundedCornerShape(32.dp)),
                ) {
                    Row(
                        modifier = Modifier
                            .padding(horizontal = 6.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Destination.entries.forEach { item ->
                            val isSelected = destination == item
                            val pillBg by animateColorAsState(
                                targetValue = if (isSelected) VrkaPurple.copy(alpha = 0.22f) else Color.Transparent,
                                animationSpec = tween(200),
                                label = "pill_bg",
                            )
                            val contentColor by animateColorAsState(
                                targetValue = if (isSelected) VrkaPurpleLight else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.72f),
                                animationSpec = tween(200),
                                label = "pill_color",
                            )

                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(24.dp))
                                    .background(pillBg)
                                    .clickable(
                                        interactionSource = remember { MutableInteractionSource() },
                                        indication = null,
                                        onClick = { destination = item },
                                    )
                                    .padding(
                                        horizontal = if (isSelected) 14.dp else 10.dp,
                                        vertical = 8.dp,
                                    ),
                                contentAlignment = Alignment.Center,
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.Center,
                                ) {
                                    Icon(
                                        painter = painterResource(item.iconRes),
                                        contentDescription = item.label,
                                        tint = contentColor,
                                        modifier = Modifier.size(19.dp),
                                    )
                                    androidx.compose.animation.AnimatedVisibility(
                                        visible = isSelected,
                                        enter = androidx.compose.animation.fadeIn(tween(180)) +
                                            androidx.compose.animation.expandHorizontally(tween(200)),
                                        exit = androidx.compose.animation.fadeOut(tween(140)) +
                                            androidx.compose.animation.shrinkHorizontally(tween(160)),
                                    ) {
                                        Text(
                                            text = item.label,
                                            style = MaterialTheme.typography.labelMedium,
                                            fontWeight = FontWeight.Bold,
                                            color = contentColor,
                                            fontSize = 12.sp,
                                            maxLines = 1,
                                            softWrap = false,
                                            modifier = Modifier.padding(start = 6.dp),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
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

    LaunchedEffect(openQueueToken) {
        if (openQueueToken > 0L) destination = Destination.QUEUE
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
            // Header Bar
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

            // Web Content (GeckoView)
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
