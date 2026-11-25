package com.torrenthub.aio.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Contrast
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.torrenthub.aio.R
import com.torrenthub.aio.data.AppLanguage
import com.torrenthub.aio.data.AppThemeMode

@Composable
fun languageDisplayLabel(lang: AppLanguage): String = when (lang) {
    AppLanguage.ID -> stringResource(R.string.lang_id)
    AppLanguage.EN -> stringResource(R.string.lang_en)
    AppLanguage.ZH -> stringResource(R.string.lang_zh)
    AppLanguage.JA -> stringResource(R.string.lang_ja)
    AppLanguage.KO -> stringResource(R.string.lang_ko)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsDialog(
    vm: AppViewModel,
    onDismiss: () -> Unit
) {
    val p = AppTheme.palette

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
                .fillMaxHeight(0.9f)
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

                            // 1A: App UI Language
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
                                            label = languageDisplayLabel(lang),
                                            selected = vm.appLanguage == lang,
                                            onClick = { vm.updateAppLanguage(lang) }
                                        )
                                    }
                                }
                            }

                            HorizontalDivider(color = p.borderColor)

                            // 1B: Subtitle Translation Target Language
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
                                    AppLanguage.entries.forEach { lang ->
                                        LanguageOptionChip(
                                            flag = lang.flag,
                                            label = languageDisplayLabel(lang),
                                            selected = vm.subtitleTargetLang == lang,
                                            onClick = { vm.updateSubtitleTargetLang(lang) }
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // Section 2: Appearance & Theme (Light, Dark, AMOLED Black)
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

                    // Section 3: Browser Swipe Navigation Control
                    Surface(
                        color = p.cardSurface,
                        shape = RoundedCornerShape(12.dp),
                        border = p.cardBorder,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = stringResource(R.string.settings_section_browser),
                                fontWeight = FontWeight.ExtraBold,
                                fontSize = 14.sp,
                                color = p.accentBlue
                            )
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
