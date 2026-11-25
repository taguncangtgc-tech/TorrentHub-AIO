package com.torrenthub.aio.ui

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.annotation.StringRes
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.torrenthub.aio.R
import com.torrenthub.aio.data.AppDownloadManager
import com.torrenthub.aio.data.AppLanguage
import com.torrenthub.aio.data.AppThemeMode
import com.torrenthub.aio.data.GeminiEngine
import com.torrenthub.aio.data.PikPakClient
import com.torrenthub.aio.data.RssRepository
import com.torrenthub.aio.data.SettingsRepository
import com.torrenthub.aio.data.SubtitleRepository
import com.torrenthub.aio.data.TorrentRepository
import com.torrenthub.aio.data.UserscriptManager
import com.torrenthub.aio.model.BrowserBookmark
import com.torrenthub.aio.model.DownloadRecord
import com.torrenthub.aio.model.DownloadState
import com.torrenthub.aio.model.GeminiJob
import com.torrenthub.aio.model.PikPakCredentials
import com.torrenthub.aio.model.PikPakDriveItem
import com.torrenthub.aio.model.PikPakFolder
import com.torrenthub.aio.model.PikPakTaskInfo
import com.torrenthub.aio.model.PillState
import com.torrenthub.aio.model.QuickStreamStage
import com.torrenthub.aio.model.QuickStreamState
import com.torrenthub.aio.model.RssFeedItem
import com.torrenthub.aio.model.RssFeedSource
import com.torrenthub.aio.model.SafVideoFile
import com.torrenthub.aio.model.SourceStatus
import com.torrenthub.aio.model.SubtitleItem
import com.torrenthub.aio.model.SubtitleProvider
import com.torrenthub.aio.model.TorrentItem
import com.torrenthub.aio.model.TranslationProvider
import com.torrenthub.aio.model.UserscriptItem
import com.torrenthub.aio.model.UserscriptMenuCmd
import com.torrenthub.aio.network.HttpClient
import com.torrenthub.aio.util.Formatters
import com.torrenthub.aio.util.LocaleHelper
import com.torrenthub.aio.util.NotificationHelper
import com.torrenthub.aio.util.SafSubtitleHelper
import com.torrenthub.aio.util.TrackerManager
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
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

    val settings = SettingsRepository.getInstance(app)

    private val _toastFlow = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val toastFlow = _toastFlow.asSharedFlow()

    data class VideoPlayEvent(
        val streamUrl: String,
        val videoTitle: String
    )

    private val _videoPlayFlow = MutableSharedFlow<VideoPlayEvent>(extraBufferCapacity = 4)
    val videoPlayFlow = _videoPlayFlow.asSharedFlow()

    var quickStreamState by mutableStateOf<QuickStreamState?>(null)
        private set
    private var quickStreamJob: Job? = null

    fun emitToast(msg: String) {
        _toastFlow.tryEmit(msg)
    }

    fun str(@StringRes resId: Int, vararg args: Any): String {
        val res = LocaleHelper.getLocalizedResources(getApplication(), appLanguage.code)
        return if (args.isEmpty()) res.getString(resId) else res.getString(resId, *args)
    }

    fun localizedLangLabel(lang: AppLanguage): String = when (lang) {
        AppLanguage.ID -> str(R.string.lang_id)
        AppLanguage.EN -> str(R.string.lang_en)
        AppLanguage.ZH -> str(R.string.lang_zh)
        AppLanguage.JA -> str(R.string.lang_ja)
        AppLanguage.KO -> str(R.string.lang_ko)
    }

    /* =====================================================================
     * PHASE 1: GLOBAL APP SETTINGS (LANGUAGE, SUBTITLE TARGET, THEME, SWIPE)
     * ===================================================================== */
    var appLanguage by mutableStateOf(settings.appLanguage)
        private set
    var subtitleTargetLang by mutableStateOf(settings.subtitleTargetLang)
        private set
    var themeMode by mutableStateOf(settings.themeMode)
        private set
    var browserTabSwipeEnabled by mutableStateOf(settings.browserTabSwipeEnabled)
        private set
    var showSettingsDialog by mutableStateOf(false)

    var currentTab by mutableIntStateOf(0)

    fun updateAppLanguage(lang: AppLanguage) {
        if (appLanguage == lang) return
        appLanguage = lang
        LocaleHelper.localeForCode(lang.code).let { java.util.Locale.setDefault(it) }
        settings.setAppLanguage(lang)
    }

    fun updateThemeMode(mode: AppThemeMode) {
        if (themeMode == mode) return
        themeMode = mode
        settings.setThemeMode(mode)
    }

    fun updateBrowserTabSwipe(enabled: Boolean) {
        browserTabSwipeEnabled = enabled
        settings.setBrowserTabSwipeEnabled(enabled)
    }

    fun updateSubtitleTargetLang(lang: AppLanguage) {
        if (subtitleTargetLang == lang) return
        subtitleTargetLang = lang
        if (GeminiEngine.isBuiltInDefaultTranslateNotes(geminiNotesTranslate)) {
            geminiNotesTranslate = GeminiEngine.defaultNotesTranslate(lang.code)
        }
        if (GeminiEngine.isBuiltInDefaultPolishNotes(geminiNotesPolish)) {
            geminiNotesPolish = GeminiEngine.defaultNotesPolish(lang.code)
        }
        saveGeminiSettings()

        if (!isGeminiRunning && geminiJobs.isNotEmpty()) {
            geminiJobs.forEachIndexed { idx, job ->
                val sub = parsedSubMap[job.id] ?: return@forEachIndexed
                val saved = settings.loadJobProgress(job.fileName, sub.texts.size, geminiTaskMode, lang.code)
                if (saved != null) {
                    progressMap[job.id] = saved
                } else {
                    progressMap.remove(job.id)
                }
                val nonEmptyCount = sub.texts.count { it.isNotBlank() }
                val savedDone = saved?.count { it.isNotBlank() } ?: 0
                val hasSaved = savedDone > 0
                val outName = GeminiEngine.formatOutputFileName(job.fileName, geminiNameMode, lang.code)
                val built = if (hasSaved && saved != null) GeminiEngine.buildSubtitle(sub, saved, currentBuildOpts()) else null
                geminiJobs[idx] = job.copy(
                    pct = if (hasSaved && nonEmptyCount > 0) ((savedDone * 100) / nonEmptyCount) else 0,
                    statusText = if (hasSaved) str(R.string.gemini_job_saved_progress, savedDone, nonEmptyCount)
                    else str(R.string.gemini_job_ready, nonEmptyCount, outName),
                    resultContent = built,
                    hasSavedProgress = hasSaved
                )
            }
        }
    }

    /* =====================================================================
     * TAB 0: TORRENT SEARCH (16 SITUS)
     * ===================================================================== */
    var torrentQuery by mutableStateOf("")
    var torrentSubFilter by mutableStateOf("")
    var torrentSortMode by mutableStateOf(settings.torrentSortMode)
    var torrentSourceFilter by mutableStateOf("all")
    var torrentTagFilter by mutableStateOf("all")
    var hideDeadTorrents by mutableStateOf(settings.hideDeadTorrents)
    var isSearchingTorrents by mutableStateOf(false)

    val enabledSources = mutableStateMapOf<String, Boolean>()
    val sourceStatuses = mutableStateListOf<SourceStatus>()
    private val rawPool = mutableListOf<TorrentItem>()
    private val poolMutex = Mutex()
    val allTorrents = mutableStateListOf<TorrentItem>()
    val selectedMagnets = mutableStateMapOf<String, Boolean>()

    private var torrentSearchJob: Job? = null

    /* =====================================================================
     * TAB 1: SUBTITLE SEARCH (MULTI-SOURCE + SAF AUTO-PAIRING)
     * ===================================================================== */
    var subtitleQuery by mutableStateOf("")
    var subtitleProvider by mutableStateOf(settings.subtitleProvider)
        private set
    var subtitleSortMode by mutableStateOf("size-desc")
    var isSearchingSubtitles by mutableStateOf(false)
    var subtitleStatusMsg by mutableStateOf<String?>(null)
    val subtitleResults = mutableStateListOf<SubtitleItem>()
    private var subtitleSearchJob: Job? = null

    var autoPairSubtitleVideo by mutableStateOf(settings.autoPairSubtitleVideo)
        private set
    var autoPairFolderUri by mutableStateOf(settings.autoPairFolderUri)
        private set
    var autoPairFolderName by mutableStateOf(settings.autoPairFolderName)
        private set
    var autoPairVideoName by mutableStateOf(settings.autoPairVideoName)
        private set

    /* =====================================================================
     * TAB 2: SUBTITLE TRANSLATOR (GOOGLE FREE, GEMINI, DEEPL, OPENAI)
     * ===================================================================== */
    var translationProvider by mutableStateOf(settings.translationProvider)
        private set
    var googleTranslateEndpoint by mutableStateOf(settings.googleTranslateEndpoint)
    var geminiEndpoint by mutableStateOf(settings.geminiEndpoint)
    var deeplApiKey by mutableStateOf(settings.deeplApiKey)
    var deeplModel by mutableStateOf(settings.deeplModel)
    var deeplEndpoint by mutableStateOf(settings.deeplEndpoint)
    var openaiApiKey by mutableStateOf(settings.openaiApiKey)
    var openaiModel by mutableStateOf(settings.openaiModel)
    var openaiEndpoint by mutableStateOf(settings.openaiEndpoint)
    val openaiAvailableModels = mutableStateListOf<String>().apply {
        addAll(GeminiEngine.OPENAI_PRESET_MODELS)
    }

    var geminiKeysRaw by mutableStateOf(settings.geminiKeysRaw)
    var geminiModel by mutableStateOf(settings.geminiModel)
    val geminiAvailableModels = mutableStateListOf<String>()
    var geminiTaskMode by mutableStateOf(settings.geminiTaskMode)
    var geminiAutoDetect by mutableStateOf(settings.geminiAutoDetect)
    var geminiNameMode by mutableStateOf(settings.geminiNameMode)
    var geminiNotesTranslate by mutableStateOf(settings.geminiNotesTranslate)
    var geminiNotesPolish by mutableStateOf(settings.geminiNotesPolish)
    var geminiGlossary by mutableStateOf(settings.geminiGlossary)
    var geminiRelaxSafety by mutableStateOf(settings.geminiRelaxSafety)
    var geminiIncludeAllLines by mutableStateOf(settings.geminiIncludeAllLines)
    var geminiSlidingContext by mutableStateOf(settings.geminiSlidingContext)
    var geminiCleanAds by mutableStateOf(settings.geminiCleanAds)
    var geminiBilingual by mutableStateOf(settings.geminiBilingual)
    var geminiTimeShiftSec by mutableStateOf(settings.geminiTimeShiftSec)
    var geminiAutoUploadPikPak by mutableStateOf(settings.geminiAutoUploadPikPak)
    var uploadingGeminiJobId by mutableStateOf<String?>(null)

    val geminiJobs = mutableStateListOf<GeminiJob>()
    private val parsedSubMap = HashMap<String, GeminiEngine.ParsedSubtitle>()
    private val progressMap = HashMap<String, MutableList<String>>()
    var isGeminiRunning by mutableStateOf(false)
    private var geminiCancelRequested = false
    var geminiStatusText by mutableStateOf<String?>(null)
    var geminiPreviewLog by mutableStateOf<String?>(null)

    /* =====================================================================
     * TAB 3: PIKPAK CLOUD ACCOUNT & MINI FILE MANAGER
     * ===================================================================== */
    var pikpakCreds by mutableStateOf(settings.loadPikPakCreds())
    var pikpakSubTab by mutableIntStateOf(0)
    var isLoadingPikPakTasks by mutableStateOf(false)
    val pikpakTasks = mutableStateListOf<PikPakTaskInfo>()

    val pikpakBrowserStack = mutableStateListOf<Pair<String, String>>()
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
    var browserCurrentUrl by mutableStateOf(settings.browserLastUrl)
    var browserPageTitle by mutableStateOf("")
    var browserIsLoading by mutableStateOf(false)
    var browserProgress by mutableIntStateOf(0)
    var browserCanGoBack by mutableStateOf(false)
    var browserCanGoForward by mutableStateOf(false)
    var browserAdShieldEnabled by mutableStateOf(settings.browserAdShieldEnabled)
    var browserAntiPopupStrict by mutableStateOf(settings.browserAntiPopupStrict)
        private set
    var browserBlockedAdsCount by mutableIntStateOf(
        settings.browserBlockedAdsTotal.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    )
    var browserSelectedQuery by mutableStateOf("")

    val browserBookmarks = mutableStateListOf<BrowserBookmark>()
    val installedUserscripts = mutableStateListOf<UserscriptItem>()
    var userscriptRevision by mutableIntStateOf(0)
    val activePageScriptNames = mutableStateListOf<String>()
    val activePageMenuCommands = mutableStateListOf<UserscriptMenuCmd>()

    /* =====================================================================
     * PHASE 4: DOWNLOAD MANAGER, CUSTOM TRACKER LIST & RSS MONITOR
     * ===================================================================== */
    val downloadManager = AppDownloadManager.getInstance(app)
    val downloadRecords: List<DownloadRecord>
        get() = downloadManager.records
    var showDownloadManagerDialog by mutableStateOf(false)
    val activeDownloadsCount: Int
        get() = downloadManager.records.count {
            it.state == DownloadState.DOWNLOADING || it.state == DownloadState.QUEUED
        }

    var autoAppendTrackers by mutableStateOf(settings.autoAppendTrackers)
        private set
    var trackerSourceUrl by mutableStateOf(settings.trackerSourceUrl)
    var fetchedTrackersRaw by mutableStateOf(settings.fetchedTrackersRaw)
        private set
    var customTrackersRaw by mutableStateOf(settings.customTrackersRaw)
    var trackerLastUpdatedMs by mutableLongStateOf(settings.trackerLastUpdatedMs)
        private set
    var isUpdatingTrackers by mutableStateOf(false)
        private set

    val activeTrackersList: List<String>
        get() = TrackerManager.getActiveTrackers(fetchedTrackersRaw, customTrackersRaw)

    var torrentSubTab by mutableIntStateOf(0)
    val rssFeeds = mutableStateListOf<RssFeedSource>()
    val rssWatchKeywords = mutableStateListOf<String>()
    val rssItems = mutableStateListOf<RssFeedItem>()
    var isLoadingRss by mutableStateOf(false)
        private set
    var rssOnlyMatched by mutableStateOf(false)
    var rssSelectedFeedId by mutableStateOf("all")
    var rssSearchFilter by mutableStateOf("")
    private var rssRefreshJob: Job? = null

    val rssMatchedCount: Int
        get() = rssItems.count { it.isMatched }

    init {
        syncFormattersTrackerConfig()
        if (GeminiEngine.isBuiltInDefaultTranslateNotes(geminiNotesTranslate)) {
            geminiNotesTranslate = GeminiEngine.defaultNotesTranslate(subtitleTargetLang.code)
        }
        if (GeminiEngine.isBuiltInDefaultPolishNotes(geminiNotesPolish)) {
            geminiNotesPolish = GeminiEngine.defaultNotesPolish(subtitleTargetLang.code)
        }
        loadEnabledSources()
        loadSavedModels()
        loadBrowserBookmarks()
        reloadInstalledUserscripts()
        loadRssConfiguration()
        if (pikpakCreds.isLoggedIn) {
            refreshPikPakTasks()
            loadPikPakDirectory("")
        }
    }

    private fun loadEnabledSources() {
        val raw = settings.enabledSourcesJson
        val savedJson = try { if (raw != null) JSONObject(raw) else null } catch (_: Exception) { null }
        for (s in TorrentRepository.SOURCES) {
            enabledSources[s.id] = savedJson?.optBoolean(s.id, s.defaultOn) ?: s.defaultOn
        }
    }

    fun toggleSource(sourceId: String, checked: Boolean) {
        enabledSources[sourceId] = checked
        val obj = JSONObject()
        enabledSources.forEach { (k, v) -> obj.put(k, v) }
        settings.setEnabledSourcesJson(obj.toString())
    }

    fun setAllSources(enableAll: Boolean) {
        TorrentRepository.SOURCES.forEach { enabledSources[it.id] = enableAll }
        val obj = JSONObject()
        enabledSources.forEach { (k, v) -> obj.put(k, v) }
        settings.setEnabledSourcesJson(obj.toString())
    }

    fun updateTorrentSort(mode: String) {
        torrentSortMode = mode
        settings.setTorrentSortMode(mode)
    }

    fun updateHideDead(hide: Boolean) {
        hideDeadTorrents = hide
        settings.setHideDeadTorrents(hide)
    }

    fun runTorrentSearch() {
        val q = torrentQuery.trim()
        if (q.isEmpty()) {
            emitToast(str(R.string.toast_enter_query))
            return
        }
        val activeSources = TorrentRepository.SOURCES.filter { enabledSources[it.id] == true }
        if (activeSources.isEmpty()) {
            emitToast(str(R.string.toast_enable_source))
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
                        val msg = e.message ?: "Error"
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
            updateSourceStatus(SourceStatus(sourceId, src.label, PillState.LOADING, message = "…"))
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
                emitToast(str(R.string.toast_bypass_ok, src.label, items.size))
            } catch (e: Exception) {
                updateSourceStatus(
                    SourceStatus(
                        sourceId = sourceId,
                        label = src.label,
                        state = PillState.ERROR,
                        message = e.message ?: "Bypass error",
                        canBypassCloudflare = true,
                        bypassUrl = pageUrl
                    )
                )
                emitToast(str(R.string.toast_bypass_fail, e.message ?: "Error"))
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
     * SUBTITLE ACTIONS (MULTI-SOURCE & AUTO-PAIRING)
     * ===================================================================== */
    fun subtitleProviderLabel(provider: SubtitleProvider): String = when (provider) {
        SubtitleProvider.ALL -> str(R.string.sub_provider_all)
        SubtitleProvider.XUNLEI -> str(R.string.sub_provider_xunlei)
        SubtitleProvider.SUBTITLECAT -> str(R.string.sub_provider_subtitlecat)
        SubtitleProvider.OPENSUBTITLES -> str(R.string.sub_provider_opensubtitles)
        SubtitleProvider.SUBDL -> str(R.string.sub_provider_subdl)
    }

    fun selectSubtitleProvider(provider: SubtitleProvider) {
        val changed = subtitleProvider != provider
        subtitleProvider = provider
        settings.setSubtitleProvider(provider)
        if (changed && subtitleQuery.trim().isNotEmpty()) {
            runSubtitleSearch()
        }
    }

    fun updateAutoPairEnabled(enabled: Boolean) {
        autoPairSubtitleVideo = enabled
        settings.setAutoPairSubtitleVideo(enabled)
    }

    fun updateAutoPairFolder(uri: Uri) {
        val ctx = getApplication<Application>()
        SafSubtitleHelper.persistTreePermission(ctx, uri)
        val folderName = SafSubtitleHelper.getTreeDisplayName(ctx, uri)
        val uriStr = uri.toString()
        autoPairFolderUri = uriStr
        autoPairFolderName = folderName
        val videos = SafSubtitleHelper.listVideosInTree(ctx, uriStr)
        if (videos.size == 1) {
            autoPairVideoName = videos.first().name
        } else if (autoPairVideoName.isNotBlank() && videos.none { it.name == autoPairVideoName }) {
            autoPairVideoName = ""
        }
        autoPairSubtitleVideo = true
        settings.setAutoPairSubtitleVideo(true)
        settings.saveAutoPairTarget(autoPairFolderUri, autoPairFolderName, autoPairVideoName)
        emitToast(str(R.string.toast_autopair_folder_set, folderName, videos.size))
    }

    fun updateAutoPairVideoName(videoName: String) {
        autoPairVideoName = videoName
        settings.saveAutoPairTarget(autoPairFolderUri, autoPairFolderName, videoName)
        if (videoName.isNotBlank()) {
            emitToast(str(R.string.toast_autopair_video_set, videoName))
        }
    }

    fun listVideosInAutoPairFolder(): List<SafVideoFile> {
        if (autoPairFolderUri.isBlank()) return emptyList()
        return SafSubtitleHelper.listVideosInTree(getApplication(), autoPairFolderUri)
    }

    suspend fun saveSubtitlePairedToSaf(
        videoFileName: String,
        subtitleNameOrExt: String,
        contentUtf8: String,
        pinVideoSelection: Boolean = false
    ): String = withContext(Dispatchers.IO) {
        val pairedName = SafSubtitleHelper.buildPairedSubtitleFileName(videoFileName, subtitleNameOrExt)
        val savedName = SafSubtitleHelper.saveSubtitleToTreeFolder(
            context = getApplication(),
            treeUriString = autoPairFolderUri,
            targetFileName = pairedName,
            contentUtf8 = contentUtf8
        )
        if (pinVideoSelection) {
            withContext(Dispatchers.Main) {
                autoPairVideoName = videoFileName
                settings.saveAutoPairTarget(autoPairFolderUri, autoPairFolderName, videoFileName)
            }
        }
        savedName
    }

    fun runSubtitleSearch(customQuery: String? = null) {
        if (customQuery != null) subtitleQuery = customQuery
        val q = subtitleQuery.trim()
        if (q.isEmpty()) {
            emitToast(str(R.string.toast_enter_sub_query))
            return
        }
        val activeProvider = subtitleProvider
        val activeTarget = subtitleTargetLang.code
        subtitleSearchJob?.cancel()
        subtitleSearchJob = viewModelScope.launch {
            isSearchingSubtitles = true
            subtitleResults.clear()
            subtitleStatusMsg = str(R.string.sub_status_searching_source, q, subtitleProviderLabel(activeProvider))
            try {
                val initial = SubtitleRepository.searchSubtitles(q, activeProvider, activeTarget)
                if (initial.isEmpty()) {
                    subtitleStatusMsg = str(R.string.sub_status_not_found, q)
                } else {
                    subtitleResults.addAll(initial)
                    subtitleStatusMsg = str(R.string.sub_status_probing, 0, initial.size)
                    val sized = SubtitleRepository.probeSizes(initial) { done, total, updatedItem ->
                        viewModelScope.launch {
                            val idx = subtitleResults.indexOfFirst { it.idKey == updatedItem.idKey }
                            if (idx >= 0) subtitleResults[idx] = updatedItem
                            subtitleStatusMsg = str(R.string.sub_status_probing, done, total)
                        }
                    }
                    subtitleResults.clear()
                    subtitleResults.addAll(sized)
                    val knownCount = sized.count { it.sizeBytes > 0L }
                    subtitleStatusMsg = str(R.string.sub_status_found, sized.size, knownCount)
                }
            } catch (e: Exception) {
                if (e !is kotlinx.coroutines.CancellationException) {
                    subtitleStatusMsg = str(R.string.sub_status_error, e.message ?: "Error")
                }
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
            emitToast(str(R.string.toast_unsupported_ext, item.ext))
            return
        }
        viewModelScope.launch {
            emitToast(str(R.string.toast_fetching_sub, item.name))
            try {
                val content = SubtitleRepository.fetchSubtitleText(item.url, subtitleTargetLang.code)
                if (content.isBlank()) throw Exception("Empty subtitle content")
                addGeminiJob(item.name, content)
                currentTab = 2
                emitToast(str(R.string.toast_sent_to_gemini, item.name))
            } catch (e: Exception) {
                emitToast(str(R.string.toast_fetch_sub_failed, e.message ?: "Error"))
            }
        }
    }

    /* =====================================================================
     * GEMINI SUBTITLE TRANSLATOR ACTIONS
     * ===================================================================== */
    private fun loadSavedModels() {
        geminiAvailableModels.clear()
        val raw = settings.geminiSavedModelsJson
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

    fun translationProviderDisplayName(provider: TranslationProvider): String = when (provider) {
        TranslationProvider.GOOGLE_FREE -> str(R.string.tr_provider_google_free)
        TranslationProvider.GEMINI -> str(R.string.tr_provider_gemini)
        TranslationProvider.DEEPL -> str(R.string.tr_provider_deepl)
        TranslationProvider.OPENAI -> str(R.string.tr_provider_openai)
    }

    fun updateTranslationProvider(provider: TranslationProvider) {
        translationProvider = provider
        saveTranslationProviderSettings()
    }

    fun saveTranslationProviderSettings() {
        settings.saveTranslationProviderConfig(
            provider = translationProvider,
            googleEndpoint = googleTranslateEndpoint,
            geminiEndpoint = geminiEndpoint,
            deeplKey = deeplApiKey,
            deeplModel = deeplModel,
            deeplEndpoint = deeplEndpoint,
            openaiKey = openaiApiKey,
            openaiModel = openaiModel,
            openaiEndpoint = openaiEndpoint
        )
    }

    fun saveGeminiSettings() {
        saveTranslationProviderSettings()
        settings.saveGeminiSettings(
            keysRaw = geminiKeysRaw,
            model = geminiModel,
            taskMode = geminiTaskMode,
            autoDetect = geminiAutoDetect,
            nameMode = geminiNameMode,
            notesTranslate = geminiNotesTranslate,
            notesPolish = geminiNotesPolish,
            glossary = geminiGlossary,
            relaxSafety = geminiRelaxSafety,
            includeAllLines = geminiIncludeAllLines,
            slidingContext = geminiSlidingContext,
            cleanAds = geminiCleanAds,
            bilingual = geminiBilingual,
            timeShiftSec = geminiTimeShiftSec,
            autoUploadPikPak = geminiAutoUploadPikPak,
            targetLangCode = subtitleTargetLang.code
        )
    }

    private fun currentBuildOpts() = GeminiEngine.BuildOptions(
        shiftSec = geminiTimeShiftSec.toDoubleOrNull() ?: 0.0,
        cleanAds = geminiCleanAds,
        bilingual = geminiBilingual
    )

    fun addGeminiJob(fileName: String, content: String) {
        val ext = fileName.substringAfterLast('.', "srt").lowercase()
        val parsed = GeminiEngine.parseSubtitle(content, ext)
        val targetCode = subtitleTargetLang.code
        val targetLabel = localizedLangLabel(subtitleTargetLang)

        if (geminiAutoDetect && geminiJobs.isEmpty()) {
            val detected = GeminiEngine.detectLanguageMode(parsed.texts, targetCode)
            if (detected != geminiTaskMode) {
                geminiTaskMode = detected
                saveGeminiSettings()
                geminiStatusText = if (detected == "polish") {
                    str(R.string.gemini_autodetect_polish, targetLabel)
                } else {
                    str(R.string.gemini_autodetect_translate, targetLabel)
                }
            }
        }

        val id = UUID.randomUUID().toString()
        parsedSubMap[id] = parsed
        val saved = settings.loadJobProgress(fileName, parsed.texts.size, geminiTaskMode, targetCode)
        if (saved != null) progressMap[id] = saved

        val nonEmptyCount = parsed.texts.count { it.isNotBlank() }
        val savedDone = saved?.count { it.isNotBlank() } ?: 0
        val hasSaved = savedDone > 0
        val outName = GeminiEngine.formatOutputFileName(fileName, geminiNameMode, targetCode)
        val built = if (hasSaved && saved != null) GeminiEngine.buildSubtitle(parsed, saved, currentBuildOpts()) else null

        val newJob = GeminiJob(
            id = id,
            fileName = fileName,
            ext = ext,
            originalContent = content,
            totalLines = nonEmptyCount,
            pct = if (hasSaved && nonEmptyCount > 0) ((savedDone * 100) / nonEmptyCount) else 0,
            statusText = if (hasSaved) str(R.string.gemini_job_saved_progress, savedDone, nonEmptyCount)
            else str(R.string.gemini_job_ready, nonEmptyCount, outName),
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
        geminiStatusText = str(R.string.gemini_status_queue_cleared)
    }

    fun resetSingleGeminiJob(jobId: String) {
        if (isGeminiRunning) return
        val idx = geminiJobs.indexOfFirst { it.id == jobId }
        if (idx < 0) return
        val job = geminiJobs[idx]
        val sub = parsedSubMap[jobId] ?: return
        settings.clearJobProgress(job.fileName, sub.texts.size, geminiTaskMode, subtitleTargetLang.code)
        progressMap.remove(jobId)
        geminiJobs[idx] = job.copy(
            pct = 0,
            resultContent = null,
            hasSavedProgress = false,
            uploadedToPikPak = false,
            statusText = str(R.string.gemini_job_reset)
        )
    }

    fun updateGeminiJobEditedContent(jobId: String, editedContent: String, editedTranslatedResult: Boolean) {
        val idx = geminiJobs.indexOfFirst { it.id == jobId }
        if (idx < 0) return
        val job = geminiJobs[idx]
        if (editedTranslatedResult) {
            geminiJobs[idx] = job.copy(resultContent = editedContent)
        } else {
            val parsed = GeminiEngine.parseSubtitle(editedContent, job.ext)
            parsedSubMap[jobId] = parsed
            val nonEmpty = parsed.texts.count { it.isNotBlank() }
            val outName = GeminiEngine.formatOutputFileName(job.fileName, geminiNameMode, subtitleTargetLang.code)
            val savedTr = progressMap[jobId]
            val rebuilt = if (savedTr != null && savedTr.size == parsed.texts.size && savedTr.any { it.isNotBlank() }) {
                GeminiEngine.buildSubtitle(parsed, savedTr, currentBuildOpts())
            } else {
                job.resultContent
            }
            geminiJobs[idx] = job.copy(
                originalContent = editedContent,
                totalLines = nonEmpty,
                statusText = if (job.pct >= 100) job.statusText else str(R.string.gemini_job_ready, nonEmpty, outName),
                resultContent = rebuilt
            )
        }
        emitToast(str(R.string.toast_editor_applied))
    }

    fun testGeminiKeys() {
        saveGeminiSettings()
        val provider = translationProvider
        val keys: List<String> = when (provider) {
            TranslationProvider.GOOGLE_FREE -> listOf("google-free")
            TranslationProvider.GEMINI -> GeminiEngine.parseKeys(geminiKeysRaw)
            TranslationProvider.DEEPL -> GeminiEngine.parseKeys(deeplApiKey)
            TranslationProvider.OPENAI -> GeminiEngine.parseKeys(openaiApiKey)
        }
        if (provider != TranslationProvider.GOOGLE_FREE && keys.isEmpty()) {
            emitToast(str(R.string.toast_enter_provider_api_key, translationProviderDisplayName(provider)))
            return
        }
        val activeModel = when (provider) {
            TranslationProvider.OPENAI -> openaiModel
            TranslationProvider.DEEPL -> deeplModel
            else -> geminiModel
        }
        val activeEndpoint = when (provider) {
            TranslationProvider.GOOGLE_FREE -> googleTranslateEndpoint
            TranslationProvider.GEMINI -> geminiEndpoint
            TranslationProvider.DEEPL -> deeplEndpoint
            TranslationProvider.OPENAI -> openaiEndpoint
        }
        viewModelScope.launch {
            geminiStatusText = str(R.string.gemini_status_testing_keys, keys.size)
            val logs = GeminiEngine.pingProvider(provider, keys, activeModel, activeEndpoint, subtitleTargetLang.code)
            geminiPreviewLog = logs.joinToString("\n")
            geminiStatusText = str(R.string.gemini_status_test_done, keys.size)
            emitToast(logs.firstOrNull() ?: str(R.string.gemini_status_test_done, keys.size))
        }
    }

    fun refreshGeminiModels() {
        val provider = translationProvider
        val keys = when (provider) {
            TranslationProvider.OPENAI -> GeminiEngine.parseKeys(openaiApiKey)
            else -> GeminiEngine.parseKeys(geminiKeysRaw)
        }
        if (keys.isEmpty()) {
            emitToast(str(R.string.toast_enter_provider_api_key, translationProviderDisplayName(provider)))
            return
        }
        saveGeminiSettings()
        viewModelScope.launch {
            geminiStatusText = str(R.string.gemini_status_fetching_models)
            if (provider == TranslationProvider.OPENAI) {
                val models = GeminiEngine.fetchAvailableModels(keys, TranslationProvider.OPENAI, openaiEndpoint)
                openaiAvailableModels.clear()
                openaiAvailableModels.addAll(models)
                geminiStatusText = str(R.string.gemini_status_models_ready, models.size)
            } else {
                val models = GeminiEngine.fetchAvailableModels(keys, TranslationProvider.GEMINI, geminiEndpoint)
                geminiAvailableModels.clear()
                geminiAvailableModels.addAll(models)
                settings.saveGeminiModelsList(models)
                geminiStatusText = str(R.string.gemini_status_models_ready, models.size)
            }
        }
    }

    fun stopGeminiRun() {
        geminiCancelRequested = true
        geminiStatusText = str(R.string.gemini_status_stopping)
    }

    private suspend fun uploadGeminiJobToPikPakInternal(jobId: String, silentIfAuto: Boolean = false): Boolean {
        val idx = geminiJobs.indexOfFirst { it.id == jobId }
        if (idx < 0) return false
        val job = geminiJobs[idx]
        val content = job.resultContent
        if (content.isNullOrBlank()) {
            if (!silentIfAuto) emitToast(str(R.string.toast_no_result_yet))
            return false
        }
        if (!pikpakCreds.isLoggedIn) {
            if (!silentIfAuto) {
                emitToast(str(R.string.toast_pikpak_login_first))
                currentTab = 3
            }
            return false
        }

        val outName = GeminiEngine.formatOutputFileName(job.fileName, geminiNameMode, subtitleTargetLang.code)
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
                        statusText = "${cur.statusText} · ☁️ ${pikpakCreds.targetFolderName}"
                    )
                }
                if (currentPikPakFolderId == pikpakCreds.targetFolderId) {
                    loadPikPakDirectory(currentPikPakFolderId)
                }
                if (!silentIfAuto) {
                    emitToast(str(R.string.toast_pikpak_uploaded, outName, pikpakCreds.targetFolderName))
                }
                true
            } else {
                emitToast("❌ PikPak: ${res.errorMessage}")
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
        val provider = translationProvider
        val geminiKeys = GeminiEngine.parseKeys(geminiKeysRaw)
        val deeplKeys = GeminiEngine.parseKeys(deeplApiKey)
        val openaiKeys = GeminiEngine.parseKeys(openaiApiKey)

        when (provider) {
            TranslationProvider.GOOGLE_FREE -> Unit
            TranslationProvider.GEMINI -> if (geminiKeys.isEmpty()) {
                emitToast(str(R.string.toast_enter_provider_api_key, translationProviderDisplayName(provider)))
                return
            }
            TranslationProvider.DEEPL -> if (deeplKeys.isEmpty() && deeplEndpoint.isBlank()) {
                emitToast(str(R.string.toast_enter_provider_api_key, translationProviderDisplayName(provider)))
                return
            }
            TranslationProvider.OPENAI -> if (openaiKeys.isEmpty()) {
                emitToast(str(R.string.toast_enter_provider_api_key, translationProviderDisplayName(provider)))
                return
            }
        }

        if (geminiJobs.isEmpty()) {
            emitToast(str(R.string.toast_add_sub_first))
            return
        }
        saveGeminiSettings()

        val appCtx = getApplication<Application>()
        val activeTargetCode = subtitleTargetLang.code
        val activeEndpoint = when (provider) {
            TranslationProvider.GOOGLE_FREE -> googleTranslateEndpoint
            TranslationProvider.GEMINI -> geminiEndpoint
            TranslationProvider.DEEPL -> deeplEndpoint
            TranslationProvider.OPENAI -> openaiEndpoint
        }

        viewModelScope.launch {
            isGeminiRunning = true
            geminiCancelRequested = false
            val cfg = GeminiEngine.TranslateConfig(
                provider = provider,
                keys = geminiKeys,
                model = geminiModel,
                customEndpoint = activeEndpoint,
                deeplKey = deeplApiKey,
                deeplModel = deeplModel,
                openaiKey = openaiApiKey,
                openaiModel = openaiModel,
                taskMode = geminiTaskMode,
                targetLang = activeTargetCode,
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
                geminiStatusText = str(R.string.gemini_status_processing, job.fileName)
                NotificationHelper.showTranslationProgress(
                    appCtx,
                    job.fileName,
                    job.pct,
                    str(R.string.gemini_status_processing, job.fileName)
                )

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
                                settings.saveJobProgress(job.fileName, sub.texts.size, geminiTaskMode, activeTargetCode, copyList)
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
                        settings.clearJobProgress(job.fileName, sub.texts.size, geminiTaskMode, activeTargetCode)
                    }
                    geminiJobs[idx] = geminiJobs[idx].copy(
                        pct = 100,
                        statusText = if (limitLines > 0) str(R.string.gemini_status_test_lines_done, limitLines)
                        else str(R.string.gemini_status_done_save),
                        resultContent = builtFinal,
                        hasSavedProgress = false
                    )
                    completedCount++

                    if (limitLines == 0 && geminiAutoUploadPikPak && pikpakCreds.isLoggedIn) {
                        uploadGeminiJobToPikPakInternal(job.id, silentIfAuto = true)
                    }

                    if (limitLines == 0 && autoPairSubtitleVideo && autoPairFolderUri.isNotBlank()) {
                        try {
                            val pairedCandidate = if (autoPairVideoName.isNotBlank() && targetIndices.size == 1) {
                                autoPairVideoName
                            } else {
                                val videos = withContext(Dispatchers.IO) {
                                    SafSubtitleHelper.listVideosInTree(appCtx, autoPairFolderUri)
                                }
                                SafSubtitleHelper.findBestMatchingVideo(videos, job.fileName)?.name
                                    ?: autoPairVideoName.takeIf { it.isNotBlank() }
                            }
                            if (!pairedCandidate.isNullOrBlank()) {
                                val savedPaired = saveSubtitlePairedToSaf(
                                    videoFileName = pairedCandidate,
                                    subtitleNameOrExt = job.ext,
                                    contentUtf8 = builtFinal,
                                    pinVideoSelection = false
                                )
                                val curJob = geminiJobs[idx]
                                geminiJobs[idx] = curJob.copy(
                                    statusText = "${curJob.statusText} · 🎬 $savedPaired"
                                )
                                emitToast(str(R.string.toast_autopair_saved, savedPaired))
                            }
                        } catch (_: Exception) {}
                    }
                } catch (e: Exception) {
                    geminiJobs[idx] = geminiJobs[idx].copy(
                        statusText = "⚠️ ${e.message}"
                    )
                }
            }

            isGeminiRunning = false
            if (geminiCancelRequested) {
                NotificationHelper.cancelProgress(appCtx)
                geminiStatusText = str(R.string.gemini_status_stopped_saved)
            } else if (completedCount > 0) {
                val summary = if (limitLines > 0) {
                    str(R.string.gemini_status_test_lines_done, limitLines)
                } else {
                    str(R.string.notif_done_summary, completedCount) +
                        (if (geminiAutoUploadPikPak && pikpakCreds.isLoggedIn) " (PikPak ✓)" else "")
                }
                NotificationHelper.showTranslationFinished(appCtx, summary)
                geminiStatusText = str(R.string.gemini_status_all_done)
            } else {
                NotificationHelper.cancelProgress(appCtx)
                geminiStatusText = str(R.string.gemini_status_stopped_error)
            }
        }
    }

    /* =====================================================================
     * PIKPAK CLOUD DOWNLOAD & MINI FILE MANAGER ACTIONS
     * ===================================================================== */
    fun savePikPakCreds(creds: PikPakCredentials) {
        pikpakCreds = creds
        settings.savePikPakCreds(creds)
    }

    fun logoutPikPak() {
        savePikPakCreds(PikPakCredentials())
        pikpakTasks.clear()
        pikpakDirItems.clear()
        pikpakBrowserStack.clear()
        emitToast(str(R.string.toast_pikpak_logged_out))
    }

    fun sendMagnetsToPikPak(magnets: List<String>) {
        if (!pikpakCreds.isLoggedIn) {
            emitToast(str(R.string.toast_pikpak_login_first))
            currentTab = 3
            return
        }
        val enrichedMagnets = magnets.map { prepareMagnet(it) }.filter { it.isNotBlank() }
        if (enrichedMagnets.isEmpty()) return
        viewModelScope.launch {
            emitToast(str(R.string.toast_pikpak_sending_magnets, enrichedMagnets.size))
            val res = PikPakClient.sendMagnetsToCloud(enrichedMagnets, pikpakCreds)
            res.updatedCreds?.let { savePikPakCreds(it) }
            if (res.ok) {
                emitToast(str(R.string.toast_pikpak_magnets_sent, res.addedCount, pikpakCreds.targetFolderName))
                refreshPikPakTasks()
            } else {
                emitToast(str(R.string.toast_pikpak_magnets_failed, res.addedCount, enrichedMagnets.size, res.errorMessage))
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
                pikpakDirError = e.message ?: "Error"
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
        emitToast(str(R.string.toast_pikpak_folder_set, name))
    }

    fun videoHasMatchingSubtitleInDir(videoItem: PikPakDriveItem, dirItems: List<PikPakDriveItem>): Boolean {
        val subs = dirItems.filter { it.isSubtitle }
        if (subs.isEmpty()) return false
        val vidBase = videoItem.name.substringBeforeLast('.').trim().lowercase()
        val vidCode = Formatters.extractVideoCode(videoItem.name).uppercase()
        return subs.any { sub ->
            val subBase = sub.name.substringBeforeLast('.').trim().lowercase()
            val subCleanBase = subBase.replace(Regex("\\.(id|ind|en|eng|zh|chs|cht|jp|ja|ko|kor)$"), "")
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
            emitToast(str(R.string.toast_fetching_sub, item.name))
            try {
                val links = PikPakClient.fetchFileLinks(item.id, pikpakCreds)
                if (links.downloadUrl.isBlank()) throw Exception("Empty download URL")
                val content = SubtitleRepository.fetchSubtitleText(links.downloadUrl)
                if (content.isBlank()) throw Exception("Empty subtitle content")

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
                emitToast(str(R.string.toast_sent_to_gemini, item.name))
            } catch (e: Exception) {
                emitToast(str(R.string.toast_fetch_sub_failed, e.message ?: "Error"))
            } finally {
                if (busyPikPakItemId == item.id) busyPikPakItemId = null
            }
        }
    }

    suspend fun fetchPikPakSubtitleTextSuspend(item: PikPakDriveItem): String {
        val links = PikPakClient.fetchFileLinks(item.id, pikpakCreds)
        links.updatedCreds?.let { savePikPakCreds(it) }
        if (links.downloadUrl.isBlank()) throw Exception("Empty download URL")
        return SubtitleRepository.fetchSubtitleText(links.downloadUrl)
    }

    suspend fun fetchPikPakStreamLinkSuspend(item: PikPakDriveItem): String {
        val links = PikPakClient.fetchFileLinks(item.id, pikpakCreds)
        links.updatedCreds?.let { savePikPakCreds(it) }
        if (links.streamUrl.isBlank()) throw Exception("Stream URL unavailable")
        return links.streamUrl
    }

    /* =====================================================================
     * PHASE 2: 1-CLICK QUICK STREAM VIA PIKPAK
     * ===================================================================== */
    fun startQuickStream(item: TorrentItem) {
        if (!pikpakCreds.isLoggedIn) {
            emitToast(str(R.string.toast_pikpak_login_first))
            currentTab = 3
            return
        }
        val enrichedMagnet = prepareMagnet(item.magnet)
        quickStreamJob?.cancel()
        quickStreamState = QuickStreamState(
            torrentTitle = item.title,
            magnet = enrichedMagnet,
            stage = QuickStreamStage.SENDING_MAGNET
        )
        quickStreamJob = viewModelScope.launch {
            val submit = PikPakClient.addMagnetForQuickStream(enrichedMagnet, pikpakCreds)
            submit.updatedCreds?.let { savePikPakCreds(it) }
            if (!submit.ok) {
                quickStreamState = quickStreamState?.copy(
                    stage = QuickStreamStage.ERROR,
                    errorMessage = submit.errorMessage
                )
                return@launch
            }

            refreshPikPakTasks()
            quickStreamState = quickStreamState?.copy(
                stage = if (submit.phase == "COMPLETE") QuickStreamStage.RESOLVING_VIDEO else QuickStreamStage.CACHING_CLOUD,
                taskId = submit.taskId,
                fileId = submit.fileId,
                taskName = submit.taskName.ifBlank { item.title },
                phaseLabel = submit.phase.ifBlank { "RUNNING" },
                progress = submit.progress,
                errorMessage = submit.errorMessage
            )

            pollAndResolveQuickStream(maxWaitSec = 90)
        }
    }

    fun continueQuickStreamWaiting() {
        val cur = quickStreamState ?: return
        if (!pikpakCreds.isLoggedIn) return
        quickStreamJob?.cancel()
        quickStreamState = cur.copy(
            stage = QuickStreamStage.CACHING_CLOUD,
            errorMessage = ""
        )
        quickStreamJob = viewModelScope.launch {
            pollAndResolveQuickStream(maxWaitSec = 90)
        }
    }

    fun retryQuickStream() {
        val cur = quickStreamState ?: return
        startQuickStream(TorrentItem(title = cur.torrentTitle, magnet = cur.magnet, hash = ""))
    }

    fun dismissQuickStream(notifyBackground: Boolean = false) {
        val wasCaching = quickStreamState?.stage == QuickStreamStage.CACHING_CLOUD ||
            quickStreamState?.stage == QuickStreamStage.SENDING_MAGNET
        quickStreamJob?.cancel()
        quickStreamJob = null
        quickStreamState = null
        refreshPikPakTasks()
        if (notifyBackground && wasCaching) {
            emitToast(str(R.string.toast_qs_background))
        }
    }

    fun openPikPakTasksFromQuickStream() {
        quickStreamJob?.cancel()
        quickStreamJob = null
        quickStreamState = null
        currentTab = 3
        pikpakSubTab = 1
        refreshPikPakTasks()
    }

    fun playQuickStreamCandidate(videoItem: PikPakDriveItem) {
        val cur = quickStreamState ?: return
        if (cur.busyVideoId != null) return
        viewModelScope.launch {
            quickStreamState = quickStreamState?.copy(busyVideoId = videoItem.id)
            try {
                val streamUrl = fetchPikPakStreamLinkSuspend(videoItem)
                _videoPlayFlow.tryEmit(VideoPlayEvent(streamUrl = streamUrl, videoTitle = videoItem.name))
            } catch (e: Exception) {
                emitToast("❌ ${e.message}")
            } finally {
                quickStreamState = quickStreamState?.copy(busyVideoId = null)
            }
        }
    }

    private suspend fun pollAndResolveQuickStream(maxWaitSec: Int) {
        var windowWaitedSec = 0
        while (true) {
            val cur = quickStreamState ?: return
            if (cur.phaseLabel == "ERROR") {
                quickStreamState = cur.copy(
                    stage = QuickStreamStage.ERROR,
                    errorMessage = cur.errorMessage.ifBlank { "PikPak cloud task failed" }
                )
                return
            }
            if (cur.phaseLabel == "COMPLETE") {
                break
            }
            if (windowWaitedSec >= maxWaitSec) {
                quickStreamState = cur.copy(stage = QuickStreamStage.TIMEOUT)
                refreshPikPakTasks()
                return
            }

            delay(2000L)
            windowWaitedSec += 2
            val latestSnap = quickStreamState ?: return
            val (polled, updatedCreds) = PikPakClient.pollOfflineTask(
                taskId = latestSnap.taskId,
                taskNameHint = latestSnap.taskName,
                creds = pikpakCreds
            )
            updatedCreds?.let { savePikPakCreds(it) }

            if (polled != null) {
                quickStreamState = latestSnap.copy(
                    stage = if (polled.phase == "COMPLETE") QuickStreamStage.RESOLVING_VIDEO else QuickStreamStage.CACHING_CLOUD,
                    taskId = polled.id.ifBlank { latestSnap.taskId },
                    fileId = polled.fileId.ifBlank { latestSnap.fileId },
                    taskName = polled.name.ifBlank { latestSnap.taskName },
                    phaseLabel = polled.phase.ifBlank { latestSnap.phaseLabel },
                    progress = polled.progress,
                    elapsedSec = latestSnap.elapsedSec + 2,
                    errorMessage = polled.message
                )
            } else {
                quickStreamState = latestSnap.copy(elapsedSec = latestSnap.elapsedSec + 2)
            }
        }

        val completedSnap = quickStreamState ?: return
        quickStreamState = completedSnap.copy(
            stage = QuickStreamStage.RESOLVING_VIDEO,
            progress = 100
        )
        refreshPikPakTasks()
        if (currentPikPakFolderId == pikpakCreds.targetFolderId) {
            loadPikPakDirectory(currentPikPakFolderId)
        }

        var videos = PikPakClient.resolveQuickStreamVideos(
            fileId = completedSnap.fileId,
            taskName = completedSnap.taskName,
            creds = pikpakCreds
        )
        if (videos.isEmpty()) {
            // Brief retry in case PikPak Drive index takes a moment after task completion
            delay(1500L)
            val (repolled, creds2) = PikPakClient.pollOfflineTask(
                taskId = completedSnap.taskId,
                taskNameHint = completedSnap.taskName,
                creds = pikpakCreds
            )
            creds2?.let { savePikPakCreds(it) }
            val resolvedFileId = repolled?.fileId?.ifBlank { completedSnap.fileId } ?: completedSnap.fileId
            videos = PikPakClient.resolveQuickStreamVideos(
                fileId = resolvedFileId,
                taskName = repolled?.name?.ifBlank { completedSnap.taskName } ?: completedSnap.taskName,
                creds = pikpakCreds
            )
        }

        val mainVideos = PikPakClient.filterSignificantVideos(videos)
        if (mainVideos.isEmpty()) {
            quickStreamState = quickStreamState?.copy(
                stage = QuickStreamStage.ERROR,
                errorMessage = str(R.string.qs_error_no_video)
            )
            return
        }

        if (mainVideos.size == 1) {
            val targetVideo = mainVideos.first()
            quickStreamState = quickStreamState?.copy(stage = QuickStreamStage.LAUNCHING_PLAYER)
            try {
                val streamUrl = try {
                    fetchPikPakStreamLinkSuspend(targetVideo)
                } catch (_: Exception) {
                    delay(1200L)
                    fetchPikPakStreamLinkSuspend(targetVideo)
                }
                quickStreamState = null
                _videoPlayFlow.tryEmit(VideoPlayEvent(streamUrl = streamUrl, videoTitle = targetVideo.name))
            } catch (e: Exception) {
                quickStreamState = quickStreamState?.copy(
                    stage = QuickStreamStage.ERROR,
                    errorMessage = e.message ?: "Failed to fetch stream URL"
                )
            }
        } else {
            quickStreamState = quickStreamState?.copy(
                stage = QuickStreamStage.SELECT_VIDEO,
                candidateVideos = mainVideos
            )
        }
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
            settings.setBrowserLastUrl(url)
        }
    }

    fun toggleBrowserAdShield(enabled: Boolean) {
        browserAdShieldEnabled = enabled
        settings.setBrowserAdShieldEnabled(enabled)
        emitToast(if (enabled) str(R.string.toast_adshield_on) else str(R.string.toast_adshield_off))
    }

    fun updateBrowserAntiPopupStrict(enabled: Boolean) {
        browserAntiPopupStrict = enabled
        settings.setBrowserAntiPopupStrict(enabled)
    }

    fun incrementBlockedAds(delta: Int = 1) {
        if (delta <= 0) return
        browserBlockedAdsCount += delta
        settings.setBrowserBlockedAdsTotal(browserBlockedAdsCount.toLong())
    }

    fun resetBlockedAdsCount() {
        browserBlockedAdsCount = 0
        settings.setBrowserBlockedAdsTotal(0L)
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
        val raw = settings.browserBookmarksJson
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
        settings.setBrowserBookmarksJson(arr.toString())
    }

    fun addBrowserBookmark(title: String, url: String) {
        val cleanUrl = url.trim()
        if (cleanUrl.isBlank()) return
        val cleanTitle = title.trim().ifBlank { cleanUrl }
        if (browserBookmarks.any { it.url.equals(cleanUrl, ignoreCase = true) }) {
            emitToast(str(R.string.toast_bookmark_exists))
            return
        }
        browserBookmarks.add(0, BrowserBookmark(cleanTitle, cleanUrl))
        saveBrowserBookmarks()
        emitToast(str(R.string.toast_bookmark_added, cleanTitle))
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
        if (rawCode.isBlank()) return
        val item = UserscriptManager.installOrUpdateScript(getApplication(), rawCode, sourceUrl)
        reloadInstalledUserscripts()
        userscriptRevision++
        emitToast(str(R.string.toast_script_installed, item.name, item.version))
    }

    fun installUserscriptFromUrl(url: String, onDone: (() -> Unit)? = null) {
        val cleanUrl = url.trim()
        if (!cleanUrl.startsWith("http://", ignoreCase = true) && !cleanUrl.startsWith("https://", ignoreCase = true)) {
            emitToast("https://...")
            return
        }
        viewModelScope.launch {
            emitToast(str(R.string.toast_checking_script))
            try {
                val code = HttpClient.getString(cleanUrl, timeoutSec = 25L)
                if (code.isBlank()) throw Exception("Empty script")
                installOrUpdateUserscript(code, cleanUrl)
                onDone?.invoke()
            } catch (e: Exception) {
                emitToast(str(R.string.toast_download_failed, e.message ?: "Error"))
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
        emitToast(str(R.string.toast_script_deleted))
    }

    /* =====================================================================
     * PHASE 4: INTERNAL DOWNLOAD MANAGER ACTIONS
     * ===================================================================== */
    fun startBrowserFileDownload(
        url: String,
        userAgent: String?,
        contentDisposition: String?,
        mimeType: String?,
        contentLength: Long,
        referer: String = browserCurrentUrl
    ) {
        val cleanUrl = url.trim()
        if (cleanUrl.isBlank()) return
        val rec = downloadManager.enqueueBrowserDownload(
            url = cleanUrl,
            userAgent = userAgent,
            contentDisposition = contentDisposition,
            mimeType = mimeType,
            contentLength = contentLength,
            referer = referer,
            onComplete = { done ->
                emitToast(str(R.string.toast_dl_completed, done.fileName))
            },
            onError = { failed, err ->
                emitToast(str(R.string.toast_dl_failed, failed.fileName, err))
            }
        )
        emitToast(str(R.string.toast_dl_started, rec.fileName))
    }

    fun downloadSubtitleViaManager(
        fileName: String,
        subtitleUrl: String,
        preloadedContentUtf8: String? = null
    ) {
        val rec = downloadManager.enqueueSubtitleDownload(
            fileName = fileName,
            subtitleUrl = subtitleUrl,
            targetLangCode = subtitleTargetLang.code,
            preloadedContentUtf8 = preloadedContentUtf8,
            onComplete = { done, utf8Text ->
                emitToast(str(R.string.toast_dl_completed, done.fileName))
                if (autoPairSubtitleVideo && autoPairFolderUri.isNotBlank()) {
                    viewModelScope.launch {
                        try {
                            val appCtx = getApplication<Application>()
                            val targetVid = autoPairVideoName.takeIf { it.isNotBlank() } ?: run {
                                val vids = withContext(Dispatchers.IO) {
                                    SafSubtitleHelper.listVideosInTree(appCtx, autoPairFolderUri)
                                }
                                SafSubtitleHelper.findBestMatchingVideo(vids, done.fileName)?.name
                            }
                            if (!targetVid.isNullOrBlank()) {
                                val savedPaired = saveSubtitlePairedToSaf(
                                    videoFileName = targetVid,
                                    subtitleNameOrExt = done.fileName,
                                    contentUtf8 = utf8Text,
                                    pinVideoSelection = false
                                )
                                emitToast(str(R.string.toast_autopair_saved, savedPaired))
                            }
                        } catch (_: Exception) {}
                    }
                }
            },
            onError = { err ->
                emitToast(str(R.string.toast_dl_failed, fileName, err))
            }
        )
        emitToast(str(R.string.toast_dl_started, rec.fileName))
    }

    fun cancelDownloadTask(id: String) {
        downloadManager.cancelDownload(id)
    }

    fun retryDownloadTask(id: String) {
        downloadManager.retryDownload(
            id = id,
            onComplete = { done -> emitToast(str(R.string.toast_dl_completed, done.fileName)) },
            onError = { err -> emitToast(str(R.string.toast_download_failed, err)) }
        )
    }

    fun deleteDownloadTask(id: String) {
        val removed = downloadManager.deleteDownload(id, deleteFromDisk = true)
        if (removed != null) {
            emitToast(str(R.string.toast_dl_deleted, removed.fileName))
        }
    }

    fun clearFinishedDownloads() {
        downloadManager.clearFinishedDownloads()
        emitToast(str(R.string.toast_dl_cleared))
    }

    fun openDownloadRecord(context: Context, record: DownloadRecord) {
        downloadManager.openDownloadedFile(
            context = context,
            record = record,
            chooserTitle = str(R.string.chooser_open_file),
            onMissingFile = { emitToast(str(R.string.toast_dl_file_missing)) },
            onNoAppFound = { emitToast(str(R.string.toast_dl_no_app)) }
        )
    }

    fun sendDownloadedSubtitleToGemini(record: DownloadRecord) {
        viewModelScope.launch {
            try {
                val content = withContext(Dispatchers.IO) {
                    val f = File(record.localPath)
                    if (!f.exists()) throw IllegalStateException("File missing")
                    f.readText(Charsets.UTF_8)
                }
                if (content.isBlank()) throw IllegalStateException("Empty file")
                addGeminiJob(record.fileName, content)
                showDownloadManagerDialog = false
                currentTab = 2
                emitToast(str(R.string.toast_sent_to_gemini, record.fileName))
            } catch (e: Exception) {
                emitToast(str(R.string.toast_read_file_failed, e.message ?: "Error"))
            }
        }
    }

    /* =====================================================================
     * PHASE 4: CUSTOM TRACKER LIST & MAGNET BUILDER ACTIONS
     * ===================================================================== */
    private fun syncFormattersTrackerConfig() {
        Formatters.dynamicAutoAppendTrackers = autoAppendTrackers
        Formatters.dynamicTrackersList = activeTrackersList
    }

    fun prepareMagnet(rawMagnet: String): String =
        TrackerManager.applyTrackersToMagnet(
            rawLink = rawMagnet,
            enabled = autoAppendTrackers,
            trackers = activeTrackersList
        )

    fun buildCustomMagnet(hashOrMagnet: String, displayName: String = ""): String =
        TrackerManager.buildMagnetLink(
            hashOrMagnetInput = hashOrMagnet,
            displayName = displayName,
            appendTrackers = autoAppendTrackers,
            trackers = activeTrackersList
        )

    fun updateAutoAppendTrackers(enabled: Boolean) {
        autoAppendTrackers = enabled
        settings.setAutoAppendTrackers(enabled)
        syncFormattersTrackerConfig()
    }

    fun updateTrackerSourceUrl(url: String) {
        trackerSourceUrl = url
        settings.setTrackerSourceUrl(url)
    }

    fun updateCustomTrackers(raw: String) {
        customTrackersRaw = raw
        settings.setCustomTrackersRaw(raw)
        syncFormattersTrackerConfig()
    }

    fun resetTrackersToDefault() {
        trackerSourceUrl = TrackerManager.DEFAULT_TRACKER_URL
        fetchedTrackersRaw = ""
        customTrackersRaw = ""
        trackerLastUpdatedMs = 0L
        settings.setTrackerSourceUrl(TrackerManager.DEFAULT_TRACKER_URL)
        settings.saveFetchedTrackers("", 0L)
        settings.setCustomTrackersRaw("")
        syncFormattersTrackerConfig()
        emitToast(str(R.string.toast_trackers_reset))
    }

    fun refreshTrackersFromUrl() {
        if (isUpdatingTrackers) return
        val targetUrl = trackerSourceUrl.trim().ifBlank { TrackerManager.DEFAULT_TRACKER_URL }
        settings.setTrackerSourceUrl(targetUrl)
        viewModelScope.launch {
            isUpdatingTrackers = true
            try {
                val fetchedList = TrackerManager.fetchTrackersFromUrl(targetUrl)
                val joined = fetchedList.joinToString("\n")
                val now = System.currentTimeMillis()
                fetchedTrackersRaw = joined
                trackerLastUpdatedMs = now
                settings.saveFetchedTrackers(joined, now)
                syncFormattersTrackerConfig()
                emitToast(str(R.string.toast_trackers_updated, fetchedList.size))
            } catch (e: Exception) {
                emitToast(str(R.string.toast_trackers_update_failed, e.message ?: "Error"))
            } finally {
                isUpdatingTrackers = false
            }
        }
    }

    /* =====================================================================
     * PHASE 4: RSS FEED MONITOR & KEYWORD AUTO-MATCH ACTIONS
     * ===================================================================== */
    private fun loadRssConfiguration() {
        rssFeeds.clear()
        val rawFeeds = settings.rssFeedsJson
        var loadedAnyFeed = false
        if (rawFeeds != null) {
            try {
                val arr = JSONArray(rawFeeds)
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val u = o.optString("url").trim()
                    if (u.isNotBlank()) {
                        rssFeeds.add(
                            RssFeedSource(
                                id = o.optString("id").ifBlank { UUID.randomUUID().toString() },
                                title = o.optString("title").ifBlank { u },
                                url = u,
                                enabled = o.optBoolean("enabled", true),
                                lastSyncMs = o.optLong("lastSyncMs", 0L),
                                lastItemCount = o.optInt("lastItemCount", 0),
                                lastError = o.optString("lastError", "")
                            )
                        )
                    }
                }
                loadedAnyFeed = true
            } catch (_: Exception) {}
        }
        if (!loadedAnyFeed) {
            rssFeeds.addAll(RssRepository.defaultFeeds())
        }

        rssWatchKeywords.clear()
        val rawKws = settings.rssWatchKeywordsJson
        if (rawKws != null) {
            try {
                val arr = JSONArray(rawKws)
                for (i in 0 until arr.length()) {
                    val kw = arr.optString(i).trim()
                    if (kw.isNotBlank() && rssWatchKeywords.none { it.equals(kw, ignoreCase = true) }) {
                        rssWatchKeywords.add(kw)
                    }
                }
            } catch (_: Exception) {}
        } else {
            rssWatchKeywords.addAll(listOf("1080p", "2160p"))
        }
    }

    private fun persistRssFeeds() {
        val arr = JSONArray()
        rssFeeds.forEach { f ->
            arr.put(
                JSONObject()
                    .put("id", f.id)
                    .put("title", f.title)
                    .put("url", f.url)
                    .put("enabled", f.enabled)
                    .put("lastSyncMs", f.lastSyncMs)
                    .put("lastItemCount", f.lastItemCount)
                    .put("lastError", f.lastError)
            )
        }
        settings.setRssFeedsJson(arr.toString())
    }

    private fun persistRssKeywords() {
        val arr = JSONArray()
        rssWatchKeywords.forEach { arr.put(it) }
        settings.setRssWatchKeywordsJson(arr.toString())
    }

    fun addRssFeed(title: String, rawUrl: String) {
        val cleanUrl = rawUrl.trim()
        if (!cleanUrl.startsWith("http://", ignoreCase = true) &&
            !cleanUrl.startsWith("https://", ignoreCase = true)
        ) {
            emitToast(str(R.string.toast_rss_invalid_url))
            return
        }
        if (rssFeeds.any { it.url.equals(cleanUrl, ignoreCase = true) }) {
            emitToast(str(R.string.toast_rss_feed_exists))
            return
        }
        val cleanTitle = title.trim().ifBlank {
            runCatching { Uri.parse(cleanUrl).host.orEmpty().removePrefix("www.") }
                .getOrDefault("")
                .ifBlank { cleanUrl }
        }
        val feed = RssFeedSource(
            id = UUID.randomUUID().toString(),
            title = cleanTitle,
            url = cleanUrl,
            enabled = true
        )
        rssFeeds.add(0, feed)
        persistRssFeeds()
        emitToast(str(R.string.toast_rss_feed_added, cleanTitle))
        refreshRssFeeds(silent = true)
    }

    fun toggleRssFeed(feedId: String, enabled: Boolean) {
        val idx = rssFeeds.indexOfFirst { it.id == feedId }
        if (idx >= 0) {
            rssFeeds[idx] = rssFeeds[idx].copy(enabled = enabled)
            persistRssFeeds()
        }
    }

    fun removeRssFeed(feedId: String) {
        rssFeeds.removeAll { it.id == feedId }
        rssItems.removeAll { it.feedId == feedId }
        if (rssSelectedFeedId == feedId) rssSelectedFeedId = "all"
        persistRssFeeds()
    }

    private fun recomputeRssKeywordMatches() {
        val kws = rssWatchKeywords.toList()
        for (i in rssItems.indices) {
            val cur = rssItems[i]
            val matched = RssRepository.matchWatchKeywords(cur.title, cur.videoCode, kws)
            if (matched != cur.matchedKeywords) {
                rssItems[i] = cur.copy(matchedKeywords = matched)
            }
        }
    }

    fun addRssWatchKeyword(rawKeyword: String) {
        val parts = rawKeyword.split(',', '\n').map { it.trim() }.filter { it.isNotEmpty() }
        if (parts.isEmpty()) return
        var addedAny = ""
        for (kw in parts) {
            if (rssWatchKeywords.none { it.equals(kw, ignoreCase = true) }) {
                rssWatchKeywords.add(kw)
                if (addedAny.isEmpty()) addedAny = kw
            }
        }
        if (addedAny.isNotEmpty()) {
            persistRssKeywords()
            recomputeRssKeywordMatches()
            emitToast(str(R.string.toast_rss_keyword_added, addedAny))
        }
    }

    fun removeRssWatchKeyword(keyword: String) {
        rssWatchKeywords.removeAll { it.equals(keyword, ignoreCase = true) }
        persistRssKeywords()
        recomputeRssKeywordMatches()
    }

    fun refreshRssFeeds(silent: Boolean = false) {
        val activeFeeds = rssFeeds.filter { it.enabled }
        if (activeFeeds.isEmpty()) {
            if (!silent) emitToast(str(R.string.rss_empty_no_feeds))
            return
        }
        rssRefreshJob?.cancel()
        val keywordsSnap = rssWatchKeywords.toList()
        rssRefreshJob = viewModelScope.launch {
            isLoadingRss = true
            try {
                val results = activeFeeds.map { feed ->
                    async {
                        try {
                            val items = RssRepository.fetchFeedItems(feed, keywordsSnap)
                            Triple(feed.id, items, "")
                        } catch (e: Exception) {
                            Triple(feed.id, emptyList<RssFeedItem>(), e.message ?: "Error")
                        }
                    }
                }.awaitAll()

                val now = System.currentTimeMillis()
                val combined = mutableListOf<RssFeedItem>()
                val seenKeys = HashSet<String>()

                for ((feedId, items, err) in results) {
                    val fIdx = rssFeeds.indexOfFirst { it.id == feedId }
                    if (fIdx >= 0) {
                        rssFeeds[fIdx] = rssFeeds[fIdx].copy(
                            lastSyncMs = now,
                            lastItemCount = items.size,
                            lastError = err
                        )
                    }
                    for (item in items) {
                        val dedupKey = item.infoHash.ifBlank { item.magnet.ifBlank { item.id } }
                        if (seenKeys.add(dedupKey)) {
                            combined.add(item)
                        }
                    }
                }
                persistRssFeeds()

                combined.sortWith(
                    compareByDescending<RssFeedItem> { it.isMatched }
                        .thenByDescending { it.pubDateMs }
                        .thenByDescending { it.seeders ?: -1 }
                )

                rssItems.clear()
                rssItems.addAll(combined)

                if (!silent) {
                    val matchCount = combined.count { it.isMatched }
                    emitToast(str(R.string.toast_rss_refreshed, combined.size, matchCount))
                }
            } finally {
                isLoadingRss = false
            }
        }
    }

    fun filteredRssItems(): List<RssFeedItem> {
        val qTokens = rssSearchFilter.trim().lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
        return rssItems.filter { item ->
            if (rssOnlyMatched && !item.isMatched) return@filter false
            if (rssSelectedFeedId != "all" && item.feedId != rssSelectedFeedId) return@filter false
            if (qTokens.isNotEmpty()) {
                val hay = "${item.title} ${item.videoCode} ${item.feedTitle}".lowercase()
                if (!qTokens.all { hay.contains(it) }) return@filter false
            }
            true
        }
    }
}
