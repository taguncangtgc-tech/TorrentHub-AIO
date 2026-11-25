package com.torrenthub.aio.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.SharedPreferencesMigration
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.torrenthub.aio.model.PikPakCredentials
import com.torrenthub.aio.model.SubtitleProvider
import com.torrenthub.aio.model.TranslationProvider
import com.torrenthub.aio.network.DohProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.UUID

enum class AppLanguage(val code: String, val nativeLabel: String, val flag: String) {
    ID("id", "Indonesia", "🇮🇩"),
    EN("en", "English", "🇬🇧");

    companion object {
        fun fromCode(code: String?): AppLanguage =
            entries.firstOrNull { it.code.equals(code, ignoreCase = true) } ?: ID
    }
}

enum class SubtitleTargetLanguage(
    val code: String,
    val nativeLabel: String,
    val flag: String,
    val englishPromptName: String,
    val googleGtxCode: String,
    val deeplCode: String,
    val openSubIso3: String
) {
    ID("id", "Indonesia", "🇮🇩", "Indonesian (Bahasa Indonesia)", "id", "ID", "ind"),
    EN("en", "English", "🇬🇧", "English", "en", "EN", "eng"),
    ZH("zh", "中文 (Mandarin)", "🇨🇳", "Simplified Chinese (简体中文)", "zh-CN", "ZH", "chi"),
    ZH_TW("zh-TW", "繁體中文 (Tradisional)", "🇹🇼", "Traditional Chinese (繁體中文)", "zh-TW", "ZH-HANT", "zht"),
    JA("ja", "日本語 (Jepang)", "🇯🇵", "Japanese (日本語)", "ja", "JA", "jpn"),
    KO("ko", "한국어 (Korea)", "🇰🇷", "Korean (한국어)", "ko", "KO", "kor"),
    MS("ms", "Bahasa Melayu", "🇲🇾", "Malay (Bahasa Melayu)", "ms", "ID", "may"),
    AR("ar", "العربية (Arab)", "🇸🇦", "Arabic (العربية)", "ar", "AR", "ara"),
    ES("es", "Español (Spanyol)", "🇪🇸", "Spanish (Español)", "es", "ES", "spa"),
    FR("fr", "Français (Prancis)", "🇫🇷", "French (Français)", "fr", "FR", "fre"),
    DE("de", "Deutsch (Jerman)", "🇩🇪", "German (Deutsch)", "de", "DE", "ger"),
    PT("pt", "Português (Portugis)", "🇧🇷", "Portuguese (Português)", "pt", "PT-BR", "por"),
    RU("ru", "Русский (Rusia)", "🇷🇺", "Russian (Русский)", "ru", "RU", "rus"),
    HI("hi", "हिन्दी (Hindi)", "🇮🇳", "Hindi (हिन्दी)", "hi", "EN", "hin"),
    TH("th", "ไทย (Thailand)", "🇹🇭", "Thai (ไทย)", "th", "TH", "tha"),
    VI("vi", "Tiếng Việt (Vietnam)", "🇻🇳", "Vietnamese (Tiếng Việt)", "vi", "VI", "vie"),
    TR("tr", "Türkçe (Turki)", "🇹🇷", "Turkish (Türkçe)", "tr", "TR", "tur");

    companion object {
        fun fromCode(code: String?): SubtitleTargetLanguage =
            entries.firstOrNull { it.code.equals(code, ignoreCase = true) } ?: ID
    }
}

enum class AppThemeMode(val id: String) {
    LIGHT("light"),
    DARK("dark"),
    AMOLED("amoled");

    companion object {
        fun fromId(id: String?): AppThemeMode =
            entries.firstOrNull { it.id.equals(id, ignoreCase = true) } ?: DARK
    }
}

private const val LEGACY_PREFS_NAME = "torrenthub_aio_prefs"
private const val DATASTORE_NAME = "torrenthub_aio_datastore"

private val Context.appDataStore: DataStore<Preferences> by preferencesDataStore(
    name = DATASTORE_NAME,
    produceMigrations = { ctx ->
        listOf(SharedPreferencesMigration(ctx, LEGACY_PREFS_NAME))
    }
)

class SettingsRepository private constructor(context: Context) {

    private val appContext = context.applicationContext
    private val dataStore: DataStore<Preferences> = appContext.appDataStore
    private val repoScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val initialSnapshot: Preferences = runBlocking(Dispatchers.IO) {
        try {
            dataStore.data.first()
        } catch (_: Exception) {
            emptyPreferences()
        }
    }

    val preferencesFlow: StateFlow<Preferences> = dataStore.data
        .catch { e ->
            if (e is IOException) emit(emptyPreferences()) else throw e
        }
        .stateIn(
            scope = repoScope,
            started = SharingStarted.Eagerly,
            initialValue = initialSnapshot
        )

    val current: Preferences
        get() = preferencesFlow.value

    init {
        com.torrenthub.aio.network.HttpClient.applyDohProvider(
            DohProvider.fromId(initialSnapshot[Keys.DOH_PROVIDER])
        )
    }

    /* ================= PHASE 1 CORE SETTINGS ================= */
    val appLanguage: AppLanguage
        get() = AppLanguage.fromCode(current[Keys.APP_LANGUAGE])

    val subtitleTargetLang: SubtitleTargetLanguage
        get() = SubtitleTargetLanguage.fromCode(current[Keys.SUB_TARGET_LANG])

    val themeMode: AppThemeMode
        get() = AppThemeMode.fromId(current[Keys.APP_THEME_MODE])

    val browserTabSwipeEnabled: Boolean
        get() = current[Keys.BROWSER_TAB_SWIPE] ?: true

    fun setAppLanguage(lang: AppLanguage) {
        repoScope.launch {
            dataStore.edit { it[Keys.APP_LANGUAGE] = lang.code }
        }
    }

    fun setSubtitleTargetLang(lang: SubtitleTargetLanguage) {
        repoScope.launch {
            dataStore.edit { it[Keys.SUB_TARGET_LANG] = lang.code }
        }
    }

    fun setThemeMode(mode: AppThemeMode) {
        repoScope.launch {
            dataStore.edit { it[Keys.APP_THEME_MODE] = mode.id }
        }
    }

    fun setBrowserTabSwipeEnabled(enabled: Boolean) {
        repoScope.launch {
            dataStore.edit { it[Keys.BROWSER_TAB_SWIPE] = enabled }
        }
    }

    /* ================= PHASE 3: SUBTITLE SOURCE, AUTO-PAIRING & CUSTOM TRANSLATOR ================= */
    val subtitleProvider: SubtitleProvider
        get() = SubtitleProvider.fromId(current[Keys.SUB_PROVIDER])

    val autoPairSubtitleVideo: Boolean
        get() = current[Keys.SUB_AUTO_PAIR] ?: false

    val autoPairFolderUri: String
        get() = current[Keys.SUB_PAIR_FOLDER_URI] ?: ""

    val autoPairFolderName: String
        get() = current[Keys.SUB_PAIR_FOLDER_NAME] ?: ""

    val autoPairVideoName: String
        get() = current[Keys.SUB_PAIR_VIDEO_NAME] ?: ""

    val translationProvider: TranslationProvider
        get() = TranslationProvider.fromId(
            current[Keys.TR_PROVIDER],
            fallbackToGemini = (current[Keys.GST_KEYS]?.isNotBlank() == true)
        )

    val googleTranslateEndpoint: String
        get() = current[Keys.TR_GOOGLE_ENDPOINT] ?: GeminiEngine.DEFAULT_GOOGLE_GTX_ENDPOINT

    val geminiEndpoint: String
        get() = current[Keys.TR_GEMINI_ENDPOINT] ?: GeminiEngine.DEFAULT_GEMINI_ENDPOINT

    val deeplApiKey: String
        get() = current[Keys.TR_DEEPL_KEY] ?: ""

    val deeplModel: String
        get() = current[Keys.TR_DEEPL_MODEL] ?: "prefer_quality_optimized"

    val deeplEndpoint: String
        get() = current[Keys.TR_DEEPL_ENDPOINT] ?: ""

    val openaiApiKey: String
        get() = current[Keys.TR_OPENAI_KEY] ?: ""

    val openaiModel: String
        get() = current[Keys.TR_OPENAI_MODEL] ?: "gpt-4o-mini"

    val openaiEndpoint: String
        get() = current[Keys.TR_OPENAI_ENDPOINT] ?: GeminiEngine.DEFAULT_OPENAI_ENDPOINT

    fun setSubtitleProvider(provider: SubtitleProvider) {
        repoScope.launch {
            dataStore.edit { it[Keys.SUB_PROVIDER] = provider.id }
        }
    }

    fun setAutoPairSubtitleVideo(enabled: Boolean) {
        repoScope.launch {
            dataStore.edit { it[Keys.SUB_AUTO_PAIR] = enabled }
        }
    }

    fun saveAutoPairTarget(folderUri: String, folderName: String, videoName: String) {
        repoScope.launch {
            dataStore.edit { prefs ->
                prefs[Keys.SUB_PAIR_FOLDER_URI] = folderUri
                prefs[Keys.SUB_PAIR_FOLDER_NAME] = folderName
                prefs[Keys.SUB_PAIR_VIDEO_NAME] = videoName
            }
        }
    }

    fun saveTranslationProviderConfig(
        provider: TranslationProvider,
        googleEndpoint: String,
        geminiEndpoint: String,
        deeplKey: String,
        deeplModel: String,
        deeplEndpoint: String,
        openaiKey: String,
        openaiModel: String,
        openaiEndpoint: String
    ) {
        repoScope.launch {
            dataStore.edit { prefs ->
                prefs[Keys.TR_PROVIDER] = provider.id
                prefs[Keys.TR_GOOGLE_ENDPOINT] = googleEndpoint.trim()
                prefs[Keys.TR_GEMINI_ENDPOINT] = geminiEndpoint.trim()
                prefs[Keys.TR_DEEPL_KEY] = deeplKey.trim()
                prefs[Keys.TR_DEEPL_MODEL] = deeplModel.trim()
                prefs[Keys.TR_DEEPL_ENDPOINT] = deeplEndpoint.trim()
                prefs[Keys.TR_OPENAI_KEY] = openaiKey.trim()
                prefs[Keys.TR_OPENAI_MODEL] = openaiModel.trim()
                prefs[Keys.TR_OPENAI_ENDPOINT] = openaiEndpoint.trim()
            }
        }
    }

    /* ================= TORRENT SETTINGS ================= */
    val torrentSortMode: String
        get() = current[Keys.UTA_SORT] ?: "seeders-desc"

    val hideDeadTorrents: Boolean
        get() = current[Keys.UTA_HIDE_DEAD] ?: true

    val enabledSourcesJson: String?
        get() = current[Keys.UTA_SOURCES]

    fun setTorrentSortMode(mode: String) {
        repoScope.launch {
            dataStore.edit { it[Keys.UTA_SORT] = mode }
        }
    }

    fun setHideDeadTorrents(hide: Boolean) {
        repoScope.launch {
            dataStore.edit { it[Keys.UTA_HIDE_DEAD] = hide }
        }
    }

    fun setEnabledSourcesJson(json: String) {
        repoScope.launch {
            dataStore.edit { it[Keys.UTA_SOURCES] = json }
        }
    }

    /* ================= GEMINI SUBTITLE SETTINGS ================= */
    val geminiKeysRaw: String
        get() = current[Keys.GST_KEYS] ?: ""

    val geminiModel: String
        get() = current[Keys.GST_MODEL] ?: GeminiEngine.DEFAULT_MODEL

    val geminiSavedModelsJson: String?
        get() = current[Keys.GST_MODELS_LIST]

    val geminiTaskMode: String
        get() = current[Keys.GST_TASK_MODE] ?: "translate"

    val geminiAutoDetect: Boolean
        get() = current[Keys.GST_AUTODETECT] ?: true

    val geminiNameMode: String
        get() = current[Keys.GST_NAME_MODE] ?: "code-upper"

    val geminiNotesTranslate: String
        get() = current[Keys.GST_NOTES_TR]
            ?: GeminiEngine.defaultNotesTranslate(subtitleTargetLang.code)

    val geminiNotesPolish: String
        get() = current[Keys.GST_NOTES_PL]
            ?: GeminiEngine.defaultNotesPolish(subtitleTargetLang.code)

    val geminiGlossary: String
        get() = current[Keys.GST_GLOSSARY] ?: ""

    val geminiRelaxSafety: Boolean
        get() = current[Keys.GST_SAFETY] ?: true

    val geminiIncludeAllLines: Boolean
        get() = current[Keys.GST_ALL_LINES] ?: false

    val geminiSlidingContext: Boolean
        get() = current[Keys.GST_SLIDING_CTX] ?: true

    val geminiCleanAds: Boolean
        get() = current[Keys.GST_CLEAN_ADS] ?: true

    val geminiBilingual: Boolean
        get() = current[Keys.GST_BILINGUAL] ?: false

    val geminiTimeShiftSec: String
        get() = current[Keys.GST_SHIFT_SEC] ?: "0"

    val geminiAutoUploadPikPak: Boolean
        get() = current[Keys.GST_AUTO_UP_PIKPAK] ?: false

    fun saveGeminiSettings(
        keysRaw: String,
        model: String,
        taskMode: String,
        autoDetect: Boolean,
        nameMode: String,
        notesTranslate: String,
        notesPolish: String,
        glossary: String,
        relaxSafety: Boolean,
        includeAllLines: Boolean,
        slidingContext: Boolean,
        cleanAds: Boolean,
        bilingual: Boolean,
        timeShiftSec: String,
        autoUploadPikPak: Boolean,
        targetLangCode: String
    ) {
        repoScope.launch {
            dataStore.edit { prefs ->
                prefs[Keys.GST_KEYS] = keysRaw.trim()
                prefs[Keys.GST_MODEL] = model
                prefs[Keys.GST_TASK_MODE] = taskMode
                prefs[Keys.GST_AUTODETECT] = autoDetect
                prefs[Keys.GST_NAME_MODE] = nameMode
                prefs[Keys.GST_NOTES_TR] = notesTranslate
                prefs[Keys.GST_NOTES_PL] = notesPolish
                prefs[Keys.GST_GLOSSARY] = glossary
                prefs[Keys.GST_SAFETY] = relaxSafety
                prefs[Keys.GST_ALL_LINES] = includeAllLines
                prefs[Keys.GST_SLIDING_CTX] = slidingContext
                prefs[Keys.GST_CLEAN_ADS] = cleanAds
                prefs[Keys.GST_BILINGUAL] = bilingual
                prefs[Keys.GST_SHIFT_SEC] = timeShiftSec
                prefs[Keys.GST_AUTO_UP_PIKPAK] = autoUploadPikPak
                prefs[Keys.SUB_TARGET_LANG] = targetLangCode
            }
        }
    }

    fun saveGeminiModelsList(models: List<String>) {
        val arr = JSONArray().apply { models.forEach { put(it) } }
        repoScope.launch {
            dataStore.edit { it[Keys.GST_MODELS_LIST] = arr.toString() }
        }
    }

    private fun progressKey(fileName: String, totalSlots: Int, mode: String, targetLang: String) =
        stringPreferencesKey("gst_prog_${mode}_${targetLang}_${fileName}_$totalSlots")

    private fun legacyProgressKey(fileName: String, totalSlots: Int, mode: String) =
        stringPreferencesKey("gst_prog_${mode}_${fileName}_$totalSlots")

    fun loadJobProgress(fileName: String, totalSlots: Int, mode: String, targetLang: String): MutableList<String>? {
        val snap = current
        val raw = snap[progressKey(fileName, totalSlots, mode, targetLang)]
            ?: (if (targetLang == "id") snap[legacyProgressKey(fileName, totalSlots, mode)] else null)
            ?: return null
        return try {
            val arr = JSONArray(raw)
            if (arr.length() != totalSlots) null
            else MutableList(arr.length()) { arr.optString(it, "") }
        } catch (_: Exception) {
            null
        }
    }

    fun saveJobProgress(fileName: String, totalSlots: Int, mode: String, targetLang: String, list: List<String>) {
        val serialized = JSONArray().apply { list.forEach { put(it) } }.toString()
        val key = progressKey(fileName, totalSlots, mode, targetLang)
        repoScope.launch {
            dataStore.edit { it[key] = serialized }
        }
    }

    fun clearJobProgress(fileName: String, totalSlots: Int, mode: String, targetLang: String) {
        val key = progressKey(fileName, totalSlots, mode, targetLang)
        val legacyKey = legacyProgressKey(fileName, totalSlots, mode)
        repoScope.launch {
            dataStore.edit {
                it.remove(key)
                if (targetLang == "id") it.remove(legacyKey)
            }
        }
    }

    /* ================= PIKPAK SETTINGS ================= */
    fun loadPikPakCreds(): PikPakCredentials {
        val snap = current
        return PikPakCredentials(
            accessToken = snap[Keys.PP_ACCESS] ?: "",
            refreshToken = snap[Keys.PP_REFRESH] ?: "",
            clientId = snap[Keys.PP_CLIENT_ID] ?: "",
            deviceId = snap[Keys.PP_DEVICE_ID] ?: "",
            captchaToken = snap[Keys.PP_CAPTCHA] ?: "",
            captchaExpiresAtMs = snap[Keys.PP_CAPTCHA_EXP] ?: 0L,
            userEmail = snap[Keys.PP_EMAIL] ?: "",
            targetFolderId = snap[Keys.PP_FOLDER_ID] ?: "",
            targetFolderName = snap[Keys.PP_FOLDER_NAME] ?: "Cloud Download (Default)"
        )
    }

    fun savePikPakCreds(creds: PikPakCredentials) {
        repoScope.launch {
            dataStore.edit { prefs ->
                prefs[Keys.PP_ACCESS] = creds.accessToken
                prefs[Keys.PP_REFRESH] = creds.refreshToken
                prefs[Keys.PP_CLIENT_ID] = creds.clientId
                prefs[Keys.PP_DEVICE_ID] = creds.deviceId
                prefs[Keys.PP_CAPTCHA] = creds.captchaToken
                prefs[Keys.PP_CAPTCHA_EXP] = creds.captchaExpiresAtMs
                prefs[Keys.PP_EMAIL] = creds.userEmail
                prefs[Keys.PP_FOLDER_ID] = creds.targetFolderId
                prefs[Keys.PP_FOLDER_NAME] = creds.targetFolderName
            }
        }
    }

    /* ================= BROWSER SETTINGS ================= */
    val browserLastUrl: String
        get() = current[Keys.BROWSER_LAST_URL] ?: "https://www.google.com"

    val browserAdShieldEnabled: Boolean
        get() = current[Keys.BROWSER_ADSHIELD] ?: true

    val browserAntiPopupStrict: Boolean
        get() = current[Keys.BROWSER_ANTIPOPUP] ?: true

    val browserBlockedAdsTotal: Long
        get() = current[Keys.BROWSER_BLOCKED_ADS_TOTAL] ?: 0L

    val browserBookmarksJson: String?
        get() = current[Keys.BROWSER_BOOKMARKS_V1]

    fun setBrowserLastUrl(url: String) {
        repoScope.launch {
            dataStore.edit { it[Keys.BROWSER_LAST_URL] = url }
        }
    }

    fun setBrowserAdShieldEnabled(enabled: Boolean) {
        repoScope.launch {
            dataStore.edit { it[Keys.BROWSER_ADSHIELD] = enabled }
        }
    }

    fun setBrowserAntiPopupStrict(enabled: Boolean) {
        repoScope.launch {
            dataStore.edit { it[Keys.BROWSER_ANTIPOPUP] = enabled }
        }
    }

    fun setBrowserBlockedAdsTotal(count: Long) {
        repoScope.launch {
            dataStore.edit { it[Keys.BROWSER_BLOCKED_ADS_TOTAL] = count.coerceAtLeast(0L) }
        }
    }

    fun setBrowserBookmarksJson(json: String) {
        repoScope.launch {
            dataStore.edit { it[Keys.BROWSER_BOOKMARKS_V1] = json }
        }
    }

    /* ================= PHASE 4: DOWNLOAD MANAGER, TRACKERS & RSS MONITOR ================= */
    val downloadHistoryJson: String?
        get() = current[Keys.DOWNLOAD_HISTORY_V1]

    fun setDownloadHistoryJson(json: String) {
        repoScope.launch {
            dataStore.edit { it[Keys.DOWNLOAD_HISTORY_V1] = json }
        }
    }

    val autoAppendTrackers: Boolean
        get() = current[Keys.TRACKER_AUTO_APPEND] ?: true

    val trackerSourceUrl: String
        get() = current[Keys.TRACKER_SOURCE_URL]
            ?: "https://raw.githubusercontent.com/ngosang/trackerslist/master/trackers_best.txt"

    val fetchedTrackersRaw: String
        get() = current[Keys.TRACKER_FETCHED_RAW] ?: ""

    val customTrackersRaw: String
        get() = current[Keys.TRACKER_CUSTOM_RAW] ?: ""

    val trackerLastUpdatedMs: Long
        get() = current[Keys.TRACKER_LAST_UPDATED_MS] ?: 0L

    fun setAutoAppendTrackers(enabled: Boolean) {
        repoScope.launch {
            dataStore.edit { it[Keys.TRACKER_AUTO_APPEND] = enabled }
        }
    }

    fun setTrackerSourceUrl(url: String) {
        repoScope.launch {
            dataStore.edit { it[Keys.TRACKER_SOURCE_URL] = url.trim() }
        }
    }

    fun saveFetchedTrackers(raw: String, updatedAtMs: Long) {
        repoScope.launch {
            dataStore.edit { prefs ->
                prefs[Keys.TRACKER_FETCHED_RAW] = raw.trim()
                prefs[Keys.TRACKER_LAST_UPDATED_MS] = updatedAtMs
            }
        }
    }

    fun setCustomTrackersRaw(raw: String) {
        repoScope.launch {
            dataStore.edit { it[Keys.TRACKER_CUSTOM_RAW] = raw }
        }
    }

    val rssFeedsJson: String?
        get() = current[Keys.RSS_FEEDS_V1]

    val rssWatchKeywordsJson: String?
        get() = current[Keys.RSS_KEYWORDS_V1]

    fun setRssFeedsJson(json: String) {
        repoScope.launch {
            dataStore.edit { it[Keys.RSS_FEEDS_V1] = json }
        }
    }

    fun setRssWatchKeywordsJson(json: String) {
        repoScope.launch {
            dataStore.edit { it[Keys.RSS_KEYWORDS_V1] = json }
        }
    }

    /* ================= PHASE 5A: DOH, APP LOCK, DISGUISE & PRIVACY ================= */
    val dohProvider: DohProvider
        get() = DohProvider.fromId(current[Keys.DOH_PROVIDER])

    val appLockEnabled: Boolean
        get() = (current[Keys.APP_LOCK_ENABLED] ?: false) && hasPinConfigured

    val appLockPinHash: String
        get() = current[Keys.APP_LOCK_PIN_HASH] ?: ""

    val appLockPinLength: Int
        get() = (current[Keys.APP_LOCK_PIN_LEN] ?: 4).coerceIn(4, 6)

    val hasPinConfigured: Boolean
        get() = appLockPinHash.isNotBlank()

    val appLockBiometricEnabled: Boolean
        get() = current[Keys.APP_LOCK_BIOMETRIC] ?: true

    val disguiseModeEnabled: Boolean
        get() = current[Keys.DISGUISE_MODE_ENABLED] ?: false

    val autoClearOnExit: Boolean
        get() = current[Keys.AUTO_CLEAR_ON_EXIT] ?: false

    fun setDohProvider(provider: DohProvider) {
        com.torrenthub.aio.network.HttpClient.applyDohProvider(provider)
        repoScope.launch {
            dataStore.edit { it[Keys.DOH_PROVIDER] = provider.id }
        }
    }

    fun saveAppLockPin(pinHash: String, pinLength: Int, enableLock: Boolean = true) {
        repoScope.launch {
            dataStore.edit { prefs ->
                prefs[Keys.APP_LOCK_PIN_HASH] = pinHash
                prefs[Keys.APP_LOCK_PIN_LEN] = pinLength.coerceIn(4, 6)
                prefs[Keys.APP_LOCK_ENABLED] = enableLock
            }
        }
    }

    fun setAppLockEnabled(enabled: Boolean) {
        repoScope.launch {
            dataStore.edit { it[Keys.APP_LOCK_ENABLED] = enabled }
        }
    }

    fun setAppLockBiometricEnabled(enabled: Boolean) {
        repoScope.launch {
            dataStore.edit { it[Keys.APP_LOCK_BIOMETRIC] = enabled }
        }
    }

    fun setDisguiseModeEnabled(enabled: Boolean) {
        repoScope.launch {
            dataStore.edit { it[Keys.DISGUISE_MODE_ENABLED] = enabled }
        }
    }

    fun setAutoClearOnExit(enabled: Boolean) {
        repoScope.launch {
            dataStore.edit { it[Keys.AUTO_CLEAR_ON_EXIT] = enabled }
        }
    }

    fun clearPersistedSearchAndBrowserHistory(blocking: Boolean = false) {
        if (blocking) {
            runBlocking(Dispatchers.IO) {
                runCatching {
                    dataStore.edit { prefs ->
                        prefs[Keys.BROWSER_LAST_URL] = "https://www.google.com"
                    }
                }
            }
        } else {
            repoScope.launch {
                dataStore.edit { prefs ->
                    prefs[Keys.BROWSER_LAST_URL] = "https://www.google.com"
                }
            }
        }
    }

    /* ================= PHASE 5B: JSON BACKUP/RESTORE & STORAGE HELPERS ================= */
    data class BackupImportResult(
        val updatedPrefs: Preferences,
        val restoredSensitiveKeys: Boolean,
        val rssFeedCount: Int,
        val customTrackerCount: Int
    )

    fun estimatedSavedJobProgressBytes(): Long {
        var total = 0L
        for ((key, value) in current.asMap()) {
            if (key.name.startsWith("gst_prog_") && value is String) {
                total += value.toByteArray(Charsets.UTF_8).size.toLong()
            }
        }
        return total
    }

    suspend fun clearAllSavedJobProgress() {
        dataStore.edit { prefs ->
            val progKeys = prefs.asMap().keys.filter { it.name.startsWith("gst_prog_") }
            for (k in progKeys) {
                prefs.remove(k)
            }
        }
    }

    fun estimatedPersistedHistoryBytes(): Long {
        var total = 0L
        val dlHist = current[Keys.DOWNLOAD_HISTORY_V1]
        if (!dlHist.isNullOrBlank() && dlHist != "[]") {
            total += dlHist.toByteArray(Charsets.UTF_8).size.toLong()
        }
        val lastUrl = current[Keys.BROWSER_LAST_URL]
        if (!lastUrl.isNullOrBlank() && lastUrl != "https://www.google.com") {
            total += lastUrl.toByteArray(Charsets.UTF_8).size.toLong()
        }
        return total
    }

    fun exportBackupJson(includeSensitiveKeys: Boolean): String {
        val snap = current
        val lang = AppLanguage.fromCode(snap[Keys.APP_LANGUAGE])
        val subTarget = SubtitleTargetLanguage.fromCode(snap[Keys.SUB_TARGET_LANG])
        val theme = AppThemeMode.fromId(snap[Keys.APP_THEME_MODE])
        val doh = DohProvider.fromId(snap[Keys.DOH_PROVIDER])

        val generalObj = JSONObject()
            .put("appLanguage", lang.code)
            .put("subtitleTargetLang", subTarget.code)
            .put("themeMode", theme.id)
            .put("dohProvider", doh.id)
            .put("disguiseModeEnabled", snap[Keys.DISGUISE_MODE_ENABLED] ?: false)
            .put("autoClearOnExit", snap[Keys.AUTO_CLEAR_ON_EXIT] ?: false)
            .put("appLockBiometricEnabled", snap[Keys.APP_LOCK_BIOMETRIC] ?: true)

        val bookmarksArr = runCatching {
            snap[Keys.BROWSER_BOOKMARKS_V1]?.let { JSONArray(it) }
        }.getOrNull() ?: JSONArray()

        val browserObj = JSONObject()
            .put("tabSwipeEnabled", snap[Keys.BROWSER_TAB_SWIPE] ?: true)
            .put("adShieldEnabled", snap[Keys.BROWSER_ADSHIELD] ?: true)
            .put("antiPopupStrict", snap[Keys.BROWSER_ANTIPOPUP] ?: true)
            .put("bookmarks", bookmarksArr)

        val customRaw = snap[Keys.TRACKER_CUSTOM_RAW] ?: ""
        val customArr = JSONArray().apply {
            com.torrenthub.aio.util.TrackerManager.parseTrackers(customRaw).forEach { put(it) }
        }
        val trackersObj = JSONObject()
            .put("autoAppendTrackers", snap[Keys.TRACKER_AUTO_APPEND] ?: true)
            .put(
                "trackerSourceUrl",
                snap[Keys.TRACKER_SOURCE_URL] ?: com.torrenthub.aio.util.TrackerManager.DEFAULT_TRACKER_URL
            )
            .put("customTrackers", customArr)
            .put("customTrackersRaw", customRaw)
            .put("fetchedTrackersRaw", snap[Keys.TRACKER_FETCHED_RAW] ?: "")
            .put("trackerLastUpdatedMs", snap[Keys.TRACKER_LAST_UPDATED_MS] ?: 0L)

        val enabledSourcesObj = runCatching {
            snap[Keys.UTA_SOURCES]?.let { JSONObject(it) }
        }.getOrNull() ?: JSONObject().apply {
            TorrentRepository.SOURCES.forEach { s -> put(s.id, s.defaultOn) }
        }
        val torrentObj = JSONObject()
            .put("sortMode", snap[Keys.UTA_SORT] ?: "seeders-desc")
            .put("hideDeadTorrents", snap[Keys.UTA_HIDE_DEAD] ?: true)
            .put("enabledSources", enabledSourcesObj)

        val feedsArr = runCatching {
            snap[Keys.RSS_FEEDS_V1]?.let { JSONArray(it) }
        }.getOrNull() ?: JSONArray().apply {
            RssRepository.defaultFeeds().forEach { f ->
                put(
                    JSONObject()
                        .put("id", f.id)
                        .put("title", f.title)
                        .put("url", f.url)
                        .put("enabled", f.enabled)
                )
            }
        }
        val keywordsArr = runCatching {
            snap[Keys.RSS_KEYWORDS_V1]?.let { JSONArray(it) }
        }.getOrNull() ?: JSONArray().apply {
            put("1080p")
            put("2160p")
        }
        val rssObj = JSONObject()
            .put("feeds", feedsArr)
            .put("watchKeywords", keywordsArr)

        val subtitleObj = JSONObject()
            .put("provider", SubtitleProvider.fromId(snap[Keys.SUB_PROVIDER]).id)
            .put("autoPairSubtitleVideo", snap[Keys.SUB_AUTO_PAIR] ?: false)

        val savedModelsArr = runCatching {
            snap[Keys.GST_MODELS_LIST]?.let { JSONArray(it) }
        }.getOrNull() ?: JSONArray()

        val activeTrProvider = TranslationProvider.fromId(
            snap[Keys.TR_PROVIDER],
            fallbackToGemini = (snap[Keys.GST_KEYS]?.isNotBlank() == true)
        )
        val trObj = JSONObject()
            .put("provider", activeTrProvider.id)
            .put("googleTranslateEndpoint", snap[Keys.TR_GOOGLE_ENDPOINT] ?: GeminiEngine.DEFAULT_GOOGLE_GTX_ENDPOINT)
            .put("geminiEndpoint", snap[Keys.TR_GEMINI_ENDPOINT] ?: GeminiEngine.DEFAULT_GEMINI_ENDPOINT)
            .put("geminiModel", snap[Keys.GST_MODEL] ?: GeminiEngine.DEFAULT_MODEL)
            .put("geminiSavedModels", savedModelsArr)
            .put("geminiTaskMode", snap[Keys.GST_TASK_MODE] ?: "translate")
            .put("geminiAutoDetect", snap[Keys.GST_AUTODETECT] ?: true)
            .put("geminiNameMode", snap[Keys.GST_NAME_MODE] ?: "code-upper")
            .put("geminiNotesTranslate", snap[Keys.GST_NOTES_TR] ?: GeminiEngine.defaultNotesTranslate(subTarget.code))
            .put("geminiNotesPolish", snap[Keys.GST_NOTES_PL] ?: GeminiEngine.defaultNotesPolish(subTarget.code))
            .put("geminiGlossary", snap[Keys.GST_GLOSSARY] ?: "")
            .put("geminiRelaxSafety", snap[Keys.GST_SAFETY] ?: true)
            .put("geminiIncludeAllLines", snap[Keys.GST_ALL_LINES] ?: false)
            .put("geminiSlidingContext", snap[Keys.GST_SLIDING_CTX] ?: true)
            .put("geminiCleanAds", snap[Keys.GST_CLEAN_ADS] ?: true)
            .put("geminiBilingual", snap[Keys.GST_BILINGUAL] ?: false)
            .put("geminiTimeShiftSec", snap[Keys.GST_SHIFT_SEC] ?: "0")
            .put("geminiAutoUploadPikPak", snap[Keys.GST_AUTO_UP_PIKPAK] ?: false)
            .put("deeplModel", snap[Keys.TR_DEEPL_MODEL] ?: "prefer_quality_optimized")
            .put("deeplEndpoint", snap[Keys.TR_DEEPL_ENDPOINT] ?: "")
            .put("openaiModel", snap[Keys.TR_OPENAI_MODEL] ?: "gpt-4o-mini")
            .put("openaiEndpoint", snap[Keys.TR_OPENAI_ENDPOINT] ?: GeminiEngine.DEFAULT_OPENAI_ENDPOINT)

        if (includeSensitiveKeys) {
            val apiKeysObj = JSONObject()
                .put("geminiKeysRaw", snap[Keys.GST_KEYS] ?: "")
                .put("deeplApiKey", snap[Keys.TR_DEEPL_KEY] ?: "")
                .put("openaiApiKey", snap[Keys.TR_OPENAI_KEY] ?: "")
            trObj.put("apiKeys", apiKeysObj)
        }

        val root = JSONObject()
            .put("backupApp", "TorrentHub-AIO")
            .put("schemaVersion", 1)
            .put("appVersion", "1.4.0")
            .put("exportedAtMs", System.currentTimeMillis())
            .put("includesSensitiveKeys", includeSensitiveKeys)
            .put("general", generalObj)
            .put("browser", browserObj)
            .put("trackers", trackersObj)
            .put("torrent", torrentObj)
            .put("rss", rssObj)
            .put("subtitle", subtitleObj)
            .put("translation", trObj)

        return root.toString(2)
    }

    suspend fun importBackupJson(rawJson: String): BackupImportResult {
        val cleaned = rawJson.trim().removePrefix("\uFEFF")
        if (cleaned.isEmpty()) {
            throw IllegalArgumentException("Empty JSON file")
        }
        val root = JSONObject(cleaned)
        val hasRecognizedSection = root.optString("backupApp") == "TorrentHub-AIO" ||
            root.has("general") ||
            root.has("trackers") ||
            root.has("rss") ||
            root.has("translation")
        if (!hasRecognizedSection) {
            throw IllegalArgumentException("Invalid TorrentHub backup schema")
        }

        var restoredKeys = false
        var importedRssCount = 0
        var importedCustomTrackerCount = 0

        val updatedPrefs = dataStore.edit { prefs ->
            root.optJSONObject("general")?.let { gen ->
                if (gen.has("appLanguage")) {
                    prefs[Keys.APP_LANGUAGE] = AppLanguage.fromCode(gen.optString("appLanguage")).code
                }
                if (gen.has("subtitleTargetLang")) {
                    prefs[Keys.SUB_TARGET_LANG] = SubtitleTargetLanguage.fromCode(gen.optString("subtitleTargetLang")).code
                }
                if (gen.has("themeMode")) {
                    prefs[Keys.APP_THEME_MODE] = AppThemeMode.fromId(gen.optString("themeMode")).id
                }
                if (gen.has("dohProvider")) {
                    val doh = DohProvider.fromId(gen.optString("dohProvider"))
                    prefs[Keys.DOH_PROVIDER] = doh.id
                    com.torrenthub.aio.network.HttpClient.applyDohProvider(doh)
                }
                if (gen.has("disguiseModeEnabled")) {
                    prefs[Keys.DISGUISE_MODE_ENABLED] = gen.optBoolean("disguiseModeEnabled", false)
                }
                if (gen.has("autoClearOnExit")) {
                    prefs[Keys.AUTO_CLEAR_ON_EXIT] = gen.optBoolean("autoClearOnExit", false)
                }
                if (gen.has("appLockBiometricEnabled")) {
                    prefs[Keys.APP_LOCK_BIOMETRIC] = gen.optBoolean("appLockBiometricEnabled", true)
                }
            }

            root.optJSONObject("browser")?.let { br ->
                if (br.has("tabSwipeEnabled")) {
                    prefs[Keys.BROWSER_TAB_SWIPE] = br.optBoolean("tabSwipeEnabled", true)
                }
                if (br.has("adShieldEnabled")) {
                    prefs[Keys.BROWSER_ADSHIELD] = br.optBoolean("adShieldEnabled", true)
                }
                if (br.has("antiPopupStrict")) {
                    prefs[Keys.BROWSER_ANTIPOPUP] = br.optBoolean("antiPopupStrict", true)
                }
                br.optJSONArray("bookmarks")?.let { bmArr ->
                    val sanitized = JSONArray()
                    for (i in 0 until bmArr.length()) {
                        val o = bmArr.optJSONObject(i) ?: continue
                        val u = o.optString("url").trim()
                        if (u.isNotBlank()) {
                            sanitized.put(
                                JSONObject()
                                    .put("title", o.optString("title").trim().ifBlank { u })
                                    .put("url", u)
                            )
                        }
                    }
                    if (sanitized.length() > 0) {
                        prefs[Keys.BROWSER_BOOKMARKS_V1] = sanitized.toString()
                    }
                }
            }

            root.optJSONObject("trackers")?.let { trk ->
                if (trk.has("autoAppendTrackers")) {
                    prefs[Keys.TRACKER_AUTO_APPEND] = trk.optBoolean("autoAppendTrackers", true)
                }
                if (trk.has("trackerSourceUrl")) {
                    val srcUrl = trk.optString("trackerSourceUrl").trim()
                        .ifBlank { com.torrenthub.aio.util.TrackerManager.DEFAULT_TRACKER_URL }
                    prefs[Keys.TRACKER_SOURCE_URL] = srcUrl
                }
                val resolvedCustomRaw = when {
                    trk.has("customTrackersRaw") -> trk.optString("customTrackersRaw")
                    trk.optJSONArray("customTrackers") != null -> {
                        val arr = trk.optJSONArray("customTrackers")!!
                        buildList {
                            for (i in 0 until arr.length()) {
                                val line = arr.optString(i).trim()
                                if (line.isNotEmpty()) add(line)
                            }
                        }.joinToString("\n")
                    }
                    else -> null
                }
                if (resolvedCustomRaw != null) {
                    prefs[Keys.TRACKER_CUSTOM_RAW] = resolvedCustomRaw
                    importedCustomTrackerCount = com.torrenthub.aio.util.TrackerManager.parseTrackers(resolvedCustomRaw).size
                }
                if (trk.has("fetchedTrackersRaw")) {
                    prefs[Keys.TRACKER_FETCHED_RAW] = trk.optString("fetchedTrackersRaw").trim()
                }
                if (trk.has("trackerLastUpdatedMs")) {
                    prefs[Keys.TRACKER_LAST_UPDATED_MS] = trk.optLong("trackerLastUpdatedMs", 0L).coerceAtLeast(0L)
                }
            }

            root.optJSONObject("torrent")?.let { tor ->
                if (tor.has("sortMode")) {
                    val sm = tor.optString("sortMode").trim()
                    if (sm.isNotBlank()) prefs[Keys.UTA_SORT] = sm
                }
                if (tor.has("hideDeadTorrents")) {
                    prefs[Keys.UTA_HIDE_DEAD] = tor.optBoolean("hideDeadTorrents", true)
                }
                tor.optJSONObject("enabledSources")?.let { srcObj ->
                    prefs[Keys.UTA_SOURCES] = srcObj.toString()
                }
            }

            root.optJSONObject("rss")?.let { rss ->
                rss.optJSONArray("feeds")?.let { fArr ->
                    val normalizedFeeds = JSONArray()
                    val seenUrls = HashSet<String>()
                    for (i in 0 until fArr.length()) {
                        val o = fArr.optJSONObject(i) ?: continue
                        val url = o.optString("url").trim()
                        if ((url.startsWith("http://", true) || url.startsWith("https://", true)) &&
                            seenUrls.add(url.lowercase())
                        ) {
                            normalizedFeeds.put(
                                JSONObject()
                                    .put("id", o.optString("id").trim().ifBlank { UUID.randomUUID().toString() })
                                    .put("title", o.optString("title").trim().ifBlank { url })
                                    .put("url", url)
                                    .put("enabled", o.optBoolean("enabled", true))
                                    .put("lastSyncMs", 0L)
                                    .put("lastItemCount", 0)
                                    .put("lastError", "")
                            )
                        }
                    }
                    importedRssCount = normalizedFeeds.length()
                    prefs[Keys.RSS_FEEDS_V1] = normalizedFeeds.toString()
                }
                rss.optJSONArray("watchKeywords")?.let { kwArr ->
                    val normalizedKws = JSONArray()
                    val seenKw = HashSet<String>()
                    for (i in 0 until kwArr.length()) {
                        val kw = kwArr.optString(i).trim()
                        if (kw.isNotEmpty() && seenKw.add(kw.lowercase())) {
                            normalizedKws.put(kw)
                        }
                    }
                    prefs[Keys.RSS_KEYWORDS_V1] = normalizedKws.toString()
                }
            }

            root.optJSONObject("subtitle")?.let { sub ->
                if (sub.has("provider")) {
                    prefs[Keys.SUB_PROVIDER] = SubtitleProvider.fromId(sub.optString("provider")).id
                }
                if (sub.has("autoPairSubtitleVideo")) {
                    val wantPair = sub.optBoolean("autoPairSubtitleVideo", false)
                    val hasFolderPerm = !prefs[Keys.SUB_PAIR_FOLDER_URI].isNullOrBlank()
                    prefs[Keys.SUB_AUTO_PAIR] = wantPair && hasFolderPerm
                }
            }

            root.optJSONObject("translation")?.let { tr ->
                val includesKeysFlag = root.optBoolean("includesSensitiveKeys", false)
                val apiKeysObj = tr.optJSONObject("apiKeys")
                if (includesKeysFlag || apiKeysObj != null) {
                    val srcKeys = apiKeysObj ?: tr
                    if (srcKeys.has("geminiKeysRaw")) {
                        prefs[Keys.GST_KEYS] = srcKeys.optString("geminiKeysRaw").trim()
                        restoredKeys = true
                    }
                    if (srcKeys.has("deeplApiKey")) {
                        prefs[Keys.TR_DEEPL_KEY] = srcKeys.optString("deeplApiKey").trim()
                        restoredKeys = true
                    }
                    if (srcKeys.has("openaiApiKey")) {
                        prefs[Keys.TR_OPENAI_KEY] = srcKeys.optString("openaiApiKey").trim()
                        restoredKeys = true
                    }
                }

                if (tr.has("provider")) {
                    val prov = TranslationProvider.fromId(
                        tr.optString("provider"),
                        fallbackToGemini = (prefs[Keys.GST_KEYS]?.isNotBlank() == true)
                    )
                    prefs[Keys.TR_PROVIDER] = prov.id
                }
                if (tr.has("googleTranslateEndpoint")) {
                    prefs[Keys.TR_GOOGLE_ENDPOINT] = tr.optString("googleTranslateEndpoint").trim()
                        .ifBlank { GeminiEngine.DEFAULT_GOOGLE_GTX_ENDPOINT }
                }
                if (tr.has("geminiEndpoint")) {
                    prefs[Keys.TR_GEMINI_ENDPOINT] = tr.optString("geminiEndpoint").trim()
                        .ifBlank { GeminiEngine.DEFAULT_GEMINI_ENDPOINT }
                }
                if (tr.has("geminiModel")) {
                    val gm = tr.optString("geminiModel").trim()
                    if (gm.isNotBlank()) prefs[Keys.GST_MODEL] = gm
                }
                tr.optJSONArray("geminiSavedModels")?.let { mArr ->
                    val cleanModels = JSONArray()
                    for (i in 0 until mArr.length()) {
                        val m = mArr.optString(i).trim()
                        if (m.isNotEmpty()) cleanModels.put(m)
                    }
                    prefs[Keys.GST_MODELS_LIST] = cleanModels.toString()
                }
                if (tr.has("geminiTaskMode")) {
                    val tm = tr.optString("geminiTaskMode").trim()
                    if (tm == "translate" || tm == "polish") prefs[Keys.GST_TASK_MODE] = tm
                }
                if (tr.has("geminiAutoDetect")) {
                    prefs[Keys.GST_AUTODETECT] = tr.optBoolean("geminiAutoDetect", true)
                }
                if (tr.has("geminiNameMode")) {
                    val nm = tr.optString("geminiNameMode").trim()
                    if (nm.isNotBlank()) prefs[Keys.GST_NAME_MODE] = nm
                }
                if (tr.has("geminiNotesTranslate")) {
                    prefs[Keys.GST_NOTES_TR] = tr.optString("geminiNotesTranslate")
                }
                if (tr.has("geminiNotesPolish")) {
                    prefs[Keys.GST_NOTES_PL] = tr.optString("geminiNotesPolish")
                }
                if (tr.has("geminiGlossary")) {
                    prefs[Keys.GST_GLOSSARY] = tr.optString("geminiGlossary")
                }
                if (tr.has("geminiRelaxSafety")) {
                    prefs[Keys.GST_SAFETY] = tr.optBoolean("geminiRelaxSafety", true)
                }
                if (tr.has("geminiIncludeAllLines")) {
                    prefs[Keys.GST_ALL_LINES] = tr.optBoolean("geminiIncludeAllLines", false)
                }
                if (tr.has("geminiSlidingContext")) {
                    prefs[Keys.GST_SLIDING_CTX] = tr.optBoolean("geminiSlidingContext", true)
                }
                if (tr.has("geminiCleanAds")) {
                    prefs[Keys.GST_CLEAN_ADS] = tr.optBoolean("geminiCleanAds", true)
                }
                if (tr.has("geminiBilingual")) {
                    prefs[Keys.GST_BILINGUAL] = tr.optBoolean("geminiBilingual", false)
                }
                if (tr.has("geminiTimeShiftSec")) {
                    prefs[Keys.GST_SHIFT_SEC] = tr.optString("geminiTimeShiftSec", "0").trim().ifBlank { "0" }
                }
                if (tr.has("geminiAutoUploadPikPak")) {
                    prefs[Keys.GST_AUTO_UP_PIKPAK] = tr.optBoolean("geminiAutoUploadPikPak", false)
                }
                if (tr.has("deeplModel")) {
                    val dm = tr.optString("deeplModel").trim()
                    if (dm.isNotBlank()) prefs[Keys.TR_DEEPL_MODEL] = dm
                }
                if (tr.has("deeplEndpoint")) {
                    prefs[Keys.TR_DEEPL_ENDPOINT] = tr.optString("deeplEndpoint").trim()
                }
                if (tr.has("openaiModel")) {
                    val om = tr.optString("openaiModel").trim()
                    if (om.isNotBlank()) prefs[Keys.TR_OPENAI_MODEL] = om
                }
                if (tr.has("openaiEndpoint")) {
                    prefs[Keys.TR_OPENAI_ENDPOINT] = tr.optString("openaiEndpoint").trim()
                        .ifBlank { GeminiEngine.DEFAULT_OPENAI_ENDPOINT }
                }
            }
        }

        return BackupImportResult(
            updatedPrefs = updatedPrefs,
            restoredSensitiveKeys = restoredKeys,
            rssFeedCount = importedRssCount,
            customTrackerCount = importedCustomTrackerCount
        )
    }

    object Keys {
        val APP_LANGUAGE = stringPreferencesKey("app_language")
        val SUB_TARGET_LANG = stringPreferencesKey("sub_target_lang")
        val APP_THEME_MODE = stringPreferencesKey("app_theme_mode")
        val BROWSER_TAB_SWIPE = booleanPreferencesKey("browser_tab_swipe")

        val DOH_PROVIDER = stringPreferencesKey("doh_provider")
        val APP_LOCK_ENABLED = booleanPreferencesKey("app_lock_enabled")
        val APP_LOCK_PIN_HASH = stringPreferencesKey("app_lock_pin_hash")
        val APP_LOCK_PIN_LEN = intPreferencesKey("app_lock_pin_len")
        val APP_LOCK_BIOMETRIC = booleanPreferencesKey("app_lock_biometric")
        val DISGUISE_MODE_ENABLED = booleanPreferencesKey("disguise_mode_enabled")
        val AUTO_CLEAR_ON_EXIT = booleanPreferencesKey("auto_clear_on_exit")

        val UTA_SORT = stringPreferencesKey("uta_sort")
        val UTA_HIDE_DEAD = booleanPreferencesKey("uta_hide_dead")
        val UTA_SOURCES = stringPreferencesKey("uta_sources")

        val GST_KEYS = stringPreferencesKey("gst_keys")
        val GST_MODEL = stringPreferencesKey("gst_model")
        val GST_MODELS_LIST = stringPreferencesKey("gst_models_list")
        val GST_TASK_MODE = stringPreferencesKey("gst_task_mode")
        val GST_AUTODETECT = booleanPreferencesKey("gst_autodetect")
        val GST_NAME_MODE = stringPreferencesKey("gst_name_mode")
        val GST_NOTES_TR = stringPreferencesKey("gst_notes_tr")
        val GST_NOTES_PL = stringPreferencesKey("gst_notes_pl")
        val GST_GLOSSARY = stringPreferencesKey("gst_glossary")
        val GST_SAFETY = booleanPreferencesKey("gst_safety")
        val GST_ALL_LINES = booleanPreferencesKey("gst_all_lines")
        val GST_SLIDING_CTX = booleanPreferencesKey("gst_sliding_ctx")
        val GST_CLEAN_ADS = booleanPreferencesKey("gst_clean_ads")
        val GST_BILINGUAL = booleanPreferencesKey("gst_bilingual")
        val GST_SHIFT_SEC = stringPreferencesKey("gst_shift_sec")
        val GST_AUTO_UP_PIKPAK = booleanPreferencesKey("gst_auto_up_pikpak")

        val PP_ACCESS = stringPreferencesKey("pp_access")
        val PP_REFRESH = stringPreferencesKey("pp_refresh")
        val PP_CLIENT_ID = stringPreferencesKey("pp_client_id")
        val PP_DEVICE_ID = stringPreferencesKey("pp_device_id")
        val PP_CAPTCHA = stringPreferencesKey("pp_captcha")
        val PP_CAPTCHA_EXP = longPreferencesKey("pp_captcha_exp")
        val PP_EMAIL = stringPreferencesKey("pp_email")
        val PP_FOLDER_ID = stringPreferencesKey("pp_folder_id")
        val PP_FOLDER_NAME = stringPreferencesKey("pp_folder_name")

        val BROWSER_LAST_URL = stringPreferencesKey("browser_last_url")
        val BROWSER_ADSHIELD = booleanPreferencesKey("browser_adshield")
        val BROWSER_ANTIPOPUP = booleanPreferencesKey("browser_antipopup")
        val BROWSER_BLOCKED_ADS_TOTAL = longPreferencesKey("browser_blocked_ads_total")
        val BROWSER_BOOKMARKS_V1 = stringPreferencesKey("browser_bookmarks_v1")

        val DOWNLOAD_HISTORY_V1 = stringPreferencesKey("download_history_v1")
        val TRACKER_AUTO_APPEND = booleanPreferencesKey("tracker_auto_append")
        val TRACKER_SOURCE_URL = stringPreferencesKey("tracker_source_url")
        val TRACKER_FETCHED_RAW = stringPreferencesKey("tracker_fetched_raw")
        val TRACKER_CUSTOM_RAW = stringPreferencesKey("tracker_custom_raw")
        val TRACKER_LAST_UPDATED_MS = longPreferencesKey("tracker_last_updated_ms")
        val RSS_FEEDS_V1 = stringPreferencesKey("rss_feeds_v1")
        val RSS_KEYWORDS_V1 = stringPreferencesKey("rss_keywords_v1")

        val SUB_PROVIDER = stringPreferencesKey("sub_provider")
        val SUB_AUTO_PAIR = booleanPreferencesKey("sub_auto_pair")
        val SUB_PAIR_FOLDER_URI = stringPreferencesKey("sub_pair_folder_uri")
        val SUB_PAIR_FOLDER_NAME = stringPreferencesKey("sub_pair_folder_name")
        val SUB_PAIR_VIDEO_NAME = stringPreferencesKey("sub_pair_video_name")

        val TR_PROVIDER = stringPreferencesKey("tr_provider")
        val TR_GOOGLE_ENDPOINT = stringPreferencesKey("tr_google_endpoint")
        val TR_GEMINI_ENDPOINT = stringPreferencesKey("tr_gemini_endpoint")
        val TR_DEEPL_KEY = stringPreferencesKey("tr_deepl_key")
        val TR_DEEPL_MODEL = stringPreferencesKey("tr_deepl_model")
        val TR_DEEPL_ENDPOINT = stringPreferencesKey("tr_deepl_endpoint")
        val TR_OPENAI_KEY = stringPreferencesKey("tr_openai_key")
        val TR_OPENAI_MODEL = stringPreferencesKey("tr_openai_model")
        val TR_OPENAI_ENDPOINT = stringPreferencesKey("tr_openai_endpoint")
    }

    companion object {
        @Volatile
        private var INSTANCE: SettingsRepository? = null

        fun getInstance(context: Context): SettingsRepository {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: SettingsRepository(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
}
