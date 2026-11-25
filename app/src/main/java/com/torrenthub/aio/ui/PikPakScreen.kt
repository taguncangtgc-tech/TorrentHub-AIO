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
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Login
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.torrenthub.aio.data.PikPakClient
import com.torrenthub.aio.model.PikPakDriveItem
import com.torrenthub.aio.model.PikPakFolder
import com.torrenthub.aio.network.HttpClient
import com.torrenthub.aio.util.Formatters
import kotlinx.coroutines.launch

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PikPakScreen(vm: AppViewModel, isActive: Boolean = true) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var showLoginWebView by remember { mutableStateOf(false) }
    var showManualToken by remember { mutableStateOf(false) }
    var showFolderPicker by remember { mutableStateOf(false) }
    var manualTokenInput by remember { mutableStateOf(vm.pikpakCreds.accessToken) }

    var previewSubName by remember { mutableStateOf<String?>(null) }
    var previewSubLoading by remember { mutableStateOf(false) }
    var previewSubLines by remember { mutableStateOf<List<String>>(emptyList()) }
    var streamingItemId by remember { mutableStateOf<String?>(null) }

    val creds = vm.pikpakCreds

    BackHandler(enabled = isActive && creds.isLoggedIn && vm.pikpakSubTab == 0 && vm.pikpakBrowserStack.isNotEmpty()) {
        vm.navigateUpPikPakFolder()
    }

    LaunchedEffect(creds.isLoggedIn) {
        if (creds.isLoggedIn && vm.pikpakDirItems.isEmpty() && !vm.isLoadingPikPakDir) {
            vm.loadPikPakDirectory(vm.currentPikPakFolderId)
        }
    }

    fun copyToClipboard(label: String, text: String) {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText(label, text))
    }

    fun playVideoExternal(item: PikPakDriveItem) {
        scope.launch {
            streamingItemId = item.id
            vm.emitToast("⏳ Mengambil link streaming \"${item.name}\"…")
            try {
                val streamUrl = vm.fetchPikPakStreamLinkSuspend(item)
                val playIntent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(Uri.parse(streamUrl), "video/*")
                    putExtra("title", item.name)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                try {
                    context.startActivity(Intent.createChooser(playIntent, "Putar dengan VLC / MX / MPV"))
                } catch (_: Exception) {
                    copyToClipboard("PikPak Stream URL", streamUrl)
                    vm.emitToast("📋 Link streaming disalin (tidak ada aplikasi pemutar video eksternal).")
                }
            } catch (e: Exception) {
                vm.emitToast("❌ Gagal mengambil link streaming: ${e.message}")
            } finally {
                if (streamingItemId == item.id) streamingItemId = null
            }
        }
    }

    fun copyVideoLink(item: PikPakDriveItem) {
        scope.launch {
            streamingItemId = item.id
            try {
                val streamUrl = vm.fetchPikPakStreamLinkSuspend(item)
                copyToClipboard("PikPak Direct Link", streamUrl)
                vm.emitToast("📋 Link langsung \"${item.name}\" disalin ke clipboard!")
            } catch (e: Exception) {
                vm.emitToast("❌ Gagal mengambil link: ${e.message}")
            } finally {
                if (streamingItemId == item.id) streamingItemId = null
            }
        }
    }

    fun openSubtitlePreview(item: PikPakDriveItem) {
        previewSubName = item.name
        previewSubLoading = true
        previewSubLines = emptyList()
        scope.launch {
            try {
                val raw = vm.fetchPikPakSubtitleTextSuspend(item)
                previewSubLines = raw.lines()
            } catch (e: Exception) {
                previewSubLines = listOf("❌ Gagal memuat isi subtitle: ${e.message}")
            } finally {
                previewSubLoading = false
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // Card 1: Connection Status & WebView Login
        Surface(color = Color(0xFF1E293B), shape = RoundedCornerShape(12.dp)) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("☁️ PikPak Cloud & Mini File Manager", fontWeight = FontWeight.ExtraBold, fontSize = 15.sp)
                        Text(
                            text = if (creds.isLoggedIn) {
                                "✅ Terhubung" + (if (creds.userEmail.isNotBlank()) " (${creds.userEmail})" else "")
                            } else {
                                "⚪ Belum terhubung ke akun PikPak"
                            },
                            fontSize = 12.5.sp,
                            color = if (creds.isLoggedIn) Color(0xFF4ADE80) else Color(0xFF94A3B8)
                        )
                    }
                    if (creds.isLoggedIn) {
                        TextButton(onClick = { vm.logoutPikPak() }) {
                            Text("Keluar", color = Color(0xFFF87171), fontSize = 12.sp)
                        }
                    }
                }

                Button(
                    onClick = { showLoginWebView = true },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0284C7))
                ) {
                    Icon(Icons.AutoMirrored.Filled.Login, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        if (creds.isLoggedIn) "Buka & Sinkron Ulang Sesi Web PikPak"
                        else "Login ke Akun PikPak (Web Resmi)",
                        fontWeight = FontWeight.Bold
                    )
                }

                // Target Folder Selector
                if (creds.isLoggedIn) {
                    Surface(
                        color = Color(0xFF0F172A),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Folder Tujuan Download & Upload SRT:", fontSize = 11.sp, color = Color(0xFF94A3B8))
                                Text(
                                    "📁 ${creds.targetFolderName}",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 13.sp,
                                    color = Color(0xFFF8FAFC)
                                )
                            }
                            OutlinedButton(
                                onClick = { showFolderPicker = true },
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                            ) {
                                Text("Ubah Folder", fontSize = 12.sp)
                            }
                        }
                    }
                }

                // Collapsible Manual Token Input
                TextButton(onClick = { showManualToken = !showManualToken }) {
                    Text(
                        if (showManualToken) "Sembunyikan Input Token Manual" else "Atau tempel Bearer Token secara manual…",
                        fontSize = 12.sp,
                        color = Color(0xFF94A3B8)
                    )
                }
                if (showManualToken) {
                    OutlinedTextField(
                        value = manualTokenInput,
                        onValueChange = { manualTokenInput = it },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 2,
                        maxLines = 3,
                        label = { Text("Authorization Bearer Token PikPak") },
                        placeholder = { Text("Bearer eyJhbGciOi...", fontSize = 12.sp) },
                        textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                    )
                    Button(
                        onClick = {
                            if (manualTokenInput.isNotBlank()) {
                                vm.savePikPakCreds(creds.copy(accessToken = manualTokenInput.trim()))
                                vm.emitToast("✅ Token manual disimpan!")
                                vm.refreshPikPakTasks()
                                vm.loadPikPakDirectory("")
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF059669))
                    ) {
                        Text("Simpan Token Manual")
                    }
                }
            }
        }

        // Sub-tab switcher when logged in: File Manager vs Cloud Tasks
        if (creds.isLoggedIn) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = { vm.pikpakSubTab = 0 },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (vm.pikpakSubTab == 0) Color(0xFF0284C7) else Color(0xFF1E293B)
                    ),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 10.dp)
                ) {
                    Text("📁 File Manager Drive", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
                Button(
                    onClick = {
                        vm.pikpakSubTab = 1
                        vm.refreshPikPakTasks()
                    },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (vm.pikpakSubTab == 1) Color(0xFF0284C7) else Color(0xFF1E293B)
                    ),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 10.dp)
                ) {
                    Text("📥 Tugas Cloud (${vm.pikpakTasks.size})", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }

            if (vm.pikpakSubTab == 0) {
                // Card 2A: Mini File Manager My Drive
                Surface(color = Color(0xFF1E293B), shape = RoundedCornerShape(12.dp)) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        // Breadcrumb & Folder Controls
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.weight(1f)
                            ) {
                                if (vm.pikpakBrowserStack.isNotEmpty()) {
                                    TextButton(
                                        onClick = { vm.navigateUpPikPakFolder() },
                                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp)
                                    ) {
                                        Text("⬅ Kembali", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                    }
                                    Spacer(Modifier.width(4.dp))
                                }
                                Text(
                                    "📁 ${vm.currentPikPakFolderName}",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 13.5.sp,
                                    color = Color(0xFF38BDF8),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            IconButton(onClick = { vm.loadPikPakDirectory() }) {
                                if (vm.isLoadingPikPakDir) {
                                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                                } else {
                                    Icon(Icons.Default.Refresh, contentDescription = "Segarkan")
                                }
                            }
                        }

                        // Quick button to set current folder as destination
                        val isAlreadyTarget = creds.targetFolderId == vm.currentPikPakFolderId
                        OutlinedButton(
                            onClick = { vm.setCurrentDirAsPikPakTarget() },
                            enabled = !isAlreadyTarget,
                            modifier = Modifier.fillMaxWidth(),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                        ) {
                            Text(
                                if (isAlreadyTarget) "📌 Folder ini aktif sebagai Tujuan Download & Upload SRT"
                                else "📌 Jadikan Folder Ini Tujuan Download & Upload SRT",
                                fontSize = 11.5.sp
                            )
                        }

                        when {
                            vm.isLoadingPikPakDir && vm.pikpakDirItems.isEmpty() -> {
                                Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                                    CircularProgressIndicator()
                                }
                            }
                            vm.pikpakDirError != null -> {
                                Text(
                                    "⚠️ ${vm.pikpakDirError}",
                                    fontSize = 12.sp,
                                    color = Color(0xFFF87171)
                                )
                            }
                            vm.pikpakDirItems.isEmpty() -> {
                                Text(
                                    "Folder ini kosong.",
                                    fontSize = 12.5.sp,
                                    color = Color(0xFF94A3B8),
                                    modifier = Modifier.padding(vertical = 8.dp)
                                )
                            }
                            else -> {
                                val dirSnapshot = vm.pikpakDirItems.toList()
                                dirSnapshot.forEach { item ->
                                    when {
                                        item.isFolder -> {
                                            Surface(
                                                color = Color(0xFF0F172A),
                                                shape = RoundedCornerShape(10.dp),
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .clickable { vm.openPikPakSubfolder(item) }
                                            ) {
                                                Row(
                                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 11.dp),
                                                    verticalAlignment = Alignment.CenterVertically
                                                ) {
                                                    Icon(Icons.Default.Folder, contentDescription = null, tint = Color(0xFF60A5FA))
                                                    Spacer(Modifier.width(10.dp))
                                                    Column(modifier = Modifier.weight(1f)) {
                                                        Text(
                                                            item.name,
                                                            fontWeight = FontWeight.SemiBold,
                                                            fontSize = 13.5.sp,
                                                            color = Color(0xFFF8FAFC),
                                                            maxLines = 2,
                                                            overflow = TextOverflow.Ellipsis
                                                        )
                                                        if (item.modifiedTime.isNotBlank()) {
                                                            Text(item.modifiedTime, fontSize = 10.5.sp, color = Color(0xFF64748B))
                                                        }
                                                    }
                                                    Icon(Icons.Default.ChevronRight, contentDescription = null, tint = Color(0xFF64748B))
                                                }
                                            }
                                        }

                                        item.isVideo -> {
                                            val hasSub = vm.videoHasMatchingSubtitleInDir(item, dirSnapshot)
                                            val isBusyStream = streamingItemId == item.id
                                            Surface(
                                                color = Color(0xFF0F172A),
                                                shape = RoundedCornerShape(10.dp),
                                                modifier = Modifier.fillMaxWidth()
                                            ) {
                                                Column(
                                                    modifier = Modifier.padding(10.dp),
                                                    verticalArrangement = Arrangement.spacedBy(6.dp)
                                                ) {
                                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                                        Icon(Icons.Default.Movie, contentDescription = null, tint = Color(0xFF38BDF8), modifier = Modifier.size(20.dp))
                                                        Spacer(Modifier.width(8.dp))
                                                        Text(
                                                            item.name,
                                                            fontWeight = FontWeight.Bold,
                                                            fontSize = 13.sp,
                                                            color = Color.White,
                                                            modifier = Modifier.weight(1f),
                                                            maxLines = 2,
                                                            overflow = TextOverflow.Ellipsis
                                                        )
                                                    }
                                                    Row(
                                                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                                                        verticalAlignment = Alignment.CenterVertically
                                                    ) {
                                                        Surface(
                                                            color = Color(0xFF0284C7).copy(alpha = 0.22f),
                                                            shape = RoundedCornerShape(4.dp)
                                                        ) {
                                                            Text(
                                                                "📦 ${Formatters.formatBytes(item.size)}",
                                                                fontSize = 10.5.sp,
                                                                fontWeight = FontWeight.Bold,
                                                                color = Color(0xFF7DD3FC),
                                                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                                            )
                                                        }
                                                        Surface(
                                                            color = if (hasSub) Color(0xFF16A34A).copy(alpha = 0.25f)
                                                            else Color(0xFF475569).copy(alpha = 0.35f),
                                                            shape = RoundedCornerShape(4.dp)
                                                        ) {
                                                            Text(
                                                                if (hasSub) "SUB ✓" else "No SUB",
                                                                fontSize = 10.5.sp,
                                                                fontWeight = FontWeight.ExtraBold,
                                                                color = if (hasSub) Color(0xFF4ADE80) else Color(0xFFCBD5E1),
                                                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                                            )
                                                        }
                                                    }
                                                    FlowRow(
                                                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                                                        verticalArrangement = Arrangement.spacedBy(6.dp)
                                                    ) {
                                                        Button(
                                                            onClick = { playVideoExternal(item) },
                                                            enabled = !isBusyStream,
                                                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF059669)),
                                                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                                                        ) {
                                                            Text(
                                                                if (isBusyStream) "⏳ Link…" else "▶️ Putar (VLC/MX)",
                                                                fontSize = 11.5.sp
                                                            )
                                                        }
                                                        OutlinedButton(
                                                            onClick = { vm.searchSubtitleForPikPakVideo(item) },
                                                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                                                        ) {
                                                            Text("💬 Cari Sub", fontSize = 11.5.sp)
                                                        }
                                                        OutlinedButton(
                                                            onClick = { copyVideoLink(item) },
                                                            enabled = !isBusyStream,
                                                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                                                        ) {
                                                            Text("📋 Link", fontSize = 11.5.sp)
                                                        }
                                                    }
                                                }
                                            }
                                        }

                                        item.isSubtitle -> {
                                            val isBusySub = vm.busyPikPakItemId == item.id
                                            Surface(
                                                color = Color(0xFF0F172A),
                                                shape = RoundedCornerShape(10.dp),
                                                modifier = Modifier.fillMaxWidth()
                                            ) {
                                                Column(
                                                    modifier = Modifier.padding(10.dp),
                                                    verticalArrangement = Arrangement.spacedBy(6.dp)
                                                ) {
                                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                                        Icon(Icons.Default.Subtitles, contentDescription = null, tint = Color(0xFFC084FC), modifier = Modifier.size(19.dp))
                                                        Spacer(Modifier.width(8.dp))
                                                        Text(
                                                            item.name,
                                                            fontWeight = FontWeight.SemiBold,
                                                            fontSize = 12.5.sp,
                                                            color = Color(0xFFF8FAFC),
                                                            modifier = Modifier.weight(1f),
                                                            maxLines = 2,
                                                            overflow = TextOverflow.Ellipsis
                                                        )
                                                        Spacer(Modifier.width(6.dp))
                                                        Text(
                                                            Formatters.formatBytes(item.size),
                                                            fontSize = 11.sp,
                                                            color = Color(0xFF94A3B8)
                                                        )
                                                    }
                                                    FlowRow(
                                                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                                                        verticalArrangement = Arrangement.spacedBy(6.dp)
                                                    ) {
                                                        Button(
                                                            onClick = { vm.sendPikPakSubtitleToGemini(item) },
                                                            enabled = !isBusySub,
                                                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF7C3AED)),
                                                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                                                        ) {
                                                            Text(
                                                                if (isBusySub) "⏳ Mengambil…" else "✨ Kirim ke Gemini",
                                                                fontSize = 11.5.sp
                                                            )
                                                        }
                                                        OutlinedButton(
                                                            onClick = { openSubtitlePreview(item) },
                                                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                                                        ) {
                                                            Text("👁️ Preview", fontSize = 11.5.sp)
                                                        }
                                                    }
                                                }
                                            }
                                        }

                                        else -> {
                                            Surface(
                                                color = Color(0xFF0F172A),
                                                shape = RoundedCornerShape(8.dp),
                                                modifier = Modifier.fillMaxWidth()
                                            ) {
                                                Row(
                                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
                                                    verticalAlignment = Alignment.CenterVertically
                                                ) {
                                                    Icon(Icons.Default.InsertDriveFile, contentDescription = null, tint = Color(0xFF64748B), modifier = Modifier.size(18.dp))
                                                    Spacer(Modifier.width(8.dp))
                                                    Text(
                                                        item.name,
                                                        fontSize = 12.5.sp,
                                                        color = Color(0xFFCBD5E1),
                                                        modifier = Modifier.weight(1f),
                                                        maxLines = 1,
                                                        overflow = TextOverflow.Ellipsis
                                                    )
                                                    Text(
                                                        Formatters.formatBytes(item.size),
                                                        fontSize = 11.sp,
                                                        color = Color(0xFF64748B)
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
            } else {
                // Card 2B: Recent Cloud Download Tasks
                Surface(color = Color(0xFF1E293B), shape = RoundedCornerShape(12.dp)) {
                    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("📥 Tugas Cloud Download Terbaru", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                            IconButton(onClick = { vm.refreshPikPakTasks() }) {
                                if (vm.isLoadingPikPakTasks) {
                                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                                } else {
                                    Icon(Icons.Default.Refresh, contentDescription = "Segarkan")
                                }
                            }
                        }

                        if (vm.pikpakTasks.isEmpty()) {
                            Text(
                                "Belum ada tugas Cloud Download aktif atau ketuk ikon segarkan di kanan atas.",
                                fontSize = 12.sp,
                                color = Color(0xFF94A3B8)
                            )
                        } else {
                            vm.pikpakTasks.forEach { task ->
                                Surface(color = Color(0xFF0F172A), shape = RoundedCornerShape(8.dp)) {
                                    Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        Text(
                                            task.name,
                                            fontWeight = FontWeight.SemiBold,
                                            fontSize = 12.5.sp,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween
                                        ) {
                                            Text(
                                                text = "${task.phase} · ${task.progress}%",
                                                fontSize = 11.sp,
                                                color = if (task.phase == "COMPLETE") Color(0xFF4ADE80) else Color(0xFF38BDF8),
                                                fontWeight = FontWeight.Bold
                                            )
                                            Text(
                                                text = Formatters.formatBytes(task.fileSize),
                                                fontSize = 11.sp,
                                                color = Color(0xFF94A3B8)
                                            )
                                        }
                                        LinearProgressIndicator(
                                            progress = { (task.progress / 100f).coerceIn(0f, 1f) },
                                            modifier = Modifier.fillMaxWidth().height(4.dp),
                                            color = if (task.phase == "COMPLETE") Color(0xFF22C55E) else Color(0xFF0284C7),
                                            trackColor = Color(0xFF334155)
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

    if (previewSubName != null) {
        val padW = remember(previewSubLines.size) { previewSubLines.size.toString().length.coerceAtLeast(3) }
        Dialog(
            onDismissRequest = { previewSubName = null },
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
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(previewSubName.orEmpty(), fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (!previewSubLoading) {
                                Text("${previewSubLines.size} baris penuh", fontSize = 11.sp, color = Color(0xFF38BDF8))
                            }
                        }
                        IconButton(onClick = { previewSubName = null }) {
                            Icon(Icons.Default.Close, contentDescription = "Tutup")
                        }
                    }
                    if (previewSubLoading) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator()
                        }
                    } else {
                        LazyColumn(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth()
                                .background(Color(0xFF020617)),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp)
                        ) {
                            items(previewSubLines.size) { idx ->
                                Row(modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp)) {
                                    Text(
                                        text = "${(idx + 1).toString().padStart(padW, ' ')}. ",
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 11.sp,
                                        color = Color(0xFF64748B)
                                    )
                                    Text(
                                        text = previewSubLines[idx],
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 11.5.sp,
                                        color = Color(0xFFE2E8F0),
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showLoginWebView) {
        PikPakLoginWebViewDialog(
            onDismiss = { showLoginWebView = false },
            onSessionCaptured = { rawStorage ->
                val parsed = PikPakClient.parseWebViewLocalStorageJson(rawStorage, vm.pikpakCreds)
                if (parsed != null) {
                    vm.savePikPakCreds(parsed)
                    manualTokenInput = parsed.accessToken
                    showLoginWebView = false
                    vm.emitToast("✅ Sesi akun PikPak berhasil tersambung!")
                    vm.refreshPikPakTasks()
                    vm.loadPikPakDirectory("")
                } else {
                    vm.emitToast("⚠️ Belum terdeteksi sesi login — pastikan Anda sudah masuk ke halaman My Drive PikPak dulu.")
                }
            }
        )
    }

    if (showFolderPicker) {
        PikPakFolderPickerDialog(
            vm = vm,
            onDismiss = { showFolderPicker = false },
            onSelect = { id, name ->
                vm.savePikPakCreds(vm.pikpakCreds.copy(targetFolderId = id, targetFolderName = name))
                showFolderPicker = false
                vm.emitToast("📁 Folder tujuan diatur ke: $name")
            }
        )
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun PikPakLoginWebViewDialog(
    onDismiss: () -> Unit,
    onSessionCaptured: (String) -> Unit
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
                .fillMaxHeight(0.9f)
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF1E293B))
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("☁️ Login Web Resmi PikPak", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        Text(
                            "Setelah daftar file My Drive muncul, ketuk tombol hijau 'Simpan Sesi Login' di bawah.",
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
                            loadUrl("https://mypikpak.com/drive/all")
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
                            val js = "(function(){var o={};for(var i=0;i<localStorage.length;i++){var k=localStorage.key(i);o[k]=localStorage.getItem(k);}return JSON.stringify(o);})()"
                            wv.evaluateJavascript(js) { rawResult ->
                                val decoded = try {
                                    org.json.JSONTokener(rawResult).nextValue()?.toString().orEmpty()
                                } catch (_: Exception) {
                                    ""
                                }
                                onSessionCaptured(decoded)
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF059669))
                    ) {
                        Text("✅ Simpan Sesi Login Ini", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

@Composable
private fun PikPakFolderPickerDialog(
    vm: AppViewModel,
    onDismiss: () -> Unit,
    onSelect: (folderId: String, folderName: String) -> Unit
) {
    val scope = rememberCoroutineScope()
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var folders by remember { mutableStateOf<List<PikPakFolder>>(emptyList()) }
    val pathStack = remember { mutableStateListOf<Pair<String, String>>() } // (id, name)

    val currentParentId = pathStack.lastOrNull()?.first.orEmpty()
    val currentParentName = pathStack.lastOrNull()?.second ?: "My Drive (Root)"

    fun loadDir(parentId: String) {
        scope.launch {
            loading = true
            error = null
            try {
                folders = vm.loadPikPakFoldersSuspend(parentId)
            } catch (e: Exception) {
                error = e.message ?: "Gagal memuat folder"
            } finally {
                loading = false
            }
        }
    }

    LaunchedEffect(currentParentId) { loadDir(currentParentId) }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            color = Color(0xFF0F172A),
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 320.dp, max = 500.dp)
        ) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("📁 Pilih Folder PikPak", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "Tutup")
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (pathStack.isNotEmpty()) {
                        TextButton(onClick = { pathStack.removeAt(pathStack.lastIndex) }) {
                            Text("⬅ Kembali")
                        }
                    }
                    Text(currentParentName, fontSize = 12.5.sp, color = Color(0xFF38BDF8), fontWeight = FontWeight.Bold)
                }

                Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    when {
                        loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                        error != null -> Text("Gagal: $error", color = Color(0xFFF87171), fontSize = 12.sp)
                        folders.isEmpty() -> Text("Tidak ada sub-folder di sini.", color = Color(0xFF94A3B8), fontSize = 12.5.sp)
                        else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            items(folders, key = { it.id }) { f ->
                                Surface(
                                    color = Color(0xFF1E293B),
                                    shape = RoundedCornerShape(8.dp),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { pathStack.add(f.id to f.name) }
                                ) {
                                    Row(
                                        modifier = Modifier.padding(10.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(Icons.Default.Folder, null, tint = Color(0xFF60A5FA))
                                        Spacer(Modifier.width(10.dp))
                                        Text(f.name, fontSize = 13.5.sp, modifier = Modifier.weight(1f))
                                        Icon(Icons.Default.ChevronRight, null, tint = Color(0xFF64748B))
                                    }
                                }
                            }
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    OutlinedButton(onClick = { onSelect("", "Cloud Download (Default)") }) {
                        Text("Default Download", fontSize = 12.sp)
                    }
                    Button(
                        onClick = {
                            if (currentParentId.isBlank()) {
                                onSelect("", "Cloud Download (Default)")
                            } else {
                                onSelect(currentParentId, currentParentName)
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF059669))
                    ) {
                        Text("Pilih Folder Ini", fontSize = 12.sp)
                    }
                }
            }
        }
    }
}
