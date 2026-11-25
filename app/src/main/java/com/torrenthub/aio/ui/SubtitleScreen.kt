package com.torrenthub.aio.ui

import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.torrenthub.aio.R
import com.torrenthub.aio.data.SubtitleRepository
import com.torrenthub.aio.data.SubtitleTargetLanguage
import com.torrenthub.aio.model.SubtitleProvider
import com.torrenthub.aio.util.Formatters
import com.torrenthub.aio.util.SubtitleCharset
import com.torrenthub.aio.util.SubtitleTools
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

private fun formatDurationCompact(durationMs: Long): String {
    if (durationMs <= 0L) return ""
    val totalSec = durationMs / 1000L
    val h = totalSec / 3600L
    val m = (totalSec / 60L) % 60L
    val s = totalSec % 60L
    return if (h > 0L) {
        "%d:%02d:%02d".format(Locale.US, h, m, s)
    } else {
        "%02d:%02d".format(Locale.US, m, s)
    }
}

@Composable
fun subtitleProviderDisplayLabel(provider: SubtitleProvider): String = when (provider) {
    SubtitleProvider.ALL -> stringResource(R.string.sub_provider_all)
    SubtitleProvider.XUNLEI -> stringResource(R.string.sub_provider_xunlei)
    SubtitleProvider.SUBTITLECAT -> stringResource(R.string.sub_provider_subtitlecat)
    SubtitleProvider.OPENSUBTITLES -> stringResource(R.string.sub_provider_opensubtitles)
    SubtitleProvider.SUBDL -> stringResource(R.string.sub_provider_subdl)
}

@Composable
fun SubtitleScreen(vm: AppViewModel) {
    val p = AppTheme.palette
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val scope = rememberCoroutineScope()

    var editorTitle by remember { mutableStateOf<String?>(null) }
    var editorContent by remember { mutableStateOf("") }
    var editorRawBytes by remember { mutableStateOf<ByteArray?>(null) }

    var pendingSaveFileName by remember { mutableStateOf("subtitle.srt") }
    var pendingSaveText by remember { mutableStateOf<String?>(null) }
    var showAutoPairSaveDialog by remember { mutableStateOf(false) }
    var showAutoPairSetupDialog by remember { mutableStateOf(false) }
    var showTargetLangMenu by remember { mutableStateOf(false) }

    val saveLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("text/plain")
    ) { uri ->
        val content = pendingSaveText
        if (uri != null && content != null) {
            scope.launch(Dispatchers.IO) {
                try {
                    val outBytes = if (content.startsWith("\uFEFF")) {
                        SubtitleTools.normalizeToUtf8Bytes(content, includeBom = true)
                    } else {
                        SubtitleTools.normalizeToUtf8Bytes(content, includeBom = false)
                    }
                    context.contentResolver.openOutputStream(uri)?.use { out ->
                        out.write(outBytes)
                    }
                    vm.emitToast(vm.str(R.string.toast_sub_saved))
                } catch (e: Exception) {
                    vm.emitToast(vm.str(R.string.toast_save_failed, e.message ?: "Error"))
                }
            }
        }
    }

    val openLocalSubLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch(Dispatchers.IO) {
            try {
                var fName = "subtitle.srt"
                context.contentResolver.query(uri, null, null, null, null)?.use { c ->
                    val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0 && c.moveToFirst()) {
                        fName = c.getString(idx) ?: fName
                    }
                }
                val bytes = context.contentResolver.openInputStream(uri)?.readBytes() ?: ByteArray(0)
                if (bytes.isNotEmpty()) {
                    val decoded = SubtitleTools.decodeWithCharset(bytes, SubtitleCharset.AUTO)
                    withContext(Dispatchers.Main) {
                        editorRawBytes = bytes
                        editorContent = decoded
                        editorTitle = fName
                    }
                }
            } catch (e: Exception) {
                vm.emitToast(vm.str(R.string.toast_read_file_failed, e.message ?: "Error"))
            }
        }
    }

    fun requestSaveSubtitle(fileName: String, utf8Content: String) {
        pendingSaveFileName = fileName
        pendingSaveText = utf8Content
        if (vm.autoPairSubtitleVideo) {
            if (vm.autoPairFolderUri.isNotBlank() && vm.autoPairVideoName.isNotBlank()) {
                scope.launch {
                    try {
                        val savedName = vm.saveSubtitlePairedToSaf(
                            videoFileName = vm.autoPairVideoName,
                            subtitleNameOrExt = fileName,
                            contentUtf8 = utf8Content
                        )
                        vm.emitToast(vm.str(R.string.toast_autopair_saved, savedName))
                    } catch (_: Exception) {
                        showAutoPairSaveDialog = true
                    }
                }
            } else {
                showAutoPairSaveDialog = true
            }
        } else {
            saveLauncher.launch(fileName)
        }
    }

    val sortedItems = remember(vm.subtitleResults.size, vm.subtitleResults.toList(), vm.subtitleSortMode) {
        vm.sortedSubtitles()
    }

    val statusText = vm.subtitleStatusMsg ?: stringResource(R.string.sub_status_idle)

    Column(modifier = Modifier.fillMaxSize()) {
        Surface(color = p.barSurface, tonalElevation = if (p.isAmoled) 0.dp else 3.dp) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Search Input Row
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = vm.subtitleQuery,
                        onValueChange = { vm.subtitleQuery = it },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        placeholder = { Text(stringResource(R.string.sub_search_placeholder), fontSize = 13.sp) },
                        trailingIcon = {
                            if (vm.subtitleQuery.isNotEmpty()) {
                                IconButton(onClick = { vm.subtitleQuery = "" }) {
                                    Icon(Icons.Default.Clear, contentDescription = stringResource(R.string.cd_clear))
                                }
                            }
                        },
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = {
                            focusManager.clearFocus()
                            vm.runSubtitleSearch()
                        }),
                        shape = RoundedCornerShape(10.dp)
                    )
                    Button(
                        onClick = {
                            focusManager.clearFocus()
                            vm.runSubtitleSearch()
                        },
                        enabled = !vm.isSearchingSubtitles,
                        shape = RoundedCornerShape(10.dp),
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 14.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                    ) {
                        if (vm.isSearchingSubtitles) {
                            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                        } else {
                            Icon(Icons.Default.Search, contentDescription = stringResource(R.string.cd_search), tint = MaterialTheme.colorScheme.onPrimary)
                        }
                    }
                }

                // 1-Tap Multi-Source Provider Selector Row
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    SubtitleProvider.entries.forEach { provider ->
                        val selected = vm.subtitleProvider == provider
                        FilterChip(
                            selected = selected,
                            onClick = { vm.selectSubtitleProvider(provider) },
                            label = {
                                Text(
                                    text = subtitleProviderDisplayLabel(provider),
                                    fontSize = 12.sp,
                                    fontWeight = if (selected) FontWeight.ExtraBold else FontWeight.Medium
                                )
                            }
                        )
                    }
                }

                // Target Language + Auto-Pairing + Local Editor + Sort Row
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box {
                        AssistChip(
                            onClick = { showTargetLangMenu = true },
                            label = {
                                Text(
                                    stringResource(
                                        R.string.sub_target_chip,
                                        "${vm.subtitleTargetLang.flag} ${subtitleLangDisplayLabel(vm.subtitleTargetLang)}"
                                    ),
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                            },
                            trailingIcon = {
                                Icon(Icons.Default.ArrowDropDown, contentDescription = null, modifier = Modifier.size(18.dp))
                            }
                        )
                        DropdownMenu(
                            expanded = showTargetLangMenu,
                            onDismissRequest = { showTargetLangMenu = false }
                        ) {
                            SubtitleTargetLanguage.entries.forEach { lang ->
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            "${lang.flag} ${subtitleLangDisplayLabel(lang)}",
                                            fontWeight = if (vm.subtitleTargetLang == lang) FontWeight.Bold else FontWeight.Normal
                                        )
                                    },
                                    onClick = {
                                        vm.updateSubtitleTargetLang(lang)
                                        showTargetLangMenu = false
                                    }
                                )
                            }
                        }
                    }

                    // Auto-Pairing Quick Chip
                    val pairLabel = if (vm.autoPairSubtitleVideo) {
                        val targetDesc = vm.autoPairVideoName.ifBlank { vm.autoPairFolderName }.ifBlank { "ON" }
                        stringResource(R.string.autopair_chip_active, targetDesc.take(18))
                    } else {
                        stringResource(R.string.autopair_chip_off)
                    }
                    FilterChip(
                        selected = vm.autoPairSubtitleVideo,
                        onClick = {
                            if (!vm.autoPairSubtitleVideo) {
                                vm.updateAutoPairEnabled(true)
                                showAutoPairSetupDialog = true
                            } else {
                                showAutoPairSetupDialog = true
                            }
                        },
                        label = { Text(pairLabel, fontSize = 11.5.sp) }
                    )

                    // Open Local File in Subtitle Editor
                    AssistChip(
                        onClick = { openLocalSubLauncher.launch(arrayOf("*/*")) },
                        label = { Text(stringResource(R.string.sub_btn_open_local_editor), fontSize = 11.5.sp) }
                    )

                    listOf(
                        "size-desc" to stringResource(R.string.sub_sort_size_desc),
                        "size-asc" to stringResource(R.string.sub_sort_size_asc),
                        "name-asc" to stringResource(R.string.sub_sort_name_asc)
                    ).forEach { (mode, lbl) ->
                        FilterChip(
                            selected = vm.subtitleSortMode == mode,
                            onClick = { vm.subtitleSortMode = mode },
                            label = { Text(lbl, fontSize = 12.sp) }
                        )
                    }
                }

                Text(statusText, fontSize = 12.sp, color = p.textMuted)
            }
        }
        if (p.isLight || p.isAmoled) {
            HorizontalDivider(color = p.borderColor, thickness = 1.dp)
        }

        if (sortedItems.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(28.dp), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.Subtitles, null, tint = p.textMuted, modifier = Modifier.size(52.dp))
                    Spacer(Modifier.height(8.dp))
                    Text(statusText, color = p.textMuted, fontSize = 13.sp)
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(sortedItems, key = { it.idKey }) { item ->
                    val providerEnum = SubtitleProvider.fromId(item.sourceId)
                    val providerBg = Color(providerEnum.colorHex)

                    Surface(
                        color = p.cardSurface,
                        shape = RoundedCornerShape(12.dp),
                        border = p.cardBorder,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    text = item.name,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 13.5.sp,
                                    color = p.textPrimary,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f)
                                )
                                Spacer(Modifier.width(8.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Surface(color = providerBg, shape = RoundedCornerShape(50)) {
                                        Text(
                                            text = item.sourceName,
                                            color = Color.White,
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.ExtraBold,
                                            modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp)
                                        )
                                    }
                                    if (item.language.isNotBlank()) {
                                        Surface(color = p.chipBg, shape = RoundedCornerShape(50)) {
                                            Text(
                                                text = item.language,
                                                color = p.chipFg,
                                                fontSize = 10.sp,
                                                fontWeight = FontWeight.Bold,
                                                modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp)
                                            )
                                        }
                                    }
                                    Surface(color = Color(0xFF075985), shape = RoundedCornerShape(50)) {
                                        Text(
                                            text = item.ext.uppercase(),
                                            color = Color(0xFFE0F2FE),
                                            fontSize = 10.5.sp,
                                            fontWeight = FontWeight.ExtraBold,
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                                        )
                                    }
                                }
                            }

                            val durTxt = formatDurationCompact(item.durationMs)
                            val sizeTxt = if (item.sizeBytes > 0L) Formatters.formatBytes(item.sizeBytes)
                            else if (vm.isSearchingSubtitles) stringResource(R.string.sub_probing_item)
                            else stringResource(R.string.sub_size_unknown)

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = sizeTxt + (if (durTxt.isNotBlank()) "  ·  ⏱️ $durTxt" else ""),
                                    fontSize = 12.sp,
                                    fontWeight = if (item.sizeBytes > 0L) FontWeight.Bold else FontWeight.Normal,
                                    color = if (item.sizeBytes > 0L) p.accentBlue else p.textMuted
                                )

                                Row(
                                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    SubBtn(stringResource(R.string.sub_btn_gemini), Color(0xFF7C3AED)) {
                                        vm.sendSubtitleItemToGemini(item)
                                    }
                                    SubBtn(stringResource(R.string.sub_btn_preview_edit), Color(0xFF2563EB)) {
                                        scope.launch {
                                            try {
                                                val bytes = SubtitleRepository.fetchSubtitleBytes(item.url, vm.subtitleTargetLang.code)
                                                val txt = if (bytes.isNotEmpty()) {
                                                    SubtitleTools.decodeWithCharset(bytes, SubtitleCharset.AUTO)
                                                } else {
                                                    SubtitleRepository.fetchSubtitleText(item.url, vm.subtitleTargetLang.code)
                                                }
                                                editorRawBytes = bytes.takeIf { it.isNotEmpty() }
                                                editorContent = txt
                                                editorTitle = item.name
                                            } catch (e: Exception) {
                                                vm.emitToast(vm.str(R.string.toast_preview_failed, e.message ?: "Error"))
                                            }
                                        }
                                    }
                                    SubBtn(stringResource(R.string.sub_btn_download_mgr), Color(0xFF0284C7)) {
                                        vm.downloadSubtitleViaManager(item.name, item.url)
                                    }
                                    SubBtn(stringResource(R.string.sub_btn_save), Color(0xFF059669)) {
                                        scope.launch {
                                            try {
                                                val txt = SubtitleRepository.fetchSubtitleText(item.url, vm.subtitleTargetLang.code)
                                                withContext(Dispatchers.Main) {
                                                    requestSaveSubtitle(item.name, txt)
                                                }
                                            } catch (e: Exception) {
                                                vm.emitToast(vm.str(R.string.toast_download_failed, e.message ?: "Error"))
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
    }

    if (editorTitle != null) {
        SubtitleEditorDialog(
            vm = vm,
            fileName = editorTitle.orEmpty(),
            initialContent = editorContent,
            rawBytes = editorRawBytes,
            primaryActionLabel = stringResource(R.string.sub_btn_send_gemini),
            onPrimaryAction = { fName, utf8Txt, _ ->
                editorTitle = null
                vm.addGeminiJob(fName, utf8Txt)
                vm.currentTab = 2
                vm.emitToast(vm.str(R.string.toast_sent_to_gemini, fName))
            },
            onSaveRequest = { fName, utf8Txt ->
                requestSaveSubtitle(fName, utf8Txt)
            },
            onDismiss = { editorTitle = null }
        )
    }

    if (showAutoPairSaveDialog && pendingSaveText != null) {
        AutoPairVideoPickerDialog(
            vm = vm,
            subtitleFileName = pendingSaveFileName,
            subtitleToSaveContent = pendingSaveText,
            hintQuery = vm.subtitleQuery.ifBlank { pendingSaveFileName },
            onManualSaveFallback = { saveLauncher.launch(pendingSaveFileName) },
            onDismiss = { showAutoPairSaveDialog = false }
        )
    }

    if (showAutoPairSetupDialog) {
        AutoPairVideoPickerDialog(
            vm = vm,
            subtitleFileName = "subtitle.srt",
            subtitleToSaveContent = null,
            hintQuery = vm.subtitleQuery,
            onDismiss = { showAutoPairSetupDialog = false }
        )
    }
}

@Composable
private fun SubBtn(label: String, bg: Color, onClick: () -> Unit) {
    Surface(
        color = bg,
        shape = RoundedCornerShape(7.dp),
        modifier = Modifier.clickable(onClick = onClick)
    ) {
        Text(
            text = label,
            color = Color.White,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 6.dp)
        )
    }
}
