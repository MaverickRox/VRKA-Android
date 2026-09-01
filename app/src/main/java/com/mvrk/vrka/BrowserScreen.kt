package com.mvrk.vrka

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.mozilla.geckoview.GeckoView

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowserScreen(
    initialUrl: String = "https://duckduckgo.com",
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val sessionManager = remember { GeckoSessionManager(context) }
    val navState by sessionManager.navState.collectAsStateWithLifecycle()
    val candidates by sessionManager.mediaBridge.candidates.collectAsStateWithLifecycle()
    val uBlockActive by GeckoRuntimeManager.getInstance(context).uBlockActive.collectAsStateWithLifecycle()

    var inputUrl by remember { mutableStateOf(initialUrl) }
    var showMediaSheet by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        sessionManager.loadUri(initialUrl)
    }

    LaunchedEffect(navState.currentUrl) {
        if (!navState.isLoading && navState.currentUrl.isNotBlank()) {
            inputUrl = navState.currentUrl
        }
    }

    BackHandler {
        if (showMediaSheet) {
            showMediaSheet = false
        } else if (navState.canGoBack) {
            sessionManager.goBack()
        } else {
            sessionManager.close()
            onClose()
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            sessionManager.close()
        }
    }

    Scaffold(
        topBar = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = {
                        sessionManager.close()
                        onClose()
                    }) {
                        Text("✕", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    }

                    OutlinedTextField(
                        value = inputUrl,
                        onValueChange = { inputUrl = it },
                        modifier = Modifier
                            .weight(1f)
                            .height(50.dp),
                        singleLine = true,
                        shape = RoundedCornerShape(24.dp),
                        placeholder = { Text("Search or enter URL", fontSize = 13.sp) },
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Uri,
                            imeAction = ImeAction.Go,
                        ),
                        keyboardActions = KeyboardActions(
                            onGo = {
                                focusManager.clearFocus()
                                sessionManager.loadUri(inputUrl)
                            }
                        ),
                        trailingIcon = {
                            if (inputUrl.isNotBlank()) {
                                IconButton(onClick = { inputUrl = "" }) {
                                    Text("✕", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = MaterialTheme.colorScheme.primary,
                            unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
                        ),
                    )

                    IconButton(onClick = {
                        if (navState.isLoading) {
                            sessionManager.stop()
                        } else {
                            sessionManager.reload()
                        }
                    }) {
                        Text(if (navState.isLoading) "⏹" else "↻", fontSize = 18.sp)
                    }
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 2.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    SuggestionChip(
                        onClick = { sessionManager.loadUri("https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8") },
                        label = { Text("HLS Stream", fontSize = 11.sp) }
                    )
                    SuggestionChip(
                        onClick = { sessionManager.loadUri("https://upload.wikimedia.org/wikipedia/commons/transcoded/c/c0/Big_Buck_Bunny_4K.webm/Big_Buck_Bunny_4K.webm.360p.vp9.webm") },
                        label = { Text("WebM Video", fontSize = 11.sp) }
                    )
                    SuggestionChip(
                        onClick = { sessionManager.loadUri("https://duckduckgo.com") },
                        label = { Text("Search", fontSize = 11.sp) }
                    )
                }

                if (navState.isLoading) {
                    LinearProgressIndicator(
                        progress = { navState.progress / 100f },
                        modifier = Modifier.fillMaxWidth().height(2.dp),
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = Color.Transparent,
                    )
                } else {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                }
            }
        },
        bottomBar = {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                tonalElevation = 3.dp,
                color = MaterialTheme.colorScheme.surface,
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceAround,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(
                        onClick = { sessionManager.goBack() },
                        enabled = navState.canGoBack,
                    ) {
                        Text("◀", fontSize = 16.sp, color = if (navState.canGoBack) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f))
                    }

                    IconButton(
                        onClick = { sessionManager.goForward() },
                        enabled = navState.canGoForward,
                    ) {
                        Text("▶", fontSize = 16.sp, color = if (navState.canGoForward) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f))
                    }

                    // uBlock status indicator
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = if (uBlockActive) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier.padding(horizontal = 4.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(if (uBlockActive) "🛡️ uBlock" else "🛡️ Off", fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }

                    // Detected media button badge
                    BadgeBox(
                        count = candidates.size,
                        onClick = { showMediaSheet = true }
                    )
                }
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    GeckoView(ctx).apply {
                        setSession(sessionManager.activeSession)
                    }
                },
                update = { view ->
                    view.setSession(sessionManager.activeSession)
                },
                onRelease = { view ->
                    view.releaseSession()
                }
            )

            // Floating Detected Media Action Strip
            AnimatedVisibility(
                visible = candidates.isNotEmpty() && !showMediaSheet,
                enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
                exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 16.dp)
            ) {
                Surface(
                    onClick = { showMediaSheet = true },
                    shape = RoundedCornerShape(24.dp),
                    color = MaterialTheme.colorScheme.primaryContainer,
                    shadowElevation = 6.dp,
                    modifier = Modifier.padding(horizontal = 16.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text("✨", fontSize = 16.sp)
                        Text(
                            text = "${candidates.size} media stream${if (candidates.size > 1) "s" else ""} detected",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                        Text(
                            text = "• View",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
        }
    }

    if (showMediaSheet) {
        ModalBottomSheet(
            onDismissRequest = { showMediaSheet = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Detected Media Streams",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "${candidates.size} found",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                if (candidates.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 32.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("No media streams detected yet.\nPlay a video or navigate to media content.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(candidates, key = { it.id }) { candidate ->
                            MediaCandidateCard(
                                candidate = candidate,
                                onDownload = {
                                    val app = context.applicationContext as VrkaApplication
                                    BrowserDownloaderBridge.handoffToDownloader(candidate, app.downloads)
                                    Toast.makeText(context, "Added to VRKA Download Queue", Toast.LENGTH_SHORT).show()
                                    showMediaSheet = false
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun BadgeBox(
    count: Int,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text("🎬", fontSize = 14.sp)
            Text(
                text = "Media",
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold
            )
            if (count > 0) {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(18.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            text = if (count > 9) "9+" else count.toString(),
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onError
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MediaCandidateCard(
    candidate: MediaStreamCandidate,
    onDownload: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = when (candidate.kind.uppercase()) {
                            "HLS" -> Color(0xFFE65100)
                            "DASH" -> Color(0xFF1565C0)
                            "AUDIO" -> Color(0xFF2E7D32)
                            else -> MaterialTheme.colorScheme.primary
                        }
                    ) {
                        Text(
                            text = candidate.kind.uppercase(),
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White,
                            modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                        )
                    }

                    if (candidate.resolution.isNotBlank()) {
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = MaterialTheme.colorScheme.secondaryContainer
                        ) {
                            Text(
                                text = candidate.resolution,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                                modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                            )
                        }
                    }

                    Text(
                        text = "• ${candidate.source}",
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Spacer(modifier = Modifier.height(4.dp))

                Text(
                    text = candidate.displayTitle,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                Text(
                    text = candidate.displaySubtitle,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Button(
                onClick = onDownload,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Text("Download", fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}
