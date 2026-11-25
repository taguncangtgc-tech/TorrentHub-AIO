package com.torrenthub.aio.ui

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.torrenthub.aio.R
import com.torrenthub.aio.data.GeminiEngine
import com.torrenthub.aio.data.SubtitleTargetLanguage
import com.torrenthub.aio.model.GeminiJob
import com.torrenthub.aio.model.TranslationProvider
import com.torrenthub.aio.util.SubtitleCharset
import com.torrenthub.aio.util.SubtitleTools
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun translationProviderTitle(provider: TranslationProvider): String = when (provider) {
    TranslationProvider.GOOGLE_FREE -> stringResource(R.string.tr_provider_google_free)
    TranslationProvider.GEMINI -> stringResource(R.string.tr_provider_gemini)
    TranslationProvider.DEEPL -> stringResource(R.string.tr_provider_deepl)
    TranslationProvider.OPENAI -> stringResource(R.string.tr_provider_openai)
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun GeminiScreen(vm: AppViewModel) {
    val p = AppTheme.palette
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var showAdvanced by remember { mutableStateOf(false) }
    var modelDropdownOpen by remember { mutableStateOf(false) }
    var deeplModelDropdownOpen by remember { mutableStateOf(false) }
    var targetLangDropdownOpen by remember { mutableStateOf(false) }
    var modeDropdownOpen by remember { mutableStateOf(false) }
    var nameModeDropdownOpen by remember { mutableStateOf(false) }

    var pendingSaveFileName by remember { mutableStateOf("subtitle.srt") }
    var pendingSaveContent by remember { mutableStateOf<String?>(null) }
    var showAutoPairSaveDialog by remember { mutableStateOf(false) }
    var showAutoPairPickerDialog by remember { mutableStateOf(false) }

    var activeEditorJob by remember { mutableStateOf<GeminiJob?>(null) }
    var activeEditorStartTranslated by remember { mutableStateOf(false) }
    var pendingLimitLinesAfterPerm by remember { mutableStateOf<Int?>(null) }

    val notifPermLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { _ ->
        val limit = pendingLimitLinesAfterPerm
        pendingLimitLinesAfterPerm = null
        if (limit != null) {
            vm.startGeminiTranslation(limitLines = limit)
        }
    }

    fun launchGeminiWithNotifCheck(limitLines: Int) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            pendingLimitLinesAfterPerm = limitLines
            notifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            vm.startGeminiTranslation(limitLines = limitLines)
        }
    }

    val openFilesLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris: List<Uri> ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        scope.launch(Dispatchers.IO) {
            uris.forEach { uri ->
                try {
                    var fileName = "subtitle.srt"
                    context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                        val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (nameIndex >= 0 && cursor.moveToFirst()) {
                            fileName = cursor.getString(nameIndex) ?: fileName
                        }
                    }
                    val bytes = context.contentResolver.openInputStream(uri)?.readBytes() ?: ByteArray(0)
                    val content = SubtitleTools.decodeWithCharset(bytes, SubtitleCharset.AUTO)
                    if (content.isNotBlank()) {
                        withContext(Dispatchers.Main) {
                            vm.addGeminiJob(fileName, content)
                        }
                    }
                } catch (e: Exception) {
                    vm.emitToast(vm.str(R.string.toast_read_file_failed, e.message ?: "Error"))
                }
            }
        }
    }

    val saveResultLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("text/plain")
    ) { uri ->
        val txt = pendingSaveContent
        if (uri != null && txt != null) {
            scope.launch(Dispatchers.IO) {
                try {
                    val outBytes = SubtitleTools.normalizeToUtf8Bytes(txt, includeBom = txt.startsWith("\uFEFF"))
                    context.contentResolver.openOutputStream(uri)?.use { out ->
                        out.write(outBytes)
                    }
                    vm.emitToast(vm.str(R.string.toast_translation_saved))
                } catch (e: Exception) {
                    vm.emitToast(vm.str(R.string.toast_save_failed, e.message ?: "Error"))
                }
            }
        }
    }

    fun requestSaveTranslated(fileName: String, content: String) {
        pendingSaveFileName = fileName
        pendingSaveContent = content
        if (vm.autoPairSubtitleVideo) {
            if (vm.autoPairFolderUri.isNotBlank() && vm.autoPairVideoName.isNotBlank()) {
                scope.launch {
                    try {
                        val saved = vm.saveSubtitlePairedToSaf(
                            videoFileName = vm.autoPairVideoName,
                            subtitleNameOrExt = fileName,
                            contentUtf8 = content
                        )
                        vm.emitToast(vm.str(R.string.toast_autopair_saved, saved))
                    } catch (_: Exception) {
                        showAutoPairSaveDialog = true
                    }
                }
            } else {
                showAutoPairSaveDialog = true
            }
        } else {
            saveResultLauncher.launch(fileName)
        }
    }

    val parsedKeys = remember(vm.geminiKeysRaw) { GeminiEngine.parseKeys(vm.geminiKeysRaw) }
    val freeCount = parsedKeys.count { !GeminiEngine.isOpenRouterKey(it) }
    val orCount = parsedKeys.count { GeminiEngine.isOpenRouterKey(it) }

    val targetLangDisplay = subtitleLangDisplayLabel(vm.subtitleTargetLang)
    val statusDisplay = vm.geminiStatusText ?: stringResource(R.string.gemini_status_ready)
    val previewLogDisplay = vm.geminiPreviewLog ?: stringResource(R.string.gemini_preview_idle)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // Card 1: Translation Provider, API Keys, Model & Target Language
        Surface(color = p.cardSurface, shape = RoundedCornerShape(12.dp), border = p.cardBorder) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    stringResource(R.string.gemini_card_title),
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 15.sp,
                    color = p.textPrimary
                )

                // Provider Selector Chips
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    TranslationProvider.entries.forEach { prov ->
                        FilterChip(
                            selected = vm.translationProvider == prov,
                            onClick = { vm.updateTranslationProvider(prov) },
                            label = {
                                Text(
                                    text = translationProviderTitle(prov),
                                    fontSize = 12.sp,
                                    fontWeight = if (vm.translationProvider == prov) FontWeight.ExtraBold else FontWeight.Medium
                                )
                            }
                        )
                    }
                }

                // Dynamic Provider Configuration Fields
                when (vm.translationProvider) {
                    TranslationProvider.GOOGLE_FREE -> {
                        Surface(
                            color = p.accentGreen.copy(alpha = 0.14f),
                            shape = RoundedCornerShape(8.dp),
                            border = BorderStroke(1.dp, p.accentGreen.copy(alpha = 0.4f)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = stringResource(R.string.tr_google_free_ready_banner),
                                    fontSize = 11.5.sp,
                                    color = p.accentGreen,
                                    fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.weight(1f)
                                )
                                OutlinedButton(
                                    onClick = { vm.testGeminiKeys() },
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                                ) {
                                    Text(stringResource(R.string.gemini_btn_test_conn), fontSize = 11.5.sp)
                                }
                            }
                        }
                    }

                    TranslationProvider.GEMINI -> {
                        OutlinedTextField(
                            value = vm.geminiKeysRaw,
                            onValueChange = {
                                vm.geminiKeysRaw = it
                                vm.saveGeminiSettings()
                            },
                            modifier = Modifier.fillMaxWidth(),
                            minLines = 2,
                            maxLines = 4,
                            label = { Text(stringResource(R.string.gemini_keys_label)) },
                            placeholder = { Text(stringResource(R.string.gemini_keys_placeholder), fontSize = 12.sp) },
                            textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                stringResource(R.string.gemini_keys_detected, freeCount, orCount),
                                fontSize = 11.sp,
                                color = p.textMuted,
                                modifier = Modifier.weight(1f)
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                OutlinedButton(
                                    onClick = { vm.testGeminiKeys() },
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                                ) {
                                    Text(stringResource(R.string.gemini_btn_test_conn), fontSize = 11.5.sp)
                                }
                                OutlinedButton(
                                    onClick = { vm.refreshGeminiModels() },
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                                ) {
                                    Text(stringResource(R.string.gemini_btn_check_models), fontSize = 11.5.sp)
                                }
                            }
                        }

                        ExposedDropdownMenuBox(
                            expanded = modelDropdownOpen,
                            onExpandedChange = { modelDropdownOpen = it }
                        ) {
                            OutlinedTextField(
                                value = vm.geminiModel,
                                onValueChange = {
                                    vm.geminiModel = it
                                    vm.saveGeminiSettings()
                                },
                                label = { Text(stringResource(R.string.gemini_model_label)) },
                                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = modelDropdownOpen) },
                                modifier = Modifier
                                    .menuAnchor()
                                    .fillMaxWidth(),
                                singleLine = true,
                                textStyle = LocalTextStyle.current.copy(fontSize = 13.sp)
                            )
                            ExposedDropdownMenu(
                                expanded = modelDropdownOpen,
                                onDismissRequest = { modelDropdownOpen = false }
                            ) {
                                vm.geminiAvailableModels.forEach { m ->
                                    DropdownMenuItem(
                                        text = { Text(m, fontSize = 13.sp) },
                                        onClick = {
                                            vm.geminiModel = m
                                            vm.saveGeminiSettings()
                                            modelDropdownOpen = false
                                        }
                                    )
                                }
                            }
                        }
                    }

                    TranslationProvider.DEEPL -> {
                        OutlinedTextField(
                            value = vm.deeplApiKey,
                            onValueChange = {
                                vm.deeplApiKey = it
                                vm.saveGeminiSettings()
                            },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            label = { Text(stringResource(R.string.tr_api_key_optional_label, "DeepL")) },
                            placeholder = { Text(stringResource(R.string.tr_api_key_deepl_placeholder), fontSize = 12.sp) },
                            textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                        )

                        ExposedDropdownMenuBox(
                            expanded = deeplModelDropdownOpen,
                            onExpandedChange = { deeplModelDropdownOpen = it }
                        ) {
                            val deeplLabel = if (vm.deeplModel == "latency_optimized") {
                                stringResource(R.string.tr_deepl_quality_latency)
                            } else {
                                stringResource(R.string.tr_deepl_quality_prefer)
                            }
                            OutlinedTextField(
                                value = deeplLabel,
                                onValueChange = {},
                                readOnly = true,
                                label = { Text(stringResource(R.string.tr_model_custom_label, "DeepL")) },
                                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = deeplModelDropdownOpen) },
                                modifier = Modifier.menuAnchor().fillMaxWidth(),
                                textStyle = LocalTextStyle.current.copy(fontSize = 13.sp)
                            )
                            ExposedDropdownMenu(
                                expanded = deeplModelDropdownOpen,
                                onDismissRequest = { deeplModelDropdownOpen = false }
                            ) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.tr_deepl_quality_prefer)) },
                                    onClick = {
                                        vm.deeplModel = "prefer_quality_optimized"
                                        vm.saveGeminiSettings()
                                        deeplModelDropdownOpen = false
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.tr_deepl_quality_latency)) },
                                    onClick = {
                                        vm.deeplModel = "latency_optimized"
                                        vm.saveGeminiSettings()
                                        deeplModelDropdownOpen = false
                                    }
                                )
                            }
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            OutlinedTextField(
                                value = vm.deeplEndpoint,
                                onValueChange = {
                                    vm.deeplEndpoint = it
                                    vm.saveGeminiSettings()
                                },
                                modifier = Modifier.weight(1f),
                                singleLine = true,
                                label = { Text(stringResource(R.string.tr_endpoint_label)) },
                                placeholder = { Text(GeminiEngine.DEFAULT_DEEPL_FREE_ENDPOINT, fontSize = 11.5.sp) },
                                textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace, fontSize = 11.5.sp)
                            )
                            OutlinedButton(onClick = { vm.testGeminiKeys() }) {
                                Text(stringResource(R.string.gemini_btn_test_conn), fontSize = 11.5.sp)
                            }
                        }
                    }

                    TranslationProvider.OPENAI -> {
                        OutlinedTextField(
                            value = vm.openaiApiKey,
                            onValueChange = {
                                vm.openaiApiKey = it
                                vm.saveGeminiSettings()
                            },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            label = { Text(stringResource(R.string.tr_api_key_optional_label, "OpenAI / Compatible")) },
                            placeholder = { Text(stringResource(R.string.tr_api_key_openai_placeholder), fontSize = 12.sp) },
                            textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                        )

                        OutlinedTextField(
                            value = vm.openaiEndpoint,
                            onValueChange = {
                                vm.openaiEndpoint = it
                                vm.saveGeminiSettings()
                            },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            label = { Text(stringResource(R.string.tr_endpoint_label)) },
                            placeholder = { Text(GeminiEngine.DEFAULT_OPENAI_ENDPOINT, fontSize = 11.5.sp) },
                            textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace, fontSize = 11.5.sp)
                        )

                        ExposedDropdownMenuBox(
                            expanded = modelDropdownOpen,
                            onExpandedChange = { modelDropdownOpen = it }
                        ) {
                            OutlinedTextField(
                                value = vm.openaiModel,
                                onValueChange = {
                                    vm.openaiModel = it
                                    vm.saveGeminiSettings()
                                },
                                singleLine = true,
                                label = { Text(stringResource(R.string.tr_model_custom_label, "OpenAI")) },
                                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = modelDropdownOpen) },
                                modifier = Modifier.menuAnchor().fillMaxWidth(),
                                textStyle = LocalTextStyle.current.copy(fontSize = 13.sp)
                            )
                            ExposedDropdownMenu(
                                expanded = modelDropdownOpen,
                                onDismissRequest = { modelDropdownOpen = false }
                            ) {
                                vm.openaiAvailableModels.forEach { m ->
                                    DropdownMenuItem(
                                        text = { Text(m, fontSize = 13.sp) },
                                        onClick = {
                                            vm.openaiModel = m
                                            vm.saveGeminiSettings()
                                            modelDropdownOpen = false
                                        }
                                    )
                                }
                            }
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.End)
                        ) {
                            OutlinedButton(
                                onClick = { vm.testGeminiKeys() },
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                            ) {
                                Text(stringResource(R.string.gemini_btn_test_conn), fontSize = 11.5.sp)
                            }
                            OutlinedButton(
                                onClick = { vm.refreshGeminiModels() },
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                            ) {
                                Text(stringResource(R.string.gemini_btn_check_models), fontSize = 11.5.sp)
                            }
                        }
                    }
                }

                // Target Language Selector (Synced with Settings & Subtitle Tab)
                ExposedDropdownMenuBox(
                    expanded = targetLangDropdownOpen,
                    onExpandedChange = { targetLangDropdownOpen = it }
                ) {
                    OutlinedTextField(
                        value = "${vm.subtitleTargetLang.flag} $targetLangDisplay",
                        onValueChange = {},
                        readOnly = true,
                        label = { Text(stringResource(R.string.gemini_target_lang_label)) },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = targetLangDropdownOpen) },
                        modifier = Modifier
                            .menuAnchor()
                            .fillMaxWidth(),
                        textStyle = LocalTextStyle.current.copy(fontSize = 13.sp)
                    )
                    ExposedDropdownMenu(
                        expanded = targetLangDropdownOpen,
                        onDismissRequest = { targetLangDropdownOpen = false }
                    ) {
                        SubtitleTargetLanguage.entries.forEach { lang ->
                            DropdownMenuItem(
                                text = { Text("${lang.flag} ${subtitleLangDisplayLabel(lang)}") },
                                onClick = {
                                    vm.updateSubtitleTargetLang(lang)
                                    targetLangDropdownOpen = false
                                }
                            )
                        }
                    }
                }

                // Task Mode Selector
                ExposedDropdownMenuBox(
                    expanded = modeDropdownOpen,
                    onExpandedChange = { modeDropdownOpen = it }
                ) {
                    OutlinedTextField(
                        value = if (vm.geminiTaskMode == "polish") {
                            stringResource(R.string.gemini_mode_polish_lang, targetLangDisplay)
                        } else {
                            stringResource(R.string.gemini_mode_translate_to, targetLangDisplay)
                        },
                        onValueChange = {},
                        readOnly = true,
                        label = { Text(stringResource(R.string.gemini_task_mode_label)) },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = modeDropdownOpen) },
                        modifier = Modifier
                            .menuAnchor()
                            .fillMaxWidth(),
                        textStyle = LocalTextStyle.current.copy(fontSize = 13.sp)
                    )
                    ExposedDropdownMenu(
                        expanded = modeDropdownOpen,
                        onDismissRequest = { modeDropdownOpen = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.gemini_mode_translate_to, targetLangDisplay)) },
                            onClick = {
                                vm.geminiTaskMode = "translate"
                                vm.saveGeminiSettings()
                                modeDropdownOpen = false
                            }
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.gemini_mode_polish_lang, targetLangDisplay)) },
                            onClick = {
                                vm.geminiTaskMode = "polish"
                                vm.saveGeminiSettings()
                                modeDropdownOpen = false
                            }
                        )
                    }
                }

                // Collapsible Advanced Options
                Surface(
                    color = p.subCardSurface,
                    shape = RoundedCornerShape(8.dp),
                    border = if (p.isLight || p.isAmoled) BorderStroke(1.dp, p.borderColor) else null,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { showAdvanced = !showAdvanced }
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            stringResource(R.string.gemini_advanced_header),
                            fontSize = 12.5.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = p.textPrimary
                        )
                        Icon(
                            if (showAdvanced) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                            contentDescription = null,
                            tint = p.textPrimary
                        )
                    }
                }

                if (showAdvanced) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        ExposedDropdownMenuBox(
                            expanded = nameModeDropdownOpen,
                            onExpandedChange = { nameModeDropdownOpen = it }
                        ) {
                            val nameModeLabel = when (vm.geminiNameMode) {
                                "code-upper" -> stringResource(R.string.gemini_name_code_upper)
                                "upper-full" -> stringResource(R.string.gemini_name_upper_full)
                                ".id", ".lang" -> stringResource(R.string.gemini_name_lang_suffix, vm.subtitleTargetLang.code)
                                else -> stringResource(R.string.gemini_name_orig)
                            }
                            OutlinedTextField(
                                value = nameModeLabel,
                                onValueChange = {},
                                readOnly = true,
                                label = { Text(stringResource(R.string.gemini_name_mode_label)) },
                                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = nameModeDropdownOpen) },
                                modifier = Modifier.menuAnchor().fillMaxWidth(),
                                textStyle = LocalTextStyle.current.copy(fontSize = 12.5.sp)
                            )
                            ExposedDropdownMenu(
                                expanded = nameModeDropdownOpen,
                                onDismissRequest = { nameModeDropdownOpen = false }
                            ) {
                                listOf(
                                    "code-upper" to stringResource(R.string.gemini_name_code_upper),
                                    "upper-full" to stringResource(R.string.gemini_name_upper_full),
                                    "" to stringResource(R.string.gemini_name_orig),
                                    ".id" to stringResource(R.string.gemini_name_lang_suffix, vm.subtitleTargetLang.code)
                                ).forEach { (valKey, labelTxt) ->
                                    DropdownMenuItem(
                                        text = { Text(labelTxt) },
                                        onClick = {
                                            vm.geminiNameMode = valKey
                                            vm.saveGeminiSettings()
                                            nameModeDropdownOpen = false
                                        }
                                    )
                                }
                            }
                        }

                        val activeNotes = if (vm.geminiTaskMode == "polish") vm.geminiNotesPolish else vm.geminiNotesTranslate
                        OutlinedTextField(
                            value = activeNotes,
                            onValueChange = {
                                if (vm.geminiTaskMode == "polish") vm.geminiNotesPolish = it else vm.geminiNotesTranslate = it
                                vm.saveGeminiSettings()
                            },
                            modifier = Modifier.fillMaxWidth(),
                            minLines = 2,
                            maxLines = 4,
                            label = {
                                Text(
                                    if (vm.geminiTaskMode == "polish") stringResource(R.string.gemini_notes_label_polish)
                                    else stringResource(R.string.gemini_notes_label_translate)
                                )
                            },
                            textStyle = LocalTextStyle.current.copy(fontSize = 12.sp)
                        )

                        OutlinedTextField(
                            value = vm.geminiGlossary,
                            onValueChange = {
                                vm.geminiGlossary = it
                                vm.saveGeminiSettings()
                            },
                            modifier = Modifier.fillMaxWidth(),
                            minLines = 2,
                            maxLines = 3,
                            label = { Text(stringResource(R.string.gemini_glossary_label)) },
                            textStyle = LocalTextStyle.current.copy(fontSize = 12.sp)
                        )

                        ToggleOptionRow(stringResource(R.string.gemini_opt_autodetect), vm.geminiAutoDetect) {
                            vm.geminiAutoDetect = it; vm.saveGeminiSettings()
                        }
                        ToggleOptionRow(stringResource(R.string.gemini_opt_safety), vm.geminiRelaxSafety) {
                            vm.geminiRelaxSafety = it; vm.saveGeminiSettings()
                        }
                        ToggleOptionRow(stringResource(R.string.gemini_opt_all_lines), vm.geminiIncludeAllLines) {
                            vm.geminiIncludeAllLines = it; vm.saveGeminiSettings()
                        }
                        ToggleOptionRow(stringResource(R.string.gemini_opt_sliding_ctx), vm.geminiSlidingContext) {
                            vm.geminiSlidingContext = it; vm.saveGeminiSettings()
                        }
                        ToggleOptionRow(stringResource(R.string.gemini_opt_clean_ads), vm.geminiCleanAds) {
                            vm.geminiCleanAds = it; vm.rebuildGeminiOutputs()
                        }
                        ToggleOptionRow(stringResource(R.string.gemini_opt_bilingual), vm.geminiBilingual) {
                            vm.geminiBilingual = it; vm.rebuildGeminiOutputs()
                        }

                        OutlinedTextField(
                            value = vm.geminiTimeShiftSec,
                            onValueChange = {
                                vm.geminiTimeShiftSec = it
                                vm.rebuildGeminiOutputs()
                            },
                            singleLine = true,
                            label = { Text(stringResource(R.string.gemini_timeshift_label)) },
                            modifier = Modifier.fillMaxWidth(),
                            textStyle = LocalTextStyle.current.copy(fontSize = 12.5.sp)
                        )
                    }
                }
            }
        }

        // Card 2: File Queue & Controls
        Surface(color = p.cardSurface, shape = RoundedCornerShape(12.dp), border = p.cardBorder) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = { openFilesLauncher.launch(arrayOf("*/*")) },
                        enabled = !vm.isGeminiRunning,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2563EB))
                    ) {
                        Text(stringResource(R.string.gemini_btn_pick_files), fontSize = 12.5.sp, color = Color.White)
                    }
                    if (vm.geminiJobs.isNotEmpty()) {
                        OutlinedButton(
                            onClick = { vm.clearGeminiJobs() },
                            enabled = !vm.isGeminiRunning
                        ) {
                            Text(stringResource(R.string.gemini_btn_clear_queue), fontSize = 12.sp)
                        }
                    }
                }

                // Auto-Pairing Local Video (SAF) toggle
                Surface(
                    color = p.subCardSurface,
                    shape = RoundedCornerShape(8.dp),
                    border = if (p.isLight || p.isAmoled) BorderStroke(1.dp, p.borderColor) else null,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { showAutoPairPickerDialog = true }
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                stringResource(R.string.settings_autopair_enable),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = p.textPrimary
                            )
                            Text(
                                when {
                                    vm.autoPairVideoName.isNotBlank() -> "🎬 ${vm.autoPairVideoName}"
                                    vm.autoPairFolderName.isNotBlank() -> "📁 ${vm.autoPairFolderName}"
                                    else -> stringResource(R.string.settings_autopair_folder_unset)
                                },
                                fontSize = 10.5.sp,
                                color = if (vm.autoPairFolderUri.isNotBlank()) p.accentBlue else p.textMuted,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        Switch(
                            checked = vm.autoPairSubtitleVideo,
                            onCheckedChange = { enabled ->
                                vm.updateAutoPairEnabled(enabled)
                                if (enabled && vm.autoPairFolderUri.isBlank()) {
                                    showAutoPairPickerDialog = true
                                }
                            }
                        )
                    }
                }

                // Auto-upload to PikPak Drive toggle
                Surface(
                    color = p.subCardSurface,
                    shape = RoundedCornerShape(8.dp),
                    border = if (p.isLight || p.isAmoled) BorderStroke(1.dp, p.borderColor) else null,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                vm.geminiAutoUploadPikPak = !vm.geminiAutoUploadPikPak
                                vm.saveGeminiSettings()
                            }
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                stringResource(R.string.gemini_auto_upload_title),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = p.textPrimary
                            )
                            Text(
                                if (vm.pikpakCreds.isLoggedIn) stringResource(R.string.gemini_auto_upload_folder, vm.pikpakCreds.targetFolderName)
                                else stringResource(R.string.gemini_auto_upload_not_logged),
                                fontSize = 10.5.sp,
                                color = if (vm.pikpakCreds.isLoggedIn) p.accentBlue else p.textMuted
                            )
                        }
                        Switch(
                            checked = vm.geminiAutoUploadPikPak,
                            onCheckedChange = {
                                vm.geminiAutoUploadPikPak = it
                                vm.saveGeminiSettings()
                            }
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Button(
                        onClick = { launchGeminiWithNotifCheck(limitLines = 10) },
                        enabled = !vm.isGeminiRunning && vm.geminiJobs.isNotEmpty(),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF7C3AED)),
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 10.dp)
                    ) {
                        Text(stringResource(R.string.gemini_btn_test_10), fontSize = 12.sp, color = Color.White)
                    }
                    Button(
                        onClick = { launchGeminiWithNotifCheck(limitLines = 0) },
                        enabled = !vm.isGeminiRunning && vm.geminiJobs.isNotEmpty(),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF059669)),
                        modifier = Modifier.weight(1.3f),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 10.dp)
                    ) {
                        Text(
                            if (vm.geminiTaskMode == "polish") stringResource(R.string.gemini_btn_run_polish)
                            else stringResource(R.string.gemini_btn_run_translate),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                    }
                    if (vm.isGeminiRunning) {
                        Button(
                            onClick = { vm.stopGeminiRun() },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFDC2626)),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 10.dp)
                        ) {
                            Text(stringResource(R.string.gemini_btn_stop), fontSize = 12.sp, color = Color.White)
                        }
                    }
                }

                Text(
                    statusDisplay,
                    fontSize = 12.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = p.accentBlue
                )

                // Job Items
                vm.geminiJobs.forEachIndexed { idx, job ->
                    val outName = GeminiEngine.formatOutputFileName(job.fileName, vm.geminiNameMode, vm.subtitleTargetLang.code)
                    val isUploadingThis = vm.uploadingGeminiJobId == job.id
                    Surface(
                        color = p.subCardSurface,
                        shape = RoundedCornerShape(10.dp),
                        border = if (p.isLight || p.isAmoled) BorderStroke(1.dp, p.borderColor) else null
                    ) {
                        Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(
                                "#${idx + 1} ${job.fileName} → $outName",
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp,
                                color = p.textPrimary,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(job.statusText, fontSize = 11.5.sp, color = p.textSecondary)
                            LinearProgressIndicator(
                                progress = { (job.pct / 100f).coerceIn(0f, 1f) },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(6.dp),
                                color = MaterialTheme.colorScheme.primary,
                                trackColor = p.chipBg
                            )
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Button(
                                    onClick = {
                                        val res = job.resultContent ?: return@Button
                                        requestSaveTranslated(outName, res)
                                    },
                                    enabled = !job.resultContent.isNullOrBlank(),
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF059669)),
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                                ) {
                                    Text(stringResource(R.string.gemini_btn_save_phone), fontSize = 11.5.sp, color = Color.White)
                                }
                                Button(
                                    onClick = { vm.uploadGeminiJobToPikPak(job.id) },
                                    enabled = !job.resultContent.isNullOrBlank() && !isUploadingThis,
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = if (job.uploadedToPikPak) Color(0xFF0369A1) else Color(0xFF0284C7)
                                    ),
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                                ) {
                                    Text(
                                        when {
                                            isUploadingThis -> stringResource(R.string.gemini_btn_uploading)
                                            job.uploadedToPikPak -> stringResource(R.string.gemini_btn_uploaded)
                                            else -> stringResource(R.string.gemini_btn_upload_pikpak)
                                        },
                                        fontSize = 11.5.sp,
                                        color = Color.White
                                    )
                                }
                                OutlinedButton(
                                    onClick = {
                                        activeEditorJob = job
                                        activeEditorStartTranslated = !job.resultContent.isNullOrBlank()
                                    },
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                                ) {
                                    Text(
                                        if (!job.resultContent.isNullOrBlank()) stringResource(R.string.gemini_btn_view_result)
                                        else stringResource(R.string.sub_btn_preview_edit),
                                        fontSize = 11.5.sp
                                    )
                                }
                                if (!vm.isGeminiRunning && (job.hasSavedProgress || job.pct > 0)) {
                                    TextButton(onClick = { vm.resetSingleGeminiJob(job.id) }) {
                                        Text(stringResource(R.string.common_reset), fontSize = 11.5.sp, color = p.accentAmber)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // Card 3: Console / Preview Box
        Surface(
            color = p.consoleSurface,
            shape = RoundedCornerShape(10.dp),
            border = if (p.isLight || p.isAmoled) BorderStroke(1.dp, p.borderColor) else null
        ) {
            Text(
                text = previewLogDisplay,
                fontFamily = FontFamily.Monospace,
                fontSize = 11.5.sp,
                color = p.textPrimary,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp)
            )
        }
    }

    if (activeEditorJob != null) {
        val job = activeEditorJob!!
        val outName = GeminiEngine.formatOutputFileName(job.fileName, vm.geminiNameMode, vm.subtitleTargetLang.code)
        SubtitleEditorDialog(
            vm = vm,
            fileName = job.fileName,
            initialContent = job.originalContent,
            translatedFileName = outName,
            translatedContent = job.resultContent,
            startOnTranslated = activeEditorStartTranslated,
            primaryActionLabel = stringResource(R.string.sub_editor_btn_apply_job),
            onPrimaryAction = { _, editedTxt, isTranslatedStage ->
                vm.updateGeminiJobEditedContent(job.id, editedTxt, isTranslatedStage)
                activeEditorJob = null
            },
            onSaveRequest = { fName, utf8Txt ->
                requestSaveTranslated(fName, utf8Txt)
            },
            onDismiss = { activeEditorJob = null }
        )
    }

    if (showAutoPairSaveDialog && pendingSaveContent != null) {
        AutoPairVideoPickerDialog(
            vm = vm,
            subtitleFileName = pendingSaveFileName,
            subtitleToSaveContent = pendingSaveContent,
            hintQuery = pendingSaveFileName,
            onManualSaveFallback = { saveResultLauncher.launch(pendingSaveFileName) },
            onDismiss = { showAutoPairSaveDialog = false }
        )
    }

    if (showAutoPairPickerDialog) {
        AutoPairVideoPickerDialog(
            vm = vm,
            subtitleFileName = vm.geminiJobs.firstOrNull()?.fileName ?: "subtitle.srt",
            subtitleToSaveContent = null,
            hintQuery = vm.geminiJobs.firstOrNull()?.fileName.orEmpty(),
            onDismiss = { showAutoPairPickerDialog = false }
        )
    }
}

@Composable
private fun ToggleOptionRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    val p = AppTheme.palette
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onChange(!checked) }
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, fontSize = 12.sp, color = p.textPrimary, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
