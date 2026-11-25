package com.torrenthub.aio.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.RssFeed
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.torrenthub.aio.R
import com.torrenthub.aio.data.RssRepository
import com.torrenthub.aio.data.SubtitleRepository
import com.torrenthub.aio.model.RssFeedItem
import com.torrenthub.aio.model.SubtitleItem
import com.torrenthub.aio.model.SubtitleProvider
import com.torrenthub.aio.model.TorrentItem
import com.torrenthub.aio.util.ExternalPlayerHelper
import com.torrenthub.aio.util.Formatters

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RssMonitorSection(vm: AppViewModel) {
    val p = AppTheme.palette
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current

    var showFeedsManager by remember { mutableStateOf(false) }
    var showKeywordsDialog by remember { mutableStateOf(false) }
    var quickSubTarget by remember { mutableStateOf<RssFeedItem?>(null) }

    LaunchedEffect(Unit) {
        if (vm.rssItems.isEmpty() && !vm.isLoadingRss && vm.rssFeeds.any { it.enabled }) {
            vm.refreshRssFeeds(silent = true)
        }
    }

    val displayed = remember(
        vm.rssItems.size,
        vm.rssItems.toList(),
        vm.rssOnlyMatched,
        vm.rssSelectedFeedId,
        vm.rssSearchFilter
    ) {
        vm.filteredRssItems()
    }

    val enabledFeedCount = vm.rssFeeds.count { it.enabled }
    val matchedTotal = vm.rssMatchedCount

    Column(modifier = Modifier.fillMaxSize()) {
        // RSS Control Strip
        Surface(
            color = p.barSurface,
            tonalElevation = if (p.isAmoled) 0.dp else 2.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Action Chips + Refresh Row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        modifier = Modifier
                            .weight(1f)
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        AssistChip(
                            onClick = { showFeedsManager = true },
                            leadingIcon = { Icon(Icons.Default.RssFeed, contentDescription = null, modifier = Modifier.size(16.dp)) },
                            label = { Text(stringResource(R.string.rss_btn_manage_feeds, enabledFeedCount), fontSize = 11.5.sp) }
                        )
                        AssistChip(
                            onClick = { showKeywordsDialog = true },
                            label = { Text(stringResource(R.string.rss_btn_keywords, vm.rssWatchKeywords.size), fontSize = 11.5.sp) }
                        )
                    }

                    Spacer(Modifier.width(6.dp))

                    Button(
                        onClick = { vm.refreshRssFeeds(silent = false) },
                        enabled = !vm.isLoadingRss,
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                        shape = RoundedCornerShape(9.dp)
                    ) {
                        if (vm.isLoadingRss) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(15.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary
                            )
                        } else {
                            Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                        }
                        Spacer(Modifier.width(4.dp))
                        Text(stringResource(R.string.rss_btn_refresh), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }

                // Filter Chips: All vs Matched + Individual Feed Chips
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    FilterChip(
                        selected = !vm.rssOnlyMatched,
                        onClick = { vm.rssOnlyMatched = false },
                        label = { Text(stringResource(R.string.rss_filter_all, vm.rssItems.size), fontSize = 11.5.sp) }
                    )
                    FilterChip(
                        selected = vm.rssOnlyMatched,
                        onClick = { vm.rssOnlyMatched = !vm.rssOnlyMatched },
                        label = {
                            Text(
                                text = stringResource(R.string.rss_filter_matched, matchedTotal),
                                fontSize = 11.5.sp,
                                fontWeight = if (matchedTotal > 0) FontWeight.ExtraBold else FontWeight.Medium
                            )
                        }
                    )
                    vm.rssFeeds.filter { it.enabled }.forEach { feed ->
                        FilterChip(
                            selected = vm.rssSelectedFeedId == feed.id,
                            onClick = {
                                vm.rssSelectedFeedId = if (vm.rssSelectedFeedId == feed.id) "all" else feed.id
                            },
                            label = { Text(feed.title, fontSize = 11.5.sp) }
                        )
                    }
                }

                // Quick Title Filter Input
                if (vm.rssItems.isNotEmpty() || vm.rssSearchFilter.isNotEmpty()) {
                    OutlinedTextField(
                        value = vm.rssSearchFilter,
                        onValueChange = { vm.rssSearchFilter = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 44.dp),
                        singleLine = true,
                        placeholder = { Text(stringResource(R.string.rss_search_placeholder), fontSize = 12.sp) },
                        trailingIcon = {
                            if (vm.rssSearchFilter.isNotEmpty()) {
                                IconButton(onClick = { vm.rssSearchFilter = "" }, modifier = Modifier.size(26.dp)) {
                                    Icon(Icons.Default.Clear, contentDescription = stringResource(R.string.cd_clear), modifier = Modifier.size(16.dp))
                                }
                            }
                        },
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
                        textStyle = LocalTextStyle.current.copy(fontSize = 12.sp),
                        shape = RoundedCornerShape(9.dp)
                    )
                }
            }
        }

        if (p.isLight || p.isAmoled) {
            HorizontalDivider(color = p.borderColor, thickness = 1.dp)
        }

        // Feed Items List
        if (displayed.isEmpty()) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(28.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = Icons.Default.RssFeed,
                        contentDescription = null,
                        tint = p.textMuted,
                        modifier = Modifier.size(52.dp)
                    )
                    Spacer(Modifier.height(10.dp))
                    val emptyMsg = when {
                        vm.isLoadingRss -> stringResource(R.string.rss_empty_loading)
                        enabledFeedCount == 0 -> stringResource(R.string.rss_empty_no_feeds)
                        else -> stringResource(R.string.rss_empty_no_match)
                    }
                    Text(emptyMsg, color = p.textMuted, fontSize = 13.sp)
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentPadding = PaddingValues(10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(displayed, key = { it.id }) { item ->
                    RssItemCard(
                        item = item,
                        onCopyMagnet = {
                            val enriched = vm.prepareMagnet(item.magnet)
                            copyToClipboard(context, enriched)
                            vm.emitToast(vm.str(R.string.toast_link_copied, item.title.take(42)))
                        },
                        onQuickStream = {
                            vm.startQuickStream(
                                TorrentItem(
                                    title = item.title,
                                    magnet = item.magnet,
                                    hash = item.infoHash,
                                    size = item.sizeBytes,
                                    seeders = item.seeders,
                                    leechers = item.leechers,
                                    videoCode = item.videoCode
                                )
                            )
                        },
                        onSendPikPak = {
                            vm.sendMagnetsToPikPak(listOf(item.magnet))
                        },
                        onStreamExternal = {
                            ExternalPlayerHelper.streamMagnetWithChooser(context, item.magnet, vm)
                        },
                        onDownloadSubtitle = {
                            quickSubTarget = item
                        },
                        onDownloadTorrentFile = {
                            if (item.torrentUrl.isNotBlank()) {
                                vm.startBrowserFileDownload(
                                    url = item.torrentUrl,
                                    userAgent = null,
                                    contentDisposition = null,
                                    mimeType = "application/x-bittorrent",
                                    contentLength = 0L,
                                    referer = item.pageUrl
                                )
                            }
                        }
                    )
                }
            }
        }
    }

    if (showFeedsManager) {
        RssFeedsManagerDialog(
            vm = vm,
            onDismiss = { showFeedsManager = false }
        )
    }

    if (showKeywordsDialog) {
        RssKeywordsDialog(
            vm = vm,
            onDismiss = { showKeywordsDialog = false }
        )
    }

    quickSubTarget?.let { target ->
        RssQuickSubtitleDialog(
            vm = vm,
            rssItem = target,
            onDismiss = { quickSubTarget = null }
        )
    }
}

@Composable
private fun RssItemCard(
    item: RssFeedItem,
    onCopyMagnet: () -> Unit,
    onQuickStream: () -> Unit,
    onSendPikPak: () -> Unit,
    onStreamExternal: () -> Unit,
    onDownloadSubtitle: () -> Unit,
    onDownloadTorrentFile: () -> Unit
) {
    val p = AppTheme.palette
    val cardBorder = when {
        item.isMatched -> BorderStroke(1.5.dp, p.accentGreen)
        else -> p.cardBorder
    }

    Surface(
        color = p.cardSurface,
        shape = RoundedCornerShape(12.dp),
        border = cardBorder,
        tonalElevation = if (p.isAmoled) 0.dp else 2.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(11.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            // Match Highlight Banner + Tags
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(5.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (item.isMatched) {
                    RssTagBadge(
                        text = stringResource(R.string.rss_match_badge, item.matchedKeywords.joinToString(", ")),
                        bg = Color(0xFF15803D)
                    )
                }
                RssTagBadge(item.feedTitle, Color(0xFF0369A1))
                if (item.qFlags.is4K) RssTagBadge("4K", Color(0xFF2563EB))
                if (item.qFlags.isSub) RssTagBadge(stringResource(R.string.torrent_tag_sub), Color(0xFF0D9488))
                if (item.qFlags.isCracked) RssTagBadge(stringResource(R.string.torrent_tag_unc), Color(0xFFBE123C))
                if (item.videoCode.isNotBlank()) RssTagBadge(item.videoCode, Color(0xFF475569))
            }

            Text(
                text = item.title,
                fontWeight = FontWeight.Bold,
                fontSize = 13.5.sp,
                color = p.textPrimary,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )

            // Metrics Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = Formatters.formatBytes(item.sizeBytes),
                    fontSize = 11.5.sp,
                    fontWeight = FontWeight.Bold,
                    color = p.textSecondary
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (item.seeders != null) {
                        Text(
                            text = "▲${item.seeders} ▼${item.leechers ?: 0}",
                            fontSize = 11.5.sp,
                            fontWeight = FontWeight.Bold,
                            color = p.accentGreen
                        )
                    }
                    if (item.pubDateMs > 0L) {
                        Text(
                            text = Formatters.formatDate(item.pubDateMs),
                            fontSize = 11.sp,
                            color = p.textMuted
                        )
                    }
                }
            }

            // Direct Action Row: Copy Magnet, Quick Stream PikPak, PikPak Cloud, Download Subtitle
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                if (item.magnet.isNotBlank()) {
                    RssActionButton(stringResource(R.string.rss_btn_copy_magnet), Color(0xFF059669), onCopyMagnet)
                    RssActionButton(stringResource(R.string.rss_btn_quick_stream), Color(0xFF4F46E5), onQuickStream)
                    RssActionButton(stringResource(R.string.torrent_btn_pikpak), Color(0xFF0284C7), onSendPikPak)
                    RssActionButton(stringResource(R.string.torrent_btn_stream_magnet), Color(0xFFD97706), onStreamExternal)
                }
                if (item.torrentUrl.isNotBlank() && item.magnet.isBlank()) {
                    RssActionButton(stringResource(R.string.rss_btn_dl_torrent), Color(0xFF0284C7), onDownloadTorrentFile)
                }
                RssActionButton(stringResource(R.string.rss_btn_download_sub), Color(0xFF7C3AED), onDownloadSubtitle)
            }
        }
    }
}

@Composable
private fun RssTagBadge(text: String, bg: Color) {
    Surface(color = bg, shape = RoundedCornerShape(5.dp)) {
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
private fun RssActionButton(label: String, bg: Color, onClick: () -> Unit) {
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RssFeedsManagerDialog(
    vm: AppViewModel,
    onDismiss: () -> Unit
) {
    val p = AppTheme.palette
    var nameInput by remember { mutableStateOf("") }
    var urlInput by remember { mutableStateOf("") }

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
                .fillMaxHeight(0.86f)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.rss_feeds_dialog_title),
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 15.5.sp,
                        color = p.textPrimary,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = stringResource(R.string.cd_close), tint = p.textPrimary)
                    }
                }

                // Add New RSS Feed Form
                Surface(color = p.cardSurface, shape = RoundedCornerShape(10.dp), border = p.cardBorder) {
                    Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = nameInput,
                            onValueChange = { nameInput = it },
                            singleLine = true,
                            label = { Text(stringResource(R.string.rss_feeds_name_label), fontSize = 12.sp) },
                            modifier = Modifier.fillMaxWidth(),
                            textStyle = LocalTextStyle.current.copy(fontSize = 12.5.sp)
                        )
                        OutlinedTextField(
                            value = urlInput,
                            onValueChange = { urlInput = it },
                            singleLine = true,
                            label = { Text(stringResource(R.string.rss_feeds_url_label), fontSize = 12.sp) },
                            placeholder = { Text("https://.../rss.xml", fontSize = 12.sp) },
                            modifier = Modifier.fillMaxWidth(),
                            textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                        )
                        Button(
                            onClick = {
                                if (urlInput.isNotBlank()) {
                                    vm.addRssFeed(nameInput, urlInput)
                                    nameInput = ""
                                    urlInput = ""
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF059669))
                        ) {
                            Text(stringResource(R.string.rss_feeds_btn_add), color = Color.White, fontWeight = FontWeight.Bold)
                        }

                        Text(
                            text = stringResource(R.string.rss_feeds_presets_label),
                            fontSize = 11.sp,
                            color = p.textMuted
                        )
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            RssRepository.PRESET_FEEDS.forEach { (lbl, presetUrl) ->
                                val alreadyAdded = vm.rssFeeds.any { it.url.equals(presetUrl, ignoreCase = true) }
                                FilterChip(
                                    selected = alreadyAdded,
                                    onClick = {
                                        if (!alreadyAdded) vm.addRssFeed(lbl, presetUrl)
                                    },
                                    label = { Text(lbl, fontSize = 11.sp) }
                                )
                            }
                        }
                    }
                }

                //Configured Feeds List
                LazyColumn(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items(vm.rssFeeds, key = { it.id }) { feed ->
                        Surface(color = p.cardSurface, shape = RoundedCornerShape(10.dp), border = p.cardBorder) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = feed.title,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 13.sp,
                                        color = p.textPrimary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = feed.url,
                                        fontSize = 11.sp,
                                        color = p.textMuted,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    if (feed.lastError.isNotBlank()) {
                                        Text(
                                            text = "⚠️ ${feed.lastError.take(60)}",
                                            fontSize = 10.5.sp,
                                            color = p.accentRed,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    } else if (feed.lastSyncMs > 0L) {
                                        Text(
                                            text = "✓ ${feed.lastItemCount} items · ${Formatters.formatDate(feed.lastSyncMs)}",
                                            fontSize = 10.5.sp,
                                            color = p.accentGreen
                                        )
                                    }
                                }
                                Switch(
                                    checked = feed.enabled,
                                    onCheckedChange = { vm.toggleRssFeed(feed.id, it) }
                                )
                                IconButton(
                                    onClick = { vm.removeRssFeed(feed.id) },
                                    modifier = Modifier.size(32.dp)
                                ) {
                                    Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.cd_delete), tint = p.accentRed, modifier = Modifier.size(18.dp))
                                }
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
private fun RssKeywordsDialog(
    vm: AppViewModel,
    onDismiss: () -> Unit
) {
    val p = AppTheme.palette
    var kwInput by remember { mutableStateOf("") }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            color = p.barSurface,
            shape = RoundedCornerShape(14.dp),
            border = BorderStroke(1.dp, p.borderColor),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.rss_keywords_dialog_title),
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 15.sp,
                        color = p.textPrimary,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = onDismiss, modifier = Modifier.size(28.dp)) {
                        Icon(Icons.Default.Close, contentDescription = stringResource(R.string.cd_close), tint = p.textPrimary)
                    }
                }

                Text(
                    text = stringResource(R.string.rss_keywords_desc),
                    fontSize = 12.sp,
                    color = p.textMuted
                )

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = kwInput,
                        onValueChange = { kwInput = it },
                        singleLine = true,
                        placeholder = { Text(stringResource(R.string.rss_keywords_input_placeholder), fontSize = 12.sp) },
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = {
                            if (kwInput.isNotBlank()) {
                                vm.addRssWatchKeyword(kwInput)
                                kwInput = ""
                            }
                        }),
                        modifier = Modifier.weight(1f),
                        textStyle = LocalTextStyle.current.copy(fontSize = 12.5.sp)
                    )
                    Button(
                        onClick = {
                            if (kwInput.isNotBlank()) {
                                vm.addRssWatchKeyword(kwInput)
                                kwInput = ""
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF059669))
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp), tint = Color.White)
                        Spacer(Modifier.width(4.dp))
                        Text(stringResource(R.string.rss_keywords_btn_add), color = Color.White, fontSize = 12.sp)
                    }
                }

                if (vm.rssWatchKeywords.isEmpty()) {
                    Text(
                        text = stringResource(R.string.rss_keywords_empty),
                        fontSize = 12.sp,
                        color = p.textMuted
                    )
                } else {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        vm.rssWatchKeywords.forEach { kw ->
                            InputChip(
                                selected = true,
                                onClick = { vm.removeRssWatchKeyword(kw) },
                                label = { Text(kw, fontSize = 12.sp, fontWeight = FontWeight.SemiBold) },
                                trailingIcon = {
                                    Icon(Icons.Default.Close, contentDescription = stringResource(R.string.cd_delete), modifier = Modifier.size(15.dp))
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
private fun RssQuickSubtitleDialog(
    vm: AppViewModel,
    rssItem: RssFeedItem,
    onDismiss: () -> Unit
) {
    val p = AppTheme.palette
    val initialQuery = remember(rssItem.id) {
        RssRepository.cleanTitleForSubtitleSearch(rssItem.title, rssItem.videoCode)
    }
    var loading by remember { mutableStateOf(true) }
    var subItems by remember { mutableStateOf<List<SubtitleItem>>(emptyList()) }

    LaunchedEffect(initialQuery) {
        loading = true
        subItems = runCatching {
            SubtitleRepository.searchSubtitles(initialQuery, SubtitleProvider.ALL, vm.subtitleTargetLang.code)
        }.getOrDefault(emptyList())
        loading = false
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
                .fillMaxWidth(0.94f)
                .heightIn(max = 520.dp)
        ) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.rss_sub_dialog_title, initialQuery),
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 14.5.sp,
                        color = p.textPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = onDismiss, modifier = Modifier.size(28.dp)) {
                        Icon(Icons.Default.Close, contentDescription = stringResource(R.string.cd_close), tint = p.textPrimary)
                    }
                }

                when {
                    loading -> {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 20.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                            Text(
                                text = stringResource(R.string.rss_sub_dialog_searching, initialQuery),
                                fontSize = 12.5.sp,
                                color = p.textSecondary
                            )
                        }
                    }

                    subItems.isEmpty() -> {
                        Text(
                            text = stringResource(R.string.rss_sub_dialog_empty, initialQuery),
                            fontSize = 12.5.sp,
                            color = p.textMuted,
                            modifier = Modifier.padding(vertical = 12.dp)
                        )
                    }

                    else -> {
                        LazyColumn(
                            modifier = Modifier
                                .weight(1f, fill = false)
                                .heightIn(max = 340.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            items(subItems.take(25), key = { it.idKey }) { sub ->
                                Surface(color = p.cardSurface, shape = RoundedCornerShape(9.dp), border = p.cardBorder) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(10.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = sub.name,
                                                fontSize = 12.5.sp,
                                                fontWeight = FontWeight.SemiBold,
                                                color = p.textPrimary,
                                                maxLines = 2,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                            Text(
                                                text = "${sub.sourceName} · ${sub.ext.uppercase()}" +
                                                    (if (sub.language.isNotBlank()) " · ${sub.language}" else ""),
                                                fontSize = 10.5.sp,
                                                color = p.textMuted
                                            )
                                        }
                                        Spacer(Modifier.width(8.dp))
                                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                            Surface(
                                                color = Color(0xFF059669),
                                                shape = RoundedCornerShape(7.dp),
                                                modifier = Modifier.clickable {
                                                    vm.downloadSubtitleViaManager(sub.name, sub.url)
                                                    onDismiss()
                                                }
                                            ) {
                                                Text(
                                                    text = stringResource(R.string.rss_sub_btn_download_now),
                                                    color = Color.White,
                                                    fontSize = 11.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    modifier = Modifier.padding(horizontal = 9.dp, vertical = 6.dp)
                                                )
                                            }
                                            Surface(
                                                color = Color(0xFF7C3AED),
                                                shape = RoundedCornerShape(7.dp),
                                                modifier = Modifier.clickable {
                                                    onDismiss()
                                                    vm.sendSubtitleItemToGemini(sub)
                                                }
                                            ) {
                                                Text(
                                                    text = stringResource(R.string.sub_btn_gemini),
                                                    color = Color.White,
                                                    fontSize = 11.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    modifier = Modifier.padding(horizontal = 9.dp, vertical = 6.dp)
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    OutlinedButton(
                        onClick = {
                            onDismiss()
                            vm.currentTab = 1
                            vm.runSubtitleSearch(initialQuery)
                        }
                    ) {
                        Text(stringResource(R.string.rss_sub_btn_open_tab), fontSize = 12.sp)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MagnetBuilderDialog(
    vm: AppViewModel,
    onDismiss: () -> Unit
) {
    val p = AppTheme.palette
    val context = LocalContext.current
    var hashInput by remember { mutableStateOf("") }
    var nameInput by remember { mutableStateOf("") }

    val trackerCount = if (vm.autoAppendTrackers) vm.activeTrackersList.size else 0
    val builtMagnet = remember(hashInput, nameInput, vm.autoAppendTrackers, vm.activeTrackersList) {
        vm.buildCustomMagnet(hashInput, nameInput)
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            color = p.barSurface,
            shape = RoundedCornerShape(14.dp),
            border = BorderStroke(1.dp, p.borderColor),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.magnet_builder_title),
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 15.sp,
                        color = p.textPrimary,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = onDismiss, modifier = Modifier.size(28.dp)) {
                        Icon(Icons.Default.Close, contentDescription = stringResource(R.string.cd_close), tint = p.textPrimary)
                    }
                }

                Text(
                    text = stringResource(R.string.magnet_builder_desc, trackerCount),
                    fontSize = 11.5.sp,
                    color = p.textMuted
                )

                OutlinedTextField(
                    value = hashInput,
                    onValueChange = { hashInput = it },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2,
                    maxLines = 3,
                    label = { Text(stringResource(R.string.magnet_builder_hash_label), fontSize = 12.sp) },
                    placeholder = { Text(stringResource(R.string.magnet_builder_hash_placeholder), fontSize = 11.5.sp) },
                    textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace, fontSize = 11.5.sp)
                )

                OutlinedTextField(
                    value = nameInput,
                    onValueChange = { nameInput = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text(stringResource(R.string.magnet_builder_name_label), fontSize = 12.sp) },
                    textStyle = LocalTextStyle.current.copy(fontSize = 12.5.sp)
                )

                if (builtMagnet.isNotBlank()) {
                    Surface(
                        color = p.subCardSurface,
                        shape = RoundedCornerShape(8.dp),
                        border = BorderStroke(1.dp, p.borderColor),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                text = stringResource(R.string.magnet_builder_preview_label, trackerCount),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = p.accentBlue
                            )
                            Text(
                                text = builtMagnet,
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace,
                                color = p.textSecondary,
                                maxLines = 4,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }

                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Button(
                            onClick = {
                                copyToClipboard(context, builtMagnet)
                                vm.emitToast(vm.str(R.string.toast_link_copied, builtMagnet.take(40)))
                                onDismiss()
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF059669)),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            Text("📋 ${stringResource(R.string.common_copy)}", fontSize = 12.sp, color = Color.White)
                        }
                        Button(
                            onClick = {
                                vm.sendMagnetsToPikPak(listOf(builtMagnet))
                                onDismiss()
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0284C7)),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            Text(stringResource(R.string.torrent_btn_pikpak), fontSize = 12.sp, color = Color.White)
                        }
                        OutlinedButton(
                            onClick = {
                                ExternalPlayerHelper.streamMagnetWithChooser(context, builtMagnet, vm)
                                onDismiss()
                            },
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            Text(stringResource(R.string.torrent_btn_stream_magnet), fontSize = 12.sp)
                        }
                    }
                } else if (hashInput.isNotBlank()) {
                    Text(
                        text = stringResource(R.string.magnet_builder_invalid),
                        fontSize = 11.5.sp,
                        color = p.accentRed
                    )
                }
            }
        }
    }
}
