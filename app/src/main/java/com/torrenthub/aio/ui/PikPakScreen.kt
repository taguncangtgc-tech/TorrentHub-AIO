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
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.torrenthub.aio.R
import com.torrenthub.aio.data.PikPakClient
import com.torrenthub.aio.model.PikPakDriveItem
import com.torrenthub.aio.model.PikPakFolder
import com.torrenthub.aio.network.HttpClient
import com.torrenthub.aio.util.ExternalPlayerHelper
import com.torrenthub.aio.util.Formatters
import kotlinx.coroutines.launch

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PikPakScreen(vm: AppViewModel, isActive: Boolean = true) {
    val p = AppTheme.palette
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
            vm.emitToast(vm.str(R.string.toast_pikpak_getting_stream, item.name))
            try {
                val streamUrl = vm.fetchPikPakStreamLinkSuspend(item)
                ExternalPlayerHelper.openVideoWithChooser(
                    context = context,
                    streamUrl = streamUrl,
                    videoTitle = item.name,
                    vm = vm
                )
            } catch (e: Exception) {
                vm.emitToast("❌ ${e.message}")
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
                vm.emitToast(vm.str(R.string.toast_pikpak_stream_copied, item.name))
            } catch (e: Exception) {
                vm.emitToast("❌ ${e.message}")
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
                previewSubLines = listOf("❌ ${e.message}")
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
        Surface(color = p.cardSurface, shape = RoundedCornerShape(12.dp), border = p.cardBorder) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            stringResource(R.string.pikpak_card_title),
                            fontWeight = FontWeight.ExtraBold,
                            fontSize = 15.sp,
                            color = p.textPrimary
                        )
                        val emailSuffix = if (creds.userEmail.isNotBlank()) " (${creds.userEmail})" else ""
                        Text(
                            text = if (creds.isLoggedIn) {
                                stringResource(R.string.pikpak_status_connected, emailSuffix)
                            } else {
                                stringResource(R.string.pikpak_status_disconnected)
                            },
                            fontSize = 12.5.sp,
                            color = if (creds.isLoggedIn) p.accentGreen else p.textMuted
                        )
                    }
                    if (creds.isLoggedIn) {
                        TextButton(onClick = { vm.logoutPikPak() }) {
                            Text(stringResource(R.string.pikpak_btn_logout), color = p.accentRed, fontSize = 12.sp)
                        }
                    }
                }

                Button(
                    onClick = { showLoginWebView = true },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0284C7))
                ) {
                    Icon(Icons.AutoMirrored.Filled.Login, contentDescription = null, modifier = Modifier.size(18.dp), tint = Color.White)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        if (creds.isLoggedIn) stringResource(R.string.pikpak_btn_resync)
                        else stringResource(R.string.pikpak_btn_login),
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                }

                // Target Folder Selector
                if (creds.isLoggedIn) {
                    Surface(
                        color = p.subCardSurface,
                        shape = RoundedCornerShape(10.dp),
                        border = if (p.isLight || p.isAmoled) BorderStroke(1.dp, p.borderColor) else null,
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
                                Text(stringResource(R.string.pikpak_target_folder_label), fontSize = 11.sp, color = p.textMuted)
                                Text(
                                    "📁 ${creds.targetFolderName}",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 13.sp,
                                    color = p.textPrimary
                                )
                            }
                            OutlinedButton(
                                onClick = { showFolderPicker = true },
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                            ) {
                                Text(stringResource(R.string.pikpak_btn_change_folder), fontSize = 12.sp)
                            }
                        }
                    }
                }

                // Collapsible Manual Token Input
                TextButton(onClick = { showManualToken = !showManualToken }) {
                    Text(
                        if (showManualToken) stringResource(R.string.pikpak_toggle_hide_token)
                        else stringResource(R.string.pikpak_toggle_show_token),
                        fontSize = 12.sp,
                        color = p.textMuted
                    )
                }
                if (showManualToken) {
                    OutlinedTextField(
                        value = manualTokenInput,
                        onValueChange = { manualTokenInput = it },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 2,
                        maxLines = 3,
                        label = { Text(stringResource(R.string.pikpak_token_label)) },
                        placeholder = { Text("Bearer eyJhbGciOi...", fontSize = 12.sp) },
                        textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                    )
                    Button(
                        onClick = {
                            if (manualTokenInput.isNotBlank()) {
                                vm.savePikPakCreds(creds.copy(accessToken = manualTokenInput.trim()))
                                vm.emitToast(vm.str(R.string.toast_pikpak_token_saved))
                                vm.refreshPikPakTasks()
                                vm.loadPikPakDirectory("")
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF059669))
                    ) {
                        Text(stringResource(R.string.pikpak_btn_save_token), color = Color.White)
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
                        containerColor = if (vm.pikpakSubTab == 0) Color(0xFF0284C7) else p.cardSurface,
                        contentColor = if (vm.pikpakSubTab == 0) Color.White else p.textPrimary
                    ),
                    border = if (vm.pikpakSubTab != 0) p.cardBorder else null,
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 10.dp)
                ) {
                    Text(stringResource(R.string.pikpak_tab_drive), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
                Button(
                    onClick = {
                        vm.pikpakSubTab = 1
                        vm.refreshPikPakTasks()
                    },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (vm.pikpakSubTab == 1) Color(0xFF0284C7) else p.cardSurface,
                        contentColor = if (vm.pikpakSubTab == 1) Color.White else p.textPrimary
                    ),
                    border = if (vm.pikpakSubTab != 1) p.cardBorder else null,
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 10.dp)
                ) {
                    Text(stringResource(R.string.pikpak_tab_tasks, vm.pikpakTasks.size), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }

            if (vm.pikpakSubTab == 0) {
                // Card 2A: Mini File Manager My Drive
                Surface(color = p.cardSurface, shape = RoundedCornerShape(12.dp), border = p.cardBorder) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
                                        Text(stringResource(R.string.pikpak_btn_back), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                    }
                                    Spacer(Modifier.width(4.dp))
                                }
                                Text(
                                    "📁 ${vm.currentPikPakFolderName}",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 13.5.sp,
                                    color = p.accentBlue,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            IconButton(onClick = { vm.loadPikPakDirectory() }) {
                                if (vm.isLoadingPikPakDir) {
                                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                                } else {
                                    Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.cd_refresh), tint = p.textPrimary)
                                }
                            }
                        }

                        val isAlreadyTarget = creds.targetFolderId == vm.currentPikPakFolderId
                        OutlinedButton(
                            onClick = { vm.setCurrentDirAsPikPakTarget() },
                            enabled = !isAlreadyTarget,
                            modifier = Modifier.fillMaxWidth(),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                        ) {
                            Text(
                                if (isAlreadyTarget) stringResource(R.string.pikpak_btn_target_active)
                                else stringResource(R.string.pikpak_btn_set_target),
                                fontSize = 11.5.sp
                            )
                        }

                        val rowBorder = if (p.isLight || p.isAmoled) BorderStroke(1.dp, p.borderColor) else null

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
                                    color = p.accentRed
                                )
                            }
                            vm.pikpakDirItems.isEmpty() -> {
                                Text(
                                    stringResource(R.string.pikpak_folder_empty),
                                    fontSize = 12.5.sp,
                                    color = p.textMuted,
                                    modifier = Modifier.padding(vertical = 8.dp)
                                )
                            }
                            else -> {
                                val dirSnapshot = vm.pikpakDirItems.toList()
                                dirSnapshot.forEach { item ->
                                    when {
                                        item.isFolder -> {
                                            Surface(
                                                color = p.subCardSurface,
                                                shape = RoundedCornerShape(10.dp),
                                                border = rowBorder,
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .clickable { vm.openPikPakSubfolder(item) }
                                            ) {
                                                Row(
                                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 11.dp),
                                                    verticalAlignment = Alignment.CenterVertically
                                                ) {
                                                    Icon(Icons.Default.Folder, contentDescription = null, tint = p.accentBlue)
                                                    Spacer(Modifier.width(10.dp))
                                                    Column(modifier = Modifier.weight(1f)) {
                                                        Text(
                                                            item.name,
                                                            fontWeight = FontWeight.SemiBold,
                                                            fontSize = 13.5.sp,
                                                            color = p.textPrimary,
                                                            maxLines = 2,
                                                            overflow = TextOverflow.Ellipsis
                                                        )
                                                        if (item.modifiedTime.isNotBlank()) {
                                                            Text(item.modifiedTime, fontSize = 10.5.sp, color = p.textMuted)
                                                        }
                                                    }
                                                    Icon(Icons.Default.ChevronRight, contentDescription = null, tint = p.textMuted)
                                                }
                                            }
                                        }

                                        item.isVideo -> {
                                            val hasSub = vm.videoHasMatchingSubtitleInDir(item, dirSnapshot)
                                            val isBusyStream = streamingItemId == item.id
                                            Surface(
                                                color = p.subCardSurface,
                                                shape = RoundedCornerShape(10.dp),
                                                border = rowBorder,
                                                modifier = Modifier.fillMaxWidth()
                                            ) {
                                                Column(
                                                    modifier = Modifier.padding(10.dp),
                                                    verticalArrangement = Arrangement.spacedBy(6.dp)
                                                ) {
                                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                                        Icon(Icons.Default.Movie, contentDescription = null, tint = p.accentBlue, modifier = Modifier.size(20.dp))
                                                        Spacer(Modifier.width(8.dp))
                                                        Text(
                                                            item.name,
                                                            fontWeight = FontWeight.Bold,
                                                            fontSize = 13.sp,
                                                            color = p.textPrimary,
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
                                                            color = p.accentBlue.copy(alpha = 0.16f),
                                                            shape = RoundedCornerShape(4.dp)
                                                        ) {
                                                            Text(
                                                                "📦 ${Formatters.formatBytes(item.size)}",
                                                                fontSize = 10.5.sp,
                                                                fontWeight = FontWeight.Bold,
                                                                color = p.accentBlue,
                                                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                                            )
                                                        }
                                                        Surface(
                                                            color = if (hasSub) p.accentGreen.copy(alpha = 0.2f) else p.chipBg,
                                                            shape = RoundedCornerShape(4.dp)
                                                        ) {
                                                            Text(
                                                                if (hasSub) stringResource(R.string.pikpak_sub_yes)
                                                                else stringResource(R.string.pikpak_sub_no),
                                                                fontSize = 10.5.sp,
                                                                fontWeight = FontWeight.ExtraBold,
                                                                color = if (hasSub) p.accentGreen else p.chipFg,
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
                                                                if (isBusyStream) stringResource(R.string.pikpak_btn_getting_link)
                                                                else stringResource(R.string.pikpak_btn_open_with),
                                                                fontSize = 11.5.sp,
                                                                color = Color.White
                                                            )
                                                        }
                                                        OutlinedButton(
                                                            onClick = { vm.searchSubtitleForPikPakVideo(item) },
                                                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                                                        ) {
                                                            Text(stringResource(R.string.pikpak_btn_search_sub), fontSize = 11.5.sp)
                                                        }
                                                        OutlinedButton(
                                                            onClick = { copyVideoLink(item) },
                                                            enabled = !isBusyStream,
                                                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                                                        ) {
                                                            Text(stringResource(R.string.pikpak_btn_copy_link), fontSize = 11.5.sp)
                                                        }
                                                    }
                                                }
                                            }
                                        }

                                        item.isSubtitle -> {
                                            val isBusySub = vm.busyPikPakItemId == item.id
                                            Surface(
                                                color = p.subCardSurface,
                                                shape = RoundedCornerShape(10.dp),
                                                border = rowBorder,
                                                modifier = Modifier.fillMaxWidth()
                                            ) {
                                                Column(
                                                    modifier = Modifier.padding(10.dp),
                                                    verticalArrangement = Arrangement.spacedBy(6.dp)
                                                ) {
                                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                                        Icon(Icons.Default.Subtitles, contentDescription = null, tint = p.accentPurple, modifier = Modifier.size(19.dp))
                                                        Spacer(Modifier.width(8.dp))
                                                        Text(
                                                            item.name,
                                                            fontWeight = FontWeight.SemiBold,
                                                            fontSize = 12.5.sp,
                                                            color = p.textPrimary,
                                                            modifier = Modifier.weight(1f),
                                                            maxLines = 2,
                                                            overflow = TextOverflow.Ellipsis
                                                        )
                                                        Spacer(Modifier.width(6.dp))
                                                        Text(
                                                            Formatters.formatBytes(item.size),
                                                            fontSize = 11.sp,
                                                            color = p.textMuted
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
                                                                if (isBusySub) stringResource(R.string.pikpak_btn_fetching)
                                                                else stringResource(R.string.pikpak_btn_send_gemini),
                                                                fontSize = 11.5.sp,
                                                                color = Color.White
                                                            )
                                                        }
                                                        OutlinedButton(
                                                            onClick = { openSubtitlePreview(item) },
                                                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                                                        ) {
                                                            Text(stringResource(R.string.common_preview), fontSize = 11.5.sp)
                                                        }
                                                    }
                                                }
                                            }
                                        }

                                        else -> {
                                            Surface(
                                                color = p.subCardSurface,
                                                shape = RoundedCornerShape(8.dp),
                                                border = rowBorder,
                                                modifier = Modifier.fillMaxWidth()
                                            ) {
                                                Row(
                                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
                                                    verticalAlignment = Alignment.CenterVertically
                                                ) {
                                                    Icon(Icons.Default.InsertDriveFile, contentDescription = null, tint = p.textMuted, modifier = Modifier.size(18.dp))
                                                    Spacer(Modifier.width(8.dp))
                                                    Text(
                                                        item.name,
                                                        fontSize = 12.5.sp,
                                                        color = p.textSecondary,
                                                        modifier = Modifier.weight(1f),
                                                        maxLines = 1,
                                                        overflow = TextOverflow.Ellipsis
                                                    )
                                                    Text(
                                                        Formatters.formatBytes(item.size),
                                                        fontSize = 11.sp,
                                                        color = p.textMuted
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
                Surface(color = p.cardSurface, shape = RoundedCornerShape(12.dp), border = p.cardBorder) {
                    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(stringResource(R.string.pikpak_tasks_title), fontWeight = FontWeight.Bold, fontSize = 14.sp, color = p.textPrimary)
                            IconButton(onClick = { vm.refreshPikPakTasks() }) {
                                if (vm.isLoadingPikPakTasks) {
                                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                                } else {
                                    Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.cd_refresh), tint = p.textPrimary)
                                }
                            }
                        }

                        if (vm.pikpakTasks.isEmpty()) {
                            Text(
                                stringResource(R.string.pikpak_tasks_empty),
                                fontSize = 12.sp,
                                color = p.textMuted
                            )
                        } else {
                            val rowBorder = if (p.isLight || p.isAmoled) BorderStroke(1.dp, p.borderColor) else null
                            vm.pikpakTasks.forEach { task ->
                                Surface(color = p.subCardSurface, shape = RoundedCornerShape(8.dp), border = rowBorder) {
                                    Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        Text(
                                            task.name,
                                            fontWeight = FontWeight.SemiBold,
                                            fontSize = 12.5.sp,
                                            color = p.textPrimary,
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
                                                color = if (task.phase == "COMPLETE") p.accentGreen else p.accentBlue,
                                                fontWeight = FontWeight.Bold
                                            )
                                            Text(
                                                text = Formatters.formatBytes(task.fileSize),
                                                fontSize = 11.sp,
                                                color = p.textMuted
                                            )
                                        }
                                        LinearProgressIndicator(
                                            progress = { (task.progress / 100f).coerceIn(0f, 1f) },
                                            modifier = Modifier.fillMaxWidth().height(4.dp),
                                            color = if (task.phase == "COMPLETE") Color(0xFF22C55E) else Color(0xFF0284C7),
                                            trackColor = p.chipBg
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
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(previewSubName.orEmpty(), fontWeight = FontWeight.Bold, color = p.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (!previewSubLoading) {
                                Text(stringResource(R.string.gemini_lines_count, previewSubLines.size), fontSize = 11.sp, color = p.accentBlue)
                            }
                        }
                        IconButton(onClick = { previewSubName = null }) {
                            Icon(Icons.Default.Close, contentDescription = stringResource(R.string.cd_close), tint = p.textPrimary)
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
                                .background(p.consoleSurface),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp)
                        ) {
                            items(previewSubLines.size) { idx ->
                                Row(modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp)) {
                                    Text(
                                        text = "${(idx + 1).toString().padStart(padW, ' ')}. ",
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 11.sp,
                                        color = p.textMuted
                                    )
                                    Text(
                                        text = previewSubLines[idx],
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 11.5.sp,
                                        color = p.textPrimary,
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
                    vm.emitToast(vm.str(R.string.toast_pikpak_session_connected))
                    vm.refreshPikPakTasks()
                    vm.loadPikPakDirectory("")
                } else {
                    vm.emitToast(vm.str(R.string.toast_pikpak_session_missing))
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
                vm.emitToast(vm.str(R.string.toast_pikpak_folder_set, name))
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
                .fillMaxHeight(0.9f)
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(p.cardSurface)
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.pikpak_login_dialog_title), fontWeight = FontWeight.Bold, fontSize = 14.sp, color = p.textPrimary)
                        Text(
                            stringResource(R.string.pikpak_login_dialog_desc),
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
                            loadUrl("https://mypikpak.com/drive/all")
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
                        Text(stringResource(R.string.pikpak_btn_save_session), fontWeight = FontWeight.Bold, color = Color.White)
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
    val p = AppTheme.palette
    val scope = rememberCoroutineScope()
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var folders by remember { mutableStateOf<List<PikPakFolder>>(emptyList()) }
    val pathStack = remember { mutableStateListOf<Pair<String, String>>() }

    val currentParentId = pathStack.lastOrNull()?.first.orEmpty()
    val currentParentName = pathStack.lastOrNull()?.second ?: "My Drive (Root)"

    fun loadDir(parentId: String) {
        scope.launch {
            loading = true
            error = null
            try {
                folders = vm.loadPikPakFoldersSuspend(parentId)
            } catch (e: Exception) {
                error = e.message ?: "Error"
            } finally {
                loading = false
            }
        }
    }

    LaunchedEffect(currentParentId) { loadDir(currentParentId) }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            color = p.barSurface,
            shape = RoundedCornerShape(14.dp),
            border = BorderStroke(1.dp, p.borderColor),
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
                    Text(stringResource(R.string.pikpak_picker_title), fontWeight = FontWeight.Bold, fontSize = 15.sp, color = p.textPrimary)
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = stringResource(R.string.cd_close), tint = p.textPrimary)
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (pathStack.isNotEmpty()) {
                        TextButton(onClick = { pathStack.removeAt(pathStack.lastIndex) }) {
                            Text(stringResource(R.string.pikpak_btn_back))
                        }
                    }
                    Text(currentParentName, fontSize = 12.5.sp, color = p.accentBlue, fontWeight = FontWeight.Bold)
                }

                Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    when {
                        loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                        error != null -> Text("⚠️ $error", color = p.accentRed, fontSize = 12.sp)
                        folders.isEmpty() -> Text(stringResource(R.string.pikpak_picker_empty), color = p.textMuted, fontSize = 12.5.sp)
                        else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            items(folders, key = { it.id }) { f ->
                                Surface(
                                    color = p.cardSurface,
                                    shape = RoundedCornerShape(8.dp),
                                    border = p.cardBorder,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { pathStack.add(f.id to f.name) }
                                ) {
                                    Row(
                                        modifier = Modifier.padding(10.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(Icons.Default.Folder, null, tint = p.accentBlue)
                                        Spacer(Modifier.width(10.dp))
                                        Text(f.name, fontSize = 13.5.sp, color = p.textPrimary, modifier = Modifier.weight(1f))
                                        Icon(Icons.Default.ChevronRight, null, tint = p.textMuted)
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
                        Text(stringResource(R.string.pikpak_btn_default_folder), fontSize = 12.sp)
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
                        Text(stringResource(R.string.pikpak_btn_select_this_folder), fontSize = 12.sp, color = Color.White)
                    }
                }
            }
        }
    }
}
