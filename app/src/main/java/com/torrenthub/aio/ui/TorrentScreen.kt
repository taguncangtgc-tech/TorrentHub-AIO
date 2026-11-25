package com.torrenthub.aio.ui

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.torrenthub.aio.R
import com.torrenthub.aio.data.PreviewRepository
import com.torrenthub.aio.data.TorrentRepository
import com.torrenthub.aio.model.PillState
import com.torrenthub.aio.model.PikPakDriveItem
import com.torrenthub.aio.model.QuickStreamStage
import com.torrenthub.aio.model.QuickStreamState
import com.torrenthub.aio.model.SourceStatus
import com.torrenthub.aio.model.ThreeSourceResult
import com.torrenthub.aio.model.TorrentItem
import com.torrenthub.aio.model.WhatsLinkInfo
import com.torrenthub.aio.network.HttpClient
import com.torrenthub.aio.util.ExternalPlayerHelper
import com.torrenthub.aio.util.Formatters
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TorrentScreen(vm: AppViewModel) {
    val p = AppTheme.palette
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current

    var showSourcesSheet by remember { mutableStateOf(false) }
    var showSortSheet by remember { mutableStateOf(false) }
    var whatsLinkTarget by remember { mutableStateOf<TorrentItem?>(null) }
    var threeSourceCode by remember { mutableStateOf<String?>(null) }
    var bypassTarget by remember { mutableStateOf<SourceStatus?>(null) }

    val displayed = remember(
        vm.allTorrents.size,
        vm.allTorrents.toList(),
        vm.torrentSubFilter,
        vm.torrentSortMode,
        vm.torrentSourceFilter,
        vm.torrentTagFilter,
        vm.hideDeadTorrents
    ) {
        vm.filteredAndSortedTorrents()
    }

    val selectedList = vm.selectedMagnets.filterValues { it }.keys.toList()
    val activeSourceCount = TorrentRepository.SOURCES.count { vm.enabledSources[it.id] == true }

    Column(modifier = Modifier.fillMaxSize()) {
        // Top Search & Controls Surface
        Surface(
            color = p.barSurface,
            tonalElevation = if (p.isAmoled) 0.dp else 3.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = vm.torrentQuery,
                        onValueChange = { vm.torrentQuery = it },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        placeholder = { Text(stringResource(R.string.torrent_search_placeholder), fontSize = 13.sp) },
                        trailingIcon = {
                            if (vm.torrentQuery.isNotEmpty()) {
                                IconButton(onClick = { vm.torrentQuery = "" }) {
                                    Icon(Icons.Default.Clear, contentDescription = stringResource(R.string.cd_clear))
                                }
                            }
                        },
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = {
                            focusManager.clearFocus()
                            vm.runTorrentSearch()
                        }),
                        shape = RoundedCornerShape(10.dp)
                    )
                    Button(
                        onClick = {
                            focusManager.clearFocus()
                            vm.runTorrentSearch()
                        },
                        enabled = !vm.isSearchingTorrents,
                        shape = RoundedCornerShape(10.dp),
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 14.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0D9488))
                    ) {
                        if (vm.isSearchingTorrents) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                                color = Color.White
                            )
                        } else {
                            Icon(Icons.Default.Search, contentDescription = stringResource(R.string.cd_search), tint = Color.White)
                        }
                    }
                }

                // Quick Filter & Sort Chips Row
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    AssistChip(
                        onClick = { showSourcesSheet = true },
                        label = { Text(stringResource(R.string.torrent_chip_sources, activeSourceCount), fontSize = 12.sp) },
                        leadingIcon = { Icon(Icons.Default.Tune, null, Modifier.size(16.dp)) }
                    )
                    AssistChip(
                        onClick = { showSortSheet = true },
                        label = { Text(sortLabelShortComposable(vm.torrentSortMode), fontSize = 12.sp) },
                        leadingIcon = { Icon(Icons.Default.Sort, null, Modifier.size(16.dp)) }
                    )
                    FilterChip(
                        selected = vm.hideDeadTorrents,
                        onClick = { vm.updateHideDead(!vm.hideDeadTorrents) },
                        label = { Text(stringResource(R.string.torrent_chip_no_zero_se), fontSize = 12.sp) }
                    )
                    listOf(
                        "4k" to "4K",
                        "sub" to stringResource(R.string.torrent_tag_sub),
                        "unc" to stringResource(R.string.torrent_tag_unc),
                        "multi" to stringResource(R.string.torrent_tag_multi)
                    ).forEach { (key, lbl) ->
                        FilterChip(
                            selected = vm.torrentTagFilter == key,
                            onClick = {
                                vm.torrentTagFilter = if (vm.torrentTagFilter == key) "all" else key
                            },
                            label = { Text(lbl, fontSize = 12.sp) }
                        )
                    }
                }

                // Sub-filter input if results exist
                if (vm.allTorrents.isNotEmpty()) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = vm.torrentSubFilter,
                            onValueChange = { vm.torrentSubFilter = it },
                            modifier = Modifier
                                .weight(1f)
                                .height(46.dp),
                            singleLine = true,
                            placeholder = { Text(stringResource(R.string.torrent_subfilter_placeholder), fontSize = 12.sp) },
                            textStyle = LocalTextStyle.current.copy(fontSize = 12.sp),
                            shape = RoundedCornerShape(8.dp)
                        )
                        Text(
                            text = stringResource(R.string.torrent_results_count, displayed.size, vm.allTorrents.size),
                            fontSize = 11.sp,
                            color = p.textMuted,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }

                // Per-site Status Pills
                if (vm.sourceStatuses.isNotEmpty()) {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(vm.sourceStatuses, key = { it.sourceId }) { st ->
                            val (bg, fg) = if (p.isLight) {
                                when (st.state) {
                                    PillState.LOADING -> Color(0xFFFEF3C7) to Color(0xFF92400E)
                                    PillState.OK -> Color(0xFFDCFCE7) to Color(0xFF166534)
                                    PillState.ERROR -> Color(0xFFFEE2E2) to Color(0xFF991B1B)
                                }
                            } else {
                                when (st.state) {
                                    PillState.LOADING -> Color(0xFF422006) to Color(0xFFFDE68A)
                                    PillState.OK -> Color(0xFF052E16) to Color(0xFF86EFAC)
                                    PillState.ERROR -> Color(0xFF450A0A) to Color(0xFFFCA5A5)
                                }
                            }
                            val text = when (st.state) {
                                PillState.LOADING -> "${st.label}: ${st.message.ifBlank { "…" }}"
                                PillState.OK -> "${st.label}: ${st.count}"
                                PillState.ERROR -> if (st.canBypassCloudflare) {
                                    stringResource(R.string.torrent_bypass_pill, st.label)
                                } else {
                                    "${st.label}: ${st.message.take(18)}"
                                }
                            }
                            Surface(
                                color = bg,
                                shape = RoundedCornerShape(50),
                                modifier = if (st.canBypassCloudflare) {
                                    Modifier.clickable { bypassTarget = st }
                                } else Modifier
                            ) {
                                Text(
                                    text = text,
                                    color = fg,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
        if (p.isLight || p.isAmoled) {
            HorizontalDivider(color = p.borderColor, thickness = 1.dp)
        }

        // Results List
        Box(modifier = Modifier.weight(1f)) {
            if (displayed.isEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(28.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        imageVector = Icons.Default.TravelExplore,
                        contentDescription = null,
                        tint = p.textMuted,
                        modifier = Modifier.size(54.dp)
                    )
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = if (vm.isSearchingTorrents) stringResource(R.string.torrent_empty_searching)
                        else stringResource(R.string.torrent_empty_idle),
                        color = p.textMuted,
                        fontSize = 13.sp
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        start = 10.dp,
                        end = 10.dp,
                        top = 8.dp,
                        bottom = if (selectedList.isNotEmpty()) 76.dp else 12.dp
                    ),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    itemsIndexed(displayed, key = { idx, item -> "${item.uniqueKey}#$idx" }) { _, item ->
                        TorrentResultCard(
                            item = item,
                            isChecked = vm.selectedMagnets[item.magnet] == true,
                            onCheckedChange = { chk ->
                                if (chk) vm.selectedMagnets[item.magnet] = true
                                else vm.selectedMagnets.remove(item.magnet)
                            },
                            onWhatsLink = { whatsLinkTarget = item },
                            onThreeSource = { if (item.videoCode.isNotBlank()) threeSourceCode = item.videoCode },
                            onCopy = {
                                copyToClipboard(context, item.magnet)
                                vm.emitToast(vm.str(R.string.toast_link_copied, item.title.take(45)))
                            },
                            onSendPikPak = { vm.sendMagnetsToPikPak(listOf(item.magnet)) },
                            onQuickStream = { vm.startQuickStream(item) },
                            onStreamMagnet = { openMagnetExternal(context, item.magnet, vm) },
                            onSearchSubtitle = { code ->
                                vm.currentTab = 1
                                vm.runSubtitleSearch(code)
                            }
                        )
                    }
                }
            }

            // Sticky Batch Action Bar
            if (selectedList.isNotEmpty()) {
                Surface(
                    color = p.cardSurface,
                    shadowElevation = 10.dp,
                    border = BorderStroke(1.dp, p.borderColor),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .padding(10.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            stringResource(R.string.torrent_selected_count, selectedList.size),
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp,
                            color = p.textPrimary
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            TextButton(onClick = { vm.selectedMagnets.clear() }) {
                                Text(stringResource(R.string.common_cancel), fontSize = 12.sp)
                            }
                            Button(
                                onClick = {
                                    copyToClipboard(context, selectedList.joinToString("\n"))
                                    vm.emitToast(vm.str(R.string.toast_links_copied, selectedList.size))
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF059669)),
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                            ) {
                                Text(stringResource(R.string.common_copy), fontSize = 12.sp, color = Color.White)
                            }
                            Button(
                                onClick = { vm.sendMagnetsToPikPak(selectedList) },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0284C7)),
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                            ) {
                                Text("☁️ PikPak (${selectedList.size})", fontSize = 12.sp, color = Color.White)
                            }
                        }
                    }
                }
            }
        }
    }

    // Bottom Sheet: Source Toggle (16 Sites)
    if (showSourcesSheet) {
        ModalBottomSheet(
            onDismissRequest = { showSourcesSheet = false },
            containerColor = p.barSurface
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        stringResource(R.string.torrent_sources_sheet_title),
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp,
                        color = p.textPrimary,
                        modifier = Modifier.weight(1f)
                    )
                    Row {
                        TextButton(onClick = { vm.setAllSources(true) }) { Text(stringResource(R.string.common_all)) }
                        TextButton(onClick = { vm.setAllSources(false) }) { Text(stringResource(R.string.common_reset)) }
                    }
                }
                LazyColumn(modifier = Modifier.heightIn(max = 420.dp)) {
                    items(TorrentRepository.SOURCES, key = { it.id }) { src ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { vm.toggleSource(src.id, !(vm.enabledSources[src.id] ?: false)) }
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(
                                checked = vm.enabledSources[src.id] == true,
                                onCheckedChange = { vm.toggleSource(src.id, it) }
                            )
                            Box(
                                modifier = Modifier
                                    .size(10.dp)
                                    .clip(CircleShape)
                                    .background(Color(src.colorHex))
                            )
                            Spacer(Modifier.width(10.dp))
                            Text(src.label, fontSize = 14.sp, color = p.textPrimary, modifier = Modifier.weight(1f))
                        }
                    }
                }
                Spacer(Modifier.height(16.dp))
            }
        }
    }

    // Bottom Sheet: Sort & Source Filter
    if (showSortSheet) {
        ModalBottomSheet(
            onDismissRequest = { showSortSheet = false },
            containerColor = p.barSurface
        ) {
            val sortOptions = listOf(
                "seeders-desc" to stringResource(R.string.sort_seeders_desc),
                "leechers-desc" to stringResource(R.string.sort_leechers_desc),
                "size-desc" to stringResource(R.string.sort_size_desc),
                "size-asc" to stringResource(R.string.sort_size_asc),
                "date-desc" to stringResource(R.string.sort_date_desc),
                "date-asc" to stringResource(R.string.sort_date_asc),
                "files-desc" to stringResource(R.string.sort_files_desc),
                "sources-desc" to stringResource(R.string.sort_sources_desc),
                "name-asc" to stringResource(R.string.sort_name_asc),
                "name-desc" to stringResource(R.string.sort_name_desc)
            )
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                Text(stringResource(R.string.torrent_sort_sheet_title), fontWeight = FontWeight.Bold, fontSize = 16.sp, color = p.textPrimary)
                Spacer(Modifier.height(6.dp))
                LazyColumn(modifier = Modifier.heightIn(max = 400.dp)) {
                    items(sortOptions, key = { it.first }) { (code, label) ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    vm.updateTorrentSort(code)
                                    showSortSheet = false
                                }
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = vm.torrentSortMode == code,
                                onClick = {
                                    vm.updateTorrentSort(code)
                                    showSortSheet = false
                                }
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(label, fontSize = 14.sp, color = p.textPrimary)
                        }
                    }
                }
                Spacer(Modifier.height(16.dp))
            }
        }
    }

    whatsLinkTarget?.let { target ->
        WhatsLinkDialog(item = target, onDismiss = { whatsLinkTarget = null })
    }

    threeSourceCode?.let { code ->
        ThreeSourcePreviewDialog(code = code, onDismiss = { threeSourceCode = null })
    }

    bypassTarget?.let { st ->
        CloudflareBypassWebViewDialog(
            status = st,
            onDismiss = { bypassTarget = null },
            onCaptured = { html, cookies ->
                val targetCopy = st
                bypassTarget = null
                vm.ingestCloudflareBypassHtml(targetCopy.sourceId, targetCopy.bypassUrl, html, cookies)
            }
        )
    }

    vm.quickStreamState?.let { qsState ->
        QuickStreamDialog(
            state = qsState,
            onBackground = { vm.dismissQuickStream(notifyBackground = true) },
            onClose = { vm.dismissQuickStream(notifyBackground = false) },
            onWaitMore = { vm.continueQuickStreamWaiting() },
            onOpenTasks = { vm.openPikPakTasksFromQuickStream() },
            onRetry = { vm.retryQuickStream() },
            onSelectVideo = { video -> vm.playQuickStreamCandidate(video) }
        )
    }
}

@Composable
private fun TorrentResultCard(
    item: TorrentItem,
    isChecked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    onWhatsLink: () -> Unit,
    onThreeSource: () -> Unit,
    onCopy: () -> Unit,
    onSendPikPak: () -> Unit,
    onQuickStream: () -> Unit,
    onStreamMagnet: () -> Unit,
    onSearchSubtitle: (String) -> Unit
) {
    val p = AppTheme.palette
    Surface(
        color = p.cardSurface,
        shape = RoundedCornerShape(12.dp),
        border = p.cardBorder,
        tonalElevation = if (p.isAmoled) 0.dp else 2.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Checkbox(
                    checked = isChecked,
                    onCheckedChange = onCheckedChange,
                    modifier = Modifier.size(22.dp)
                )
                Spacer(Modifier.width(8.dp))
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    // Quality & Code Tags
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        if (item.qFlags.isEd2k) SmallBadge("ED2K", Color(0xFFEA580C))
                        if (item.qFlags.is4K) SmallBadge("4K", Color(0xFF2563EB))
                        if (item.qFlags.isSub) SmallBadge(stringResource(R.string.torrent_tag_sub), Color(0xFF16A34A))
                        if (item.qFlags.isCracked) SmallBadge(stringResource(R.string.torrent_tag_unc), Color(0xFFBE123C))
                        if (item.videoCode.isNotBlank()) {
                            SmallBadge(
                                text = stringResource(R.string.torrent_badge_search_sub, item.videoCode),
                                bg = Color(0xFF334155),
                                onClick = { onSearchSubtitle(item.videoCode) }
                            )
                        }
                    }

                    Text(
                        text = item.title,
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.5.sp,
                        color = p.textPrimary,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis
                    )

                    // Source Badges + Metrics
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            modifier = Modifier
                                .weight(1f)
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            item.sources.forEachIndexed { idx, srcLabel ->
                                val sid = item.sourceIds.getOrNull(idx) ?: item.sourceId
                                val col = TorrentRepository.SOURCES.firstOrNull { it.id == sid }?.colorHex ?: 0xFF475569
                                SmallBadge(srcLabel, Color(col))
                            }
                        }

                        Spacer(Modifier.width(6.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = Formatters.formatBytes(item.size) + (if (item.files.isNotBlank()) " (${item.files}f)" else ""),
                                fontSize = 11.5.sp,
                                fontWeight = FontWeight.Bold,
                                color = p.textSecondary
                            )
                            if (item.seeders != null) {
                                Text(
                                    text = "▲${item.seeders} ▼${item.leechers ?: 0}",
                                    fontSize = 11.5.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = p.accentGreen
                                )
                            }
                            if (item.dateMs > 0L) {
                                Text(
                                    text = Formatters.formatDate(item.dateMs),
                                    fontSize = 11.sp,
                                    color = p.textMuted
                                )
                            }
                        }
                    }
                }
            }

            // Action Buttons Row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                ActionPillButton(stringResource(R.string.torrent_btn_check), Color(0xFFBE185D), onWhatsLink)
                if (item.videoCode.isNotBlank()) {
                    ActionPillButton(stringResource(R.string.torrent_btn_3src), Color(0xFF7C3AED), onThreeSource)
                }
                ActionPillButton(stringResource(R.string.torrent_btn_magnet), Color(0xFF059669), onCopy)
                ActionPillButton(stringResource(R.string.torrent_btn_pikpak), Color(0xFF0284C7), onSendPikPak)
                ActionPillButton(stringResource(R.string.torrent_btn_quick_stream), Color(0xFF4F46E5), onQuickStream)
                ActionPillButton(stringResource(R.string.torrent_btn_stream_magnet), Color(0xFFD97706), onStreamMagnet)
            }
        }
    }
}

@Composable
private fun SmallBadge(text: String, bg: Color, onClick: (() -> Unit)? = null) {
    Surface(
        color = bg,
        shape = RoundedCornerShape(5.dp),
        modifier = if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier
    ) {
        Text(
            text = text,
            color = Color.White,
            fontSize = 10.sp,
            fontWeight = FontWeight.ExtraBold,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
        )
    }
}

@Composable
private fun ActionPillButton(label: String, bg: Color, onClick: () -> Unit) {
    Surface(
        color = bg,
        shape = RoundedCornerShape(7.dp),
        modifier = Modifier.clickable(onClick = onClick)
    ) {
        Text(
            text = label,
            color = Color.White,
            fontSize = 11.5.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
        )
    }
}

/* ================= WHATSLINK SCREENSHOT GALLERY DIALOG ================= */
@Composable
private fun WhatsLinkDialog(item: TorrentItem, onDismiss: () -> Unit) {
    val p = AppTheme.palette
    val context = LocalContext.current
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var info by remember { mutableStateOf<WhatsLinkInfo?>(null) }
    var curIdx by remember { mutableIntStateOf(0) }

    LaunchedEffect(item.magnet) {
        loading = true
        error = null
        try {
            info = PreviewRepository.queryWhatsLink(item.magnet)
        } catch (e: Exception) {
            error = e.message ?: "Error"
        } finally {
            loading = false
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            color = p.barSurface,
            shape = RoundedCornerShape(14.dp),
            border = BorderStroke(1.dp, p.borderColor),
            modifier = Modifier
                .fillMaxWidth(0.95f)
                .fillMaxHeight(0.84f)
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(p.cardSurface)
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.whatslink_title), fontSize = 11.sp, color = p.accentBlue, fontWeight = FontWeight.Bold)
                        Text(item.title, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = p.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = stringResource(R.string.cd_close), tint = p.textPrimary)
                    }
                }

                when {
                    loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator()
                            Spacer(Modifier.height(10.dp))
                            Text(stringResource(R.string.whatslink_loading), fontSize = 13.sp, color = p.textSecondary)
                        }
                    }
                    error != null -> Box(Modifier.fillMaxSize().padding(20.dp), contentAlignment = Alignment.Center) {
                        Text(stringResource(R.string.whatslink_error, error.orEmpty()), color = p.accentRed)
                    }
                    info != null -> {
                        val data = info!!
                        val countStr = if (data.count > 0) data.count.toString() else "—"
                        val typePart = if (data.fileType.isNotBlank()) stringResource(R.string.whatslink_type_part, data.fileType) else ""
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Surface(color = p.cardSurface, shape = RoundedCornerShape(8.dp), border = p.cardBorder) {
                                Text(
                                    text = stringResource(
                                        R.string.whatslink_summary,
                                        countStr,
                                        Formatters.formatBytes(if (data.size > 0) data.size else item.size),
                                        typePart
                                    ),
                                    fontSize = 12.sp,
                                    color = p.textPrimary,
                                    modifier = Modifier.padding(10.dp)
                                )
                            }

                            if (data.screenshots.isEmpty()) {
                                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                    Text(
                                        stringResource(R.string.whatslink_no_screenshots),
                                        color = p.textMuted,
                                        fontSize = 13.sp
                                    )
                                }
                            } else {
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(Color(0xFF020617)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    AsyncImage(
                                        model = ImageRequest.Builder(context)
                                            .data(data.screenshots[curIdx])
                                            .crossfade(true)
                                            .build(),
                                        contentDescription = "Screenshot",
                                        contentScale = ContentScale.Fit,
                                        modifier = Modifier.fillMaxSize()
                                    )
                                    if (data.screenshots.size > 1) {
                                        IconButton(
                                            onClick = { curIdx = (curIdx - 1 + data.screenshots.size) % data.screenshots.size },
                                            modifier = Modifier.align(Alignment.CenterStart)
                                        ) {
                                            Icon(Icons.Default.ChevronLeft, null, tint = Color.White, modifier = Modifier.size(34.dp))
                                        }
                                        IconButton(
                                            onClick = { curIdx = (curIdx + 1) % data.screenshots.size },
                                            modifier = Modifier.align(Alignment.CenterEnd)
                                        ) {
                                            Icon(Icons.Default.ChevronRight, null, tint = Color.White, modifier = Modifier.size(34.dp))
                                        }
                                    }
                                    Surface(
                                        color = Color(0xAA0F172A),
                                        shape = RoundedCornerShape(50),
                                        modifier = Modifier
                                            .align(Alignment.BottomEnd)
                                            .padding(8.dp)
                                    ) {
                                        Text(
                                            "${curIdx + 1} / ${data.screenshots.size}",
                                            fontSize = 11.sp,
                                            color = Color.White,
                                            fontWeight = FontWeight.Bold,
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                                        )
                                    }
                                }

                                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    items(data.screenshots.size) { idx ->
                                        AsyncImage(
                                            model = data.screenshots[idx],
                                            contentDescription = null,
                                            contentScale = ContentScale.Crop,
                                            modifier = Modifier
                                                .size(width = 86.dp, height = 52.dp)
                                                .clip(RoundedCornerShape(6.dp))
                                                .clickable { curIdx = idx }
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
}

/* ================= 3-SOURCE IMAGE PREVIEW DIALOG (WITH PINCH-TO-ZOOM) ================= */
@Composable
private fun ThreeSourcePreviewDialog(code: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var loading by remember { mutableStateOf(true) }
    var res by remember { mutableStateOf<ThreeSourceResult?>(null) }
    var scale by remember { mutableFloatStateOf(1f) }
    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }

    fun loadSource(src: String?) {
        scope.launch {
            loading = true
            scale = 1f
            offsetX = 0f
            offsetY = 0f
            res = if (src == null) PreviewRepository.fetchAnyThreeSource(code)
            else PreviewRepository.fetchFromSource(code, src)
            loading = false
        }
    }

    LaunchedEffect(code) { loadSource(null) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            color = Color(0xFF020617),
            modifier = Modifier.fillMaxSize()
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF0F172A))
                        .padding(horizontal = 10.dp, vertical = 8.dp)
                        .horizontalScroll(rememberScrollState()),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(code, fontWeight = FontWeight.ExtraBold, fontSize = 14.sp, color = Color.White)
                    listOf("javfree" to "🟢 javfree", "projectjav" to "🟡 projectjav", "javstore" to "🔴 javstore").forEach { (id, lbl) ->
                        FilterChip(
                            selected = res?.activeSource == id,
                            onClick = { loadSource(id) },
                            label = { Text(lbl, fontSize = 11.5.sp, color = Color.White) }
                        )
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = stringResource(R.string.cd_close), tint = Color.White)
                    }
                }

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(Unit) {
                            detectTransformGestures { _, pan, zoom, _ ->
                                scale = (scale * zoom).coerceIn(1f, 4f)
                                if (scale > 1f) {
                                    offsetX += pan.x
                                    offsetY += pan.y
                                } else {
                                    offsetX = 0f
                                    offsetY = 0f
                                }
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    if (loading) {
                        CircularProgressIndicator()
                    } else if (res?.imageUrl.isNullOrBlank()) {
                        Text(
                            stringResource(
                                R.string.threesrc_not_found,
                                code,
                                res?.activeSource ?: stringResource(R.string.threesrc_all_sources)
                            ),
                            color = Color(0xFF94A3B8),
                            fontSize = 13.sp,
                            modifier = Modifier.padding(24.dp)
                        )
                    } else {
                        val reqBuilder = ImageRequest.Builder(context)
                            .data(res!!.imageUrl)
                            .crossfade(true)
                        if (res!!.referer.isNotBlank()) {
                            reqBuilder.addHeader("Referer", res!!.referer)
                        }
                        AsyncImage(
                            model = reqBuilder.build(),
                            contentDescription = code,
                            contentScale = ContentScale.Fit,
                            modifier = Modifier
                                .fillMaxSize()
                                .graphicsLayer(
                                    scaleX = scale,
                                    scaleY = scale,
                                    translationX = offsetX,
                                    translationY = offsetY
                                )
                        )
                    }
                }
            }
        }
    }
}

/* ================= VISIBLE WEBVIEW DIALOG FOR USER CLOUDFLARE VERIFICATION ================= */
@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun CloudflareBypassWebViewDialog(
    status: SourceStatus,
    onDismiss: () -> Unit,
    onCaptured: (html: String, cookies: String) -> Unit
) {
    val p = AppTheme.palette
    val activityContext = LocalView.current.context
    var webViewRef by remember { mutableStateOf<WebView?>(null) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            color = p.barSurface,
            shape = RoundedCornerShape(14.dp),
            border = BorderStroke(1.dp, p.borderColor),
            modifier = Modifier
                .fillMaxWidth(0.96f)
                .fillMaxHeight(0.88f)
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(p.cardSurface)
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.bypass_dialog_title, status.label), fontWeight = FontWeight.Bold, fontSize = 14.sp, color = p.textPrimary)
                        Text(
                            stringResource(R.string.bypass_dialog_desc),
                            fontSize = 11.sp,
                            color = p.textMuted
                        )
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = stringResource(R.string.cd_close), tint = p.textPrimary)
                    }
                }

                AndroidView(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    factory = {
                        WebView(activityContext).apply {
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            settings.userAgentString = HttpClient.DEFAULT_UA
                            CookieManager.getInstance().setAcceptCookie(true)
                            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                            webChromeClient = WebChromeClient()
                            webViewClient = WebViewClient()
                            loadUrl(status.bypassUrl)
                            webViewRef = this
                        }
                    }
                )

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(p.cardSurface)
                        .padding(10.dp),
                    horizontalArrangement = Arrangement.End
                ) {
                    Button(
                        onClick = {
                            val wv = webViewRef ?: return@Button
                            val curUrl = wv.url ?: status.bypassUrl
                            val cookies = CookieManager.getInstance().getCookie(curUrl).orEmpty()
                            wv.evaluateJavascript("document.documentElement.outerHTML") { rawJsonStr ->
                                val unescaped = try {
                                    org.json.JSONTokener(rawJsonStr).nextValue()?.toString().orEmpty()
                                } catch (_: Exception) {
                                    ""
                                }
                                if (unescaped.isNotBlank()) {
                                    onCaptured(unescaped, cookies)
                                }
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF059669))
                    ) {
                        Text(stringResource(R.string.bypass_btn_capture), fontWeight = FontWeight.Bold, color = Color.White)
                    }
                }
            }
        }
    }
}

@Composable
private fun sortLabelShortComposable(mode: String): String = when (mode) {
    "leechers-desc" -> stringResource(R.string.sort_short_leechers)
    "size-desc" -> stringResource(R.string.sort_short_size_desc)
    "size-asc" -> stringResource(R.string.sort_short_size_asc)
    "date-desc" -> stringResource(R.string.sort_short_date_desc)
    "date-asc" -> stringResource(R.string.sort_short_date_asc)
    "files-desc" -> stringResource(R.string.sort_short_files)
    "sources-desc" -> stringResource(R.string.sort_short_multi)
    "name-asc" -> stringResource(R.string.sort_short_az)
    "name-desc" -> stringResource(R.string.sort_short_za)
    else -> stringResource(R.string.sort_short_seeders)
}

fun copyToClipboard(context: Context, text: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText("TorrentHub", text))
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun QuickStreamDialog(
    state: QuickStreamState,
    onBackground: () -> Unit,
    onClose: () -> Unit,
    onWaitMore: () -> Unit,
    onOpenTasks: () -> Unit,
    onRetry: () -> Unit,
    onSelectVideo: (PikPakDriveItem) -> Unit
) {
    val p = AppTheme.palette
    val isActivelyPolling = state.stage == QuickStreamStage.SENDING_MAGNET ||
        state.stage == QuickStreamStage.CACHING_CLOUD ||
        state.stage == QuickStreamStage.RESOLVING_VIDEO ||
        state.stage == QuickStreamStage.LAUNCHING_PLAYER

    Dialog(
        onDismissRequest = {
            if (isActivelyPolling || state.stage == QuickStreamStage.TIMEOUT) onBackground() else onClose()
        },
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            color = p.barSurface,
            shape = RoundedCornerShape(14.dp),
            border = BorderStroke(1.dp, p.borderColor),
            modifier = Modifier
                .fillMaxWidth(0.94f)
                .wrapContentHeight()
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                // Header
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(p.cardSurface)
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.qs_dialog_title),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = p.accentBlue
                        )
                        Text(
                            text = state.torrentTitle,
                            fontSize = 13.5.sp,
                            fontWeight = FontWeight.Bold,
                            color = p.textPrimary,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    IconButton(
                        onClick = {
                            if (isActivelyPolling || state.stage == QuickStreamStage.TIMEOUT) onBackground() else onClose()
                        }
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = stringResource(R.string.cd_close),
                            tint = p.textPrimary
                        )
                    }
                }

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    when (state.stage) {
                        QuickStreamStage.SENDING_MAGNET,
                        QuickStreamStage.CACHING_CLOUD,
                        QuickStreamStage.RESOLVING_VIDEO,
                        QuickStreamStage.LAUNCHING_PLAYER -> {
                            val stepLabel = when (state.stage) {
                                QuickStreamStage.SENDING_MAGNET -> stringResource(R.string.qs_step_sending)
                                QuickStreamStage.CACHING_CLOUD -> stringResource(
                                    R.string.qs_step_caching,
                                    state.phaseLabel,
                                    state.progress
                                )
                                QuickStreamStage.RESOLVING_VIDEO -> stringResource(R.string.qs_step_resolving)
                                QuickStreamStage.LAUNCHING_PLAYER -> stringResource(R.string.qs_step_launching)
                                else -> ""
                            }

                            Surface(
                                color = p.cardSurface,
                                shape = RoundedCornerShape(10.dp),
                                border = p.cardBorder,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(
                                    modifier = Modifier.padding(12.dp),
                                    verticalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                                    ) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(20.dp),
                                            strokeWidth = 2.5.dp,
                                            color = p.accentBlue
                                        )
                                        Text(
                                            text = stepLabel,
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            color = p.textPrimary,
                                            modifier = Modifier.weight(1f)
                                        )
                                    }

                                    if (state.stage == QuickStreamStage.CACHING_CLOUD && state.progress > 0) {
                                        LinearProgressIndicator(
                                            progress = { (state.progress / 100f).coerceIn(0f, 1f) },
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

                                    if (state.elapsedSec > 0) {
                                        Text(
                                            text = stringResource(R.string.qs_elapsed_sec, state.elapsedSec),
                                            fontSize = 11.5.sp,
                                            color = p.textMuted
                                        )
                                    }
                                }
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.End,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                TextButton(onClick = onBackground) {
                                    Text(stringResource(R.string.qs_btn_background), fontSize = 12.5.sp)
                                }
                                Spacer(Modifier.width(6.dp))
                                OutlinedButton(
                                    onClick = onOpenTasks,
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                                ) {
                                    Text(stringResource(R.string.qs_btn_open_tasks), fontSize = 12.sp)
                                }
                            }
                        }

                        QuickStreamStage.SELECT_VIDEO -> {
                            Text(
                                text = stringResource(R.string.qs_select_video_title, state.candidateVideos.size),
                                fontSize = 13.5.sp,
                                fontWeight = FontWeight.Bold,
                                color = p.accentGreen
                            )
                            Text(
                                text = stringResource(R.string.qs_select_video_hint),
                                fontSize = 12.sp,
                                color = p.textSecondary
                            )

                            val rowBorder = if (p.isLight || p.isAmoled) BorderStroke(1.dp, p.borderColor) else null
                            LazyColumn(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(max = 320.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                items(state.candidateVideos, key = { it.id }) { vid ->
                                    val isBusy = state.busyVideoId == vid.id
                                    Surface(
                                        color = p.subCardSurface,
                                        shape = RoundedCornerShape(10.dp),
                                        border = rowBorder,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable(enabled = state.busyVideoId == null) { onSelectVideo(vid) }
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Movie,
                                                contentDescription = null,
                                                tint = p.accentBlue,
                                                modifier = Modifier.size(20.dp)
                                            )
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(
                                                    text = vid.name,
                                                    fontSize = 12.5.sp,
                                                    fontWeight = FontWeight.SemiBold,
                                                    color = p.textPrimary,
                                                    maxLines = 2,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                                Text(
                                                    text = Formatters.formatBytes(vid.size),
                                                    fontSize = 11.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    color = p.accentBlue
                                                )
                                            }
                                            if (isBusy) {
                                                CircularProgressIndicator(
                                                    modifier = Modifier.size(18.dp),
                                                    strokeWidth = 2.dp,
                                                    color = p.accentGreen
                                                )
                                            } else {
                                                Icon(
                                                    imageVector = Icons.Default.PlayCircle,
                                                    contentDescription = null,
                                                    tint = p.accentGreen,
                                                    modifier = Modifier.size(24.dp)
                                                )
                                            }
                                        }
                                    }
                                }
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.End
                            ) {
                                TextButton(onClick = onClose) {
                                    Text(stringResource(R.string.common_close), fontSize = 12.5.sp)
                                }
                            }
                        }

                        QuickStreamStage.TIMEOUT -> {
                            Surface(
                                color = p.cardSurface,
                                shape = RoundedCornerShape(10.dp),
                                border = p.cardBorder,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(
                                    modifier = Modifier.padding(12.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Text(
                                        text = stringResource(R.string.qs_timeout_title, state.progress),
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = p.accentAmber
                                    )
                                    Text(
                                        text = stringResource(R.string.qs_timeout_desc),
                                        fontSize = 12.sp,
                                        color = p.textSecondary
                                    )
                                    LinearProgressIndicator(
                                        progress = { (state.progress / 100f).coerceIn(0f, 1f) },
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(5.dp)
                                            .clip(RoundedCornerShape(50)),
                                        color = p.accentAmber,
                                        trackColor = p.chipBg
                                    )
                                }
                            }

                            FlowRow(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.End,
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                TextButton(onClick = onBackground) {
                                    Text(stringResource(R.string.qs_btn_background), fontSize = 12.sp)
                                }
                                OutlinedButton(
                                    onClick = onOpenTasks,
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                                    modifier = Modifier.padding(horizontal = 4.dp)
                                ) {
                                    Text(stringResource(R.string.qs_btn_open_tasks), fontSize = 12.sp)
                                }
                                Button(
                                    onClick = onWaitMore,
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4F46E5)),
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                                ) {
                                    Text(stringResource(R.string.qs_btn_wait_more), fontSize = 12.sp, color = Color.White)
                                }
                            }
                        }

                        QuickStreamStage.ERROR -> {
                            Surface(
                                color = p.cardSurface,
                                shape = RoundedCornerShape(10.dp),
                                border = p.cardBorder,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    text = "⚠️ ${state.errorMessage}",
                                    fontSize = 12.5.sp,
                                    color = p.accentRed,
                                    modifier = Modifier.padding(12.dp)
                                )
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.End,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                TextButton(onClick = onClose) {
                                    Text(stringResource(R.string.common_close), fontSize = 12.5.sp)
                                }
                                Spacer(Modifier.width(6.dp))
                                Button(
                                    onClick = onRetry,
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0284C7)),
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                                ) {
                                    Text(stringResource(R.string.qs_btn_retry), fontSize = 12.sp, color = Color.White)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun openMagnetExternal(context: Context, link: String, vm: AppViewModel) {
    ExternalPlayerHelper.streamMagnetWithChooser(context, link, vm)
}
