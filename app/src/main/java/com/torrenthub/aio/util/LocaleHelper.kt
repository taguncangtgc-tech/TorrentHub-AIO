package com.torrenthub.aio.util

import android.content.Context
import android.content.ContextWrapper
import android.content.res.AssetManager
import android.content.res.Configuration
import android.content.res.Resources
import android.os.LocaleList
import java.util.Locale

object LocaleHelper {

    fun localeForCode(langCode: String): Locale = when (langCode.lowercase(Locale.ROOT)) {
        "en" -> Locale.ENGLISH
        "zh" -> Locale.SIMPLIFIED_CHINESE
        "ja" -> Locale.JAPANESE
        "ko" -> Locale.KOREAN
        else -> Locale.forLanguageTag("id-ID")
    }

    fun wrapContext(baseContext: Context, langCode: String): Context {
        val locale = localeForCode(langCode)
        Locale.setDefault(locale)
        val config = Configuration(baseContext.resources.configuration)
        config.setLocales(LocaleList(locale))
        config.setLayoutDirection(locale)
        return baseContext.createConfigurationContext(config)
    }

    /**
     * Pembungkus Context khusus untuk hierarki Jetpack Compose yang mengganti Resources/Assets
     * sesuai bahasa terpilih tanpa memutus rantai ContextWrapper -> ComponentActivity.
     */
    fun wrapActivityContext(activityContext: Context, langCode: String): Context {
        val configContext = wrapContext(activityContext, langCode)
        return object : ContextWrapper(activityContext) {
            override fun getResources(): Resources = configContext.resources
            override fun getAssets(): AssetManager = configContext.assets
        }
    }

    fun getLocalizedResources(baseContext: Context, langCode: String): Resources =
        wrapContext(baseContext, langCode).resources
}
