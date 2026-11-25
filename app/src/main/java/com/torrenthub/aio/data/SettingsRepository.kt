package com.torrenthub.aio.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.SharedPreferencesMigration
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.torrenthub.aio.model.PikPakCredentials
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
import java.io.IOException

enum class AppLanguage(val code: String, val nativeLabel: String, val flag: String) {
    ID("id", "Indonesia", "🇮🇩"),
    EN("en", "English", "🇬🇧"),
    ZH("zh", "中文 (Mandarin)", "🇨🇳"),
    JA("ja", "日本語 (Jepang)", "🇯🇵"),
    KO("ko", "한국어 (Korea)", "🇰🇷");

    companion object {
        fun fromCode(code: String?): AppLanguage =
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

    /* ================= PHASE 1 CORE SETTINGS ================= */
    val appLanguage: AppLanguage
        get() = AppLanguage.fromCode(current[Keys.APP_LANGUAGE])

    val subtitleTargetLang: AppLanguage
        get() = AppLanguage.fromCode(current[Keys.SUB_TARGET_LANG])

    val themeMode: AppThemeMode
        get() = AppThemeMode.fromId(current[Keys.APP_THEME_MODE])

    val browserTabSwipeEnabled: Boolean
        get() = current[Keys.BROWSER_TAB_SWIPE] ?: true

    fun setAppLanguage(lang: AppLanguage) {
        repoScope.launch {
            dataStore.edit { it[Keys.APP_LANGUAGE] = lang.code }
        }
    }

    fun setSubtitleTargetLang(lang: AppLanguage) {
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

    fun setBrowserBookmarksJson(json: String) {
        repoScope.launch {
            dataStore.edit { it[Keys.BROWSER_BOOKMARKS_V1] = json }
        }
    }

    object Keys {
        val APP_LANGUAGE = stringPreferencesKey("app_language")
        val SUB_TARGET_LANG = stringPreferencesKey("sub_target_lang")
        val APP_THEME_MODE = stringPreferencesKey("app_theme_mode")
        val BROWSER_TAB_SWIPE = booleanPreferencesKey("browser_tab_swipe")

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
        val BROWSER_BOOKMARKS_V1 = stringPreferencesKey("browser_bookmarks_v1")
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
