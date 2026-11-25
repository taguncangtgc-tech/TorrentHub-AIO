package com.torrenthub.aio.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Contrast
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SaveAlt
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.torrenthub.aio.R
import com.torrenthub.aio.data.AppLanguage
import com.torrenthub.aio.data.AppThemeMode
import com.torrenthub.aio.data.GeminiEngine
import com.torrenthub.aio.data.SubtitleTargetLanguage
import com.torrenthub.aio.model.TranslationProvider
import com.torrenthub.aio.network.DohProvider
import com.torrenthub.aio.util.AppSecurityHelper
import com.torrenthub.aio.util.Formatters
import com.torrenthub.aio.util.StorageManagerHelper
import com.torrenthub.aio.util.TrackerManager

@Composable
fun uiLanguageDisplayLabel(lang: AppLanguage): String = when (lang) {
    AppLanguage.ID -> stringResource(R.string.lang_id)
    AppLanguage.EN -> stringResource(R.string.lang_en)
}

@Composable
fun subtitleLangDisplayLabel(lang: SubtitleTargetLanguage): String = when (lang) {
    SubtitleTargetLanguage.ID -> stringResource(R.string.lang_id)
    SubtitleTargetLanguage.EN -> stringResource(R.string.lang_en)
    SubtitleTargetLanguage.ZH -> stringResource(R.string.lang_zh)
    SubtitleTargetLanguage.ZH_TW -> stringResource(R.string.lang_zh_tw)
    SubtitleTargetLanguage.JA -> stringResource(R.string.lang_ja)
    SubtitleTargetLanguage.KO -> stringResource(R.string.lang_ko)
    SubtitleTargetLanguage.MS -> stringResource(R.string.lang_ms)
    SubtitleTargetLanguage.AR -> stringResource(R.string.lang_ar)
    SubtitleTargetLanguage.ES -> stringResource(R.string.lang_es)
    SubtitleTargetLanguage.FR -> stringResource(R.string.lang_fr)
    SubtitleTargetLanguage.DE -> stringResource(R.string.lang_de)
    SubtitleTargetLanguage.PT -> stringResource(R.string.lang_pt)
    SubtitleTargetLanguage.RU -> stringResource(R.string.lang_ru)
    SubtitleTargetLanguage.HI -> stringResource(R.string.lang_hi)
    SubtitleTargetLanguage.TH -> stringResource(R.string.lang_th)
    SubtitleTargetLanguage.VI -> stringResource(R.string.lang_vi)
    SubtitleTargetLanguage.TR -> stringResource(R.string.lang_tr)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsDialog(
    vm: AppViewModel,
    activeWebView: android.webkit.WebView? = null,
    onClearHistoryRequested: () -> Unit = { vm.clearSearchAndBrowserHistoryNow(webView = activeWebView) },
    onDismiss: () -> Unit
) {
    val p = AppTheme.palette
    val context = LocalContext.current
    val activity = AppSecurityHelper.findActivity(LocalView.current.context)
        ?: AppSecurityHelper.findActivity(context)
    val biometricAvailable = remember(activity) {
        activity != null && AppSecurityHelper.isBiometricAvailable(activity)
    }

    var showAutoPairVideoModal by remember { mutableStateOf(false) }
    var activePinDialogMode by remember { mutableStateOf<PinDialogMode?>(null) }
    val disableBioTitle = stringResource(R.string.applock_dialog_disable_title)
    val disableBioSub = stringResource(R.string.applock_prompt_subtitle)
    val usePinBtnTxt = stringResource(R.string.applock_btn_use_pin)

    LaunchedEffect(Unit) {
        vm.refreshStorageUsage()
    }

    val safFolderLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            vm.updateAutoPairFolder(uri)
        }
    }

    val backupExportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri != null) {
            vm.exportSettingsBackupToUri(uri, vm.includeSensitiveKeysOnExport)
        }
    }

    val backupImportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            vm.importSettingsBackupFromUri(uri)
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            color = p.background,
            shape = RoundedCornerShape(16.dp),
            border = BorderStroke(1.dp, p.borderColor),
            modifier = Modifier
                .fillMaxWidth(0.95f)
                .fillMaxHeight(0.92f)
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // Header
                Surface(
                    color = p.barSurface,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = stringResource(R.string.settings_title),
                                    fontWeight = FontWeight.ExtraBold,
                                    fontSize = 17.sp,
                                    color = p.textPrimary
                                )
                                Text(
                                    text = stringResource(R.string.settings_subtitle),
                                    fontSize = 11.5.sp,
                                    color = p.textMuted
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
                    }
                }

                // Body
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // Section 1: App Language & Subtitle Target Language
                    Surface(
                        color = p.cardSurface,
                        shape = RoundedCornerShape(12.dp),
                        border = p.cardBorder,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Text(
                                text = stringResource(R.string.settings_section_language),
                                fontWeight = FontWeight.ExtraBold,
                                fontSize = 14.sp,
                                color = p.accentBlue
                            )

                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(
                                    text = stringResource(R.string.settings_app_language),
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 13.5.sp,
                                    color = p.textPrimary
                                )
                                Text(
                                    text = stringResource(R.string.settings_app_language_desc),
                                    fontSize = 11.5.sp,
                                    color = p.textMuted
                                )
                                FlowRow(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    AppLanguage.entries.forEach { lang ->
                                        LanguageOptionChip(
                                            flag = lang.flag,
                                            label = uiLanguageDisplayLabel(lang),
                                            selected = vm.appLanguage == lang,
                                            onClick = { vm.updateAppLanguage(lang) }
                                        )
                                    }
                                }
                            }

                            HorizontalDivider(color = p.borderColor)

                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(
                                    text = stringResource(R.string.settings_sub_target_lang),
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 13.5.sp,
                                    color = p.textPrimary
                                )
                                Text(
                                    text = stringResource(R.string.settings_sub_target_lang_desc),
                                    fontSize = 11.5.sp,
                                    color = p.textMuted
                                )
                                FlowRow(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    SubtitleTargetLanguage.entries.forEach { lang ->
                                        LanguageOptionChip(
                                            flag = lang.flag,
                                            label = subtitleLangDisplayLabel(lang),
                                            selected = vm.subtitleTargetLang == lang,
                                            onClick = { vm.updateSubtitleTargetLang(lang) }
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // Section 1B: DNS-over-HTTPS (DoH) Network Anti-Block
                    Surface(
                        color = p.cardSurface,
                        shape = RoundedCornerShape(12.dp),
                        border = p.cardBorder,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Text(
                                text = stringResource(R.string.settings_section_doh),
                                fontWeight = FontWeight.ExtraBold,
                                fontSize = 14.sp,
                                color = p.accentBlue
                            )
                            Text(
                                text = stringResource(R.string.settings_doh_desc),
                                fontSize = 11.5.sp,
                                color = p.textMuted
                            )

                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                ProviderOptionCard(
                                    title = stringResource(R.string.doh_provider_off),
                                    subtitle = stringResource(R.string.doh_provider_off_desc),
                                    selected = vm.dohProvider == DohProvider.OFF,
                                    onClick = { vm.updateDohProvider(DohProvider.OFF) }
                                )
                                ProviderOptionCard(
                                    title = stringResource(R.string.doh_provider_cloudflare),
                                    subtitle = stringResource(R.string.doh_provider_cloudflare_desc),
                                    selected = vm.dohProvider == DohProvider.CLOUDFLARE,
                                    onClick = { vm.updateDohProvider(DohProvider.CLOUDFLARE) }
                                )
                                ProviderOptionCard(
                                    title = stringResource(R.string.doh_provider_google),
                                    subtitle = stringResource(R.string.doh_provider_google_desc),
                                    selected = vm.dohProvider == DohProvider.GOOGLE,
                                    onClick = { vm.updateDohProvider(DohProvider.GOOGLE) }
                                )
                                ProviderOptionCard(
                                    title = stringResource(R.string.doh_provider_quad9),
                                    subtitle = stringResource(R.string.doh_provider_quad9_desc),
                                    selected = vm.dohProvider == DohProvider.QUAD9,
                                    onClick = { vm.updateDohProvider(DohProvider.QUAD9) }
                                )
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.End
                            ) {
                                OutlinedButton(
                                    onClick = { vm.testDohConnection() },
                                    enabled = !vm.isTestingDoh
                                ) {
                                    Text(
                                        text = if (vm.isTestingDoh) stringResource(R.string.settings_doh_testing)
                                        else stringResource(R.string.settings_doh_btn_test),
                                        fontSize = 12.sp
                                    )
                                }
                            }
                        }
                    }

                    // Section 1C: App Lock (PIN / Biometric), Disguise Mode & Auto-Clear on Exit
                    Surface(
                        color = p.cardSurface,
                        shape = RoundedCornerShape(12.dp),
                        border = p.cardBorder,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Text(
                                text = stringResource(R.string.settings_section_security),
                                fontWeight = FontWeight.ExtraBold,
                                fontSize = 14.sp,
                                color = p.accentBlue
                            )
                            Text(
                                text = stringResource(R.string.settings_security_desc),
                                fontSize = 11.5.sp,
                                color = p.textMuted
                            )

                            fun requestToggleAppLock(enable: Boolean) {
                                if (enable) {
                                    if (vm.hasPinConfigured) {
                                        vm.enableAppLockWithExistingPin()
                                    } else {
                                        activePinDialogMode = PinDialogMode.SETUP_NEW
                                    }
                                } else {
                                    if (vm.appLockBiometricEnabled && biometricAvailable && activity != null) {
                                        AppSecurityHelper.authenticateBiometric(
                                            activity = activity,
                                            title = disableBioTitle,
                                            subtitle = disableBioSub,
                                            negativeButtonText = usePinBtnTxt,
                                            onSuccess = { vm.disableAppLockConfirmed() },
                                            onError = { activePinDialogMode = PinDialogMode.VERIFY_TO_DISABLE }
                                        )
                                    } else {
                                        activePinDialogMode = PinDialogMode.VERIFY_TO_DISABLE
                                    }
                                }
                            }

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp))
                                    .clickable { requestToggleAppLock(!vm.appLockEnabled) }
                                    .padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column(
                                    modifier = Modifier
                                        .weight(1f)
                                        .padding(end = 12.dp),
                                    verticalArrangement = Arrangement.spacedBy(2.dp)
                                ) {
                                    Text(
                                        text = stringResource(R.string.settings_applock_enable),
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 13.5.sp,
                                        color = p.textPrimary
                                    )
                                    Text(
                                        text = stringResource(R.string.settings_applock_enable_desc),
                                        fontSize = 11.5.sp,
                                        color = p.textMuted
                                    )
                                }
                                Switch(
                                    checked = vm.appLockEnabled,
                                    onCheckedChange = { requestToggleAppLock(it) }
                                )
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                OutlinedButton(
                                    onClick = {
                                        activePinDialogMode = if (vm.hasPinConfigured) {
                                            PinDialogMode.CHANGE_EXISTING
                                        } else {
                                            PinDialogMode.SETUP_NEW
                                        }
                                    },
                                    modifier = Modifier.weight(1f),
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp)
                                ) {
                                    Icon(Icons.Default.Key, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text(
                                        text = if (vm.hasPinConfigured) stringResource(R.string.settings_applock_btn_change_pin)
                                        else stringResource(R.string.settings_applock_btn_setup_pin),
                                        fontSize = 11.5.sp
                                    )
                                }
                                if (vm.appLockEnabled) {
                                    OutlinedButton(
                                        onClick = { vm.lockSessionNow() },
                                        modifier = Modifier.weight(1f),
                                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp)
                                    ) {
                                        Icon(Icons.Default.Lock, contentDescription = null, modifier = Modifier.size(16.dp))
                                        Spacer(Modifier.width(4.dp))
                                        Text(stringResource(R.string.settings_applock_btn_lock_now), fontSize = 11.5.sp)
                                    }
                                }
                            }

                            if (vm.appLockEnabled || vm.hasPinConfigured) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(10.dp))
                                        .clickable { vm.updateAppLockBiometric(!vm.appLockBiometricEnabled) }
                                        .padding(vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Column(
                                        modifier = Modifier
                                            .weight(1f)
                                            .padding(end = 12.dp),
                                        verticalArrangement = Arrangement.spacedBy(2.dp)
                                    ) {
                                        Text(
                                            text = stringResource(R.string.settings_applock_biometric),
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 13.sp,
                                            color = p.textPrimary
                                        )
                                        Text(
                                            text = if (biometricAvailable) {
                                                stringResource(R.string.settings_applock_biometric_desc)
                                            } else {
                                                stringResource(R.string.settings_applock_biometric_unavailable)
                                            },
                                            fontSize = 11.5.sp,
                                            color = if (biometricAvailable) p.textMuted else p.accentAmber
                                        )
                                    }
                                    Switch(
                                        checked = vm.appLockBiometricEnabled,
                                        onCheckedChange = { vm.updateAppLockBiometric(it) }
                                    )
                                }
                            }

                            HorizontalDivider(color = p.borderColor)

                            // Disguise Mode Toggle (FLAG_SECURE + Neutral Header)
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp))
                                    .clickable { vm.updateDisguiseMode(!vm.disguiseModeEnabled) }
                                    .padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column(
                                    modifier = Modifier
                                        .weight(1f)
                                        .padding(end = 12.dp),
                                    verticalArrangement = Arrangement.spacedBy(2.dp)
                                ) {
                                    Text(
                                        text = stringResource(R.string.settings_disguise_enable),
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 13.5.sp,
                                        color = p.textPrimary
                                    )
                                    Text(
                                        text = stringResource(R.string.settings_disguise_enable_desc),
                                        fontSize = 11.5.sp,
                                        color = p.textMuted
                                    )
                                }
                                Switch(
                                    checked = vm.disguiseModeEnabled,
                                    onCheckedChange = { vm.updateDisguiseMode(it) }
                                )
                            }

                            HorizontalDivider(color = p.borderColor)

                            // Auto-Clear Search & Browser History on Exit
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp))
                                    .clickable { vm.updateAutoClearOnExit(!vm.autoClearOnExit) }
                                    .padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column(
                                    modifier = Modifier
                                        .weight(1f)
                                        .padding(end = 12.dp),
                                    verticalArrangement = Arrangement.spacedBy(2.dp)
                                ) {
                                    Text(
                                        text = stringResource(R.string.settings_autoclear_exit),
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 13.5.sp,
                                        color = p.textPrimary
                                    )
                                    Text(
                                        text = stringResource(R.string.settings_autoclear_exit_desc),
                                        fontSize = 11.5.sp,
                                        color = p.textMuted
                                    )
                                }
                                Switch(
                                    checked = vm.autoClearOnExit,
                                    onCheckedChange = { vm.updateAutoClearOnExit(it) }
                                )
                            }

                            OutlinedButton(
                                onClick = {
                                    onClearHistoryRequested()
                                    vm.refreshStorageUsage()
                                },
                                modifier = Modifier.fillMaxWidth(),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.DeleteSweep,
                                    contentDescription = null,
                                    tint = p.accentRed,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    text = stringResource(R.string.settings_btn_clear_history_now),
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = p.accentRed
                                )
                            }
                        }
                    }

                    // Section 2: Custom Translation Provider & API Keys
                    Surface(
                        color = p.cardSurface,
                        shape = RoundedCornerShape(12.dp),
                        border = p.cardBorder,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Text(
                                text = stringResource(R.string.settings_section_translator),
                                fontWeight = FontWeight.ExtraBold,
                                fontSize = 14.sp,
                                color = p.accentBlue
                            )
                            Text(
                                text = stringResource(R.string.settings_translator_desc),
                                fontSize = 11.5.sp,
                                color = p.textMuted
                            )

                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                ProviderOptionCard(
                                    title = stringResource(R.string.tr_provider_google_free),
                                    subtitle = stringResource(R.string.tr_provider_google_free_desc),
                                    selected = vm.translationProvider == TranslationProvider.GOOGLE_FREE,
                                    onClick = { vm.updateTranslationProvider(TranslationProvider.GOOGLE_FREE) }
                                )
                                ProviderOptionCard(
                                    title = stringResource(R.string.tr_provider_gemini),
                                    subtitle = stringResource(R.string.tr_provider_gemini_desc),
                                    selected = vm.translationProvider == TranslationProvider.GEMINI,
                                    onClick = { vm.updateTranslationProvider(TranslationProvider.GEMINI) }
                                )
                                ProviderOptionCard(
                                    title = stringResource(R.string.tr_provider_deepl),
                                    subtitle = stringResource(R.string.tr_provider_deepl_desc),
                                    selected = vm.translationProvider == TranslationProvider.DEEPL,
                                    onClick = { vm.updateTranslationProvider(TranslationProvider.DEEPL) }
                                )
                                ProviderOptionCard(
                                    title = stringResource(R.string.tr_provider_openai),
                                    subtitle = stringResource(R.string.tr_provider_openai_desc),
                                    selected = vm.translationProvider == TranslationProvider.OPENAI,
                                    onClick = { vm.updateTranslationProvider(TranslationProvider.OPENAI) }
                                )
                            }

                            HorizontalDivider(color = p.borderColor)

                            // Contextual API Key, Model & Endpoint Configuration
                            when (vm.translationProvider) {
                                TranslationProvider.GOOGLE_FREE -> {
                                    Text(
                                        text = stringResource(R.string.tr_google_free_ready_banner),
                                        fontSize = 12.sp,
                                        color = p.accentGreen,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    OutlinedTextField(
                                        value = vm.googleTranslateEndpoint,
                                        onValueChange = {
                                            vm.googleTranslateEndpoint = it
                                            vm.saveTranslationProviderSettings()
                                        },
                                        modifier = Modifier.fillMaxWidth(),
                                        singleLine = true,
                                        label = { Text(stringResource(R.string.tr_endpoint_label)) },
                                        placeholder = { Text(GeminiEngine.DEFAULT_GOOGLE_GTX_ENDPOINT, fontSize = 11.5.sp) },
                                        textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace, fontSize = 11.5.sp)
                                    )
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
                                        maxLines = 3,
                                        label = { Text(stringResource(R.string.tr_api_key_optional_label, "Gemini")) },
                                        placeholder = { Text(stringResource(R.string.gemini_keys_placeholder), fontSize = 11.5.sp) },
                                        textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace, fontSize = 11.5.sp)
                                    )
                                    OutlinedTextField(
                                        value = vm.geminiModel,
                                        onValueChange = {
                                            vm.geminiModel = it
                                            vm.saveGeminiSettings()
                                        },
                                        modifier = Modifier.fillMaxWidth(),
                                        singleLine = true,
                                        label = { Text(stringResource(R.string.tr_model_custom_label, "Gemini")) },
                                        textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                                    )
                                    Row(
                                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        GeminiEngine.DEFAULT_MODELS.take(5).forEach { m ->
                                            FilterChip(
                                                selected = vm.geminiModel == m,
                                                onClick = {
                                                    vm.geminiModel = m
                                                    vm.saveGeminiSettings()
                                                },
                                                label = { Text(m, fontSize = 11.sp) }
                                            )
                                        }
                                    }
                                    OutlinedTextField(
                                        value = vm.geminiEndpoint,
                                        onValueChange = {
                                            vm.geminiEndpoint = it
                                            vm.saveTranslationProviderSettings()
                                        },
                                        modifier = Modifier.fillMaxWidth(),
                                        singleLine = true,
                                        label = { Text(stringResource(R.string.tr_endpoint_label)) },
                                        placeholder = { Text(GeminiEngine.DEFAULT_GEMINI_ENDPOINT, fontSize = 11.5.sp) },
                                        textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace, fontSize = 11.5.sp)
                                    )
                                }

                                TranslationProvider.DEEPL -> {
                                    OutlinedTextField(
                                        value = vm.deeplApiKey,
                                        onValueChange = {
                                            vm.deeplApiKey = it
                                            vm.saveTranslationProviderSettings()
                                        },
                                        modifier = Modifier.fillMaxWidth(),
                                        singleLine = true,
                                        label = { Text(stringResource(R.string.tr_api_key_optional_label, "DeepL")) },
                                        placeholder = { Text(stringResource(R.string.tr_api_key_deepl_placeholder), fontSize = 11.5.sp) },
                                        textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace, fontSize = 11.5.sp)
                                    )
                                    Row(
                                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        FilterChip(
                                            selected = vm.deeplModel == "prefer_quality_optimized",
                                            onClick = {
                                                vm.deeplModel = "prefer_quality_optimized"
                                                vm.saveTranslationProviderSettings()
                                            },
                                            label = { Text(stringResource(R.string.tr_deepl_quality_prefer), fontSize = 11.5.sp) }
                                        )
                                        FilterChip(
                                            selected = vm.deeplModel == "latency_optimized",
                                            onClick = {
                                                vm.deeplModel = "latency_optimized"
                                                vm.saveTranslationProviderSettings()
                                            },
                                            label = { Text(stringResource(R.string.tr_deepl_quality_latency), fontSize = 11.5.sp) }
                                        )
                                    }
                                    OutlinedTextField(
                                        value = vm.deeplEndpoint,
                                        onValueChange = {
                                            vm.deeplEndpoint = it
                                            vm.saveTranslationProviderSettings()
                                        },
                                        modifier = Modifier.fillMaxWidth(),
                                        singleLine = true,
                                        label = { Text(stringResource(R.string.tr_endpoint_label)) },
                                        placeholder = { Text(GeminiEngine.DEFAULT_DEEPL_FREE_ENDPOINT, fontSize = 11.5.sp) },
                                        textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace, fontSize = 11.5.sp)
                                    )
                                }

                                TranslationProvider.OPENAI -> {
                                    OutlinedTextField(
                                        value = vm.openaiApiKey,
                                        onValueChange = {
                                            vm.openaiApiKey = it
                                            vm.saveTranslationProviderSettings()
                                        },
                                        modifier = Modifier.fillMaxWidth(),
                                        singleLine = true,
                                        label = { Text(stringResource(R.string.tr_api_key_optional_label, "OpenAI / Compatible")) },
                                        placeholder = { Text(stringResource(R.string.tr_api_key_openai_placeholder), fontSize = 11.5.sp) },
                                        textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace, fontSize = 11.5.sp)
                                    )
                                    OutlinedTextField(
                                        value = vm.openaiModel,
                                        onValueChange = {
                                            vm.openaiModel = it
                                            vm.saveTranslationProviderSettings()
                                        },
                                        modifier = Modifier.fillMaxWidth(),
                                        singleLine = true,
                                        label = { Text(stringResource(R.string.tr_model_custom_label, "OpenAI")) },
                                        textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                                    )
                                    Row(
                                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        GeminiEngine.OPENAI_PRESET_MODELS.forEach { m ->
                                            FilterChip(
                                                selected = vm.openaiModel == m,
                                                onClick = {
                                                    vm.openaiModel = m
                                                    vm.saveTranslationProviderSettings()
                                                },
                                                label = { Text(m, fontSize = 11.sp) }
                                            )
                                        }
                                    }
                                    OutlinedTextField(
                                        value = vm.openaiEndpoint,
                                        onValueChange = {
                                            vm.openaiEndpoint = it
                                            vm.saveTranslationProviderSettings()
                                        },
                                        modifier = Modifier.fillMaxWidth(),
                                        singleLine = true,
                                        label = { Text(stringResource(R.string.tr_endpoint_label)) },
                                        placeholder = { Text(GeminiEngine.DEFAULT_OPENAI_ENDPOINT, fontSize = 11.5.sp) },
                                        textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace, fontSize = 11.5.sp)
                                    )
                                    Row(
                                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        GeminiEngine.OPENAI_PRESET_ENDPOINTS.forEach { (lbl, ep) ->
                                            FilterChip(
                                                selected = vm.openaiEndpoint.trimEnd('/') == ep,
                                                onClick = {
                                                    vm.openaiEndpoint = ep
                                                    vm.saveTranslationProviderSettings()
                                                },
                                                label = { Text(lbl, fontSize = 11.sp) }
                                            )
                                        }
                                    }
                                }
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.End
                            ) {
                                OutlinedButton(onClick = { vm.testGeminiKeys() }) {
                                    Text(stringResource(R.string.gemini_btn_test_conn), fontSize = 12.sp)
                                }
                            }
                        }
                    }

                    // Section 3: Auto-Pairing Subtitle & Video (SAF)
                    Surface(
                        color = p.cardSurface,
                        shape = RoundedCornerShape(12.dp),
                        border = p.cardBorder,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Text(
                                text = stringResource(R.string.settings_section_autopair),
                                fontWeight = FontWeight.ExtraBold,
                                fontSize = 14.sp,
                                color = p.accentBlue
                            )

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp))
                                    .clickable { vm.updateAutoPairEnabled(!vm.autoPairSubtitleVideo) }
                                    .padding(vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column(
                                    modifier = Modifier
                                        .weight(1f)
                                        .padding(end = 12.dp),
                                    verticalArrangement = Arrangement.spacedBy(2.dp)
                                ) {
                                    Text(
                                        text = stringResource(R.string.settings_autopair_enable),
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 13.5.sp,
                                        color = p.textPrimary
                                    )
                                    Text(
                                        text = stringResource(R.string.settings_autopair_enable_desc),
                                        fontSize = 11.5.sp,
                                        color = p.textMuted
                                    )
                                }
                                Switch(
                                    checked = vm.autoPairSubtitleVideo,
                                    onCheckedChange = { vm.updateAutoPairEnabled(it) }
                                )
                            }

                            Surface(
                                color = p.subCardSurface,
                                shape = RoundedCornerShape(10.dp),
                                border = BorderStroke(1.dp, p.borderColor),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(
                                    modifier = Modifier.padding(10.dp),
                                    verticalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Text(
                                        text = stringResource(R.string.settings_autopair_folder_label),
                                        fontSize = 11.5.sp,
                                        color = p.textMuted
                                    )
                                    Text(
                                        text = if (vm.autoPairFolderName.isNotBlank()) "📁 ${vm.autoPairFolderName}"
                                        else stringResource(R.string.settings_autopair_folder_unset),
                                        fontSize = 12.5.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = if (vm.autoPairFolderName.isNotBlank()) p.accentBlue else p.textSecondary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = stringResource(R.string.settings_autopair_video_label),
                                        fontSize = 11.5.sp,
                                        color = p.textMuted
                                    )
                                    Text(
                                        text = if (vm.autoPairVideoName.isNotBlank()) "🎬 ${vm.autoPairVideoName}"
                                        else stringResource(R.string.settings_autopair_video_auto),
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = p.textPrimary,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis
                                    )

                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        OutlinedButton(
                                            onClick = { safFolderLauncher.launch(null) },
                                            modifier = Modifier.weight(1f),
                                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp)
                                        ) {
                                            Icon(Icons.Default.FolderOpen, contentDescription = null, modifier = Modifier.size(16.dp))
                                            Spacer(Modifier.width(4.dp))
                                            Text(stringResource(R.string.settings_autopair_btn_pick_folder), fontSize = 11.5.sp)
                                        }
                                        OutlinedButton(
                                            onClick = { showAutoPairVideoModal = true },
                                            enabled = vm.autoPairFolderUri.isNotBlank(),
                                            modifier = Modifier.weight(1f),
                                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp)
                                        ) {
                                            Icon(Icons.Default.Movie, contentDescription = null, modifier = Modifier.size(16.dp))
                                            Spacer(Modifier.width(4.dp))
                                            Text(stringResource(R.string.settings_autopair_btn_pick_video), fontSize = 11.5.sp)
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // Section 4: Appearance & Theme (Light, Dark, AMOLED Black)
                    Surface(
                        color = p.cardSurface,
                        shape = RoundedCornerShape(12.dp),
                        border = p.cardBorder,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Text(
                                text = stringResource(R.string.settings_section_appearance),
                                fontWeight = FontWeight.ExtraBold,
                                fontSize = 14.sp,
                                color = p.accentBlue
                            )
                            Text(
                                text = stringResource(R.string.settings_theme_mode),
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.5.sp,
                                color = p.textPrimary
                            )
                            Text(
                                text = stringResource(R.string.settings_theme_desc),
                                fontSize = 11.5.sp,
                                color = p.textMuted
                            )

                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                ThemeOptionRow(
                                    title = stringResource(R.string.theme_light),
                                    subtitle = stringResource(R.string.theme_light_desc),
                                    swatchBg = Color(0xFFF8FAFC),
                                    swatchBorder = Color(0xFF94A3B8),
                                    icon = Icons.Default.LightMode,
                                    selected = vm.themeMode == AppThemeMode.LIGHT,
                                    onClick = { vm.updateThemeMode(AppThemeMode.LIGHT) }
                                )
                                ThemeOptionRow(
                                    title = stringResource(R.string.theme_dark),
                                    subtitle = stringResource(R.string.theme_dark_desc),
                                    swatchBg = Color(0xFF0F172A),
                                    swatchBorder = Color(0xFF38BDF8),
                                    icon = Icons.Default.DarkMode,
                                    selected = vm.themeMode == AppThemeMode.DARK,
                                    onClick = { vm.updateThemeMode(AppThemeMode.DARK) }
                                )
                                ThemeOptionRow(
                                    title = stringResource(R.string.theme_amoled),
                                    subtitle = stringResource(R.string.theme_amoled_desc),
                                    swatchBg = Color(0xFF000000),
                                    swatchBorder = Color(0xFF14B8A6),
                                    icon = Icons.Default.Contrast,
                                    selected = vm.themeMode == AppThemeMode.AMOLED,
                                    onClick = { vm.updateThemeMode(AppThemeMode.AMOLED) }
                                )
                            }
                        }
                    }

                    // Section 5: Browser Ad-Blocker, Anti-Popup & Swipe Control
                    Surface(
                        color = p.cardSurface,
                        shape = RoundedCornerShape(12.dp),
                        border = p.cardBorder,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Text(
                                text = stringResource(R.string.settings_section_browser),
                                fontWeight = FontWeight.ExtraBold,
                                fontSize = 14.sp,
                                color = p.accentBlue
                            )

                            // Ad-Blocker Toggle
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp))
                                    .clickable { vm.toggleBrowserAdShield(!vm.browserAdShieldEnabled) }
                                    .padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column(
                                    modifier = Modifier
                                        .weight(1f)
                                        .padding(end = 12.dp),
                                    verticalArrangement = Arrangement.spacedBy(2.dp)
                                ) {
                                    Text(
                                        text = stringResource(R.string.settings_adblock_enable),
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 13.5.sp,
                                        color = p.textPrimary
                                    )
                                    Text(
                                        text = stringResource(R.string.settings_adblock_enable_desc),
                                        fontSize = 11.5.sp,
                                        color = p.textMuted
                                    )
                                }
                                Switch(
                                    checked = vm.browserAdShieldEnabled,
                                    onCheckedChange = { vm.toggleBrowserAdShield(it) }
                                )
                            }

                            if (vm.browserAdShieldEnabled) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(10.dp))
                                        .clickable { vm.updateBrowserAntiPopupStrict(!vm.browserAntiPopupStrict) }
                                        .padding(vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Column(
                                        modifier = Modifier
                                            .weight(1f)
                                            .padding(end = 12.dp),
                                        verticalArrangement = Arrangement.spacedBy(2.dp)
                                    ) {
                                        Text(
                                            text = stringResource(R.string.settings_antipopup_strict),
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 13.sp,
                                            color = p.textPrimary
                                        )
                                        Text(
                                            text = stringResource(R.string.settings_antipopup_strict_desc),
                                            fontSize = 11.5.sp,
                                            color = p.textMuted
                                        )
                                    }
                                    Switch(
                                        checked = vm.browserAntiPopupStrict,
                                        onCheckedChange = { vm.updateBrowserAntiPopupStrict(it) }
                                    )
                                }
                            }

                            Surface(
                                color = p.subCardSurface,
                                shape = RoundedCornerShape(10.dp),
                                border = BorderStroke(1.dp, p.borderColor),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 12.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        text = stringResource(R.string.settings_adblock_stats, vm.browserBlockedAdsCount),
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = p.accentGreen,
                                        modifier = Modifier.weight(1f)
                                    )
                                    if (vm.browserBlockedAdsCount > 0) {
                                        TextButton(
                                            onClick = { vm.resetBlockedAdsCount() },
                                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                                        ) {
                                            Text(stringResource(R.string.settings_adblock_reset), fontSize = 11.5.sp)
                                        }
                                    }
                                }
                            }

                            HorizontalDivider(color = p.borderColor)

                            // Swipe Navigation Toggle
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp))
                                    .clickable { vm.updateBrowserTabSwipe(!vm.browserTabSwipeEnabled) }
                                    .padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column(
                                    modifier = Modifier
                                        .weight(1f)
                                        .padding(end = 12.dp),
                                    verticalArrangement = Arrangement.spacedBy(2.dp)
                                ) {
                                    Text(
                                        text = stringResource(R.string.settings_browser_swipe),
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 13.5.sp,
                                        color = p.textPrimary
                                    )
                                    Text(
                                        text = stringResource(R.string.settings_browser_swipe_desc),
                                        fontSize = 11.5.sp,
                                        color = p.textMuted
                                    )
                                }
                                Switch(
                                    checked = vm.browserTabSwipeEnabled,
                                    onCheckedChange = { vm.updateBrowserTabSwipe(it) }
                                )
                            }
                        }
                    }

                    // Section 6: Custom Tracker List Otomatis
                    val activeTrackerCount = vm.activeTrackersList.size
                    val fetchedCount = remember(vm.fetchedTrackersRaw) {
                        TrackerManager.parseTrackers(vm.fetchedTrackersRaw).size
                    }
                    Surface(
                        color = p.cardSurface,
                        shape = RoundedCornerShape(12.dp),
                        border = p.cardBorder,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Text(
                                text = stringResource(R.string.settings_section_trackers),
                                fontWeight = FontWeight.ExtraBold,
                                fontSize = 14.sp,
                                color = p.accentBlue
                            )
                            Text(
                                text = stringResource(R.string.settings_trackers_desc),
                                fontSize = 11.5.sp,
                                color = p.textMuted
                            )

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp))
                                    .clickable { vm.updateAutoAppendTrackers(!vm.autoAppendTrackers) }
                                    .padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column(
                                    modifier = Modifier
                                        .weight(1f)
                                        .padding(end = 12.dp),
                                    verticalArrangement = Arrangement.spacedBy(2.dp)
                                ) {
                                    Text(
                                        text = stringResource(R.string.settings_trackers_enable),
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 13.5.sp,
                                        color = p.textPrimary
                                    )
                                    Text(
                                        text = stringResource(R.string.settings_trackers_enable_desc, activeTrackerCount),
                                        fontSize = 11.5.sp,
                                        color = p.accentGreen
                                    )
                                }
                                Switch(
                                    checked = vm.autoAppendTrackers,
                                    onCheckedChange = { vm.updateAutoAppendTrackers(it) }
                                )
                            }

                            OutlinedTextField(
                                value = vm.trackerSourceUrl,
                                onValueChange = { vm.updateTrackerSourceUrl(it) },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                                label = { Text(stringResource(R.string.settings_trackers_url_label)) },
                                textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace, fontSize = 11.5.sp)
                            )

                            Row(
                                modifier = Modifier.horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                TrackerManager.PRESET_TRACKER_URLS.forEach { (lbl, presetUrl) ->
                                    FilterChip(
                                        selected = vm.trackerSourceUrl.trim() == presetUrl,
                                        onClick = { vm.updateTrackerSourceUrl(presetUrl) },
                                        label = { Text(lbl, fontSize = 11.sp) }
                                    )
                                }
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = if (vm.trackerLastUpdatedMs > 0L && fetchedCount > 0) {
                                        stringResource(
                                            R.string.settings_trackers_last_updated,
                                            Formatters.formatDate(vm.trackerLastUpdatedMs),
                                            fetchedCount
                                        )
                                    } else {
                                        stringResource(
                                            R.string.settings_trackers_never_updated,
                                            TrackerManager.BUILTIN_PUBLIC_TRACKERS.size
                                        )
                                    },
                                    fontSize = 11.sp,
                                    color = p.textMuted,
                                    modifier = Modifier.weight(1f)
                                )
                                Spacer(Modifier.width(8.dp))
                                Button(
                                    onClick = { vm.refreshTrackersFromUrl() },
                                    enabled = !vm.isUpdatingTrackers,
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                                ) {
                                    Text(
                                        text = if (vm.isUpdatingTrackers) stringResource(R.string.settings_trackers_updating)
                                        else stringResource(R.string.settings_trackers_btn_update),
                                        fontSize = 11.5.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }

                            OutlinedTextField(
                                value = vm.customTrackersRaw,
                                onValueChange = { vm.updateCustomTrackers(it) },
                                modifier = Modifier.fillMaxWidth(),
                                minLines = 2,
                                maxLines = 4,
                                label = { Text(stringResource(R.string.settings_trackers_custom_label)) },
                                placeholder = { Text(stringResource(R.string.settings_trackers_custom_placeholder), fontSize = 11.sp) },
                                textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace, fontSize = 11.5.sp)
                            )

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.End
                            ) {
                                TextButton(onClick = { vm.resetTrackersToDefault() }) {
                                    Text(stringResource(R.string.common_reset), fontSize = 12.sp)
                                }
                            }
                        }
                    }

                    // Section 7: Backup & Restore Settings to/from JSON (SAF)
                    val customTrackerCount = remember(vm.customTrackersRaw) {
                        TrackerManager.parseTrackers(vm.customTrackersRaw).size
                    }
                    Surface(
                        color = p.cardSurface,
                        shape = RoundedCornerShape(12.dp),
                        border = p.cardBorder,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Text(
                                text = stringResource(R.string.settings_section_backup),
                                fontWeight = FontWeight.ExtraBold,
                                fontSize = 14.sp,
                                color = p.accentBlue
                            )
                            Text(
                                text = stringResource(R.string.settings_backup_desc),
                                fontSize = 11.5.sp,
                                color = p.textMuted
                            )

                            Surface(
                                color = p.subCardSurface,
                                shape = RoundedCornerShape(10.dp),
                                border = BorderStroke(1.dp, p.borderColor),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    text = stringResource(
                                        R.string.settings_backup_scope_summary,
                                        vm.rssFeeds.size,
                                        customTrackerCount,
                                        vm.translationProviderDisplayName(vm.translationProvider)
                                    ),
                                    fontSize = 11.5.sp,
                                    color = p.textSecondary,
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                                )
                            }

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp))
                                    .clickable { vm.includeSensitiveKeysOnExport = !vm.includeSensitiveKeysOnExport }
                                    .padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column(
                                    modifier = Modifier
                                        .weight(1f)
                                        .padding(end = 12.dp),
                                    verticalArrangement = Arrangement.spacedBy(2.dp)
                                ) {
                                    Text(
                                        text = stringResource(R.string.settings_backup_include_keys),
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 13.sp,
                                        color = p.textPrimary
                                    )
                                    Text(
                                        text = if (vm.includeSensitiveKeysOnExport) {
                                            stringResource(R.string.settings_backup_include_keys_on_desc)
                                        } else {
                                            stringResource(R.string.settings_backup_include_keys_off_desc)
                                        },
                                        fontSize = 11.5.sp,
                                        color = if (vm.includeSensitiveKeysOnExport) p.accentAmber else p.textMuted
                                    )
                                }
                                Switch(
                                    checked = vm.includeSensitiveKeysOnExport,
                                    onCheckedChange = { vm.includeSensitiveKeysOnExport = it }
                                )
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Button(
                                    onClick = {
                                        backupExportLauncher.launch(StorageManagerHelper.buildDefaultBackupFileName())
                                    },
                                    enabled = !vm.isBackupBusy,
                                    modifier = Modifier.weight(1f),
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.SaveAlt,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(Modifier.width(6.dp))
                                    Text(
                                        text = stringResource(R.string.settings_backup_btn_export),
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                                OutlinedButton(
                                    onClick = {
                                        backupImportLauncher.launch(
                                            arrayOf("application/json", "text/plain", "application/octet-stream", "*/*")
                                        )
                                    },
                                    enabled = !vm.isBackupBusy,
                                    modifier = Modifier.weight(1f),
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.FileOpen,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(Modifier.width(6.dp))
                                    Text(
                                        text = stringResource(R.string.settings_backup_btn_import),
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                }
                            }
                        }
                    }

                    // Section 8: Storage Management & Cache Cleanup
                    val usage = vm.storageUsage
                    Surface(
                        color = p.cardSurface,
                        shape = RoundedCornerShape(12.dp),
                        border = p.cardBorder,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = stringResource(R.string.settings_section_storage),
                                    fontWeight = FontWeight.ExtraBold,
                                    fontSize = 14.sp,
                                    color = p.accentBlue,
                                    modifier = Modifier.weight(1f)
                                )
                                IconButton(
                                    onClick = { vm.refreshStorageUsage() },
                                    enabled = !vm.isScanningStorage && !vm.isCleaningStorage,
                                    modifier = Modifier.size(28.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Refresh,
                                        contentDescription = stringResource(R.string.cd_refresh_storage),
                                        tint = p.textSecondary,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                            Text(
                                text = stringResource(R.string.settings_storage_desc),
                                fontSize = 11.5.sp,
                                color = p.textMuted
                            )

                            Surface(
                                color = p.subCardSurface,
                                shape = RoundedCornerShape(10.dp),
                                border = BorderStroke(1.dp, p.borderColor),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(
                                    modifier = Modifier.padding(12.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text(
                                            text = stringResource(R.string.settings_storage_total_label),
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 12.5.sp,
                                            color = p.textPrimary
                                        )
                                        Text(
                                            text = if (vm.isScanningStorage) {
                                                stringResource(R.string.settings_storage_scanning)
                                            } else {
                                                StorageManagerHelper.formatStorageSize(usage.totalBytes)
                                            },
                                            fontWeight = FontWeight.ExtraBold,
                                            fontFamily = FontFamily.Monospace,
                                            fontSize = 13.sp,
                                            color = p.accentBlue
                                        )
                                    }

                                    if (usage.totalBytes > 0L) {
                                        val totalF = usage.totalBytes.toFloat().coerceAtLeast(1f)
                                        val wWeb = (usage.webViewCacheBytes.toFloat() / totalF).coerceAtLeast(0f)
                                        val wSub = (usage.subtitleTempBytes.toFloat() / totalF).coerceAtLeast(0f)
                                        val wHist = (usage.historyAndLogsBytes.toFloat() / totalF).coerceAtLeast(0f)
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .height(7.dp)
                                                .clip(RoundedCornerShape(4.dp))
                                                .background(p.borderColor)
                                        ) {
                                            if (wWeb > 0f) {
                                                Box(
                                                    modifier = Modifier
                                                        .weight(wWeb)
                                                        .fillMaxHeight()
                                                        .background(p.accentBlue)
                                                )
                                            }
                                            if (wSub > 0f) {
                                                Box(
                                                    modifier = Modifier
                                                        .weight(wSub)
                                                        .fillMaxHeight()
                                                        .background(p.accentGreen)
                                                )
                                            }
                                            if (wHist > 0f) {
                                                Box(
                                                    modifier = Modifier
                                                        .weight(wHist)
                                                        .fillMaxHeight()
                                                        .background(p.accentAmber)
                                                )
                                            }
                                        }
                                    }

                                    HorizontalDivider(color = p.borderColor)

                                    StorageBreakdownRow(
                                        dotColor = p.accentBlue,
                                        label = stringResource(R.string.settings_storage_webview_cache),
                                        sizeText = StorageManagerHelper.formatStorageSize(usage.webViewCacheBytes)
                                    )
                                    StorageBreakdownRow(
                                        dotColor = p.accentGreen,
                                        label = stringResource(R.string.settings_storage_subtitle_temp),
                                        sizeText = StorageManagerHelper.formatStorageSize(usage.subtitleTempBytes)
                                    )
                                    StorageBreakdownRow(
                                        dotColor = p.accentAmber,
                                        label = stringResource(R.string.settings_storage_history_logs),
                                        sizeText = StorageManagerHelper.formatStorageSize(usage.historyAndLogsBytes)
                                    )
                                }
                            }

                            Button(
                                onClick = { vm.clearWebViewAndSubtitleCache(activeWebView) },
                                enabled = !vm.isCleaningStorage,
                                modifier = Modifier.fillMaxWidth(),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 9.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.CleaningServices,
                                    contentDescription = null,
                                    modifier = Modifier.size(17.dp)
                                )
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    text = stringResource(R.string.settings_storage_btn_clear_cache),
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }

                            OutlinedButton(
                                onClick = { vm.resetAppHistoryAndLogs(activeWebView) },
                                enabled = !vm.isCleaningStorage,
                                modifier = Modifier.fillMaxWidth(),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.DeleteSweep,
                                    contentDescription = null,
                                    tint = p.accentRed,
                                    modifier = Modifier.size(17.dp)
                                )
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    text = stringResource(R.string.settings_storage_btn_reset_history),
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = p.accentRed
                                )
                            }
                        }
                    }
                }

                // Footer close button
                Surface(
                    color = p.barSurface,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column {
                        HorizontalDivider(color = p.borderColor, thickness = 1.dp)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 10.dp),
                            horizontalArrangement = Arrangement.End
                        ) {
                            Button(
                                onClick = onDismiss,
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Text(
                                    text = stringResource(R.string.common_close),
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (showAutoPairVideoModal) {
        AutoPairVideoPickerDialog(
            vm = vm,
            subtitleFileName = "subtitle.srt",
            subtitleToSaveContent = null,
            hintQuery = vm.subtitleQuery,
            onDismiss = { showAutoPairVideoModal = false }
        )
    }

    activePinDialogMode?.let { dialogMode ->
        PinSetupOrVerifyDialog(
            vm = vm,
            mode = dialogMode,
            onDismiss = { activePinDialogMode = null },
            onCompleted = { activePinDialogMode = null }
        )
    }
}

@Composable
private fun StorageBreakdownRow(
    dotColor: Color,
    label: String,
    sizeText: String
) {
    val p = AppTheme.palette
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(
            modifier = Modifier
                .weight(1f)
                .padding(end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(dotColor)
            )
            Text(
                text = label,
                fontSize = 12.sp,
                color = p.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Text(
            text = sizeText,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            fontFamily = FontFamily.Monospace,
            color = p.textPrimary
        )
    }
}

@Composable
private fun ProviderOptionCard(
    title: String,
    subtitle: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    val p = AppTheme.palette
    val primary = MaterialTheme.colorScheme.primary
    Surface(
        color = if (selected) primary.copy(alpha = if (p.isLight) 0.12f else 0.18f) else p.subCardSurface,
        shape = RoundedCornerShape(10.dp),
        border = BorderStroke(
            width = if (selected) 1.5.dp else 1.dp,
            color = if (selected) primary else p.borderColor
        ),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp,
                    color = if (selected) primary else p.textPrimary
                )
                Text(
                    text = subtitle,
                    fontSize = 11.sp,
                    color = p.textMuted
                )
            }
            if (selected) {
                Icon(
                    imageVector = Icons.Default.CheckCircle,
                    contentDescription = null,
                    tint = primary,
                    modifier = Modifier.size(20.dp)
                )
            } else {
                RadioButton(selected = false, onClick = onClick)
            }
        }
    }
}

@Composable
private fun LanguageOptionChip(
    flag: String,
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    val p = AppTheme.palette
    val primary = MaterialTheme.colorScheme.primary
    Surface(
        color = if (selected) primary.copy(alpha = if (p.isLight) 0.14f else 0.22f) else p.subCardSurface,
        shape = RoundedCornerShape(10.dp),
        border = BorderStroke(
            width = if (selected) 1.5.dp else 1.dp,
            color = if (selected) primary else p.borderColor
        ),
        modifier = Modifier.clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 11.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(text = flag, fontSize = 14.sp)
            Text(
                text = label,
                fontSize = 12.5.sp,
                fontWeight = if (selected) FontWeight.ExtraBold else FontWeight.Medium,
                color = if (selected) primary else p.textPrimary
            )
        }
    }
}

@Composable
private fun ThemeOptionRow(
    title: String,
    subtitle: String,
    swatchBg: Color,
    swatchBorder: Color,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    selected: Boolean,
    onClick: () -> Unit
) {
    val p = AppTheme.palette
    val primary = MaterialTheme.colorScheme.primary
    Surface(
        color = if (selected) primary.copy(alpha = if (p.isLight) 0.12f else 0.18f) else p.subCardSurface,
        shape = RoundedCornerShape(10.dp),
        border = BorderStroke(
            width = if (selected) 1.5.dp else 1.dp,
            color = if (selected) primary else p.borderColor
        ),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .clip(CircleShape)
                    .background(swatchBg)
                    .border(1.5.dp, swatchBorder, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = if (swatchBg == Color(0xFFF8FAFC)) Color(0xFF0F172A) else Color(0xFFF8FAFC),
                    modifier = Modifier.size(18.dp)
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.5.sp,
                    color = p.textPrimary
                )
                Text(
                    text = subtitle,
                    fontSize = 11.5.sp,
                    color = p.textMuted
                )
            }
            if (selected) {
                Icon(
                    imageVector = Icons.Default.CheckCircle,
                    contentDescription = null,
                    tint = primary,
                    modifier = Modifier.size(20.dp)
                )
            } else {
                RadioButton(selected = false, onClick = onClick)
            }
        }
    }
}
