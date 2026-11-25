package com.torrenthub.aio.util

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.Build
import android.os.CancellationSignal
import android.webkit.CookieManager
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewDatabase
import androidx.core.content.ContextCompat
import java.security.MessageDigest

object AppSecurityHelper {
    private const val PIN_SALT = "torrenthub_aio_pin_v1:"

    fun isValidPinFormat(pin: String): Boolean =
        pin.length in 4..6 && pin.all { it.isDigit() }

    fun hashPin(pin: String): String {
        val clean = pin.trim()
        val digest = MessageDigest.getInstance("SHA-256")
        val bytes = digest.digest((PIN_SALT + clean).toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    fun verifyPin(inputPin: String, expectedHash: String): Boolean {
        if (expectedHash.isBlank() || !isValidPinFormat(inputPin)) return false
        return hashPin(inputPin).equals(expectedHash, ignoreCase = true)
    }

    fun findActivity(context: Context): Activity? {
        var cur: Context? = context
        while (cur is ContextWrapper) {
            if (cur is Activity) return cur
            cur = cur.baseContext
        }
        return null
    }

    fun isBiometricAvailable(context: Context): Boolean {
        return try {
            when {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> {
                    val bm = context.getSystemService(BiometricManager::class.java)
                    bm?.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_WEAK) ==
                        BiometricManager.BIOMETRIC_SUCCESS
                }
                Build.VERSION.SDK_INT == Build.VERSION_CODES.Q -> {
                    val bm = context.getSystemService(BiometricManager::class.java)
                    @Suppress("DEPRECATION")
                    bm?.canAuthenticate() == BiometricManager.BIOMETRIC_SUCCESS
                }
                else -> {
                    @Suppress("DEPRECATION")
                    val fm = context.getSystemService(Context.FINGERPRINT_SERVICE) as? android.hardware.fingerprint.FingerprintManager
                    @Suppress("DEPRECATION")
                    fm != null && fm.isHardwareDetected && fm.hasEnrolledFingerprints()
                }
            }
        } catch (_: Exception) {
            false
        }
    }

    fun authenticateBiometric(
        activity: Activity,
        title: String,
        subtitle: String,
        negativeButtonText: String,
        onSuccess: () -> Unit,
        onError: (String) -> Unit = {},
        onFailed: () -> Unit = {}
    ): CancellationSignal? {
        if (!isBiometricAvailable(activity)) {
            onError("")
            return null
        }
        val signal = CancellationSignal()
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val executor = ContextCompat.getMainExecutor(activity)
                val prompt = BiometricPrompt.Builder(activity)
                    .setTitle(title)
                    .setSubtitle(subtitle)
                    .setNegativeButton(negativeButtonText, executor) { _, _ ->
                        onError("")
                    }
                    .build()
                prompt.authenticate(
                    signal,
                    executor,
                    object : BiometricPrompt.AuthenticationCallback() {
                        override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult?) {
                            onSuccess()
                        }

                        override fun onAuthenticationError(errorCode: Int, errString: CharSequence?) {
                            onError(errString?.toString().orEmpty())
                        }

                        override fun onAuthenticationFailed() {
                            onFailed()
                        }
                    }
                )
            } else {
                @Suppress("DEPRECATION")
                val fm = activity.getSystemService(Context.FINGERPRINT_SERVICE) as? android.hardware.fingerprint.FingerprintManager
                @Suppress("DEPRECATION")
                fm?.authenticate(
                    null,
                    signal,
                    0,
                    object : android.hardware.fingerprint.FingerprintManager.AuthenticationCallback() {
                        override fun onAuthenticationSucceeded(result: android.hardware.fingerprint.FingerprintManager.AuthenticationResult?) {
                            onSuccess()
                        }

                        override fun onAuthenticationError(errMsgId: Int, errString: CharSequence?) {
                            onError(errString?.toString().orEmpty())
                        }

                        override fun onAuthenticationFailed() {
                            onFailed()
                        }
                    },
                    null
                )
            }
            signal
        } catch (e: Exception) {
            onError(e.message.orEmpty())
            null
        }
    }

    fun clearWebViewSessionAndHistory(context: Context, webView: WebView?) {
        runCatching {
            webView?.stopLoading()
            webView?.loadUrl("about:blank")
            webView?.clearHistory()
            webView?.clearFormData()
            webView?.clearCache(true)
        }
        runCatching {
            WebViewDatabase.getInstance(context.applicationContext).clearFormData()
        }
        runCatching {
            WebStorage.getInstance().deleteAllData()
        }
        runCatching {
            val cm = CookieManager.getInstance()
            cm.removeAllCookies(null)
            cm.flush()
        }
    }
}
