package com.torrenthub.aio.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Close
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.torrenthub.aio.R
import com.torrenthub.aio.data.AppLanguage
import com.torrenthub.aio.data.SubtitleRepository
import com.torrenthub.aio.util.Formatters
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
fun SubtitleScreen(vm: AppViewModel) {
    val p = AppTheme.palette
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val scope = rememberCoroutineScope()

    var previewTitle by remember { mutableStateOf<String?>(null) }
    var previewContent by remember { mutableStateOf("") }
    var pendingSaveText by remember { mutableStateOf<String?>(null) }
    var showTargetLangMenu by remember { mutableStateOf(false) }

    val saveLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("text/plain")
    ) { uri ->
        val content = pendingSaveText
        if (uri != null && content != null) {
            scope.launch(Dispatchers.IO) {
                try {
                    context.contentResolver.openOutputStream(uri)?.use { out ->
                        out.write(content.toByteArray(Charsets.UTF_8))
                    }
                    vm.emitToast(vm.str(R.string.toast_sub_saved))
                } catch (e: Exception) {
                    vm.emitToast(vm.str(R.string.toast_save_failed, e.message ?: "Error"))
                }
            }
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
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2563EB))
                    ) {
                        if (vm.isSearchingSubtitles) {
                            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = Color.White)
                        } else {
                            Icon(Icons.Default.Search, contentDescription = stringResource(R.string.cd_search), tint = Color.White)
                        }
                    }
                }

                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Quick Target Translation Language selector right in Subtitle Tab
                    Box {
                        AssistChip(
                            onClick = { showTargetLangMenu = true },
                            label = {
                                Text(
                                    stringResource(
                                        R.string.sub_target_chip,
                                        "${vm.subtitleTargetLang.flag} ${languageDisplayLabel(vm.subtitleTargetLang)}"
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
                            AppLanguage.entries.forEach { lang ->
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            "${lang.flag} ${languageDisplayLabel(lang)}",
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
                                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
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

                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    SubBtn(stringResource(R.string.sub_btn_gemini), Color(0xFF7C3AED)) {
                                        vm.sendSubtitleItemToGemini(item)
                                    }
                                    SubBtn(stringResource(R.string.common_preview), Color(0xFF2563EB)) {
                                        scope.launch {
                                            try {
                                                val txt = SubtitleRepository.fetchSubtitleText(item.url)
                                                previewContent = txt
                                                previewTitle = item.name
                                            } catch (e: Exception) {
                                                vm.emitToast(vm.str(R.string.toast_preview_failed, e.message ?: "Error"))
                                            }
                                        }
                                    }
                                    SubBtn(stringResource(R.string.sub_btn_save), Color(0xFF059669)) {
                                        scope.launch {
                                            try {
                                                val txt = SubtitleRepository.fetchSubtitleText(item.url)
                                                pendingSaveText = txt
                                                withContext(Dispatchers.Main) {
                                                    saveLauncher.launch(item.name)
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

    if (previewTitle != null) {
        val allLines = remember(previewContent) { previewContent.lines() }
        val padWidth = remember(allLines.size) { allLines.size.toString().length.coerceAtLeast(3) }

        Dialog(
            onDismissRequest = { previewTitle = null },
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
                            Text(
                                previewTitle.orEmpty(),
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp,
                                color = p.textPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                stringResource(
                                    R.string.sub_preview_meta,
                                    allLines.size,
                                    Formatters.formatBytes(previewContent.toByteArray(Charsets.UTF_8).size.toLong())
                                ),
                                fontSize = 11.sp,
                                color = p.accentBlue
                            )
                        }
                        IconButton(onClick = { previewTitle = null }) {
                            Icon(Icons.Default.Close, contentDescription = stringResource(R.string.cd_close), tint = p.textPrimary)
                        }
                    }

                    LazyColumn(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .background(p.consoleSurface),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp)
                    ) {
                        itemsIndexed(allLines) { idx, line ->
                            Row(modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp)) {
                                Text(
                                    text = "${(idx + 1).toString().padStart(padWidth, ' ')}. ",
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 11.sp,
                                    color = p.textMuted
                                )
                                Text(
                                    text = line,
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 11.5.sp,
                                    color = p.textPrimary,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(p.cardSurface)
                            .padding(10.dp),
                        horizontalArrangement = Arrangement.End
                    ) {
                        Button(
                            onClick = {
                                val name = previewTitle ?: "subtitle.srt"
                                val txt = previewContent
                                previewTitle = null
                                vm.addGeminiJob(name, txt)
                                vm.currentTab = 2
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF7C3AED))
                        ) {
                            Text(stringResource(R.string.sub_btn_send_gemini), color = Color.White)
                        }
                        Spacer(Modifier.width(8.dp))
                        Button(
                            onClick = {
                                val name = previewTitle ?: "subtitle.srt"
                                pendingSaveText = previewContent
                                saveLauncher.launch(name)
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF059669))
                        ) {
                            Text(stringResource(R.string.sub_btn_save), color = Color.White)
                        }
                    }
                }
            }
        }
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
