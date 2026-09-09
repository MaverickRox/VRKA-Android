package com.mvrk.vrka.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.mvrk.vrka.VrkaMonoFamily
import com.mvrk.vrka.VrkaTokens
import com.mvrk.vrka.update.AppReleaseInfo
import com.mvrk.vrka.update.AppUpdateDownloadState
import java.util.Locale

@Composable
fun AppUpdateDialog(
    release: AppReleaseInfo,
    downloadState: AppUpdateDownloadState,
    onDownloadClick: () -> Unit,
    onDismissClick: () -> Unit,
    onInstallClick: () -> Unit = onDownloadClick,
) {
    Dialog(
        onDismissRequest = {
            if (downloadState !is AppUpdateDownloadState.Downloading) {
                onDismissClick()
            }
        },
        properties = DialogProperties(
            dismissOnBackPress = downloadState !is AppUpdateDownloadState.Downloading,
            dismissOnClickOutside = downloadState !is AppUpdateDownloadState.Downloading,
            usePlatformDefaultWidth = false,
        ),
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .padding(vertical = 24.dp),
            shape = RoundedCornerShape(22.dp),
            color = VrkaTokens.SurfaceCard,
            contentColor = VrkaTokens.TextPrimary,
            border = BorderStroke(1.dp, VrkaTokens.BorderSubtle),
            tonalElevation = 6.dp,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Update Available",
                            style = MaterialTheme.typography.titleLarge.copy(
                                fontFamily = VrkaMonoFamily,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 0.5.sp,
                            ),
                            color = VrkaTokens.TextPrimary,
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Version v${release.version} is now available",
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontFamily = VrkaMonoFamily,
                            ),
                            color = VrkaTokens.TextSecondary,
                        )
                    }

                    // Version Tag Badge
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = VrkaTokens.AccentContainer,
                        border = BorderStroke(1.dp, VrkaTokens.Accent.copy(alpha = 0.4f)),
                    ) {
                        Text(
                            text = "v${release.version}",
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontFamily = VrkaMonoFamily,
                                fontWeight = FontWeight.Bold,
                            ),
                            color = VrkaTokens.Accent,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        )
                    }
                }

                if (release.apkSizeBytes > 0 || release.publishedAt.isNotBlank()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    val metaParts = buildList {
                        if (release.apkSizeBytes > 0) add(formatFileSize(release.apkSizeBytes))
                        if (release.publishedAt.isNotBlank()) add(formatPublishedDate(release.publishedAt))
                    }
                    Text(
                        text = metaParts.joinToString(" • "),
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontFamily = VrkaMonoFamily,
                            fontSize = 11.sp,
                        ),
                        color = VrkaTokens.TextTertiary,
                    )
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Changelog scrollable container
                Text(
                    text = "Release Notes",
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontFamily = VrkaMonoFamily,
                        fontWeight = FontWeight.SemiBold,
                    ),
                    color = VrkaTokens.TextSecondary,
                    modifier = Modifier.padding(bottom = 6.dp),
                )

                val scrollState = rememberScrollState()
                val parsedNotes = remember(release.body) {
                    parseMarkdownToAnnotatedString(release.body)
                }

                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 100.dp, max = 240.dp),
                    shape = RoundedCornerShape(12.dp),
                    color = VrkaTokens.SurfaceInset,
                    border = BorderStroke(1.dp, VrkaTokens.BorderSubtle),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .verticalScroll(scrollState)
                            .padding(14.dp),
                    ) {
                        if (parsedNotes.isBlank()) {
                            Text(
                                text = "No release notes provided for this version.",
                                style = MaterialTheme.typography.bodySmall.copy(
                                    fontFamily = VrkaMonoFamily,
                                    fontStyle = FontStyle.Italic,
                                ),
                                color = VrkaTokens.TextTertiary,
                            )
                        } else {
                            Text(
                                text = parsedNotes,
                                style = MaterialTheme.typography.bodySmall.copy(
                                    fontFamily = VrkaMonoFamily,
                                    lineHeight = 19.sp,
                                ),
                                color = VrkaTokens.TextPrimary,
                            )
                        }
                    }
                }

                // Download Progress / Status
                when (downloadState) {
                    is AppUpdateDownloadState.Downloading -> {
                        Spacer(modifier = Modifier.height(16.dp))
                        Column(modifier = Modifier.fillMaxWidth()) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Text(
                                    text = "Downloading update...",
                                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = VrkaMonoFamily),
                                    color = VrkaTokens.TextSecondary,
                                )
                                Text(
                                    text = "${(downloadState.progress * 100).toInt()}%",
                                    style = MaterialTheme.typography.bodySmall.copy(
                                        fontFamily = VrkaMonoFamily,
                                        fontWeight = FontWeight.Bold,
                                    ),
                                    color = VrkaTokens.Accent,
                                )
                            }
                            Spacer(modifier = Modifier.height(6.dp))
                            LinearProgressIndicator(
                                progress = { downloadState.progress },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(6.dp),
                                color = VrkaTokens.Accent,
                                trackColor = VrkaTokens.SurfaceInset,
                                strokeCap = StrokeCap.Round,
                            )
                            if (downloadState.totalBytes > 0) {
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = "${formatFileSize(downloadState.downloadedBytes)} / ${formatFileSize(downloadState.totalBytes)}",
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        fontFamily = VrkaMonoFamily,
                                        fontSize = 11.sp,
                                    ),
                                    color = VrkaTokens.TextTertiary,
                                    modifier = Modifier.align(Alignment.End),
                                )
                            }
                        }
                    }
                    is AppUpdateDownloadState.Installing -> {
                        Spacer(modifier = Modifier.height(16.dp))
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                                color = VrkaTokens.Accent,
                            )
                            Text(
                                text = "Preparing package installer...",
                                style = MaterialTheme.typography.bodySmall.copy(fontFamily = VrkaMonoFamily),
                                color = VrkaTokens.TextSecondary,
                            )
                        }
                    }
                    is AppUpdateDownloadState.Error -> {
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = downloadState.message,
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = VrkaMonoFamily),
                            color = VrkaTokens.Error,
                        )
                    }
                    else -> Unit
                }

                Spacer(modifier = Modifier.height(20.dp))

                // Action buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (downloadState !is AppUpdateDownloadState.Downloading) {
                        TextButton(
                            onClick = onDismissClick,
                            shape = RoundedCornerShape(10.dp),
                        ) {
                            Text(
                                text = "Later",
                                style = MaterialTheme.typography.bodyMedium.copy(
                                    fontFamily = VrkaMonoFamily,
                                    fontWeight = FontWeight.SemiBold,
                                ),
                                color = VrkaTokens.TextSecondary,
                            )
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                    }

                    when (downloadState) {
                        is AppUpdateDownloadState.Downloading -> {
                            Button(
                                onClick = {},
                                enabled = false,
                                shape = RoundedCornerShape(10.dp),
                                colors = ButtonDefaults.buttonColors(
                                    disabledContainerColor = VrkaTokens.Accent.copy(alpha = 0.4f),
                                    disabledContentColor = Color.White.copy(alpha = 0.7f),
                                ),
                            ) {
                                Text(
                                    text = "Downloading...",
                                    style = MaterialTheme.typography.bodyMedium.copy(
                                        fontFamily = VrkaMonoFamily,
                                        fontWeight = FontWeight.Bold,
                                    ),
                                )
                            }
                        }
                        is AppUpdateDownloadState.ReadyToInstall -> {
                            Button(
                                onClick = onInstallClick,
                                shape = RoundedCornerShape(10.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = VrkaTokens.Success,
                                    contentColor = Color.White,
                                ),
                            ) {
                                Text(
                                    text = "Install",
                                    style = MaterialTheme.typography.bodyMedium.copy(
                                        fontFamily = VrkaMonoFamily,
                                        fontWeight = FontWeight.Bold,
                                    ),
                                )
                            }
                        }
                        is AppUpdateDownloadState.Error -> {
                            Button(
                                onClick = onDownloadClick,
                                shape = RoundedCornerShape(10.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = VrkaTokens.Accent,
                                    contentColor = Color.White,
                                ),
                            ) {
                                Text(
                                    text = "Retry",
                                    style = MaterialTheme.typography.bodyMedium.copy(
                                        fontFamily = VrkaMonoFamily,
                                        fontWeight = FontWeight.Bold,
                                    ),
                                )
                            }
                        }
                        else -> {
                            Button(
                                onClick = onDownloadClick,
                                shape = RoundedCornerShape(10.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = VrkaTokens.Accent,
                                    contentColor = Color.White,
                                ),
                            ) {
                                Text(
                                    text = "Download Update",
                                    style = MaterialTheme.typography.bodyMedium.copy(
                                        fontFamily = VrkaMonoFamily,
                                        fontWeight = FontWeight.Bold,
                                    ),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Lightweight, safe Markdown-to-AnnotatedString parser.
 * Supports headings (#, ##, ###), bullet lists (*, -), bold text (**bold**),
 * inline code (`code`), URLs, and line breaks without heavy external dependencies.
 */
fun parseMarkdownToAnnotatedString(markdown: String): AnnotatedString {
    if (markdown.isBlank()) return AnnotatedString("")

    return buildAnnotatedString {
        val lines = markdown.lines()
        var isFirstLine = true

        for (line in lines) {
            val trimmed = line.trim()

            if (!isFirstLine) {
                append("\n")
            }
            isFirstLine = false

            when {
                trimmed.startsWith("### ") -> {
                    val content = trimmed.removePrefix("### ").trim()
                    pushStyle(SpanStyle(fontWeight = FontWeight.Bold, fontSize = 14.sp))
                    appendInlineFormattedText(content)
                    pop()
                }
                trimmed.startsWith("## ") -> {
                    val content = trimmed.removePrefix("## ").trim()
                    pushStyle(SpanStyle(fontWeight = FontWeight.Bold, fontSize = 15.sp))
                    appendInlineFormattedText(content)
                    pop()
                }
                trimmed.startsWith("# ") -> {
                    val content = trimmed.removePrefix("# ").trim()
                    pushStyle(SpanStyle(fontWeight = FontWeight.Bold, fontSize = 16.sp))
                    appendInlineFormattedText(content)
                    pop()
                }
                trimmed.startsWith("- ") || trimmed.startsWith("* ") -> {
                    val bulletText = trimmed.substring(2).trim()
                    append("• ")
                    appendInlineFormattedText(bulletText)
                }
                trimmed.startsWith("> ") -> {
                    val quoteText = trimmed.removePrefix("> ").trim()
                    pushStyle(SpanStyle(fontStyle = FontStyle.Italic))
                    append("│ ")
                    appendInlineFormattedText(quoteText)
                    pop()
                }
                else -> {
                    appendInlineFormattedText(trimmed)
                }
            }
        }
    }
}

private fun AnnotatedString.Builder.appendInlineFormattedText(text: String) {
    var cursor = 0
    val length = text.length

    while (cursor < length) {
        // Check for bold: **text**
        if (cursor + 1 < length && text[cursor] == '*' && text[cursor + 1] == '*') {
            val end = text.indexOf("**", cursor + 2)
            if (end != -1) {
                val boldContent = text.substring(cursor + 2, end)
                pushStyle(SpanStyle(fontWeight = FontWeight.Bold))
                append(boldContent)
                pop()
                cursor = end + 2
                continue
            }
        }

        // Check for inline code: `code`
        if (text[cursor] == '`') {
            val end = text.indexOf('`', cursor + 1)
            if (end != -1) {
                val codeContent = text.substring(cursor + 1, end)
                pushStyle(
                    SpanStyle(
                        fontFamily = FontFamily.Monospace,
                        background = Color(0x22FFFFFF),
                    ),
                )
                append(" $codeContent ")
                pop()
                cursor = end + 1
                continue
            }
        }

        // Check for markdown link: [text](url)
        if (text[cursor] == '[') {
            val closingBracket = text.indexOf(']', cursor + 1)
            if (closingBracket != -1 && closingBracket + 1 < length && text[closingBracket + 1] == '(') {
                val closingParen = text.indexOf(')', closingBracket + 2)
                if (closingParen != -1) {
                    val linkText = text.substring(cursor + 1, closingBracket)
                    pushStyle(
                        SpanStyle(
                            color = Color(0xFF64B5F6),
                            textDecoration = TextDecoration.Underline,
                        ),
                    )
                    append(linkText)
                    pop()
                    cursor = closingParen + 1
                    continue
                }
            }
        }

        // Default: append single character
        append(text[cursor])
        cursor++
    }
}

private fun formatFileSize(bytes: Long): String {
    if (bytes <= 0) return ""
    val kb = bytes / 1024.0
    val mb = kb / 1024.0
    val gb = mb / 1024.0
    return when {
        gb >= 1.0 -> String.format(Locale.US, "%.1f GB", gb)
        mb >= 1.0 -> String.format(Locale.US, "%.1f MB", mb)
        else -> String.format(Locale.US, "%.0f KB", kb)
    }
}

private fun formatPublishedDate(dateStr: String): String {
    return if (dateStr.length >= 10) dateStr.substring(0, 10) else dateStr
}
