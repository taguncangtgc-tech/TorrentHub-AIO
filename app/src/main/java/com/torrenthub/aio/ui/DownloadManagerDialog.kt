package com.torrenthub.aio.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
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
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.torrenthub.aio.R
import com.torrenthub.aio.model.DownloadCategory
import com.torrenthub.aio.model.DownloadRecord
import com.torrenthub.aio.model.DownloadState
import com.torrenthub.aio.util.Formatters

@Composable
fun DownloadManagerDialog(
    vm: AppViewModel,
    onDismiss: () -> Unit
) {
    val p = AppTheme.palette
    val context = LocalContext.current
    var filterMode by remember { mutableStateOf("all") }

    val allRecords = vm.downloadRecords
    val activeCount = allRecords.count { it.state == DownloadState.DOWNLOADING || it.state == DownloadState.QUEUED }
    val completedCount = allRecords.count { it.state == DownloadState.COMPLETED }

    val displayedRecords = remember(allRecords.size, allRecords.toList(), filterMode) {
        when (filterMode) {
            "active" -> allRecords.filter { it.state == DownloadState.DOWNLOADING || it.state == DownloadState.QUEUED }
            "completed" -> allRecords.filter { it.state == DownloadState.COMPLETED }
            else -> allRecords
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            color = p.background,
            shape = RoundedCornerShape(16.dp),
            border = BorderStroke(1.dp, p.borderColor),
            modifier = Modifier
                .fillMaxWidth(0.95f)
                .fillMaxHeight(0.88f)
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // Header
                Surface(color = p.barSurface, modifier = Modifier.fillMaxWidth()) {
                    Column {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = stringResource(R.string.dl_title),
                                    fontWeight = FontWeight.ExtraBold,
                                    fontSize = 16.5.sp,
                                    color = p.textPrimary
                                )
                                Text(
                                    text = stringResource(R.string.dl_subtitle),
                                    fontSize = 11.5.sp,
                                    color = p.textMuted
                                )
                            }
                            IconButton(onClick = onDismiss) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = stringResource(R.string.cd_close),
                                    tint = p.textPrimary
                                )
                            }
                        }

                        // Filter & Clear Bar
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 12.dp, end = 12.dp, bottom = 10.dp)
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            FilterChip(
                                selected = filterMode == "all",
                                onClick = { filterMode = "all" },
                                label = { Text(stringResource(R.string.dl_filter_all, allRecords.size), fontSize = 11.5.sp) }
                            )
                            FilterChip(
                                selected = filterMode == "active",
                                onClick = { filterMode = "active" },
                                label = { Text(stringResource(R.string.dl_filter_active, activeCount), fontSize = 11.5.sp) }
                            )
                            FilterChip(
                                selected = filterMode == "completed",
                                onClick = { filterMode = "completed" },
                                label = { Text(stringResource(R.string.dl_filter_completed, completedCount), fontSize = 11.5.sp) }
                            )
                            if (allRecords.any { it.state != DownloadState.DOWNLOADING && it.state != DownloadState.QUEUED }) {
                                AssistChip(
                                    onClick = { vm.clearFinishedDownloads() },
                                    leadingIcon = { Icon(Icons.Default.DeleteSweep, contentDescription = null, modifier = Modifier.size(16.dp)) },
                                    label = { Text(stringResource(R.string.dl_btn_clear_done), fontSize = 11.5.sp) }
                                )
                            }
                        }
                        HorizontalDivider(color = p.borderColor, thickness = 1.dp)
                    }
                }

                // Body
                if (displayedRecords.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .padding(28.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                imageVector = Icons.Default.DownloadDone,
                                contentDescription = null,
                                tint = p.textMuted,
                                modifier = Modifier.size(52.dp)
                            )
                            Spacer(Modifier.height(10.dp))
                            Text(
                                text = stringResource(R.string.dl_empty_state),
                                color = p.textMuted,
                                fontSize = 13.sp
                            )
                        }
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                        contentPadding = PaddingValues(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(displayedRecords, key = { it.id }) { rec ->
                            DownloadRecordCard(
                                record = rec,
                                onOpen = { vm.openDownloadRecord(context, rec) },
                                onCancel = { vm.cancelDownloadTask(rec.id) },
                                onRetry = { vm.retryDownloadTask(rec.id) },
                                onDelete = { vm.deleteDownloadTask(rec.id) },
                                onSendGemini = { vm.sendDownloadedSubtitleToGemini(rec) }
                            )
                        }
                    }
                }

                // Footer
                Surface(color = p.barSurface, modifier = Modifier.fillMaxWidth()) {
                    Column {
                        HorizontalDivider(color = p.borderColor, thickness = 1.dp)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 10.dp),
                            horizontalArrangement = Arrangement.End
                        ) {
                            Button(
                                onClick = onDismiss,
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Text(stringResource(R.string.common_close), fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DownloadRecordCard(
    record: DownloadRecord,
    onOpen: () -> Unit,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
    onDelete: () -> Unit,
    onSendGemini: () -> Unit
) {
    val p = AppTheme.palette
    val (catLabel, catColor) = when (record.category) {
        DownloadCategory.BROWSER -> stringResource(R.string.dl_cat_browser) to p.accentBlue
        DownloadCategory.SUBTITLE -> stringResource(R.string.dl_cat_subtitle) to p.accentPurple
        DownloadCategory.TORRENT -> stringResource(R.string.dl_cat_torrent) to p.accentGreen
    }

    val statusText = when (record.state) {
        DownloadState.QUEUED -> stringResource(R.string.dl_state_queued)
        DownloadState.DOWNLOADING -> stringResource(R.string.dl_state_downloading, record.progressPct)
        DownloadState.COMPLETED -> stringResource(R.string.dl_state_completed)
        DownloadState.FAILED -> stringResource(R.string.dl_state_failed, record.errorMessage.ifBlank { "Error" })
        DownloadState.CANCELLED -> stringResource(R.string.dl_state_cancelled)
    }

    val statusColor = when (record.state) {
        DownloadState.COMPLETED -> p.accentGreen
        DownloadState.FAILED -> p.accentRed
        DownloadState.DOWNLOADING, DownloadState.QUEUED -> p.accentBlue
        DownloadState.CANCELLED -> p.textMuted
    }

    val isSubFile = record.category == DownloadCategory.SUBTITLE ||
        Regex("\\.(srt|ass|ssa|vtt)$", RegexOption.IGNORE_CASE).containsMatchIn(record.fileName)

    Surface(
        color = p.cardSurface,
        shape = RoundedCornerShape(12.dp),
        border = p.cardBorder,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    color = catColor.copy(alpha = if (p.isLight) 0.14f else 0.22f),
                    shape = RoundedCornerShape(6.dp)
                ) {
                    Text(
                        text = catLabel,
                        fontSize = 10.5.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = catColor,
                        modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp)
                    )
                }
                Text(
                    text = Formatters.formatDate(record.createdAtMs),
                    fontSize = 10.5.sp,
                    color = p.textMuted
                )
            }

            Text(
                text = record.fileName,
                fontWeight = FontWeight.Bold,
                fontSize = 13.5.sp,
                color = p.textPrimary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )

            // Progress bar for active downloads
            if (record.state == DownloadState.DOWNLOADING || record.state == DownloadState.QUEUED) {
                if (record.totalBytes > 0L) {
                    LinearProgressIndicator(
                        progress = { (record.progressPct / 100f).coerceIn(0f, 1f) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .clip(RoundedCornerShape(50)),
                        color = p.accentBlue,
                        trackColor = p.chipBg
                    )
                } else {
                    LinearProgressIndicator(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .clip(RoundedCornerShape(50)),
                        color = p.accentBlue,
                        trackColor = p.chipBg
                    )
                }
            }

            // Status & Size metrics
            val sizeSummary = when {
                record.state == DownloadState.DOWNLOADING && record.totalBytes > 0L ->
                    "${Formatters.formatBytes(record.downloadedBytes)} / ${Formatters.formatBytes(record.totalBytes)}" +
                        (if (record.speedBytesPerSec > 0L) " · ${Formatters.formatBytes(record.speedBytesPerSec)}/s" else "")
                record.state == DownloadState.DOWNLOADING ->
                    Formatters.formatBytes(record.downloadedBytes) +
                        (if (record.speedBytesPerSec > 0L) " · ${Formatters.formatBytes(record.speedBytesPerSec)}/s" else "")
                record.totalBytes > 0L -> Formatters.formatBytes(record.totalBytes)
                record.downloadedBytes > 0L -> Formatters.formatBytes(record.downloadedBytes)
                else -> "—"
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = statusText,
                    fontSize = 11.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = statusColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = sizeSummary,
                    fontSize = 11.5.sp,
                    fontWeight = FontWeight.Bold,
                    color = p.textSecondary
                )
            }

            // Action Buttons
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                when (record.state) {
                    DownloadState.DOWNLOADING, DownloadState.QUEUED -> {
                        OutlinedButton(
                            onClick = onCancel,
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                        ) {
                            Text(stringResource(R.string.dl_btn_cancel), fontSize = 11.5.sp, color = p.accentRed)
                        }
                    }

                    DownloadState.COMPLETED -> {
                        Button(
                            onClick = onOpen,
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                        ) {
                            Icon(Icons.Default.FolderOpen, contentDescription = null, modifier = Modifier.size(15.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(stringResource(R.string.dl_btn_open), fontSize = 11.5.sp)
                        }
                        if (isSubFile) {
                            Button(
                                onClick = onSendGemini,
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF7C3AED)),
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                            ) {
                                Text(stringResource(R.string.dl_btn_send_gemini), fontSize = 11.5.sp, color = Color.White)
                            }
                        }
                        OutlinedButton(
                            onClick = onDelete,
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                        ) {
                            Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(15.dp), tint = p.accentRed)
                            Spacer(Modifier.width(4.dp))
                            Text(stringResource(R.string.dl_btn_delete), fontSize = 11.5.sp, color = p.accentRed)
                        }
                    }

                    DownloadState.FAILED, DownloadState.CANCELLED -> {
                        Button(
                            onClick = onRetry,
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0284C7)),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                        ) {
                            Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(15.dp), tint = Color.White)
                            Spacer(Modifier.width(4.dp))
                            Text(stringResource(R.string.dl_btn_retry), fontSize = 11.5.sp, color = Color.White)
                        }
                        OutlinedButton(
                            onClick = onDelete,
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                        ) {
                            Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(15.dp), tint = p.accentRed)
                            Spacer(Modifier.width(4.dp))
                            Text(stringResource(R.string.dl_btn_delete), fontSize = 11.5.sp, color = p.accentRed)
                        }
                    }
                }
            }
        }
    }
}
