# ==============================================================================
# TorrentHub AIO v1.4.0 - ProGuard / R8 Rules
# ==============================================================================

# Preserve source file & line numbers for readable crash diagnostics
-keepattributes SourceFile,LineNumberTable
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod,Exceptions

# ------------------------------------------------------------------------------
# 1. OkHttp 4, OkHttp DNS-over-HTTPS (DoH) & Okio
# ------------------------------------------------------------------------------
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn javax.annotation.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

-keep class okhttp3.** { *; }
-keep interface okhttp3.** { *; }
-keep class okhttp3.dnsoverhttps.** { *; }
-keep class okio.** { *; }

# ------------------------------------------------------------------------------
# 2. Retrofit & Gson (Safe rules for reflection/type tokens if present/minified)
# ------------------------------------------------------------------------------
-dontwarn retrofit2.**
-dontwarn com.google.gson.**

-keep class retrofit2.** { *; }
-keepclasseswithmembers class * {
    @retrofit2.http.* <methods>;
}
-keep class com.google.gson.** { *; }
-keep class com.google.gson.reflect.TypeToken
-keep class * extends com.google.gson.reflect.TypeToken
-keepclassmembers,allowobfuscation class * {
    @com.google.gson.annotations.SerializedName <fields>;
}

# ------------------------------------------------------------------------------
# 3. AndroidX DataStore (Preferences & Proto internals)
# ------------------------------------------------------------------------------
-dontwarn androidx.datastore.**
-keep class androidx.datastore.** { *; }
-keepclassmembers class * extends androidx.datastore.preferences.protobuf.GeneratedMessageLite {
    <fields>;
    <methods>;
}

# ------------------------------------------------------------------------------
# 4. Biometric / Fingerprint Authentication Callbacks
# ------------------------------------------------------------------------------
-dontwarn android.hardware.biometrics.**
-dontwarn android.hardware.fingerprint.**
-dontwarn androidx.biometric.**

-keep class androidx.biometric.** { *; }
-keep class * extends android.hardware.biometrics.BiometricPrompt$AuthenticationCallback {
    <methods>;
}
-keep class * extends android.hardware.fingerprint.FingerprintManager$AuthenticationCallback {
    <methods>;
}

# ------------------------------------------------------------------------------
# 5. Kotlin Coroutines & Flow
# ------------------------------------------------------------------------------
-dontwarn kotlinx.coroutines.**
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keepclassmembers class kotlinx.coroutines.** {
    volatile <fields>;
}

# ------------------------------------------------------------------------------
# 6. WebView JavascriptInterface Bridge (Userscript GM_* & Browser Detector)
# ------------------------------------------------------------------------------
-keepattributes JavascriptInterface
-keep @interface android.webkit.JavascriptInterface
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
-keep class androidx.webkit.** { *; }
-dontwarn androidx.webkit.**

# ------------------------------------------------------------------------------
# 7. Jsoup & Coil ImageLoader
# ------------------------------------------------------------------------------
-dontwarn org.jsoup.**
-keeppackagenames org.jsoup.nodes
-keep class org.jsoup.** { *; }
-dontwarn coil.**

# ------------------------------------------------------------------------------
# 8. Application Models, Enums & ViewModels
# ------------------------------------------------------------------------------
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

-keep class com.torrenthub.aio.model.** { *; }
-keep class com.torrenthub.aio.network.DohProvider { *; }
-keep class com.torrenthub.aio.data.AppLanguage { *; }
-keep class com.torrenthub.aio.data.SubtitleTargetLanguage { *; }
-keep class com.torrenthub.aio.data.AppThemeMode { *; }
-keep class * extends androidx.lifecycle.ViewModel {
    <init>();
    <init>(android.app.Application);
}
