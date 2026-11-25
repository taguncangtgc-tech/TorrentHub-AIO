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
import com.torrenthub.aio.data.PreviewRepository
import com.torrenthub.aio.data.TorrentRepository
import com.torrenthub.aio.model.PillState
import com.torrenthub.aio.model.SourceStatus
import com.torrenthub.aio.model.ThreeSourceResult
import com.torrenthub.aio.model.TorrentItem
import com.torrenthub.aio.model.WhatsLinkInfo
import com.torrenthub.aio.network.HttpClient
import com.torrenthub.aio.util.Formatters
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TorrentScreen(vm: AppViewModel) {
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
            color = Color(0xFF0F172A),
            tonalElevation = 3.dp
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
                        placeholder = { Text("Cari judul, kode (IPX-090, FC2-...), film…", fontSize = 13.sp) },
                        trailingIcon = {
                            if (vm.torrentQuery.isNotEmpty()) {
                                IconButton(onClick = { vm.torrentQuery = "" }) {
                                    Icon(Icons.Default.Clear, contentDescription = "Hapus")
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
                            Icon(Icons.Default.Search, contentDescription = "Cari")
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
                        label = { Text("Sumber ($activeSourceCount/16)", fontSize = 12.sp) },
                        leadingIcon = { Icon(Icons.Default.Tune, null, Modifier.size(16.dp)) }
                    )
                    AssistChip(
                        onClick = { showSortSheet = true },
                        label = { Text(sortLabelShort(vm.torrentSortMode), fontSize = 12.sp) },
                        leadingIcon = { Icon(Icons.Default.Sort, null, Modifier.size(16.dp)) }
                    )
                    FilterChip(
                        selected = vm.hideDeadTorrents,
                        onClick = { vm.updateHideDead(!vm.hideDeadTorrents) },
                        label = { Text("Tanpa 0 SE", fontSize = 12.sp) }
                    )
                    listOf(
                        "4k" to "4K",
                        "sub" to "Sub/中字",
                        "unc" to "Uncensored",
                        "multi" to "Multi-Sumber"
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
                            placeholder = { Text("Filter hasil (mis: 1080p, hevc)…", fontSize = 12.sp) },
                            textStyle = LocalTextStyle.current.copy(fontSize = 12.sp),
                            shape = RoundedCornerShape(8.dp)
                        )
                        Text(
                            text = "${displayed.size}/${vm.allTorrents.size} hasil",
                            fontSize = 11.sp,
                            color = Color(0xFF94A3B8),
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }

                // Per-site Status Pills
                if (vm.sourceStatuses.isNotEmpty()) {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(vm.sourceStatuses, key = { it.sourceId }) { st ->
                            val (bg, fg) = when (st.state) {
                                PillState.LOADING -> Color(0xFF422006) to Color(0xFFFDE68A)
                                PillState.OK -> Color(0xFF052E16) to Color(0xFF86EFAC)
                                PillState.ERROR -> Color(0xFF450A0A) to Color(0xFFFCA5A5)
                            }
                            val text = when (st.state) {
                                PillState.LOADING -> "${st.label}: ${st.message.ifBlank { "…" }}"
                                PillState.OK -> "${st.label}: ${st.count}"
                                PillState.ERROR -> if (st.canBypassCloudflare) "${st.label}: 403 · 🔓 Bypass" else "${st.label}: ${st.message.take(18)}"
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
                        tint = Color(0xFF475569),
                        modifier = Modifier.size(54.dp)
                    )
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = if (vm.isSearchingTorrents) "Sedang mencari ke semua sumber torrent aktif…"
                        else "Masukkan kata kunci untuk mencari torrent dari 16 situs sekaligus.",
                        color = Color(0xFF94A3B8),
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
                                vm.emitToast("📋 Tautan disalin: ${item.title.take(45)}")
                            },
                            onSendPikPak = { vm.sendMagnetsToPikPak(listOf(item.magnet)) },
                            onOpenExternal = { openMagnetExternal(context, item.magnet, vm) },
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
                    color = Color(0xFF1E293B),
                    shadowElevation = 10.dp,
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
                            "${selectedList.size} dipilih",
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp,
                            color = Color.White
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            TextButton(onClick = { vm.selectedMagnets.clear() }) {
                                Text("Batal", fontSize = 12.sp)
                            }
                            Button(
                                onClick = {
                                    copyToClipboard(context, selectedList.joinToString("\n"))
                                    vm.emitToast("📋 ${selectedList.size} tautan berhasil disalin!")
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF059669)),
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                            ) {
                                Text("Salin", fontSize = 12.sp)
                            }
                            Button(
                                onClick = { vm.sendMagnetsToPikPak(selectedList) },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0284C7)),
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                            ) {
                                Text("☁️ PikPak (${selectedList.size})", fontSize = 12.sp)
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
            containerColor = Color(0xFF0F172A)
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
                    Text("Pilih Sumber Torrent (16 Situs)", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    Row {
                        TextButton(onClick = { vm.setAllSources(true) }) { Text("Semua") }
                        TextButton(onClick = { vm.setAllSources(false) }) { Text("Reset") }
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
                            Text(src.label, fontSize = 14.sp, modifier = Modifier.weight(1f))
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
            containerColor = Color(0xFF0F172A)
        ) {
            val sortOptions = listOf(
                "seeders-desc" to "Seeder Terbanyak",
                "leechers-desc" to "Leecher Terbanyak",
                "size-desc" to "Ukuran Terbesar",
                "size-asc" to "Ukuran Terkecil",
                "date-desc" to "Tanggal Terbaru",
                "date-asc" to "Tanggal Terlama",
                "files-desc" to "Jumlah File Terbanyak",
                "sources-desc" to "Multi-Sumber Terbanyak",
                "name-asc" to "Nama A → Z",
                "name-desc" to "Nama Z → A"
            )
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                Text("Urutkan Hasil (Sort By)", fontWeight = FontWeight.Bold, fontSize = 16.sp)
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
                            Text(label, fontSize = 14.sp)
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
    onOpenExternal: () -> Unit,
    onSearchSubtitle: (String) -> Unit
) {
    Surface(
        color = Color(0xFF1E293B),
        shape = RoundedCornerShape(12.dp),
        tonalElevation = 2.dp,
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
                        if (item.qFlags.isSub) SmallBadge("Sub/中字", Color(0xFF16A34A))
                        if (item.qFlags.isCracked) SmallBadge("Uncensored", Color(0xFFBE123C))
                        if (item.videoCode.isNotBlank()) {
                            SmallBadge(
                                text = "🎬 ${item.videoCode} · Cari Sub",
                                bg = Color(0xFF334155),
                                onClick = { onSearchSubtitle(item.videoCode) }
                            )
                        }
                    }

                    Text(
                        text = item.title,
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.5.sp,
                        color = Color(0xFFF8FAFC),
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
                                color = Color(0xFFCBD5E1)
                            )
                            if (item.seeders != null) {
                                Text(
                                    text = "▲${item.seeders} ▼${item.leechers ?: 0}",
                                    fontSize = 11.5.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFF4ADE80)
                                )
                            }
                            if (item.dateMs > 0L) {
                                Text(
                                    text = Formatters.formatDate(item.dateMs),
                                    fontSize = 11.sp,
                                    color = Color(0xFF94A3B8)
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
                ActionPillButton("📸 Cek", Color(0xFFBE185D), onWhatsLink)
                if (item.videoCode.isNotBlank()) {
                    ActionPillButton("🖼️ 3-Src", Color(0xFF7C3AED), onThreeSource)
                }
                ActionPillButton("📋 Magnet", Color(0xFF059669), onCopy)
                ActionPillButton("☁️ PikPak", Color(0xFF0284C7), onSendPikPak)
                ActionPillButton("🧲 Buka", Color(0xFFD97706), onOpenExternal)
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
            error = e.message ?: "Gagal memuat WhatsLink"
        } finally {
            loading = false
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            color = Color(0xFF0F172A),
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier
                .fillMaxWidth(0.95f)
                .fillMaxHeight(0.84f)
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF1E293B))
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("📸 WhatsLink Verifikasi Magnet", fontSize = 11.sp, color = Color(0xFF38BDF8), fontWeight = FontWeight.Bold)
                        Text(item.title, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "Tutup")
                    }
                }

                when {
                    loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator()
                            Spacer(Modifier.height(10.dp))
                            Text("Mengambil info & screenshot dari WhatsLink…", fontSize = 13.sp, color = Color(0xFFCBD5E1))
                        }
                    }
                    error != null -> Box(Modifier.fillMaxSize().padding(20.dp), contentAlignment = Alignment.Center) {
                        Text("Gagal: $error", color = Color(0xFFF87171))
                    }
                    info != null -> {
                        val data = info!!
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Surface(color = Color(0xFF1E293B), shape = RoundedCornerShape(8.dp)) {
                                Text(
                                    text = "File: ${if (data.count > 0) data.count else "—"}  ·  Ukuran: ${Formatters.formatBytes(if (data.size > 0) data.size else item.size)}" +
                                        (if (data.fileType.isNotBlank()) "  ·  Tipe: ${data.fileType}" else ""),
                                    fontSize = 12.sp,
                                    color = Color(0xFFE2E8F0),
                                    modifier = Modifier.padding(10.dp)
                                )
                            }

                            if (data.screenshots.isEmpty()) {
                                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                    Text(
                                        "WhatsLink belum memiliki screenshot untuk magnet ini.",
                                        color = Color(0xFF94A3B8),
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
                            label = { Text(lbl, fontSize = 11.5.sp) }
                        )
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "Tutup", tint = Color.White)
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
                            "Gambar untuk $code tidak ditemukan di ${res?.activeSource ?: "ketiga sumber"}.",
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
    var webViewRef by remember { mutableStateOf<WebView?>(null) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            color = Color(0xFF0F172A),
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier
                .fillMaxWidth(0.96f)
                .fillMaxHeight(0.88f)
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF1E293B))
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("🔓 ${status.label} via Browser", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        Text(
                            "Jika muncul verifikasi keamanan, selesaikan di halaman bawah lalu ketuk 'Ambil Hasil'.",
                            fontSize = 11.sp,
                            color = Color(0xFF94A3B8)
                        )
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "Tutup")
                    }
                }

                AndroidView(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    factory = { ctx ->
                        WebView(ctx).apply {
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
                        .background(Color(0xFF1E293B))
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
                        Text("📥 Ambil Hasil Halaman Ini", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

private fun sortLabelShort(mode: String): String = when (mode) {
    "leechers-desc" -> "Sort: Leecher"
    "size-desc" -> "Sort: Terbesar"
    "size-asc" -> "Sort: Terkecil"
    "date-desc" -> "Sort: Terbaru"
    "date-asc" -> "Sort: Terlama"
    "files-desc" -> "Sort: Jml File"
    "sources-desc" -> "Sort: Multi"
    "name-asc" -> "Sort: A-Z"
    "name-desc" -> "Sort: Z-A"
    else -> "Sort: Seeder"
}

fun copyToClipboard(context: Context, text: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText("TorrentHub", text))
}

private fun openMagnetExternal(context: Context, link: String, vm: AppViewModel) {
    try {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(link)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    } catch (_: Exception) {
        copyToClipboard(context, link)
        vm.emitToast("Aplikasi torrent lokal tidak ditemukan — tautan disalin ke clipboard.")
    }
}
