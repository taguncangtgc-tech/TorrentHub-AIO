package com.torrenthub.aio.ui

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.torrenthub.aio.data.GeminiEngine
import com.torrenthub.aio.data.PikPakClient
import com.torrenthub.aio.data.SubtitleRepository
import com.torrenthub.aio.data.TorrentRepository
import com.torrenthub.aio.data.UserscriptManager
import com.torrenthub.aio.model.BrowserBookmark
import com.torrenthub.aio.model.GeminiJob
import com.torrenthub.aio.model.PikPakCredentials
import com.torrenthub.aio.model.PikPakDriveItem
import com.torrenthub.aio.model.PikPakFolder
import com.torrenthub.aio.model.PikPakTaskInfo
import com.torrenthub.aio.model.PillState
import com.torrenthub.aio.model.SourceStatus
import com.torrenthub.aio.model.SubtitleItem
import com.torrenthub.aio.model.TorrentItem
import com.torrenthub.aio.model.UserscriptItem
import com.torrenthub.aio.model.UserscriptMenuCmd
import com.torrenthub.aio.util.Formatters
import com.torrenthub.aio.util.NotificationHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import java.util.UUID

class AppViewModel(app: Application) : AndroidViewModel(app) {

    private val prefs = app.getSharedPreferences("torrenthub_aio_prefs", Application.MODE_PRIVATE)

    private val _toastFlow = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val toastFlow = _toastFlow.asSharedFlow()

    fun emitToast(msg: String) {
        _toastFlow.tryEmit(msg)
    }

    var currentTab by mutableIntStateOf(0)

    /* =====================================================================
     * TAB 0: TORRENT SEARCH (16 SITUS)
     * ===================================================================== */
    var torrentQuery by mutableStateOf("")
    var torrentSubFilter by mutableStateOf("")
    var torrentSortMode by mutableStateOf(prefs.getString("uta_sort", "seeders-desc") ?: "seeders-desc")
    var torrentSourceFilter by mutableStateOf("all")
    var torrentTagFilter by mutableStateOf("all")
    var hideDeadTorrents by mutableStateOf(prefs.getBoolean("uta_hide_dead", true))
    var isSearchingTorrents by mutableStateOf(false)

    val enabledSources = mutableStateMapOf<String, Boolean>()
    val sourceStatuses = mutableStateListOf<SourceStatus>()
    private val rawPool = mutableListOf<TorrentItem>()
    private val poolMutex = Mutex()
    val allTorrents = mutableStateListOf<TorrentItem>()
    val selectedMagnets = mutableStateMapOf<String, Boolean>()

    private var torrentSearchJob: Job? = null

    /* =====================================================================
     * TAB 1: SUBTITLE SEARCH (XUNLEI)
     * ===================================================================== */
    var subtitleQuery by mutableStateOf("")
    var subtitleSortMode by mutableStateOf("size-desc")
    var isSearchingSubtitles by mutableStateOf(false)
    var subtitleStatusMsg by mutableStateOf("Masukkan kode/judul video untuk mencari subtitle.")
    val subtitleResults = mutableStateListOf<SubtitleItem>()

    /* =====================================================================
     * TAB 2: GEMINI SUBTITLE TRANSLATOR
     * ===================================================================== */
    var geminiKeysRaw by mutableStateOf(prefs.getString("gst_keys", "") ?: "")
    var geminiModel by mutableStateOf(prefs.getString("gst_model", GeminiEngine.DEFAULT_MODEL) ?: GeminiEngine.DEFAULT_MODEL)
    val geminiAvailableModels = mutableStateListOf<String>()
    var geminiTaskMode by mutableStateOf(prefs.getString("gst_task_mode", "translate") ?: "translate")
    var geminiAutoDetect by mutableStateOf(prefs.getBoolean("gst_autodetect", true))
    var geminiNameMode by mutableStateOf(prefs.getString("gst_name_mode", "code-upper") ?: "code-upper")
    var geminiNotesTranslate by mutableStateOf(
        prefs.getString("gst_notes_tr", GeminiEngine.DEFAULT_NOTES_TRANSLATE) ?: GeminiEngine.DEFAULT_NOTES_TRANSLATE
    )
    var geminiNotesPolish by mutableStateOf(
        prefs.getString("gst_notes_pl", GeminiEngine.DEFAULT_NOTES_POLISH) ?: GeminiEngine.DEFAULT_NOTES_POLISH
    )
    var geminiGlossary by mutableStateOf(prefs.getString("gst_glossary", "") ?: "")
    var geminiRelaxSafety by mutableStateOf(prefs.getBoolean("gst_safety", true))
    var geminiIncludeAllLines by mutableStateOf(prefs.getBoolean("gst_all_lines", false))
    var geminiSlidingContext by mutableStateOf(prefs.getBoolean("gst_sliding_ctx", true))
    var geminiCleanAds by mutableStateOf(prefs.getBoolean("gst_clean_ads", true))
    var geminiBilingual by mutableStateOf(prefs.getBoolean("gst_bilingual", false))
    var geminiTimeShiftSec by mutableStateOf(prefs.getString("gst_shift_sec", "0") ?: "0")
    var geminiAutoUploadPikPak by mutableStateOf(prefs.getBoolean("gst_auto_up_pikpak", false))
    var uploadingGeminiJobId by mutableStateOf<String?>(null)

    val geminiJobs = mutableStateListOf<GeminiJob>()
    private val parsedSubMap = HashMap<String, GeminiEngine.ParsedSubtitle>()
    private val progressMap = HashMap<String, MutableList<String>>()
    var isGeminiRunning by mutableStateOf(false)
    private var geminiCancelRequested = false
    var geminiStatusText by mutableStateOf("Siap: Pilih file .srt/.ass/.vtt dari HP atau kirim dari tab Subtitle/PikPak.")
    var geminiPreviewLog by mutableStateOf("Pratinjau dialog & hasil tes koneksi akan muncul di sini.")

    /* =====================================================================
     * TAB 3: PIKPAK CLOUD ACCOUNT & MINI FILE MANAGER
     * ===================================================================== */
    var pikpakCreds by mutableStateOf(loadPikPakCreds())
    var pikpakSubTab by mutableIntStateOf(0) // 0 = File Manager My Drive, 1 = Tugas Cloud Download
    var isLoadingPikPakTasks by mutableStateOf(false)
    val pikpakTasks = mutableStateListOf<PikPakTaskInfo>()

    val pikpakBrowserStack = mutableStateListOf<Pair<String, String>>() // (folderId, folderName)
    val currentPikPakFolderId: String
        get() = pikpakBrowserStack.lastOrNull()?.first.orEmpty()
    val currentPikPakFolderName: String
        get() = pikpakBrowserStack.lastOrNull()?.second ?: "My Drive (Root)"
    val pikpakDirItems = mutableStateListOf<PikPakDriveItem>()
    var isLoadingPikPakDir by mutableStateOf(false)
    var pikpakDirError by mutableStateOf<String?>(null)
    var busyPikPakItemId by mutableStateOf<String?>(null)

    /* =====================================================================
     * TAB 4: BUILT-IN BROWSER + TAMPERMONKEY USERSCRIPT ENGINE
     * ===================================================================== */
    var browserCurrentUrl by mutableStateOf(
        prefs.getString("browser_last_url", "https://www.google.com") ?: "https://www.google.com"
    )
    var browserPageTitle by mutableStateOf("")
    var browserIsLoading by mutableStateOf(false)
    var browserProgress by mutableIntStateOf(0)
    var browserCanGoBack by mutableStateOf(false)
    var browserCanGoForward by mutableStateOf(false)
    var browserAdShieldEnabled by mutableStateOf(prefs.getBoolean("browser_adshield", true))
    var browserBlockedAdsCount by mutableIntStateOf(0)
    var browserSelectedQuery by mutableStateOf("")

    val browserBookmarks = mutableStateListOf<BrowserBookmark>()
    val installedUserscripts = mutableStateListOf<UserscriptItem>()
    var userscriptRevision by mutableIntStateOf(0)
    val activePageScriptNames = mutableStateListOf<String>()
    val activePageMenuCommands = mutableStateListOf<UserscriptMenuCmd>()

    init {
        loadEnabledSources()
        loadSavedModels()
        loadBrowserBookmarks()
        reloadInstalledUserscripts()
        if (pikpakCreds.isLoggedIn) {
            refreshPikPakTasks()
            loadPikPakDirectory("")
        }
    }

    private fun loadEnabledSources() {
        val raw = prefs.getString("uta_sources", null)
        val savedJson = try { if (raw != null) JSONObject(raw) else null } catch (_: Exception) { null }
        for (s in TorrentRepository.SOURCES) {
            enabledSources[s.id] = savedJson?.optBoolean(s.id, s.defaultOn) ?: s.defaultOn
        }
    }

    fun toggleSource(sourceId: String, checked: Boolean) {
        enabledSources[sourceId] = checked
        val obj = JSONObject()
        enabledSources.forEach { (k, v) -> obj.put(k, v) }
        prefs.edit().putString("uta_sources", obj.toString()).apply()
    }

    fun setAllSources(enableAll: Boolean) {
        TorrentRepository.SOURCES.forEach { enabledSources[it.id] = enableAll }
        val obj = JSONObject()
        enabledSources.forEach { (k, v) -> obj.put(k, v) }
        prefs.edit().putString("uta_sources", obj.toString()).apply()
    }

    fun updateTorrentSort(mode: String) {
        torrentSortMode = mode
        prefs.edit().putString("uta_sort", mode).apply()
    }

    fun updateHideDead(hide: Boolean) {
        hideDeadTorrents = hide
        prefs.edit().putBoolean("uta_hide_dead", hide).apply()
    }

    fun runTorrentSearch() {
        val q = torrentQuery.trim()
        if (q.isEmpty()) {
            emitToast("Masukkan kata kunci pencarian dulu.")
            return
        }
        val activeSources = TorrentRepository.SOURCES.filter { enabledSources[it.id] == true }
        if (activeSources.isEmpty()) {
            emitToast("Aktifkan minimal 1 sumber torrent.")
            return
        }

        torrentSearchJob?.cancel()
        torrentSearchJob = viewModelScope.launch {
            isSearchingTorrents = true
            selectedMagnets.clear()
            poolMutex.withLock { rawPool.clear() }
            allTorrents.clear()
            sourceStatuses.clear()
            activeSources.forEach { s ->
                sourceStatuses.add(SourceStatus(s.id, s.label, PillState.LOADING))
            }

            val sortSnap = torrentSortMode
            activeSources.map { src ->
                async {
                    try {
                        val items = withContext(Dispatchers.Default) {
                            TorrentRepository.searchSource(src.id, q, sortSnap)
                        }
                        val merged = poolMutex.withLock {
                            rawPool.addAll(items)
                            withContext(Dispatchers.Default) {
                                TorrentRepository.mergeAndDeduplicate(rawPool)
                            }
                        }
                        allTorrents.clear()
                        allTorrents.addAll(merged)
                        updateSourceStatus(
                            SourceStatus(src.id, src.label, PillState.OK, count = items.size)
                        )
                    } catch (e: Exception) {
                        val msg = e.message ?: "Gagal"
                        val canBypass = src.supportsWebViewBridge &&
                            Regex("403|503|Cloudflare|Diblokir", RegexOption.IGNORE_CASE).containsMatchIn(msg)
                        updateSourceStatus(
                            SourceStatus(
                                sourceId = src.id,
                                label = src.label,
                                state = PillState.ERROR,
                                message = msg,
                                canBypassCloudflare = canBypass,
                                bypassUrl = if (canBypass) TorrentRepository.buildBypassSearchUrl(src.id, q, sortSnap) else ""
                            )
                        )
                    }
                }
            }.awaitAll()

            isSearchingTorrents = false
        }
    }

    private fun updateSourceStatus(updated: SourceStatus) {
        val idx = sourceStatuses.indexOfFirst { it.sourceId == updated.sourceId }
        if (idx >= 0) sourceStatuses[idx] = updated
    }

    fun ingestCloudflareBypassHtml(sourceId: String, pageUrl: String, html: String, cookieHeader: String) {
        viewModelScope.launch {
            val src = TorrentRepository.SOURCES.firstOrNull { it.id == sourceId } ?: return@launch
            updateSourceStatus(SourceStatus(sourceId, src.label, PillState.LOADING, message = "mengambil magnet…"))
            try {
                val doc = Jsoup.parse(html, pageUrl)
                val items = when (sourceId) {
                    "rargb" -> TorrentRepository.resolveRargbRows(
                        TorrentRepository.parseRargbDoc(doc),
                        pageUrl,
                        cookieHeader
                    )
                    "bt4g" -> TorrentRepository.resolveBt4gCards(
                        TorrentRepository.parseBt4gDoc(doc),
                        pageUrl,
                        cookieHeader
                    )
                    else -> emptyList()
                }
                poolMutex.withLock {
                    rawPool.addAll(items)
                    val merged = TorrentRepository.mergeAndDeduplicate(rawPool)
                    allTorrents.clear()
                    allTorrents.addAll(merged)
                }
                updateSourceStatus(SourceStatus(sourceId, "${src.label} (Bypass)", PillState.OK, count = items.size))
                emitToast("✅ ${src.label}: ${items.size} hasil berhasil diambil!")
            } catch (e: Exception) {
                updateSourceStatus(
                    SourceStatus(
                        sourceId = sourceId,
                        label = src.label,
                        state = PillState.ERROR,
                        message = e.message ?: "Gagal bypass",
                        canBypassCloudflare = true,
                        bypassUrl = pageUrl
                    )
                )
                emitToast("❌ Bypass gagal: ${e.message}")
            }
        }
    }

    fun filteredAndSortedTorrents(): List<TorrentItem> {
        val tokens = torrentSubFilter.trim().lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
        val filtered = allTorrents.filter { item ->
            if (hideDeadTorrents && item.seeders == 0) return@filter false
            if (torrentSourceFilter != "all" && torrentSourceFilter !in item.sourceIds) return@filter false
            when (torrentTagFilter) {
                "4k" -> if (!item.qFlags.is4K) return@filter false
                "sub" -> if (!item.qFlags.isSub) return@filter false
                "unc" -> if (!item.qFlags.isCracked) return@filter false
                "multi" -> if (item.sourceIds.size < 2) return@filter false
            }
            if (tokens.isNotEmpty()) {
                val hay = item.title.lowercase()
                if (!tokens.all { hay.contains(it) }) return@filter false
            }
            true
        }

        fun se(x: TorrentItem) = x.seeders ?: -1
        fun le(x: TorrentItem) = x.leechers ?: -1
        fun fc(x: TorrentItem) = x.files.toIntOrNull() ?: 0

        return when (torrentSortMode) {
            "leechers-desc" -> filtered.sortedWith(compareByDescending<TorrentItem> { le(it) }.thenByDescending { se(it) })
            "size-desc" -> filtered.sortedWith(compareByDescending<TorrentItem> { it.size }.thenByDescending { se(it) })
            "size-asc" -> filtered.sortedWith(compareBy<TorrentItem> { if (it.size > 0) it.size else Long.MAX_VALUE }.thenByDescending { se(it) })
            "date-desc" -> filtered.sortedWith(compareByDescending<TorrentItem> { it.dateMs }.thenByDescending { se(it) })
            "date-asc" -> filtered.sortedWith(compareBy<TorrentItem> { if (it.dateMs > 0) it.dateMs else Long.MAX_VALUE }.thenByDescending { se(it) })
            "files-desc" -> filtered.sortedWith(compareByDescending<TorrentItem> { fc(it) }.thenByDescending { it.size })
            "sources-desc" -> filtered.sortedWith(compareByDescending<TorrentItem> { it.sourceIds.size }.thenByDescending { it.size })
            "name-asc" -> filtered.sortedBy { it.title.lowercase() }
            "name-desc" -> filtered.sortedByDescending { it.title.lowercase() }
            else -> filtered.sortedWith(compareByDescending<TorrentItem> { se(it) }.thenByDescending { it.size })
        }
    }

    /* =====================================================================
     * SUBTITLE ACTIONS
     * ===================================================================== */
    fun runSubtitleSearch(customQuery: String? = null) {
        if (customQuery != null) subtitleQuery = customQuery
        val q = subtitleQuery.trim()
        if (q.isEmpty()) {
            emitToast("Masukkan kode atau nama subtitle dulu.")
            return
        }
        viewModelScope.launch {
            isSearchingSubtitles = true
            subtitleResults.clear()
            subtitleStatusMsg = "⏳ Mencari subtitle untuk \"$q\"…"
            try {
                val initial = SubtitleRepository.searchSubtitles(q)
                if (initial.isEmpty()) {
                    subtitleStatusMsg = "Tidak ditemukan subtitle untuk \"$q\"."
                } else {
                    subtitleResults.addAll(initial)
                    subtitleStatusMsg = "⏳ Mengecek ukuran file subtitle… 0/${initial.size}"
                    val sized = SubtitleRepository.probeSizes(initial) { done, total, updatedItem ->
                        viewModelScope.launch {
                            val idx = subtitleResults.indexOfFirst { it.idKey == updatedItem.idKey }
                            if (idx >= 0) subtitleResults[idx] = updatedItem
                            subtitleStatusMsg = "⏳ Mengecek ukuran file subtitle… $done/$total"
                        }
                    }
                    subtitleResults.clear()
                    subtitleResults.addAll(sized)
                    val knownCount = sized.count { it.sizeBytes > 0L }
                    subtitleStatusMsg = "Ditemukan ${sized.size} subtitle ($knownCount ukuran terdeteksi)."
                }
            } catch (e: Exception) {
                subtitleStatusMsg = "Gagal mencari: ${e.message}"
            } finally {
                isSearchingSubtitles = false
            }
        }
    }

    fun sortedSubtitles(): List<SubtitleItem> = when (subtitleSortMode) {
        "size-asc" -> subtitleResults.sortedBy { if (it.sizeBytes > 0) it.sizeBytes else Long.MAX_VALUE }
        "name-asc" -> subtitleResults.sortedBy { it.name.lowercase() }
        else -> subtitleResults.sortedByDescending { if (it.sizeBytes > 0) it.sizeBytes else -1L }
    }

    fun sendSubtitleItemToGemini(item: SubtitleItem) {
        if (item.ext.lowercase() !in SubtitleRepository.SUPPORTED_TEXT_EXTS) {
            emitToast("Format .${item.ext} tidak didukung (hanya .srt, .vtt, .ass, .ssa)")
            return
        }
        viewModelScope.launch {
            emitToast("⏳ Mengambil \"${item.name}\"…")
            try {
                val content = SubtitleRepository.fetchSubtitleText(item.url)
                if (content.isBlank()) throw Exception("Isi file subtitle kosong")
                addGeminiJob(item.name, content)
                currentTab = 2
                emitToast("✨ \"${item.name}\" masuk ke Gemini Translator!")
            } catch (e: Exception) {
                emitToast("❌ Gagal mengambil subtitle: ${e.message}")
            }
        }
    }

    /* =====================================================================
     * GEMINI SUBTITLE TRANSLATOR ACTIONS
     * ===================================================================== */
    private fun loadSavedModels() {
        geminiAvailableModels.clear()
        val raw = prefs.getString("gst_models_list", null)
        val extra = mutableListOf<String>()
        if (raw != null) {
            try {
                val arr = JSONArray(raw)
                for (i in 0 until arr.length()) extra.add(arr.optString(i))
            } catch (_: Exception) {}
        }
        geminiAvailableModels.addAll((GeminiEngine.DEFAULT_MODELS + extra).distinct())
        if (geminiModel !in geminiAvailableModels) {
            geminiAvailableModels.add(0, geminiModel)
        }
    }

    fun saveGeminiSettings() {
        prefs.edit()
            .putString("gst_keys", geminiKeysRaw.trim())
            .putString("gst_model", geminiModel)
            .putString("gst_task_mode", geminiTaskMode)
            .putBoolean("gst_autodetect", geminiAutoDetect)
            .putString("gst_name_mode", geminiNameMode)
            .putString("gst_notes_tr", geminiNotesTranslate)
            .putString("gst_notes_pl", geminiNotesPolish)
            .putString("gst_glossary", geminiGlossary)
            .putBoolean("gst_safety", geminiRelaxSafety)
            .putBoolean("gst_all_lines", geminiIncludeAllLines)
            .putBoolean("gst_sliding_ctx", geminiSlidingContext)
            .putBoolean("gst_clean_ads", geminiCleanAds)
            .putBoolean("gst_bilingual", geminiBilingual)
            .putString("gst_shift_sec", geminiTimeShiftSec)
            .putBoolean("gst_auto_up_pikpak", geminiAutoUploadPikPak)
            .apply()
    }

    private fun progressStorageKey(fileName: String, totalSlots: Int, mode: String): String =
        "gst_prog_${mode}_${fileName}_$totalSlots"

    private fun loadJobProgress(fileName: String, totalSlots: Int, mode: String): MutableList<String>? {
        val raw = prefs.getString(progressStorageKey(fileName, totalSlots, mode), null) ?: return null
        return try {
            val arr = JSONArray(raw)
            if (arr.length() != totalSlots) null
            else MutableList(arr.length()) { arr.optString(it, "") }
        } catch (_: Exception) {
            null
        }
    }

    private fun saveJobProgress(fileName: String, totalSlots: Int, mode: String, list: List<String>) {
        val arr = JSONArray().apply { list.forEach { put(it) } }
        prefs.edit().putString(progressStorageKey(fileName, totalSlots, mode), arr.toString()).apply()
    }

    private fun clearJobProgress(fileName: String, totalSlots: Int, mode: String) {
        prefs.edit().remove(progressStorageKey(fileName, totalSlots, mode)).apply()
    }

    private fun currentBuildOpts() = GeminiEngine.BuildOptions(
        shiftSec = geminiTimeShiftSec.toDoubleOrNull() ?: 0.0,
        cleanAds = geminiCleanAds,
        bilingual = geminiBilingual
    )

    fun addGeminiJob(fileName: String, content: String) {
        val ext = fileName.substringAfterLast('.', "srt").lowercase()
        val parsed = GeminiEngine.parseSubtitle(content, ext)

        if (geminiAutoDetect && geminiJobs.isEmpty()) {
            val detected = GeminiEngine.detectLanguageMode(parsed.texts)
            if (detected != geminiTaskMode) {
                geminiTaskMode = detected
                saveGeminiSettings()
                geminiStatusText = if (detected == "polish") {
                    "Auto-deteksi: Subtitle Bahasa Indonesia -> Mode Perbaiki SRT ID aktif."
                } else {
                    "Auto-deteksi: Subtitle Bahasa Asing -> Mode Terjemahkan aktif."
                }
            }
        }

        val id = UUID.randomUUID().toString()
        parsedSubMap[id] = parsed
        val saved = loadJobProgress(fileName, parsed.texts.size, geminiTaskMode)
        if (saved != null) progressMap[id] = saved

        val nonEmptyCount = parsed.texts.count { it.isNotBlank() }
        val savedDone = saved?.count { it.isNotBlank() } ?: 0
        val hasSaved = savedDone > 0
        val outName = GeminiEngine.formatOutputFileName(fileName, geminiNameMode)
        val built = if (hasSaved && saved != null) GeminiEngine.buildSubtitle(parsed, saved, currentBuildOpts()) else null

        val newJob = GeminiJob(
            id = id,
            fileName = fileName,
            ext = ext,
            originalContent = content,
            totalLines = nonEmptyCount,
            pct = if (hasSaved && nonEmptyCount > 0) ((savedDone * 100) / nonEmptyCount) else 0,
            statusText = if (hasSaved) "Progres tersimpan ($savedDone/$nonEmptyCount baris) siap dilanjutkan."
            else "Siap ($nonEmptyCount baris dialog -> $outName)",
            resultContent = built,
            hasSavedProgress = hasSaved
        )
        val existingIdx = geminiJobs.indexOfFirst { it.fileName.equals(fileName, ignoreCase = true) }
        if (existingIdx >= 0) geminiJobs[existingIdx] = newJob else geminiJobs.add(newJob)
    }

    fun rebuildGeminiOutputs() {
        saveGeminiSettings()
        val opts = currentBuildOpts()
        geminiJobs.forEachIndexed { idx, job ->
            val sub = parsedSubMap[job.id] ?: return@forEachIndexed
            val tr = progressMap[job.id] ?: return@forEachIndexed
            if (tr.any { it.isNotBlank() }) {
                geminiJobs[idx] = job.copy(resultContent = GeminiEngine.buildSubtitle(sub, tr, opts))
            }
        }
    }

    fun clearGeminiJobs() {
        if (isGeminiRunning) return
        geminiJobs.clear()
        parsedSubMap.clear()
        progressMap.clear()
        geminiStatusText = "Daftar antrean file dikosongkan."
    }

    fun resetSingleGeminiJob(jobId: String) {
        if (isGeminiRunning) return
        val idx = geminiJobs.indexOfFirst { it.id == jobId }
        if (idx < 0) return
        val job = geminiJobs[idx]
        val sub = parsedSubMap[jobId] ?: return
        clearJobProgress(job.fileName, sub.texts.size, geminiTaskMode)
        progressMap.remove(jobId)
        geminiJobs[idx] = job.copy(
            pct = 0,
            resultContent = null,
            hasSavedProgress = false,
            uploadedToPikPak = false,
            statusText = "Progres direset. Siap mulai dari awal."
        )
    }

    fun testGeminiKeys() {
        val keys = GeminiEngine.parseKeys(geminiKeysRaw)
        if (keys.isEmpty()) {
            emitToast("Masukkan minimal 1 API key terlebih dahulu.")
            return
        }
        saveGeminiSettings()
        viewModelScope.launch {
            geminiStatusText = "⏳ Menguji ${keys.size} API key…"
            val logs = GeminiEngine.pingKeys(keys, geminiModel)
            geminiPreviewLog = logs.joinToString("\n")
            geminiStatusText = "Tes koneksi selesai (${keys.size} key)."
        }
    }

    fun refreshGeminiModels() {
        val keys = GeminiEngine.parseKeys(geminiKeysRaw)
        if (keys.isEmpty()) {
            emitToast("Masukkan minimal 1 API key terlebih dahulu.")
            return
        }
        saveGeminiSettings()
        viewModelScope.launch {
            geminiStatusText = "⏳ Mengambil daftar model aktif…"
            val models = GeminiEngine.fetchAvailableModels(keys)
            geminiAvailableModels.clear()
            geminiAvailableModels.addAll(models)
            val arr = JSONArray().apply { models.forEach { put(it) } }
            prefs.edit().putString("gst_models_list", arr.toString()).apply()
            geminiStatusText = "✅ ${models.size} model tersedia."
        }
    }

    fun stopGeminiRun() {
        geminiCancelRequested = true
        geminiStatusText = "Menghentikan proses & menyimpan progres…"
    }

    private suspend fun uploadGeminiJobToPikPakInternal(jobId: String, silentIfAuto: Boolean = false): Boolean {
        val idx = geminiJobs.indexOfFirst { it.id == jobId }
        if (idx < 0) return false
        val job = geminiJobs[idx]
        val content = job.resultContent
        if (content.isNullOrBlank()) {
            if (!silentIfAuto) emitToast("Hasil terjemahan belum tersedia.")
            return false
        }
        if (!pikpakCreds.isLoggedIn) {
            if (!silentIfAuto) {
                emitToast("Login ke PikPak dulu di tab PikPak!")
                currentTab = 3
            }
            return false
        }

        val outName = GeminiEngine.formatOutputFileName(job.fileName, geminiNameMode)
        uploadingGeminiJobId = jobId
        return try {
            val res = PikPakClient.uploadSubtitleFile(outName, content, pikpakCreds)
            res.updatedCreds?.let { savePikPakCreds(it) }
            if (res.ok) {
                val curIdx = geminiJobs.indexOfFirst { it.id == jobId }
                if (curIdx >= 0) {
                    val cur = geminiJobs[curIdx]
                    geminiJobs[curIdx] = cur.copy(
                        uploadedToPikPak = true,
                        statusText = "${cur.statusText} · ☁️ Ter-upload ke ${pikpakCreds.targetFolderName}"
                    )
                }
                if (currentPikPakFolderId == pikpakCreds.targetFolderId) {
                    loadPikPakDirectory(currentPikPakFolderId)
                }
                if (!silentIfAuto) {
                    emitToast("☁️ \"$outName\" berhasil di-upload ke PikPak (${pikpakCreds.targetFolderName})!")
                }
                true
            } else {
                emitToast("❌ Upload PikPak gagal: ${res.errorMessage}")
                false
            }
        } finally {
            if (uploadingGeminiJobId == jobId) uploadingGeminiJobId = null
        }
    }

    fun uploadGeminiJobToPikPak(jobId: String) {
        viewModelScope.launch {
            uploadGeminiJobToPikPakInternal(jobId, silentIfAuto = false)
        }
    }

    fun startGeminiTranslation(limitLines: Int = 0) {
        val keys = GeminiEngine.parseKeys(geminiKeysRaw)
        if (keys.isEmpty()) {
            emitToast("Isi minimal 1 API key Google AI Studio atau OpenRouter dulu.")
            return
        }
        if (geminiJobs.isEmpty()) {
            emitToast("Tambahkan file subtitle terlebih dahulu.")
            return
        }
        saveGeminiSettings()

        val appCtx = getApplication<Application>()

        viewModelScope.launch {
            isGeminiRunning = true
            geminiCancelRequested = false
            val cfg = GeminiEngine.TranslateConfig(
                keys = keys,
                model = geminiModel,
                taskMode = geminiTaskMode,
                notes = if (geminiTaskMode == "polish") geminiNotesPolish else geminiNotesTranslate,
                glossary = geminiGlossary,
                relaxSafety = geminiRelaxSafety,
                includeAllLines = geminiIncludeAllLines,
                useSlidingContext = geminiSlidingContext,
                cleanAds = geminiCleanAds
            )

            val targetIndices = if (limitLines > 0) listOf(0) else geminiJobs.indices.toList()
            var completedCount = 0

            for (idx in targetIndices) {
                if (geminiCancelRequested) break
                val job = geminiJobs[idx]
                val sub = parsedSubMap[job.id] ?: continue
                geminiStatusText = "Memproses ${job.fileName}…"
                NotificationHelper.showTranslationProgress(appCtx, job.fileName, job.pct, "Memulai terjemahan…")

                try {
                    val existing = if (limitLines > 0) null else progressMap[job.id]
                    val finalTr = GeminiEngine.processSubtitleJob(
                        sub = sub,
                        existingProgress = existing,
                        cfg = cfg,
                        limitLines = limitLines,
                        isCancelled = { geminiCancelRequested },
                        onProgress = { pct, stat, curTr, samplePair ->
                            val copyList = curTr.toMutableList()
                            progressMap[job.id] = copyList
                            if (limitLines == 0) {
                                saveJobProgress(job.fileName, sub.texts.size, geminiTaskMode, copyList)
                            }
                            val builtNow = GeminiEngine.buildSubtitle(sub, copyList, currentBuildOpts())
                            geminiJobs[idx] = geminiJobs[idx].copy(
                                pct = pct,
                                statusText = stat,
                                resultContent = builtNow,
                                hasSavedProgress = limitLines == 0
                            )
                            NotificationHelper.showTranslationProgress(appCtx, job.fileName, pct, stat)
                            if (samplePair != null) {
                                geminiPreviewLog = "=== ${job.fileName} ===\n$samplePair"
                            }
                        }
                    )
                    val builtFinal = GeminiEngine.buildSubtitle(sub, finalTr, currentBuildOpts())
                    if (limitLines == 0) {
                        clearJobProgress(job.fileName, sub.texts.size, geminiTaskMode)
                    }
                    geminiJobs[idx] = geminiJobs[idx].copy(
                        pct = 100,
                        statusText = if (limitLines > 0) "✅ Tes $limitLines baris selesai!" else "✅ Selesai! Siap disimpan ke HP.",
                        resultContent = builtFinal,
                        hasSavedProgress = false
                    )
                    completedCount++

                    if (limitLines == 0 && geminiAutoUploadPikPak && pikpakCreds.isLoggedIn) {
                        uploadGeminiJobToPikPakInternal(job.id, silentIfAuto = true)
                    }
                } catch (e: Exception) {
                    geminiJobs[idx] = geminiJobs[idx].copy(
                        statusText = "⚠️ Berhenti: ${e.message} (Progres aman tersimpan)"
                    )
                }
            }

            isGeminiRunning = false
            if (geminiCancelRequested) {
                NotificationHelper.cancelProgress(appCtx)
                geminiStatusText = "Proses dihentikan (progres tersimpan otomatis)."
            } else if (completedCount > 0) {
                val summary = if (limitLines > 0) {
                    "Tes $limitLines baris selesai!"
                } else {
                    "$completedCount file subtitle selesai diproses!" +
                        (if (geminiAutoUploadPikPak && pikpakCreds.isLoggedIn) " (Ter-upload ke PikPak)" else "")
                }
                NotificationHelper.showTranslationFinished(appCtx, summary)
                geminiStatusText = "✅ Pemrosesan selesai!"
            } else {
                NotificationHelper.cancelProgress(appCtx)
                geminiStatusText = "⚠️ Pemrosesan berhenti dengan kendala."
            }
        }
    }

    /* =====================================================================
     * PIKPAK CLOUD DOWNLOAD & MINI FILE MANAGER ACTIONS
     * ===================================================================== */
    private fun loadPikPakCreds(): PikPakCredentials = PikPakCredentials(
        accessToken = prefs.getString("pp_access", "") ?: "",
        refreshToken = prefs.getString("pp_refresh", "") ?: "",
        clientId = prefs.getString("pp_client_id", "") ?: "",
        deviceId = prefs.getString("pp_device_id", "") ?: "",
        captchaToken = prefs.getString("pp_captcha", "") ?: "",
        captchaExpiresAtMs = prefs.getLong("pp_captcha_exp", 0L),
        userEmail = prefs.getString("pp_email", "") ?: "",
        targetFolderId = prefs.getString("pp_folder_id", "") ?: "",
        targetFolderName = prefs.getString("pp_folder_name", "Cloud Download (Default)") ?: "Cloud Download (Default)"
    )

    fun savePikPakCreds(creds: PikPakCredentials) {
        pikpakCreds = creds
        prefs.edit()
            .putString("pp_access", creds.accessToken)
            .putString("pp_refresh", creds.refreshToken)
            .putString("pp_client_id", creds.clientId)
            .putString("pp_device_id", creds.deviceId)
            .putString("pp_captcha", creds.captchaToken)
            .putLong("pp_captcha_exp", creds.captchaExpiresAtMs)
            .putString("pp_email", creds.userEmail)
            .putString("pp_folder_id", creds.targetFolderId)
            .putString("pp_folder_name", creds.targetFolderName)
            .apply()
    }

    fun logoutPikPak() {
        savePikPakCreds(PikPakCredentials())
        pikpakTasks.clear()
        pikpakDirItems.clear()
        pikpakBrowserStack.clear()
        emitToast("Akun PikPak telah diputuskan.")
    }

    fun sendMagnetsToPikPak(magnets: List<String>) {
        if (!pikpakCreds.isLoggedIn) {
            emitToast("Login ke PikPak dulu di tab PikPak!")
            currentTab = 3
            return
        }
        viewModelScope.launch {
            emitToast("⏳ Mengirim ${magnets.size} tautan ke PikPak Cloud…")
            val res = PikPakClient.sendMagnetsToCloud(magnets, pikpakCreds)
            res.updatedCreds?.let { savePikPakCreds(it) }
            if (res.ok) {
                emitToast("✅ ${res.addedCount} tautan masuk ke PikPak (${pikpakCreds.targetFolderName})!")
                refreshPikPakTasks()
            } else {
                emitToast("❌ Gagal (${res.addedCount}/${magnets.size}): ${res.errorMessage}")
            }
        }
    }

    fun refreshPikPakTasks() {
        if (!pikpakCreds.isLoggedIn) return
        viewModelScope.launch {
            isLoadingPikPakTasks = true
            try {
                val list = PikPakClient.listRecentOfflineTasks(pikpakCreds)
                pikpakTasks.clear()
                pikpakTasks.addAll(list)
            } catch (_: Exception) {
            } finally {
                isLoadingPikPakTasks = false
            }
        }
    }

    fun loadPikPakDirectory(parentId: String = currentPikPakFolderId) {
        if (!pikpakCreds.isLoggedIn) return
        viewModelScope.launch {
            isLoadingPikPakDir = true
            pikpakDirError = null
            try {
                val items = PikPakClient.listDirectoryItems(parentId, pikpakCreds)
                pikpakDirItems.clear()
                pikpakDirItems.addAll(items)
            } catch (e: Exception) {
                pikpakDirError = e.message ?: "Gagal memuat isi folder PikPak"
            } finally {
                isLoadingPikPakDir = false
            }
        }
    }

    fun openPikPakSubfolder(folderItem: PikPakDriveItem) {
        if (!folderItem.isFolder) return
        pikpakBrowserStack.add(folderItem.id to folderItem.name)
        loadPikPakDirectory(folderItem.id)
    }

    fun navigateUpPikPakFolder() {
        if (pikpakBrowserStack.isEmpty()) return
        pikpakBrowserStack.removeAt(pikpakBrowserStack.lastIndex)
        loadPikPakDirectory(currentPikPakFolderId)
    }

    fun setCurrentDirAsPikPakTarget() {
        val id = currentPikPakFolderId
        val name = if (id.isBlank()) "Cloud Download (Default)" else currentPikPakFolderName
        savePikPakCreds(pikpakCreds.copy(targetFolderId = id, targetFolderName = name))
        emitToast("📌 Folder tujuan diatur ke: $name")
    }

    fun videoHasMatchingSubtitleInDir(videoItem: PikPakDriveItem, dirItems: List<PikPakDriveItem>): Boolean {
        val subs = dirItems.filter { it.isSubtitle }
        if (subs.isEmpty()) return false
        val vidBase = videoItem.name.substringBeforeLast('.').trim().lowercase()
        val vidCode = Formatters.extractVideoCode(videoItem.name).uppercase()
        return subs.any { sub ->
            val subBase = sub.name.substringBeforeLast('.').trim().lowercase()
            val subCleanBase = subBase.replace(Regex("\\.(id|ind|en|eng|chs|cht|jp|ja)$"), "")
            val subCode = Formatters.extractVideoCode(sub.name).uppercase()
            (vidCode.isNotEmpty() && vidCode == subCode) ||
                subBase == vidBase ||
                subCleanBase == vidBase ||
                (vidBase.length >= 4 && (subBase.startsWith(vidBase) || vidBase.startsWith(subCleanBase)))
        }
    }

    fun sendPikPakSubtitleToGemini(item: PikPakDriveItem) {
        if (!item.isSubtitle) return
        viewModelScope.launch {
            busyPikPakItemId = item.id
            emitToast("⏳ Mengambil \"${item.name}\" dari PikPak…")
            try {
                val links = PikPakClient.fetchFileLinks(item.id, pikpakCreds)
                if (links.downloadUrl.isBlank()) throw Exception("Link unduhan subtitle kosong dari PikPak")
                val content = SubtitleRepository.fetchSubtitleText(links.downloadUrl)
                if (content.isBlank()) throw Exception("Isi file subtitle kosong")

                // Automatically set target upload folder to this subtitle's folder if inside a folder
                if (currentPikPakFolderId.isNotBlank() && pikpakCreds.targetFolderId != currentPikPakFolderId) {
                    savePikPakCreds(
                        pikpakCreds.copy(
                            targetFolderId = currentPikPakFolderId,
                            targetFolderName = currentPikPakFolderName
                        )
                    )
                }

                addGeminiJob(item.name, content)
                currentTab = 2
                emitToast("✨ \"${item.name}\" siap diterjemahkan (tujuan upload: ${pikpakCreds.targetFolderName})!")
            } catch (e: Exception) {
                emitToast("❌ Gagal mengambil subtitle PikPak: ${e.message}")
            } finally {
                if (busyPikPakItemId == item.id) busyPikPakItemId = null
            }
        }
    }

    suspend fun fetchPikPakSubtitleTextSuspend(item: PikPakDriveItem): String {
        val links = PikPakClient.fetchFileLinks(item.id, pikpakCreds)
        if (links.downloadUrl.isBlank()) throw Exception("Tautan file kosong")
        return SubtitleRepository.fetchSubtitleText(links.downloadUrl)
    }

    suspend fun fetchPikPakStreamLinkSuspend(item: PikPakDriveItem): String {
        val links = PikPakClient.fetchFileLinks(item.id, pikpakCreds)
        if (links.streamUrl.isBlank()) throw Exception("Tautan streaming tidak tersedia")
        return links.streamUrl
    }

    fun searchSubtitleForPikPakVideo(videoItem: PikPakDriveItem) {
        val code = Formatters.extractVideoCode(videoItem.name)
            .ifBlank { videoItem.name.substringBeforeLast('.') }
        currentTab = 1
        runSubtitleSearch(code)
    }

    suspend fun loadPikPakFoldersSuspend(parentId: String): List<PikPakFolder> =
        PikPakClient.listFolders(parentId, pikpakCreds)

    /* =====================================================================
     * BROWSER & TAMPERMONKEY USERSCRIPT ACTIONS
     * ===================================================================== */
    fun saveBrowserLastUrl(url: String) {
        if (url.startsWith("http://", ignoreCase = true) || url.startsWith("https://", ignoreCase = true)) {
            browserCurrentUrl = url
            prefs.edit().putString("browser_last_url", url).apply()
        }
    }

    fun toggleBrowserAdShield(enabled: Boolean) {
        browserAdShieldEnabled = enabled
        prefs.edit().putBoolean("browser_adshield", enabled).apply()
        emitToast(if (enabled) "🛡️ Anti-Iklan & Blokir Pop-up: AKTIF" else "⚪ Anti-Iklan & Blokir Pop-up: NONAKTIF")
    }

    fun searchTorrentFromBrowser(rawQuery: String) {
        val clean = rawQuery.trim()
        if (clean.isEmpty()) return
        browserSelectedQuery = ""
        torrentQuery = clean
        currentTab = 0
        runTorrentSearch()
    }

    fun searchSubtitleFromBrowser(rawQuery: String) {
        val clean = rawQuery.trim()
        if (clean.isEmpty()) return
        browserSelectedQuery = ""
        currentTab = 1
        runSubtitleSearch(clean)
    }

    private fun loadBrowserBookmarks() {
        browserBookmarks.clear()
        val raw = prefs.getString("browser_bookmarks_v1", null)
        if (raw != null) {
            try {
                val arr = JSONArray(raw)
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val t = o.optString("title")
                    val u = o.optString("url")
                    if (u.isNotBlank()) browserBookmarks.add(BrowserBookmark(t.ifBlank { u }, u))
                }
                if (browserBookmarks.isNotEmpty()) return
            } catch (_: Exception) {}
        }
        browserBookmarks.addAll(
            listOf(
                BrowserBookmark("Google", "https://www.google.com"),
                BrowserBookmark("IMDb", "https://m.imdb.com"),
                BrowserBookmark("JavDB", "https://javdb.com"),
                BrowserBookmark("MissAV", "https://missav.ws"),
                BrowserBookmark("MyDramaList", "https://mydramalist.com"),
                BrowserBookmark("GreasyFork (.user.js)", "https://greasyfork.org/id/scripts")
            )
        )
    }

    private fun saveBrowserBookmarks() {
        val arr = JSONArray()
        browserBookmarks.forEach { b ->
            arr.put(JSONObject().put("title", b.title).put("url", b.url))
        }
        prefs.edit().putString("browser_bookmarks_v1", arr.toString()).apply()
    }

    fun addBrowserBookmark(title: String, url: String) {
        val cleanUrl = url.trim()
        if (cleanUrl.isBlank()) return
        val cleanTitle = title.trim().ifBlank { cleanUrl }
        if (browserBookmarks.any { it.url.equals(cleanUrl, ignoreCase = true) }) {
            emitToast("⭐ Halaman ini sudah ada di Bookmark.")
            return
        }
        browserBookmarks.add(0, BrowserBookmark(cleanTitle, cleanUrl))
        saveBrowserBookmarks()
        emitToast("⭐ Ditambahkan ke Bookmark: $cleanTitle")
    }

    fun removeBrowserBookmark(url: String) {
        browserBookmarks.removeAll { it.url.equals(url, ignoreCase = true) }
        saveBrowserBookmarks()
    }

    fun reloadInstalledUserscripts() {
        installedUserscripts.clear()
        installedUserscripts.addAll(UserscriptManager.loadInstalledScripts(getApplication()))
    }

    fun installOrUpdateUserscript(rawCode: String, sourceUrl: String = "") {
        if (rawCode.isBlank()) {
            emitToast("Kode script kosong.")
            return
        }
        val item = UserscriptManager.installOrUpdateScript(getApplication(), rawCode, sourceUrl)
        reloadInstalledUserscripts()
        userscriptRevision++
        emitToast("🧩 Userscript terpasang: ${item.name} (v${item.version}) — Muat ulang halaman untuk menerapkan.")
    }

    fun installUserscriptFromUrl(url: String, onDone: (() -> Unit)? = null) {
        val cleanUrl = url.trim()
        if (!cleanUrl.startsWith("http://", ignoreCase = true) && !cleanUrl.startsWith("https://", ignoreCase = true)) {
            emitToast("Masukkan URL lengkap (https://...)")
            return
        }
        viewModelScope.launch {
            emitToast("⏳ Mengunduh script dari URL…")
            try {
                val code = com.torrenthub.aio.network.HttpClient.getString(cleanUrl, timeoutSec = 25L)
                if (code.isBlank()) throw Exception("Isi script kosong")
                installOrUpdateUserscript(code, cleanUrl)
                onDone?.invoke()
            } catch (e: Exception) {
                emitToast("❌ Gagal mengunduh script: ${e.message}")
            }
        }
    }

    fun toggleUserscript(scriptId: String, enabled: Boolean) {
        val next = UserscriptManager.setScriptEnabled(getApplication(), scriptId, enabled)
        installedUserscripts.clear()
        installedUserscripts.addAll(next)
        userscriptRevision++
    }

    fun deleteUserscript(scriptId: String) {
        val next = UserscriptManager.deleteScript(getApplication(), scriptId)
        installedUserscripts.clear()
        installedUserscripts.addAll(next)
        userscriptRevision++
        emitToast("🗑️ Userscript dihapus.")
    }
}
