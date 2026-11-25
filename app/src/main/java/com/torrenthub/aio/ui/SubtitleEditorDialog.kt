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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.torrenthub.aio.R
import com.torrenthub.aio.model.SafVideoFile
import com.torrenthub.aio.util.Formatters
import com.torrenthub.aio.util.SafSubtitleHelper
import com.torrenthub.aio.util.SubtitleCharset
import com.torrenthub.aio.util.SubtitleTools
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.abs

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SubtitleEditorDialog(
    vm: AppViewModel,
    fileName: String,
    initialContent: String,
    rawBytes: ByteArray? = null,
    translatedFileName: String? = null,
    translatedContent: String? = null,
    startOnTranslated: Boolean = false,
    primaryActionLabel: String? = null,
    onPrimaryAction: ((activeFileName: String, utf8Content: String, isTranslatedStage: Boolean) -> Unit)? = null,
    onSaveRequest: (activeFileName: String, utf8Content: String) -> Unit,
    onDismiss: () -> Unit
) {
    val p = AppTheme.palette
    val hasBothStages = !translatedContent.isNullOrBlank()
    var viewingTranslated by remember { mutableStateOf(hasBothStages && startOnTranslated) }

    var origBaseText by remember(initialContent) { mutableStateOf(initialContent.removePrefix("\uFEFF")) }
    var transBaseText by remember(translatedContent) { mutableStateOf(translatedContent.orEmpty().removePrefix("\uFEFF")) }

    var selectedCharset by remember { mutableStateOf(SubtitleCharset.AUTO) }
    var includeUtf8Bom by remember { mutableStateOf(false) }
    var cumulativeDelaySec by remember { mutableDoubleStateOf(0.0) }
    var customDelayInput by remember { mutableStateOf("") }

    var showToolsPanel by remember { mutableStateOf(true) }
    var rawTextEditMode by remember { mutableStateOf(false) }
    var editingLineIndex by remember { mutableStateOf<Int?>(null) }
    var editingLineDraft by remember { mutableStateOf("") }

    val activeTitle = if (viewingTranslated && !translatedFileName.isNullOrBlank()) translatedFileName else fileName
    val currentBaseText = if (viewingTranslated) transBaseText else origBaseText

    val displayedContent = remember(currentBaseText, cumulativeDelaySec) {
        if (abs(cumulativeDelaySec) < 0.0001) {
            currentBaseText
        } else {
            SubtitleTools.shiftTimestamps(currentBaseText, cumulativeDelaySec)
        }
    }

    fun buildFinalUtf8String(): String {
        val clean = displayedContent.removePrefix("\uFEFF").replace("\u0000", "")
        return if (includeUtf8Bom) "\uFEFF$clean" else clean
    }

    fun applyEncodingSelection(charset: SubtitleCharset) {
        selectedCharset = charset
        includeUtf8Bom = (charset == SubtitleCharset.UTF8_BOM)
        if (!viewingTranslated && rawBytes != null && rawBytes.isNotEmpty()) {
            origBaseText = SubtitleTools.decodeWithCharset(rawBytes, charset).removePrefix("\uFEFF")
        }
        vm.emitToast(vm.str(R.string.toast_encoding_applied, charset.label))
    }

    fun addDelayDelta(delta: Double) {
        cumulativeDelaySec = ((cumulativeDelaySec + delta) * 100.0).let { kotlin.math.round(it) / 100.0 }
    }

    val allLines = remember(displayedContent) { displayedContent.lines() }
    val padWidth = remember(allLines.size) { allLines.size.toString().length.coerceAtLeast(3) }
    val utf8ByteSize = remember(displayedContent, includeUtf8Bom) {
        SubtitleTools.normalizeToUtf8Bytes(displayedContent, includeUtf8Bom).size.toLong()
    }
    val formattedOffset = remember(cumulativeDelaySec) {
        val sign = if (cumulativeDelaySec > 0.0001) "+" else ""
        String.format(Locale.US, "%s%.2fs", sign, cumulativeDelaySec)
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            color = p.barSurface,
            shape = RoundedCornerShape(16.dp),
            border = BorderStroke(1.dp, p.borderColor),
            modifier = Modifier
                .fillMaxWidth(0.97f)
                .fillMaxHeight(0.92f)
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // Header
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(p.cardSurface)
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                text = activeTitle,
                                fontWeight = FontWeight.ExtraBold,
                                fontSize = 14.sp,
                                color = p.textPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false)
                            )
                            Surface(
                                color = p.accentGreen.copy(alpha = 0.18f),
                                shape = RoundedCornerShape(50),
                                border = BorderStroke(1.dp, p.accentGreen.copy(alpha = 0.5f))
                            ) {
                                Text(
                                    text = stringResource(R.string.sub_editor_utf8_badge),
                                    color = p.accentGreen,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp)
                                )
                            }
                        }
                        Text(
                            text = stringResource(
                                R.string.sub_preview_meta,
                                allLines.size,
                                Formatters.formatBytes(utf8ByteSize)
                            ) + "  ·  ⏱️ $formattedOffset",
                            fontSize = 11.sp,
                            color = p.accentBlue
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

                HorizontalDivider(color = p.borderColor, thickness = 1.dp)

                // Stage Switcher & Collapsible Tools Header
                Surface(color = p.cardSurface, modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (hasBothStages) {
                                FilterChip(
                                    selected = !viewingTranslated,
                                    onClick = { viewingTranslated = false },
                                    label = { Text(stringResource(R.string.sub_editor_stage_orig), fontSize = 11.5.sp) }
                                )
                                FilterChip(
                                    selected = viewingTranslated,
                                    onClick = { viewingTranslated = true },
                                    label = { Text(stringResource(R.string.sub_editor_stage_translated), fontSize = 11.5.sp) }
                                )
                            }

                            FilterChip(
                                selected = !rawTextEditMode,
                                onClick = { rawTextEditMode = false },
                                label = { Text(stringResource(R.string.sub_editor_view_lines), fontSize = 11.5.sp) }
                            )
                            if (utf8ByteSize <= 250_000L) {
                                FilterChip(
                                    selected = rawTextEditMode,
                                    onClick = {
                                        if (!rawTextEditMode && abs(cumulativeDelaySec) >= 0.0001) {
                                            if (viewingTranslated) transBaseText = displayedContent
                                            else origBaseText = displayedContent
                                            cumulativeDelaySec = 0.0
                                        }
                                        rawTextEditMode = true
                                    },
                                    label = { Text(stringResource(R.string.sub_editor_view_raw), fontSize = 11.5.sp) }
                                )
                            }
                        }

                        // Collapsible Sync & Encoding Toggle Bar
                        Surface(
                            color = p.subCardSurface,
                            shape = RoundedCornerShape(8.dp),
                            border = BorderStroke(1.dp, p.borderColor),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { showToolsPanel = !showToolsPanel }
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = stringResource(R.string.sub_editor_toggle_tools),
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = p.textPrimary
                                )
                                Icon(
                                    imageVector = if (showToolsPanel) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                    contentDescription = null,
                                    tint = p.textPrimary,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }

                        if (showToolsPanel) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(p.subCardSurface, RoundedCornerShape(10.dp))
                                    .padding(10.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                // 1. Encoding Correction Row
                                Text(
                                    text = stringResource(R.string.sub_editor_encoding_label),
                                    fontSize = 11.5.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = p.textSecondary
                                )
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .horizontalScroll(rememberScrollState()),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    SubtitleCharset.entries.forEach { cs ->
                                        val enabledChip = viewingTranslated.not() || cs in setOf(
                                            SubtitleCharset.AUTO,
                                            SubtitleCharset.UTF8,
                                            SubtitleCharset.UTF8_BOM
                                        ) || rawBytes != null
                                        if (enabledChip) {
                                            FilterChip(
                                                selected = selectedCharset == cs,
                                                onClick = { applyEncodingSelection(cs) },
                                                label = { Text(cs.label, fontSize = 11.sp) }
                                            )
                                        }
                                    }
                                    AssistChip(
                                        onClick = {
                                            if (viewingTranslated) {
                                                transBaseText = SubtitleTools.repairMojibakeUtf8(transBaseText)
                                            } else {
                                                origBaseText = SubtitleTools.repairMojibakeUtf8(origBaseText)
                                            }
                                            vm.emitToast(vm.str(R.string.toast_encoding_applied, "UTF-8 Clean"))
                                        },
                                        label = { Text(stringResource(R.string.sub_editor_btn_fix_mojibake), fontSize = 11.sp) }
                                    )
                                }

                                HorizontalDivider(color = p.borderColor)

                                // 2. Subtitle Delay / Sync Row
                                Text(
                                    text = stringResource(R.string.sub_editor_delay_label, formattedOffset),
                                    fontSize = 11.5.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = p.accentBlue
                                )
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .horizontalScroll(rememberScrollState()),
                                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    listOf(
                                        -5.0 to "-5s",
                                        -1.0 to "-1s",
                                        -0.5 to "-0.5s",
                                        -0.1 to "-0.1s"
                                    ).forEach { (delta, lbl) ->
                                        DelayChipButton(lbl, p.accentAmber) { addDelayDelta(delta) }
                                    }
                                    DelayChipButton(stringResource(R.string.common_reset), p.chipFg, bgOverride = p.chipBg) {
                                        cumulativeDelaySec = 0.0
                                        customDelayInput = ""
                                    }
                                    listOf(
                                        0.1 to "+0.1s",
                                        0.5 to "+0.5s",
                                        1.0 to "+1s",
                                        5.0 to "+5s"
                                    ).forEach { (delta, lbl) ->
                                        DelayChipButton(lbl, p.accentGreen) { addDelayDelta(delta) }
                                    }
                                }

                                // Custom delay seconds input
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    OutlinedTextField(
                                        value = customDelayInput,
                                        onValueChange = { customDelayInput = it },
                                        singleLine = true,
                                        placeholder = { Text(stringResource(R.string.sub_editor_delay_input_hint), fontSize = 11.5.sp) },
                                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                        modifier = Modifier.weight(1f),
                                        textStyle = LocalTextStyle.current.copy(fontSize = 12.sp, fontFamily = FontFamily.Monospace),
                                        shape = RoundedCornerShape(8.dp)
                                    )
                                    Button(
                                        onClick = {
                                            val parsed = customDelayInput.trim().replace(',', '.').toDoubleOrNull()
                                            if (parsed != null) {
                                                cumulativeDelaySec = parsed
                                            }
                                        },
                                        shape = RoundedCornerShape(8.dp),
                                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp)
                                    ) {
                                        Text(stringResource(R.string.sub_editor_btn_apply_shift), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                        }
                    }
                }

                HorizontalDivider(color = p.borderColor, thickness = 1.dp)

                // Content Viewer / Editor
                if (rawTextEditMode) {
                    OutlinedTextField(
                        value = if (viewingTranslated) transBaseText else origBaseText,
                        onValueChange = { updated ->
                            if (viewingTranslated) transBaseText = updated else origBaseText = updated
                        },
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .background(p.consoleSurface)
                            .padding(8.dp),
                        textStyle = LocalTextStyle.current.copy(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp,
                            color = p.textPrimary
                        )
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .background(p.consoleSurface),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp)
                    ) {
                        itemsIndexed(allLines) { idx, line ->
                            val isTime = SubtitleTools.isTimestampLine(line)
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        editingLineIndex = idx
                                        editingLineDraft = line
                                    }
                                    .padding(vertical = 2.dp),
                                verticalAlignment = Alignment.Top
                            ) {
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
                                    fontWeight = if (isTime) FontWeight.Bold else FontWeight.Normal,
                                    color = if (isTime) p.accentBlue else p.textPrimary,
                                    modifier = Modifier.weight(1f)
                                )
                                Icon(
                                    imageVector = Icons.Default.Edit,
                                    contentDescription = null,
                                    tint = p.textMuted.copy(alpha = 0.45f),
                                    modifier = Modifier
                                        .padding(start = 4.dp, top = 2.dp)
                                        .size(12.dp)
                                )
                            }
                        }
                    }
                }

                HorizontalDivider(color = p.borderColor, thickness = 1.dp)

                // Footer Actions
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(p.cardSurface)
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (primaryActionLabel != null && onPrimaryAction != null) {
                        Button(
                            onClick = {
                                val finalTxt = buildFinalUtf8String()
                                onPrimaryAction(activeTitle, finalTxt, viewingTranslated)
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF7C3AED)),
                            shape = RoundedCornerShape(9.dp)
                        ) {
                            Text(primaryActionLabel, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                    Button(
                        onClick = {
                            val finalTxt = buildFinalUtf8String()
                            onSaveRequest(activeTitle, finalTxt)
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF059669)),
                        shape = RoundedCornerShape(9.dp)
                    ) {
                        Text(stringResource(R.string.sub_editor_btn_save_utf8), color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }

    // Single Line Quick Edit Dialog
    if (editingLineIndex != null) {
        val targetIdx = editingLineIndex!!
        AlertDialog(
            onDismissRequest = { editingLineIndex = null },
            containerColor = p.cardSurface,
            title = {
                Text(
                    stringResource(R.string.sub_editor_edit_line_title, targetIdx + 1),
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    color = p.textPrimary
                )
            },
            text = {
                OutlinedTextField(
                    value = editingLineDraft,
                    onValueChange = { editingLineDraft = it },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2,
                    maxLines = 5,
                    textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace, fontSize = 12.5.sp)
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        val mutableLines = displayedContent.lines().toMutableList()
                        if (targetIdx in mutableLines.indices) {
                            mutableLines[targetIdx] = editingLineDraft
                            val eol = if (currentBaseText.contains("\r\n")) "\r\n" else "\n"
                            val updatedFull = mutableLines.joinToString(eol)
                            if (viewingTranslated) transBaseText = updatedFull else origBaseText = updatedFull
                            cumulativeDelaySec = 0.0
                        }
                        editingLineIndex = null
                    }
                ) {
                    Text(stringResource(R.string.common_save))
                }
            },
            dismissButton = {
                TextButton(onClick = { editingLineIndex = null }) {
                    Text(stringResource(R.string.common_cancel))
                }
            }
        )
    }
}

@Composable
private fun DelayChipButton(
    label: String,
    fgColor: Color,
    bgOverride: Color? = null,
    onClick: () -> Unit
) {
    Surface(
        color = bgOverride ?: fgColor.copy(alpha = 0.16f),
        shape = RoundedCornerShape(7.dp),
        border = BorderStroke(1.dp, fgColor.copy(alpha = 0.45f)),
        modifier = Modifier.clickable(onClick = onClick)
    ) {
        Text(
            text = label,
            color = fgColor,
            fontSize = 11.5.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp)
        )
    }
}

@Composable
fun AutoPairVideoPickerDialog(
    vm: AppViewModel,
    subtitleFileName: String = "subtitle.srt",
    subtitleToSaveContent: String? = null,
    hintQuery: String = "",
    onManualSaveFallback: (() -> Unit)? = null,
    onDismiss: () -> Unit
) {
    val p = AppTheme.palette
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var videos by remember { mutableStateOf<List<SafVideoFile>>(emptyList()) }
    var isLoading by remember { mutableStateOf(false) }

    fun reloadVideos() {
        if (vm.autoPairFolderUri.isBlank()) {
            videos = emptyList()
            return
        }
        scope.launch {
            isLoading = true
            val list = withContext(Dispatchers.IO) {
                SafSubtitleHelper.listVideosInTree(context, vm.autoPairFolderUri)
            }
            videos = list
            isLoading = false
        }
    }

    LaunchedEffect(vm.autoPairFolderUri) {
        reloadVideos()
    }

    val folderPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            vm.updateAutoPairFolder(uri)
            reloadVideos()
        }
    }

    val recommendedVideo = remember(videos, hintQuery, subtitleFileName) {
        SafSubtitleHelper.findBestMatchingVideo(
            videos,
            hintQuery.ifBlank { subtitleFileName }
        )
    }

    val extDisplay = subtitleFileName.substringAfterLast('.', "srt").lowercase(Locale.ROOT)

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            color = p.barSurface,
            shape = RoundedCornerShape(16.dp),
            border = BorderStroke(1.dp, p.borderColor),
            modifier = Modifier
                .fillMaxWidth(0.94f)
                .fillMaxHeight(0.82f)
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(p.cardSurface)
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.autopair_dialog_title),
                            fontWeight = FontWeight.ExtraBold,
                            fontSize = 15.sp,
                            color = p.textPrimary
                        )
                        Text(
                            text = stringResource(R.string.autopair_dialog_desc, extDisplay),
                            fontSize = 11.5.sp,
                            color = p.textMuted
                        )
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = stringResource(R.string.cd_close), tint = p.textPrimary)
                    }
                }

                HorizontalDivider(color = p.borderColor)

                // Folder Bar
                Surface(color = p.subCardSurface, modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                            Text(
                                text = if (vm.autoPairFolderName.isNotBlank()) {
                                    stringResource(R.string.autopair_dialog_folder_current, vm.autoPairFolderName)
                                } else {
                                    stringResource(R.string.settings_autopair_folder_unset)
                                },
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = if (vm.autoPairFolderName.isNotBlank()) p.accentBlue else p.textMuted,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        OutlinedButton(
                            onClick = { folderPickerLauncher.launch(null) },
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                        ) {
                            Icon(Icons.Default.FolderOpen, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(stringResource(R.string.settings_autopair_btn_pick_folder), fontSize = 11.5.sp)
                        }
                    }
                }

                HorizontalDivider(color = p.borderColor)

                // Video List
                Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    when {
                        isLoading -> {
                            CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                        }
                        videos.isEmpty() -> {
                            Column(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(24.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center
                            ) {
                                Icon(Icons.Default.Movie, contentDescription = null, tint = p.textMuted, modifier = Modifier.size(44.dp))
                                Spacer(Modifier.height(8.dp))
                                Text(
                                    text = stringResource(R.string.autopair_dialog_no_videos),
                                    color = p.textMuted,
                                    fontSize = 12.5.sp
                                )
                            }
                        }
                        else -> {
                            val orderedVideos = remember(videos, recommendedVideo) {
                                if (recommendedVideo == null) videos
                                else listOf(recommendedVideo) + videos.filterNot { it.documentId == recommendedVideo.documentId }
                            }
                            LazyColumn(
                                modifier = Modifier.fillMaxSize(),
                                contentPadding = PaddingValues(12.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                items(orderedVideos, key = { it.documentId }) { video ->
                                    val isRec = recommendedVideo?.documentId == video.documentId
                                    val isPinned = vm.autoPairVideoName == video.name
                                    val previewSubName = SafSubtitleHelper.buildPairedSubtitleFileName(video.name, extDisplay)

                                    Surface(
                                        color = if (isRec || isPinned) {
                                            MaterialTheme.colorScheme.primary.copy(alpha = if (p.isLight) 0.12f else 0.18f)
                                        } else {
                                            p.cardSurface
                                        },
                                        shape = RoundedCornerShape(10.dp),
                                        border = BorderStroke(
                                            width = if (isRec || isPinned) 1.5.dp else 1.dp,
                                            color = if (isRec || isPinned) MaterialTheme.colorScheme.primary else p.borderColor
                                        ),
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable {
                                                if (subtitleToSaveContent != null) {
                                                    scope.launch {
                                                        try {
                                                            val savedName = vm.saveSubtitlePairedToSaf(
                                                                videoFileName = video.name,
                                                                subtitleNameOrExt = subtitleFileName,
                                                                contentUtf8 = subtitleToSaveContent,
                                                                pinVideoSelection = true
                                                            )
                                                            vm.emitToast(vm.str(R.string.toast_autopair_saved, savedName))
                                                            onDismiss()
                                                        } catch (e: Exception) {
                                                            vm.emitToast(vm.str(R.string.toast_save_failed, e.message ?: "Error"))
                                                        }
                                                    }
                                                } else {
                                                    vm.updateAutoPairVideoName(video.name)
                                                    onDismiss()
                                                }
                                            }
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(12.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Movie,
                                                contentDescription = null,
                                                tint = p.accentBlue,
                                                modifier = Modifier.size(22.dp)
                                            )
                                            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                                if (isRec) {
                                                    Text(
                                                        text = stringResource(R.string.autopair_dialog_recommended),
                                                        fontSize = 10.5.sp,
                                                        fontWeight = FontWeight.ExtraBold,
                                                        color = p.accentGreen
                                                    )
                                                }
                                                Text(
                                                    text = video.name,
                                                    fontWeight = FontWeight.Bold,
                                                    fontSize = 13.sp,
                                                    color = p.textPrimary,
                                                    maxLines = 2,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                                Text(
                                                    text = "→ $previewSubName" +
                                                        (if (video.sizeBytes > 0L) "  (${Formatters.formatBytes(video.sizeBytes)})" else ""),
                                                    fontSize = 11.sp,
                                                    color = p.accentBlue,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                            }
                                            if (isPinned) {
                                                Icon(
                                                    imageVector = Icons.Default.CheckCircle,
                                                    contentDescription = null,
                                                    tint = MaterialTheme.colorScheme.primary,
                                                    modifier = Modifier.size(20.dp)
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                HorizontalDivider(color = p.borderColor)

                // Footer
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(p.cardSurface)
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (subtitleToSaveContent == null && vm.autoPairVideoName.isNotBlank()) {
                        TextButton(
                            onClick = {
                                vm.updateAutoPairVideoName("")
                                onDismiss()
                            }
                        ) {
                            Text(stringResource(R.string.settings_autopair_btn_clear_video), fontSize = 12.sp, color = p.accentAmber)
                        }
                    } else if (onManualSaveFallback != null) {
                        OutlinedButton(
                            onClick = {
                                onDismiss()
                                onManualSaveFallback()
                            }
                        ) {
                            Text(stringResource(R.string.autopair_btn_save_manual), fontSize = 12.sp)
                        }
                    } else {
                        Spacer(Modifier.width(1.dp))
                    }

                    TextButton(onClick = onDismiss) {
                        Text(stringResource(R.string.common_close), fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}
