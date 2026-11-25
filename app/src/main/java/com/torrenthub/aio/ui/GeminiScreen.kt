package com.torrenthub.aio.ui

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
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
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import com.torrenthub.aio.data.GeminiEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun GeminiScreen(vm: AppViewModel) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var showAdvanced by remember { mutableStateOf(false) }
    var modelDropdownOpen by remember { mutableStateOf(false) }
    var modeDropdownOpen by remember { mutableStateOf(false) }
    var nameModeDropdownOpen by remember { mutableStateOf(false) }

    var pendingSaveContent by remember { mutableStateOf<String?>(null) }
    var previewJobTitle by remember { mutableStateOf<String?>(null) }
    var previewJobResult by remember { mutableStateOf("") }
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
                    val content = context.contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.readText().orEmpty()
                    if (content.isNotBlank()) {
                        withContext(Dispatchers.Main) {
                            vm.addGeminiJob(fileName, content)
                        }
                    }
                } catch (e: Exception) {
                    vm.emitToast("❌ Gagal membaca file: ${e.message}")
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
                    context.contentResolver.openOutputStream(uri)?.use { out ->
                        out.write(txt.toByteArray(Charsets.UTF_8))
                    }
                    vm.emitToast("✅ Hasil terjemahan berhasil disimpan!")
                } catch (e: Exception) {
                    vm.emitToast("❌ Gagal menyimpan: ${e.message}")
                }
            }
        }
    }

    val parsedKeys = remember(vm.geminiKeysRaw) { GeminiEngine.parseKeys(vm.geminiKeysRaw) }
    val freeCount = parsedKeys.count { !GeminiEngine.isOpenRouterKey(it) }
    val orCount = parsedKeys.count { GeminiEngine.isOpenRouterKey(it) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // Card 1: API Keys & Model
        Surface(color = Color(0xFF1E293B), shape = RoundedCornerShape(12.dp)) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "✨ Gemini Subtitle Translator Hybrid",
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 15.sp,
                    color = Color(0xFFF8FAFC)
                )

                OutlinedTextField(
                    value = vm.geminiKeysRaw,
                    onValueChange = {
                        vm.geminiKeysRaw = it
                        vm.saveGeminiSettings()
                    },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2,
                    maxLines = 4,
                    label = { Text("API Keys (Google AI Studio / OpenRouter)") },
                    placeholder = { Text("Tempel AIzaSy... atau sk-or-v1-... (pisahkan baris baru)", fontSize = 12.sp) },
                    textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "Terdeteksi: $freeCount Google + $orCount OpenRouter",
                        fontSize = 11.sp,
                        color = Color(0xFF94A3B8)
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        OutlinedButton(
                            onClick = { vm.testGeminiKeys() },
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                        ) {
                            Text("Tes Koneksi", fontSize = 11.5.sp)
                        }
                        OutlinedButton(
                            onClick = { vm.refreshGeminiModels() },
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                        ) {
                            Text("Cek Model", fontSize = 11.5.sp)
                        }
                    }
                }

                // Model Selector
                ExposedDropdownMenuBox(
                    expanded = modelDropdownOpen,
                    onExpandedChange = { modelDropdownOpen = it }
                ) {
                    OutlinedTextField(
                        value = vm.geminiModel,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Model AI (Auto-Fallback jika 503/Sibuk)") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = modelDropdownOpen) },
                        modifier = Modifier
                            .menuAnchor()
                            .fillMaxWidth(),
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

                // Task Mode Selector
                ExposedDropdownMenuBox(
                    expanded = modeDropdownOpen,
                    onExpandedChange = { modeDropdownOpen = it }
                ) {
                    OutlinedTextField(
                        value = if (vm.geminiTaskMode == "polish") "Perbaiki SRT Bahasa Indonesia (MTL)"
                        else "Terjemahkan ke Bahasa Indonesia",
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Mode Tugas") },
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
                            text = { Text("Terjemahkan ke Bahasa Indonesia") },
                            onClick = {
                                vm.geminiTaskMode = "translate"
                                vm.saveGeminiSettings()
                                modeDropdownOpen = false
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Perbaiki SRT Bahasa Indonesia (Hasil Mesin)") },
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
                    color = Color(0xFF0F172A),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { showAdvanced = !showAdvanced }
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Pengaturan Prompt, Glosarium & Format Output", fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold)
                        Icon(if (showAdvanced) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null)
                    }
                }

                if (showAdvanced) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        ExposedDropdownMenuBox(
                            expanded = nameModeDropdownOpen,
                            onExpandedChange = { nameModeDropdownOpen = it }
                        ) {
                            val nameModeLabel = when (vm.geminiNameMode) {
                                "code-upper" -> "Kode Huruf Besar (JUL-334.srt)"
                                "upper-full" -> "Nama Penuh Huruf Besar"
                                ".id" -> "Nama Asli + .id.srt"
                                else -> "Nama Asli Apa Adanya"
                            }
                            OutlinedTextField(
                                value = nameModeLabel,
                                onValueChange = {},
                                readOnly = true,
                                label = { Text("Format Nama Output") },
                                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = nameModeDropdownOpen) },
                                modifier = Modifier.menuAnchor().fillMaxWidth(),
                                textStyle = LocalTextStyle.current.copy(fontSize = 12.5.sp)
                            )
                            ExposedDropdownMenu(
                                expanded = nameModeDropdownOpen,
                                onDismissRequest = { nameModeDropdownOpen = false }
                            ) {
                                listOf(
                                    "code-upper" to "Kode Huruf Besar (JUL-334.srt)",
                                    "upper-full" to "Nama Penuh Huruf Besar",
                                    "" to "Nama Asli Apa Adanya",
                                    ".id" to "Nama Asli + .id.srt"
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
                            label = { Text("Catatan Gaya Bahasa (${if (vm.geminiTaskMode == "polish") "Perbaikan" else "Terjemah"})") },
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
                            label = { Text("Kamus Istilah / Glosarium (Sensei = Pak Guru)") },
                            textStyle = LocalTextStyle.current.copy(fontSize = 12.sp)
                        )

                        ToggleOptionRow("Auto-deteksi bahasa subtitle", vm.geminiAutoDetect) {
                            vm.geminiAutoDetect = it; vm.saveGeminiSettings()
                        }
                        ToggleOptionRow("Longgarkan sensor (BLOCK_NONE + Fallback)", vm.geminiRelaxSafety) {
                            vm.geminiRelaxSafety = it; vm.saveGeminiSettings()
                        }
                        ToggleOptionRow("Proses semua baris teks (non-CJK termasuk Inggris)", vm.geminiIncludeAllLines) {
                            vm.geminiIncludeAllLines = it; vm.saveGeminiSettings()
                        }
                        ToggleOptionRow("Konteks antar-batch (Sliding Context)", vm.geminiSlidingContext) {
                            vm.geminiSlidingContext = it; vm.saveGeminiSettings()
                        }
                        ToggleOptionRow("Bersihkan iklan SRT & rapikan overlap", vm.geminiCleanAds) {
                            vm.geminiCleanAds = it; vm.rebuildGeminiOutputs()
                        }
                        ToggleOptionRow("Output Bilingual (ID + Asli)", vm.geminiBilingual) {
                            vm.geminiBilingual = it; vm.rebuildGeminiOutputs()
                        }

                        OutlinedTextField(
                            value = vm.geminiTimeShiftSec,
                            onValueChange = {
                                vm.geminiTimeShiftSec = it
                                vm.rebuildGeminiOutputs()
                            },
                            singleLine = true,
                            label = { Text("Geser Waktu / Time Shift (detik, mis: 1.5 atau -0.8)") },
                            modifier = Modifier.fillMaxWidth(),
                            textStyle = LocalTextStyle.current.copy(fontSize = 12.5.sp)
                        )
                    }
                }
            }
        }

        // Card 2: File Queue & Controls
        Surface(color = Color(0xFF1E293B), shape = RoundedCornerShape(12.dp)) {
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
                        Text("📂 Pilih File SRT/ASS", fontSize = 12.5.sp)
                    }
                    if (vm.geminiJobs.isNotEmpty()) {
                        OutlinedButton(
                            onClick = { vm.clearGeminiJobs() },
                            enabled = !vm.isGeminiRunning
                        ) {
                            Text("Kosongkan", fontSize = 12.sp)
                        }
                    }
                }

                // Auto-upload to PikPak Drive toggle
                Surface(
                    color = Color(0xFF0F172A),
                    shape = RoundedCornerShape(8.dp),
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
                                "☁️ Otomatis upload hasil SRT ke PikPak",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = Color(0xFFE2E8F0)
                            )
                            Text(
                                if (vm.pikpakCreds.isLoggedIn) "Folder tujuan: 📁 ${vm.pikpakCreds.targetFolderName}"
                                else "Belum login PikPak (atur di tab PikPak)",
                                fontSize = 10.5.sp,
                                color = if (vm.pikpakCreds.isLoggedIn) Color(0xFF38BDF8) else Color(0xFF94A3B8)
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
                        Text("Tes 10 Baris", fontSize = 12.sp)
                    }
                    Button(
                        onClick = { launchGeminiWithNotifCheck(limitLines = 0) },
                        enabled = !vm.isGeminiRunning && vm.geminiJobs.isNotEmpty(),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF059669)),
                        modifier = Modifier.weight(1.3f),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 10.dp)
                    ) {
                        Text(
                            if (vm.geminiTaskMode == "polish") "▶️ Perbaiki Semua" else "▶️ Terjemahkan",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    if (vm.isGeminiRunning) {
                        Button(
                            onClick = { vm.stopGeminiRun() },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFDC2626)),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 10.dp)
                        ) {
                            Text("⏹️ Stop", fontSize = 12.sp)
                        }
                    }
                }

                Text(
                    vm.geminiStatusText,
                    fontSize = 12.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color(0xFF38BDF8)
                )

                // Job Items
                vm.geminiJobs.forEachIndexed { idx, job ->
                    val outName = GeminiEngine.formatOutputFileName(job.fileName, vm.geminiNameMode)
                    val isUploadingThis = vm.uploadingGeminiJobId == job.id
                    Surface(color = Color(0xFF0F172A), shape = RoundedCornerShape(10.dp)) {
                        Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(
                                "#${idx + 1} ${job.fileName} → $outName",
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp,
                                color = Color.White,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(job.statusText, fontSize = 11.5.sp, color = Color(0xFFCBD5E1))
                            LinearProgressIndicator(
                                progress = { (job.pct / 100f).coerceIn(0f, 1f) },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(6.dp),
                                color = Color(0xFF14B8A6),
                                trackColor = Color(0xFF334155)
                            )
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Button(
                                    onClick = {
                                        val res = job.resultContent ?: return@Button
                                        pendingSaveContent = res
                                        saveResultLauncher.launch(outName)
                                    },
                                    enabled = !job.resultContent.isNullOrBlank(),
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF059669)),
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                                ) {
                                    Text("💾 Simpan HP", fontSize = 11.5.sp)
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
                                            isUploadingThis -> "⏳ Upload…"
                                            job.uploadedToPikPak -> "☁️ Ter-upload ✓"
                                            else -> "☁️ Upload PikPak"
                                        },
                                        fontSize = 11.5.sp
                                    )
                                }
                                OutlinedButton(
                                    onClick = {
                                        previewJobTitle = outName
                                        previewJobResult = job.resultContent.orEmpty()
                                    },
                                    enabled = !job.resultContent.isNullOrBlank(),
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                                ) {
                                    Text("👁️ Lihat Hasil", fontSize = 11.5.sp)
                                }
                                if (!vm.isGeminiRunning && (job.hasSavedProgress || job.pct > 0)) {
                                    TextButton(onClick = { vm.resetSingleGeminiJob(job.id) }) {
                                        Text("Reset", fontSize = 11.5.sp, color = Color(0xFFF59E0B))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // Card 3: Console / Preview Box
        Surface(color = Color(0xFF020617), shape = RoundedCornerShape(10.dp)) {
            Text(
                text = vm.geminiPreviewLog,
                fontFamily = FontFamily.Monospace,
                fontSize = 11.5.sp,
                color = Color(0xFFE2E8F0),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp)
            )
        }
    }

    if (previewJobTitle != null) {
        val allResultLines = remember(previewJobResult) { previewJobResult.lines() }
        val padW = remember(allResultLines.size) { allResultLines.size.toString().length.coerceAtLeast(3) }
        Dialog(
            onDismissRequest = { previewJobTitle = null },
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
                            Text(previewJobTitle.orEmpty(), fontWeight = FontWeight.Bold)
                            Text("${allResultLines.size} baris penuh", fontSize = 11.sp, color = Color(0xFF38BDF8))
                        }
                        IconButton(onClick = { previewJobTitle = null }) {
                            Icon(Icons.Default.Close, contentDescription = "Tutup")
                        }
                    }
                    androidx.compose.foundation.lazy.LazyColumn(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .background(Color(0xFF020617)),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp)
                    ) {
                        items(allResultLines.size) { idx ->
                            Row(modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp)) {
                                Text(
                                    text = "${(idx + 1).toString().padStart(padW, ' ')}. ",
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 11.sp,
                                    color = Color(0xFF64748B)
                                )
                                Text(
                                    text = allResultLines[idx],
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

@Composable
private fun ToggleOptionRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onChange(!checked) }
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, fontSize = 12.sp, color = Color(0xFFE2E8F0), modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
